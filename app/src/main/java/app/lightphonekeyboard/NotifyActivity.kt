package app.lightphonekeyboard

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Lock-screen view for notifications: when "show on lock screen" is on and the phone is locked, the
 * service launches this over the keyguard so the chosen apps' notifications are still glanceable. Tap a
 * card to open it; it dismisses itself when there's nothing left, on a tap, or after a short while.
 */
class NotifyActivity : AppCompatActivity() {

    private val autoFinish = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        render()
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); render() }

    private fun render() {
        val notifs = NotifyService.instance?.current().orEmpty()
        if (notifs.isEmpty()) { finish(); return }
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding((16 * dp).toInt(), (72 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            setOnClickListener { finish() }
        }
        for (d in notifs) {
            val card = card(d, dp)
            root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (10 * dp).toInt() })
        }
        setContentView(root)
        autoFinish.removeCallbacksAndMessages(null)
        autoFinish.postDelayed({ finish() }, 12_000)
    }

    private fun card(d: NotifyService.NotifyData, dp: Float): View {
        val pad = (12 * dp).toInt()
        val bg = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = 24 * dp }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@NotifyActivity).apply {
                text = d.title; setTextColor(Color.WHITE); textSize = 16f
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (d.text.isNotEmpty()) addView(TextView(this@NotifyActivity).apply {
                text = d.text; setTextColor(Color.argb(210, 255, 255, 255)); textSize = 15f
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            setOnClickListener { NotifyService.open(d); finish() }
        }
    }

    override fun onDestroy() { autoFinish.removeCallbacksAndMessages(null); super.onDestroy() }
}
