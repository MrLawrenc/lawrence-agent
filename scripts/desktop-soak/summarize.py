#!/usr/bin/env python3
"""Validate completed owned-fixture soak evidence and report measurements without benefit claims."""
import argparse
import json
import pathlib
import statistics


def read(path):
    return json.loads(path.read_text())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--capture', type=pathlib.Path, required=True)
    parser.add_argument('--ui', type=pathlib.Path, required=True)
    parser.add_argument('--minimum-seconds', type=int, default=3600)
    options = parser.parse_args()
    state = read(options.capture / 'status.json')
    result = read(options.capture / 'capture-result.json')
    session = pathlib.Path(state['sessionDirectory'])
    rolling = read(session / 'rolling-capture.json')
    raw = read(session / 'spans/manifest.json')
    ui = read(options.ui / 'summary.json')
    resources = [json.loads(line) for line in (options.capture / 'resources.jsonl').read_text().splitlines()]
    pulses = [json.loads(line) for line in (options.ui / 'pulses.jsonl').read_text().splitlines()]
    checks = {
        'captureReachedMinimumDuration': state.get('elapsedSeconds', 0) >= options.minimum_seconds,
        'captureExitSuccess': state.get('exitCode') == 0,
        'bytecodeRestored': result.get('restored') is True,
        'ownedTargetSurvivedStop': state.get('targetAliveAfterStop') is True,
        'rawCaptureComplete': raw.get('captureComplete') is True,
        'rollingArchiveComplete': result.get('rollingArchiveComplete') is True and not rolling.get('errors'),
        'uiReachedMinimumDuration': ui.get('status') == 'COMPLETED' and ui.get('elapsedSeconds', 0) >= options.minimum_seconds,
        'resourceSamplesPresent': bool(resources),
        'uiSamplesPresent': bool(pulses),
    }
    def memory(key):
        values = [row[key] for row in resources if isinstance(row.get(key), (int, float))]
        count = max(1, len(values) // 4)
        return {'samples': len(values), 'peakBytes': max(values, default=0), 'firstQuartileMedianBytes': statistics.median(values[:count]) if values else None, 'lastQuartileMedianBytes': statistics.median(values[-count:]) if values else None}
    report = {
        'status': 'PASSED' if all(checks.values()) else 'FAILED',
        'checks': checks,
        'captureElapsedSeconds': state.get('elapsedSeconds'),
        'uiElapsedSeconds': ui.get('elapsedSeconds'),
        'windows': len(rolling.get('windows', [])),
        'collectorRss': memory('collectorRssBytes'),
        'targetRss': memory('targetRssBytes'),
        'peakEvidenceDiskBytes': max((row.get('diskBytes', 0) for row in resources), default=0),
        'peakUiHeapUsedBytes': max((row.get('heapUsedBytes', 0) for row in pulses), default=0),
        'maxUiPulseGapMillis': ui.get('maxPulseGapMillis'),
        'sessionDirectory': str(session),
        'boundaries': [
            'One self-owned JVM and configuration on this machine; no production overhead guarantee.',
            'RSS includes native memory and residency/compression; growth alone does not establish a leak.',
            'UI pulse gap includes startup and background/OS scheduling; it is not a per-action latency percentile.',
            'Rolling windows remain partial evidence; only the master final receipts establish capture completion.',
        ],
    }
    output = options.capture / 'verification-summary.json'
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    # Older harnesses wrote the terminal summary separately from their heartbeat status.
    if ui.get('status') == 'COMPLETED':
        (options.ui / 'status.json').write_text(json.dumps(ui, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False))
    return 0 if report['status'] == 'PASSED' else 1


if __name__ == '__main__':
    raise SystemExit(main())
