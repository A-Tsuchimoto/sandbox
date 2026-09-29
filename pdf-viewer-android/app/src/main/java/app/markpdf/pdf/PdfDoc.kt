package app.markpdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

/**
 * 開いている 1 つの PDF。
 *
 * 描画は OS 標準の [PdfRenderer] を使う（外部ライブラリ不要で APK が小さく済む）。
 * PdfRenderer はスレッドセーフではないので、描画はすべて専用の 1 スレッドで直列に行う。
 */
class PdfDoc private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** ページサイズ（pt）。[w0, h0, w1, h1, ...] */
    private val pageSizes: IntArray,
) : Closeable {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Volatile
    private var closed = false

    val pageCount: Int get() = pageSizes.size / 2

    /** 高さ / 幅 */
    fun aspect(page: Int): Float = pageSizes[page * 2 + 1].toFloat() / pageSizes[page * 2]

    /** ページ幅（pt、回転適用後） */
    fun pageWidthPt(page: Int): Int = pageSizes[page * 2]

    fun cached(page: Int, width: Int): Bitmap? = cache.get(key(page, width))

    /**
     * [page] を幅 [width]px で描画し、メインスレッドで [onDone] を呼ぶ。
     * 返り値の Future をキャンセルすれば、まだ始まっていない描画は行われない。
     */
    fun render(page: Int, width: Int, onDone: (Bitmap) -> Unit): Future<*> = worker.submit {
        if (closed) return@submit
        val key = key(page, width)
        val bmp = cache.get(key) ?: renderNow(page, width).also { cache.put(key, it) }
        main.post { if (!closed) onDone(bmp) }
    }

    /**
     * 拡大表示用：ページを幅 [pageWidthPx] で描いたときの矩形 ([left], [top], [w], [h]) だけを描画する。
     * 見えている部分だけを高解像度で描くので、拡大してもメモリを食わない。キャッシュはしない。
     */
    fun renderRegion(
        page: Int,
        pageWidthPx: Int,
        left: Int,
        top: Int,
        w: Int,
        h: Int,
        onDone: (Bitmap) -> Unit,
    ): Future<*> = worker.submit {
        if (closed) return@submit
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val scale = pageWidthPx.toFloat() / pageWidthPt(page)
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate(-left.toFloat(), -top.toFloat())
        renderer.openPage(page).use {
            it.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        main.post { if (!closed) onDone(bmp) }
    }

    private fun renderNow(page: Int, width: Int): Bitmap {
        val height = (width * aspect(page)).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        renderer.openPage(page).use {
            it.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        return bmp
    }

    override fun close() {
        if (closed) return
        closed = true
        worker.execute {
            renderer.close()
            pfd.close()
        }
        worker.shutdown()
        cache.evictAll()
    }

    private fun key(page: Int, width: Int) = "$page@$width"

    companion object {
        /** ブロッキング。バックグラウンドスレッドから呼ぶこと。 */
        @Throws(IOException::class, SecurityException::class)
        fun open(context: Context, uri: Uri): PdfDoc {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("cannot open $uri")
            try {
                val renderer = PdfRenderer(pfd)
                val sizes = IntArray(renderer.pageCount * 2)
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use {
                        sizes[i * 2] = it.width
                        sizes[i * 2 + 1] = it.height
                    }
                }
                return PdfDoc(pfd, renderer, sizes)
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
        }

        /** ヒープの 1/6 をページ画像のキャッシュに使う。 */
        private fun cacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
