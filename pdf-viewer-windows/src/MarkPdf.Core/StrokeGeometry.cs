using System;

namespace MarkPdf.Core
{
    /// <summary>直線マーカーの幾何計算（Android 版と共通の仕様）。</summary>
    public static class StrokeGeometry
    {
        /// <summary>この角度以内なら水平／垂直に吸着させる。</summary>
        public const double SnapDegrees = 10.0;

        private static readonly double SnapTan = Math.Tan(SnapDegrees * Math.PI / 180);

        /// <summary>
        /// 始点 (sx, sy) から指（マウス）の位置 (x, y) への終点を決める。
        /// ほぼ水平なら y を、ほぼ垂直なら x を始点に揃える。結果は [0, w] × [0, h] に収める。
        /// </summary>
        public static (double x, double y) SnapEnd(double sx, double sy, double x, double y, double w, double h)
        {
            var dx = x - sx;
            var dy = y - sy;
            double ex = x, ey = y;
            if (Math.Abs(dy) <= Math.Abs(dx) * SnapTan) ey = sy;
            else if (Math.Abs(dx) <= Math.Abs(dy) * SnapTan) ex = sx;
            return (MathUtil.Clamp(ex, 0, w), MathUtil.Clamp(ey, 0, h));
        }

        /// <summary>点 (px, py) と線分 (x1, y1)-(x2, y2) の距離。</summary>
        public static double DistanceToSegment(double px, double py, double x1, double y1, double x2, double y2)
        {
            var vx = x2 - x1;
            var vy = y2 - y1;
            var len2 = vx * vx + vy * vy;
            var t = len2 == 0 ? 0 : MathUtil.Clamp(((px - x1) * vx + (py - y1) * vy) / len2, 0, 1);
            var dx = px - (x1 + t * vx);
            var dy = py - (y1 + t * vy);
            return Math.Sqrt(dx * dx + dy * dy);
        }

        /// <summary>
        /// 太さ <paramref name="width"/> の線分を四角形にした四隅（始点側の上, 終点側の上, 始点側の下, 終点側の下）。
        /// 端は平ら（蛍光ペンのような見た目）。
        /// </summary>
        public static double[] Quad(double x1, double y1, double x2, double y2, double width)
        {
            var len = Math.Sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1));
            double ux = 1, uy = 0;
            if (len > 1e-9)
            {
                ux = (x2 - x1) / len;
                uy = (y2 - y1) / len;
            }
            var nx = -uy * width / 2;
            var ny = ux * width / 2;
            return new[]
            {
                x1 + nx, y1 + ny,
                x2 + nx, y2 + ny,
                x1 - nx, y1 - ny,
                x2 - nx, y2 - ny,
            };
        }
    }
}
