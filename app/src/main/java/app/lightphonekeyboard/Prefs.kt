package app.lightphonekeyboard

import android.content.Context

/** Tiny SharedPreferences wrapper. Single-process app, so the Activity's writes are seen by the IME. */
object Prefs {
    private const val FILE = "light_keyboard_prefs"
    private const val KEY_AUTOCORRECT = "autocorrect"
    private const val KEY_GESTURE = "gesture_typing"
    private const val KEY_PDF_DARK = "pdf_dark_mode"
    private const val KEY_PDF_CONTINUOUS = "pdf_continuous"
    private const val KEY_SUGGESTIONS = "suggestion_bar"
    private const val KEY_VOICE = "voice_enabled"
    private const val KEY_NUMBER_ROW = "number_row"
    private const val KEY_HAPTIC = "haptic_level"
    private const val KEY_AUTO_CAP = "auto_cap"
    private const val KEY_DOUBLE_SPACE = "double_space_period"
    private const val KEY_LANG_INDICATOR = "language_indicator"
    private const val KEY_RECENT_EMOJI = "recent_emoji"
    private const val RECENT_EMOJI_MAX = 12
    private const val KEY_EMOJI_SET = "emoji_set"
    private const val KEY_KEEP_MEDIAL = "he_keep_medial"
    private const val KEY_ENABLED_LANGS = "enabled_languages"
    private const val KEY_ACTIVE_LANG = "active_language"
    private const val KEY_SOUND = "key_sound"
    private const val KEY_LP_DELAY = "longpress_delay"
    private const val KEY_SWIPE_SENS = "swipe_sensitivity"
    private const val KEY_KB_HEIGHT = "keyboard_height"
    private const val KEY_COLOR_APPS = "color_apps"
    private const val KEY_WE_DISABLED = "color_we_disabled_filter"
    private const val KEY_COLOR_KEYMAP = "color_keymap"
    private const val KEY_RECENTS_KEYMAP = "recents_keymap"
    private const val KEY_BACK_KEYMAP = "back_keymap"
    private const val KEY_CLOSE_ON_LOCK = "close_apps_on_lock"
    private const val KEY_WHEEL_BRIGHTNESS = "wheel_brightness"
    private const val KEY_WHEEL_PRESS_BACK = "wheel_press_back"
    private const val KEY_NOTIF_APPS = "notif_apps"
    private const val KEY_NOTIF_HORIZONTAL = "notif_horizontal"
    private const val KEY_NOTIF_SWIPE = "notif_swipe"
    private const val KEY_NOTIF_WAKE = "notif_wake_screen"
    private const val KEY_NOTIF_LOCK = "notif_lock_screen"
    private const val KEY_NOTIF_GROUP = "notif_group_summaries"
    private const val KEY_NOTIF_STAY = "notif_stay_until_dismissed"
    private const val KEY_NOTIF_SYNC = "notif_sync_dismissed"
    private const val KEY_NOTIF_OFFSET = "notif_offset_idx"
    private const val KEY_NOTIF_DURATION = "notif_duration_idx"
    private const val KEY_NOTIF_SIZE = "notif_size_idx"

    /** Haptic strength levels. */
    const val HAPTIC_OFF = 0
    const val HAPTIC_LIGHT = 1
    const val HAPTIC_MEDIUM = 2
    const val HAPTIC_STRONG = 3

    /** Colour-filter keymap gestures, in the order the setting cycles through them. */
    // Hardware gestures any key-shortcut action (colour toggle / recents / back) can be bound to. Values
    // are stored, so keep them stable; 3 and 4 were the retired double-volume gestures (now treated as off).
    const val COLOR_KEYMAP_NONE = 0
    const val COLOR_KEYMAP_CAMERA = 1
    const val COLOR_KEYMAP_VOLUME_CHORD = 2
    const val COLOR_KEYMAP_WHEEL_LONG = 5

    /** Three-step levels shared by long-press delay, swipe sensitivity, and keyboard height. */
    const val LEVEL_LOW = 0
    const val LEVEL_NORMAL = 1
    const val LEVEL_HIGH = 2

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The raw store — so a long-running service can register a change listener and react live. */
    fun shared(c: Context) = prefs(c)

