#!/bin/bash
# Finder でダブルクリックすると MP4 Joiner を起動します。
cd "$(dirname "$0")" || exit 1
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 が見つかりません。ターミナルで xcode-select --install を実行してください。"
  read -r -p "Enter で閉じます"
  exit 1
fi
if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ffmpeg が見つかりません。ターミナルで brew install ffmpeg を実行してください。"
  read -r -p "Enter で閉じます"
  exit 1
fi
exec python3 -m mp4join gui
