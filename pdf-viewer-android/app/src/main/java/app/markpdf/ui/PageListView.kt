package app.markpdf.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import app.markpdf.pdf.PdfDoc
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ページを縦に並べ、上下左右スクロールと拡大縮小ができるビュー。
 *
 * - 全ページ分の [PageView]（サイズだけ持つ軽い箱）を並べ、見えているページ±1 だけ画像を保持する
 * - 画像は「画面幅」の解像度で 1 回だけ描き、拡大時は見えている部分だけ高解像度で重ねる
 *   （拡大してもメモリ使用量はほぼ一定）
 * - 1 本指：スクロール（マーカー／消しゴム中はページ側が使う）
 * - 2 本指：スクロール＋ピンチで拡大縮小
 * - ダブルタップ：拡大 ↔ 等倍
 */
class PageListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** (先頭に見えているページ, 総ページ数) */
    var onPageChanged: ((Int, Int) -> Unit)? = null

    /** 拡大率が変わった */
    var onZoomChanged: ((Float) -> Unit)? = null

    var zoom = 1f
        private set

    private val pages = ArrayList<PageView>()
    private var aspects = FloatArray(0)
    private var tops = IntArray(0)
    private var contentW = 0
    private var contentH = 0
    private val loaded = HashSet<Int>()
    private val jobs = HashMap<Int, Future<*>>()
    private val detailJobs = HashMap<Int, Future<*>>()
    private val gapPx = (6 * resources.displayMetrics.density).roundToInt()
    private var doc: PdfDoc? = null
    private var lastReportedPage = -1

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var lastFx = 0f
    private var lastFy = 0f
    private var hasLast = false
    private var dragging = false
    private var zoomAnimator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                setZoom(zoom * d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        },
    ).apply { isQuickScaleEnabled = false }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                animateZoomTo(if (zoom < 1.5f) 2f else 1f, e.x, e.y)
                return true
            }
        },
    )

    private val detailRunnable = Runnable { updateDetails() }

    init {
        setWillNotDraw(false)
    }

    fun setDocument(doc: PdfDoc?, host: PageView.Host) {
        loaded.toList().forEach(::release)
        detailJobs.values.forEach { it.cancel(false) }
        detailJobs.clear()
        removeAllViews()
        pages.clear()
        lastReportedPage = -1
        zoomAnimator?.cancel()
        zoom = 1f
        this.doc = doc
        val n = doc?.pageCount ?: 0
        aspects = FloatArray(n) { doc!!.aspect(it) }
        tops = IntArray(n)
        for (i in 0 until n) {
            val pv = PageView(context, i, aspects[i], host)
            addView(pv)
            pages.add(pv)
        }
        scroller.forceFinished(true)
        scrollTo(0, 0)
        onZoomChanged?.invoke(zoom)
        requestLayout()
    }

    /** ハイライトが変わったページを再描画する。 */
    fun refreshPage(page: Int) {
        pages.getOrNull(page)?.invalidate()
    }

    // ---- 拡大縮小 ----

    fun zoomIn() = stepZoom(+1)

    fun zoomOut() = stepZoom(-1)

    fun resetZoom() = animateZoomTo(1f, width / 2f, height / 2f)

    private fun stepZoom(dir: Int) {
        val target = if (dir > 0) {
            ZOOM_STEPS.firstOrNull { it > zoom + 0.01f } ?: MAX_ZOOM
        } else {
            ZOOM_STEPS.lastOrNull { it < zoom - 0.01f } ?: MIN_ZOOM
        }
        animateZoomTo(target, width / 2f, height / 2f)
    }

    private fun animateZoomTo(target: Float, fx: Float, fy: Float) {
        zoomAnimator?.cancel()
        scroller.forceFinished(true)
        zoomAnimator = ValueAnimator.ofFloat(zoom, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { setZoom(it.animatedValue as Float, fx, fy) }
            start()
        }
    }

    /** 画面上の点 ([fx], [fy]) の位置を保ったまま拡大率を変える。 */
    private fun setZoom(z: Float, fx: Float, fy: Float) {
        val nz = z.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (pages.isEmpty() || width == 0 || abs(nz - zoom) < 0.0001f) return
        // 指の下にあるページと、その中での相対位置を覚えておく
        val cx = scrollX + fx
        val cy = scrollY + fy
        val i = pageAt(cy.toInt())
        val oldPw = pageWidth(zoom).coerceAtLeast(1)
        val ry = (cy - tops[i]) / pageHeight(i, oldPw).coerceAtLeast(1)
        val rx = cx / oldPw

        zoom = nz
        computeGeometry()
        val newCx = rx * contentW
        val newCy = tops[i] + ry * pageHeight(i, contentW)
        clearDetails()
        scrollToClamped((newCx - fx).roundToInt(), (newCy - fy).roundToInt())
        requestLayout()
        onZoomChanged?.invoke(zoom)
    }

    // ---- レイアウト ----

    private fun pageWidth(z: Float) = (width * z).roundToInt()

    private fun pageHeight(i: Int, pw: Int) = (pw * aspects[i]).roundToInt()

    /** 各ページの上端とコンテンツ全体の大きさを計算する（PageView.onMeasure と同じ丸め方） */
    private fun computeGeometry() {
        val pw = pageWidth(zoom)
        var y = 0
        for (i in aspects.indices) {
            if (i > 0) y += gapPx
            tops[i] = y
            y += pageHeight(i, pw)
        }
        contentW = pw
        contentH = y
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cs = MeasureSpec.makeMeasureSpec((w * zoom).roundToInt(), MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (p in pages) p.measure(cs, hs)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        computeGeometry()
        for ((i, p) in pages.withIndex()) {
            p.layout(0, tops[i], p.measuredWidth, tops[i] + p.measuredHeight)
        }
        scrollToClamped(scrollX, scrollY)
        updateVisible()
        scheduleDetails()
    }

    private fun scrollToClamped(x: Int, y: Int) {
        val cx = x.coerceIn(0, max(0, contentW - width))
        val cy = y.coerceIn(0, max(0, contentH - height))
        if (cx != scrollX || cy != scrollY) scrollTo(cx, cy)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        updateVisible()
        scheduleDetails()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollToClamped(scroller.currX, scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    override fun computeVerticalScrollRange() = contentH
    override fun computeVerticalScrollOffset() = scrollY
    override fun computeVerticalScrollExtent() = height
    override fun computeHorizontalScrollRange() = contentW
    override fun computeHorizontalScrollOffset() = scrollX
    override fun computeHorizontalScrollExtent() = width

    // ---- タッチ ----

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true)
            hasLast = false
        }
        // 2 本指になったら（マーカー中でも）スクロール・拡大に切り替える
        return ev.pointerCount >= 2
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (pages.isEmpty()) return false
        val vt = velocity ?: VelocityTracker.obtain().also { velocity = it }
        vt.addMovement(ev)

        // 指の重心（離れていく指は除く）
        val skip = if (ev.actionMasked == MotionEvent.ACTION_POINTER_UP) ev.actionIndex else -1
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until ev.pointerCount) {
            if (i == skip) continue
            sx += ev.getX(i)
            sy += ev.getY(i)
            n++
        }
        val fx = if (n > 0) sx / n else ev.x
        val fy = if (n > 0) sy / n else ev.y

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                zoomAnimator?.cancel()
                scroller.forceFinished(true)
                lastFx = fx
                lastFy = fy
                hasLast = true
                dragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastFx = fx
                lastFy = fy
                hasLast = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!hasLast) {
                    lastFx = fx
                    lastFy = fy
                    hasLast = true
                }
                val dx = lastFx - fx
                val dy = lastFy - fy
                if (!dragging && (ev.pointerCount >= 2 || hypot(dx, dy) > touchSlop)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    // パンを先に適用し、その後でピンチの拡大（指の下の位置を保つ）
                    scrollToClamped(scrollX + dx.roundToInt(), scrollY + dy.roundToInt())
                    lastFx = fx
                    lastFy = fy
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    vt.computeCurrentVelocity(1000, maxFling.toFloat())
                    val vx = vt.xVelocity
                    val vy = vt.yVelocity
                    if (abs(vx) > minFling || abs(vy) > minFling) {
                        scroller.fling(
                            scrollX, scrollY, -vx.toInt(), -vy.toInt(),
                            0, max(0, contentW - width), 0, max(0, contentH - height),
                        )
                        postInvalidateOnAnimation()
                    }
                }
                endTouch()
            }
            MotionEvent.ACTION_CANCEL -> endTouch()
        }
        scaleDetector.onTouchEvent(ev)
        if (ev.pointerCount == 1) gestureDetector.onTouchEvent(ev)
        return true
    }

    private fun endTouch() {
        dragging = false
        hasLast = false
        velocity?.recycle()
        velocity = null
        scheduleDetails()
    }

    // ---- 描画範囲の管理 ----

    /** y を含む（またはその直後の）ページ */
    private fun pageAt(y: Int): Int {
        var lo = 0
        var hi = pages.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (tops[mid] + pageHeight(mid, contentW) > y) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** 上端が y より上にある最後のページ */
    private fun lastPageAbove(y: Int): Int {
        var lo = 0
        var hi = pages.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] < y) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun visibleRange(): IntRange? {
        if (doc == null || pages.isEmpty() || height == 0 || contentW == 0) return null
        val first = pageAt(scrollY)
        val last = max(first, lastPageAbove(scrollY + height))
        return first..last
    }

    private fun updateVisible() {
        val range = visibleRange() ?: return
        val from = max(0, range.first - 1)
        val to = min(pages.size - 1, range.last + 1)
        loaded.filter { it < from || it > to }.forEach(::release)
        for (i in from..to) ensureRendered(i)
        if (range.first != lastReportedPage) {
            lastReportedPage = range.first
            onPageChanged?.invoke(range.first, pages.size)
        }
    }

    private fun ensureRendered(i: Int) {
        val d = doc ?: return
        val pv = pages[i]
        // 拡大率によらず「画面幅」の解像度で描く（拡大分は updateDetails で補う）
        val w = min(width, MAX_RENDER_WIDTH)
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
            if (doc === d && i in loaded && pv.requestedWidth == w) {
                jobs.remove(i)
                pv.bitmap = bmp
            }
        }
    }

    private fun release(i: Int) {
        jobs.remove(i)?.cancel(false)
        detailJobs.remove(i)?.cancel(false)
        loaded.remove(i)
        pages.getOrNull(i)?.let {
            it.bitmap = null
            it.requestedWidth = 0
            it.setDetail(null)
        }
    }

    // ---- 拡大時の高解像度描画 ----

    private fun scheduleDetails() {
        removeCallbacks(detailRunnable)
        postDelayed(detailRunnable, DETAIL_DELAY_MS)
    }

    private fun clearDetails() {
        detailJobs.values.forEach { it.cancel(false) }
        detailJobs.clear()
        for (i in loaded) pages[i].setDetail(null)
        scheduleDetails()
    }

    private fun updateDetails() {
        val d = doc ?: return
        if (dragging || !scroller.isFinished || zoomAnimator?.isRunning == true) {
            scheduleDetails()
            return
        }
        val range = visibleRange() ?: return
        // 画面幅の画像で足りる倍率なら何もしない
        val needDetail = contentW > min(width, MAX_RENDER_WIDTH) * 1.1f
        for (i in loaded) {
            if (!needDetail || i !in range) {
                detailJobs.remove(i)?.cancel(false)
                if (pages[i].detailKey != null) pages[i].setDetail(null)
            }
        }
        if (!needDetail) return
        for (i in range) {
            val pv = pages[i]
            val l = max(0, scrollX - pv.left)
            val t = max(0, scrollY - pv.top)
            val r = min(pv.width, scrollX + width - pv.left)
            val b = min(pv.height, scrollY + height - pv.top)
            if (r <= l || b <= t) continue
            val pw = pv.width
            val key = "$pw:$l:$t:$r:$b"
            if (pv.detailKey == key) continue
            detailJobs.remove(i)?.cancel(false)
            pv.detailKey = key
            detailJobs[i] = d.renderRegion(i, pw, l, t, r - l, b - t) { bmp ->
                if (doc === d && pv.detailKey == key) {
                    detailJobs.remove(i)
                    pv.setDetail(bmp, l, t, pw)
                    pv.detailKey = key
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(detailRunnable)
        zoomAnimator?.cancel()
    }

    private companion object {
        /** タブレット等での使いすぎ防止（1 ページ最大 約 8MB） */
        const val MAX_RENDER_WIDTH = 1600
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
        val ZOOM_STEPS = floatArrayOf(1f, 1.5f, 2f, 3f, 4f, 5f)
        const val DETAIL_DELAY_MS = 120L
    }
}
