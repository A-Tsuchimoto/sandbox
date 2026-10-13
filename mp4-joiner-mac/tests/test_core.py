"""ffmpeg で小さなテスト動画を作って、実際に連結まで通すテスト。

    python3 -m unittest discover -s tests
"""

import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from mp4join import core  # noqa: E402

FFMPEG = core.find_tool("ffmpeg")


def make_clip(path, seconds=2.0, size="320x240", rate=30, audio=True, color="blue", creation=None):
    args = [FFMPEG, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", f"color=c={color}:s={size}:r={rate}:d={seconds}"]
    if audio:
        args += ["-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=48000:duration={seconds}"]
    args += ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", str(rate)]
    if audio:
        args += ["-c:a", "aac", "-ac", "2", "-shortest"]
    if creation:
        args += ["-metadata", f"creation_time={creation}"]
    args.append(path)
    subprocess.run(args, check=True)
    return path


def ffprobe_json(path):
    return core.probe(path)


@unittest.skipUnless(FFMPEG, "ffmpeg が必要です")
class JoinTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def p(self, name):
        return os.path.join(self.dir, name)

    def test_copy_mode_same_format(self):
        clips = [core.probe(make_clip(self.p(f"c{i}.mp4"), 1.5)) for i in range(3)]
        out = self.p("out.mp4")
        plan = core.Runner().join(clips, out, core.JoinOptions())
        self.assertEqual(plan.mode, "copy")
        self.assertAlmostEqual(core.probe(out).duration, 4.5, delta=0.3)
        self.assertEqual([n for n in os.listdir(self.dir) if n.startswith(".mp4join-")], [])

    def test_fade_reencode(self):
        clips = [core.probe(make_clip(self.p(f"c{i}.mp4"), 2, color=c)) for i, c in enumerate(["white", "white", "white"])]
        out = self.p("out.mp4")
        seen = []
        plan = core.Runner().join(clips, out, core.JoinOptions(fade=0.5, encoder="libx264"),
                                  lambda p, m: seen.append(p))
        self.assertEqual(plan.mode, "reencode")
        self.assertEqual(plan.fades, [(0, 0.5), (0.5, 0.5), (0.5, 0)])
        joined = core.probe(out)
        self.assertAlmostEqual(joined.duration, 6.0, delta=0.3)
        self.assertTrue(joined.has_audio)
        self.assertEqual(seen[-1], 1.0)
        self.assertEqual(seen, sorted(seen))  # 進捗は単調増加

        # つなぎ目（2 秒地点の直前）は暗く、クリップの中央は明るいはず
        def luma(t):
            raw = subprocess.run([FFMPEG, "-loglevel", "error", "-ss", str(t), "-i", out, "-frames:v", "1",
                                  "-vf", "scale=1:1,format=gray", "-f", "rawvideo", "-"],
                                 capture_output=True, check=True).stdout
            return raw[0]
        self.assertLess(luma(1.97), 40)
        self.assertGreater(luma(3.0), 200)

    def test_mixed_formats_and_no_audio(self):
        a = core.probe(make_clip(self.p("a.mp4"), 1, size="320x240", rate=30))
        b = core.probe(make_clip(self.p("b.mp4"), 1, size="640x360", rate=25, audio=False))
        c = core.probe(make_clip(self.p("c.mp4"), 1, size="320x240", rate=30))
        self.assertFalse(b.has_audio)
        out = self.p("out.mp4")
        plan = core.Runner().join([a, b, c], out, core.JoinOptions(encoder="libx264"))
        self.assertEqual(plan.mode, "reencode")
        j = core.probe(out)
        self.assertEqual((j.width, j.height, j.fps), (320, 240, "30/1"))
        self.assertTrue(j.has_audio)
        self.assertAlmostEqual(j.duration, 3.0, delta=0.3)

    def test_many_clips_and_quote_in_path(self):
        src = make_clip(self.p("base.mp4"), 0.5)
        paths = []
        for i in range(50):
            dst = self.p(f"it's clip {i:02d}.mp4")
            shutil.copy(src, dst)
            paths.append(dst)
        clips = [core.probe(p) for p in paths]
        out = self.p("out.mp4")
        plan = core.Runner().join(clips, out, core.JoinOptions())
        self.assertEqual(plan.mode, "copy")
        self.assertAlmostEqual(core.probe(out).duration, 25.0, delta=1.0)

    def test_cancel(self):
        clips = [core.probe(make_clip(self.p(f"c{i}.mp4"), 1)) for i in range(2)]
        runner = core.Runner()
        runner.cancel()
        with self.assertRaises(core.Cancelled):
            runner.join(clips, self.p("out.mp4"), core.JoinOptions(fade=0.5, encoder="libx264"))
        self.assertFalse(os.path.exists(self.p("out.mp4")))

    def test_output_same_as_input_rejected(self):
        c = core.probe(make_clip(self.p("a.mp4"), 1))
        with self.assertRaises(core.JoinError):
            core.Runner().join([c], c.path, core.JoinOptions())

    def test_expand_folder(self):
        make_clip(self.p("GX020001.MP4"), 0.5)
        make_clip(self.p("GX010001.MP4"), 0.5)
        open(self.p("notes.txt"), "w").close()
        open(self.p("._GX010001.MP4"), "w").close()  # macOS が外付けディスクに作るゴミファイル
        names = [os.path.basename(p) for p in core.expand_inputs([self.dir])]
        self.assertEqual(names, ["GX010001.MP4", "GX020001.MP4"])


class PlanTest(unittest.TestCase):
    def clip(self, name, dur=10.0, **kw):
        base = dict(path="/x/" + name, name=name, size=1000, duration=dur, width=1920, height=1080, fps="30/1",
                    vcodec="h264", pix_fmt="yuv420p", vbitrate=50_000_000, has_audio=True, acodec="aac",
                    sample_rate=48000, channels=2)
        base.update(kw)
        return core.Clip(**base)

    def test_transitions_and_edges(self):
        clips = [self.clip("a"), self.clip("b"), self.clip("c")]
        plan = core.make_plan(clips, core.JoinOptions(fade=2, transitions=[False, True], fade_edges=True))
        self.assertEqual(plan.fades, [(2, 0), (0, 2), (2, 2)])

    def test_short_clip_fade_clamped(self):
        clips = [self.clip("a"), self.clip("b", dur=1.0), self.clip("c")]
        plan = core.make_plan(clips, core.JoinOptions(fade=2))
        self.assertEqual(plan.fades[1], (0.5, 0.5))

    def test_fade_zero_is_copy(self):
        plan = core.make_plan([self.clip("a"), self.clip("b")], core.JoinOptions(fade=0, transitions=[True]))
        self.assertEqual(plan.mode, "copy")

    def test_most_common_resolution(self):
        clips = [self.clip("a", width=3840, height=2160), self.clip("b"), self.clip("c")]
        plan = core.make_plan(clips, core.JoinOptions())
        self.assertEqual((plan.width, plan.height), (1920, 1080))


class GoProTest(unittest.TestCase):
    def test_gopro_sort(self):
        names = ["GX020124.MP4", "GX010125.MP4", "GX010124.MP4", "GH010123.MP4", "GP010122.MP4", "GOPR0122.MP4",
                 "IMG_0001.MP4"]
        clips = [core.Clip(path=n, name=n, size=0, duration=1, width=1, height=1, fps="30/1", vcodec="h264",
                           pix_fmt="", vbitrate=0, has_audio=False) for n in names]
        got = [c.name for c in core.sort_clips(clips, "gopro")]
        self.assertEqual(got, ["GOPR0122.MP4", "GP010122.MP4", "GH010123.MP4", "GX010124.MP4", "GX020124.MP4",
                               "GX010125.MP4", "IMG_0001.MP4"])

    def test_same_recording(self):
        self.assertTrue(core.is_same_recording("GX010124.MP4", "GX020124.MP4"))
        self.assertTrue(core.is_same_recording("GOPR0122.MP4", "GP010122.MP4"))
        self.assertFalse(core.is_same_recording("GX010124.MP4", "GX010125.MP4"))
        self.assertFalse(core.is_same_recording("GX020124.MP4", "GX010124.MP4"))
        self.assertFalse(core.is_same_recording("a.mp4", "b.mp4"))


if __name__ == "__main__":
    unittest.main()
