#!/usr/bin/env python3
"""Preview or remove reproducible Python/OS cache files in repository source folders."""
import argparse
import json
import os
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]


def candidates(root):
    for current, directories, files in os.walk(root, followlinks=False):
        directory = Path(current)
        for name in sorted(directories[:]):
            path = directory / name
            if path.is_symlink() or name.startswith('.') or name == 'build':
                directories.remove(name)
            elif name == '__pycache__':
                directories.remove(name)
                # Only known bytecode files: preserve unexpected contents and symlinks.
                entries = list(path.iterdir())
                if all(p.is_file() and not p.is_symlink() and p.suffix in ('.pyc', '.pyo') for p in entries):
                    yield path, sum(p.stat().st_size for p in entries)
        for name in sorted(files):
            path = directory / name
            if not path.is_symlink() and (name == '.DS_Store' or path.suffix in ('.pyc', '.pyo')):
                yield path, path.stat().st_size


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Remove the listed cache files')
    args = parser.parse_args()
    selected = sorted(candidates(ROOT))
    if args.apply:
        for path, _ in selected:
            if path.is_dir():
                shutil.rmtree(path)
            else:
                path.unlink()
    print(json.dumps({'mode': 'apply' if args.apply else 'preview',
                      'paths': [str(p.relative_to(ROOT)) for p, _ in selected],
                      'count': len(selected), 'bytes': sum(size for _, size in selected)},
                     ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
