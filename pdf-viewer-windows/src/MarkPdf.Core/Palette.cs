namespace MarkPdf.Core
{
    /// <summary>マーカーの色と太さ（Android 版と共通）。</summary>
    public static class Palette
    {
        /// <summary>先頭 <see cref="FreeColors"/> 色は無料、残りは Pro。</summary>
        public static readonly uint[] Colors =
        {
            0xFFFFE600, // イエロー
            0xFFFF80AB, // ピンク
            0xFF69F0AE, // グリーン
            0xFF40C4FF, // ブルー
            0xFFFFAB40, // オレンジ
            0xFFB388FF, // パープル
            0xFFFF5252, // レッド
            0xFFB0BEC5, // グレー
        };

        public static readonly string[] ColorNames =
            { "イエロー", "ピンク", "グリーン", "ブルー", "オレンジ", "パープル", "レッド", "グレー" };

        public const int FreeColors = 2;

        public static bool IsLocked(int index, bool pro) => !pro && index >= FreeColors;

        /// <summary>太さ（ページ幅に対する割合）。A4・10.5pt 本文の行高 ≒ 0.024。</summary>
        public static readonly float[] Widths = { 0.015f, 0.024f, 0.036f };

        public static readonly string[] WidthNames = { "細", "中", "太" };
    }
}
