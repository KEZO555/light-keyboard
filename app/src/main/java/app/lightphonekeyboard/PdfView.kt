package app.lightphonekeyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.roundToInt

/**
 * A minimal, self-drawn PDF viewer view — the LightPDF experience ported into Type's hand-drawn style
 * (no Compose). Renders a document's pages stacked vertically with touch scroll + fling, pinch-zoom and
 * pan (1–5×), an optional colour-inverting dark mode, and a paged / continuous layout.
 *
 * [PdfRenderer] is single-threaded and opens one page at a time, so all rendering runs on a private
 * background thread: each visible page is rendered once at a fixed supersample of the view width, cached
 * (a few pages, LRU), and merely scaled on screen as you zoom — smooth, and bounded in memory even for a
 * big PDF (unlike rendering every page at 4× up front).
 */
class PdfView(context: Context) : View(context) {

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageCount = 0
    private var aspect = FloatArray(0)          // page height / width, per page
    private var cumAspect = FloatArray(1)       // prefix sums of [aspect]; cumAspect[i] = sum(aspect[0until i])

    var onLoaded: (() -> Unit)? = null
    var onError: (() -> Unit)? = null

    var darkMode = false
        set(v) { field = v; invalidate() }
    var continuous = false
        set(v) { field = v; clampScroll(); invalidate() }

    private var zoom = 1f
    private var scrollY = 0f                     // top of the viewport in content space (at current zoom)
    private var panX = 0f                        // horizontal pan (only meaningful when zoomed in)

    private val minZoom = 1f
    private val maxZoom = 5f
    private val density = resources.displayMetrics.density
    private val gapPx get() = if (continuous) 0f else 10f * density
    private val padPx get() = if (continuous) 0f else 12f * density   // top/bottom of the whole document

