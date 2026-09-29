package app.markpdf.pdf.export

import app.markpdf.pdf.Highlight
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.hypot

/**
 * ハイライトを PDF 標準の「ハイライト注釈」として書き出す。
 *
 * 元の PDF は 1 バイトも変えず、末尾に追記する（増分保存）。
 * そのため本文・画質・フォントはそのままで、ファイルサイズもほとんど増えない。
 * 他の PDF アプリでも注釈として表示・削除できる。
 */
object HighlightExporter {

    class UnsupportedPdfException(val reason: Reason) : Exception(reason.name)

    enum class Reason { ENCRYPTED, PAGE_MISMATCH, BROKEN }

    /**
     * [src]（元 PDF 全体）に [highlights] を追記した PDF を [out] に書く。
     * @param expectedPages 表示中の PDF のページ数（読み取り結果の検証用）
     */
    fun export(src: ByteBuffer, out: OutputStream, highlights: Map<Int, List<Highlight>>, expectedPages: Int) {
        val pdf = try {
            PdfFile(src)
        } catch (e: PdfFormatException) {
            throw UnsupportedPdfException(Reason.BROKEN)
        }
        if (pdf.trailer["Encrypt"] != null) throw UnsupportedPdfException(Reason.ENCRYPTED)
        val pages = try {
            pdf.pages()
        } catch (e: PdfFormatException) {
            throw UnsupportedPdfException(Reason.BROKEN)
        }
        if (pages.size != expectedPages) throw UnsupportedPdfException(Reason.PAGE_MISMATCH)

        var nextNum = (pdf.trailer["Size"] as? PdfNum)?.int ?: throw UnsupportedPdfException(Reason.BROKEN)
        val objects = ArrayList<Pair<PdfRef, ByteArray>>()

        for ((pageIndex, list) in highlights.toSortedMap()) {
            if (list.isEmpty()) continue
            val page = pages.getOrNull(pageIndex) ?: continue
            val newAnnots = ArrayList<PdfObj>()
            for (h in list) {
                val apRef = PdfRef(nextNum++, 0)
                val annotRef = PdfRef(nextNum++, 0)
                val (annot, ap) = buildAnnotation(h, page, annotRef)
                objects += apRef to ap
                annot["AP"] = PdfDict().also { it["N"] = apRef }
                objects += annotRef to PdfWriter.toBytes(annot)
                newAnnots += annotRef
            }
            // ページ辞書をコピーして /Annots に追加し、同じ番号で上書き定義する
            val dict = PdfDict(LinkedHashMap(page.dict.map))
            val existing = (pdf.resolve(dict["Annots"]) as? PdfArray)?.items ?: emptyList()
            dict["Annots"] = PdfArray((existing + newAnnots).toMutableList())
            objects += page.ref to PdfWriter.toBytes(dict)
        }

        // ---- 書き出し ----
        val base = src.duplicate().apply { position(0) }
        val chunk = ByteArray(64 * 1024)
        while (base.hasRemaining()) {
            val n = minOf(chunk.size, base.remaining())
            base.get(chunk, 0, n)
            out.write(chunk, 0, n)
        }
        val tail = ByteArrayOutputStream()
        var offset = src.limit().toLong()
        if (src.limit() == 0 || src.get(src.limit() - 1) != '\n'.code.toByte()) {
            tail.write('\n'.code)
        }
        val offsets = HashMap<Int, Long>()
        for ((ref, body) in objects) {
            offsets[ref.num] = offset + tail.size()
            PdfWriter.ascii(tail, "${ref.num} ${ref.gen} obj\n")
            tail.write(body)
            PdfWriter.ascii(tail, "\nendobj\n")
        }
        val gens = objects.associate { it.first.num to it.first.gen }

        val trailer = PdfDict()
        pdf.trailer["Root"]?.let { trailer["Root"] = it }
        pdf.trailer["Info"]?.let { trailer["Info"] = it }
        pdf.trailer["ID"]?.let { trailer["ID"] = it }
        trailer["Prev"] = PdfNum(pdf.lastXrefOffset.toString())

        val xrefOffset = offset + tail.size()
        if (pdf.usesXrefStream) {
            // 元が xref ストリームなら、追記分も xref ストリームで書く（無圧縮）
            val xrefNum = nextNum++
            offsets[xrefNum] = xrefOffset
            val nums = (offsets.keys).sorted()
            val rows = ByteArrayOutputStream()
            for (n in nums) {
                val off = offsets.getValue(n)
                rows.write(1)
                for (s in 24 downTo 0 step 8) rows.write(((off shr s) and 0xFF).toInt())
                val g = gens[n] ?: 0
                rows.write((g shr 8) and 0xFF)
                rows.write(g and 0xFF)
            }
            trailer["Type"] = PdfName("XRef")
            trailer["Size"] = PdfNum(nextNum.toString())
            trailer["W"] = PdfArray(mutableListOf(PdfNum("1"), PdfNum("4"), PdfNum("2")))
            trailer["Index"] = PdfArray(runs(nums).flatMap { listOf(PdfNum("${it.first}"), PdfNum("${it.second}")) }.toMutableList())
            trailer["Length"] = PdfNum(rows.size().toString())
            PdfWriter.ascii(tail, "$xrefNum 0 obj\n")
            PdfWriter.write(trailer, tail)
            PdfWriter.ascii(tail, "\nstream\n")
            rows.writeTo(tail)
            PdfWriter.ascii(tail, "\nendstream\nendobj\n")
        } else {
            val nums = offsets.keys.sorted()
            PdfWriter.ascii(tail, "xref\n")
            for ((start, count) in runs(nums)) {
                PdfWriter.ascii(tail, "$start $count\n")
                for (n in start until start + count) {
                    PdfWriter.ascii(tail, String.format(Locale.ROOT, "%010d %05d n\r\n", offsets.getValue(n), gens[n] ?: 0))
                }
            }
            trailer["Size"] = PdfNum(nextNum.toString())
            PdfWriter.ascii(tail, "trailer\n")
            PdfWriter.write(trailer, tail)
            PdfWriter.ascii(tail, "\n")
        }
        PdfWriter.ascii(tail, "startxref\n$xrefOffset\n%%EOF\n")
        tail.writeTo(out)
        out.flush()
    }

