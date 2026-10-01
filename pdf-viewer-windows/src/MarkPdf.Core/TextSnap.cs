using System;

namespace MarkPdf.Core
{
    /// <summary>ページ画像の一部を読み出す（0xAARRGGBB、行優先）。</summary>
    public interface IPixelReader
    {
        int Width { get; }
        int Height { get; }
        void Read(int x, int y, int w, int h, int[] buffer);
    }

    /// <summary>
    /// 「文字吸着」：マーカーの線を近くの文字の行（縦書きなら列）の中央に合わせる。
    /// PDF のテキスト情報は使わず、表示用に描いたページ画像から「暗い画素が並ぶ行」を探す（Android 版と同じ方式）。
    /// </summary>
    public sealed class TextSnap
    {
        private const float MinLine = 0.004f;
        private const float MaxLine = 0.08f;

        private int[] _pixels = new int[0];
        private int[] _profile = new int[0];

        /// <summary>水平な線 (x1..x2, y) を文字の行の中央に合わせた y。座標は画像のピクセル単位。</summary>
        public float? SnapHorizontal(IPixelReader img, float x1, float x2, float y, float thickness) =>
            Snap(img, x1, x2, y, thickness, horizontal: true);

        /// <summary>垂直な線 (x, y1..y2) を文字の列（縦書き）の中央に合わせた x。</summary>
        public float? SnapVertical(IPixelReader img, float y1, float y2, float x, float thickness) =>
            Snap(img, y1, y2, x, thickness, horizontal: false);

        private float? Snap(IPixelReader img, float along1, float along2, float across, float thickness, bool horizontal)
        {
            var bw = img.Width;
            var bh = img.Height;
            var alongMax = horizontal ? bw : bh;
            var acrossMax = horizontal ? bh : bw;
            float unit = bw;

            var a = Math.Min(along1, along2);
            var b = Math.Max(along1, along2);
            var minSpan = unit * 0.1f;
            if (b - a < minSpan)
            {
                var mid = (a + b) / 2;
                a = mid - minSpan / 2;
                b = mid + minSpan / 2;
            }
            var a0 = MathUtil.Clamp((int)Math.Round(a), 0, alongMax - 1);
            var a1 = MathUtil.Clamp((int)Math.Round(b), a0 + 1, alongMax);

            var maxRun = (int)Math.Round(unit * MaxLine);
            var reach = Math.Max(thickness * 0.8f, unit * 0.01f);
            var c0 = MathUtil.Clamp((int)Math.Round(across - reach - maxRun), 0, acrossMax - 1);
            var c1 = MathUtil.Clamp((int)Math.Round(across + reach + maxRun), c0 + 1, acrossMax);

            var alongLen = a1 - a0;
            var acrossLen = c1 - c0;
            var need = alongLen * acrossLen;
            if (_pixels.Length < need) _pixels = new int[need];
            if (_profile.Length < acrossLen) _profile = new int[acrossLen];

            if (horizontal)
            {
                img.Read(a0, c0, alongLen, acrossLen, _pixels);
                for (var r = 0; r < acrossLen; r++)
                {
                    var n = 0;
                    var start = r * alongLen;
                    for (var i = 0; i < alongLen; i++) if (IsInk(_pixels[start + i])) n++;
                    _profile[r] = n;
                }
            }
            else
            {
                img.Read(c0, a0, acrossLen, alongLen, _pixels);
                Array.Clear(_profile, 0, acrossLen);
                for (var r = 0; r < alongLen; r++)
                {
                    var start = r * acrossLen;
                    for (var c = 0; c < acrossLen; c++) if (IsInk(_pixels[start + c])) _profile[c]++;
                }
            }

            var center = NearestRunCenter(_profile, acrossLen, across - c0, Math.Max(1, alongLen / 100), reach,
                Math.Max(2, (int)Math.Round(unit * MinLine)), maxRun);
            return center.HasValue ? c0 + center.Value : (float?)null;
        }

        /// <summary>文字（インク）とみなす暗さ。</summary>
        public static bool IsInk(int argb)
        {
            var r = (argb >> 16) & 0xFF;
            var g = (argb >> 8) & 0xFF;
            var b = argb & 0xFF;
            return r * 299 + g * 587 + b * 114 < 160_000;
        }

        /// <summary>
        /// 各行のインク数 <paramref name="profile"/> から「インクのある行の連なり」を探し、
        /// <paramref name="target"/> に最も近いもの（距離 <paramref name="reach"/> 以内）の中央を返す。
        /// 1 行の隙間はつなげる。端で切れたもの・細すぎ／太すぎるものは除く。
        /// </summary>
        public static float? NearestRunCenter(int[] profile, int len, float target, int minInk, float reach, int minRun, int maxRun)
        {
            float? best = null;
            var bestDist = float.MaxValue;
            var i = 0;
            while (i < len)
            {
                if (profile[i] < minInk)
                {
                    i++;
                    continue;
                }
                var start = i;
                var end = i;
                for (var j = i + 1; j < len; j++)
                {
                    if (profile[j] >= minInk) end = j;
                    else if (!(j + 1 < len && profile[j + 1] >= minInk)) break;
                }
                i = end + 1;
                var h = end - start + 1;
                if (start == 0 || end == len - 1 || h < minRun || h > maxRun) continue;
                float dist = target < start ? start - target : target > end + 1 ? target - (end + 1) : 0f;
                if (dist <= reach && dist < bestDist)
                {
                    bestDist = dist;
                    best = (start + end + 1) / 2f;
                }
            }
            return best;
        }
    }
}
