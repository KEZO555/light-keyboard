package app.lightphonekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Black-and-white keyboard view, matched to the LightOS keyboard. UI-only: it reports key events
 * through [Listener]; the host [LightImeService] applies them to the focused field via InputConnection.
 *
 * This is a single self-drawing surface rather than one Android view per key. That matters for
 * typing accuracy:
 *   - Key hit rects *tile the whole surface with no gaps* (the visible gutters are drawn inside each
 *     cell), so there are no dead zones — every pixel, including the very corners, maps to a key.
 *     A point that somehow lands outside all rects snaps to the nearest key center.
 *   - Keys commit on touch-DOWN, and the key is locked in at down. The old per-view onClick fired on
 *     UP and cancelled if the finger drifted off the key — which is exactly what happens when you
 *     "roll" between keys typing fast, so letters were being dropped. Committing on down removes both
 *     the latency and the drift-cancellation.
 *   - Touches are tracked per pointer, so overlapping/rolling presses each register.
 *
 * Swipe DOWN anywhere on the keyboard closes it ([Listener.onDismiss]).
 *
 * Future: Apple-style dynamic target resizing (silently growing the hit rects of likely next letters
 * from a language model while the visible keys stay put) would build on this tiled-rect foundation.
 */
class LightKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onText(s: String)
        fun onBackspace()
        /** Delete the previous whole word (key-repeat escalates to this after a long hold). */
        fun onBackspaceWord()
        fun onEnter()
        fun onDismiss()
        /** Up to [n] characters immediately before the cursor, for the typing-accuracy context model. */
        fun textBeforeCursor(n: Int): CharSequence?
        /** Mic key tapped — start voice dictation. */
        fun onMic()
        /** Listening surface tapped — cancel dictation. */
        fun onMicCancel()
        /** Globe key changed the letters language (ISO code, e.g. "en"/"he"/"es"). Lets the host pick
         *  the right autocorrect engine and dictation backend. */
        fun onLanguageChange(code: String)
        /** Double-tap on space — turn the trailing space into ". " (sentence end). */
        fun onDoubleSpace()
        /** Space-bar swipe — move the caret by [steps] (negative = left, positive = right). */
        fun onCursorMove(steps: Int)
        /** Space-bar swipe up/down — move the caret one line ([down] = down, else up). */
        fun onCursorVertical(down: Boolean)
        /** Long-press the globe — open the keyboard's settings screen. */
        fun onOpenSettings()
        /** The suggestion-bar slot at [index] was tapped — the host decides what it means (insert a
         *  completion/correction, or keep the literal word). */
        fun onSuggestionPicked(index: Int)

        /** A finished swipe-typing gesture: decode [xs]/[ys] (a finger path) against [keys] (letter key
         *  centres, pitch [keyWidth]) and commit the word. */
        fun onGesture(keys: List<GestureTyping.Key>, xs: FloatArray, ys: FloatArray, keyWidth: Float)
    }

    var listener: Listener? = null

    /** Whether swipe typing is on (mirrored from Prefs by the service). */
    var gestureEnabled = false

    // Swipe-typing capture for the first pointer.
    private var gestureDownOnLetter = false
    private var gesturing = false
    private val gestureXs = ArrayList<Float>(64)
    private val gestureYs = ArrayList<Float>(64)

    private object Key {
        const val SHIFT = "__SHIFT__"
        const val BACKSPACE = "__BKSP__"
        const val SPACE = "__SPACE__"
        const val ENTER = "__ENTER__"
        const val GLOBE = "__GLOBE__"   // switches English ⇄ Hebrew
        const val EMOJI = "__EMOJI__"   // opens the emoji panel
        const val PERIOD = "."          // period; long-press starts voice dictation
        const val MIC = "__MIC__"       // (icon reused by the voice listening overlay)
        const val SYMBOLS = "123"
        const val LETTERS = "ABC"
        const val MORE = "=\\<"
        const val COMMA = ","
        // In-keyboard quick-settings panel (opened by holding the globe). Each row toggles/cycles a pref.
        const val SET_HAPTIC = "__SET_HAPTIC__"
        const val SET_AUTOCORRECT = "__SET_AC__"
        const val SET_GESTURE = "__SET_GESTURE__"
        const val SET_AUTOCAP = "__SET_CAP__"
        const val SET_LANGIND = "__SET_LANGIND__"
        const val SET_KBHEIGHT = "__SET_KBH__"
        const val SET_SUGGEST = "__SET_SUGGEST__"
        const val SET_DONE = "__SET_DONE__"
        const val SET_ALL = "__SET_ALL__"
    }

    // The quick-settings panel: one full-width tappable row per setting, then a Done / All-settings row.
    private fun settingsRows(): List<List<String>> {
        val rows = ArrayList<List<String>>(8)
        rows.add(listOf(Key.SET_HAPTIC))
        rows.add(listOf(Key.SET_AUTOCORRECT))
        rows.add(listOf(Key.SET_GESTURE))
        rows.add(listOf(Key.SET_AUTOCAP))
        rows.add(listOf(Key.SET_LANGIND))
        rows.add(listOf(Key.SET_KBHEIGHT))
        rows.add(listOf(Key.SET_SUGGEST))
        rows.add(listOf(Key.SET_DONE, Key.SET_ALL))
        return rows
    }

    // One bottom row in every mode, space centred: [toggle] · comma · globe · space · [. | ⌫] · enter.
    // The comma key types "," on tap and opens the emoji panel on long-press (emoji shown small in its
    // corner). Only the toggle (123/ABC) and the key right of space (period, ⌫ in emoji) change.
    private fun bottomRow(): List<String> {
        // Numpad has its own bottom row (no space bar): ABC · , · 0 · . · ⏎, aligned to the 5-wide grid.
        if (layer == Layer.NUMBER) return listOf(Key.LETTERS, ",", "0", ".", Key.ENTER)
        val toggle = if (layer == Layer.LETTERS) Key.SYMBOLS else Key.LETTERS
        val rightOfSpace = if (layer == Layer.EMOJI) Key.BACKSPACE else Key.PERIOD
        return listOf(toggle, Key.COMMA, Key.GLOBE, Key.SPACE, rightOfSpace, Key.ENTER)
    }

    // Letter layouts now live per-language in [Languages]; this object holds the language-independent
    // layers (number row, symbols pages).
    private object Layout {
        // Optional persistent number row (prepended to the letters layers when enabled in setup).
        val numberRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        // Symbols page 1, matching the system keyboard. =\< leads to page 2 (more).
        val symbols = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
            listOf(Key.MORE, "*", "\"", "'", ":", ";", "!", "?", Key.BACKSPACE),
        )
        // Symbols page 2 (the less-common marks); 123 leads back to page 1.
        val more = listOf(
            listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆"),
            listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\"),
            listOf(Key.SYMBOLS, "%", "©", "®", "™", "[", "]", "<", ">", Key.BACKSPACE),
        )
        // Numeric layer: opened automatically by the host when a number / phone / datetime field is
        // focused (see LightImeService). A calculator-style numpad — 1–9 in a 3×3 block with 0 below,
        // operators down the left, and % / ⌫ down the right. The bottom row (ABC · , · 0 · . · ⏎) and
        // the uniform key widths are handled in bottomRow()/weightFor().
        val number = listOf(
            listOf("+", "1", "2", "3", "%"),
            listOf("-", "4", "5", "6", "/"),
            listOf("*", "7", "8", "9", Key.BACKSPACE),
        )
    }

    companion object {
        // The emoji shown in the panel when the user hasn't customised the set: eight curated
        // favourites first, then the twenty most-common. Laid out as a grid (see layoutEmoji).
        val EMOJI_DEFAULT = listOf(
            "🧞‍♂️", "🦾", "🪬", "👾", "🫶🏻", "🌸", "🫠", "🤌🏻",
            "😀", "😂", "🥹", "😍", "😎", "😭", "🥰", "😘", "😉", "🙃",
            "👍🏻", "🙏🏻", "🙌🏻", "👀", "🔥", "💙", "✨", "🎉", "💯", "🤔",
        )
        // The full pool the user can pick from in Settings (defaults first, then more common emoji).
        // Kept to colour-emoji code points (U+1F300–1F9FF) so they always render as emoji — the older
        // symbol/dingbat block (⭐ ☀ ⚡ ✅ ❌ ❤ …) shows up as plain monochrome glyphs on some phones.
        val EMOJI_CANDIDATES = EMOJI_DEFAULT + listOf(
            "😅", "😊", "🙂", "😌", "😋", "😜", "🤗", "🤭", "🤫", "😐",
            "😶", "😏", "🙄", "😬", "😴", "😪", "😔", "😢", "😩", "😤",
            "😠", "😡", "😱", "😨", "😰", "😳", "🤩", "🤪", "🥴", "🤢",
            "😷", "🥳", "🤠", "🥶", "🤧", "😇", "👋🏻", "👏🏻", "👎🏻", "🤝🏻",
            "💪🏻", "🙈", "🙊", "🧡", "💛", "💚", "💜", "🖤", "🤎", "💔",
            "💕", "💖", "🎈", "🎁", "🎂", "🌙", "🌈", "🍀", "🌹", "🌻",
            "🍕", "🍔", "🍺", "🍷", "🎵", "🎶", "📞", "💬", "💤", "💀",
            "👻", "🤖", "🤡", "👑", "🏆", "🚀", "🎬", "📷", "💡", "📌",
        ).distinct()
    }

    private enum class Layer { LETTERS, SYMBOLS, MORE, NUMBER, EMOJI, SETTINGS }

    private var lang: LangDef = initialLang()
    val isHebrew: Boolean get() = lang.code == "he"
    val langCode: String get() = lang.code

    /** Whether voice dictation is usable for the current language right now (set by the host service):
     *  voice on, and either this language's offline model is downloaded or a system recognizer exists.
     *  Gates the mic key on the period. */
    var voiceAvailable: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Reopen in the last-used language, but only if it's still enabled; otherwise the first enabled. */
    private fun initialLang(): LangDef {
        val enabled = Prefs.enabledLanguages(context)
        val active = Prefs.activeLanguage(context)
        return if (active in enabled) Languages.byCode(active)
        else Languages.ALL.firstOrNull { it.code in enabled } ?: Languages.EN
    }

    private var layer = Layer.LETTERS
    private var shifted = true
    private var capsLock = false           // double-tap shift → stays uppercase until tapped off
    private var lastShiftTapMs = 0L

    // Voice-dictation listening overlay (drawn instead of keys while the recognizer is active).
    private var listening = false
    private var listeningStatus = ""

    // Backspace held → repeat deleting chars, then escalate to whole words after a long hold.
    private var backspacePointerId = -1
    private var backspaceDownMs = 0L
    private val backspaceRepeat = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - backspaceDownMs >= BACKSPACE_WORD_AFTER_MS) {
                listener?.onBackspaceWord()
                postDelayed(this, BACKSPACE_WORD_INTERVAL_MS)
            } else {
                listener?.onBackspace()
                postDelayed(this, BACKSPACE_CHAR_INTERVAL_MS)
            }
        }
    }

    // Long-press a letter → a popup of alternates (English accents / Hebrew niqqud). The base char is
    // committed on down as usual; choosing a non-base option retracts it and commits the choice.
    private var longPressPointerId = -1
    private var longPressCandidate: PlacedKey? = null
    private var popupActive = false
    private var popupOptions: List<String> = emptyList()
    private var popupIndex = 0
    private var popupKey: PlacedKey? = null
    private var popupReplacesBase = false   // letter-symbol preview: commit retracts the base letter
    private val altLongPress = Runnable { showAltPopup() }

    // The 123/ABC toggle acts on release so its long-press can open the accents picker: tap = switch
    // layer, hold = picker. This tracks the in-flight such press, resolved on UP.
    private var pendingReleasePointer = -1
    private var pendingReleaseId = ""

    // Space-bar gestures: double-tap → ". ", drag → move the caret (iPhone trackpad style, 2D).
    private var spacePointerId = -1
    private var spaceDownX = 0f
    private var spaceDownY = 0f
    private var spaceSwiping = false
    private var spaceSwipeRefX = 0f
    private var spaceSwipeRefY = 0f
    private var lastSpaceTapMs = 0L

    /** One key with its (gapless) hit rect and its inset, drawn-to rect. */
    private class PlacedKey(val id: String, val hit: RectF, val vis: RectF) {
        val cx get() = vis.centerX()
        val cy get() = vis.centerY()
    }

    private val placed = ArrayList<PlacedKey>()
    private val letterKeys = ArrayList<PlacedKey>()   // letter keys of the active layout, for the accuracy model

    // Spatial "tap lattice": for the word being typed, the nearby keys (and their spatial log-likelihood)
    // under each ambiguous tap (null entry = a confident tap). Fed to autocorrect so a fix is steered by
    // where the finger actually landed, not just the static key grid.
    private val tapLattice = ArrayList<HashMap<Char, Float>?>()
    private val tapChars = StringBuilder()             // committed letters this set covers, for alignment
    private val tapPxs = ArrayList<Float>(24)          // raw tap point per committed letter (tap-typing hybrid)
    private val tapPys = ArrayList<Float>(24)
    private var tapPrevWasLetter = false

    /** The word being typed, as a tap path (one point per letter) + key geometry — for the tap-typing
     *  hybrid (decode the taps as if they were a swipe). Null if the lattice no longer lines up with [word]. */
    class TapPath(val keys: List<GestureTyping.Key>, val xs: FloatArray, val ys: FloatArray, val keyWidth: Float)

    fun tapPath(word: String): TapPath? {
        if (word.isEmpty() || tapChars.length != word.length || tapPxs.size != word.length || letterKeys.isEmpty()) return null
        for (i in word.indices) if (tapChars[i].lowercaseChar() != word[i].lowercaseChar()) return null
        val keys = ArrayList<GestureTyping.Key>(letterKeys.size)
        for (k in letterKeys) keys.add(GestureTyping.Key(k.id[0], k.cx, k.cy))
        val kw = letterKeys[0].vis.width().coerceAtLeast(1f)
        return TapPath(keys, tapPxs.toFloatArray(), tapPys.toFloatArray(), kw)
    }

    // --- metrics (px) ---
    private val padTop = dpf(3)
    private val padBottom = dpf(10)
    private val padSide = dpf(6)
    private val keyGap = dpf(3)        // half the visible gutter; applied as an inset on each side
    // Row height scales with the keyboard-height setting (compact / normal / tall). The scale is read
    // from prefs once per measure/layout pass (refreshMetrics) and cached here, so rowPitch — accessed
    // several times per pass — doesn't hit SharedPreferences each time.
    private var heightScale = 1f
    private var numberRowOn = false   // all cached once per measure/layout pass (refreshMetrics)
    private var suggestionsOn = false
    private var multiLang = false     // 2+ languages enabled → can label the space bar with the active one
    private var langIndicatorOn = true  // user toggle (quick settings / Settings) for that label
    private fun refreshMetrics() {
        heightScale = when (Prefs.keyboardHeight(context)) {
            Prefs.LEVEL_LOW -> 0.84f
            Prefs.LEVEL_HIGH -> 1.20f
            else -> 1f
        }
        numberRowOn = Prefs.numberRow(context)
        suggestionsOn = Prefs.suggestions(context)
        multiLang = Prefs.enabledLanguages(context).size > 1
        langIndicatorOn = Prefs.languageIndicator(context)
    }
    private val rowKeyH: Float get() = dpf(43) * heightScale
    private val rowPitch: Float get() = rowKeyH + keyGap * 2   // ~49dp per row at normal height

    // Suggestion bar: a strip of tap-able word completions above the keys, reserved as a top band when
    // enabled so the keyboard's height stays constant across every layer (letters / symbols / emoji /
    // settings). [topInset] is that reserved height; layouts offset their rows by it.
    private val stripH: Float get() = dpf(SUGGESTION_STRIP_DP)
    private fun stripShown(): Boolean = suggestionsOn && !listening
    private fun topInset(): Float = if (stripShown()) stripH else 0f
    private var suggestions: List<String> = emptyList()
    // [primary] = the slot pressing space will auto-apply (highlighted); -1 = none. [literal] = the slot
    // showing the user's word verbatim (drawn in quotes; tapping it keeps the word as typed); -1 = none.
    private var suggestionPrimary = -1
    private var suggestionLiteral = -1

    /** Host pushes the current suggestions here ([words] empty to clear). [primary] is the slot space
     *  will commit (highlighted); [literal] is the user's own word (shown quoted). Redraws only. */
    fun setSuggestions(words: List<String>, primary: Int = -1, literal: Int = -1) {
        if (words == suggestions && primary == suggestionPrimary && literal == suggestionLiteral) return
        suggestions = words
        suggestionPrimary = primary
        suggestionLiteral = literal
        if (stripShown()) invalidate()
    }

    // The optional number row (row 0 of the letters layer when enabled) is laid out much shorter than a
    // normal letter row, so it's a slim strip of digits rather than a full extra row.
    private val NUM_ROW_SCALE = 0.6f
    private fun isNumberRowAt0(): Boolean = layer == Layer.LETTERS && numberRowOn
    private fun rowKeyHAt(i: Int): Float = if (i == 0 && isNumberRowAt0()) rowKeyH * NUM_ROW_SCALE else rowKeyH
    private fun rowPitchAt(i: Int): Float = rowKeyHAt(i) + keyGap * 2

    private val emojiCols = 10
    // Rows depend on how many emoji are in the active set (default 28 → 3 rows), recomputed live.
    private val emojiGridRows: Int get() = maxOf(1, (activeEmojiBase().size + emojiCols - 1) / emojiCols)

    // --- paints / icon cache ---
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val spacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    // LightOS-style toggle in the quick-settings panel: a knob on a short track (hollow-left = off,
    // filled-right = on). Round-capped stroke for the track and the hollow knob.
    private val switchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND
    }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    // Suggestion-bar dividers / baseline: faint white, matching the keyboard's restrained look.
    private val stripDivPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(38, 255, 255, 255) }
    // Alternates popup: a dark rounded card with a white border; the selected cell is filled white.
    private val popupBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 28, 28) }
    private val popupBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val popupSelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconCache = HashMap<Int, Drawable>()
    private val popupCardRect = RectF()                 // reused each frame, no per-draw allocation
    private val upperCache = HashMap<String, String>()  // cached uppercase letter labels (shifted draw)

    // --- touch tracking ---
    private val pressed = HashMap<Int, PlacedKey>()   // pointerId -> key, for the pressed highlight
    private var downX = 0f
    private var downY = 0f
    private var firstPointerId = -1
    private var firstKeyRetractable = false   // did the gesture's first tap commit a retractable char?
    private var dismissedThisGesture = false

    init {
        setBackgroundColor(Color.BLACK)
        setWillNotDraw(false)
        rebuild()
    }

    private val currentRows: List<List<String>>
        get() {
            if (layer == Layer.EMOJI) return emptyList()   // emoji is laid out separately
            if (layer == Layer.SETTINGS) return settingsRows()
            var rows = when (layer) {
                Layer.LETTERS -> lang.rows
                Layer.SYMBOLS -> Layout.symbols
                Layer.MORE -> Layout.more
                Layer.NUMBER -> Layout.number
                Layer.EMOJI, Layer.SETTINGS -> emptyList()
            }
            // Optional persistent number row sits above the letters (the symbols layer has its own).
            if (isNumberRowAt0()) {
                rows = listOf(Layout.numberRow) + rows
            }
            return rows + listOf(bottomRow())   // shared [toggle] · emoji · globe · space · . · enter
        }

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        refreshMetrics()
        val w = MeasureSpec.getSize(widthSpec)
        // The emoji panel is laid out to the same row count as the letters layer, so the keyboard
        // keeps a constant height and doesn't jump when you switch to emoji.
        val rowCount = when {
            listening -> lang.rows.size                // keep height constant while listening
            layer == Layer.EMOJI -> emojiGridRows + 1  // emoji rows + control row (= 4, same as letters)
            layer == Layer.SETTINGS -> lettersRowCount()  // match the letters height → no jump on open/close
            else -> currentRows.size
        }
        var h = padTop + padBottom + topInset()   // the suggestion bar (if on) sits above the rows
        for (i in 0 until rowCount) h += rowPitchAt(i)
        setMeasuredDimension(w, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
    }

    private fun rebuild() {
        relayout()
        requestLayout()
        invalidate()
    }

    private fun relayout() {
        refreshMetrics()
        placed.clear()
        letterKeys.clear()
        if (width == 0 || height == 0 || listening) return
        if (layer == Layer.EMOJI) { layoutEmoji(); return }
        if (layer == Layer.SETTINGS) { layoutSettings(); return }

        val w = width.toFloat()
        val h = height.toFloat()
        val rows = currentRows
        val n = rows.size
        val top = topInset()
        // Rows can have different heights (the number row is shorter), so accumulate the top offset.
        var rowTop = top + padTop
        for (i in rows.indices) {
            val pitch = rowPitchAt(i)
            // Bands tile [top, h]: the top row absorbs the top pad, the bottom row absorbs the bottom pad.
            val bandTop = if (i == 0) top else rowTop
            val bandBottom = if (i == n - 1) h else rowTop + pitch
            val visTop = rowTop + keyGap
            val visBottom = visTop + rowKeyHAt(i)
            layoutRow(rows[i], bandTop, bandBottom, visTop, visBottom, w)
            rowTop += pitch
        }
        for (k in placed) if (isLetterKey(k.id)) letterKeys.add(k)
    }

    private fun layoutRow(
        row: List<String>, bandTop: Float, bandBottom: Float,
        visTop: Float, visBottom: Float, w: Float,
    ) {
        val totalWeight = row.sumOf { weightFor(it).toDouble() }.toFloat()
        val drawLeft = padSide
        val drawW = w - padSide * 2
        var cum = 0f
        for ((j, id) in row.withIndex()) {
            val cellLeft = drawLeft + drawW * (cum / totalWeight)
            cum += weightFor(id)
            val cellRight = drawLeft + drawW * (cum / totalWeight)
            // Hit rects tile [0, w]: edge keys reach the screen edge; interior boundaries sit on the
            // visible cell edge, i.e. the midline of the gutter between two keys (nearest-key by design).
            val hitLeft = if (j == 0) 0f else cellLeft
            val hitRight = if (j == row.size - 1) w else cellRight
            placed.add(
                PlacedKey(
                    id,
                    RectF(hitLeft, bandTop, hitRight, bandBottom),
                    RectF(cellLeft + keyGap, visTop, cellRight - keyGap, visBottom),
                ),
            )
        }
    }

    private fun layoutEmoji() {
        // Emoji grid fills the rows above a normal control row, using the same row geometry as the
        // letters layer so the keyboard height never jumps. The control row mirrors the letters bottom
        // row — a layer key, then the globe at the SAME index, then space — so the globe never moves;
        // it just swaps enter for backspace (deleting an emoji is what you want here).
        val w = width.toFloat()
        val h = height.toFloat()
        val top = topInset()
        val drawW = w - padSide * 2
        val glyphs = displayedEmoji()
        for (r in 0 until emojiGridRows) {
            val bandTop = if (r == 0) top else top + padTop + r * rowPitch
            val bandBottom = top + padTop + (r + 1) * rowPitch
            val visTop = top + padTop + r * rowPitch + keyGap
            val visBottom = visTop + rowKeyH
            for (c in 0 until emojiCols) {
                val idx = r * emojiCols + c
                if (idx >= glyphs.size) break
                val cellLeft = padSide + drawW * (c.toFloat() / emojiCols)
                val cellRight = padSide + drawW * ((c + 1).toFloat() / emojiCols)
                val hitLeft = if (c == 0) 0f else cellLeft
                val hitRight = if (c == emojiCols - 1) w else cellRight
                placed.add(
                    PlacedKey(
                        glyphs[idx],
                        RectF(hitLeft, bandTop, hitRight, bandBottom),
                        RectF(cellLeft, visTop, cellRight, visBottom),
                    ),
                )
            }
        }

        // Same bottom row as every other mode (here the fifth key is backspace, so you can delete).
        val i = emojiGridRows
        val bandTop = top + padTop + i * rowPitch
        val visTop = bandTop + keyGap
        layoutRow(bottomRow(), bandTop, h, visTop, visTop + rowKeyH, w)
    }

    /** How many rows the letters layer occupies right now (letters + bottom, plus the number row if on).
     *  The settings panel matches this so opening/closing it never changes the keyboard's height. */
    private fun lettersRowCount(): Int =
        (if (numberRowOn) 1 else 0) + lang.rows.size + 1

    /** Lay the quick-settings rows out to fill the same total height as the letters layer (even bands). */
    private fun layoutSettings() {
        val w = width.toFloat()
        val h = height.toFloat()
        val rows = settingsRows()
        val inset = topInset()
        val band = (h - inset) / rows.size
        for (i in rows.indices) {
            val top = inset + i * band
            layoutRow(rows[i], top, top + band, top + keyGap, top + band - keyGap, w)
        }
    }

    /** The active emoji set: the user's chosen set in Settings, or the default if they haven't picked. */
    private fun activeEmojiBase(): List<String> =
        Prefs.emojiSet(context).ifEmpty { EMOJI_DEFAULT }

    /** The active emoji set, with recently-used ones pulled to the front (same glyphs, reordered). */
    private fun displayedEmoji(): List<String> {
        val base = activeEmojiBase()
        val recents = Prefs.recentEmoji(context).filter { it in base }
        return if (recents.isEmpty()) base else recents + base.filter { it !in recents }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (listening) { drawListening(canvas); return }
        if (stripShown()) drawSuggestionStrip(canvas)
        for (pk in placed) {
            if (pressed.containsValue(pk)) {
                val r = dpf(8)
                canvas.drawRoundRect(pk.vis, r, r, pressPaint)
            }
            drawKey(canvas, pk)
        }
        if (popupActive) drawAltPopup(canvas)
    }

    /** The alternates popup: a card of option cells above the long-pressed key (below it for the top
     *  row, where there's no room above). The selected cell is filled; slide to it and release. */
    private fun drawAltPopup(canvas: Canvas) {
        val opts = popupOptions
        val k = popupKey ?: return
        if (opts.isEmpty()) return
        val cellW = popupCellW(opts.size)
        val cellH = dpf(POPUP_CELL_H_DP)
        val totalW = cellW * opts.size
        val left = popupLeft(totalW)
        var top = k.vis.top - dpf(5) - cellH
        if (top < 0f) top = k.vis.bottom + dpf(5)     // genuinely no room above (top row) → drop below
        val r = dpf(8)
        val card = popupCardRect.apply { set(left, top, left + totalW, top + cellH) }
        canvas.drawRoundRect(card, r, r, popupBgPaint)
        canvas.drawRoundRect(card, r, r, popupBorderPaint)
        textPaint.textSize = spf(22)
        for (j in opts.indices) {
            val cl = left + cellW * j
            if (j == popupIndex) {
                val inset = dpf(3)
                val ir = dpf(6)
                canvas.drawRoundRect(cl + inset, top + inset, cl + cellW - inset, top + cellH - inset, ir, ir, popupSelPaint)
                textPaint.color = Color.BLACK
            } else {
                textPaint.color = Color.WHITE
            }
            // A bare Hebrew vowel point renders on a dotted circle so it's visible in the picker.
            val o = opts[j]
            val label = if (o.length == 1 && o[0] in 'ְ'..'ּ') "◌$o" else o
            val baseline = card.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, cl + cellW / 2f, baseline, textPaint)
        }
        textPaint.color = Color.WHITE     // restore for subsequent draws
    }

    /** The suggestion bar: up to three tap-able word completions, evenly split, with hairline dividers
     *  (and a highlighted pill on the auto-applied slot). Drawn only in the letters layer; on other
     *  layers it's a reserved empty band so the keyboard height stays constant. */
    private fun drawSuggestionStrip(canvas: Canvas) {
        val sh = stripH
        if (layer != Layer.LETTERS) return
        val sugg = suggestions
        if (sugg.isEmpty()) return
        val n = sugg.size
        val drawW = width - padSide * 2f
        val cellW = drawW / n
        textPaint.textSize = spf(17)   // match the quick-settings menu text size
        textPaint.color = Color.WHITE
        val baseline = sh / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        for (j in 0 until n) {
            val cl = padSide + cellW * j
            if (j > 0) {                                       // thin divider between cells
                val dh = sh * 0.4f
                canvas.drawRect(cl, sh / 2f - dh / 2f, cl + dpf(1), sh / 2f + dh / 2f, stripDivPaint)
            }
            // The auto-applied slot is drawn bold so it pops (no fill); quotes mark the user's literal
            // word so "keep what I typed" is unmistakable.
            textPaint.typeface = if (j == suggestionPrimary) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            val shown = if (j == suggestionLiteral) "“${sugg[j]}”" else sugg[j]
            val label = ellipsize(shown, cellW - dpf(14), textPaint)
            canvas.drawText(label, cl + cellW / 2f, baseline, textPaint)
        }
        textPaint.typeface = android.graphics.Typeface.DEFAULT   // restore for other text
    }

    /** Truncate [s] with an ellipsis so it fits [maxW] in [paint] (suggestion words are usually short). */
    private fun ellipsize(s: String, maxW: Float, paint: Paint): String {
        if (paint.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && paint.measureText(s.substring(0, end) + "…") > maxW) end--
        return s.substring(0, end) + "…"
    }

    /** The voice-dictation surface: a big centered mic, the live status/partial text, and a hint. */
    private fun drawListening(canvas: Canvas) {
        val cx = width / 2f
        val midY = height / 2f
        val d = iconCache.getOrPut(R.drawable.ic_kb_mic) { context.getDrawable(R.drawable.ic_kb_mic)!! }
        val size = dpf(44)
        val left = (cx - size / 2f).toInt()
        val top = (midY - size - dpf(6)).toInt()
        d.setBounds(left, top, (left + size).toInt(), (top + size).toInt())
        d.draw(canvas)
        textPaint.textSize = spf(18)
        // Wrap the live text so a long phrase stacks into lines instead of running off the screen.
        drawWrappedCentered(canvas, listeningStatus, cx, midY + dpf(22), width - dpf(48), textPaint)
        textPaint.textSize = spf(12)
        canvas.drawText(context.getString(R.string.tap_done), cx, height - dpf(18), textPaint)
    }

    /** Draw [text] centered on ([cx],[centerY]), wrapping at word boundaries to fit [maxWidth]. */
    private fun drawWrappedCentered(
        canvas: Canvas, text: String, cx: Float, centerY: Float, maxWidth: Float, paint: Paint,
    ) {
        if (text.isEmpty()) return
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isEmpty() || paint.measureText(candidate) <= maxWidth) {
                line = candidate
            } else {
                lines.add(line); line = word
            }
        }
        if (line.isNotEmpty()) lines.add(line)
        val lineH = paint.descent() - paint.ascent()
        var baseline = centerY - lines.size * lineH / 2f - paint.ascent()
        for (l in lines) { canvas.drawText(l, cx, baseline, paint); baseline += lineH }
    }

    private fun drawKey(canvas: Canvas, pk: PlacedKey) {
        val id = pk.id
        if (id.startsWith("__SET_")) { drawSettingRow(canvas, pk); return }
        if (id == Key.SPACE) {
            // The centred space-bar line, with the active language code set into the middle of it (line
            // segments flanking the label) when more than one language is enabled — so the line stays but
            // it's clear which language is on. With a single language it's just the plain line.
            val y = pk.vis.centerY()
            val lineL = pk.vis.left + dpf(28)
            val lineR = pk.vis.right - dpf(28)
            if (multiLang && langIndicatorOn) {
                val label = lang.code.uppercase()
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = spf(12)
                textPaint.color = Color.argb(190, 255, 255, 255)
                val cx = pk.vis.centerX()
                val gap = dpf(8)                                    // breathing room around the label
                val leftEnd = cx - textPaint.measureText(label) / 2f - gap
                val rightStart = cx - (leftEnd - cx)                // mirror of leftEnd about the centre
                if (leftEnd > lineL) canvas.drawRect(lineL, y - dpf(1), leftEnd, y + dpf(1), spacePaint)
                if (rightStart < lineR) canvas.drawRect(rightStart, y - dpf(1), lineR, y + dpf(1), spacePaint)
                canvas.drawText(label, cx, y - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
                textPaint.color = Color.WHITE
            } else {
                canvas.drawRect(lineL, y - dpf(1), lineR, y + dpf(1), spacePaint)
            }
            return
        }
        val icon = iconFor(id)
        if (icon != null) {
            drawIcon(canvas, icon, pk.vis, padFor(id))
            // Caps-lock indicator: an underline beneath the shift glyph.
            if (id == Key.SHIFT && capsLock) {
                val cx = pk.vis.centerX()
                val y = pk.vis.centerY() + dpf(11)
                canvas.drawRect(cx - dpf(7), y - dpf(1), cx + dpf(7), y + dpf(1), spacePaint)
            }
            // Tiny gear badge tucked into the globe's top-right corner (the globe is drawn a touch
            // smaller — see padFor — so the gear sits in the freed corner, not over the globe).
            if (id == Key.GLOBE) {
                val s = dpf(8)
                val d = iconCache.getOrPut(R.drawable.ic_kb_gear) { context.getDrawable(R.drawable.ic_kb_gear)!! }
                val right = pk.vis.right - dpf(1)
                val top = pk.vis.top + dpf(1)
                d.setBounds((right - s).toInt(), top.toInt(), right.toInt(), (top + s).toInt())
                d.alpha = 120
                d.draw(canvas)
            }
            return
        }
        // Emoji glyphs are large; everything else (letters, the layer toggle, the period) is normal.
        // Keys in the slim number row are shorter, so their digits get a smaller size to fit.
        val short = pk.vis.height() < rowKeyH * 0.8f
        // The 123 toggle carries an accent hint in its top-right corner, so draw "123" smaller and a
        // little lower to leave that corner clear.
        val cornerAccent = id == Key.SYMBOLS && layer == Layer.LETTERS && lang.accents.isNotEmpty()
        val size = when {
            layer == Layer.EMOJI && id in EMOJI_CANDIDATES -> spf(24)
            id.length == 1 -> if (short) spf(16) else spf(23)
            cornerAccent -> spf(17)      // the "123" toggle — a bit larger, close to the "ABC" size
            else -> spf(18)
        }
        textPaint.textSize = size
        val baseShift = if (cornerAccent) dpf(3) else 0f
        val baseline = pk.vis.centerY() + baseShift - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(labelFor(id), pk.vis.centerX(), baseline, textPaint)
        // Tiny dim corner hint (long-press types it) — minimal, top-right.
        val hint = hintFor(id)
        if (hint != null) {
            textPaint.textSize = spf(9)
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = Color.argb(115, 255, 255, 255)
            canvas.drawText(hint, pk.vis.right - dpf(4), pk.vis.top + dpf(12), textPaint)
            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.CENTER
        }
        // The 123 key holds for the language's accents / vowel points — hint it with the first one,
        // tucked into the top-right corner (the "123" label is drawn smaller + lower to leave room).
        if (cornerAccent) {
            val a = lang.accents[0]
            val glyph = if (a.length == 1 && a[0] in 'ְ'..'ּ') "◌$a" else a   // dotted circle for a bare niqqud
            textPaint.textSize = spf(9)
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = Color.argb(140, 255, 255, 255)
            canvas.drawText(glyph, pk.vis.right - dpf(3), pk.vis.top + dpf(11), textPaint)
            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.CENTER
        }
        // The period doubles as the voice key (long-press) — show a small mic so it's discoverable.
        // Shown only when voice is usable for the current language (model downloaded / recognizer present).
        if (id == Key.PERIOD && voiceAvailable) {
            val s = dpf(15)
            val d = iconCache.getOrPut(R.drawable.ic_kb_mic) { context.getDrawable(R.drawable.ic_kb_mic)!! }
            val cx = pk.vis.centerX()
            val top = pk.vis.top + dpf(2)
            d.setBounds((cx - s / 2f).toInt(), top.toInt(), (cx + s / 2f).toInt(), (top + s).toInt())
            d.draw(canvas)
        }
        // The comma key opens emoji on long-press — show a small emoji so it's discoverable.
        if (id == Key.COMMA) {
            val s = dpf(14)
            val d = iconCache.getOrPut(R.drawable.ic_kb_emoji) { context.getDrawable(R.drawable.ic_kb_emoji)!! }
            val cx = pk.vis.centerX()
            val top = pk.vis.top + dpf(2)
            d.setBounds((cx - s / 2f).toInt(), top.toInt(), (cx + s / 2f).toInt(), (top + s).toInt())
            d.draw(canvas)
        }
    }

    /** A quick-settings row: label on the left; a LightOS switch (on/off rows) or the current value text
     *  (multi-level rows) on the right; Done/All-settings centred. */
    private fun drawSettingRow(canvas: Canvas, pk: PlacedKey) {
        val (label, value) = settingLabel(pk.id)
        val baseline = pk.vis.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        textPaint.textSize = spf(17)
        if (value == null) {                                   // Done / All settings — centred buttons
            canvas.drawText(label, pk.vis.centerX(), baseline, textPaint)
            return
        }
        val padX = dpf(18)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, pk.vis.left + padX, baseline, textPaint)
        // True on/off rows show a LightOS switch; multi-level rows (haptics, height — whose value can also
        // read "Off") keep their text value.
        val isToggle = pk.id == Key.SET_AUTOCORRECT || pk.id == Key.SET_AUTOCAP ||
            pk.id == Key.SET_LANGIND || pk.id == Key.SET_SUGGEST
        if (isToggle) {
            drawSwitch(canvas, pk.vis.right - padX, pk.vis.centerY(), value == "On")
        } else {
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = Color.argb(190, 255, 255, 255)
            canvas.drawText(value, pk.vis.right - padX, baseline, textPaint)
            textPaint.color = Color.WHITE
        }
        textPaint.textAlign = Paint.Align.CENTER
    }

    /** A Light Phone–style toggle ending at [rightX], vertical centre [cy]: a short track with a knob —
     *  hollow knob on the left when [on] is false, a filled knob on the right when true. */
    private fun drawSwitch(canvas: Canvas, rightX: Float, cy: Float, on: Boolean) {
        val r = dpf(5)                        // knob radius
        val w = dpf(30)                       // total track width
        val left = rightX - w
        switchPaint.style = Paint.Style.STROKE
        if (on) {
            canvas.drawLine(left, cy, rightX - r, cy, switchPaint)         // track, then…
            switchPaint.style = Paint.Style.FILL
            canvas.drawCircle(rightX - r, cy, r, switchPaint)             // …filled knob at the right
        } else {
            canvas.drawCircle(left + r, cy, r, switchPaint)              // hollow knob at the left…
            canvas.drawLine(left + r * 2 + dpf(2), cy, rightX, cy, switchPaint)  // …then track
        }
    }

    /** Label + current-value text for a quick-settings row (value null = a plain centred button). */
    private fun settingLabel(id: String): Pair<String, String?> {
        fun onOff(b: Boolean) = context.getString(if (b) R.string.on else R.string.off)
        return when (id) {
            Key.SET_HAPTIC ->
                context.getString(R.string.haptic_feedback) to listOf(
                    R.string.off, R.string.haptic_light, R.string.haptic_medium, R.string.haptic_strong
                ).map { context.getString(it) }[Prefs.hapticLevel(context).coerceIn(0, 3)]
            Key.SET_AUTOCORRECT -> context.getString(R.string.setup_autocorrect) to onOff(Prefs.autocorrect(context))
            Key.SET_GESTURE -> context.getString(R.string.setup_gesture) to onOff(Prefs.gestureTyping(context))
            Key.SET_AUTOCAP -> context.getString(R.string.setup_auto_cap) to onOff(Prefs.autoCap(context))
            Key.SET_LANGIND -> context.getString(R.string.lang_label) to onOff(Prefs.languageIndicator(context))
            Key.SET_KBHEIGHT ->
                context.getString(R.string.kb_size) to listOf(
                    R.string.size_compact, R.string.speed_normal, R.string.size_tall
                ).map { context.getString(it) }[Prefs.keyboardHeight(context).coerceIn(0, 2)]
            Key.SET_SUGGEST -> context.getString(R.string.setup_suggestions) to onOff(Prefs.suggestions(context))
            Key.SET_DONE -> context.getString(R.string.done) to null
            Key.SET_ALL -> context.getString(R.string.all_settings) to null
            else -> "" to null
        }
    }

    /** Buzz once at [level]'s key-press strength (used when the haptics row is cycled). */
    private fun previewHaptic(level: Int) = when (level) {
        Prefs.HAPTIC_LIGHT -> buzz(30, 200)
        Prefs.HAPTIC_MEDIUM -> buzz(38, 228)
        Prefs.HAPTIC_STRONG -> buzz(45, 255)
        else -> Unit
    }

    private fun drawIcon(canvas: Canvas, res: Int, vis: RectF, pad: Float) {
        val d = iconCache.getOrPut(res) { context.getDrawable(res)!! }
        val size = (minOf(vis.width(), vis.height()) - pad * 2).coerceAtLeast(1f)
        val left = (vis.centerX() - size / 2f).toInt()
        val top = (vis.centerY() - size / 2f).toInt()
        d.setBounds(left, top, (left + size).toInt(), (top + size).toInt())
        d.draw(canvas)
    }

    private fun iconFor(id: String): Int? = when (id) {
        Key.GLOBE -> R.drawable.ic_kb_globe
        Key.EMOJI -> R.drawable.ic_kb_emoji
        Key.BACKSPACE -> R.drawable.ic_kb_backspace
        Key.ENTER -> R.drawable.ic_kb_enter
        Key.MIC -> R.drawable.ic_kb_mic
        Key.SHIFT -> if (shifted) R.drawable.ic_kb_chevron_down else R.drawable.ic_kb_chevron_up
        else -> null
    }

    // Smaller inset = larger glyph. The bottom-row icons (globe / emoji / enter) are roomy so they
    // read clearly; shift keeps a touch more breathing room for its caps-lock underline.
    private fun padFor(id: String): Float = when (id) {
        Key.SHIFT -> dpf(7)
        Key.GLOBE -> dpf(3)              // roomy globe; the gear badge still tucks into the corner
        Key.EMOJI, Key.ENTER -> dpf(2)
        Key.BACKSPACE -> dpf(4)
        else -> dpf(6)
    }

    // Latin scripts have case; caseless scripts (Hebrew) return letters verbatim. The "back to letters"
    // toggle shows the active language's own label (e.g. "אבג" for Hebrew).
    private fun labelFor(id: String): String = when {
        id == Key.LETTERS -> lang.lettersLabel
        lang.hasCase && shifted && layer == Layer.LETTERS && id.length == 1 && id[0].isLetter() ->
            upperCache[id] ?: id.uppercase().also { upperCache[id] = it }
        else -> id
    }

    // The numpad is a uniform grid (every key the same width); other layers use the per-key weights.
    private fun weightFor(id: String): Float =
        if (layer == Layer.NUMBER) 1f else weightForKey(id)

    private fun weightForKey(id: String): Float = when (id) {
        Key.SPACE -> 5f
        // Only the bottom-row layer toggle is wide. =\< stays normal width so the symbols row-3
        // backspace lines up with the letters row-3 backspace.
        Key.SYMBOLS, Key.LETTERS -> 1.7f
        else -> 1f
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (listening) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) { tap(); listener?.onMicCancel() }
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dismissedThisGesture = false
                firstPointerId = ev.getPointerId(0)
                firstKeyRetractable = pressDown(firstPointerId, ev.x, ev.y)
                gesturing = false
                gestureDownOnLetter = gestureEnabled && layer == Layer.LETTERS &&
                    (findKey(ev.x, ev.y)?.let { isLetterKey(it.id) } == true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                pressDown(ev.getPointerId(idx), ev.getX(idx), ev.getY(idx))
            }

            MotionEvent.ACTION_MOVE -> {
                // 1) Alternates popup open → the long-press pointer's x selects the option.
                if (popupActive) {
                    val pidx = ev.findPointerIndex(longPressPointerId)
                    if (pidx >= 0) { updatePopupSelection(ev.getX(pidx), ev.getY(pidx)); invalidate() }
                    return true
                }
                // 2) Space-bar drag → 2D caret movement (iPhone trackpad style).
                if (spacePointerId != -1) {
                    val sidx = ev.findPointerIndex(spacePointerId)
                    if (sidx >= 0 && handleSpaceSwipe(ev.getX(sidx), ev.getY(sidx))) return true
                }
                // 2b) Swipe typing: a glide from a letter key collects a path; the first tap is retracted.
                if (gestureDownOnLetter && !dismissedThisGesture) {
                    val idx = ev.findPointerIndex(firstPointerId)
                    if (idx >= 0 && handleGestureMove(ev.getX(idx), ev.getY(idx))) return true
                }
                // 3) Otherwise, the first pointer's downward swipe dismisses the keyboard.
                if (!dismissedThisGesture) {
                    val idx = ev.findPointerIndex(firstPointerId)
                    if (idx >= 0) {
                        val dy = ev.getY(idx) - downY
                        val dx = ev.getX(idx) - downX
                        if (dy > dpf(60) && dy > abs(dx) * 1.5f) {
                            dismissedThisGesture = true
                            stopBackspaceRepeat()
                            endAltLongPress()
                            pendingReleasePointer = -1
                            // The first tap already committed a char on down; retract it so the swipe
                            // doesn't leave a stray letter behind.
                            if (firstKeyRetractable) listener?.onBackspace()
                            pressed.clear()
                            invalidate()
                            listener?.onDismiss()
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pid = ev.getPointerId(ev.actionIndex)
                if (pid == longPressPointerId) commitPopupAndReset()
                if (pid == pendingReleasePointer) { if (!dismissedThisGesture) onKey(pendingReleaseId); pendingReleasePointer = -1 }
                pressed.remove(pid)
                if (pid == backspacePointerId) stopBackspaceRepeat()
                if (pid == spacePointerId) spacePointerId = -1
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                if (gesturing) {
                    if (up) finishGesture() else { gesturing = false; gestureXs.clear(); gestureYs.clear() }
                    pressed.clear(); invalidate()
                    return true
                }
                if (up) commitPopupAndReset() else endAltLongPress()
                // A quick tap (no long-press fired) runs the key's action on release.
                if (pendingReleasePointer != -1) { if (up && !dismissedThisGesture) onKey(pendingReleaseId); pendingReleasePointer = -1 }
                pressed.clear()
                stopBackspaceRepeat()
                spacePointerId = -1
                invalidate()
            }
        }
        return true
    }

    /** Swipe typing: collect the finger path. Returns true once a glide has started (consuming the move).
     *  The first letter, typed on touch-down, is retracted when the glide begins. */
    private fun handleGestureMove(x: Float, y: Float): Boolean {
        if (!gesturing) {
            val dx = x - downX; val dy = y - downY
            if (abs(dx) < dpf(16) && abs(dy) < dpf(16)) return false
            if (dy > 0 && dy > abs(dx) * 1.5f) return false   // a steep downward swipe → leave it to dismiss
            gesturing = true
            endAltLongPress(); stopBackspaceRepeat()
            clearTapLattice()
            if (firstKeyRetractable) { listener?.onBackspace(); firstKeyRetractable = false }
            pressed.clear()
            gestureXs.clear(); gestureYs.clear()
            gestureXs.add(downX); gestureYs.add(downY)
            invalidate()
        }
        val n = gestureXs.size
        if (n == 0 || abs(x - gestureXs[n - 1]) + abs(y - gestureYs[n - 1]) >= dpf(6)) {
            if (gestureXs.size < 96) { gestureXs.add(x); gestureYs.add(y) }   // resample + cap
        }
        return true
    }

    /** Finish a gesture: hand the path + letter-key geometry to the host to decode and commit. */
    private fun finishGesture() {
        gesturing = false
        val n = gestureXs.size
        if (n >= 2 && letterKeys.isNotEmpty()) {
            val keys = ArrayList<GestureTyping.Key>(letterKeys.size)
            for (k in letterKeys) keys.add(GestureTyping.Key(k.id[0], k.cx, k.cy))
            val kw = letterKeys[0].vis.width().coerceAtLeast(1f)
            listener?.onGesture(keys, gestureXs.toFloatArray(), gestureYs.toFloatArray(), kw)
        }
        gestureXs.clear(); gestureYs.clear()
    }

    /** Space-bar drag → caret moves like an iPhone trackpad: left/right by characters, up/down by
     *  lines. Returns true once swiping starts (the first step retracts the space typed on down). */
    private fun handleSpaceSwipe(x: Float, y: Float): Boolean {
        if (!spaceSwiping) {
            if (abs(x - spaceDownX) < SPACE_SWIPE_START && abs(y - spaceDownY) < SPACE_SWIPE_START) return false
            spaceSwiping = true
            spaceSwipeRefX = spaceDownX
            spaceSwipeRefY = spaceDownY
            endAltLongPress()
            if (firstPointerId == spacePointerId && firstKeyRetractable) {
                listener?.onBackspace()           // remove the space inserted on down
                firstKeyRetractable = false
            }
        }
        var moved = false
        val hStep = SPACE_SWIPE_STEP * swipeScale()       // less travel per move = more sensitive
        val vStep = SPACE_SWIPE_VSTEP * swipeScale()
        while (x - spaceSwipeRefX >= hStep) { listener?.onCursorMove(1); spaceSwipeRefX += hStep; moved = true }
        while (x - spaceSwipeRefX <= -hStep) { listener?.onCursorMove(-1); spaceSwipeRefX -= hStep; moved = true }
        while (y - spaceSwipeRefY >= vStep) { listener?.onCursorVertical(true); spaceSwipeRefY += vStep; moved = true }
        while (y - spaceSwipeRefY <= -vStep) { listener?.onCursorVertical(false); spaceSwipeRefY -= vStep; moved = true }
        if (moved) cursorTick()
        return true
    }

    /** Resolve the key under a pointer, commit it immediately, and light it up. */
    private fun pressDown(pointerId: Int, x: Float, y: Float): Boolean {
        if (dismissedThisGesture) return false
        // The suggestion bar sits above all key rects. A tap in it picks that completion (letters layer
        // only; elsewhere the band is empty). Either way the strip never falls through to a key — without
        // this guard, findKey's nearest-key fallback would type a top-row letter from the empty band.
        if (stripShown() && y <= stripH) {
            if (layer == Layer.LETTERS && suggestions.isNotEmpty()) {
                val cellW = (width - padSide * 2f) / suggestions.size
                val j = (((x - padSide) / cellW).toInt()).coerceIn(0, suggestions.size - 1)
                tap(); clearTapLattice(); listener?.onSuggestionPicked(j)
            }
            return false
        }
        val raw = findKey(x, y) ?: return false
        // Only letters get the accuracy treatment; control keys & other layers stay exact hit-testing.
        val key = if (layer == Layer.LETTERS && isLetter(raw.id)) resolveLetter(x, y, raw) else raw
        pressed[pointerId] = key
        invalidate()
        // Maintain the spatial tap lattice for the word being typed (consumed by [spatialSubCost]).
        val letterTap = layer == Layer.LETTERS && isLetterKey(key.id)
        if (letterTap) {
            if (!tapPrevWasLetter) clearTapLattice()      // a new word starts
            tapLattice.add(tapCandidates(x, y, raw))
            tapChars.append(key.id[0])
            tapPxs.add(x); tapPys.add(y)
            tapPrevWasLetter = true
        }
        if (key.id == Key.SYMBOLS || key.id == Key.LETTERS || key.id == Key.GLOBE || key.id == Key.ENTER) {
            // Resolve on release: tap = the key's action, long-press = 123/ABC→accents, globe→settings,
            // enter→hide the keyboard.
            clearTapLattice()                             // layer switch / globe / enter break the word
            pendingReleasePointer = pointerId
            pendingReleaseId = key.id
            longPressPointerId = pointerId
            longPressCandidate = key
            removeCallbacks(altLongPress)
            postDelayed(altLongPress, longPressMs())
            return false
        }
        val retractable = onKey(key.id)
        if (!letterTap) tapPrevWasLetter = false          // a non-letter (space, punctuation) ends the word
        if (key.id == Key.BACKSPACE) {           // first delete fired on down; now arm the repeat
            clearTapLattice()                    // edits break lattice↔text alignment; drop it
            backspacePointerId = pointerId
            backspaceDownMs = System.currentTimeMillis()
            removeCallbacks(backspaceRepeat)
            postDelayed(backspaceRepeat, BACKSPACE_INITIAL_DELAY_MS)
        }
        if (key.id == Key.SPACE) {               // track for double-tap (handled in onKey) + 2D swipe
            spacePointerId = pointerId
            spaceDownX = x
            spaceDownY = y
            spaceSwiping = false
        }
        // Long-press: a letter → its symbol; the period → voice (when usable for this language); the
        // comma → emoji panel.
        if (hintFor(key.id) != null ||
            (key.id == Key.PERIOD && voiceAvailable) ||
            key.id == Key.COMMA
        ) {
            longPressPointerId = pointerId
            longPressCandidate = key
            removeCallbacks(altLongPress)
            postDelayed(altLongPress, longPressMs())
        }
        return retractable
    }

    private fun stopBackspaceRepeat() {
        backspacePointerId = -1
        removeCallbacks(backspaceRepeat)
    }

    // ------------------------------------------------------------------ alternates popup

    /** The tiny corner label (number/symbol) for a letter key, or null. */
    private fun hintFor(id: String): String? {
        if (layer != Layer.LETTERS || id.length != 1) return null
        return lang.hints[id[0].lowercaseChar()]
    }

    // Accents / vowel points for the active language, reached by holding the 123/ABC key.
    private fun accentSet(): List<String> = lang.accents

    private fun showAltPopup() {
        val k = longPressCandidate ?: return
        // Long-press the period → start voice dictation (retract the '.' committed on down).
        if (k.id == Key.PERIOD) {
            endAltLongPress()
            if (voiceAvailable) { tap(); listener?.onBackspace(); listener?.onMic() }
            return
        }
        // Long-press the comma key → open the emoji panel (retract the ',' typed on down).
        if (k.id == Key.COMMA) {
            endAltLongPress()
            if (firstPointerId == longPressPointerId) firstKeyRetractable = false
            tap(); listener?.onBackspace(); layer = Layer.EMOJI; rebuild()
            return
        }
        // Long-press the globe → open the on-keyboard quick-settings panel.
        if (k.id == Key.GLOBE) {
            pendingReleasePointer = -1   // consumed as settings, so release won't switch language
            endAltLongPress()
            tap(); layer = Layer.SETTINGS; rebuild()
            return
        }
        // Long-press enter → hide the keyboard (a plain tap inserts a newline / submits).
        if (k.id == Key.ENTER) {
            pendingReleasePointer = -1   // consumed as dismiss, so release won't also send Enter
            endAltLongPress()
            tap(); listener?.onDismiss()
            return
        }
        // Long-press the 123/ABC toggle → the accents / vowel-points picker.
        if (k.id == Key.SYMBOLS || k.id == Key.LETTERS) {
            pendingReleasePointer = -1   // consumed as the picker, so release won't switch layer
            popupReplacesBase = false
            popupOptions = accentSet()
            popupActive = true; popupKey = k; popupIndex = -1
            tap(); invalidate()
            return
        }
        // Long-press a letter → preview its corner number/symbol in a popup. It's selected by default
        // (release commits, replacing the letter typed on down); drag away from the key to cancel.
        val hint = hintFor(k.id) ?: run { endAltLongPress(); return }
        popupReplacesBase = true
        popupOptions = listOf(hint)
        popupActive = true; popupKey = k; popupIndex = 0
        tap(); invalidate()
    }

    private fun popupCellW(n: Int): Float =
        minOf(dpf(POPUP_CELL_DP), (width - padSide * 2) / n.coerceAtLeast(1))

    /** Update which popup cell is picked. The multi-option picker (accents / vowel points) requires the
     *  finger to slide up into the card; the single-symbol letter preview is selected by default and
     *  only cancels (index -1) when the finger is dragged clearly away from the key. */
    private fun updatePopupSelection(x: Float, y: Float) {
        val opts = popupOptions
        if (opts.isEmpty()) return
        val k = popupKey ?: return
        if (popupReplacesBase) {
            val w = k.vis.width()
            val inZone = y <= k.vis.bottom + dpf(12) &&
                x >= k.vis.centerX() - w && x <= k.vis.centerX() + w
            popupIndex = if (inZone) 0 else -1
            return
        }
        if (y > k.vis.top) { popupIndex = -1; return }
        val cellW = popupCellW(opts.size)
        val totalW = cellW * opts.size
        val left = popupLeft(totalW)
        popupIndex = (((x - left) / cellW).toInt()).coerceIn(0, opts.size - 1)
    }

    /** Commit the picked option and reset. A letter-preview symbol replaces the base letter committed on
     *  down; a Hebrew vowel point simply attaches to the preceding letter. */
    private fun commitPopupAndReset() {
        if (popupActive && popupIndex in popupOptions.indices) {
            if (popupReplacesBase) {
                if (firstPointerId == longPressPointerId) firstKeyRetractable = false
                listener?.onBackspace()              // remove the letter typed on down
            }
            listener?.onText(popupOptions[popupIndex])
            tap()
        }
        endAltLongPress()
    }

    private fun endAltLongPress() {
        removeCallbacks(altLongPress)
        longPressPointerId = -1
        longPressCandidate = null
        popupReplacesBase = false
        if (popupActive) {
            popupActive = false; popupKey = null; popupOptions = emptyList(); invalidate()
        }
    }

    /** Left edge of the popup card, clamped on-screen. */
    private fun popupLeft(totalW: Float): Float {
        val cx = popupKey?.vis?.centerX() ?: (width / 2f)
        return (cx - totalW / 2f).coerceIn(padSide, (width - padSide - totalW).coerceAtLeast(padSide))
    }

    /** Tiled rects always contain the point; the nearest-center fallback only covers off-surface taps. */
    private fun findKey(x: Float, y: Float): PlacedKey? {
        if (placed.isEmpty()) return null
        val cx = x.coerceIn(0f, width - 1f)
        val cy = y.coerceIn(0f, height - 1f)
        placed.firstOrNull { it.hit.contains(cx, cy) }?.let { return it }
        return placed.minByOrNull { val dx = it.cx - cx; val dy = it.cy - cy; dx * dx + dy * dy }
    }

    // ------------------------------------------------------------------ typing accuracy
    //
    // Per-tap key selection = spatial likelihood (Gaussian on distance) × language likelihood
    // (a character trigram model, frequency-weighted English). For an ambiguous tap near a key
    // boundary this lets context break the tie (after "th", a tap between e/r/w resolves to "e").
    // A confident tap inside a key's core is returned directly, so deliberate taps are never
    // overridden. Distances are normalised by key width / row pitch so both axes are comparable.

    /** Holds ln P(c3 | c1,c2) over an alphabet of [syms] symbols (letters + a word boundary). */
    private class CharModel(private val logp: FloatArray, val syms: Int) {
        fun lp(c1: Int, c2: Int, c3: Int): Float = logp[(c1 * syms + c2) * syms + c3]
    }

    /**
     * Per-language alphabet for the accuracy model. Letters occupy a contiguous Unicode block from
     * [base] (EN: a-z, 26; HE: א..ת incl. finals, 27); index [boundary] == [size] is the word-boundary
     * symbol, so the trigram table has [syms] symbols per axis — matching the .bin built by the tools.
     */
    private class LangSpec(val base: Char, val size: Int, val modelRes: Int) {
        val syms: Int get() = size + 1
        val boundary: Int get() = size
        operator fun contains(ch: Char): Boolean {
            val l = ch.lowercaseChar()      // no-op for Hebrew; folds EN uppercase from the field
            return l >= base && l.code < base.code + size
        }
        fun symIndex(ch: Char): Int {
            val i = ch.lowercaseChar().code - base.code
            return if (i in 0 until size) i else boundary
        }
    }

    // The typing-accuracy trigram model exists only for English & Hebrew; other languages have no spec
    // and fall back to plain nearest-key hit-testing.
    private val specEn = LangSpec('a', 26, R.raw.charmodel)
    private val specHe = LangSpec('א', 27, R.raw.hebcharmodel)   // U+05D0 = א
    private val spec: LangSpec? get() = when (lang.code) { "en" -> specEn; "he" -> specHe; else -> null }

    // Cache per language. containsKey (not getOrPut) so a failed load caches null instead of retrying
    // a resource read on every tap.
    private val charModels = HashMap<String, CharModel?>()
    private fun charModel(): CharModel? {
        val sp = spec ?: return null
        if (!charModels.containsKey(lang.code)) charModels[lang.code] = loadCharModel(sp)
        return charModels[lang.code]
    }

    // Tunables. biasY shifts the effective touch point up because fingers tend to land low; if a
    // particular zone reads wrong, nudge these. LAMBDA scales how much context can sway a tap.
    private val biasX = 0f
    private val biasY = -dpf(6)
    private val coreFrac = 0.5f       // within this fraction of a key (normalised) → no override
    private val sigmaFrac = 0.72f     // Gaussian width in key units
    private val radiusFrac = 1.5f     // only score candidates within this many key units
    private val lambda = 1.0f

    private fun isLetter(id: String): Boolean {
        val sp = spec ?: return false
        return id.length == 1 && id[0] in sp
    }

    /** A letter key in any language — used to collect [letterKeys] and the spatial tap lattice, regardless
     *  of whether the language has a character model. */
    private fun isLetterKey(id: String): Boolean = id.length == 1 && id[0].isLetter()

    /** Nearby letter keys → spatial log-likelihood (-d²/σ²) for an ambiguous tap; null for a confident tap
     *  or when there are no letter keys. Pure geometry (no character model), so every language feeds it. */
    private fun tapCandidates(x: Float, y: Float, raw: PlacedKey): HashMap<Char, Float>? {
        if (letterKeys.isEmpty()) return null
        val cx = x + biasX
        val cy = y + biasY
        val kw = raw.vis.width().coerceAtLeast(1f)
        var nd2 = Float.MAX_VALUE
        for (k in letterKeys) { val d2 = norm2(k, cx, cy, kw); if (d2 < nd2) nd2 = d2 }
        if (sqrt(nd2) < coreFrac) return null              // confident tap — no ambiguity to record
        val sigma2 = 2f * sigmaFrac * sigmaFrac
        val radius2 = radiusFrac * radiusFrac
        val cands = HashMap<Char, Float>(8)
        for (k in letterKeys) { val d2 = norm2(k, cx, cy, kw); if (d2 <= radius2) cands[k.id[0]] = -d2 / sigma2 }
        return cands
    }

    private fun clearTapLattice() {
        tapLattice.clear(); tapChars.setLength(0); tapPxs.clear(); tapPys.clear(); tapPrevWasLetter = false
    }

    /**
     * A spatial substitution-cost function for [word], built from the tap lattice of the word currently
     * being typed — or null if the lattice doesn't line up with [word] (then autocorrect just uses the key
     * grid). Returns the cheapest cost (0) for the key the finger was most likely on, 1 for another nearby
     * key, and null (→ grid cost) for a key the finger wasn't near. Self-checking: any mismatch in length
     * or letters means we can't trust the alignment, so we fall back rather than risk a wrong steer.
     */
    fun spatialSubCost(word: String): ((Int, Char, Char) -> Int?)? {
        if (word.isEmpty() || tapChars.length != word.length) return null
        for (i in word.indices) if (tapChars[i].lowercaseChar() != word[i].lowercaseChar()) return null
        val lattice = ArrayList(tapLattice)
        return fn@{ pos, from, to ->
            val cand = lattice.getOrNull(pos) ?: return@fn null
            val toP = cand[to.lowercaseChar()] ?: return@fn null     // finger wasn't near 'to' → grid
            val f = from.lowercaseChar()
            var bestAlt = -Float.MAX_VALUE
            for ((ch, p) in cand) if (ch != f && p > bestAlt) bestAlt = p
            if (bestAlt == -Float.MAX_VALUE) null else if (toP >= bestAlt - 1e-3f) 0 else 1
        }
    }

    private fun resolveLetter(x: Float, y: Float, raw: PlacedKey): PlacedKey {
        if (letterKeys.isEmpty()) return raw
        val cx = x + biasX
        val cy = y + biasY
        val kw = raw.vis.width().coerceAtLeast(1f)
        // nearest letter key, in normalised (per-axis) distance
        var nearest = raw
        var nd2 = Float.MAX_VALUE
        for (k in letterKeys) {
            val d2 = norm2(k, cx, cy, kw)
            if (d2 < nd2) { nd2 = d2; nearest = k }
        }
        if (sqrt(nd2) < coreFrac) return nearest          // confident tap — leave it alone
        val model = charModel() ?: return nearest
        val sp = spec ?: return nearest
        val (c1, c2) = contextSymbols(sp)
        val sigma2 = 2f * sigmaFrac * sigmaFrac
        val radius2 = radiusFrac * radiusFrac
        var best = nearest
        var bestScore = -Float.MAX_VALUE
        for (k in letterKeys) {
            val d2 = norm2(k, cx, cy, kw)
            if (d2 > radius2) continue
            val score = -d2 / sigma2 + lambda * model.lp(c1, c2, sp.symIndex(k.id[0]))
            if (score > bestScore) { bestScore = score; best = k }
        }
        return best
    }

    private fun norm2(k: PlacedKey, cx: Float, cy: Float, kw: Float): Float {
        val dx = (k.cx - cx) / kw
        val dy = (k.cy - cy) / rowPitch
        return dx * dx + dy * dy
    }

    /** The two symbols before the cursor (letter → 0..size-1, anything else / absent → boundary). */
    private fun contextSymbols(sp: LangSpec): Pair<Int, Int> {
        val s = listener?.textBeforeCursor(2)?.toString().orEmpty()
        val c1 = if (s.length >= 2) sp.symIndex(s[s.length - 2]) else sp.boundary
        val c2 = if (s.isNotEmpty()) sp.symIndex(s[s.length - 1]) else sp.boundary
        return c1 to c2
    }

    private fun loadCharModel(s: LangSpec): CharModel? = try {
        val bytes = resources.openRawResource(s.modelRes).use { it.readBytes() }
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val arr = FloatArray(fb.remaining())
        fb.get(arr)
        CharModel(arr, s.syms)
    } catch (e: Exception) {
        null   // fall back to pure nearest-key (spatial only) if the asset is missing/corrupt
    }

    /** Applies a key. Returns true if it committed a single retractable character (text or space). */
    private fun onKey(id: String): Boolean {
        tap()
        when (id) {
            Key.SHIFT -> { onShift(); invalidate() }   // case/glyph only — no geometry change, so no re-layout
            Key.BACKSPACE -> listener?.onBackspace()
            Key.ENTER -> listener?.onEnter()
            Key.SYMBOLS -> { layer = Layer.SYMBOLS; rebuild() }
            Key.MORE -> { layer = Layer.MORE; rebuild() }
            Key.LETTERS -> { layer = Layer.LETTERS; rebuild() }
            Key.GLOBE -> toggleLang()
            Key.EMOJI -> { layer = Layer.EMOJI; rebuild() }
            Key.SET_HAPTIC -> {
                val next = (Prefs.hapticLevel(context) + 1) % 4
                Prefs.setHapticLevel(context, next); previewHaptic(next); invalidate()
            }
            Key.SET_AUTOCORRECT -> { Prefs.setAutocorrect(context, !Prefs.autocorrect(context)); invalidate() }
            Key.SET_GESTURE -> {
                Prefs.setGestureTyping(context, !Prefs.gestureTyping(context))
                gestureEnabled = Prefs.gestureTyping(context)   // take effect this session immediately
                invalidate()
            }
            Key.SET_AUTOCAP -> { Prefs.setAutoCap(context, !Prefs.autoCap(context)); invalidate() }
            Key.SET_LANGIND -> { Prefs.setLanguageIndicator(context, !Prefs.languageIndicator(context)); rebuild() }
            Key.SET_KBHEIGHT -> {
                Prefs.setKeyboardHeight(context, (Prefs.keyboardHeight(context) + 1) % 3); rebuild()
            }
            // Toggling the suggestion bar changes the keyboard's height (the strip), so re-measure.
            Key.SET_SUGGEST -> { Prefs.setSuggestions(context, !Prefs.suggestions(context)); rebuild() }
            Key.SET_DONE -> { layer = Layer.LETTERS; rebuild() }
            Key.SET_ALL -> listener?.onOpenSettings()
            Key.MIC -> listener?.onMic()
            Key.PERIOD -> { listener?.onText("."); return true }   // long-press → voice (handled in touch)
            Key.COMMA -> { listener?.onText(","); return true }
            Key.SPACE -> {
                val now = System.currentTimeMillis()
                if (Prefs.doubleSpacePeriod(context) && now - lastSpaceTapMs < DOUBLE_TAP_MS) {  // double-tap → ". "
                    lastSpaceTapMs = 0L
                    listener?.onDoubleSpace()
                    return false
                }
                lastSpaceTapMs = now
                listener?.onText(" ")
                return true
            }
            else -> {
                if (layer == Layer.EMOJI) {
                    Prefs.pushRecentEmoji(context, id)   // float it to the front next time
                    listener?.onText(id)
                    return false
                }
                listener?.onText(labelFor(id))
                return true
            }
        }
        return false
    }

    /** Globe key: cycle to the next enabled language (and snap back to the letters view if we were in
     *  emoji/symbols). The host is told so it can swap autocorrect engine + dictation. */
    private fun toggleLang() {
        val enabled = enabledLangs()
        val idx = enabled.indexOfFirst { it.code == lang.code }
        applyLang(enabled[(idx + 1) % enabled.size])
        listener?.onLanguageChange(lang.code)
        rebuild()
    }

    /** The enabled languages in registry order; always at least one (English). */
    private fun enabledLangs(): List<LangDef> {
        val codes = Prefs.enabledLanguages(context)
        val list = Languages.ALL.filter { it.code in codes }
        return list.ifEmpty { listOf(Languages.EN) }
    }

    /** Switch to [def], remembering it and resetting case state for caseless scripts. */
    private fun applyLang(def: LangDef) {
        lang = def
        Prefs.setActiveLanguage(context, def.code)
        if (!lang.hasCase) { shifted = false; capsLock = false }
        layer = Layer.LETTERS
    }

    /** Shift tap: toggles one-shot uppercase; a quick double-tap latches caps lock; tapping while
     *  locked clears it. */
    private fun onShift() {
        val now = System.currentTimeMillis()
        when {
            capsLock -> { capsLock = false; shifted = false }
            now - lastShiftTapMs < DOUBLE_TAP_MS -> { capsLock = true; shifted = true }
            else -> shifted = !shifted
        }
        lastShiftTapMs = now
    }

    /** Set the letters language from outside (e.g. an OS input-subtype switch). Unlike the globe key,
     *  this does NOT re-notify the host — the host is the one driving the change. */
    fun setLanguageCode(code: String) {
        if (lang.code == code) return
        clearTapLattice()
        applyLang(Languages.byCode(code))
        rebuild()
    }

    /** Reset to the default letters/uppercase view (called when a new field gains focus). */
    fun reset() {
        stopBackspaceRepeat()
        endAltLongPress()
        clearTapLattice()
        spacePointerId = -1; spaceSwiping = false; pendingReleasePointer = -1
        layer = Layer.LETTERS; shifted = lang.hasCase; capsLock = false; listening = false; rebuild()
    }

    /** Open the numeric layer — the host calls this when a number / phone / datetime field gains focus,
     *  so digits are there without tapping 123. The ABC toggle still switches to letters. */
    fun showNumbers() {
        if (layer != Layer.NUMBER) { layer = Layer.NUMBER; rebuild() }
    }

    override fun onDetachedFromWindow() {
        stopBackspaceRepeat()
        endAltLongPress()
        releaseSound()
        super.onDetachedFromWindow()
    }

    /** Enter the voice-dictation listening surface. */
    fun startListeningUi() { listening = true; listeningStatus = context.getString(R.string.listening); rebuild() }

    /** Update the listening status / live partial transcription. */
    fun setListeningStatus(text: String) {
        if (!listening) return
        listeningStatus = text
        invalidate()
    }

    /** Leave the listening surface, back to keys. */
    fun stopListeningUi() {
        if (!listening) return
        listening = false
        rebuild()
    }

    /**
     * Sentence-case auto-shift, driven by the host IME from the field's caps mode: uppercase at a
     * sentence start, lowercase after the first letter. One-shot — a manual SHIFT tap holds only
     * until the next letter, after which the IME recomputes this.
     */
    fun setShifted(value: Boolean) {
        if (!lang.hasCase) return       // caseless scripts (Hebrew) — no auto-shift
        if (capsLock) return            // caps lock overrides sentence-case auto-shift
        if (shifted != value) {
            shifted = value
            // Case is resolved live at draw (labelFor / the shift icon), so this only changes what's
            // painted, not geometry — repaint without a layout pass (mirrors the manual Shift key).
            if (layer == Layer.LETTERS) invalidate()
        }
    }

    // iPhone-style haptics: short, crisp, low-amplitude vibrations rather than the heavier system
    // key-click. A key press is a touch firmer than a caret tick. Respects the device haptic toggle.
    private val vibrator: android.os.Vibrator? by lazy {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(android.os.VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    }
    private val hapticsEnabled: Boolean by lazy {
        android.provider.Settings.System.getInt(
            context.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1,
        ) != 0
    }

    private fun buzz(ms: Long, amplitude: Int) {
        if (!hapticsEnabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(android.os.VibrationEffect.createOneShot(ms, amplitude)) }
    }

    // Optional key-press click: a soft, short bundled "tock" (res/raw/key_click) in the spirit of the
    // iOS keyboard, played quietly through SoundPool — quieter and more consistent than the loud system
    // FX_KEYPRESS effect, which varies by device. Created lazily on first use, released on detach.
    private var soundPool: android.media.SoundPool? = null
    private var keyClickId = 0
    private fun playKeySound() {
        if (!Prefs.soundEnabled(context)) return
        var pool = soundPool
        if (pool == null) {
            pool = android.media.SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
            soundPool = pool
            keyClickId = runCatching { pool.load(context, R.raw.key_click, 1) }.getOrDefault(0)
        }
        if (keyClickId != 0) runCatching { pool.play(keyClickId, KEY_SOUND_VOLUME, KEY_SOUND_VOLUME, 0, 0, 1f) }
    }

    private fun releaseSound() {
        soundPool?.release()
        soundPool = null
        keyClickId = 0
    }

    /** Hold time before a long-press fires — shorter when the user picks a more sensitive setting. */
    private fun longPressMs(): Long = when (Prefs.longPressDelay(context)) {
        Prefs.LEVEL_HIGH -> 200L     // fast
        Prefs.LEVEL_LOW -> 460L      // slow
        else -> 320L                 // normal
    }

    /** Multiplier on space-swipe travel-per-step: <1 = more sensitive (caret moves with less travel). */
    private fun swipeScale(): Float = when (Prefs.swipeSensitivity(context)) {
        Prefs.LEVEL_HIGH -> 0.65f
        Prefs.LEVEL_LOW -> 1.55f
        else -> 1f
    }

    private fun tap() {                                           // key-press feedback (haptic + sound)
        playKeySound()
        when (Prefs.hapticLevel(context)) {                      // strength from Setup
            Prefs.HAPTIC_LIGHT -> buzz(30, 200)
            Prefs.HAPTIC_MEDIUM -> buzz(38, 228)
            Prefs.HAPTIC_STRONG -> buzz(45, 255)
            else -> Unit                                          // off
        }
    }

    private fun cursorTick() = when (Prefs.hapticLevel(context)) { // lighter tick for caret movement
        Prefs.HAPTIC_LIGHT -> buzz(10, 80)
        Prefs.HAPTIC_MEDIUM -> buzz(16, 140)
        Prefs.HAPTIC_STRONG -> buzz(24, 200)
        else -> Unit
    }

    private val DOUBLE_TAP_MS = 300L
    private val BACKSPACE_INITIAL_DELAY_MS = 400L   // pause before key-repeat kicks in
    private val BACKSPACE_CHAR_INTERVAL_MS = 95L    // per-character repeat rate
    private val BACKSPACE_WORD_AFTER_MS = 1500L     // after this long holding, delete whole words
    private val BACKSPACE_WORD_INTERVAL_MS = 190L   // per-word repeat rate
    private val SUGGESTION_STRIP_DP = 26            // height of the suggestion bar when enabled
    private val KEY_SOUND_VOLUME = 0.5f             // soft key-click playback level (0..1)
    private val POPUP_CELL_DP = 38                  // alternates popup: cell width
    private val POPUP_CELL_H_DP = 38                // …and height — kept short so it fits above the key
                                                    //   even at the compact keyboard size
    private val SPACE_SWIPE_START = dpf(16)         // travel before space-swipe engages
    private val SPACE_SWIPE_STEP = dpf(11)          // travel per one-character caret move
    private val SPACE_SWIPE_VSTEP = dpf(22)         // vertical travel per line move

    private fun dpf(v: Int): Float = v * resources.displayMetrics.density
    private fun spf(v: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v.toFloat(), resources.displayMetrics)
}