    // --- background rendering ---
    private val renderThread = HandlerThread("pdf-render").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val ui = Handler(Looper.getMainLooper())
    private val requested = HashSet<Int>()
    private var renderWidthPx = 0                // supersampled bitmap width; 0 until sized
    private val cache = object : LruCache<Int, Bitmap>(5) {
        override fun entryRemoved(evicted: Boolean, key: Int, old: Bitmap?, new: Bitmap?) {
            if (old != null && old != new) old.recycle()
        }
    }

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val darkBmpPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ))
        )
    }
    private val blankPaint = Paint().apply { color = Color.WHITE }
    private val srcRect = Rect()
    private val dstRect = RectF()

    private val scroller = OverScroller(context)

    /** Open a PDF from an already-opened file descriptor. Takes ownership (closed in [close]). */
    fun open(descriptor: ParcelFileDescriptor) {
        close()
        renderHandler.post {
            try {
                val r = PdfRenderer(descriptor)
                val n = r.pageCount
                val asp = FloatArray(n)
                for (i in 0 until n) {                          // one page open at a time (PdfRenderer rule)
                    val p = r.openPage(i)
                    try { asp[i] = p.height.toFloat() / p.width } finally { p.close() }
                }
                val cum = FloatArray(n + 1)
                for (i in 0 until n) cum[i + 1] = cum[i] + asp[i]
                ui.post {
                    pfd = descriptor; renderer = r; pageCount = n; aspect = asp; cumAspect = cum
                    zoom = 1f; scrollY = 0f; panX = 0f
                    cache.evictAll(); requested.clear()
                    clampScroll(); invalidate()
                    onLoaded?.invoke()
                }
            } catch (e: Throwable) {
                runCatching { descriptor.close() }
                ui.post { onError?.invoke() }
            }
        }
    }

    val hasDocument: Boolean get() = renderer != null

    fun close() {
        val r = renderer; val d = pfd
        renderer = null; pfd = null; pageCount = 0; aspect = FloatArray(0); cumAspect = FloatArray(1)
        cache.evictAll(); requested.clear()
        renderHandler.post { runCatching { r?.close() }; runCatching { d?.close() } }
        invalidate()
    }

    /** Release the render thread — call from the Activity's onDestroy. */
    fun release() {
        close()
        renderThread.quitSafely()
    }

    fun zoomBy(factor: Float) { setZoom(zoom * factor, width / 2f, height / 2f) }
    val currentZoom get() = zoom

    // ---- geometry (content space = pixels at the current zoom) ----
    private fun pageWidth() = width * zoom
    private fun pageHeight(i: Int) = pageWidth() * aspect[i]
    private fun contentHeight(): Float {
        if (pageCount == 0) return 0f
        return padPx * 2 + pageWidth() * cumAspect[pageCount] + gapPx * (pageCount - 1)
    }
    private fun pageTop(i: Int): Float = padPx + pageWidth() * cumAspect[i] + gapPx * i

    private fun clampScroll() {
        if (width == 0 || renderer == null) { scrollY = 0f; panX = 0f; return }
        val maxY = (contentHeight() - height).coerceAtLeast(0f)
        scrollY = scrollY.coerceIn(0f, maxY)
        val overW = (pageWidth() - width).coerceAtLeast(0f)
        panX = panX.coerceIn(-overW / 2f, overW / 2f)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        renderWidthPx = (w * SUPERSAMPLE).coerceAtMost(MAX_RENDER_W)
        cache.evictAll(); requested.clear()
        clampScroll()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(if (darkMode) Color.BLACK else Color.parseColor("#222222"))
        if (renderer == null || width == 0) return
        val paint = if (darkMode) darkBmpPaint else bmpPaint
        val pw = pageWidth()
        val xLeft = (width - pw) / 2f + panX
        for (i in 0 until pageCount) {
            val top = pageTop(i) - scrollY
            val ph = pageHeight(i)
            if (top > height || top + ph < 0) continue               // off-screen
            dstRect.set(xLeft, top, xLeft + pw, top + ph)
            val bmp = cache.get(i)
            if (bmp != null && !bmp.isRecycled) {
                srcRect.set(0, 0, bmp.width, bmp.height)
                canvas.drawRect(dstRect, blankPaint.also { it.colorFilter = paint.colorFilter })
                canvas.drawBitmap(bmp, srcRect, dstRect, paint)
            } else {
                canvas.drawRect(dstRect, blankPaint.also { it.colorFilter = paint.colorFilter })
                requestPage(i)
            }
        }
        blankPaint.colorFilter = null
    }

    private fun requestPage(index: Int) {
        if (renderWidthPx <= 0 || index in requested || cache.get(index) != null) return
        requested.add(index)
        val w = renderWidthPx
        val h = (w * aspect[index]).roundToInt().coerceAtLeast(1)
        renderHandler.post {
            val r = renderer
            var bmp: Bitmap? = null
            if (r != null) {
                try {
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    val page = r.openPage(index)
                    try { page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } finally { page.close() }
                    bmp = b
                } catch (e: Throwable) { bmp = null }
            }
            ui.post {
                requested.remove(index)
                if (bmp != null) {
                    if (renderer != null && index < pageCount) { cache.put(index, bmp); invalidate() }
                    else bmp.recycle()
                }
            }
        }
    }

    // ---- gestures ----
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            setZoom(zoom * d.scaleFactor, d.focusX, d.focusY); return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollY += dy; if (zoom > 1f) panX -= dx; clampScroll(); invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val maxY = (contentHeight() - height).coerceAtLeast(0f).toInt()
            scroller.fling(0, scrollY.toInt(), 0, -vy.toInt(), 0, 0, 0, maxY)
            postInvalidateOnAnimation(); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            setZoom(if (zoom > 1.01f) 1f else 2.5f, e.x, e.y); return true
        }
    })

    private fun setZoom(target: Float, fx: Float, fy: Float) {
        val z = target.coerceIn(minZoom, maxZoom)
        if (z == zoom) return
        // keep the content point under (fx,fy) fixed as we zoom
        val cx = (fx - (width - pageWidth()) / 2f - panX)
        val cy = scrollY + fy
        val ratio = z / zoom
        zoom = z
        scrollY = cy * ratio - fy
        panX = (fx - (width - pageWidth()) / 2f) - cx * ratio
        if (zoom <= 1f) panX = 0f
        clampScroll(); invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat(); clampScroll(); postInvalidateOnAnimation()
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (renderer == null) return false
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    companion object {
        private const val SUPERSAMPLE = 2
        private const val MAX_RENDER_W = 1600     // cap the supersampled bitmap width to bound memory
    }
}
