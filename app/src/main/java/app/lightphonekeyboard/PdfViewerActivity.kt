package app.lightphonekeyboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * The PDF viewer — LightPDF's features in Type's style. Opens a PDF from a file tap (a VIEW intent for
 * application/pdf) or the document picker, and shows it in a [PdfView] with a minimal top toolbar: zoom,
 * invert (dark), flow (continuous vs paged), save-a-copy, share, and close. Black-and-white, system font,
 * no Compose — consistent with the rest of Type.
 */
class PdfViewerActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var landing: View
    private lateinit var viewer: LinearLayout
    private lateinit var pdf: PdfView
    private var sourceUri: Uri? = null

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { load(it) }
    }
    private val saveCopy = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { dest ->
        if (dest != null) copyTo(dest)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pdf = PdfView(this).apply {
            darkMode = Prefs.pdfDarkMode(this@PdfViewerActivity)
            continuous = Prefs.pdfContinuous(this@PdfViewerActivity)
            onError = { Toast.makeText(this@PdfViewerActivity, getString(R.string.pdf_open_failed), Toast.LENGTH_SHORT).show(); showLanding() }
            onLoaded = { showViewer() }
        }
        landing = buildLanding()
        viewer = buildViewer()
        root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.black))
            addView(landing, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(viewer, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        setContentView(root)
        showLanding()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW) load(uri)
    }

    private fun load(uri: Uri) {
        sourceUri = uri
        try {
            val fd = contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("no fd")
            pdf.open(fd)   // PdfView takes ownership of the descriptor
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.pdf_open_failed), Toast.LENGTH_SHORT).show()
            showLanding()
        }
    }

    private fun copyTo(dest: Uri) {
        val src = sourceUri ?: return
        runCatching {
            contentResolver.openInputStream(src)?.use { input ->
                contentResolver.openOutputStream(dest)?.use { output -> input.copyTo(output) }
            }
        }.onSuccess {
            Toast.makeText(this, getString(R.string.pdf_saved), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, getString(R.string.pdf_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun share() {
        val uri = sourceUri ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.pdf_share))) }
    }

    private fun showLanding() { landing.visibility = View.VISIBLE; viewer.visibility = View.GONE }
    private fun showViewer() { landing.visibility = View.GONE; viewer.visibility = View.VISIBLE }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (viewer.visibility == View.VISIBLE) { pdf.close(); showLanding() } else super.onBackPressed()
    }

    override fun onDestroy() { pdf.release(); super.onDestroy() }

    // ---------- UI (built in code, Type's monochrome style) ----------

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun buildLanding(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(getColor(R.color.black))
        addView(TextView(this@PdfViewerActivity).apply {
            text = getString(R.string.section_pdf)
            setTextColor(getColor(R.color.white)); textSize = 28f; letterSpacing = 0.25f
            gravity = Gravity.CENTER
        })
        val open = bigButton(getString(R.string.pdf_open_document)) { openPdf.launch(arrayOf("application/pdf")) }
        addView(open, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(40f) })
    }

    private fun buildViewer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.black))
        addView(buildToolbar(), LinearLayout.LayoutParams(MATCH, WRAP))
        addView(pdf, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private var invertBtn: TextView? = null
    private var flowBtn: TextView? = null

    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }
        bar.addView(toolButton("✕", active = true) { pdf.close(); showLanding() })
        bar.addView(toolButton("−", active = true) { pdf.zoomBy(0.8f) })
        bar.addView(toolButton("+", active = true) { pdf.zoomBy(1.25f) })
        invertBtn = toolButton(getString(R.string.pdf_invert), active = pdf.darkMode) {
            pdf.darkMode = !pdf.darkMode
            Prefs.setPdfDarkMode(this, pdf.darkMode)
            invertBtn?.let { setActive(it, pdf.darkMode) }
        }.also { bar.addView(it) }
        flowBtn = toolButton(getString(R.string.pdf_flow), active = pdf.continuous) {
            pdf.continuous = !pdf.continuous
            Prefs.setPdfContinuous(this, pdf.continuous)
            flowBtn?.let { setActive(it, pdf.continuous) }
        }.also { bar.addView(it) }
        bar.addView(toolButton(getString(R.string.pdf_save_copy), active = true) { saveCopy.launch("copy.pdf") })
        bar.addView(toolButton(getString(R.string.pdf_share), active = true) { share() })
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(getColor(R.color.black))
            addView(bar)
        }
    }

    private fun setActive(tv: TextView, active: Boolean) =
        tv.setTextColor(getColor(if (active) R.color.white else R.color.gray))

    private fun toolButton(label: String, active: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(getColor(if (active) R.color.white else R.color.gray))
            gravity = Gravity.CENTER
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun bigButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 22f
            setTextColor(getColor(R.color.white))
            gravity = Gravity.CENTER
            setPadding(dp(20f), dp(14f), dp(20f), dp(14f))
            isClickable = true
            setOnClickListener { onClick() }
        }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
