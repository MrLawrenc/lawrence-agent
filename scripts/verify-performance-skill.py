#!/usr/bin/env python3
"""Real-JVM smoke for the portable skill; only owns its disposable Java fixture."""
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'skills/java-performance/scripts'))
import runtime

JAVA_SOURCE = '''package skillfixtures;
public class SkillTarget {
  static volatile long value;
  public static void main(String[] args) throws Exception {
    System.out.println("READY"); System.out.flush();
    while(!java.nio.file.Files.exists(java.nio.file.Path.of(args[0]))) { business(); Thread.sleep(40); }
  }
  public static void business() throws Exception { long n=value; for(int i=0;i<5000;i++)n=n*31+i;value=n;Thread.sleep(4); }
}
'''


def main():
    base = ROOT/'monitor-cli/build/skill-verification'
    base.mkdir(parents=True, exist_ok=True)
    output = pathlib.Path(tempfile.mkdtemp(prefix='run-',dir=base))
    skill = output/'portable-skill'
    shutil.copytree(ROOT/'skills/java-performance',skill,ignore=shutil.ignore_patterns('__pycache__'))
    script = skill/'scripts/perf.py'
    installed = output/'installed'
    command = [sys.executable,str(script)]
    env = dict(os.environ)
    env.pop('MONITOR_CLI',None)
    def call(name,args,expected=0):
        result = subprocess.run(command+list(map(str,args)),capture_output=True,text=True,env=env,timeout=40)
        (output/(name+'.stdout.json')).write_text(result.stdout)
        (output/(name+'.stderr.log')).write_text(result.stderr)
        if result.returncode != expected: raise AssertionError(name+': '+result.stderr+' '+result.stdout[:2000])
        return json.loads(result.stdout)
    def wait_job(job,timeout=75):
        end=time.time()+timeout
        while time.time()<end:
            state=runtime.read_json(pathlib.Path(job)/'job.json')
            if state['status'] in {'completed','degraded','stopped','failed','restore_unconfirmed','quota_reached','target_changed','interrupted'}: return state
            time.sleep(.2)
        raise AssertionError('Job timeout: '+str(job))
    owned=None;jobs=[]
    try:
        artifact=ROOT/'monitor-cli/build/libs/monitor-cli-all.jar'
        first=call('install',['ensure','--cli',artifact,'--install-root',installed])
        assert first['source']=='installed' and first['managed']
        cached=call('cache',['ensure','--install-root',installed])
        assert cached['source']=='managed-cache' and cached['sha256']==first['sha256']
        source=output/'SkillTarget.java';source.write_text(JAVA_SOURCE)
        classes=output/'classes';classes.mkdir()
        subprocess.run(['javac','-d',str(classes),str(source)],check=True,timeout=30,capture_output=True)
        flag=output/'target-stop'
        owned=subprocess.Popen([runtime.java(),'-Djava.rmi.server.hostname=127.0.0.1','-cp',str(classes),'skillfixtures.SkillTarget',str(flag)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        assert owned.stdout.readline().strip()=='READY'
        start_args=['start','--pid',owned.pid,'--install-root',installed,'--work-dir',output/'jobs']
        diag=call('diagnostic-start',start_args+['--duration','10s','--window','2s'])
        jobs.append(diag['jobDirectory']);diagnostic=wait_job(diag['jobDirectory'])
        assert diagnostic['status']=='completed',diagnostic
        assert diagnostic['completedWindows']>=2,diagnostic
        cfg=output/'monitor.yml'
        cfg.write_text('''version: 2
scope:
  includeClasses: [skillfixtures.SkillTarget]
  excludeMethods: [main]
sampling:
  ratePercent: 100
output:
  capture:
    arguments: false
    returnValue: false
    sqlParameters: false
dependencies:
  jdbc: false
  http: false
diagnostics:
  enabled: true
  jfr: true
overhead:
  enabled: false
''')
        capture=call('capture-start',start_args+['--mode','capture','--config',cfg,'--duration','15s','--window','5s'])
        jobs.append(capture['jobDirectory']);captured=wait_job(capture['jobDirectory'])
        assert captured['status']=='completed',captured
        assert captured['completedWindows']>=2,captured
        args=['collect','--job',capture['jobDirectory'],'--limit','1']
        batch=call('collect',args);repeat=call('collect-repeat',args)
        assert batch['nextCursor']==repeat['nextCursor']==1
        window=batch['windows'][0]
        assert captured['nativeRolling'] and captured['restored']
        assert window['restored'] is None and not window['quality']['captureComplete']
        assert window['jfrCheckpoint']['status']=='COMPLETED',window
        session=pathlib.Path(window['sessionDirectory'])
        cli_args=['run','--install-root',installed,'--']
        call('evidence-index',cli_args+['evidence','index','--session',session,'--json'])
        indexed=call('evidence-query',cli_args+['evidence','query','--session',session,'--kind','span','--limit','1','--json'])
        evidence=indexed['matches'][0]
        source=call('evidence-get',cli_args+['evidence','get','--session',session,'--id',evidence['evidenceId'],'--json'])
        value=source['value']
        call('timeline',cli_args+['timeline','--session',session,'--trace',source['traceId'],'--limit','10','--json'])
        # Report an exact fixture observation without inventing a performance defect or cause.
        response={'schemaVersion':1,'sessionId':runtime.read_json(session/'session.json')['sessionId'],
            'quality':{'verdict':'partial','captureComplete':False,'analysisReady':False,'populationStatisticsAllowed':False,'issues':['Rolling windows have no independent exporter acknowledgement.']},
            'findings':[{'id':'F1','priority':'P3','title':'Recorded fixture duration','observation':'The selected completed invocation has the cited duration; no business SLO is supplied.',
                'evidence':[{'file':source['file'],'line':source['line'],'jsonPointer':'/durationNanos','evidenceId':source['evidenceId'],
                    'traceId':value['traceId'],'method':value['method'],'value':value['durationNanos'],'unit':'nanoseconds'}],
                'hypothesis':None,'confidence':'low','missingEvidence':['Controlled business baseline'],'recommendation':'Measure an equivalent workload before changing code.',
                'validation':{'baseline':'Warm the same workload.','change':'One isolated candidate.','metrics':['Duration'],'acceptance':'Equivalent semantics and repeatable measured improvement.','risks':['Observer effects']}}],
            'nextMeasurements':['This fixture validates collection, not business performance; measure a controlled business baseline.']}
        analysis=pathlib.Path(capture['jobDirectory'])/'analysis/window-000001';analysis.mkdir(parents=True)
        response_path=analysis/'ai-response.json';runtime.atomic_json(response_path,response)
        valid=call('ai-verify',['run','--install-root',installed,'--','ai','verify','--session',session,'--response',response_path,'--json'])
        assert valid['valid']
        runtime.atomic_json(analysis/'validation.json',valid)
        master=pathlib.Path(captured['masterSessionDirectory'])
        call('strict-compare-blocked',cli_args+['compare','--before',master,'--after',master,'--json'],5)
        call('ack',['ack','--job',capture['jobDirectory'],'--cursor','1'])
        following=call('collect-after-ack',args)
        assert following['afterCursor']==1 and following['windows'][0]['sequence']==2
        stop=call('stop-start',start_args+['--mode','capture','--config',cfg,'--duration','2m','--window','1m'])
        jobs.append(stop['jobDirectory']);stop_job=pathlib.Path(stop['jobDirectory'])
        end=time.time()+30
        while time.time()<end:
            ready=stop_job/'native/stderr.log'
            samples=list(stop_job.glob('native/captures/*/session-*/diagnostics/manifest.json'))
            if ready.is_file() and 'READY pid=' in ready.read_text() and samples and runtime.read_json(samples[0]).get('samples',0)>0:break
            time.sleep(.2)
        else:raise AssertionError('No READY for stop fixture')
        call('stop-request',['stop','--job',stop_job])
        stopped=wait_job(stop_job)
        assert stopped['status']=='stopped',stopped
        result=runtime.read_json(stop_job/'windows/000001/result.json')
        assert stopped['restored'] and result['accepted'],result
        assert result['jfrCheckpoint']['status']=='COMPLETED',result
        assert pathlib.Path(result['sessionDirectory'],'diagnostics/jfr-analysis.json').is_file()
        assert owned.poll() is None,'Business fixture must survive stop'
        summary={'portableSkillInstalled':True,'sameHashCacheReused':True,'diagnosticWindows':diagnostic['completedWindows'],
                 'traceWindows':captured['completedWindows'],'traceRestored':True,'ackResumesAtWindow':2,'aiContractValidated':valid['valid'],
                 'nativeSingleAttach':True,'indexedJsonlEvidenceValidated':True,'finalWindowJfrPresent':True,
                 'gracefulStopRestored':stopped['restored'],'businessJvmSurvived':owned.poll() is None,'jobs':jobs}
        runtime.atomic_json(output/'summary.json',summary)
        print(output);print(json.dumps(summary,indent=2))
    finally:
        for job in jobs:
            path=pathlib.Path(job)
            if (path/'job.json').is_file() and runtime.read_json(path/'job.json')['status'] not in {'completed','degraded','stopped','failed','restore_unconfirmed','quota_reached','target_changed','interrupted'}:
                runtime.atomic_json(path/'stop.request',{'requestedAtMillis':int(time.time()*1000)})
                try:wait_job(job,40)
                except AssertionError:pass
        if owned is not None:
            (output/'target-stop').touch()
            try:owned.wait(timeout=5)
            except subprocess.TimeoutExpired:
                owned.terminate();owned.wait(timeout=5)


if __name__=='__main__':main()
