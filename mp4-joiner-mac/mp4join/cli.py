"""コマンドライン入口。

    python3 -m mp4join                       # GUI（ブラウザ）を起動
    python3 -m mp4join join a.mp4 b.mp4 -o out.mp4 [--fade 1.5] [--sort gopro]
"""

from __future__ import annotations

import argparse
import os
import sys

from . import core


def _bar(p: float, msg: str):
    width = 30
    filled = int(width * p)
    sys.stderr.write(f"\r[{'#' * filled}{'.' * (width - filled)}] {p * 100:5.1f}% {msg[:60]:<60}")
    sys.stderr.flush()


def cmd_join(args) -> int:
    ffmpeg, ffprobe = core.require_tools()
    paths = core.expand_inputs(args.inputs)
    if not paths:
        print("入力がありません。", file=sys.stderr)
        return 2
    clips = [core.probe(p, ffprobe) for p in paths]
    if args.sort != "none":
        clips = core.sort_clips(clips, args.sort)

    transitions = None
    if args.fade and args.keep_chapters:
        transitions = [not core.is_same_recording(a.name, b.name) for a, b in zip(clips, clips[1:])]
    opts = core.JoinOptions(fade=args.fade, transitions=transitions, fade_edges=args.fade_edges,
                            codec=args.codec, quality=args.quality, force_reencode=args.reencode)
    plan = core.make_plan(clips, opts)

    print(f"{len(clips)} 本 / 合計 {core._fmt_time(plan.total_duration)}", file=sys.stderr)
    for i, c in enumerate(clips, 1):
        print(f"  {i:2d}. {c.name}  {core._fmt_time(c.duration)}  {c.width}x{c.height}", file=sys.stderr)
    print(plan.reason, file=sys.stderr)
    if args.dry_run:
        return 0

    output = os.path.abspath(os.path.expanduser(args.output))
    if os.path.exists(output) and not args.yes:
        print(f"{output} は既に存在します。上書きするには -y を付けてください。", file=sys.stderr)
        return 1
    runner = core.Runner(ffmpeg, ffprobe)
    try:
        runner.join(clips, output, opts, _bar)
    except KeyboardInterrupt:
        runner.cancel()
        print("\nキャンセルしました。", file=sys.stderr)
        return 130
    print(f"\n保存しました: {output}", file=sys.stderr)
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="mp4join", description="MP4 を 1 本に連結します。")
    sub = parser.add_subparsers(dest="command")

    g = sub.add_parser("gui", help="ブラウザで操作する画面を起動（既定）")
    g.add_argument("--port", type=int, default=8765)
    g.add_argument("--no-browser", action="store_true")

    j = sub.add_parser("join", help="コマンドラインで連結")
    j.add_argument("inputs", nargs="+", help="動画ファイル、または動画の入ったフォルダ（指定順に連結）")
    j.add_argument("-o", "--output", required=True)
    j.add_argument("--sort", choices=["none", "gopro", "name", "date"], default="none",
                   help="並べ替え（既定: 指定順のまま）")
    j.add_argument("--fade", type=float, default=0.0, help="つなぎ目のフェード秒数（0 でなし）")
    j.add_argument("--fade-edges", action="store_true", help="動画全体の最初と最後もフェード")
    j.add_argument("--keep-chapters", action="store_true",
                   help="GoPro が自動分割した同じ撮影のつなぎ目はフェードしない")
    j.add_argument("--codec", choices=["h264", "hevc"], default="h264")
    j.add_argument("--quality", choices=["high", "standard", "small"], default="standard")
    j.add_argument("--reencode", action="store_true", help="フェードなしでも再エンコードする")
    j.add_argument("-y", "--yes", action="store_true", help="出力ファイルを上書き")
    j.add_argument("-n", "--dry-run", action="store_true", help="並び順と方式を表示するだけ")

    args = parser.parse_args(argv)
    try:
        if args.command == "join":
            return cmd_join(args)
        from . import server

        server.serve(port=getattr(args, "port", 8765), open_browser=not getattr(args, "no_browser", False))
        return 0
    except core.JoinError as e:
        print(f"\nエラー: {e}", file=sys.stderr)
        return 1
