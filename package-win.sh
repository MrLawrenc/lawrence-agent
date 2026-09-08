#!/usr/bin/env bash
set -euo pipefail

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) ;;
    *)
        echo "package-win.sh 只能在 Windows 的 Git Bash 中执行。"
        echo "请将项目复制或拉取到 Windows，再运行此脚本。"
        exit 1
        ;;
esac

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_APP_DIRECTORY="$SCRIPT_DIR/monitor-app/build/jpackage/Agent Monitor"
BUILD_APP_EXE="$BUILD_APP_DIRECTORY/Agent Monitor.exe"
APP_OUTPUT_DIRECTORY="$SCRIPT_DIR/app"
APP_DIRECTORY="$APP_OUTPUT_DIRECTORY/Agent Monitor"
APP_EXE="$APP_DIRECTORY/Agent Monitor.exe"

cd "$SCRIPT_DIR"
./gradlew :monitor-app:packageWindowsApp

if [[ ! -f "$BUILD_APP_EXE" ]]; then
    echo "打包未生成预期文件：$BUILD_APP_EXE" >&2
    exit 1
fi

mkdir -p "$APP_OUTPUT_DIRECTORY"
rm -rf "$APP_DIRECTORY"
mv "$BUILD_APP_DIRECTORY" "$APP_DIRECTORY"

echo
echo "打包完成：$APP_EXE"
echo "可双击该 .exe 文件启动 Agent Monitor。"
