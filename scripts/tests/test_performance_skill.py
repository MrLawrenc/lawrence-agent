import argparse
import contextlib
import importlib.util
import io
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import time
import threading
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / 'skills/java-performance/scripts'
sys.path.insert(0, str(SCRIPTS))
import runtime
import perf

FAKE_CLI = '''#!/usr/bin/env python3
import json,os,pathlib,signal,sys,time
a=sys.argv[1:]
def option(name): return a[a.index(name)+1]
def emit(v): print(json.dumps(v),flush=True)
if '--help' in a: print('scope preview session show ai verify diag record --rolling evidence query jvm identity monitor requests monitor diagnose monitor distributed');sys.exit(0)
if a[:2]==['jvm','list']: emit({'jvms':[{'pid':os.environ.get('SKILL_TEST_PID','0'),'displayName':'owned fixture'}]});sys.exit(0)
if a[:2]==['config','validate']: emit({'valid':True});sys.exit(0)
if a[:2]==['session','show']:
 if os.environ.get('SKILL_TEST_SUMMARY_FAIL'): print('metadata unavailable',file=sys.stderr);sys.exit(3)
 p=pathlib.Path(option('--session'));r=json.loads((p/'reports/performance-report.json').read_text());emit(r);sys.exit(0)
root=pathlib.Path(option('--output'))/'stamp/session-fixture';root.mkdir(parents=True)
(root/'reports').mkdir();(root/'diagnostics').mkdir()
(root/'session.json').write_text(json.dumps({'schemaVersion':1,'application':'agent-monitor','sessionId':'fixture','startedAt':'2026-10-27T00:00:00Z'}))
report={'sessionId':'fixture','status':'COMPLETED','captureComplete':True,'analysisReady':True,'observedRootTraces':1,'captureQuality':{}}
manifest={'schemaVersion':1,'sessionId':'fixture','status':'COMPLETED','samples':1,'threadSnapshots':1,'errors':[]}
stopped=[False]
def stop(*_): stopped[0]=True
signal.signal(signal.SIGTERM,stop)
if '--rolling' in a and os.environ.get('SKILL_TEST_CHECKPOINT_GATE'):
 window=root/'windows/stamp/session-window';(window/'reports').mkdir(parents=True);(window/'diagnostics').mkdir()
 (window/'session.json').write_text((root/'session.json').read_text())
 partial=dict(report,captureComplete=False,analysisReady=False)
 (window/'reports/performance-report.json').write_text(json.dumps(partial))
 (window/'diagnostics/jfr-checkpoint.json').write_text('{"status":"PENDING"}')
 (root/'rolling-capture.json').write_text(json.dumps({'windows':[{'sequence':1,'sessionDirectory':str(window),'checkpointRequired':True}]}))
 gate=pathlib.Path(os.environ['SKILL_TEST_CHECKPOINT_GATE']);release=pathlib.Path(os.environ['SKILL_TEST_NATIVE_RELEASE']);deadline=time.time()+15
 while not gate.exists() and not stopped[0] and time.time()<deadline:time.sleep(.02)
 (window/'diagnostics/jfr-analysis.json').write_text('{"eventCounts":{"jdk.ExecutionSample":1}}')
 (window/'diagnostics/jfr-checkpoint.json').write_text('{"status":"COMPLETED"}')
 while not release.exists() and not stopped[0] and time.time()<deadline:time.sleep(.02)
if os.environ.get('SKILL_TEST_BYTES'): (root/'payload.bin').write_bytes(b'x'*int(os.environ['SKILL_TEST_BYTES']))
deadline=time.time()+float(os.environ.get('SKILL_TEST_DELAY','.5'))
while time.time()<deadline and not stopped[0]: time.sleep(.02)
(root/'reports/performance-report.json').write_text(json.dumps(report))
(root/'diagnostics/manifest.json').write_text(json.dumps(manifest))
bad=os.environ.get('SKILL_TEST_RESTORE')=='false'
emit({'sessionDirectory':str(root),'restored':not bad})
sys.exit(4 if bad else 0)
'''


class PerformanceSkillTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='performance skill ')
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name).resolve()
        self.fake = self.root / 'fake-monitor'
        self.fake.write_text(FAKE_CLI)
        self.fake.chmod(0o755)

    def job(self, **updates):
        root = self.root / 'job'
        root.mkdir()
        job = dict(status='pending', workerPid=None, mode='capture', target=runtime.identity(os.getppid()),
                   requestedDurationMillis=1000, windowMillis=500, maxBytes=32*1024*1024,
                   cliCommand=[sys.executable, str(self.fake)], configuration=None,
                   completedWindows=0, degradedWindows=0, currentWindow=None, continuous=False,
                   coverageNote='Independent windows')
        job.update(updates)
        perf.save_job(root, job)
        return root

    def run_worker(self, root):
        with patch.object(perf.signal, 'signal'):
            perf.worker_main(root)
        return runtime.read_json(root / 'job.json')

    def test_duration_bounds_and_long_hour_support(self):
        self.assertEqual(7200, runtime.duration('2h'))
        self.assertEqual(.5, runtime.duration('500ms'))
        for text in ['0s','8d','1e4s','-2s','forever']:
            with self.assertRaises(ValueError): runtime.duration(text)
        with self.assertRaises(ValueError): runtime.duration('61m',3600)

    def test_install_is_content_addressed_and_detects_tampering(self):
        jar = self.root / 'local.jar';jar.write_bytes(b'local trusted test artifact')
        cache = self.root / 'cache'
        with patch.object(runtime,'java',return_value='/example/java'), patch.object(runtime,'probe') as probe:
            result = runtime.ensure_cli(cli=str(jar), install_root=cache)
            self.assertEqual(runtime.digest(jar), result['sha256'])
            self.assertEqual(jar.read_bytes(), pathlib.Path(result['jar']).read_bytes())
            self.assertTrue(pathlib.Path(result['launcher']).is_file())
            cached = runtime.ensure_cli(install_root=cache)
            self.assertEqual('managed-cache', cached['source'])
            pathlib.Path(result['jar']).write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'integrity'): runtime.ensure_cli(install_root=cache)
            self.assertGreaterEqual(probe.call_count,2)

    def test_hash_mismatch_never_executes_and_remote_requires_pin(self):
        jar = self.root/'local.jar';jar.write_bytes(b'jar')
        with patch.object(runtime,'probe') as probe:
            with self.assertRaisesRegex(ValueError,'mismatch'):
                runtime.ensure_cli(cli=str(jar),install_root=self.root/'cache',sha256='0'*64)
            with self.assertRaisesRegex(ValueError,'SHA-256'):
                runtime.ensure_cli(install_root=self.root/'cache',url='https://example.invalid/tool.jar')
            probe.assert_not_called()

    def test_missing_offline_source_is_actionable_without_network(self):
        with patch.object(runtime,'source_repository',return_value=None), patch.object(runtime.shutil,'which',return_value=None):
            with self.assertRaisesRegex(ValueError,'No public release URL'):
                runtime.ensure_cli(install_root=self.root/'cache')

    def test_external_cli_probe_and_child_arguments_preserve_spaces(self):
        result = runtime.ensure_cli(cli=str(self.fake))
        self.assertEqual([str(self.fake)],result['command'])
        self.assertFalse(result['managed'])

    def test_restore_failure_stops_after_one_window_and_keeps_artifacts(self):
        root = self.job(requestedDurationMillis=5000)
        with patch.dict(os.environ, {'SKILL_TEST_RESTORE':'false','SKILL_TEST_DELAY':'.2'}):
            job=self.run_worker(root)
        self.assertEqual('restore_unconfirmed',job['status'])
        self.assertEqual(1,job['completedWindows'])
        record=runtime.read_json(root/'windows/000001/result.json')
        self.assertFalse(record['accepted']);self.assertFalse(record['restored'])
        self.assertTrue(pathlib.Path(record['sessionDirectory']).is_dir())

    def test_metadata_failure_still_exposes_finalized_window_to_collect(self):
        root = self.job(requestedDurationMillis=5000)
        with patch.dict(os.environ, {'SKILL_TEST_SUMMARY_FAIL':'true','SKILL_TEST_DELAY':'.2'}):
            job=self.run_worker(root)
        self.assertEqual('failed',job['status'])
        self.assertEqual(1,job['completedWindows'])
        batch=perf.collect(argparse.Namespace(job=str(root),consumer='ai',after=None,limit=10))
        self.assertEqual(1,len(batch['windows']))
        record=batch['windows'][0]
        self.assertTrue(record['restored']);self.assertFalse(record['accepted'])
        self.assertIn('summaryError',record)
        self.assertTrue(pathlib.Path(record['sessionDirectory']).is_dir())

    def test_soft_quota_keeps_existing_results_and_never_deletes(self):
        root=self.job(maxBytes=1024*1024,requestedDurationMillis=5000)
        with patch.dict(os.environ, {'SKILL_TEST_BYTES':str(2*1024*1024),'SKILL_TEST_DELAY':'.2'}):
            job=self.run_worker(root)
        self.assertEqual('quota_reached',job['status'])
        self.assertEqual(1,job['completedWindows'])
        self.assertTrue(next(root.glob('windows/*/captures/*/session-*/payload.bin')).exists())

    def test_changed_process_identity_prevents_any_cli_start(self):
        root=self.job()
        with patch.object(perf,'identity',return_value={'pid':1,'startToken':'different'}), patch.object(perf.subprocess,'Popen') as popen:
            job=self.run_worker(root)
        self.assertEqual('target_changed',job['status']);popen.assert_not_called()

    def test_native_capture_rechecks_identity_before_spawning_cli(self):
        root=self.job(nativeRolling=True)
        with patch.object(perf,'identity',return_value={'pid':1,'startToken':'different'}),patch.object(perf.subprocess,'Popen') as popen:
            job=self.run_worker(root)
        self.assertEqual('target_changed',job['status']);popen.assert_not_called()

    def test_native_window_is_consumable_only_after_jfr_checkpoint_publication(self):
        root=self.job(nativeRolling=True,requestedDurationMillis=60000)
        config=root/'config.yml';config.write_text('fixture');job=runtime.read_json(root/'job.json');job['configuration']=str(config);runtime.atomic_json(root/'job.json',job)
        gate=root/'publish-jfr';release=root/'finish-native';worker=threading.Thread(target=self.run_worker,args=(root,))
        def wait_for(predicate):
            deadline=time.time()+12
            while time.time()<deadline:
                if predicate():return
                time.sleep(.05)
            self.fail('Timed out waiting for native fixture state')
        with patch.dict(os.environ,{'SKILL_TEST_CHECKPOINT_GATE':str(gate),'SKILL_TEST_NATIVE_RELEASE':str(release),'SKILL_TEST_DELAY':'.1'}):
            worker.start()
            try:
                wait_for(lambda:bool(list(root.glob('native/captures/*/session-*/rolling-capture.json'))))
                time.sleep(2.2)
                self.assertEqual(0,runtime.read_json(root/'job.json')['completedWindows'])
                gate.touch();wait_for(lambda:runtime.read_json(root/'job.json')['completedWindows']==1)
                batch=perf.collect(argparse.Namespace(job=str(root),consumer='ai',after=None,limit=10))
                self.assertIn('diagnostics/jfr-analysis.json',batch['windows'][0]['analysisInputs'])
                self.assertEqual('COMPLETED',batch['windows'][0]['jfrCheckpoint']['status'])
            finally:
                gate.touch();release.touch();worker.join(12)
            self.assertFalse(worker.is_alive())
            self.assertEqual('completed',runtime.read_json(root/'job.json')['status'])

    def test_stopped_job_does_not_start_a_window(self):
        root=self.job();runtime.atomic_json(root/'stop.request',{'requested':True})
        job=self.run_worker(root)
        self.assertEqual('stopped',job['status']);self.assertEqual(0,job['completedWindows'])

    def test_collect_is_replayable_until_ack_and_cursor_is_bounded(self):
        root=self.job()
        job=self.run_worker(root)
        self.assertGreaterEqual(job['completedWindows'],1)
        args=argparse.Namespace(job=str(root),consumer='ai',after=None,limit=1)
        first=perf.collect(args);second=perf.collect(args)
        self.assertEqual(first['nextCursor'],second['nextCursor'])
        self.assertEqual(0,first['afterCursor'])
        runtime.atomic_json(root/'consumers/ai.json',{'cursor':first['nextCursor']})
        self.assertEqual(first['nextCursor'],perf.collect(args)['afterCursor'])
        args.after=999
        with self.assertRaises(ValueError):perf.collect(args)
        with self.assertRaises(ValueError):perf.consumer_path(root,'../outside')

    def test_stale_heartbeat_is_reported_without_restarting(self):
        root=self.job();job=runtime.read_json(root/'job.json');job.update(status='running',heartbeatMillis=perf.now()-60000)
        runtime.atomic_json(root/'job.json',job)
        result=perf.status(root)
        self.assertTrue(result['workerUnresponsive']);self.assertEqual('running',result['status'])
        self.assertEqual(job,runtime.read_json(root/'job.json'))

    def test_collect_rejects_window_path_escaping_job(self):
        root=self.job();window=root/'windows/000001';window.mkdir(parents=True)
        runtime.atomic_json(window/'result.json',{'sequence':1,'sessionDirectory':str(self.root)})
        job=runtime.read_json(root/'job.json');job['completedWindows']=1;perf.save_job(root,job)
        with self.assertRaisesRegex(ValueError,'escapes'):
            perf.collect(argparse.Namespace(job=str(root),consumer='ai',after=None,limit=10))


if __name__=='__main__': unittest.main()
