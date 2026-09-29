package app.markpdf.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * ゲームのアナログパッドのような移動用コントローラ。
 * つまみを倒した方向・量に応じて [onMove] に (-1..1, -1..1) を通知し、離すと (0, 0) を通知する。
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onMove: ((Float, Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = 0x55FFFFFF
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val knobEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        color = 0x33000000
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt() }

    private var kx = 0f
    private var ky = 0f

    private val radius get() = min(width, height) / 2f
    private val knobRadius get() = radius * 0.42f
    private val travel get() = radius - knobRadius

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = radius
        canvas.drawCircle(cx, cy, r, basePaint)
        canvas.drawCircle(cx, cy, r - ringPaint.strokeWidth, ringPaint)
        // 上下左右の小さな三角
        val a = r * 0.12f
        val o = r * 0.82f
        drawTri(canvas, cx, cy - o, 0f, -1f, a)
        drawTri(canvas, cx, cy + o, 0f, 1f, a)
        drawTri(canvas, cx - o, cy, -1f, 0f, a)
        drawTri(canvas, cx + o, cy, 1f, 0f, a)
        canvas.drawCircle(cx + kx * travel, cy + ky * travel, knobRadius, knobPaint)
        canvas.drawCircle(cx + kx * travel, cy + ky * travel, knobRadius, knobEdge)
    }

    private val path = Path()

    private fun drawTri(c: Canvas, x: Float, y: Float, dx: Float, dy: Float, a: Float) {
        path.reset()
        path.moveTo(x + dx * a, y + dy * a)
        path.lineTo(x - dy * a - dx * a * 0.2f, y + dx * a - dy * a * 0.2f)
        path.lineTo(x + dy * a - dx * a * 0.2f, y - dx * a - dy * a * 0.2f)
        path.close()
        c.drawPath(path, arrowPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                var x = (e.x - width / 2f) / travel
                var y = (e.y - height / 2f) / travel
                val len = hypot(x, y)
                if (len > 1f) {
                    x /= len
                    y /= len
                }
                set(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> set(0f, 0f)
        }
        return true
    }

    private fun set(x: Float, y: Float) {
        if (x == kx && y == ky) return
        kx = x
        ky = y
        invalidate()
        onMove?.invoke(x, y)
    }
}
