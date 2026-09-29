package app.markpdf.pdf.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * 既存 PDF の読み取り（相互参照表・オブジェクト・ページツリー）。
 * 相互参照は従来の xref 表と xref ストリーム（PDF 1.5+、オブジェクトストリーム）の両方に対応する。
 */
class PdfFile(private val buf: ByteBuffer) {

    private sealed interface Entry
    private class InFile(val offset: Int) : Entry
    private class InObjStm(val stmNum: Int, val index: Int) : Entry

    private val entries = HashMap<Int, Entry>()
    private val objStmCache = HashMap<Int, Pair<ByteBuffer, IntArray>>()

    /** 最新の trailer（xref ストリームの場合はその辞書） */
    val trailer: PdfDict

    /** 最新の相互参照の位置（増分保存の /Prev に使う） */
    val lastXrefOffset: Int

    /** 最新の相互参照が xref ストリームかどうか */
    val usesXrefStream: Boolean

    val size: Int get() = buf.limit()

    init {
        lastXrefOffset = findStartXref()
        var first: PdfDict? = null
        var firstIsStream = false
        val visited = HashSet<Int>()
        var off: Int? = lastXrefOffset
        while (off != null && visited.add(off)) {
            val (t, isStream) = readXrefSection(off)
            if (first == null) {
                first = t
                firstIsStream = isStream
            }
            // ハイブリッド形式：xref 表に加えて xref ストリームがある
            (t["XRefStm"] as? PdfNum)?.let { if (visited.add(it.int)) readXrefSection(it.int) }
            off = (t["Prev"] as? PdfNum)?.int
        }
        trailer = first ?: throw PdfFormatException("trailer がない")
        usesXrefStream = firstIsStream
    }

    private fun findStartXref(): Int {
        val kw = "startxref".toByteArray()
        val from = maxOf(0, size - 2048)
        for (i in size - kw.size downTo from) {
            var ok = true
            for (j in kw.indices) if (buf.get(i + j) != kw[j]) {
                ok = false
                break
            }
            if (ok) return PdfParser(buf, i + kw.size).readInt()
        }
        throw PdfFormatException("startxref が見つからない")
    }

    /** 1 つの相互参照セクションを読み、(trailer, xref ストリームか) を返す。既存の項目は上書きしない。 */
    private fun readXrefSection(offset: Int): Pair<PdfDict, Boolean> {
        if (offset < 0 || offset >= size) throw PdfFormatException("xref の位置が不正: $offset")
        val p = PdfParser(buf, offset)
        if (p.keyword("xref")) {
            while (true) {
                if (p.keyword("trailer")) {
                    val t = p.parseObject() as? PdfDict ?: throw PdfFormatException("trailer が辞書でない")
                    return t to false
                }
                val start = p.readInt()
                val count = p.readInt()
                for (i in 0 until count) {
                    val off = p.readInt()
                    p.readInt() // gen
                    p.skipWs()
                    val type = p.token()
                    if (type == "n" && off > 0) entries.putIfAbsent(start + i, InFile(off))
                }
            }
        }
        val s = parseIndirectAt(offset) as? PdfStream ?: throw PdfFormatException("xref ストリームでない")
        readXrefStream(s)
        return s.dict to true
    }

    private fun readXrefStream(s: PdfStream) {
        val d = s.dict
        val data = decode(s)
        val w = (d["W"] as? PdfArray)?.items?.map { (it as PdfNum).int }
            ?: throw PdfFormatException("/W がない")
        val index = (d["Index"] as? PdfArray)?.items?.map { (it as PdfNum).int }
            ?: listOf(0, (d["Size"] as PdfNum).int)
        val rowLen = w.sum()
        var pos = 0
        fun field(width: Int, default: Long): Long {
            if (width == 0) return default
            var v = 0L
            repeat(width) { v = (v shl 8) or (data[pos++].toLong() and 0xFF) }
            return v
        }
        var i = 0
        while (i + 1 < index.size) {
            val start = index[i]
            val count = index[i + 1]
            for (k in 0 until count) {
                if (pos + rowLen > data.size) return
                val type = field(w[0], 1)
                val f2 = field(w[1], 0)
                val f3 = field(w.getOrElse(2) { 0 }, 0)
                when (type) {
                    1L -> entries.putIfAbsent(start + k, InFile(f2.toInt()))
                    2L -> entries.putIfAbsent(start + k, InObjStm(f2.toInt(), f3.toInt()))
                }
            }
            i += 2
        }
    }

