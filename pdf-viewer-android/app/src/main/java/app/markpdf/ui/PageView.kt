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
        for (hl in host.highlights(pageIndex)) {
            drawMarker(canvas, hl.color, hl.x1 * w, hl.y1 * h, hl.x2 * w, hl.y2 * h, hl.width * w)
        }
        if (drawing) {
            drawMarker(canvas, host.strokeColor, sx, sy, ex, ey, host.strokeWidth * w)
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
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (drawing) {
                drawing = false
                if (hypot(ex - sx, ey - sy) >= minStrokePx) {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    host.onStroke(pageIndex, sx / w, sy / h, ex / w, ey / h)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> cancelStroke()
        }
        return true
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
}
