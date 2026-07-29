package app.lightphonekeyboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout

/**
 * Key shortcuts & more: the Light Phone utilities — pausing the always-on grayscale per app,
 * hardware keymaps, wheel brightness and close-on-lock (see [ColorFilterService]). LightOS
 * template style (see [LightUi]).
 */
class ColorSettingsActivity : SettingsScreen() {

    // The hardware gestures any key-shortcut action can be bound to, in display order. The values are the
    // stored COLOR_KEYMAP_* ints (kept stable), so display order is independent of them. Every gesture is
    // only active inside apps — on the LightOS home screen the keys keep their stock behaviour.
    private val keymapValues = listOf(
        Prefs.COLOR_KEYMAP_NONE, Prefs.COLOR_KEYMAP_WHEEL_LONG,
        Prefs.COLOR_KEYMAP_VOLUME_CHORD, Prefs.COLOR_KEYMAP_CAMERA,
    )
    private val keymapNames = listOf(R.string.off, R.string.keymap_wheel, R.string.keymap_volume, R.string.keymap_camera)

    /** A key-shortcut action bound to one of [keymapValues], cycling through them on tap. */
    private fun keymapItem(c: LinearLayout, labelRes: Int, subRes: Int, get: () -> Int, set: (Int) -> Unit) {
        LightUi.valueItem(c, getString(labelRes), getString(subRes),
            value = { getString(keymapNames[keymapValues.indexOf(get()).coerceAtLeast(0)]) },
            onClick = { set(keymapValues[(keymapValues.indexOf(get()).coerceAtLeast(0) + 1) % keymapValues.size]) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.migrateWheelBack(this)   // fold the retired "wheel goes back" toggle into the Back keymap
        setContentView(LightUi.screen(this, getString(R.string.section_color)) { c ->
            LightUi.hint(c, getString(R.string.setup_color_blurb))
            if (!ColorFilter.hasPermission(this)) {
                LightUi.hint(c, getString(R.string.setup_color_permission))
            }
            LightUi.navItem(c, getString(R.string.setup_color_service), getString(R.string.setup_color_service_sub)) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            LightUi.navItem(c, getString(R.string.setup_color_apps), getString(R.string.setup_color_apps_sub)) {
                startActivity(Intent(this, ColorAppsActivity::class.java))
            }
            keymapItem(c, R.string.color_toggle, R.string.setup_color_keymap_sub,
                { Prefs.colorKeymap(this) }, { Prefs.setColorKeymap(this, it) })
            keymapItem(c, R.string.recent_apps, R.string.setup_recents_keymap_sub,
                { Prefs.recentsKeymap(this) }, { Prefs.setRecentsKeymap(this, it) })
            keymapItem(c, R.string.go_back, R.string.setup_back_keymap_sub,
                { Prefs.backKeymap(this) }, { Prefs.setBackKeymap(this, it) })
            toggleItem(c, R.string.setup_wheel_brightness, R.string.setup_wheel_brightness_sub,
                { Prefs.wheelBrightness(this) },
                {
                    Prefs.setWheelBrightness(this, it)
                    // Brightness writes need the user-grantable "Modify system settings" appop.
                    if (it && !Settings.System.canWrite(this)) {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                        )
                    }
                })
            toggleItem(c, R.string.setup_close_on_lock, R.string.setup_close_on_lock_sub,
                { Prefs.closeAppsOnLock(this) }, { Prefs.setCloseAppsOnLock(this, it) })
            LightUi.navItem(c, getString(R.string.setup_key_test), getString(R.string.setup_key_test_menu_sub)) {
                startActivity(Intent(this, KeyTestActivity::class.java))
            }
        })
    }
}