    /** "n g obj ... endobj" を読む。 */
    private fun parseIndirectAt(offset: Int): PdfObj {
        val p = PdfParser(buf, offset)
        p.readInt()
        p.readInt()
        if (!p.keyword("obj")) throw PdfFormatException("obj がない @$offset")
        val o = p.parseObject()
        if (o is PdfDict && p.keyword("stream")) {
            // "stream" の直後は CRLF か LF
            if (p.peek() == '\r'.code) p.pos++
            if (p.peek() == '\n'.code) p.pos++
            val start = p.pos
            val declared = (resolve(o["Length"]) as? PdfNum)?.int ?: -1
            val len = if (declared >= 0 && endstreamAt(start + declared)) declared else searchEndstream(start)
            return PdfStream(o, start, len)
        }
        return o
    }

    private fun endstreamAt(pos: Int): Boolean {
        if (pos > size) return false
        return PdfParser(buf, pos).keyword("endstream")
    }

    private fun searchEndstream(start: Int): Int {
        val kw = "endstream".toByteArray()
        var i = start
        while (i + kw.size <= size) {
            var ok = true
            for (j in kw.indices) if (buf.get(i + j) != kw[j]) {
                ok = false
                break
            }
            if (ok) {
                var end = i
                if (end > start && buf.get(end - 1) == '\n'.code.toByte()) end--
                if (end > start && buf.get(end - 1) == '\r'.code.toByte()) end--
                return end - start
            }
            i++
        }
        throw PdfFormatException("endstream がない")
    }

    fun getObject(num: Int): PdfObj? {
        return when (val e = entries[num] ?: return null) {
            is InFile -> parseIndirectAt(e.offset)
            is InObjStm -> {
                val (data, offsets) = objStmCache.getOrPut(e.stmNum) { loadObjStm(e.stmNum) }
                if (e.index !in offsets.indices) return null
                PdfParser(data, offsets[e.index]).parseObject()
            }
        }
    }

    private fun loadObjStm(num: Int): Pair<ByteBuffer, IntArray> {
        val s = (entries[num] as? InFile)?.let { parseIndirectAt(it.offset) } as? PdfStream
            ?: throw PdfFormatException("オブジェクトストリーム $num が読めない")
        val data = ByteBuffer.wrap(decode(s))
        val n = (s.dict["N"] as PdfNum).int
        val first = (s.dict["First"] as PdfNum).int
        val p = PdfParser(data)
        val offsets = IntArray(n) {
            p.readInt()
            first + p.readInt()
        }
        return data to offsets
    }

    fun resolve(o: PdfObj?): PdfObj? = if (o is PdfRef) getObject(o.num) else o

    // ---- ストリームの展開（FlateDecode + PNG/TIFF 予測子） ----

