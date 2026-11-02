#!/usr/bin/env python3
"""Build a local release candidate with source identity and artifact checksums; never publish it."""
import argparse
import datetime
import hashlib
import json
import pathlib
import platform
import shutil
import subprocess


def main():
    repo = pathlib.Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=pathlib.Path)
    options = parser.parse_args()

    def git(*args):
        return subprocess.check_output(['git', *args], cwd=repo, text=True).strip()

    commit = git('rev-parse', 'HEAD')
    status = git('status', '--porcelain')
    system = platform.system()
    output = options.output or repo / 'build/release-candidates' / (datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S') + '-' + commit[:8])
    output = output.resolve()
    if output.exists():
        parser.error('output must be a new directory')
    subprocess.run(['python3' if system != 'Windows' else 'python', str(repo / 'scripts/generate-native-icons.py'), '--check'], cwd=repo, check=True)
    tasks = [':monitor-app:shadowJar', ':monitor-app:distZip', ':monitor-cli:installDist', ':monitor-cli:distZip', ':monitor-cli:shadowJar']
    if system == 'Darwin':
        tasks.append(':monitor-app:packageMacApp')
    elif system == 'Windows':
        tasks.append(':monitor-app:packageWindowsApp')
    wrapper = str(repo / ('gradlew.bat' if system == 'Windows' else 'gradlew'))
    subprocess.run([wrapper, *tasks, '--console=plain', '--max-workers=1'], cwd=repo, check=True)
    if git('rev-parse', 'HEAD') != commit or git('status', '--porcelain') != status:
        raise SystemExit('Source identity changed during packaging; candidate was not collected')
    output.mkdir(parents=True, exist_ok=False)
    for module in ('monitor-app', 'monitor-cli'):
        for file in (repo / module / 'build/distributions').glob('*.zip'):
            shutil.copy2(file, output / file.name)
        file = repo / module / 'build/libs' / (module + '-all.jar')
        shutil.copy2(file, output / file.name)
    if system in ('Darwin', 'Windows'):
        name = 'Agent Monitor.app' if system == 'Darwin' else 'Agent Monitor'
        native = repo / 'monitor-app/build/jpackage' / name
        if system == 'Darwin':
            subprocess.run(['ditto', '-c', '-k', '--sequesterRsrc', '--keepParent', str(native), str(output / 'AgentMonitor-macOS.zip')], check=True)
        else:
            shutil.make_archive(str(output / 'AgentMonitor-Windows'), 'zip', native.parent, native.name)
    artifacts = []
    for file in sorted(output.iterdir()):
        digest = hashlib.file_digest(file.open('rb'), 'sha256').hexdigest()
        artifacts.append({'file': file.name, 'bytes': file.stat().st_size, 'sha256': digest})
    (output / 'SHA256SUMS').write_text(''.join(f"{a['sha256']}  {a['file']}\n" for a in artifacts), encoding='utf-8')
    manifest = {'commit': commit, 'workingTreeClean': not status, 'workingTreeStatus': status.splitlines(), 'system': system, 'architecture': platform.machine(), 'java': subprocess.run(['java', '-version'], capture_output=True, text=True, check=True).stderr.strip(), 'buildTasks': tasks, 'artifacts': artifacts, 'verification': 'Packaging succeeded. Test results and long-capture receipts must be reviewed separately; packaging does not imply verification passed.'}
    (output / 'candidate.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'candidateDirectory': str(output), 'commit': commit, 'workingTreeClean': not status}, ensure_ascii=False))


if __name__ == '__main__':
    main()
