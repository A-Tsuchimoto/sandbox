"""ブラウザで操作するローカル GUI。

127.0.0.1 だけで待ち受ける小さな HTTP サーバー。ファイル選択は macOS 標準の
ダイアログ（osascript）を使うので、フルパスをそのまま扱える。
"""

from __future__ import annotations

import hashlib
import json
import os
import secrets
import shutil
import subprocess
import sys
import tempfile
import threading
import webbrowser
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Optional
from urllib.parse import parse_qs, urlparse

from . import core

STATIC_DIR = os.path.join(os.path.dirname(__file__), "static")
IS_MAC = sys.platform == "darwin"


class AppState:
    def __init__(self):
        self.ffmpeg, self.ffprobe = core.require_tools()
        self.token = secrets.token_urlsafe(24)
        self.clips: dict[str, core.Clip] = {}  # 読み込み済み（サムネイル要求はこの中だけ許可）
        self.thumb_dir = tempfile.mkdtemp(prefix="mp4join-thumbs-")
        self.thumb_sem = threading.Semaphore(3)
        self.lock = threading.Lock()
        self.job: Optional[dict] = None
        self.runner: Optional[core.Runner] = None

    # --- clips -------------------------------------------------------------
    def add_paths(self, paths: list[str]) -> tuple[list[dict], list[str]]:
        clips, errors = [], []
        for p in core.expand_inputs(paths):
            try:
                c = self.clips.get(p) or core.probe(p, self.ffprobe)
                self.clips[c.path] = c
                clips.append(c.to_dict())
            except core.JoinError as e:
                errors.append(str(e))
        return clips, errors

    def thumbnail(self, path: str) -> Optional[bytes]:
        clip = self.clips.get(path)
        if not clip:
            return None
        key = hashlib.sha1(f"{path}|{clip.size}".encode()).hexdigest()
        out = os.path.join(self.thumb_dir, key + ".jpg")
        if not os.path.exists(out):
            t = min(1.0, clip.duration / 2)
            with self.thumb_sem:
                subprocess.run(
                    [self.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-ss", f"{t:.2f}", "-i", path,
                     "-frames:v", "1", "-vf", "scale=240:-2", "-q:v", "5", out],
                    capture_output=True,
                )
        try:
            with open(out, "rb") as f:
                return f.read()
        except OSError:
            return None

    # --- job ---------------------------------------------------------------
    def start(self, req: dict) -> dict:
        with self.lock:
            if self.job and self.job["state"] == "running":
                raise core.JoinError("すでに処理中です。")
            paths = req.get("clips") or []
            missing = [p for p in paths if p not in self.clips]
            if missing:
                raise core.JoinError("読み込まれていないファイルがあります: " + ", ".join(missing))
            clips = [self.clips[p] for p in paths]
            output = (req.get("output") or "").strip()
            if not output:
                raise core.JoinError("保存先を指定してください。")
            output = os.path.abspath(os.path.expanduser(output))
            if not output.lower().endswith((".mp4", ".mov", ".m4v")):
                output += ".mp4"
            if os.path.exists(output) and not req.get("overwrite"):
                raise core.JoinError(f"同名のファイルがあります: {output}")
            opts = core.JoinOptions(
                fade=float(req.get("fade") or 0),
                transitions=req.get("transitions"),
                fade_edges=bool(req.get("fade_edges")),
                codec=req.get("codec") or "h264",
                quality=req.get("quality") or "standard",
            )
            plan = core.make_plan(clips, opts)  # 引数のエラーはここで即座に返す
            self.runner = core.Runner(self.ffmpeg, self.ffprobe)
            self.job = {"state": "running", "progress": 0.0, "message": "準備中…", "output": output,
                        "mode": plan.mode, "error": ""}
            threading.Thread(target=self._run, args=(self.runner, clips, output, opts), daemon=True).start()
            return dict(self.job)

    def _run(self, runner: core.Runner, clips, output, opts):
        def progress(p: float, msg: str):
            self.job.update(progress=p, message=msg)

        try:
            runner.join(clips, output, opts, progress)
            self.job.update(state="done", progress=1.0, message="完了しました")
        except core.Cancelled:
            self.job.update(state="cancelled", message="キャンセルしました")
        except core.JoinError as e:
            self.job.update(state="error", error=str(e), message="エラー")
        except Exception as e:  # 想定外のエラーも画面に出す
            self.job.update(state="error", error=f"{type(e).__name__}: {e}", message="エラー")

    def status(self) -> dict:
        return dict(self.job) if self.job else {"state": "idle"}

    def cancel(self):
        if self.runner:
            self.runner.cancel()


# ---------------------------------------------------------------------------
# macOS ダイアログ
# ---------------------------------------------------------------------------


def _osascript(script: str) -> Optional[str]:
    if not IS_MAC:
        raise core.JoinError("ファイル選択ダイアログは macOS でのみ使えます。パスを直接入力してください。")
    proc = subprocess.run(["osascript", "-e", script], capture_output=True, text=True)
    if proc.returncode != 0:
        return None  # キャンセル
    return proc.stdout.strip()


