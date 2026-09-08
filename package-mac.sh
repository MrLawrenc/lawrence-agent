#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_APP_BUNDLE="$SCRIPT_DIR/monitor-app/build/jpackage/Agent Monitor.app"
APP_DIRECTORY="$SCRIPT_DIR/app"
APP_BUNDLE="$APP_DIRECTORY/Agent Monitor.app"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "package-mac.sh 只能在 macOS 上执行。"
    exit 1
fi

cd "$SCRIPT_DIR"
./gradlew :monitor-app:packageMacApp

if [[ ! -d "$BUILD_APP_BUNDLE" ]]; then
    echo "打包任务已完成，但未找到应用包：$BUILD_APP_BUNDLE" >&2
    exit 1
fi

mkdir -p "$APP_DIRECTORY"
rm -rf "$APP_BUNDLE"
mv "$BUILD_APP_BUNDLE" "$APP_BUNDLE"

echo ""
echo "打包完成：$APP_BUNDLE"
echo "可双击打开，或执行："
echo "open \"$APP_BUNDLE\""
