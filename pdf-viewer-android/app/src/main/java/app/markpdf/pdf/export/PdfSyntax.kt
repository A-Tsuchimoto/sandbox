package app.markpdf.pdf.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/*
 * ハイライト書き出しに必要な分だけの、最小限の PDF 構文（読み取り・書き出し）。
 * 外部ライブラリを使わずに APK を軽く保つため自前で持つ。
 */

class PdfFormatException(message: String) : Exception(message)

sealed interface PdfObj

/** 数値。書き戻しで精度が変わらないよう元の表記を保持する。 */
class PdfNum(val raw: String) : PdfObj {
    val double: Double get() = raw.toDoubleOrNull() ?: 0.0
    val int: Int get() = double.toInt()
}

/** 名前（先頭の / を除いた生の表記）。 */
data class PdfName(val raw: String) : PdfObj

class PdfStr(val bytes: ByteArray) : PdfObj
data class PdfRef(val num: Int, val gen: Int) : PdfObj
data class PdfBool(val value: Boolean) : PdfObj
object PdfNull : PdfObj
class PdfArray(val items: MutableList<PdfObj> = mutableListOf()) : PdfObj
class PdfDict(val map: LinkedHashMap<String, PdfObj> = LinkedHashMap()) : PdfObj {
    operator fun get(key: String): PdfObj? = map[key]
    operator fun set(key: String, value: PdfObj) {
        map[key] = value
    }
}

/** ストリーム（辞書 + ファイル内のデータ位置）。 */
class PdfStream(val dict: PdfDict, val dataStart: Int, val dataLength: Int) : PdfObj

/** バイト列上の字句・構文解析器。 */
class PdfParser(private val buf: ByteBuffer, var pos: Int = 0) {

    private val limit = buf.limit()

    fun peek(): Int = if (pos < limit) buf.get(pos).toInt() and 0xFF else -1

    fun skipWs() {
        while (pos < limit) {
            val c = peek()
            if (isWs(c)) {
                pos++
            } else if (c == '%'.code) {
                while (pos < limit && peek() != '\n'.code && peek() != '\r'.code) pos++
            } else {
                return
            }
        }
    }

    /** 現在位置が [kw] で、その後ろが区切り文字なら読み進めて true。 */
    fun keyword(kw: String): Boolean {
        skipWs()
        if (pos + kw.length > limit) return false
        for (i in kw.indices) if (buf.get(pos + i).toInt() != kw[i].code) return false
        val after = if (pos + kw.length < limit) buf.get(pos + kw.length).toInt() and 0xFF else -1
        if (after != -1 && !isWs(after) && !isDelim(after)) return false
        pos += kw.length
        return true
    }

    fun readInt(): Int {
        skipWs()
        val t = token()
        return t.toIntOrNull() ?: throw PdfFormatException("整数が必要: '$t' @$pos")
    }

    fun parseObject(): PdfObj {
        skipWs()
        val c = peek()
        return when {
            c == -1 -> throw PdfFormatException("予期しない終端")
            c == '/'.code -> {
                pos++
                PdfName(token())
            }
            c == '('.code -> PdfStr(literalString())
            c == '<'.code -> {
                if (pos + 1 < limit && buf.get(pos + 1).toInt() == '<'.code) dict() else PdfStr(hexString())
            }
            c == '['.code -> array()
            c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code -> numberOrRef()
            else -> {
                val t = token()
                when (t) {
                    "true" -> PdfBool(true)
                    "false" -> PdfBool(false)
                    "null" -> PdfNull
                    else -> throw PdfFormatException("不明なトークン '$t' @$pos")
                }
            }
        }
    }

    private fun numberOrRef(): PdfObj {
        val t = token()
        val n = t.toIntOrNull()
        if (n != null && n >= 0) {
            val save = pos
            skipWs()
            val g = if (peek() in '0'.code..'9'.code) token().toIntOrNull() else null
            if (g != null) {
                skipWs()
                if (peek() == 'R'.code) {
                    val after = if (pos + 1 < limit) buf.get(pos + 1).toInt() and 0xFF else -1
                    if (after == -1 || isWs(after) || isDelim(after)) {
                        pos++
                        return PdfRef(n, g)
                    }
                }
            }
            pos = save
        }
        return PdfNum(t)
    }

