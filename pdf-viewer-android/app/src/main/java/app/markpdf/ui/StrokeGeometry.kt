package app.markpdf.ui

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.tan

/** 直線マーカーの幾何計算（Android 非依存・単体テスト可能）。 */
object StrokeGeometry {

    /** この角度以内なら水平／垂直に吸着させる。 */
    const val SNAP_DEGREES = 10.0

    private val snapTan = tan(Math.toRadians(SNAP_DEGREES)).toFloat()

    /**
     * 始点 (sx, sy) から指の位置 (x, y) への終点を決める。
     * ほぼ水平なら y を始点に揃え（横書きの行）、ほぼ垂直なら x を揃える（縦書きの行）。
     * 結果は [0, w] × [0, h] に収める。
     */
    fun snapEnd(sx: Float, sy: Float, x: Float, y: Float, w: Float, h: Float): Pair<Float, Float> {
        val dx = x - sx
        val dy = y - sy
        val (ex, ey) = when {
            abs(dy) <= abs(dx) * snapTan -> x to sy
            abs(dx) <= abs(dy) * snapTan -> sx to y
            else -> x to y
        }
        return ex.coerceIn(0f, w) to ey.coerceIn(0f, h)
    }

    /** 点 (px, py) と線分 (x1, y1)-(x2, y2) の距離。 */
    fun distanceToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val vx = x2 - x1
        val vy = y2 - y1
        val len2 = vx * vx + vy * vy
        val t = if (len2 == 0f) 0f else (((px - x1) * vx + (py - y1) * vy) / len2).coerceIn(0f, 1f)
        return hypot(px - (x1 + t * vx), py - (y1 + t * vy))
    }
}
