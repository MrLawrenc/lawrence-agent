import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]


class DesktopSoakVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.run = pathlib.Path(self.temp.name)
        self.session = self.run / 'session'
        (self.session / 'spans').mkdir(parents=True)
        (self.session / 'reports').mkdir()
        self.write('status.json', {'status': 'COMPLETED', 'durationSeconds': 3660, 'elapsedSeconds': 3665, 'readyActiveElapsedSeconds': 3660, 'exitCode': 0, 'targetAliveAfterStop': True, 'sessionDirectory': str(self.session)})
        self.write('capture-result.json', {'restored': True, 'captureComplete': True, 'analysisReady': True, 'captureQuality': {'acceptedSpans': 2}, 'captureBudgetReached': False, 'rollingArchiveComplete': True})
        self.write('session/session.json', {'sessionId': 'owned-fixture'})
        self.write('session/rolling-capture.json', {'windows': [{}], 'errors': []})
        self.write('session/spans/manifest.json', {'captureComplete': True, 'writtenSpans': 2})
        self.write('session/reports/performance-report.json', {'sessionId': 'owned-fixture', 'captureComplete': True, 'analysisReady': True})
        (self.run / 'resources.jsonl').write_text('{"timestampMillis":1000,"collectorRssBytes":10}\n{"timestampMillis":6000,"collectorRssBytes":20}\n')

    def write(self, path, value):
        (self.run / path).write_text(json.dumps(value))

    def verify(self):
        result = subprocess.run([sys.executable, str(REPO / 'scripts/desktop-soak/summarize.py'), '--capture', str(self.run)], capture_output=True, text=True)
        return result.returncode, json.loads(result.stdout)

    def test_capture_only_verification_does_not_claim_ui_was_exercised(self):
        code, result = self.verify()
        self.assertEqual(0, code)
        self.assertEqual('capture_only', result['scope'])
        self.assertIsNone(result['uiElapsedSeconds'])
        self.assertIsNone(result['targetRss']['peakBytes'])

    def test_long_wall_time_cannot_replace_recorded_active_duration(self):
        state = json.loads((self.run / 'status.json').read_text())
        state.update(elapsedSeconds=10000, readyActiveElapsedSeconds=50)
        self.write('status.json', state)
        code, result = self.verify()
        self.assertEqual(1, code)
        self.assertFalse(result['checks']['captureReachedMinimumActiveDuration'])

    def test_raw_counts_must_match_the_collector_receipt(self):
        self.write('session/spans/manifest.json', {'captureComplete': True, 'writtenSpans': 1})
        code, result = self.verify()
        self.assertEqual(1, code)
        self.assertFalse(result['checks']['rawCountMatchesAccepted'])

    def test_foreign_report_cannot_become_a_valid_soak(self):
        self.write('session/reports/performance-report.json', {'sessionId': 'foreign', 'captureComplete': True, 'analysisReady': True})
        code, result = self.verify()
        self.assertEqual(1, code)
        self.assertFalse(result['checks']['reportIdentityMatchesSession'])

    def test_missing_restore_is_not_hidden_by_complete_raw_files(self):
        result = json.loads((self.run / 'capture-result.json').read_text())
        result['restored'] = False
        self.write('capture-result.json', result)
        code, summary = self.verify()
        self.assertEqual(1, code)
        self.assertFalse(summary['checks']['bytecodeRestored'])
