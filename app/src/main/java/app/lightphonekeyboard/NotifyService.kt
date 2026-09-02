package app.lightphonekeyboard

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * Notification overlay — LightNotifi's behaviour ported into Type (plain Views, no Compose). Listens for
 * notifications from the apps you choose and shows them as a floating card (or a row of cards) over
 * whatever's on screen, with tap-to-open, optional swipe-to-dismiss, an auto-dismiss timer, and a
 * lock-screen view. Settings live in [NotifySettingsActivity]; state is stored in [Prefs].
 */
class NotifyService : NotificationListenerService() {

    data class NotifyData(val key: String, val title: String, val text: String, val pkg: String, val intent: PendingIntent?)

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val notifs = ArrayList<NotifyData>()
    private var overlay: LinearLayout? = null       // [ card holder ] over [ page dots ]
    private var cardHolder: LinearLayout? = null
    private var dotsRow: LinearLayout? = null
    private var currentIndex = 0                     // which notification the pager is showing
    private val dismissRunnables = HashMap<String, Runnable>()
    private var wakeLock: PowerManager.WakeLock? = null

    // Cached settings (kept in sync by [prefsListener]).
    private var apps: Set<String> = emptySet()
    private var swipe = true
    private var wake = false
    private var lock = false
    private var groups = false
    private var stay = false
    private var sync = true
    private var offsetDp = 28
    private var durationIdx = 1
    private var sizeIdx = 1

