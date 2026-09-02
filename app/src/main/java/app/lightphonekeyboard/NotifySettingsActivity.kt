package app.lightphonekeyboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * Notifications section: grant the two permissions, choose which apps show, preview the look, and tune
 * the overlay (swipe, layout, wake, lock-screen, group summaries, stay/​sync behaviour, duration, size,
 * position). Drives [NotifyService] via [Prefs]. LightOS template style (see [LightUi]).
 */
class NotifySettingsActivity : SettingsScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LightUi.screen(this, getString(R.string.section_notify)) { c ->
            LightUi.hint(c, getString(R.string.notify_blurb))

            if (!NotifyService.isEnabled(this)) {
                LightUi.navItem(c, getString(R.string.notify_grant_access), getString(R.string.notify_grant_access_sub)) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
            if (!Settings.canDrawOverlays(this)) {
                LightUi.navItem(c, getString(R.string.notify_grant_overlay), getString(R.string.notify_grant_overlay_sub)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
            LightUi.navItem(c, getString(R.string.notify_apps), getString(R.string.notify_apps_sub)) {
                startActivity(Intent(this, NotifyAppsActivity::class.java))
            }
            LightUi.navItem(c, getString(R.string.notify_preview), getString(R.string.notify_preview_sub)) {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    startService(Intent(this, NotifyService::class.java).setAction(NotifyService.ACTION_PREVIEW))
                }
            }

            toggleItem(c, R.string.notify_swipe, R.string.notify_swipe_sub,
                { Prefs.notifSwipe(this) }, { Prefs.setNotifSwipe(this, it) })
            toggleItem(c, R.string.notify_wake, R.string.notify_wake_sub,
                { Prefs.notifWakeScreen(this) }, { Prefs.setNotifWakeScreen(this, it) })
            toggleItem(c, R.string.notify_lock, R.string.notify_lock_sub,
                { Prefs.notifLockScreen(this) }, { Prefs.setNotifLockScreen(this, it) })
            toggleItem(c, R.string.notify_groups, R.string.notify_groups_sub,
                { Prefs.notifGroupSummaries(this) }, { Prefs.setNotifGroupSummaries(this, it) })
            toggleItem(c, R.string.notify_stay, R.string.notify_stay_sub,
                { Prefs.notifStay(this) }, { Prefs.setNotifStay(this, it) })
            toggleItem(c, R.string.notify_sync, R.string.notify_sync_sub,
                { Prefs.notifSync(this) }, { Prefs.setNotifSync(this, it) })

            cycleItem(c, getString(R.string.notify_duration), R.string.notify_duration_sub,
                listOf("3s", "5s", "8s", "15s"),
                { Prefs.notifDurationIdx(this) }, { Prefs.setNotifDurationIdx(this, it) })
            cycleItem(c, getString(R.string.notify_size), R.string.notify_size_sub,
                listOf(getString(R.string.small), getString(R.string.medium), getString(R.string.large)),
                { Prefs.notifSizeIdx(this) }, { Prefs.setNotifSizeIdx(this, it) })
            // Distance from the top of the screen, in dp (default 28). Cycles through a handful of steps.
            val posDp = listOf(0, 14, 28, 42, 56, 72, 90)
            cycleItem(c, getString(R.string.notify_position), R.string.notify_position_sub,
                posDp.map { "$it dp" },
                { posDp.indexOf(Prefs.notifOffsetDp(this)).let { if (it < 0) posDp.indexOf(28) else it } },
                { Prefs.setNotifOffsetDp(this, posDp[it]) })
        })
    }
}
