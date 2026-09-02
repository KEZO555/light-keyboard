package app.lightphonekeyboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * Setup + settings menu, in the LightOS template style (see [LightUi]): the two setup steps, then
 * one nav item per settings section — Typing, Keys & feel, Languages, Emoji and the Colour filter —
 * each opening its own screen. Pure B/W, text-first. (On LightOS the system keyboard screens may be
 * buried; adb fallback: `adb shell ime enable app.lightphonekeyboard.debug/...LightImeService` then
 * `ime set`.)
 */
class SetupActivity : SettingsScreen() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LightUi.screen(this, getString(R.string.setup_title), showBack = false) { c ->
            LightUi.hint(c, getString(R.string.setup_blurb))

            // Two setup steps.
            LightUi.navItem(c, getString(R.string.setup_step1)) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            LightUi.navItem(c, getString(R.string.setup_step2)) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
            LightUi.hint(c, getString(R.string.setup_done))

            // Settings sections, one screen each.
            LightUi.navItem(c, getString(R.string.section_typing), getString(R.string.section_typing_sub)) {
                startActivity(Intent(this, TypingSettingsActivity::class.java))
            }
            LightUi.navItem(c, getString(R.string.section_keys), getString(R.string.section_keys_sub)) {
                startActivity(Intent(this, KeyboardSettingsActivity::class.java))
            }
            LightUi.navItem(c, getString(R.string.setup_languages), getString(R.string.setup_languages_menu_sub)) {
                startActivity(Intent(this, LanguagesActivity::class.java))
            }
            LightUi.navItem(c, getString(R.string.setup_emoji), getString(R.string.setup_emoji_sub)) {
                startActivity(Intent(this, EmojiSettingsActivity::class.java))
            }
            LightUi.navItem(c, getString(R.string.section_color), getString(R.string.section_color_sub)) {
                startActivity(Intent(this, ColorSettingsActivity::class.java))
            }
            LightUi.navItem(c, getString(R.string.section_pdf), getString(R.string.section_pdf_sub)) {
                startActivity(Intent(this, PdfViewerActivity::class.java))
            }

            LightUi.hint(c, getString(R.string.setup_tip))
        })
    }
}