    private fun decode(s: PdfStream): ByteArray {
        val raw = ByteArray(s.dataLength)
        for (i in raw.indices) raw[i] = buf.get(s.dataStart + i)
        val filters = when (val f = resolve(s.dict["Filter"])) {
            null -> emptyList()
            is PdfName -> listOf(f.raw)
            is PdfArray -> f.items.map { (it as PdfName).raw }
            else -> throw PdfFormatException("不明なフィルタ")
        }
        val parms = when (val p = resolve(s.dict["DecodeParms"])) {
            is PdfDict -> listOf(p)
            is PdfArray -> p.items.map { resolve(it) as? PdfDict }
            else -> emptyList()
        }
        var data = raw
        filters.forEachIndexed { i, f ->
            if (f != "FlateDecode" && f != "Fl") throw PdfFormatException("未対応のフィルタ: $f")
            data = unpredict(inflate(data), parms.getOrNull(i))
        }
        return data
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val tmp = ByteArray(8192)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(tmp)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(tmp, 0, n)
            }
        } finally {
            inf.end()
        }
        return out.toByteArray()
    }

    private fun unpredict(data: ByteArray, parms: PdfDict?): ByteArray {
        val predictor = (parms?.get("Predictor") as? PdfNum)?.int ?: 1
        if (predictor < 10) {
            if (predictor == 1) return data
            throw PdfFormatException("未対応の予測子: $predictor")
        }
        val colors = (parms?.get("Colors") as? PdfNum)?.int ?: 1
        val bpc = (parms?.get("BitsPerComponent") as? PdfNum)?.int ?: 8
        val columns = (parms?.get("Columns") as? PdfNum)?.int ?: 1
        val bpp = maxOf(1, colors * bpc / 8)
        val rowLen = (colors * bpc * columns + 7) / 8
        val out = ByteArrayOutputStream()
        val prev = ByteArray(rowLen)
        val row = ByteArray(rowLen)
        var pos = 0
        while (pos + 1 + rowLen <= data.size) {
            val type = data[pos++].toInt()
            for (i in 0 until rowLen) {
                val x = data[pos + i].toInt() and 0xFF
                val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val v = when (type) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + (a + b) / 2
                    4 -> {
                        val pa = abs(b - c)
                        val pb = abs(a - c)
                        val pc = abs(a + b - 2 * c)
                        x + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                    else -> throw PdfFormatException("不正な PNG 予測子: $type")
                }
                row[i] = v.toByte()
            }
            pos += rowLen
            out.write(row)
            row.copyInto(prev)
        }
        return out.toByteArray()
    }

    // ---- ページ ----

    class Page(
        val ref: PdfRef,
        val dict: PdfDict,
        /** 表示に使われる枠（CropBox、なければ MediaBox）[llx, lly, urx, ury] */
        val box: DoubleArray,
        /** 0 / 90 / 180 / 270 */
        val rotate: Int,
    )

    fun pages(): List<Page> {
        val root = resolve(trailer["Root"]) as? PdfDict ?: throw PdfFormatException("/Root がない")
        val result = ArrayList<Page>()
        val visited = HashSet<Int>()
        fun walk(ref: PdfRef, media: PdfObj?, crop: PdfObj?, rotate: PdfObj?) {
            if (!visited.add(ref.num)) return
            val node = getObject(ref.num) as? PdfDict ?: return
            val m = node["MediaBox"] ?: media
            val c = node["CropBox"] ?: crop
            val r = node["Rotate"] ?: rotate
            val kids = resolve(node["Kids"]) as? PdfArray
            if (kids != null && (node["Type"] as? PdfName)?.raw != "Page") {
                for (k in kids.items) if (k is PdfRef) walk(k, m, c, r)
            } else {
                val box = toBox(resolve(c)) ?: toBox(resolve(m)) ?: doubleArrayOf(0.0, 0.0, 612.0, 792.0)
                val rot = (((resolve(r) as? PdfNum)?.int ?: 0) % 360 + 360) % 360
                result.add(Page(ref, node, box, rot))
            }
        }
        val pagesRef = root["Pages"] as? PdfRef ?: throw PdfFormatException("/Pages がない")
        walk(pagesRef, null, null, null)
        return result
    }

    private fun toBox(o: PdfObj?): DoubleArray? {
        val a = o as? PdfArray ?: return null
        if (a.items.size != 4) return null
        val v = a.items.map { (resolve(it) as? PdfNum)?.double ?: return null }
        return doubleArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
    }
}
