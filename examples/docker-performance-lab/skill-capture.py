#!/usr/bin/env python3
"""Exercise the actual portable skill against a prepared lab; leave analysis/ack to AI."""
import argparse
import collections
import json
import pathlib
import subprocess
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SERVICES = ('gateway', 'inventory', 'pricing')


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=pathlib.Path, required=True)
    args = parser.parse_args()
    run = args.run.resolve()
    control = json.loads((run / 'control.json').read_text())
    summary = json.loads((run / 'summary.json').read_text())
    if summary['status'] != 'PREPARED':
        parser.error('A PREPARED lab is required; do not launch duplicate jobs')
    docker = control['docker']
    analysis = run / 'analysis/skill'
    if (analysis / 'workflow.json').exists():
        parser.error('An existing workflow must be resumed via its saved jobs; refusing duplicate collection')
    analysis.mkdir(parents=True, exist_ok=True)
    state = {'project': summary['project'], 'runDirectory': str(run), 'jobs': {}, 'status': 'STARTING',
             'scope': 'dev.perflab.work', 'durationSeconds': 90, 'windowSeconds': 30,
             'maxBytesPerJob': 268435456, 'containerMount': '/captures', 'hostMount': str(run / 'captures')}
    state_path = analysis / 'workflow.json'
    save(state_path, state)

    def execute(service, label, arguments):
        cid = control['containers'][service]
        command = docker + ['exec', '-e', 'JAVA_TOOL_OPTIONS=-Xmx160m', '-e', 'MONITOR_SKILL_HOME=/tmp/monitor-runtime',
                            cid, 'python3', '/tmp/java-performance/scripts/perf.py'] + arguments
        result = subprocess.run(command, text=True, capture_output=True, timeout=90)
        (analysis / (label + '.stderr')).write_text(result.stderr)
        value = json.loads(result.stdout)
        save(analysis / (label + '.json'), value)
        if result.returncode:
            raise RuntimeError(label + ': ' + str(value))
        return value

    try:
        for service in SERVICES:
            cid = control['containers'][service]
            metadata = json.loads(subprocess.check_output(docker + ['inspect', cid], text=True))[0]
            if metadata['Config']['Labels'].get('com.docker.compose.project') != summary['project'] or not metadata['State']['Running']:
                raise RuntimeError('Unexpected or stopped container: ' + service)
            subprocess.run(docker + ['cp', str(ROOT / 'skills/java-performance'), cid + ':/tmp/java-performance'], check=True)
            installed = execute(service, 'ensure-' + service, ['ensure', '--cli', '/opt/monitor/monitor-cli.jar'])
            if installed['sha256'] != summary['cliSha256']:
                raise RuntimeError('CLI artifact differs from the prepared lab: ' + service)
            listing = execute(service, 'jvms-' + service, ['run', '--', 'jvm', 'list'])
            if not any(str(j['pid']) == '1' for j in listing['jvms']):
                raise RuntimeError('Expected owned fixture PID 1: ' + service)
            identity = execute(service, 'identity-' + service, ['run', '--', 'jvm', 'identity', '--pid', '1'])
            preview = execute(service, 'scope-' + service, ['run', '--', 'scope', 'preview', '--pid', '1', '--config', '/config/' + service + '.yml', '--package', 'dev.perflab.work', '--selected-only', '--limit', '20'])
            if preview.get('globalSelectedClasses', 0) < 1:
                raise RuntimeError('No business classes match the capture scope: ' + service)
            state['jobs'][service] = {'containerId': metadata['Id'], 'containerStartedAt': metadata['State']['StartedAt'],
                                     'jvmIdentity': identity, 'cliSha256': installed['sha256'], 'scopePreview': preview}
            save(state_path, state)
        # All preflights complete before the three finite captures start.
        for service in SERVICES:
            result = execute(service, 'start-' + service, ['start', '--pid', '1', '--mode', 'capture', '--config', '/config/' + service + '.yml',
                             '--duration', '90s', '--window', '30s', '--max-bytes', '268435456', '--work-dir', '/captures/skill-jobs/' + service])
            job = state['jobs'][service]
            job['jobDirectory'] = result['jobDirectory']
            job['hostJobDirectory'] = str(run / 'captures' / pathlib.PurePosixPath(result['jobDirectory']).relative_to('/captures'))
            save(state_path, state)
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                logs = list(pathlib.Path(job['hostJobDirectory']).glob('native/stderr.log'))
                if logs and 'READY pid=' in logs[0].read_text():
                    break
                current = json.loads((pathlib.Path(job['hostJobDirectory']) / 'job.json').read_text())
                if current['status'] not in ('pending', 'running'):
                    raise RuntimeError('Capture did not become ready: ' + service + ': ' + current['status'])
                time.sleep(.25)
            else:
                raise TimeoutError('Capture READY: ' + service)
            print('SKILL READY', service, result['jobDirectory'], flush=True)
        state['status'] = 'CAPTURING'
        save(state_path, state)
        ledger = []
        started = time.monotonic()
        for round_number in range(3):
            for path in ['/chain', '/slow-sql', '/pool-wait', '/async', '/redis', '/rabbit', '/apache', '/failure', '/memory'] + ['/cpu'] * 12:
                target = control['addresses']['gateway'] + path
                before = time.perf_counter()
                try:
                    with urllib.request.urlopen(target, timeout=15) as reply:
                        code, body = reply.status, json.loads(reply.read())
                except urllib.error.HTTPError as error:
                    code, body = error.code, json.loads(error.read())
                if code != (503 if path == '/failure' else 200):
                    raise RuntimeError('Unexpected workload response: ' + path + ': ' + str(code))
                ledger.append({'round': round_number + 1, 'path': path, 'status': code,
                               'elapsedMillis': (time.perf_counter() - before) * 1000, 'body': body})
                save(analysis / 'load.json', {'requests': len(ledger), 'byPath': dict(collections.Counter(row['path'] for row in ledger)), 'ledger': ledger})
            print('WORKLOAD ROUND', round_number + 1, 'requests', len(ledger), flush=True)
            remaining = started + (round_number + 1) * 22 - time.monotonic()
            if remaining > 0:
                time.sleep(min(remaining, 22))
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            pending = []
            for service, job in state['jobs'].items():
                status = json.loads((pathlib.Path(job['hostJobDirectory']) / 'job.json').read_text())
                if status['status'] in ('pending', 'running', 'stopping'):
                    pending.append(service)
            if not pending:
                break
            time.sleep(2)
        else:
            raise TimeoutError('Skill jobs are still active; inspect workflow.json and stop gracefully')
        for service, job in state['jobs'].items():
            job['finalStatus'] = execute(service, 'status-' + service, ['status', '--job', job['jobDirectory']])
            job['collect'] = execute(service, 'collect-' + service, ['collect', '--job', job['jobDirectory'], '--consumer', 'ai'])
        attention = [service + ': ' + job['finalStatus']['status']
                     for service, job in state['jobs'].items()
                     if job['finalStatus']['status'] not in ('completed', 'degraded')
                     or job['finalStatus'].get('restored') is not True
                     or not job['collect'].get('windows')]
        if attention:
            state['attention'] = attention
            raise RuntimeError('Capture needs attention: ' + '; '.join(attention) +
                               '. Inspect saved status/collect and worker logs; do not restart or infer restoration.')
        state['status'] = 'AWAITING_AI_ANALYSIS'
        print('Evidence ready; analyze and verify each window before ack. Then clean up this exact project.', flush=True)
    except BaseException as error:
        state['status'] = 'ATTENTION_REQUIRED' if state.get('attention') else 'FAILED'
        state['error'] = str(error)
        raise
    finally:
        save(state_path, state)
        print('WORKFLOW', state_path, flush=True)


if __name__ == '__main__':
    main()
