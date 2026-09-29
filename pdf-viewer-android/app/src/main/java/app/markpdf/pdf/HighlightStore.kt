package app.markpdf.pdf

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 1 本のハイライト（直線マーカー）。
 *
 * 座標はページに対する正規化値（x はページ幅、y はページ高さで割った 0..1）。
 * 太さ [width] はページ幅に対する割合。画面サイズや回転が変わっても同じ位置に描ける。
 */
data class Highlight(
    val id: Long,
    val page: Int,
    val color: Int,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val width: Float,
)

/**
 * 1 つの PDF に付いたハイライトの保存先。
 *
 * PDF 本体は書き換えず、アプリ内の JSON に保存する（試作段階の方針）。
 * 追加・削除は Undo できる。
 */
class HighlightStore(private val file: File) {

    private sealed interface Change {
        val h: Highlight
    }

    private class Added(override val h: Highlight) : Change
    private class Removed(override val h: Highlight, val index: Int) : Change

    private val byPage = HashMap<Int, MutableList<Highlight>>()
    private val history = ArrayDeque<Change>()
    private var nextId = 1L

    val canUndo: Boolean get() = history.isNotEmpty()

    fun page(page: Int): List<Highlight> = byPage[page] ?: emptyList()

    val isEmpty: Boolean get() = byPage.values.all { it.isEmpty() }

    /** 書き出し用のコピー（ページ番号 → 描いた順のハイライト） */
    fun snapshot(): Map<Int, List<Highlight>> = byPage.mapValues { it.value.toList() }

    fun add(page: Int, color: Int, x1: Float, y1: Float, x2: Float, y2: Float, width: Float): Highlight {
        val h = Highlight(nextId++, page, color, x1, y1, x2, y2, width)
        byPage.getOrPut(page) { mutableListOf() }.add(h)
        pushHistory(Added(h))
        save()
        return h
    }

    fun remove(h: Highlight) {
        val list = byPage[h.page] ?: return
        val index = list.indexOfFirst { it.id == h.id }
        if (index < 0) return
        list.removeAt(index)
        pushHistory(Removed(h, index))
        save()
    }

    /** 直前の操作を取り消す。影響を受けたページ番号を返す。 */
    fun undo(): Int? {
        val change = history.removeLastOrNull() ?: return null
        val list = byPage.getOrPut(change.h.page) { mutableListOf() }
        when (change) {
            is Added -> list.removeAll { it.id == change.h.id }
            is Removed -> list.add(change.index.coerceAtMost(list.size), change.h)
        }
        save()
        return change.h.page
    }

    private fun pushHistory(c: Change) {
        history.addLast(c)
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    /** ブロッキング。バックグラウンドスレッドから呼ぶこと。 */
    fun load() {
        if (!file.exists()) return
        runCatching {
            val arr = JSONObject(file.readText()).getJSONArray("highlights")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val h = Highlight(
                    id = o.getLong("id"),
                    page = o.getInt("page"),
                    color = o.getInt("color"),
                    x1 = o.getDouble("x1").toFloat(),
                    y1 = o.getDouble("y1").toFloat(),
                    x2 = o.getDouble("x2").toFloat(),
                    y2 = o.getDouble("y2").toFloat(),
                    width = o.getDouble("w").toFloat(),
                )
                byPage.getOrPut(h.page) { mutableListOf() }.add(h)
                if (h.id >= nextId) nextId = h.id + 1
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (list in byPage.values) for (h in list) {
            arr.put(
                JSONObject()
                    .put("id", h.id)
                    .put("page", h.page)
                    .put("color", h.color)
                    .put("x1", h.x1.toDouble())
                    .put("y1", h.y1.toDouble())
                    .put("x2", h.x2.toDouble())
                    .put("y2", h.y2.toDouble())
                    .put("w", h.width.toDouble()),
            )
        }
        val json = JSONObject().put("version", 1).put("highlights", arr).toString()
        io.execute {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) {
                    file.writeText(json)
                    tmp.delete()
                }
            }
        }
    }

    companion object {
        private const val MAX_HISTORY = 100

        /** 書き込みは全ドキュメント共通の 1 スレッドで順番に行う。 */
        private val io = Executors.newSingleThreadExecutor()
    }
}
