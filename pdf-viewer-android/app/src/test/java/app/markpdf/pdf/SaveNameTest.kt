package app.markpdf.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveNameTest {
    @Test
    fun appendsSuffix() = assertEquals("report_highlighted.pdf", SaveName.suggest("report.pdf"))

    @Test
    fun upperCaseExtension() = assertEquals("Scan_highlighted.pdf", SaveName.suggest("Scan.PDF"))

    @Test
    fun doesNotRepeatSuffix() = assertEquals("a_highlighted.pdf", SaveName.suggest("a_highlighted.pdf"))

    @Test
    fun japaneseName() = assertEquals("議事録_highlighted.pdf", SaveName.suggest("議事録.pdf"))
}
