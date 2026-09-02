package app.lightphonekeyboard

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Choose which apps' notifications the overlay shows — a per-app On/Off, mirroring the colour-apps
 * picker. Stored in [Prefs.notifApps] and read live by [NotifyService].
 */
class NotifyAppsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chosen = Prefs.notifApps(this).toMutableSet()
        setContentView(LightUi.screen(this, getString(R.string.notify_apps)) { content ->
            LightUi.hint(content, getString(R.string.notify_apps_sub))
            for ((label, pkg) in launchableApps()) {
                LightUi.valueItem(
                    content,
                    label = label,
                    value = { getString(if (pkg in chosen) R.string.on else R.string.off) },
                    onClick = {
                        if (pkg in chosen) chosen.remove(pkg) else chosen.add(pkg)
                        Prefs.setNotifApps(this, chosen)
                    },
                )
            }
        })
    }

    /** Launchable apps as label→package, alphabetical, excluding ourselves. */
    private fun launchableApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
            .toList()
    }
}
