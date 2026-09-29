package app.markpdf.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeGeometryTest {

    @Test
    fun nearlyHorizontalSnapsToStartY() {
        val (x, y) = StrokeGeometry.snapEnd(100f, 200f, 400f, 230f, 1000f, 1000f)
        assertEquals(400f, x)
        assertEquals(200f, y)
    }

    @Test
    fun nearlyVerticalSnapsToStartX() {
        val (x, y) = StrokeGeometry.snapEnd(100f, 200f, 120f, 600f, 1000f, 1000f)
        assertEquals(100f, x)
        assertEquals(600f, y)
    }

    @Test
    fun diagonalIsKept() {
        val (x, y) = StrokeGeometry.snapEnd(100f, 100f, 300f, 300f, 1000f, 1000f)
        assertEquals(300f, x)
        assertEquals(300f, y)
    }

    @Test
    fun endIsClampedToPage() {
        val (x, y) = StrokeGeometry.snapEnd(100f, 100f, 1500f, 110f, 1000f, 800f)
        assertEquals(1000f, x)
        assertEquals(100f, y)
    }

    @Test
    fun distanceToSegment() {
        assertEquals(5f, StrokeGeometry.distanceToSegment(50f, 5f, 0f, 0f, 100f, 0f), 1e-4f)
        // 端点より外側は端点までの距離
        assertEquals(5f, StrokeGeometry.distanceToSegment(-3f, 4f, 0f, 0f, 100f, 0f), 1e-4f)
        // 長さ 0 の線分
        assertEquals(5f, StrokeGeometry.distanceToSegment(3f, 4f, 0f, 0f, 0f, 0f), 1e-4f)
    }
}
