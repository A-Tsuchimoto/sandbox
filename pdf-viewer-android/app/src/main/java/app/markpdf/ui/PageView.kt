package app.markpdf.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import app.markpdf.pdf.Highlight
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class Tool { NONE, PEN, ERASER }

/**
 * PDF の 1 ページ。ページ画像の上にハイライトを重ねて描き、マーカー／消しゴムの操作を受ける。
 *
 * マーカー：指を置いた点を始点に、指の位置まで「まっすぐな線」をプレビューし、離したら確定。
 * 2 本目の指が触れたら描画をやめてスクロールに譲る。
 */
@SuppressLint("ViewConstructor")
class PageView(
    context: Context,
    val pageIndex: Int,
    private val aspect: Float,
    private val host: Host,
) : View(context) {

    interface Host {
        val tool: Tool
        val strokeColor: Int
        /** ページ幅に対する割合 */
        val strokeWidth: Float
        /** 文字に吸着するか */
        val textSnap: Boolean
        fun highlights(page: Int): List<Highlight>
        fun onStroke(page: Int, x1: Float, y1: Float, x2: Float, y2: Float)
        fun onErase(page: Int, highlight: Highlight)
    }

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 現在の [bitmap] を要求したときの幅（0 = 未要求） */
    var requestedWidth = 0

    /** 拡大時に重ねる、見えている部分だけの高解像度画像 */
    private var detail: Bitmap? = null
    private var detailLeft = 0
    private var detailTop = 0
    private var detailPageWidth = 0

    /** 要求中／表示中の高解像度画像の識別子（ページ幅と矩形） */
    var detailKey: String? = null

    fun setDetail(bmp: Bitmap?, left: Int = 0, top: Int = 0, pageWidth: Int = 0) {
        detail = bmp
        detailLeft = left
        detailTop = top
        detailPageWidth = pageWidth
        if (bmp == null) detailKey = null
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private val minStrokePx = 6 * density
    private val eraseSlopPx = 10 * density

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        // 乗算合成：蛍光ペンのように、下の文字が黒いまま見える
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
    private val dst = RectF()

    private var drawing = false
    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f

    // 実際に描く（確定する）線。文字に吸着したときは上の生の座標からずれる
    private var dsx = 0f
    private var dsy = 0f
    private var dex = 0f
    private var dey = 0f

    init {
        setBackgroundColor(Color.WHITE)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * aspect).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bitmap?.let {
            dst.set(0f, 0f, w, h)
            canvas.drawBitmap(it, null, dst, bitmapPaint)
        }
        detail?.let {
            // 拡大率が変わった直後は位置が合わないので描かない（すぐ描き直される）
            if (detailPageWidth == width) canvas.drawBitmap(it, detailLeft.toFloat(), detailTop.toFloat(), null)
        }
        for (hl in host.highlights(pageIndex)) {
            drawMarker(canvas, hl.color, hl.x1 * w, hl.y1 * h, hl.x2 * w, hl.y2 * h, hl.width * w)
        }
        if (drawing) {
            drawMarker(canvas, host.strokeColor, dsx, dsy, dex, dey, host.strokeWidth * w)
        }
    }

    private fun drawMarker(c: Canvas, color: Int, x1: Float, y1: Float, x2: Float, y2: Float, width: Float) {
        markerPaint.color = color
        markerPaint.strokeWidth = width
        c.drawLine(x1, y1, x2, y2, markerPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = when (host.tool) {
        Tool.PEN -> onPenTouch(e)
        Tool.ERASER -> onEraserTouch(e)
        Tool.NONE -> false
    }

    private fun onPenTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                drawing = true
                sx = e.x
                sy = e.y
                ex = sx
                ey = sy
                updateSnapped()
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 2 本指 → 描画を中止してスクロールに譲る
                cancelStroke()
                parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_MOVE -> if (drawing) {
                val (x, y) = StrokeGeometry.snapEnd(sx, sy, e.x, e.y, width.toFloat(), height.toFloat())
                ex = x
                ey = y
                updateSnapped()
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (drawing) {
                drawing = false
                if (hypot(ex - sx, ey - sy) >= minStrokePx) {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    host.onStroke(pageIndex, dsx / w, dsy / h, dex / w, dey / h)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> cancelStroke()
        }
        return true
    }

    /** 水平／垂直の線なら、近くの文字の行（列）の中央に寄せる */
    private fun updateSnapped() {
        dsx = sx
        dsy = sy
        dex = ex
        dey = ey
        val bmp = bitmap ?: return
        if (!host.textSnap || width == 0 || height == 0) return
        val kx = bmp.width.toFloat() / width
        val ky = bmp.height.toFloat() / height
        val thickness = host.strokeWidth * bmp.width
        if (ey == sy) {
            val c = sharedSnap.snapHorizontal(bmp, sx * kx, ex * kx, sy * ky, thickness) ?: return
            dsy = c / ky
            dey = dsy
        } else if (ex == sx) {
            val c = sharedSnap.snapVertical(bmp, sy * ky, ey * ky, sx * kx, thickness) ?: return
            dsx = c / kx
            dex = dsx
        }
    }

    private fun onEraserTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                eraseAt(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1) eraseAt(e.x, e.y)
            MotionEvent.ACTION_POINTER_DOWN -> parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun eraseAt(x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        // 上に描かれたもの（後から引いたもの）を優先して消す
        val hit = host.highlights(pageIndex).lastOrNull {
            val d = StrokeGeometry.distanceToSegment(x, y, it.x1 * w, it.y1 * h, it.x2 * w, it.y2 * h)
            d <= it.width * w / 2 + eraseSlopPx
        } ?: return
        host.onErase(pageIndex, hit)
    }

    private fun cancelStroke() {
        if (drawing) {
            drawing = false
            invalidate()
        }
    }

    private companion object {
        /** 作業用バッファを全ページで共有する（UI スレッドのみで使う） */
        val sharedSnap = TextSnap()
    }
}
