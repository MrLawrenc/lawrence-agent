#!/usr/bin/env python3
"""Verify owned-fixture receipts, timing and resource evidence without performance claims."""
import argparse
import json
import pathlib
import statistics


def read(path):
    return json.loads(path.read_text())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--capture', type=pathlib.Path, required=True)
    parser.add_argument('--ui', type=pathlib.Path)
    parser.add_argument('--minimum-seconds', type=int, default=3600)
    options = parser.parse_args()
    if options.minimum_seconds < 1:
        parser.error('minimum-seconds must be positive')
    state = read(options.capture / 'status.json')
    result = read(options.capture / 'capture-result.json')
    session = pathlib.Path(state['sessionDirectory'])
    rolling = read(session / 'rolling-capture.json')
    raw = read(session / 'spans/manifest.json')
    report = read(session / 'reports/performance-report.json')
    identity = read(session / 'session.json')
    resources = [json.loads(line) for line in (options.capture / 'resources.jsonl').read_text().splitlines()]
    active = state.get('readyActiveElapsedSeconds')
    measured_active = type(active) in (int, float)
    accepted = result.get('captureQuality', {}).get('acceptedSpans')
    checks = {
        'captureReachedMinimumWallDuration': state.get('elapsedSeconds', 0) >= options.minimum_seconds,
        'captureReachedMinimumActiveDuration': active >= options.minimum_seconds if measured_active else state.get('durationSeconds', 0) >= options.minimum_seconds and result.get('captureBudgetReached') is False,
        'captureExitSuccess': state.get('status') == 'COMPLETED' and state.get('exitCode') == 0,
        'bytecodeRestored': result.get('restored') is True,
        'ownedTargetSurvivedStop': state.get('targetAliveAfterStop') is True,
        'rawCaptureComplete': raw.get('captureComplete') is True and result.get('captureComplete') is True,
        'rawCountMatchesAccepted': type(accepted) is int and accepted > 0 and type(raw.get('writtenSpans')) is int and raw['writtenSpans'] == accepted,
        'reportAnalysisReady': report.get('captureComplete') is True and report.get('analysisReady') is True and result.get('analysisReady') is True,
        'reportIdentityMatchesSession': bool(identity.get('sessionId')) and report.get('sessionId') == identity.get('sessionId'),
        'rollingArchiveComplete': result.get('rollingArchiveComplete') is True and not rolling.get('errors'),
        'resourceSamplesPresent': bool(resources),
    }
    ui = None
    pulses = []
    if options.ui is not None:
        ui = read(options.ui / 'summary.json')
        pulses = [json.loads(line) for line in (options.ui / 'pulses.jsonl').read_text().splitlines()]
        checks.update(uiReachedMinimumDuration=ui.get('status') == 'COMPLETED' and ui.get('elapsedSeconds', 0) >= options.minimum_seconds, uiSamplesPresent=bool(pulses))

    def memory(key):
        values = [row[key] for row in resources if type(row.get(key)) in (int, float)]
        count = max(1, len(values) // 4)
        return {'samples': len(values), 'peakBytes': max(values, default=None), 'firstQuartileMedianBytes': statistics.median(values[:count]) if values else None, 'lastQuartileMedianBytes': statistics.median(values[-count:]) if values else None}

    gaps = [max(0, b['timestampMillis'] - a['timestampMillis']) for a, b in zip(resources, resources[1:])]
    verification = {
        'status': 'PASSED' if all(checks.values()) else 'FAILED',
        'scope': 'capture_and_ui' if ui else 'capture_only',
        'checks': checks,
        'captureElapsedSeconds': state.get('elapsedSeconds'),
        'captureReadyActiveElapsedSeconds': active,
        'requestedDurationSeconds': state.get('durationSeconds'),
        'activeTimingBasis': 'recorded_monotonic_clock' if measured_active else 'legacy_harness: configured CLI monotonic deadline plus successful completion; active clock was not separately recorded',
        'maxResourceSampleWallGapMillis': max(gaps, default=0),
        'uiElapsedSeconds': ui.get('elapsedSeconds') if ui else None,
        'windows': len(rolling.get('windows', [])),
        'collectorRss': memory('collectorRssBytes'),
        'targetRss': memory('targetRssBytes'),
        'peakEvidenceDiskBytes': max((row.get('diskBytes', 0) for row in resources), default=0),
        'peakUiHeapUsedBytes': max((row.get('heapUsedBytes', 0) for row in pulses), default=0) if ui else None,
        'maxUiPulseGapMillis': ui.get('maxPulseGapMillis') if ui else None,
        'sessionDirectory': str(session),
        'boundaries': [
            'One self-owned JVM and configuration on this machine; no production overhead guarantee.',
            'Wall-clock sampling gaps include suspend/resume; passing capture receipts do not establish an uninterrupted soak.',
            'RSS includes native memory and residency/compression; growth alone does not establish a leak.',
            'UI pulse gap includes startup and background/OS scheduling; it is not a per-action latency percentile.',
            'Rolling windows remain partial evidence; only the master final receipts establish capture completion.',
        ],
    }
    (options.capture / 'verification-summary.json').write_text(json.dumps(verification, ensure_ascii=False, indent=2))
    if ui and ui.get('status') == 'COMPLETED':
        (options.ui / 'status.json').write_text(json.dumps(ui, ensure_ascii=False, indent=2))
    print(json.dumps(verification, ensure_ascii=False))
    return 0 if verification['status'] == 'PASSED' else 1


if __name__ == '__main__':
    raise SystemExit(main())
