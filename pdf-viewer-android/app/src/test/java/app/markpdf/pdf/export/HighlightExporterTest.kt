package app.markpdf.pdf.export

import app.markpdf.pdf.Highlight
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class HighlightExporterTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    private fun hl(id: Long, page: Int, color: Int = 0xFFFFE600.toInt()) =
        Highlight(id, page, color, 0.1f, 0.12f, 0.8f, 0.12f, 0.024f)

    private fun exportAndReparse(name: String, highlights: Map<Int, List<Highlight>>): Pair<ByteArray, ByteArray> {
        val src = fixture(name)
        val out = ByteArrayOutputStream()
        HighlightExporter.export(ByteBuffer.wrap(src), out, highlights, expectedPages = 3)
        val result = out.toByteArray()
        // 検証用に書き出しておく（外部ツールでの確認用）
        File("build/export-test").mkdirs()
        File("build/export-test/out-$name").writeBytes(result)
        return src to result
    }

    private fun annotCount(pdf: PdfFile, page: PdfFile.Page): Int =
        (pdf.resolve(page.dict["Annots"]) as? PdfArray)?.items?.size ?: 0

    private fun check(name: String) {
        val highlights = mapOf(
            0 to listOf(hl(1, 0), hl(2, 0, 0xFF40C4FF.toInt())),
            1 to listOf(hl(3, 1)),
            2 to listOf(hl(4, 2, 0xFFFF80AB.toInt())),
        )
        val (src, result) = exportAndReparse(name, highlights)

        // 元のバイト列はそのまま先頭に残る（増分保存）
        assertArrayEquals(src, result.copyOf(src.size))

        val before = PdfFile(ByteBuffer.wrap(src))
        val after = PdfFile(ByteBuffer.wrap(result))
        val pb = before.pages()
        val pa = after.pages()
        assertEquals(3, pa.size)
        assertEquals(annotCount(before, pb[0]) + 2, annotCount(after, pa[0]))
        assertEquals(annotCount(before, pb[1]) + 1, annotCount(after, pa[1]))
        // 既存の注釈は残る
        assertEquals(annotCount(before, pb[2]) + 1, annotCount(after, pa[2]))
        assertTrue(annotCount(before, pb[2]) >= 1)

        val annot = after.resolve((after.resolve(pa[0].dict["Annots"]) as PdfArray).items.last()) as PdfDict
        assertEquals("Highlight", (annot["Subtype"] as PdfName).raw)
        assertEquals(8, (annot["QuadPoints"] as PdfArray).items.size)
        val ap = after.resolve((annot["AP"] as PdfDict)["N"])
        assertTrue(ap is PdfStream)
        assertEquals(before.usesXrefStream, after.usesXrefStream)
    }

    @Test
    fun classicXref() = check("classic.pdf")

    @Test
    fun xrefStreamWithObjectStreams() = check("objstm.pdf")

    @Test
    fun pageCountMismatchIsRejected() {
        try {
            HighlightExporter.export(ByteBuffer.wrap(fixture("classic.pdf")), ByteArrayOutputStream(), emptyMap(), 5)
            fail()
        } catch (e: HighlightExporter.UnsupportedPdfException) {
            assertEquals(HighlightExporter.Reason.PAGE_MISMATCH, e.reason)
        }
    }

    @Test
    fun garbageIsRejected() {
        try {
            HighlightExporter.export(ByteBuffer.wrap("hello".toByteArray()), ByteArrayOutputStream(), emptyMap(), 1)
            fail()
        } catch (e: HighlightExporter.UnsupportedPdfException) {
            assertEquals(HighlightExporter.Reason.BROKEN, e.reason)
        }
    }

    @Test
    fun rotationMapping() {
        val box = doubleArrayOf(0.0, 0.0, 600.0, 800.0)
        // 左上（表示上）
        assertEquals(0.0 to 800.0, HighlightExporter.toPdf(0.0, 0.0, box, 0))
        // 90° 回転：表示の左上は PDF の左下
        assertEquals(0.0 to 0.0, HighlightExporter.toPdf(0.0, 0.0, box, 90))
        assertEquals(600.0 to 0.0, HighlightExporter.toPdf(0.0, 0.0, box, 180))
        assertEquals(600.0 to 800.0, HighlightExporter.toPdf(0.0, 0.0, box, 270))
    }
}
