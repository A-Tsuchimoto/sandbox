package app.markpdf.ui

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 「文字に吸着」：マーカーの線を近くの文字の行（縦書きなら列）の中央に合わせる。
 *
 * PDF のテキスト情報は使わず、表示用に描画したページ画像から「黒い画素が並ぶ行」を探す。
 * そのためスキャンした PDF でも動き、計算も線の周辺だけで軽い。
 */
class TextSnap {

    private var pixels = IntArray(0)
    private var profile = IntArray(0)

    /**
     * 水平な線 (x1..x2, y) を、近くの文字の行の中央に合わせた y を返す（見つからなければ null）。
     * 座標はすべて [bmp] のピクセル単位。[thickness] は線の太さ。
     */
    fun snapHorizontal(bmp: Bitmap, x1: Float, x2: Float, y: Float, thickness: Float): Float? =
        snap(bmp, along1 = x1, along2 = x2, across = y, thickness = thickness, horizontal = true)

    /** 垂直な線 (x, y1..y2) を、近くの文字の列（縦書き）の中央に合わせた x を返す。 */
    fun snapVertical(bmp: Bitmap, y1: Float, y2: Float, x: Float, thickness: Float): Float? =
        snap(bmp, along1 = y1, along2 = y2, across = x, thickness = thickness, horizontal = false)

    private fun snap(
        bmp: Bitmap,
        along1: Float,
        along2: Float,
        across: Float,
        thickness: Float,
        horizontal: Boolean,
    ): Float? {
        val bw = bmp.width
        val bh = bmp.height
        val alongMax = if (horizontal) bw else bh
        val acrossMax = if (horizontal) bh else bw
        val unit = bw.toFloat() // 基準はページ幅

        // 線に沿った範囲（書き始めは短いので最低限の幅を確保）
        var a = min(along1, along2)
        var b = max(along1, along2)
        val minSpan = unit * 0.1f
        if (b - a < minSpan) {
            val mid = (a + b) / 2
            a = mid - minSpan / 2
            b = mid + minSpan / 2
        }
        val a0 = a.roundToInt().coerceIn(0, alongMax - 1)
        val a1 = b.roundToInt().coerceIn(a0 + 1, alongMax)

        // 線に直交する方向の探索範囲
        val maxRun = (unit * MAX_LINE).roundToInt()
        val reach = max(thickness * 0.8f, unit * 0.01f)
        val c0 = (across - reach - maxRun).roundToInt().coerceIn(0, acrossMax - 1)
        val c1 = (across + reach + maxRun).roundToInt().coerceIn(c0 + 1, acrossMax)

        val alongLen = a1 - a0
        val acrossLen = c1 - c0
        val need = alongLen * acrossLen
        if (pixels.size < need) pixels = IntArray(need)
        if (profile.size < acrossLen) profile = IntArray(acrossLen)

        if (horizontal) {
            bmp.getPixels(pixels, 0, alongLen, a0, c0, alongLen, acrossLen)
            for (r in 0 until acrossLen) {
                var n = 0
                val base = r * alongLen
                for (i in 0 until alongLen) if (isInk(pixels[base + i])) n++
                profile[r] = n
            }
        } else {
            bmp.getPixels(pixels, 0, acrossLen, c0, a0, acrossLen, alongLen)
            java.util.Arrays.fill(profile, 0, acrossLen, 0)
            for (r in 0 until alongLen) {
                val base = r * acrossLen
                for (c in 0 until acrossLen) if (isInk(pixels[base + c])) profile[c]++
            }
        }

        val center = nearestRunCenter(
            profile = profile,
            len = acrossLen,
            target = across - c0,
            minInk = max(1, alongLen / 100),
            reach = reach,
            minRun = max(2, (unit * MIN_LINE).roundToInt()),
            maxRun = maxRun,
        ) ?: return null
        return c0 + center
    }

    companion object {
        /** 行の高さとして認める範囲（ページ幅に対する割合） */
        private const val MIN_LINE = 0.004f
        private const val MAX_LINE = 0.08f

        /** 文字（インク）とみなす暗さ */
        fun isInk(argb: Int): Boolean {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return r * 299 + g * 587 + b * 114 < 160_000
        }

        /**
         * [profile]（各行のインク数）から「インクのある行の連なり」を探し、
         * [target] に最も近いもの（距離 [reach] 以内）の中央を返す。
         * 1 行分の空きは同じ連なりとして扱う。端で切れているもの・細すぎ／太すぎるものは除く。
         */
        fun nearestRunCenter(
            profile: IntArray,
            len: Int,
            target: Float,
            minInk: Int,
            reach: Float,
            minRun: Int,
            maxRun: Int,
        ): Float? {
            var best: Float? = null
            var bestDist = Float.MAX_VALUE
            var i = 0
            while (i < len) {
                if (profile[i] < minInk) {
                    i++
                    continue
                }
                val start = i
                var end = i
                var j = i + 1
                while (j < len) {
                    if (profile[j] >= minInk) {
                        end = j
                    } else if (j + 1 < len && profile[j + 1] >= minInk) {
                        // 1 行だけの隙間はつなげる
                    } else {
                        break
                    }
                    j++
                }
                i = end + 1
                val h = end - start + 1
                if (start == 0 || end == len - 1 || h < minRun || h > maxRun) continue
                val dist = when {
                    target < start -> start - target
                    target > end + 1 -> target - (end + 1)
                    else -> 0f
                }
                if (dist <= reach && dist < bestDist) {
                    bestDist = dist
                    best = (start + end + 1) / 2f
                }
            }
            return best?.takeIf { abs(it - target) < reach + maxRun }
        }
    }
}
