package app.markpdf.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.ScrollView
import app.markpdf.pdf.PdfDoc
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min

/**
 * ページを縦に並べてスクロール表示するビュー。
 *
 * 全ページ分の [PageView]（サイズだけ持つ軽い箱）を並べ、
 * 画面に見えているページとその前後 1 ページだけ画像を描画・保持する。
 */
class PageListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    /** (先頭に見えているページ, 総ページ数) */
    var onPageChanged: ((Int, Int) -> Unit)? = null

    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val pages = ArrayList<PageView>()
    private val loaded = HashSet<Int>()
    private val jobs = HashMap<Int, Future<*>>()
    private val gapPx = (6 * resources.displayMetrics.density).toInt()
    private var doc: PdfDoc? = null
    private var lastReportedPage = -1

    init {
        isFillViewport = true
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setOnScrollChangeListener { _, _, _, _, _ -> updateVisible() }
    }

    fun setDocument(doc: PdfDoc?, host: PageView.Host) {
        loaded.toList().forEach(::release)
        column.removeAllViews()
        pages.clear()
        lastReportedPage = -1
        this.doc = doc
        if (doc != null) {
            for (i in 0 until doc.pageCount) {
                val pv = PageView(context, i, doc.aspect(i), host)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.topMargin = if (i == 0) 0 else gapPx
                column.addView(pv, lp)
                pages.add(pv)
            }
        }
        scrollTo(0, 0)
    }

    /** ハイライトが変わったページを再描画する。 */
    fun refreshPage(page: Int) {
        pages.getOrNull(page)?.invalidate()
    }

    fun refreshAll() {
        pages.forEach { it.invalidate() }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // 画面回転などで幅が変わった場合も、ここで描き直しが走る
        updateVisible()
    }

    private fun updateVisible() {
        val n = pages.size
        if (doc == null || n == 0 || height == 0) return
        val top = scrollY
        val bottom = scrollY + height
        val first = firstPageBelow(top)
        val last = max(first, lastPageAbove(bottom))
        val from = max(0, first - 1)
        val to = min(n - 1, last + 1)

        loaded.filter { it < from || it > to }.forEach(::release)
        for (i in from..to) ensureRendered(i)

        if (first != lastReportedPage) {
            lastReportedPage = first
            onPageChanged?.invoke(first, n)
        }
    }

    /** 下端が [y] より下にある最初のページ */
    private fun firstPageBelow(y: Int): Int {
        var lo = 0
        var hi = pages.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (pages[mid].bottom > y) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** 上端が [y] より上にある最後のページ */
    private fun lastPageAbove(y: Int): Int {
        var lo = 0
        var hi = pages.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (pages[mid].top < y) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun ensureRendered(i: Int) {
        val d = doc ?: return
        val pv = pages[i]
        val w = min(pv.width, MAX_RENDER_WIDTH)
        if (w <= 0 || pv.requestedWidth == w) return

        jobs.remove(i)?.cancel(false)
        pv.requestedWidth = w
        loaded.add(i)
        val cached = d.cached(i, w)
        if (cached != null) {
            pv.bitmap = cached
            return
        }
        jobs[i] = d.render(i, w) { bmp ->
            // 待っている間にページが解放された／幅が変わった場合は捨てる
            if (doc === d && i in loaded && pv.requestedWidth == w) {
                jobs.remove(i)
                pv.bitmap = bmp
            }
        }
    }

    private fun release(i: Int) {
        jobs.remove(i)?.cancel(false)
        loaded.remove(i)
        pages.getOrNull(i)?.let {
            it.bitmap = null
            it.requestedWidth = 0
        }
    }

    private companion object {
        /** タブレット等での使いすぎ防止（1 ページ最大 約 8MB） */
        const val MAX_RENDER_WIDTH = 1600
    }
}
