package app.lightphonekeyboard

import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/** Base for the LightUi settings screens: the shared toggle / cycle row builders (see [LightUi]). */
abstract class SettingsScreen : AppCompatActivity() {

    /** A boolean setting as "label / On|Off". */
    protected fun toggleItem(c: LinearLayout, labelRes: Int, subRes: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        LightUi.valueItem(c, getString(labelRes), getString(subRes),
            value = { getString(if (get()) R.string.on else R.string.off) }, onClick = { set(!get()) })
    }

    /** A multi-step setting as "label / <current name>", cycling on tap. */
    protected fun cycleItem(c: LinearLayout, label: String, subRes: Int, names: List<String>, get: () -> Int, set: (Int) -> Unit) {
        LightUi.valueItem(c, label, getString(subRes),
            value = { names[get().coerceIn(0, names.size - 1)] }, onClick = { set((get() + 1) % names.size) })
    }
}