    /** 昇順の番号列を連続区間 (開始, 個数) に分ける */
    private fun runs(sorted: List<Int>): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var start = -1
        var count = 0
        for (n in sorted) {
            if (start >= 0 && n == start + count) {
                count++
            } else {
                if (start >= 0) result += start to count
                start = n
                count = 1
            }
        }
        if (start >= 0) result += start to count
        return result
    }

    /** 表示上の正規化座標 (0..1, 左上原点) → PDF 座標（pt, 左下原点）。ページの回転も考慮。 */
    internal fun toPdf(nx: Double, ny: Double, box: DoubleArray, rotate: Int): Pair<Double, Double> {
        val (llx, lly, urx, ury) = box.toList()
        val w = urx - llx
        val h = ury - lly
        return when (rotate) {
            90 -> llx + ny * w to lly + nx * h
            180 -> llx + (1 - nx) * w to lly + ny * h
            270 -> llx + (1 - ny) * w to ury - nx * h
            else -> llx + nx * w to ury - ny * h
        }
    }

    private fun buildAnnotation(h: Highlight, page: PdfFile.Page, annotRef: PdfRef): Pair<PdfDict, ByteArray> {
        val box = page.box
        val displayWidth = if (page.rotate == 90 || page.rotate == 270) box[3] - box[1] else box[2] - box[0]
        var (ax, ay) = toPdf(h.x1.toDouble(), h.y1.toDouble(), box, page.rotate)
        var (bx, by) = toPdf(h.x2.toDouble(), h.y2.toDouble(), box, page.rotate)
        // 左→右（垂直なら下→上）の向きにそろえ、法線が上を向くようにする
        if (bx < ax - 1e-6 || (kotlin.math.abs(bx - ax) <= 1e-6 && by < ay)) {
            ax = bx.also { bx = ax }
            ay = by.also { by = ay }
        }
        val len = hypot(bx - ax, by - ay)
        val (ux, uy) = if (len < 1e-6) 1.0 to 0.0 else (bx - ax) / len to (by - ay) / len
        val t = h.width * displayWidth / 2
        var nx = -uy * t
        var ny = ux * t
        if (ny < 0) {
            nx = -nx
            ny = -ny
        }
        // 四隅：左上, 右上, 左下, 右下（ハイライト注釈の慣例順）
        val q = doubleArrayOf(
            ax + nx, ay + ny,
            bx + nx, by + ny,
            ax - nx, ay - ny,
            bx - nx, by - ny,
        )
        val xs = doubleArrayOf(q[0], q[2], q[4], q[6])
        val ys = doubleArrayOf(q[1], q[3], q[5], q[7])
        val rect = doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
        val r = ((h.color shr 16) and 0xFF) / 255.0
        val g = ((h.color shr 8) and 0xFF) / 255.0
        val b = (h.color and 0xFF) / 255.0

        val annot = PdfDict()
        annot["Type"] = PdfName("Annot")
        annot["Subtype"] = PdfName("Highlight")
        annot["Rect"] = nums(*rect)
        annot["QuadPoints"] = nums(*q)
        annot["C"] = nums(r, g, b)
        annot["CA"] = PdfNum("1")
        annot["F"] = PdfNum("4")
        annot["P"] = page.ref
        annot["NM"] = PdfStr("markpdf-${h.id}-${annotRef.num}".toByteArray())

        // 外観ストリーム：乗算合成で四角形を塗る（どのビューアでも同じ見た目になるように）
        val content = String.format(
            Locale.ROOT,
            "/G0 gs %s %s %s rg %s %s m %s %s l %s %s l %s %s l h f\n",
            fmt(r), fmt(g), fmt(b),
            fmt(q[0]), fmt(q[1]), fmt(q[2]), fmt(q[3]), fmt(q[6]), fmt(q[7]), fmt(q[4]), fmt(q[5]),
        ).toByteArray(Charsets.ISO_8859_1)
        val gs = PdfDict().also {
            it["Type"] = PdfName("ExtGState")
            it["BM"] = PdfName("Multiply")
            it["CA"] = PdfNum("1")
            it["ca"] = PdfNum("1")
        }
        val apDict = PdfDict().also {
            it["Type"] = PdfName("XObject")
            it["Subtype"] = PdfName("Form")
            it["BBox"] = nums(*rect)
            it["Resources"] = PdfDict().also { res ->
                res["ExtGState"] = PdfDict().also { e -> e["G0"] = gs }
            }
            it["Length"] = PdfNum(content.size.toString())
        }
        val ap = ByteArrayOutputStream()
        PdfWriter.write(apDict, ap)
        PdfWriter.ascii(ap, "\nstream\n")
        ap.write(content)
        PdfWriter.ascii(ap, "\nendstream")
        return annot to ap.toByteArray()
    }

    private fun nums(vararg v: Double) = PdfArray(v.map { PdfNum(fmt(it)) }.toMutableList())

    private fun fmt(v: Double): String {
        val s = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }
}