    private fun dict(): PdfDict {
        pos += 2
        val d = PdfDict()
        while (true) {
            skipWs()
            if (peek() == '>'.code) {
                pos += 2
                return d
            }
            val key = parseObject() as? PdfName ?: throw PdfFormatException("辞書のキーが名前でない @$pos")
            d[key.raw] = parseObject()
        }
    }

    private fun array(): PdfArray {
        pos++
        val a = PdfArray()
        while (true) {
            skipWs()
            if (peek() == ']'.code) {
                pos++
                return a
            }
            a.items.add(parseObject())
        }
    }

    private fun literalString(): ByteArray {
        pos++
        val out = ByteArrayOutputStream()
        var depth = 1
        while (pos < limit) {
            var c = peek()
            pos++
            when (c) {
                '('.code -> {
                    depth++
                    out.write(c)
                }
                ')'.code -> {
                    depth--
                    if (depth == 0) return out.toByteArray()
                    out.write(c)
                }
                '\\'.code -> {
                    c = peek()
                    pos++
                    when (c) {
                        'n'.code -> out.write('\n'.code)
                        'r'.code -> out.write('\r'.code)
                        't'.code -> out.write('\t'.code)
                        'b'.code -> out.write(8)
                        'f'.code -> out.write(12)
                        '\r'.code -> if (peek() == '\n'.code) pos++
                        '\n'.code -> {}
                        in '0'.code..'7'.code -> {
                            var v = c - '0'.code
                            repeat(2) {
                                val d = peek()
                                if (d in '0'.code..'7'.code) {
                                    v = v * 8 + (d - '0'.code)
                                    pos++
                                }
                            }
                            out.write(v and 0xFF)
                        }
                        else -> out.write(c)
                    }
                }
                else -> out.write(c)
            }
        }
        throw PdfFormatException("文字列が閉じていない")
    }

    private fun hexString(): ByteArray {
        pos++
        val out = ByteArrayOutputStream()
        var hi = -1
        while (pos < limit) {
            val c = peek()
            pos++
            if (c == '>'.code) break
            val v = Character.digit(c, 16)
            if (v < 0) continue
            if (hi < 0) {
                hi = v
            } else {
                out.write(hi * 16 + v)
                hi = -1
            }
        }
        if (hi >= 0) out.write(hi * 16)
        return out.toByteArray()
    }

    /** 区切り文字までの 1 トークン */
    fun token(): String {
        val start = pos
        while (pos < limit) {
            val c = peek()
            if (isWs(c) || isDelim(c)) break
            pos++
        }
        if (pos == start) throw PdfFormatException("トークンが空 @$pos")
        val b = ByteArray(pos - start)
        for (i in b.indices) b[i] = buf.get(start + i)
        return String(b, Charsets.ISO_8859_1)
    }

    companion object {
        fun isWs(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
        fun isDelim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code
    }
}

/** PDF オブジェクトをバイト列に書き出す。文字列は常に 16 進表記にする。 */
object PdfWriter {
    fun write(o: PdfObj, out: ByteArrayOutputStream) {
        when (o) {
            is PdfNum -> ascii(out, o.raw)
            is PdfName -> ascii(out, "/" + o.raw)
            is PdfStr -> {
                out.write('<'.code)
                for (b in o.bytes) ascii(out, "%02X".format(b.toInt() and 0xFF))
                out.write('>'.code)
            }
            is PdfRef -> ascii(out, "${o.num} ${o.gen} R")
            is PdfBool -> ascii(out, o.value.toString())
            PdfNull -> ascii(out, "null")
            is PdfArray -> {
                out.write('['.code)
                o.items.forEachIndexed { i, item ->
                    if (i > 0) out.write(' '.code)
                    write(item, out)
                }
                out.write(']'.code)
            }
            is PdfDict -> {
                ascii(out, "<<")
                for ((k, v) in o.map) {
                    ascii(out, "/$k ")
                    write(v, out)
                    out.write('\n'.code)
                }
                ascii(out, ">>")
            }
            is PdfStream -> throw PdfFormatException("ストリームは直接書き出せない")
        }
    }

    fun toBytes(o: PdfObj): ByteArray = ByteArrayOutputStream().also { write(o, it) }.toByteArray()

    fun ascii(out: ByteArrayOutputStream, s: String) {
        out.write(s.toByteArray(Charsets.ISO_8859_1))
    }
}