    // --- Notifications (LightNotifi port; used by NotifyService / NotifySettingsActivity) ---
    fun notifApps(c: Context): Set<String> = prefs(c).getStringSet(KEY_NOTIF_APPS, emptySet()) ?: emptySet()
    fun setNotifApps(c: Context, v: Set<String>) = prefs(c).edit().putStringSet(KEY_NOTIF_APPS, v).apply()
    fun notifHorizontal(c: Context) = prefs(c).getBoolean(KEY_NOTIF_HORIZONTAL, false)
    fun setNotifHorizontal(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_NOTIF_HORIZONTAL, v).apply()
    fun notifSwipe(c: Context) = prefs(c).getBoolean(KEY_NOTIF_SWIPE, true)
    fun setNotifSwipe(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_NOTIF_SWIPE, v).apply()
    fun notifWakeScreen(c: Context) = prefs(c).getBoolean(KEY_NOTIF_WAKE, false)
    fun setNotifWakeScreen(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_NOTIF_WAKE, v).apply()
    fun notifLockScreen(c: Context) = prefs(c).getBoolean(KEY_NOTIF_LOCK, false)
    fun setNotifLockScreen(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_NOTIF_LOCK, v).apply()
    fun notifGroupSummaries(c: Context) = prefs(c).getBoolean(KEY_NOTIF_GROUP, false)
    fun setNotifGroupSummaries(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_NOTIF_GROUP, v).apply()
    fun notifStay(c: Context) = prefs(c).getBoolean(KEY_NOTIF_STAY, false)
    fun setNotifStay(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_NOTIF_STAY, v).apply()
    fun notifSync(c: Context) = prefs(c).getBoolean(KEY_NOTIF_SYNC, true)
    fun setNotifSync(c: Context, v: Boolean) = prefs(c).edit().putBoolean(KEY_NOTIF_SYNC, v).apply()
    fun notifOffsetIdx(c: Context) = prefs(c).getInt(KEY_NOTIF_OFFSET, 1)     // 0 low / 1 mid / 2 high
    fun setNotifOffsetIdx(c: Context, v: Int) = prefs(c).edit().putInt(KEY_NOTIF_OFFSET, v).apply()
    fun notifDurationIdx(c: Context) = prefs(c).getInt(KEY_NOTIF_DURATION, 1) // 0 3s / 1 5s / 2 8s / 3 15s
    fun setNotifDurationIdx(c: Context, v: Int) = prefs(c).edit().putInt(KEY_NOTIF_DURATION, v).apply()
    fun notifSizeIdx(c: Context) = prefs(c).getInt(KEY_NOTIF_SIZE, 1)         // 0 small / 1 medium / 2 large
    fun setNotifSizeIdx(c: Context, v: Int) = prefs(c).edit().putInt(KEY_NOTIF_SIZE, v).apply()

    /** Keys the NotifyService watches for live changes. */
    const val KEY_NOTIF_OFFSET_PUB = KEY_NOTIF_OFFSET
    const val KEY_NOTIF_SIZE_PUB = KEY_NOTIF_SIZE

    /** Word-level autocorrect using the device's spell checker. On by default. */
    fun autocorrect(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTOCORRECT, true)

    fun setAutocorrect(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_AUTOCORRECT, value).apply()

    /** Gesture (swipe) typing: glide across letters to type a word. Off by default. */
    fun gestureTyping(c: Context): Boolean = prefs(c).getBoolean(KEY_GESTURE, false)

    fun setGestureTyping(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_GESTURE, value).apply()

    /** PDF viewer: invert page colours (dark mode) and continuous (vs paged) layout. */
    fun pdfDarkMode(c: Context): Boolean = prefs(c).getBoolean(KEY_PDF_DARK, false)
    fun setPdfDarkMode(c: Context, value: Boolean) = prefs(c).edit().putBoolean(KEY_PDF_DARK, value).apply()
    fun pdfContinuous(c: Context): Boolean = prefs(c).getBoolean(KEY_PDF_CONTINUOUS, false)
    fun setPdfContinuous(c: Context, value: Boolean) = prefs(c).edit().putBoolean(KEY_PDF_CONTINUOUS, value).apply()

    /** Suggestion bar: a strip of tap-able word completions above the keys. Off by default (keeps the
     *  default keyboard minimal). */
    fun suggestions(c: Context): Boolean = prefs(c).getBoolean(KEY_SUGGESTIONS, false)

    fun setSuggestions(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_SUGGESTIONS, value).apply()

