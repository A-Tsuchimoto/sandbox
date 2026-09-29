package app.markpdf

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import app.markpdf.billing.ProStatus
import app.markpdf.pdf.DocInfo
import app.markpdf.pdf.Highlight
import app.markpdf.pdf.HighlightStore
import app.markpdf.pdf.PdfDoc
import app.markpdf.pdf.SaveName
import app.markpdf.pdf.export.HighlightExporter
import app.markpdf.ui.PageListView
import app.markpdf.ui.PageView
import app.markpdf.ui.Palette
import app.markpdf.ui.SwatchView
import app.markpdf.ui.Tool
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

class MainActivity : Activity(), PageView.Host {

    private lateinit var pageList: PageListView
    private lateinit var titleView: TextView
    private lateinit var emptyView: TextView
    private lateinit var pageIndicator: TextView
    private lateinit var penBar: View
    private lateinit var swatches: LinearLayout
    private lateinit var btnPen: ImageButton
    private lateinit var btnEraser: ImageButton
    private lateinit var btnUndo: ImageButton
    private lateinit var btnWidth: TextView
    private lateinit var btnSave: ImageButton
    private lateinit var zoomBar: View
    private lateinit var zoomLabel: TextView

    private lateinit var pro: ProStatus
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val loader = Executors.newSingleThreadExecutor()
    private var loadToken = 0

    private var doc: PdfDoc? = null
    private var store: HighlightStore? = null
    private var docUri: Uri? = null
    private var docTitle: String? = null

    private var colorIndex = 0
    private var widthIndex = 1
    private var penHintShown = false

    // ---- PageView.Host ----

    override var tool = Tool.NONE
        private set
    override val strokeColor: Int get() = Palette.colors[colorIndex]
    override val strokeWidth: Float get() = Palette.widths[widthIndex]

    override fun highlights(page: Int): List<Highlight> = store?.page(page) ?: emptyList()

    override fun onStroke(page: Int, x1: Float, y1: Float, x2: Float, y2: Float) {
        val s = store ?: return
        s.add(page, strokeColor, x1, y1, x2, y2, strokeWidth)
        pageList.refreshPage(page)
        updateUndo()
    }

    override fun onErase(page: Int, highlight: Highlight) {
        store?.remove(highlight)
        pageList.refreshPage(page)
        updateUndo()
    }

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pro = ProStatus(this)

        pageList = findViewById(R.id.pageList)
        titleView = findViewById(R.id.title)
        emptyView = findViewById(R.id.emptyView)
        pageIndicator = findViewById(R.id.pageIndicator)
        penBar = findViewById(R.id.penBar)
        swatches = findViewById(R.id.swatches)
        btnPen = findViewById(R.id.btnPen)
        btnEraser = findViewById(R.id.btnEraser)
        btnUndo = findViewById(R.id.btnUndo)
        btnWidth = findViewById(R.id.btnWidth)
        btnSave = findViewById(R.id.btnSave)
        zoomBar = findViewById(R.id.zoomBar)
        zoomLabel = findViewById(R.id.zoomLabel)

        applyWindowInsets()

        colorIndex = prefs.getInt(KEY_COLOR, 0).coerceIn(0, Palette.colors.lastIndex)
        if (Palette.isLocked(colorIndex, pro.isPro)) colorIndex = 0
        widthIndex = prefs.getInt(KEY_WIDTH, 1).coerceIn(0, Palette.widths.lastIndex)

        emptyView.setOnClickListener { openPicker() }
        btnPen.setOnClickListener { toggleTool(Tool.PEN) }
        btnEraser.setOnClickListener { toggleTool(Tool.ERASER) }
        btnUndo.setOnClickListener { undo() }
        btnSave.setOnClickListener { startSave() }
        findViewById<View>(R.id.btnZoomIn).setOnClickListener { pageList.zoomIn() }
        findViewById<View>(R.id.btnZoomOut).setOnClickListener { pageList.zoomOut() }
        zoomLabel.setOnClickListener { pageList.resetZoom() }
        pageList.onZoomChanged = { z ->
            zoomLabel.text = getString(R.string.zoom_label, Math.round(z * 100))
        }
        btnWidth.setOnClickListener { cycleWidth() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMenu(it) }
        pageList.onPageChanged = { page, total ->
            pageIndicator.text = getString(R.string.page_indicator, page + 1, total)
        }

