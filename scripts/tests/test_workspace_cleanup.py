"""Exercise cleanup in a disposable repository, including protected data and links."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'clean-workspace.py'


class WorkspaceCleanupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'scripts').mkdir()
        shutil.copy2(SCRIPT, self.root / 'scripts/clean-workspace.py')

    def write(self, name, content='cache'):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def run_cleanup(self, *args):
        result = subprocess.run([sys.executable, str(self.root / 'scripts/clean-workspace.py'), *args],
                                text=True, capture_output=True, check=True)
        return json.loads(result.stdout)

    def test_preview_then_apply_only_known_caches(self):
        cache = self.write('src/__pycache__/module.cpython-313.pyc')
        metadata = self.write('src/.DS_Store')
        source = self.write('src/module.py')
        report = self.write('session/reports/performance-report.json', '{}')
        self.assertEqual(self.run_cleanup()['count'], 2)
        self.assertTrue(cache.exists())
        result = self.run_cleanup('--apply')
        self.assertEqual(result['bytes'], 10)
        self.assertFalse(cache.parent.exists())
        self.assertFalse(metadata.exists())
        self.assertTrue(source.exists())
        self.assertTrue(report.exists())
        self.assertEqual(self.run_cleanup('--apply')['count'], 0)

    def test_preserves_build_hidden_and_runtime_state(self):
        paths = [self.write(name) for name in ('module/build/__pycache__/data.pyc',
                 '.gradle/cache.pyc', '.git/object.pyc', '.agents/local.pyc',
                 'agent-monitor.pid', 'session/session.lock', 'session/profile.jfr')]
        self.assertEqual(self.run_cleanup('--apply')['count'], 0)
        self.assertTrue(all(p.exists() for p in paths))

    def test_preserves_unexpected_cache_contents(self):
        paths = [self.write('src/__pycache__/notes.txt'),
                 self.write('src/__pycache__/module.pyc')]
        self.assertEqual(self.run_cleanup('--apply')['count'], 0)
        self.assertTrue(all(p.exists() for p in paths))

    def test_never_follows_symlinks(self):
        target = self.write('.external/cache.pyc')
        (self.root / 'linked').symlink_to(target.parent, target_is_directory=True)
        (self.root / 'linked.pyc').symlink_to(target)
        folder = self.root / 'src/__pycache__'
        folder.mkdir(parents=True)
        (folder / 'module.pyc').symlink_to(target)
        self.assertEqual(self.run_cleanup('--apply')['count'], 0)
        self.assertTrue(target.exists())
        self.assertTrue((folder / 'module.pyc').is_symlink())


if __name__ == '__main__':
    unittest.main()
