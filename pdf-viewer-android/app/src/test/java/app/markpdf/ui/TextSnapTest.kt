package app.markpdf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextSnapTest {

    /** 行ごとのインク数。rows の範囲にインクがある */
    private fun profile(len: Int, vararg rows: IntRange): IntArray {
        val p = IntArray(len)
        for (r in rows) for (i in r) p[i] = 50
        return p
    }

    private fun center(p: IntArray, target: Float, reach: Float = 10f) =
        TextSnap.nearestRunCenter(p, p.size, target, minInk = 5, reach = reach, minRun = 3, maxRun = 40)

    @Test
    fun snapsToLineUnderFinger() {
        // 行 40..59（中央 50）の少し下をなぞった
        assertEquals(50f, center(profile(120, 40..59), 56f))
    }

    @Test
    fun picksNearestOfTwoLines() {
        val p = profile(150, 20..39, 60..79)
        assertEquals(30f, center(p, 45f)!!, 0f)
        assertEquals(70f, center(p, 55f)!!, 0f)
    }

    @Test
    fun oneRowGapIsBridged() {
        assertEquals(50f, center(profile(120, 40..48, 50..59), 50f))
    }

    @Test
    fun tooFarIsIgnored() {
        assertNull(center(profile(200, 20..39), 120f))
    }

    @Test
    fun thinRuleAndTallBlockAreIgnored() {
        assertNull(center(profile(200, 50..51), 50f)) // 罫線
        assertNull(center(profile(200, 20..120), 60f)) // 図・写真
    }

    @Test
    fun runCutByWindowEdgeIsIgnored() {
        assertNull(center(profile(100, 0..15), 10f))
    }
}