        buildPalette()
        updateWidthLabel()
        updateToolUi()

        val restored = savedInstanceState?.let { bundleUri(it) }
        (restored ?: viewIntentUri(intent))?.let(::openDocument)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewIntentUri(intent)?.let(::openDocument)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        docUri?.let { outState.putParcelable(KEY_URI, it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        loadToken++
        doc?.close()
        doc = null
        loader.shutdown()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_OPEN -> {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                openDocument(uri)
            }
            REQ_SAVE -> saveTo(uri)
        }
    }

    // ---- document ----

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
        startActivityForResult(intent, REQ_OPEN)
    }

    private fun openDocument(uri: Uri) {
        val token = ++loadToken
        titleView.setText(R.string.loading)
        loader.execute {
            val result = runCatching {
                val info = DocInfo.query(this, uri)
                val d = PdfDoc.open(this, uri)
                val s = HighlightStore(File(filesDir, "highlights/${info.key}.json")).apply { load() }
                Triple(info, d, s)
            }
            runOnUiThread {
                val loaded = result.getOrNull()
                if (token != loadToken || isDestroyed) {
                    loaded?.second?.close()
                    return@runOnUiThread
                }
                if (loaded == null) {
                    onOpenFailed(result.exceptionOrNull())
                } else {
                    showDocument(uri, loaded.first, loaded.second, loaded.third)
                }
            }
        }
    }

    private fun showDocument(uri: Uri, info: DocInfo, d: PdfDoc, s: HighlightStore) {
        doc?.close()
        doc = d
        store = s
        docUri = uri
        docTitle = info.displayName
        titleView.text = info.displayName
        emptyView.visibility = View.GONE
        pageIndicator.visibility = if (d.pageCount > 0) View.VISIBLE else View.GONE
        zoomBar.visibility = pageIndicator.visibility
        pageList.setDocument(d, this)
        updateToolUi()
    }

    private fun onOpenFailed(e: Throwable?) {
        titleView.text = docTitle ?: getString(R.string.app_name)
        val msg = if (e is SecurityException) R.string.open_failed_password else R.string.open_failed
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---- save ----

    /** 保存先をシステムの画面で選んでもらう（ファイル名は「元の名前_highlighted.pdf」を提案） */
    private fun startSave() {
        val s = store ?: return
        if (s.isEmpty) {
            Toast.makeText(this, R.string.save_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_TITLE, SaveName.suggest(docTitle ?: "document.pdf"))
        startActivityForResult(intent, REQ_SAVE)
    }

    private fun saveTo(target: Uri) {
        val src = docUri ?: return
        val d = doc ?: return
        val s = store ?: return
        val snapshot = s.snapshot()
        val pageCount = d.pageCount
        btnSave.isEnabled = false
        Toast.makeText(this, R.string.saving, Toast.LENGTH_SHORT).show()
        loader.execute {
            val result = runCatching { exportTo(src, target, snapshot, pageCount) }
            if (result.isFailure) {
                // 作りかけの空ファイルを残さない
                runCatching { DocumentsContract.deleteDocument(contentResolver, target) }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                btnSave.isEnabled = doc != null
                result.onSuccess { name ->
                    Toast.makeText(this, getString(R.string.saved, name), Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    val msg = when ((e as? HighlightExporter.UnsupportedPdfException)?.reason) {
                        HighlightExporter.Reason.ENCRYPTED -> R.string.save_failed_encrypted
                        null -> R.string.save_failed
                        else -> R.string.save_failed_unsupported
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** ブロッキング。元 PDF を一時ファイルに写し、ハイライトを追記して保存先に書く。保存先の表示名を返す。 */
    private fun exportTo(src: Uri, target: Uri, highlights: Map<Int, List<Highlight>>, pageCount: Int): String {
        val tmp = File.createTempFile("export", ".pdf", cacheDir)
        try {
            val input = contentResolver.openInputStream(src) ?: error("cannot read $src")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            RandomAccessFile(tmp, "r").use { raf ->
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                val out = contentResolver.openOutputStream(target, "w") ?: error("cannot write $target")
                BufferedOutputStream(out).use { HighlightExporter.export(buf, it, highlights, pageCount) }
            }
        } finally {
            tmp.delete()
        }
        return DocInfo.query(this, target).displayName
    }

    // ---- tools ----

    private fun toggleTool(t: Tool) {
        if (doc == null) return
        tool = if (tool == t) Tool.NONE else t
        if (tool == Tool.PEN && !penHintShown) {
            penHintShown = true
            Toast.makeText(this, R.string.pen_hint, Toast.LENGTH_LONG).show()
        } else if (tool == Tool.ERASER) {
            Toast.makeText(this, R.string.eraser_hint, Toast.LENGTH_SHORT).show()
        }
        updateToolUi()
    }

    private fun updateToolUi() {
        val hasDoc = doc != null
        btnPen.isEnabled = hasDoc
        btnSave.isEnabled = hasDoc
        btnEraser.isEnabled = hasDoc
        btnPen.isSelected = tool == Tool.PEN
        btnEraser.isSelected = tool == Tool.ERASER
        penBar.visibility = if (tool == Tool.PEN) View.VISIBLE else View.GONE
        updateUndo()
    }

    private fun updateUndo() {
        btnUndo.isEnabled = store?.canUndo == true
    }

    private fun undo() {
        store?.undo()?.let { pageList.refreshPage(it) }
        updateUndo()
    }

    private fun buildPalette() {
        swatches.removeAllViews()
        val isPro = pro.isPro
        Palette.colors.forEachIndexed { i, color ->
            val locked = Palette.isLocked(i, isPro)
            val v = SwatchView(this, color, locked)
            v.contentDescription = SwatchView.describe(color)
            v.isSelected = i == colorIndex
            v.setOnClickListener {
                if (locked) showProDialog() else selectColor(i)
            }
            swatches.addView(v)
        }
    }

    private fun selectColor(i: Int) {
        colorIndex = i
        prefs.edit().putInt(KEY_COLOR, i).apply()
        for (c in 0 until swatches.childCount) swatches.getChildAt(c).isSelected = c == i
    }

    private fun cycleWidth() {
        widthIndex = (widthIndex + 1) % Palette.widths.size
        prefs.edit().putInt(KEY_WIDTH, widthIndex).apply()
        updateWidthLabel()
    }

    private fun updateWidthLabel() {
        btnWidth.text = getString(R.string.width_label, getString(Palette.widthLabels[widthIndex]))
    }

    // ---- menu / Pro ----

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_OPEN, 0, R.string.open_pdf)
        menu.menu.add(0, MENU_PRO, 1, R.string.menu_pro)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                MENU_OPEN -> openPicker()
                MENU_PRO -> showProDialog()
            }
            true
        }
        menu.show()
    }

    private fun showProDialog() {
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.pro_title)
            .setMessage(if (pro.isPro) R.string.pro_active else R.string.pro_message)
            .setPositiveButton(R.string.close, null)
        if (BuildConfig.DEBUG) {
            // 課金未実装の試作用スイッチ（デバッグビルドのみ）
            val label = if (pro.isPro) R.string.debug_disable_pro else R.string.debug_enable_pro
            builder.setNeutralButton(label) { _, _ ->
                pro.isPro = !pro.isPro
                if (Palette.isLocked(colorIndex, pro.isPro)) selectColor(0)
                buildPalette()
            }
        }
        builder.show()
    }

    // ---- helpers ----

    /** targetSdk 35+ は edge-to-edge 必須のため、システムバーの分だけ余白を取る。 */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.root)
        val topBar = findViewById<View>(R.id.topBar)
        val topBarPadTop = topBar.paddingTop
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (l, t, r, b) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                listOf(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                listOf(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
            }
            v.setPadding(l, 0, r, b)
            topBar.setPadding(topBar.paddingLeft, topBarPadTop + t, topBar.paddingRight, topBar.paddingBottom)
            insets
        }
    }

    private fun viewIntentUri(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_VIEW) intent.data else null

    private fun bundleUri(b: Bundle): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            b.getParcelable(KEY_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            b.getParcelable(KEY_URI)
        }

    private companion object {
        const val REQ_OPEN = 1
        const val REQ_SAVE = 2
        const val MENU_OPEN = 1
        const val MENU_PRO = 2
        const val KEY_URI = "doc_uri"
        const val KEY_COLOR = "color_index"
        const val KEY_WIDTH = "width_index"
    }
}