def pick_files() -> list[str]:
    out = _osascript(
        'set fs to choose file with prompt "連結する動画を選択（複数選択可）" '
        'of type {"public.mpeg-4", "com.apple.quicktime-movie"} with multiple selections allowed\n'
        'set out to ""\n'
        'repeat with f in fs\n set out to out & POSIX path of f & linefeed\nend repeat\n'
        'return out'
    )
    return [l for l in (out or "").splitlines() if l]


def pick_folder() -> list[str]:
    out = _osascript('POSIX path of (choose folder with prompt "動画の入ったフォルダを選択（例: GoPro の DCIM/100GOPRO）")')
    return [out] if out else []


def pick_output(default_name: str) -> Optional[str]:
    safe = default_name.replace('"', "")
    return _osascript(f'POSIX path of (choose file name with prompt "保存先" default name "{safe}")')


def reveal(path: str):
    if IS_MAC and os.path.exists(path):
        subprocess.run(["open", "-R", path])


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------


def make_handler(state: AppState):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):  # アクセスログは出さない
            pass

        def _send(self, status: int, body: bytes, ctype: str):
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _json(self, obj, status: int = 200):
            self._send(status, json.dumps(obj, ensure_ascii=False).encode(), "application/json; charset=utf-8")

        def _authorized(self, token: Optional[str]) -> bool:
            # 他のサイトから localhost への CSRF / DNS rebinding を防ぐ
            host = (self.headers.get("Host") or "").split(":")[0]
            return host in ("127.0.0.1", "localhost") and secrets.compare_digest(token or "", state.token)

        def do_GET(self):
            url = urlparse(self.path)
            if url.path == "/":
                with open(os.path.join(STATIC_DIR, "index.html"), encoding="utf-8") as f:
                    html = f.read().replace("__TOKEN__", state.token).replace("__IS_MAC__", "true" if IS_MAC else "false")
                return self._send(200, html.encode(), "text/html; charset=utf-8")
            if url.path == "/api/thumb":
                q = parse_qs(url.query)
                if not self._authorized((q.get("token") or [""])[0]):
                    return self._send(403, b"forbidden", "text/plain")
                data = state.thumbnail((q.get("path") or [""])[0])
                if data is None:
                    return self._send(404, b"", "image/jpeg")
                return self._send(200, data, "image/jpeg")
            if url.path == "/api/status":
                if not self._authorized(self.headers.get("X-Token")):
                    return self._send(403, b"forbidden", "text/plain")
                return self._json(state.status())
            self._send(404, b"not found", "text/plain")

        def do_POST(self):
            if not self._authorized(self.headers.get("X-Token")):
                return self._send(403, b"forbidden", "text/plain")
            length = int(self.headers.get("Content-Length") or 0)
            try:
                req = json.loads(self.rfile.read(length) or b"{}")
            except json.JSONDecodeError:
                return self._json({"error": "bad json"}, 400)
            try:
                return self._json(self._route(urlparse(self.path).path, req))
            except core.JoinError as e:
                return self._json({"error": str(e)}, HTTPStatus.BAD_REQUEST)

        def _route(self, path: str, req: dict):
            if path == "/api/pick-files":
                return dict(zip(("clips", "errors"), state.add_paths(pick_files())))
            if path == "/api/pick-folder":
                return dict(zip(("clips", "errors"), state.add_paths(pick_folder())))
            if path == "/api/add":
                return dict(zip(("clips", "errors"), state.add_paths(req.get("paths") or [])))
            if path == "/api/pick-output":
                return {"path": pick_output(req.get("default_name") or "joined.mp4")}
            if path == "/api/plan":
                clips = [state.clips[p] for p in req.get("clips") or [] if p in state.clips]
                if not clips:
                    return {"plan": None}
                opts = core.JoinOptions(fade=float(req.get("fade") or 0), transitions=req.get("transitions"),
                                        fade_edges=bool(req.get("fade_edges")), codec=req.get("codec") or "h264",
                                        quality=req.get("quality") or "standard")
                return {"plan": core.make_plan(clips, opts).to_dict()}
            if path == "/api/start":
                return state.start(req)
            if path == "/api/cancel":
                state.cancel()
                return {"ok": True}
            if path == "/api/reveal":
                reveal(req.get("path") or "")
                return {"ok": True}
            raise core.JoinError("unknown api")

    return Handler


def serve(port: int = 8765, open_browser: bool = True):
    state = AppState()
    try:
        httpd = ThreadingHTTPServer(("127.0.0.1", port), make_handler(state))
    except OSError:
        httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(state))  # 使用中なら空きポート
    url = f"http://127.0.0.1:{httpd.server_address[1]}/"
    print(f"MP4 Joiner を起動しました: {url}")
    print("終了するにはこのウィンドウで Ctrl+C を押してください。")
    if open_browser:
        threading.Timer(0.5, lambda: webbrowser.open(url)).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n終了します。")
    finally:
        state.cancel()
        httpd.server_close()
        shutil.rmtree(state.thumb_dir, ignore_errors=True)