    /** Voice dictation (mic key + offline STT). Off by default; turning it on downloads the model. */
    fun voiceEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_VOICE, false)

    fun setVoiceEnabled(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_VOICE, value).apply()

    /** Persistent number row above the letters. Off by default. */
    fun numberRow(c: Context): Boolean = prefs(c).getBoolean(KEY_NUMBER_ROW, false)

    fun setNumberRow(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_NUMBER_ROW, value).apply()

    /** Key/cursor haptic strength (HAPTIC_OFF..HAPTIC_STRONG). Strong by default. */
    fun hapticLevel(c: Context): Int = prefs(c).getInt(KEY_HAPTIC, HAPTIC_STRONG)

    fun setHapticLevel(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_HAPTIC, value).apply()

    /** Auto-capitalize at the start of sentences. On by default. */
    fun autoCap(c: Context): Boolean = prefs(c).getBoolean(KEY_AUTO_CAP, true)

    fun setAutoCap(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_AUTO_CAP, value).apply()

    /** Double-tap the space bar to insert ". ". On by default. */
    fun doubleSpacePeriod(c: Context): Boolean = prefs(c).getBoolean(KEY_DOUBLE_SPACE, true)

    fun setDoubleSpacePeriod(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_DOUBLE_SPACE, value).apply()

    /** Show the active language code on the space bar (only matters with 2+ languages). On by default. */
    fun languageIndicator(c: Context): Boolean = prefs(c).getBoolean(KEY_LANG_INDICATOR, true)

    fun setLanguageIndicator(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_LANG_INDICATOR, value).apply()

    /** Play a click on each key press (uses the system key-press sound). Off by default. */
    fun soundEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_SOUND, false)

    fun setSoundEnabled(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_SOUND, value).apply()

    /** How long to hold a key before its long-press fires (LEVEL_LOW = slow … LEVEL_HIGH = fast). */
    fun longPressDelay(c: Context): Int = prefs(c).getInt(KEY_LP_DELAY, LEVEL_NORMAL)

    fun setLongPressDelay(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_LP_DELAY, value).apply()

    /** Space-bar cursor swipe sensitivity (LEVEL_HIGH = the caret moves with less finger travel). */
    fun swipeSensitivity(c: Context): Int = prefs(c).getInt(KEY_SWIPE_SENS, LEVEL_NORMAL)

    fun setSwipeSensitivity(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_SWIPE_SENS, value).apply()

    /** Keyboard height (LEVEL_LOW = compact … LEVEL_HIGH = tall). */
    fun keyboardHeight(c: Context): Int = prefs(c).getInt(KEY_KB_HEIGHT, LEVEL_NORMAL)

    fun setKeyboardHeight(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_KB_HEIGHT, value).apply()

    /** Languages enabled in the globe rotation (comma-separated ISO codes). English + Hebrew by
     *  default, preserving the original behaviour. */
    fun enabledLanguages(c: Context): Set<String> =
        prefs(c).getString(KEY_ENABLED_LANGS, "en,he")!!.split(',').filter { it.isNotEmpty() }.toSet()

    fun setEnabledLanguages(c: Context, codes: Collection<String>) =
        prefs(c).edit().putString(KEY_ENABLED_LANGS, codes.joinToString(",")).apply()

    /** The language the keyboard last showed, so it reopens in the same one. */
    fun activeLanguage(c: Context): String = prefs(c).getString(KEY_ACTIVE_LANG, "en")!!

    fun setActiveLanguage(c: Context, code: String) =
        prefs(c).edit().putString(KEY_ACTIVE_LANG, code).apply()

    /** Hebrew words the user has chosen to keep in their medial form at the end (e.g. קליפ) — never
     *  auto-finalized. Newline-separated, capped. */
    fun keepMedial(c: Context): Set<String> =
        prefs(c).getString(KEY_KEEP_MEDIAL, "")!!.split('\n').filter { it.isNotEmpty() }.toSet()

    fun addKeepMedial(c: Context, word: String) {
        val set = LinkedHashSet(keepMedial(c))
        if (!set.add(word)) return
        while (set.size > 500) set.remove(set.first())   // bound it
        prefs(c).edit().putString(KEY_KEEP_MEDIAL, set.joinToString("\n")).apply()
    }

    /** The user's chosen emoji set (newline-separated), or empty if they haven't customized it — in
     *  which case the keyboard falls back to its default set. */
    fun emojiSet(c: Context): List<String> =
        prefs(c).getString(KEY_EMOJI_SET, "")!!.split('\n').filter { it.isNotEmpty() }

    fun setEmojiSet(c: Context, list: List<String>) =
        prefs(c).edit().putString(KEY_EMOJI_SET, list.joinToString("\n")).apply()

    /** Most-recently-used emoji, newest first (newline-separated). Drives the emoji grid order. */
    fun recentEmoji(c: Context): List<String> =
        prefs(c).getString(KEY_RECENT_EMOJI, "")!!.split('\n').filter { it.isNotEmpty() }

    /** Push [emoji] to the front of the recents list (deduped, capped). */
    fun pushRecentEmoji(c: Context, emoji: String) {
        val list = ArrayList<String>(recentEmoji(c))
        list.remove(emoji)
        list.add(0, emoji)
        while (list.size > RECENT_EMOJI_MAX) list.removeAt(list.size - 1)
        prefs(c).edit().putString(KEY_RECENT_EMOJI, list.joinToString("\n")).apply()
    }

    // --- Colour filter (grayscale) settings, used by ColorFilterService ---

    /** Packages that always open in full colour (grayscale pauses while they're foregrounded). */
    fun colorApps(c: Context): Set<String> =
        prefs(c).getStringSet(KEY_COLOR_APPS, emptySet()) ?: emptySet()

    fun setColorApps(c: Context, value: Set<String>) =
        prefs(c).edit().putStringSet(KEY_COLOR_APPS, value).apply()

    /** True while the service has paused grayscale and still owes the system a restore. Persisted
     *  so grayscale comes back even if the process dies in between. */
    fun weDisabledFilter(c: Context): Boolean = prefs(c).getBoolean(KEY_WE_DISABLED, false)

    fun setWeDisabledFilter(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_WE_DISABLED, value).apply()

    /** Hardware key gesture that toggles colour for the current app, one of COLOR_KEYMAP_*. */
    fun colorKeymap(c: Context): Int = prefs(c).getInt(KEY_COLOR_KEYMAP, COLOR_KEYMAP_NONE)

    fun setColorKeymap(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_COLOR_KEYMAP, value).apply()

    /** Hardware key gesture that opens the system recents screen, one of COLOR_KEYMAP_*. */
    fun recentsKeymap(c: Context): Int = prefs(c).getInt(KEY_RECENTS_KEYMAP, COLOR_KEYMAP_NONE)

    fun setRecentsKeymap(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_RECENTS_KEYMAP, value).apply()

    /** Hardware key gesture that triggers the system Back action, one of COLOR_KEYMAP_*. */
    fun backKeymap(c: Context): Int = prefs(c).getInt(KEY_BACK_KEYMAP, COLOR_KEYMAP_NONE)

    fun setBackKeymap(c: Context, value: Int) =
        prefs(c).edit().putInt(KEY_BACK_KEYMAP, value).apply()

    /** Inside apps, the side wheel (volume keys) adjusts screen brightness instead of volume. */
    fun wheelBrightness(c: Context): Boolean = prefs(c).getBoolean(KEY_WHEEL_BRIGHTNESS, false)

    fun setWheelBrightness(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_WHEEL_BRIGHTNESS, value).apply()

    /** One-time move of the old "wheel long-press goes back" toggle into the unified keymap: if it was on
     *  and Back isn't otherwise bound, bind Back to the wheel long-press gesture. Then retire the flag. */
    fun migrateWheelBack(c: Context) {
        val p = prefs(c)
        if (!p.contains(KEY_WHEEL_PRESS_BACK)) return
        if (p.getBoolean(KEY_WHEEL_PRESS_BACK, false) && backKeymap(c) == COLOR_KEYMAP_NONE) {
            setBackKeymap(c, COLOR_KEYMAP_WHEEL_LONG)
        }
        p.edit().remove(KEY_WHEEL_PRESS_BACK).apply()
    }

    /** Kill the apps used since the last lock whenever the screen locks. Off by default. */
    fun closeAppsOnLock(c: Context): Boolean = prefs(c).getBoolean(KEY_CLOSE_ON_LOCK, false)

    fun setCloseAppsOnLock(c: Context, value: Boolean) =
        prefs(c).edit().putBoolean(KEY_CLOSE_ON_LOCK, value).apply()
}
