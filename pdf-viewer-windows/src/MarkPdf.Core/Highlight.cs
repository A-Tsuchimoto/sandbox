namespace MarkPdf.Core
{
    /// <summary>
    /// 1 本のハイライト（直線マーカー）。
    /// 座標はページに対する正規化値（x はページ幅、y はページ高さで割った 0..1、左上原点）。
    /// 太さ <see cref="Width"/> はページ幅に対する割合。Android 版と同じ形式。
    /// </summary>
    public sealed class Highlight
    {
        public Highlight(long id, int page, uint color, float x1, float y1, float x2, float y2, float width)
        {
            Id = id;
            Page = page;
            Color = color;
            X1 = x1;
            Y1 = y1;
            X2 = x2;
            Y2 = y2;
            Width = width;
        }

        public long Id { get; }
        public int Page { get; }

        /// <summary>0xAARRGGBB</summary>
        public uint Color { get; }

        public float X1 { get; }
        public float Y1 { get; }
        public float X2 { get; }
        public float Y2 { get; }
        public float Width { get; }
    }
}
