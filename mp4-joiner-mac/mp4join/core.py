"""ffmpeg / ffprobe を使った MP4 連結のコア処理。

GUI(server.py) と CLI(cli.py) の両方から使う。標準ライブラリのみに依存し、
外部コマンドとして ffmpeg / ffprobe を呼び出す。

連結方式は 2 通り:

* 高速コピー（フェードなし & 全クリップの形式が同じ）
    concat demuxer + ``-c copy`` で再エンコードせずに繋ぐ。数十 GB でも数分。
* 再エンコード（フェードあり、または形式が混在）
    クリップごとにフェード付きで同一設定の中間ファイルへエンコードし、
    最後に中間ファイルを ``-c copy`` で繋ぐ。1 クリップずつ処理するので
    50 本でもメモリを食わない。
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
from collections import Counter
from dataclasses import asdict, dataclass, field
from fractions import Fraction
from typing import Callable, Iterable, Optional

VIDEO_EXTS = {".mp4", ".mov", ".m4v"}

# Finder から起動すると PATH に Homebrew が入っていないことがあるので補う
_EXTRA_BIN_DIRS = ["/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin"]


class JoinError(Exception):
    """ユーザーに見せるエラー。"""


class Cancelled(Exception):
    pass


def find_tool(name: str) -> Optional[str]:
    found = shutil.which(name)
    if found:
        return found
    for d in _EXTRA_BIN_DIRS:
        p = os.path.join(d, name)
        if os.access(p, os.X_OK):
            return p
    return None


def require_tools() -> tuple[str, str]:
    ffmpeg, ffprobe = find_tool("ffmpeg"), find_tool("ffprobe")
    if not ffmpeg or not ffprobe:
        raise JoinError(
            "ffmpeg が見つかりません。ターミナルで `brew install ffmpeg` を実行してから再度起動してください。"
        )
    return ffmpeg, ffprobe


# ---------------------------------------------------------------------------
# クリップ情報
# ---------------------------------------------------------------------------


@dataclass
class Clip:
    path: str
    name: str
    size: int
    duration: float
    width: int
    height: int
    fps: str  # "30000/1001" のような分数文字列
    vcodec: str
    pix_fmt: str
    vbitrate: int
    has_audio: bool
    acodec: str = ""
    sample_rate: int = 0
    channels: int = 0
    creation_time: str = ""

    def to_dict(self) -> dict:
        d = asdict(self)
        d["fps_value"] = round(float(Fraction(self.fps)), 3) if self.fps else 0
        return d

    def copy_signature(self) -> tuple:
        """この値が全クリップで一致すれば -c copy で連結できる。"""
        return (
            self.vcodec,
            self.width,
            self.height,
            self.pix_fmt,
            self.fps,
            self.has_audio,
            self.acodec,
            self.sample_rate,
            self.channels,
        )


def _parse_rate(s: Optional[str]) -> Optional[str]:
    if not s or s in ("0/0", "0"):
        return None
    try:
        f = Fraction(s)
    except (ValueError, ZeroDivisionError):
        return None
    if f <= 0 or f > 1000:
        return None
    return f"{f.numerator}/{f.denominator}"


def _rotation(stream: dict) -> int:
    for sd in stream.get("side_data_list") or []:
        if "rotation" in sd:
            try:
                return int(float(sd["rotation"]))
            except (TypeError, ValueError):
                pass
    try:
        return int(float((stream.get("tags") or {}).get("rotate", 0)))
    except (TypeError, ValueError):
        return 0


def probe(path: str, ffprobe: Optional[str] = None) -> Clip:
    ffprobe = ffprobe or require_tools()[1]
    path = os.path.abspath(os.path.expanduser(path))
    if not os.path.isfile(path):
        raise JoinError(f"ファイルが見つかりません: {path}")
    proc = subprocess.run(
        [ffprobe, "-v", "error", "-print_format", "json", "-show_format", "-show_streams", path],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        raise JoinError(f"動画として読み込めません: {os.path.basename(path)}\n{proc.stderr.strip()}")
    info = json.loads(proc.stdout or "{}")
    streams = info.get("streams") or []
    fmt = info.get("format") or {}

    video = next(
        (
            s
            for s in streams
            if s.get("codec_type") == "video" and not (s.get("disposition") or {}).get("attached_pic")
        ),
        None,
    )
    if video is None:
        raise JoinError(f"映像トラックがありません: {os.path.basename(path)}")
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)

    width, height = int(video.get("width") or 0), int(video.get("height") or 0)
    if abs(_rotation(video)) % 180 == 90:
        width, height = height, width

    duration = 0.0
    for src in (video.get("duration"), fmt.get("duration")):
        try:
            duration = float(src)
            if duration > 0:
                break
        except (TypeError, ValueError):
            continue

    fps = _parse_rate(video.get("avg_frame_rate")) or _parse_rate(video.get("r_frame_rate")) or "30/1"

    def _int(v) -> int:
        try:
            return int(v)
        except (TypeError, ValueError):
            return 0

    tags = fmt.get("tags") or {}
    return Clip(
        path=path,
        name=os.path.basename(path),
        size=os.path.getsize(path),
        duration=duration,
        width=width,
        height=height,
        fps=fps,
        vcodec=video.get("codec_name", ""),
        pix_fmt=video.get("pix_fmt", ""),
        vbitrate=_int(video.get("bit_rate")) or _int(fmt.get("bit_rate")),
        has_audio=audio is not None,
        acodec=(audio or {}).get("codec_name", ""),
        sample_rate=_int((audio or {}).get("sample_rate")),
        channels=_int((audio or {}).get("channels")),
        creation_time=tags.get("creation_time", ""),
    )


def expand_inputs(paths: Iterable[str]) -> list[str]:
    """ファイルはそのまま、フォルダは中の動画ファイル（直下のみ）に展開する。"""
    out: list[str] = []
    for p in paths:
        p = os.path.abspath(os.path.expanduser(p.strip()))
        if os.path.isdir(p):
            for name in sorted(os.listdir(p)):
                full = os.path.join(p, name)
                if (
                    not name.startswith(".")
                    and os.path.splitext(name)[1].lower() in VIDEO_EXTS
                    and os.path.isfile(full)
                ):
                    out.append(full)
        else:
            out.append(p)
    return out


# ---------------------------------------------------------------------------
# 並べ替え
# ---------------------------------------------------------------------------

# GoPro のファイル名規則
#   HERO5 以前:  GOPR1234.MP4（1 本目）, GP011234.MP4, GP021234.MP4 …（分割の続き）
#   HERO6 以降:  GH011234.MP4, GH021234.MP4 …（GX=HEVC, GL=低解像度プロキシ）
# 名前順だと「チャプター番号 → ファイル番号」の順になってしまうので、
# 「ファイル番号 → チャプター番号」で並べる。
_GOPRO_OLD_FIRST = re.compile(r"^GOPR(\d{4})\.", re.I)
_GOPRO_CHAPTER = re.compile(r"^G[HXPL](\d{2})(\d{4})\.", re.I)


def gopro_id(name: str) -> Optional[tuple[int, int]]:
    """(ファイル番号, チャプター番号) を返す。GoPro 形式でなければ None。"""
    m = _GOPRO_OLD_FIRST.match(name)
    if m:
        return int(m.group(1)), 0
    m = _GOPRO_CHAPTER.match(name)
    if m:
        return int(m.group(2)), int(m.group(1))
    return None


def _natural_key(s: str) -> list:
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def sort_key_gopro(name: str):
    gid = gopro_id(name)
    return (0, gid, []) if gid else (1, (0, 0), _natural_key(name))


def is_same_recording(prev_name: str, next_name: str) -> bool:
    """GoPro が自動分割した同じ撮影の連続チャプターか（つなぎ目にフェード不要）。"""
    a, b = gopro_id(prev_name), gopro_id(next_name)
    if not a or not b:
        return False
    if a[0] != b[0]:
        return False
    # GOPR1234 (0) → GP011234 (1)、GH011234 (1) → GH021234 (2)
    return b[1] == a[1] + 1


def sort_clips(clips: list[Clip], how: str) -> list[Clip]:
    if how == "gopro":
        return sorted(clips, key=lambda c: sort_key_gopro(c.name))
    if how == "name":
        return sorted(clips, key=lambda c: _natural_key(c.name))
    if how == "date":
        # 撮影日時が無いものは末尾へ。同時刻は GoPro 順で安定させる
        return sorted(clips, key=lambda c: (c.creation_time == "", c.creation_time, sort_key_gopro(c.name)))
    raise ValueError(how)


# ---------------------------------------------------------------------------
# 連結プラン
# ---------------------------------------------------------------------------

QUALITY_BPP = {"high": 0.12, "standard": 0.08, "small": 0.05}  # H.264 の bits / pixel
QUALITY_CRF = {"high": (18, 21), "standard": (21, 24), "small": (25, 28)}  # (x264, x265)


@dataclass
class JoinOptions:
    fade: float = 0.0  # フェード秒数。0 ならフェードなし
    transitions: Optional[list[bool]] = None  # つなぎ目ごとのフェード有無（len = n-1）。None なら全部
    fade_edges: bool = False  # 動画全体の最初と最後もフェードする
    codec: str = "h264"  # h264 / hevc
    quality: str = "standard"  # high / standard / small
    force_reencode: bool = False
    encoder: Optional[str] = None  # 明示指定（テスト用）。None なら自動


@dataclass
class Plan:
    mode: str  # "copy" or "reencode"
    reason: str
    width: int = 0
    height: int = 0
    fps: str = ""
    fades: list[tuple[float, float]] = field(default_factory=list)  # クリップごとの (in秒, out秒)
    total_duration: float = 0.0
    estimated_bytes: int = 0

    def to_dict(self) -> dict:
        return asdict(self)


def _most_common(values):
    return Counter(values).most_common(1)[0][0]


def make_plan(clips: list[Clip], opts: JoinOptions) -> Plan:
    if not clips:
        raise JoinError("動画が 1 本もありません。")
    n = len(clips)
    trans = list(opts.transitions) if opts.transitions is not None else [True] * (n - 1)
    if len(trans) != n - 1:
        raise JoinError("つなぎ目の指定数がクリップ数と合いません。")
    fade = max(0.0, float(opts.fade or 0))
    if fade == 0:
        trans = [False] * (n - 1)
    edges = fade > 0 and opts.fade_edges

    total = sum(c.duration for c in clips)
    any_fade = any(trans) or edges
    same_format = len({c.copy_signature() for c in clips}) == 1

    if not any_fade and same_format and not opts.force_reencode:
        return Plan(
            mode="copy",
            reason="全クリップが同じ形式なので再エンコードせず高速に連結します。",
            width=clips[0].width,
            height=clips[0].height,
            fps=clips[0].fps,
            fades=[(0.0, 0.0)] * n,
            total_duration=total,
            estimated_bytes=sum(c.size for c in clips),
        )

    if any_fade:
        reason = "フェード処理のため再エンコードします。"
    elif opts.force_reencode:
        reason = "再エンコードが指定されています。"
    else:
        reason = "解像度・フレームレート・コーデック等が異なるクリップがあるため、形式を揃えて再エンコードします。"

    width = _most_common((c.width, c.height) for c in clips)
    fps = _most_common(c.fps for c in clips)

    fades = []
    for i, c in enumerate(clips):
        fin = fade if (i > 0 and trans[i - 1]) or (i == 0 and edges) else 0.0
        fout = fade if (i < n - 1 and trans[i]) or (i == n - 1 and edges) else 0.0
        # 短いクリップではフェードが重ならないよう縮める
        limit = c.duration / 2 if fin and fout else c.duration
        fades.append((min(fin, limit), min(fout, limit)))

    w, h = width
    bitrate = _target_bitrate(w, h, fps, opts.codec, opts.quality, clips)
    return Plan(
        mode="reencode",
        reason=reason,
        width=w,
        height=h,
        fps=fps,
        fades=fades,
        total_duration=total,
        estimated_bytes=int(total * (bitrate + 192_000) / 8),
    )


def _target_bitrate(w: int, h: int, fps: str, codec: str, quality: str, clips: list[Clip]) -> int:
    bpp = QUALITY_BPP.get(quality, QUALITY_BPP["standard"]) * (0.6 if codec == "hevc" else 1.0)
    rate = int(w * h * float(Fraction(fps)) * bpp)
    # 元動画より大きくしても画質は上がらないので頭打ちにする
    src = max((c.vbitrate for c in clips), default=0)
    if src:
        rate = min(rate, src)
    return max(rate, 1_000_000)


# ---------------------------------------------------------------------------
# エンコーダ
# ---------------------------------------------------------------------------

_encoder_cache: dict[str, set[str]] = {}


def available_encoders(ffmpeg: str) -> set[str]:
    if ffmpeg not in _encoder_cache:
        proc = subprocess.run([ffmpeg, "-hide_banner", "-encoders"], capture_output=True, text=True)
        names = set()
        for line in proc.stdout.splitlines():
            parts = line.split()
            if len(parts) >= 2 and len(parts[0]) == 6 and parts[0][0] in "VAS":
                names.add(parts[1])
        _encoder_cache[ffmpeg] = names
    return _encoder_cache[ffmpeg]


def video_encoder_args(ffmpeg: str, opts: JoinOptions, plan: Plan, clips: list[Clip]) -> list[str]:
    encs = available_encoders(ffmpeg)
    hevc = opts.codec == "hevc"
    hw = "hevc_videotoolbox" if hevc else "h264_videotoolbox"
    sw = "libx265" if hevc else "libx264"
    enc = opts.encoder or (hw if hw in encs else sw)
    if enc not in encs:
        raise JoinError(f"ffmpeg にエンコーダ {enc} がありません。")

    args = ["-c:v", enc]
    if enc.endswith("_videotoolbox"):
        # Mac のハードウェアエンコーダ（高速）。ビットレートで画質を決める
        rate = _target_bitrate(plan.width, plan.height, plan.fps, opts.codec, opts.quality, clips)
        args += ["-b:v", str(rate), "-maxrate", str(int(rate * 1.5)), "-bufsize", str(rate * 2)]
        if not hevc:
            args += ["-profile:v", "high"]
    else:
        crf = QUALITY_CRF.get(opts.quality, QUALITY_CRF["standard"])[1 if hevc else 0]
        args += ["-crf", str(crf), "-preset", "medium"]
        if enc == "libx265":
            args += ["-x265-params", "log-level=error"]
    if hevc:
        args += ["-tag:v", "hvc1"]  # QuickTime / 写真.app で再生できるようにする
    # GOP を揃えておくと連結後のシークが安定する
    gop = max(1, round(float(Fraction(plan.fps)) * 2))
    args += ["-g", str(gop)]
    return args


# ---------------------------------------------------------------------------
# 実行
# ---------------------------------------------------------------------------

ProgressFn = Callable[[float, str], None]


def _concat_list_line(path: str) -> str:
    return "file '" + path.replace("'", "'\\''") + "'\n"


def _fmt_time(sec: float) -> str:
    sec = int(round(sec))
    h, rem = divmod(sec, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


class Runner:
    """1 回分の連結処理。別スレッドから cancel() できる。"""

    def __init__(self, ffmpeg: Optional[str] = None, ffprobe: Optional[str] = None):
        if not ffmpeg or not ffprobe:
            ffmpeg, ffprobe = require_tools()
        self.ffmpeg = ffmpeg
        self.ffprobe = ffprobe
        self._proc: Optional[subprocess.Popen] = None
        self._cancel = threading.Event()
        self.log: list[str] = []

    def cancel(self):
        self._cancel.set()
        proc = self._proc
        if proc and proc.poll() is None:
            proc.terminate()

    # ffmpeg を -progress 付きで走らせ、out_time を on_time(秒) に流す
    def _run(self, args: list[str], on_time: Callable[[float], None]):
        if self._cancel.is_set():
            raise Cancelled()
        cmd = [self.ffmpeg, "-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-progress", "pipe:1", "-nostats"] + args
        self.log.append(" ".join(_shell_quote(a) for a in cmd))
        stderr_file = tempfile.TemporaryFile(mode="w+")
        self._proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=stderr_file, text=True)
        try:
            assert self._proc.stdout is not None
            for line in self._proc.stdout:
                key, _, val = line.strip().partition("=")
                if key in ("out_time_us", "out_time_ms"):  # 両方とも実際はマイクロ秒
                    try:
                        on_time(max(0.0, int(val) / 1_000_000))
                    except ValueError:
                        pass
            rc = self._proc.wait()
        finally:
            if self._proc.stdout:
                self._proc.stdout.close()
            stderr_file.seek(0)
            err = stderr_file.read().strip()
            stderr_file.close()
            self._proc = None
        if self._cancel.is_set():
            raise Cancelled()
        if rc != 0:
            if err:
                self.log.append(err)
            raise JoinError("ffmpeg がエラーで終了しました:\n" + (err[-2000:] or f"exit code {rc}"))

    def join(self, clips: list[Clip], output: str, opts: JoinOptions, progress: Optional[ProgressFn] = None) -> Plan:
        progress = progress or (lambda p, m: None)
        output = os.path.abspath(os.path.expanduser(output))
        out_dir = os.path.dirname(output)
        if not os.path.isdir(out_dir):
            raise JoinError(f"保存先フォルダがありません: {out_dir}")
        if any(os.path.realpath(c.path) == os.path.realpath(output) for c in clips):
            raise JoinError("保存先が入力ファイルと同じです。別の名前を指定してください。")

        plan = make_plan(clips, opts)
        # 中間ファイル + 出力で最大 2 倍弱を使う
        need = plan.estimated_bytes * (2 if plan.mode == "reencode" else 1)
        free = shutil.disk_usage(out_dir).free
        if need > free:
            raise JoinError(
                f"保存先の空き容量が足りません（必要 約{need / 1e9:.1f} GB / 空き {free / 1e9:.1f} GB）。"
            )

        # 中間ファイルは出力と同じディスクに置く（外付け SSD に出力する場合など）
        work = tempfile.mkdtemp(prefix=".mp4join-", dir=out_dir)
        tmp_out = os.path.join(work, "joined" + os.path.splitext(output)[1])
        try:
            if plan.mode == "copy":
                self._join_copy(clips, plan, tmp_out, work, progress)
            else:
                self._join_reencode(clips, plan, opts, tmp_out, work, progress)
            os.replace(tmp_out, output)
            progress(1.0, "完了しました")
        finally:
            shutil.rmtree(work, ignore_errors=True)
        return plan

    def _join_copy(self, clips, plan, out, work, progress):
        list_path = os.path.join(work, "list.txt")
        with open(list_path, "w", encoding="utf-8") as f:
            for c in clips:
                f.write(_concat_list_line(c.path))
        total = plan.total_duration or 1
        args = ["-f", "concat", "-safe", "0", "-i", list_path, "-map", "0:v:0", "-map", "0:a:0?", "-c", "copy"]
        if clips[0].vcodec == "hevc":
            args += ["-tag:v", "hvc1"]
        args += ["-movflags", "+faststart", out]
        progress(0.0, f"{len(clips)} 本を高速連結中…")
        self._run(args, lambda t: progress(min(t / total, 0.999), f"高速連結中… {_fmt_time(t)} / {_fmt_time(total)}"))

    def _join_reencode(self, clips, plan, opts, out, work, progress):
        venc = video_encoder_args(self.ffmpeg, opts, plan, clips)
        total = plan.total_duration or 1
        encode_share = 0.97  # 最後の連結（コピー）は速いので残りわずか
        done = 0.0
        parts = []
        W, H = plan.width, plan.height
        for i, c in enumerate(clips):
            fin, fout = plan.fades[i]
            part = os.path.join(work, f"part{i:03d}.mp4")
            vf = (
                f"[0:v:0]scale={W}:{H}:force_original_aspect_ratio=decrease,"
                f"pad={W}:{H}:(ow-iw)/2:(oh-ih)/2,setsar=1,fps={plan.fps},format=yuv420p,setpts=PTS-STARTPTS"
            )
            af = "aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,asetpts=PTS-STARTPTS"
            if fin:
                vf += f",fade=t=in:st=0:d={fin:.3f}"
                af += f",afade=t=in:st=0:d={fin:.3f}"
            if fout:
                st = max(0.0, c.duration - fout)
                vf += f",fade=t=out:st={st:.3f}:d={fout:.3f}"
                af += f",afade=t=out:st={st:.3f}:d={fout:.3f}"
            args = ["-i", c.path]
            if c.has_audio:
                af = "[0:a:0]" + af
            else:
                # 音声なしのクリップには無音を付けて、全体の音声トラックを揃える
                args += ["-f", "lavfi", "-t", f"{c.duration:.3f}", "-i", "anullsrc=r=48000:cl=stereo"]
                af = "[1:a]" + af
            args += [
                "-filter_complex", f"{vf}[v];{af}[a]",
                "-map", "[v]", "-map", "[a]",
                "-t", f"{c.duration:.3f}",
                *venc,
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000",
                "-video_track_timescale", "90000",
                "-map_metadata", "-1",
                part,
            ]
            label = f"エンコード中 {i + 1}/{len(clips)}: {c.name}"
            progress(done / total * encode_share, label)
            base = done
            self._run(args, lambda t, base=base, d=c.duration, label=label: progress(
                (base + min(t, d)) / total * encode_share, label))
            done += c.duration
            parts.append(part)

        list_path = os.path.join(work, "list.txt")
        with open(list_path, "w", encoding="utf-8") as f:
            for p in parts:
                f.write(_concat_list_line(p))
        args = ["-f", "concat", "-safe", "0", "-i", list_path, "-map", "0:v:0", "-map", "0:a:0", "-c", "copy"]
        if opts.codec == "hevc":
            args += ["-tag:v", "hvc1"]
        args += ["-movflags", "+faststart", out]
        self._run(args, lambda t: progress(encode_share + (1 - encode_share) * min(t / total, 0.999), "1 本にまとめています…"))


def _shell_quote(s: str) -> str:
    return s if re.fullmatch(r"[\w@%+=:,./-]+", s) else "'" + s.replace("'", "'\\''") + "'"
