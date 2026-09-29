package app.markpdf.ui

import app.markpdf.R

/** マーカーの色と太さの定義。 */
object Palette {
    /** 先頭 [FREE_COLORS] 色は無料、残りは Pro。 */
    val colors = intArrayOf(
        0xFFFFE600.toInt(), // イエロー
        0xFFFF80AB.toInt(), // ピンク
        0xFF69F0AE.toInt(), // グリーン
        0xFF40C4FF.toInt(), // ブルー
        0xFFFFAB40.toInt(), // オレンジ
        0xFFB388FF.toInt(), // パープル
        0xFFFF5252.toInt(), // レッド
        0xFFB0BEC5.toInt(), // グレー
    )

    const val FREE_COLORS = 2

    fun isLocked(index: Int, pro: Boolean) = !pro && index >= FREE_COLORS

    /** 太さ（ページ幅に対する割合）。A4・10.5pt 本文の行高 ≒ 0.024。 */
    val widths = floatArrayOf(0.015f, 0.024f, 0.036f)
    val widthLabels = intArrayOf(R.string.width_thin, R.string.width_medium, R.string.width_thick)
}
