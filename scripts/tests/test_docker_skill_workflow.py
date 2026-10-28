"""Container-free regression for the real lab skill workflow terminal states."""
import contextlib
import io
import json
import pathlib
import runpy
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

SCRIPT = pathlib.Path(__file__).resolve().parents[2] / 'examples/docker-performance-lab/skill-capture.py'
SERVICES = ('gateway', 'inventory', 'pricing')


class DockerSkillWorkflowTest(unittest.TestCase):
    def exercise(self, terminal, windows):
        with tempfile.TemporaryDirectory(prefix='lab-terminal-review-') as name:
            root = pathlib.Path(name)
            containers = {service: 'owned-' + service for service in SERVICES}
            jobs = {}
            for service in SERVICES:
                job = root / 'captures/skill-jobs' / service / 'J'
                (job / 'native').mkdir(parents=True)
                (job / 'native/stderr.log').write_text('READY pid=1\n')
                jobs[service] = dict(status=terminal, restored=terminal != 'restore_unconfirmed',
                    completedWindows=windows, workerUnresponsive=False)
                (job / 'job.json').write_text(json.dumps(jobs[service]))
            (root / 'control.json').write_text(json.dumps(dict(docker=['fake-docker'],
                containers=containers, addresses={'gateway': 'http://fixture.invalid'})))
            (root / 'summary.json').write_text(json.dumps(dict(status='PREPARED',
                project='owned-project', cliSha256='fixture-hash')))

            def fake_run(command, **kwargs):
                self.assertEqual('fake-docker', command[0])
                if command[1] == 'cp':
                    return types.SimpleNamespace(returncode=0)
                service = next(service for service, cid in containers.items() if cid in command)
                arguments = command[command.index('/tmp/java-performance/scripts/perf.py') + 1:]
                action = arguments[0]
                if action == 'ensure':
                    value = {'sha256': 'fixture-hash'}
                elif action == 'start':
                    value = {'jobDirectory': '/captures/skill-jobs/' + service + '/J'}
                elif action == 'status':
                    value = jobs[service]
                elif action == 'collect':
                    value = dict(status=terminal, windows=[dict(sequence=i + 1, accepted=True)
                        for i in range(windows)], hasMore=False)
                elif action == 'run' and arguments[3] == 'list':
                    value = {'jvms': [{'pid': 1}]}
                elif action == 'run' and 'identity' in arguments:
                    value = {'pid': 1, 'startedAt': 'fixture-start'}
                elif action == 'run' and 'preview' in arguments:
                    value = {'globalSelectedClasses': 1}
                else:
                    raise AssertionError('Unexpected external command: ' + repr(command))
                return types.SimpleNamespace(returncode=0, stdout=json.dumps(value), stderr='')

            def fake_inspect(command, **kwargs):
                self.assertEqual(['fake-docker', 'inspect'], command[:2])
                self.assertIn(command[2], containers.values())
                return json.dumps([{'Id': command[2], 'Config': {'Labels': {
                    'com.docker.compose.project': 'owned-project'}}, 'State': {
                    'Running': True, 'StartedAt': 'fixture-start'}}])

            class Reply:
                def __init__(self, url, **kwargs):
                    if not url.startswith('http://fixture.invalid/'):
                        raise AssertionError('Unexpected URL: ' + url)
                    self.status = 503 if url.endswith('/failure') else 200
                def __enter__(self): return self
                def __exit__(self, *args): pass
                def read(self): return b'{}'

            exit_code = 0
            with patch.object(sys, 'argv', [str(SCRIPT), '--run', str(root)]), \
                    patch('subprocess.run', side_effect=fake_run), \
                    patch('subprocess.check_output', side_effect=fake_inspect), \
                    patch('urllib.request.urlopen', side_effect=Reply), \
                    patch('time.sleep'), contextlib.redirect_stdout(io.StringIO()):
                try:
                    runpy.run_path(str(SCRIPT), run_name='__main__')
                except SystemExit as error:
                    exit_code = error.code or 0
                except RuntimeError:
                    exit_code = 1
            result = json.loads((root / 'analysis/skill/workflow.json').read_text())
            return result, exit_code

    def assert_attention(self, terminal, windows):
        result, exit_code = self.exercise(terminal, windows)
        self.assertEqual('ATTENTION_REQUIRED', result['status'], result)
        self.assertNotEqual(0, exit_code, 'A failed collection must not exit successfully')
        for job in result['jobs'].values():
            self.assertEqual(terminal, job['finalStatus']['status'])
            self.assertIn('collect', job, 'Keep partial evidence available for inspection')

    def test_restored_collection_keeps_windows_for_ai(self):
        result, exit_code = self.exercise('completed', 1)
        self.assertEqual(0, exit_code)
        self.assertEqual('AWAITING_AI_ANALYSIS', result['status'])
        self.assertEqual(3, len(result['jobs']))

    def test_unconfirmed_restore_does_not_look_ready(self):
        self.assert_attention('restore_unconfirmed', 0)

    def test_failed_collection_does_not_look_ready(self):
        self.assert_attention('failed', 1)

    def test_completed_without_windows_requires_attention(self):
        self.assert_attention('completed', 0)


if __name__ == '__main__':
    unittest.main()
