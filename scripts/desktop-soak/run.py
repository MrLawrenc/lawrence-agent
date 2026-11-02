#!/usr/bin/env python3
"""Own a target JVM, collect one rolling session and retain RSS/disk/restore evidence."""
import argparse, datetime, hashlib, json, os, pathlib, platform, subprocess, time


def write(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2))
    temporary.replace(path)


def safe_size(path):
    try:
        return path.stat().st_size
    except FileNotFoundError:
        return 0


def rss(pid):
    try:
        value = subprocess.check_output(['ps', '-o', 'rss=', '-p', str(pid)], text=True).strip()
        return int(value) * 1024 if value else None
    except (ValueError, subprocess.CalledProcessError):
        return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--duration', type=int, default=3660)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    options = parser.parse_args()
    if not 5 <= options.duration <= 604800:
        parser.error('duration must be 5–604800 seconds')
    repo = pathlib.Path(__file__).resolve().parents[2]
    run = options.output.resolve()
    run.mkdir(parents=True, exist_ok=False)
    classes = run / 'classes'
    classes.mkdir()
    subprocess.run(['javac', '-d', str(classes), str(repo / 'scripts/desktop-soak/SoakTarget.java')], check=True)
    jar = run / 'monitor-cli-all.jar'
    import shutil
    shutil.copy2(repo / 'monitor-cli/build/libs/monitor-cli-all.jar', jar)
    base = ['java', '-Xmx512m', '--add-modules', 'jdk.attach', '-jar', str(jar)]
    target_log = (run / 'target.log').open('w')
    target = subprocess.Popen(['java', '-Xmx128m', '-cp', str(classes), 'agentfixtures.soak.SoakTarget', str(run)], stdout=target_log, stderr=subprocess.STDOUT)
    capture = None
    restore_confirmed = False
    keep_awake = None
    try:
        for _ in range(100):
            if 'READY' in (run / 'target.log').read_text():
                break
            if target.poll() is not None:
                raise RuntimeError('Owned target exited before READY')
            time.sleep(.1)
        identity = json.loads(subprocess.check_output(base + ['jvm', 'identity', '--pid', str(target.pid), '--json'], text=True))
        write(run / 'target-identity.json', identity)
        config = run / 'monitor.yml'
        config.write_text('''version: 2
scope:
  includePackages: []
  includeClasses: ['agentfixtures.soak.SoakTarget$Business']
  excludeConditions: []
  excludeMethods: []
sampling:
  ratePercent: 10
  tailCaptureThresholdMs: 0
output:
  exporters: [otlp, file]
  capture:
    arguments: false
    returnValue: false
    sqlParameters: false
diagnostics:
  enabled: true
  intervalMs: 2000
  threadIntervalMs: 10000
  maxSamples: 7200
  jfr: false
''')
        result = (run / 'capture-result.json').open('w')
        progress = (run / 'capture.log').open('w')
        command = base + ['capture', '--pid', str(target.pid), '--config', str(config), '--expected-start', str(identity['startedAt']), '--duration', str(options.duration) + 's', '--rolling', '--window', '300s', '--max-bytes', '536870912', '--output', str(run / 'captures'), '--json']
        started = time.time()
        started_monotonic = time.monotonic()
        capture = subprocess.Popen(command, stdout=result, stderr=progress)
        if platform.system() == 'Darwin' and shutil.which('caffeinate'):
            keep_awake = subprocess.Popen(['caffeinate', '-i', '-w', str(capture.pid)])
        ready_at = None
        last_sample = 0
        state = {'status': 'RUNNING', 'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'durationSeconds': options.duration, 'targetPid': target.pid, 'capturePid': capture.pid, 'keepAwakePid': keep_awake.pid if keep_awake else None, 'cliJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'targetIdentity': identity, 'command': command}
        write(run / 'status.json', state)
        with (run / 'resources.jsonl').open('w') as resources:
            while capture.poll() is None:
                now = time.monotonic()
                if ready_at is None and 'READY pid=' in (run / 'capture.log').read_text():
                    ready_at = now
                if ready_at is not None and now-ready_at >= max(1,options.duration-5):
                    (run / 'quiesce.marker').touch(exist_ok=True)
                if now-last_sample < 5:
                    time.sleep(.25)
                    continue
                last_sample = now
                files = [file for file in (run / 'captures').rglob('*') if file.is_file()]
                sample = {'timestampMillis': int(time.time() * 1000), 'elapsedSeconds': round(time.time() - started, 3), 'activeElapsedSeconds': round(time.monotonic()-started_monotonic,3), 'targetRssBytes': rss(target.pid), 'collectorRssBytes': rss(capture.pid), 'diskBytes': sum(safe_size(file) for file in files), 'files': len(files)}
                resources.write(json.dumps(sample) + '\n'); resources.flush()
                sessions = list((run / 'captures').glob('*/session-*/session.json'))
                if sessions and 'sessionDirectory' not in state:
                    state['sessionDirectory'] = str(sessions[0].parent)
                    write(run / 'status.json', state)
                if target.poll() is not None:
                    capture.terminate()
                time.sleep(.25)
        result.close(); progress.close()
        outcome = json.loads((run / 'capture-result.json').read_text())
        restore_confirmed = outcome.get('restored') is True or capture.returncode == 2
        state.update(status='COMPLETED' if capture.returncode == 0 and outcome.get('restored') is True and ready_at is not None and time.monotonic()-ready_at >= options.duration-1 else 'FAILED', elapsedSeconds=time.time() - started, activeElapsedSeconds=time.monotonic()-started_monotonic, readyActiveElapsedSeconds=time.monotonic()-ready_at if ready_at is not None else None, exitCode=capture.returncode, restored=outcome.get('restored'), targetAliveAfterStop=target.poll() is None, ownedTargetQuiesced='QUIESCED' in (run / 'target.log').read_text())
        write(run / 'status.json', state)
        print(json.dumps(state, ensure_ascii=False))
        return 0 if state['status'] == 'COMPLETED' else 1
    finally:
        try:
            if capture is not None and capture.poll() is None:
                capture.terminate()
                try:
                    capture.wait(timeout=35)
                except subprocess.TimeoutExpired:
                    # Preserve the owned target and evidence when restore cannot be confirmed.
                    write(run / 'interrupted.json', {'restoreUnconfirmed': True, 'targetPid': target.pid, 'capturePid': capture.pid})
                    return 4
            # This fixture is owned by this script; stop only after the capture process has ended.
            if target.poll() is None and (capture is None or restore_confirmed):
                target.terminate()
                target.wait(timeout=10)
            if target.poll() is None:
                write(run / 'preserved-target.json', {'targetPid': target.pid, 'restoreUnconfirmed': True})
            target_log.close()
        finally:
            if keep_awake is not None and keep_awake.poll() is None:
                keep_awake.terminate()
                keep_awake.wait(timeout=5)



if __name__ == '__main__':
    raise SystemExit(main())