    private val density get() = resources.displayMetrics.density
    private val sizeScale get() = SIZES[sizeIdx.coerceIn(0, SIZES.size - 1)]
    private val durationMs get() = DURATIONS[durationIdx.coerceIn(0, DURATIONS.size - 1)] * 1000L
    private val offsetPx get() = (offsetDp * density).toInt()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        loadCaches()
        // Position / size affect the live window, so refresh it (and re-lay the cards).
        overlay?.let { v -> main.post { runCatching { wm.updateViewLayout(v, params()) }; renderCurrent() } }
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON && lock && notifs.isNotEmpty() && isLocked()) {
                startLockScreen()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        loadCaches()
        Prefs.shared(this).registerOnSharedPreferenceChangeListener(prefsListener)
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PREVIEW) {
            show(NotifyData(PREVIEW_KEY, getString(R.string.notify_preview_title), getString(R.string.notify_preview_text), packageName, null))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(screenOn) }
        runCatching { Prefs.shared(this).unregisterOnSharedPreferenceChangeListener(prefsListener) }
        clearAll()
        super.onDestroy()
    }

    private fun loadCaches() {
        apps = Prefs.notifApps(this)
        swipe = Prefs.notifSwipe(this)
        wake = Prefs.notifWakeScreen(this)
        lock = Prefs.notifLockScreen(this)
        groups = Prefs.notifGroupSummaries(this)
        stay = Prefs.notifStay(this)
        sync = Prefs.notifSync(this)
        offsetDp = Prefs.notifOffsetDp(this)
        durationIdx = Prefs.notifDurationIdx(this)
        sizeIdx = Prefs.notifSizeIdx(this)
    }

    // ---------------- notifications ----------------

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName !in apps) return
        if (!groups && (n.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = n.notification.extras
        val title = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(NotificationCompat.EXTRA_TITLE_BIG)?.toString()
            ?: getString(R.string.notify_default_title)

        var text = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
        runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n.notification)?.let { ms ->
                ms.messages.lastOrNull()?.let { last ->
                    val sender = last.person?.name ?: getString(R.string.notify_default_title)
                    val content = last.text?.toString() ?: ""
                    val group = ms.conversationTitle
                    text = if (ms.isGroupConversation && !group.isNullOrEmpty()) "[$group] $sender: $content"
                    else if (ms.isGroupConversation) "$sender: $content" else content
                }
            }
        }
        if (text.isNullOrEmpty()) text = extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString()
        if (text.isNullOrEmpty()) extras.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)?.lastOrNull()?.let { text = it.toString() }
        if (text.isNullOrEmpty()) text = extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)?.toString()
        if (text.isNullOrEmpty()) text = n.notification.tickerText?.toString()

        if (title.isBlank() && text.isNullOrEmpty()) return
        show(NotifyData(n.key, title, text ?: "", n.packageName, n.notification.contentIntent))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        val n = sbn ?: return
        if (reason == REASON_LISTENER_CANCEL) return
        val remote = reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL
        if (stay && reason != REASON_CLICK && n.key != PREVIEW_KEY) {
            if (!sync || !remote) return
        }
        main.post { removeByKey(n.key) }
    }

    private fun show(data: NotifyData) {
        main.post {
            if (wake) acquireWake()
            val idx = notifs.indexOfFirst { it.key == data.key }
            if (idx >= 0) notifs[idx] = data else notifs.add(data)
            while (notifs.size > MAX_CARDS) notifs.removeAt(0)
            currentIndex = notifs.size - 1                 // page to the newest notification

            if (lock && isLocked()) {
                startLockScreen()
            } else {
                if (Settings.canDrawOverlays(this) && overlay == null) addOverlay()
                overlay?.let { runCatching { wm.updateViewLayout(it, params()) } }
                renderCurrent()
            }
            if (!stay || data.key == PREVIEW_KEY) scheduleDismiss(data.key)
        }
    }

    private fun scheduleDismiss(key: String) {
        dismissRunnables.remove(key)?.let { main.removeCallbacks(it) }
        val r = Runnable { removeByKey(key) }
        dismissRunnables[key] = r
        main.postDelayed(r, if (key == PREVIEW_KEY) maxOf(durationMs, 10_000L) else durationMs)
    }

    private fun removeByKey(key: String) {
        notifs.removeAll { it.key == key }
        dismissRunnables.remove(key)?.let { main.removeCallbacks(it) }
        if (notifs.isEmpty()) removeOverlay()
        else { currentIndex = currentIndex.coerceIn(0, notifs.size - 1); renderCurrent() }
    }

    private fun dismissInternal(key: String) {
        main.post {
            removeByKey(key)
            if (key != PREVIEW_KEY) runCatching { cancelNotification(key) }   // clear it from the shade too
        }
    }

    private fun openNotification(data: NotifyData) {
        // Android 14+ blocks activity launches from a background service unless we opt in via BAL.
        val opts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
        runCatching {
            if (data.intent != null) {
                val fill = Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // The Bundle-options overload only exists on API 34+, so only call it when we built opts.
                if (opts != null) data.intent.send(this, 0, fill, null, null, null, opts)
                else data.intent.send(this, 0, fill)
            } else {
                packageManager.getLaunchIntentForPackage(data.pkg)?.let {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts)
                }
            }
        }
        dismissInternal(data.key)
    }

    private fun clearAll() { notifs.clear(); removeOverlay() }

    // ---------------- overlay views (plain Views) ----------------
    // One card at a time: tap to open, swipe up to dismiss, swipe left/right to page between
    // notifications. A row of dots under the card marks how many there are and which is showing.

    private fun addOverlay() {
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(holder)
            addView(dots)
        }
        runCatching { wm.addView(root, params()); overlay = root; cardHolder = holder; dotsRow = dots }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null; cardHolder = null; dotsRow = null
    }

    /** Show the notification at [currentIndex] in the card holder, and refresh the page dots. */
    private fun renderCurrent() {
        val holder = cardHolder ?: return
        holder.removeAllViews()
        if (notifs.isEmpty()) { removeOverlay(); return }
        currentIndex = currentIndex.coerceIn(0, notifs.size - 1)
        val cardW = minOf((340 * sizeScale * density).toInt(),
            resources.displayMetrics.widthPixels - (32 * density).toInt())
        holder.addView(buildCard(notifs[currentIndex]),
            LinearLayout.LayoutParams(cardW, WindowManager.LayoutParams.WRAP_CONTENT))
        renderDots()
    }

    /** A dot per notification; the current one is solid white, the rest are dim. Hidden for a single card. */
    private fun renderDots() {
        val row = dotsRow ?: return
        row.removeAllViews()
        if (notifs.size <= 1) return
        val s = (6 * density).toInt()
        val gap = (4 * density).toInt()
        for (i in notifs.indices) {
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (i == currentIndex) Color.WHITE else Color.argb(90, 255, 255, 255))
                }
            }
            row.addView(dot, LinearLayout.LayoutParams(s, s).apply {
                leftMargin = gap; rightMargin = gap; topMargin = (8 * density).toInt()
            })
        }
    }

    private fun buildCard(data: NotifyData): View {
        val pad = (12 * sizeScale * density).toInt()
        val bg = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = 24 * sizeScale * density }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            setPadding(pad, pad, pad, pad)
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_notify)
            setColorFilter(Color.WHITE)
            val s = (30 * sizeScale * density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { rightMargin = (12 * sizeScale * density).toInt() }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = data.title; setTextColor(Color.WHITE); textSize = 16f * sizeScale
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (data.text.isNotEmpty()) col.addView(TextView(this).apply {
            text = data.text; setTextColor(Color.argb(210, 255, 255, 255)); textSize = 15f * sizeScale
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(icon); row.addView(col)
        attachGestures(row)
        return row
    }

    /**
     * One unified touch handler for the card: a tap (no real movement) opens the notification, a swipe up
     * past the threshold dismisses it (when swipe-to-dismiss is on), and a horizontal swipe pages to the
     * next / previous notification. Anything short of a threshold springs back.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(card: View) {
        var downX = 0f; var downY = 0f; var dragged = false; var axisX = false
        val slop = 8 * density
        val threshold = 60 * density
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragged = false; axisX = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragged && (abs(dx) > slop || abs(dy) > slop)) { dragged = true; axisX = abs(dx) > abs(dy) }
                    if (dragged) {
                        if (axisX) { v.translationX = dx; v.alpha = 1f - (abs(dx) / (threshold * 3)).coerceIn(0f, 0.6f) }
                        else { v.translationY = minOf(dy, 0f); v.alpha = 1f - (abs(minOf(dy, 0f)) / (threshold * 3)).coerceIn(0f, 0.6f) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    when {
                        !dragged -> openCurrent()
                        !axisX && swipe && -dy > threshold -> dismissCurrent()
                        axisX && abs(dx) > threshold -> page(if (dx < 0) 1 else -1)
                        else -> settle(v)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { settle(v); true }
                else -> false
            }
        }
    }

    private fun openCurrent() { notifs.getOrNull(currentIndex)?.let { openNotification(it) } }
    private fun dismissCurrent() { notifs.getOrNull(currentIndex)?.let { dismissInternal(it.key) } }
    private fun page(delta: Int) { currentIndex = (currentIndex + delta).coerceIn(0, notifs.size - 1); renderCurrent() }
    private fun settle(v: View) { v.animate().translationX(0f).translationY(0f).alpha(1f).setDuration(120).start() }

    private fun params(): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (lock) flags = flags or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = offsetPx
            windowAnimations = android.R.style.Animation_Toast
        }
    }

    // ---------------- misc ----------------

    private fun isLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    private fun startLockScreen() {
        runCatching {
            startActivity(Intent(this, NotifyActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWake() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "LightKeyboard:Notify")
            wakeLock?.let { if (it.isHeld) it.release(); it.acquire(3000) }
        }
    }

    /** Snapshot for the lock-screen activity. */
    fun current(): List<NotifyData> = ArrayList(notifs)

    companion object {
        const val ACTION_PREVIEW = "app.lightphonekeyboard.NOTIFY_PREVIEW"
        private const val PREVIEW_KEY = "__preview__"
        private const val MAX_CARDS = 10
        val DURATIONS = intArrayOf(3, 5, 8, 15)          // seconds, by notifDurationIdx
        val SIZES = floatArrayOf(0.85f, 1.0f, 1.2f)      // card scale, by notifSizeIdx

        @Volatile var instance: NotifyService? = null; private set

        fun dismiss(key: String) { instance?.dismissInternal(key) }
        fun open(data: NotifyData) { instance?.openNotification(data) }

        /** Is Type's notification-listener actually enabled in system settings? */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(":").any { it.contains(context.packageName) }
        }
    }
}
