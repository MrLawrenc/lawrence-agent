#!/usr/bin/env python3
"""Wrap the existing PNG in native containers without changing its pixels."""
import argparse
import pathlib
import struct


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    options = parser.parse_args()
    assets = pathlib.Path(__file__).resolve().parents[1] / 'monitor-app/src/main/resources/com/agentmonitor/app'
    png = (assets / 'icon.png').read_bytes()
    if png[:8] != b'\x89PNG\r\n\x1a\n' or png[12:16] != b'IHDR':
        raise ValueError('icon.png must have a PNG IHDR')
    width, height = struct.unpack('>II', png[16:24])
    types = {16: b'icp4', 32: b'icp5', 64: b'icp6', 128: b'ic07', 256: b'ic08'}
    if width != height or width not in types:
        raise ValueError('Use a square 16/32/64/128/256 PNG for both native formats')
    icns = b'icns' + struct.pack('>I', len(png) + 16) + types[width] + struct.pack('>I', len(png) + 8) + png
    ico = struct.pack('<HHH', 0, 1, 1) + struct.pack('<BBBBHHII', width % 256, height % 256, 0, 0, 1, 32, len(png), 22) + png
    for name, data in [('icon.icns', icns), ('icon.ico', ico)]:
        file = assets / name
        if options.check:
            if not file.exists() or file.read_bytes() != data:
                raise SystemExit(f'{file} is missing or stale; run this script without --check')
        else:
            file.write_bytes(data)
    print('Native icon containers match icon.png')


if __name__ == '__main__':
    main()
