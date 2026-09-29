package app.markpdf.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** パレットの色ボタン。選択中はリング、Pro 限定の色は鍵マーク付きで表示する。 */
@SuppressLint("ViewConstructor")
class SwatchView(context: Context, val color: Int, val locked: Boolean) : View(context) {

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@SwatchView.color }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1 * d
        color = 0x33000000
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        color = 0xFF1F2328.toInt()
    }
    private val veil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8CFFFFFF.toInt() }
    private val lock = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3C4043.toInt() }
    private val lockStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * d
        color = 0xFF3C4043.toInt()
    }
    private val rect = RectF()

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (44 * d).toInt()
        setMeasuredDimension(s, s)
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 7 * d
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, border)
        if (isSelected) canvas.drawCircle(cx, cy, r + 4 * d, ring)
        if (locked) {
            canvas.drawCircle(cx, cy, r, veil)
            // 鍵：本体 + つる
            val bw = 9 * d
            val bh = 7 * d
            rect.set(cx - bw / 2, cy - bh / 2 + 2 * d, cx + bw / 2, cy + bh / 2 + 2 * d)
            canvas.drawRoundRect(rect, 1.5f * d, 1.5f * d, lock)
            rect.set(cx - 3 * d, cy - 6.5f * d, cx + 3 * d, cy - 0.5f * d)
            canvas.drawArc(rect, 180f, 180f, false, lockStroke)
            canvas.drawLine(rect.left, cy - 3.5f * d, rect.left, rect.bottom + 1 * d, lockStroke)
            canvas.drawLine(rect.right, cy - 3.5f * d, rect.right, rect.bottom + 1 * d, lockStroke)
        }
    }

    companion object {
        fun describe(color: Int): String = "#%06X".format(color and 0xFFFFFF)
    }
}
