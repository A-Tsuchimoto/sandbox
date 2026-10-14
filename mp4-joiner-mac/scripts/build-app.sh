#!/bin/bash
# MP4 Joiner.app を作って zip にする（macOS + Xcode が必要）
#   ./scripts/build-app.sh [ビルド番号]
set -euo pipefail
cd "$(dirname "$0")/.."

BUILD_NUMBER="${1:-1}"
ARCHS=(--arch arm64 --arch x86_64)   # Apple Silicon / Intel 両対応

swift build -c release "${ARCHS[@]}"
BIN_DIR="$(swift build -c release "${ARCHS[@]}" --show-bin-path)"

APP="build/MP4 Joiner.app"
rm -rf build
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_DIR/MP4Joiner" "$APP/Contents/MacOS/MP4Joiner"
sed "s/BUILD_NUMBER/$BUILD_NUMBER/" Resources/Info.plist > "$APP/Contents/Info.plist"

# アイコン（失敗してもアプリ自体は作る）
if swift scripts/make-icon.swift build/AppIcon.iconset && iconutil -c icns build/AppIcon.iconset -o "$APP/Contents/Resources/AppIcon.icns"; then
  echo "icon: ok"
else
  echo "icon: skipped"
fi

# Apple の開発者署名はないので ad-hoc 署名（Apple Silicon で起動するのに必要）
codesign --force --deep --sign - "$APP"
codesign --verify --verbose "$APP"

(cd build && ditto -c -k --keepParent "MP4 Joiner.app" MP4Joiner-mac.zip)
ls -la build
lipo -info "$APP/Contents/MacOS/MP4Joiner"
