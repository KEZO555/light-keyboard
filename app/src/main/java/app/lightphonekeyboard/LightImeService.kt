package app.lightphonekeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import kotlin.math.abs

/**
 * The system keyboard. Once enabled + selected as default, it appears in every text field on the
 * phone. Keystrokes from [LightKeyboardView] are applied to the focused field via InputConnection.
 *
 * Optional word-level autocorrect (toggle in [SetupActivity]) runs on top: when a word is finished
 * (space / punctuation / enter) we look it up in the active language's frequency dictionary (via
 * [Dictionaries], plus your learned words) and swap in the most likely fix. Case is preserved, and the
 * first backspace after a correction reverts it.
 */
class LightImeService : InputMethodService(), LightKeyboardView.Listener {

    private var keyboard: LightKeyboardView? = null

    private val dictation by lazy { VoiceDictation(this) }          // offline Vosk (per-language model)
    private val sysDictation by lazy { SystemDictation(this) }      // platform recognizer (Hebrew fallback)

    // Current letters language code, mirrored from the keyboard (globe key). Autocorrect uses the
    // matching dictionary (English/Hebrew bundled today; other languages download in a later phase).
    private var langCode = "en"
    private val hebrew: Boolean get() = langCode == "he"

    // Revert-on-backspace: after a correction the text before the cursor ends with [undoFrom];
    // the next backspace restores [undoTo] instead of deleting a character. [undoWord] is the word the
    // user originally typed — learned if they revert (a strong "I meant this" signal).
    private var undoFrom: String? = null
    private var undoTo: String? = null
    private var undoWord: String? = null
    // If the pending revert is undoing an auto-finalized Hebrew ending, this is the medial-form word to
    // remember as "keep medial" so it's never auto-finalized again (e.g. קליפ).
    private var undoKeepMedial: String? = null

    private var micActive = false

    override fun onCreate() {
        super.onCreate()
        warmVoice()   // warm the current language's model if voice is on (and that model is downloaded)
    }

    /** Voice can be used right now for [code]: the feature is on, and either its offline Vosk model is
     *  downloaded (most languages) or a system recognizer is present (Hebrew, which has no Vosk model). */
    private fun voiceUsableFor(code: String): Boolean {
        if (!Prefs.voiceEnabled(this)) return false
        val def = Languages.byCode(code)
        return if (def.voiceUrl != null) VoiceModel.isInstalled(this, code) else sysDictation.available
    }

    /** Tell the keyboard whether to show the mic for the current language. */
    private fun updateVoiceAvailability() { keyboard?.voiceAvailable = voiceUsableFor(langCode) }

    /** Pre-load the Vosk model for the current language so the first mic tap is instant. */
    private fun warmVoice() {
        val def = Languages.byCode(langCode)
        if (Prefs.voiceEnabled(this) && def.voiceUrl != null && VoiceModel.isInstalled(this, langCode)) {
            dictation.prepare(langCode)
        }
    }

    override fun onCreateInputView(): View {
        val kb = LightKeyboardView(this)
        kb.listener = this
        keyboard = kb
        return kb
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboard?.reset()
        keyboard?.gestureEnabled = Prefs.gestureTyping(this)
        // Number / phone / datetime fields open straight on the numeric layer (the ABC key still
        // switches to letters) — like most keyboards, so you don't have to tap 123 every time.
        if (isNumericField(info)) keyboard?.showNumbers()
        micActive = false
        dictation.destroy()
        sysDictation.destroy()
        clearUndo()
        // The keyboard keeps its language across fields; sync ours and warm the dictionaries.
        langCode = keyboard?.langCode ?: "en"
        if (dictNeeded()) prepareDict(langCode)
        warmVoice()
        updateVoiceAvailability()
        updateShift()
        updateSuggestions()
    }

    // Hints/labels (lowercased) that mark a text field as really wanting digits.
    private val NUMERIC_HINT = Regex("phone|mobile|number|postal|postcode|\\bfax\\b|\\btel\\b|\\bpin\\b|\\bzip\\b")

    /** True for fields that expect digits — number, phone, or date/time — so the keyboard can open on
     *  its numeric layer. Some apps (e.g. WhatsApp's contact editor) declare a phone field as plain
     *  *text*; for those we fall back to the field's hint/label (e.g. "Phone number"). */
    private fun isNumericField(info: EditorInfo?): Boolean {
        info ?: return false
        when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> return true
            InputType.TYPE_CLASS_TEXT -> {
                val hint = buildString {
                    info.label?.let { append(it).append(' ') }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.hintText?.let { append(it) }
                }.lowercase()
                return hint.isNotBlank() && NUMERIC_HINT.containsMatchIn(hint)
            }
        }
        return false
    }

    /** Warm the autocorrect dictionary for [code] (bundled languages always; downloadable ones only
     *  once the user has downloaded them — [WordDictionary.prepare] no-ops otherwise). */
    private fun prepareDict(code: String) {
        val dict = Dictionaries.get(code) ?: return
        dict.prepare(this)
        val def = Languages.byCode(code)
        // First use of a downloadable language with no dictionary yet → fetch it automatically so autocorrect
        // just works (no trip to the Languages screen). Otherwise, refresh it if the bundled data improved.
        DictModel.ensureInstalled(this, def) { dict.reload(this) }
        DictModel.refreshIfStale(this, def) { dict.reload(this) }
    }

    override fun onDestroy() {
        dictation.destroy()
        sysDictation.destroy()
        super.onDestroy()
    }

    /** Globe key changed the letters language. Swap autocorrect engine and warm its dictionary. */
    override fun onLanguageChange(code: String) {
        langCode = code
        clearUndo()
        if (dictNeeded()) prepareDict(code)
        warmVoice()
        updateVoiceAvailability()
        updateSuggestions()
    }

    /** Keep the keyboard's language in sync when the user switches our subtype via the system globe. */
    @Suppress("DEPRECATION")
    override fun onCurrentInputMethodSubtypeChanged(subtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(subtype)
        val tag = subtype?.languageTag?.takeIf { it.isNotEmpty() } ?: subtype?.locale.orEmpty()
        val he = tag.startsWith("he") || tag.startsWith("iw")   // "iw" is the legacy Hebrew code
        langCode = if (he) "he" else "en"
        keyboard?.setLanguageCode(langCode)
        clearUndo()
        if (dictNeeded()) prepareDict(langCode)
        warmVoice()
        updateVoiceAvailability()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        updateShift() // after each keystroke/cursor move, recompute uppercase-vs-lowercase
        updateSuggestions()
    }

    /** Sentence-case: uppercase at a sentence start, lowercase after — from the field's caps mode. */
    private fun updateShift() {
        if (!Prefs.autoCap(this)) return
        val ic = currentInputConnection ?: return
        val type = currentInputEditorInfo?.inputType ?: return
        keyboard?.setShifted(ic.getCursorCapsMode(type) != 0)
    }

    /** Long-press the globe → open the keyboard's settings screen. */
    override fun onOpenSettings() {
        startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun textBeforeCursor(n: Int): CharSequence? =
        currentInputConnection?.getTextBeforeCursor(n, 0)

    // ------------------------------------------------------------------ key events

    override fun onText(s: String) {
        val ic = currentInputConnection ?: return
        if (s.length == 1 && isWordChar(s[0])) {
            clearUndo()
            // A letter continues the word, so a preceding Hebrew *final* form is no longer word-final.
            fixMedialBeforeTyping(s[0])
            ic.commitText(s, 1)
            return
        }
        // Only whitespace / sentence punctuation finish a word for autocorrect. Digits and other
        // symbols just commit, so alphanumeric tokens (ab2, mp3, covid19) are left alone.
        if (!(s.length == 1 && isCorrectTrigger(s[0]))) {
            clearUndo()
            ic.commitText(s, 1)
            return
        }
        // A word terminator: snap a trailing medial Hebrew letter to its final form, then autocorrect.
        val finalizedMedial = fixFinalForWordEnd()
        val original = trailingWord()
        // English "always" fixes (standalone "i" → "I", apostrophe-less contractions): applied before — and
        // independent of — the typo/learn flow, so they never get suppressed or relearned.
        val forced = if (autocorrectOn()) forcedFix(original) else null
        if (forced != null) {
            val cased = applyCase(original, forced)
            ic.beginBatchEdit()
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(cased, 1); ic.commitText(s, 1)
            ic.endBatchEdit()
            undoFrom = cased + s; undoTo = original + s; undoWord = null; undoKeepMedial = null
            return
        }
        // Merge-words: a fragment that completes the previous word into a real word ("to gether" → "together").
        if (autocorrectOn() && finalizedMedial == null) {
            val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
            val prev = TextOps.precedingWord(before)
            val merged = dict()?.mergeWord(prev, original)
            if (merged != null && before.lowercase().endsWith("${prev.lowercase()} ${original.lowercase()}")) {
                val cased = applyCase(prev, merged)
                ic.beginBatchEdit()
                ic.deleteSurroundingText(prev.length + 1 + original.length, 0)
                ic.commitText(cased, 1); ic.commitText(s, 1)
                ic.endBatchEdit()
                undoFrom = cased + s; undoTo = "$prev $original$s"; undoWord = null; undoKeepMedial = null
                return
            }
        }
        val fix = if (autocorrectOn()) autocorrectFix(original) else null
        // Use-count any *unfamiliar* word — whether or not it has a correction. We leave it (or offer the
        // fix) the first few times in case it's a typo, and only trust it — learning it — once it's been
        // typed enough. Crucially this counts no-fix words too: a one-off typo with no correction (e.g.
        // מאציו, two edits from מאמין) is committed but never saved to your words on the first keystroke,
        // so it can't silently become "known" and then a correction target. Known words are left as-is.
        val known = dict()?.takeIf { it.ready }?.isWord(original.lowercase()) == true
        val usedEnough = !known && registerUnknownUse(original)
        val correct = fix != null && !fix.equals(original, ignoreCase = true) && !usedEnough
        if (correct) {
            val cased = applyCase(original, fix!!)
            ic.beginBatchEdit()
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(cased, 1)
            ic.commitText(s, 1)
            ic.endBatchEdit()
            undoFrom = cased + s     // arm revert: text now ends with the fix + terminator
            undoTo = original + s
            undoWord = original      // if the user reverts, learn what they actually typed
            undoKeepMedial = null
        } else {
            ic.commitText(s, 1)
            // No immediate learning here: a known word needs none, and an unfamiliar one is learned by
            // registerUnknownUse above only after enough repeats (or instantly via an undo of a fix).
            if (finalizedMedial != null) {
                // Arm a revert: backspace restores the medial ending AND remembers to keep it medial.
                val finWord = finalizedMedial.dropLast(1) + medialToFinal[finalizedMedial.last()]
                undoFrom = finWord + s
                undoTo = finalizedMedial + s
                undoWord = null
                undoKeepMedial = finalizedMedial
            } else {
                clearUndo()
            }
        }
        recordContext()   // learn the (previous word → this word) pair for next-word prediction
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        val from = undoFrom
        val to = undoTo
        if (from != null && to != null) {
            val before = ic.getTextBeforeCursor(from.length, 0)?.toString()
            val word = undoWord
            val keepMedial = undoKeepMedial
            clearUndo()
            if (before == from) {   // only revert if the corrected text is still sitting there
                ic.beginBatchEdit()
                ic.deleteSurroundingText(from.length, 0)
                ic.commitText(to, 1)
                ic.endBatchEdit()
                if (word != null) learnTyped(word)              // user rejected the fix — learn their word
                if (keepMedial != null) Prefs.addKeepMedial(this, keepMedial)  // …or the medial ending
                return
            }
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        // Delete a whole grapheme cluster, not one UTF-16 unit — otherwise an emoji (surrogate pair /
        // variation selector) gets half-deleted and the leftover renders as a white box.
        val before = ic.getTextBeforeCursor(GRAPHEME_LOOKBACK, 0)
        ic.deleteSurroundingText(lastGraphemeLength(before), 0)
    }

    override fun onBackspaceWord() {
        val ic = currentInputConnection ?: return
        clearUndo()
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        if (before.isEmpty()) { ic.deleteSurroundingText(1, 0); return }
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--    // trailing whitespace
        while (i > 0 && !before[i - 1].isWhitespace()) i--   // then the word
        val count = before.length - i
        ic.deleteSurroundingText(if (count > 0) count else 1, 0)
    }

    /** Length (in chars) of the last grapheme cluster of [before]; 1 if empty/unknown. */
    private fun lastGraphemeLength(before: CharSequence?): Int = TextOps.lastGraphemeLength(before)

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        fixFinalForWordEnd()
        // Fix the last word before firing the action / newline.
        val original = trailingWord()
        val fix = if (autocorrectOn()) autocorrectFix(original) else null
        // Same use-counting as onText: an unfamiliar word is learned only after enough repeats, never on
        // the first keystroke — so a one-off typo with no fix isn't silently saved as a word.
        val known = dict()?.takeIf { it.ready }?.isWord(original.lowercase()) == true
        val usedEnough = !known && registerUnknownUse(original)
        val correct = fix != null && !fix.equals(original, ignoreCase = true) && !usedEnough
        if (correct) {
            val cased = applyCase(original, fix!!)
            ic.beginBatchEdit()
            ic.deleteSurroundingText(original.length, 0)
            ic.commitText(cased, 1)
            ic.endBatchEdit()
        }
        recordContext()   // record the word pair before the line breaks the context
        clearUndo()
        // Enter inserts a real newline by default. Only fields that ask to submit/advance on Enter
        // (Go / Search / Send / Next) get their action — and not if the field opts out with
        // IME_FLAG_NO_ENTER_ACTION. A plain "Done" action on a text field used to just dismiss the
        // keyboard, so we treat that as a newline (Enter never closes the keyboard). But a *numeric* field
        // is single-line — a newline is meaningless there — so its Done/OK action should submit like the
        // rest, so Enter acts as the "OK" the field shows. (Long-press Enter still hides the keyboard.)
        val opts = currentInputEditorInfo?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val submits = action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_SEARCH ||
            action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_NEXT ||
            (action == EditorInfo.IME_ACTION_DONE && isNumericField(currentInputEditorInfo))
        if (submits && (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    override fun onDismiss() {
        requestHideSelf(0) // swipe-down closes the keyboard, the proper Android way
    }

    /** Double-tap space → turn a trailing "<letter> " into "<letter>. " (only after a word char). */
    override fun onDoubleSpace() {
        val ic = currentInputConnection ?: return
        clearUndo()
        val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)   // remove the lone trailing space
            ic.commitText(". ", 1)
            ic.endBatchEdit()
        } else {
            ic.commitText(" ", 1)            // nothing to punctuate — just a space
        }
    }

    /** Space-bar swipe → nudge the caret one character per step (negative = left). */
    override fun onCursorMove(steps: Int) {
        val ic = currentInputConnection ?: return
        clearUndo()
        val key = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(steps)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        }
    }

    /** Space-bar swipe up/down → move the caret one line. */
    override fun onCursorVertical(down: Boolean) {
        val ic = currentInputConnection ?: return
        clearUndo()
        val key = if (down) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    // Hebrew letters with distinct word-final forms (defined + tested in TextOps). Typed text uses the
    // medial form; we snap to the final form at a word end, and back to medial when the word continues.
    private val medialToFinal = TextOps.hebrewMedialToFinal
    private val finalToMedial = TextOps.hebrewFinalToMedial

    // A geresh / apostrophe marks a transliterated foreign word (ג׳ = j, צ׳ = ch, ז׳ = zh, ת׳, ד׳ …).
    // Those keep their medial letter at the end — e.g. ג׳יפ, צ׳יפ, ג׳ירף — because the final form would
    // change the sound (final ף is "f", but "jeep" ends in a "p"). So we don't auto-finalize them.
    private fun isForeignWordEnd(before: String): Boolean {
        var i = before.length
        while (i > 0 && (before[i - 1] in 'א'..'ת' || before[i - 1] in "׳'״’")) i--
        return before.substring(i).any { it == '׳' || it == '\'' || it == '״' || it == '’' }
    }

    /** The trailing Hebrew word (letters + geresh/apostrophe) of [before], in whatever form it's in. */
    private fun trailingHebrewWord(before: String): String {
        var i = before.length
        while (i > 0 && (before[i - 1] in 'א'..'ת' || before[i - 1] in "׳'״’")) i--
        return before.substring(i)
    }

    /** At a word end: if the last letter is a medial form with a final variant, snap it to final, and
     *  return the medial-form word that was snapped (for revert/learning). Skips geresh-marked foreign
     *  words and any word the user has chosen to keep medial; returns null when nothing was changed. */
    private fun fixFinalForWordEnd(): String? {
        if (!hebrew) return null
        val ic = currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(24, 0)?.toString().orEmpty()
        val fin = before.lastOrNull()?.let { medialToFinal[it] } ?: return null
        if (isForeignWordEnd(before)) return null
        val word = trailingHebrewWord(before)                 // medial form, e.g. "קליפ"
        if (word in Prefs.keepMedial(this)) return null        // user corrected this one before
        ic.beginBatchEdit(); ic.deleteSurroundingText(1, 0); ic.commitText(fin.toString(), 1); ic.endBatchEdit()
        return word
    }

    /** Before appending [next]: if a final form sits before the cursor, the word continues, so convert
     *  it back to its medial form — unless [next] repeats that same final letter (e.g. חלוםםם), which
     *  means the user deliberately wants the final form, so we leave it untouched. */
    private fun fixMedialBeforeTyping(next: Char) {
        if (!hebrew) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val prev = before.firstOrNull() ?: return
        if (next == prev) return                       // repeated same final letter → keep it final
        val med = finalToMedial[prev] ?: return
        ic.beginBatchEdit(); ic.deleteSurroundingText(1, 0); ic.commitText(med.toString(), 1); ic.endBatchEdit()
    }

    // Never take over the whole screen with the big white "extract" editor (it appears in landscape by
    // default). Our keyboard is built for the compact LightOS layout, so keep it docked at the bottom.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onMic() {
        if (!Prefs.voiceEnabled(this)) return   // mic key is hidden when voice is off, but guard anyway
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // An IME can't pop the permission dialog itself; the shim activity does it.
            startActivity(Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val kb = keyboard ?: return
        micActive = true
        kb.startListeningUi()
        // Languages with an offline Vosk model dictate on-device; Hebrew (no Vosk model) falls back to
        // the platform recognizer.
        if (Languages.byCode(langCode).voiceUrl != null) startDictationWhenReady(kb, langCode, attempts = 0)
        else startHebrewDictation(kb)
    }

    /** Hebrew dictation through the platform recognizer (no model download; needs the recognizer present). */
    private fun startHebrewDictation(kb: LightKeyboardView) {
        if (!micActive) return
        if (!sysDictation.available) {
            micActive = false
            kb.setListeningStatus(getString(R.string.voice_unavailable))
            kb.postDelayed({ kb.stopListeningUi() }, 1200)
            return
        }
        sysDictation.listen(
            SystemDictation.HEBREW,
            onPartial = { kb.setListeningStatus(it) },
            onSegment = { text ->
                clearUndo()
                currentInputConnection?.commitText(spacedDictation(text), 1)
            },
            onError = { msg ->
                micActive = false
                kb.setListeningStatus(msg)
                kb.postDelayed({ kb.stopListeningUi() }, 1200)
            },
        )
    }

    /** Wait (briefly) for [code]'s model to finish loading on first use, then start listening. */
    private fun startDictationWhenReady(kb: LightKeyboardView, code: String, attempts: Int) {
        if (!micActive || langCode != code) return   // bail if the user switched language meanwhile
        if (dictation.ready(code)) {
            dictation.listen(
                code,
                onPartial = { kb.setListeningStatus(it) },
                // Each finished segment commits to the field; dictation keeps going across pauses.
                onSegment = { text ->
                    clearUndo()
                    currentInputConnection?.commitText(spacedDictation(text), 1)
                },
                onError = { msg ->
                    micActive = false
                    kb.setListeningStatus(msg)
                    kb.postDelayed({ kb.stopListeningUi() }, 1200)
                },
            )
            return
        }
        dictation.prepare(code)
        if (attempts > 40) {   // ~12s; first-run model load should be done well before this
            micActive = false
            kb.setListeningStatus(getString(R.string.voice_unavailable))
            kb.postDelayed({ kb.stopListeningUi() }, 1200)
            return
        }
        kb.setListeningStatus(getString(R.string.voice_preparing))
        kb.postDelayed({ startDictationWhenReady(kb, code, attempts + 1) }, 300)
    }

    /** Tap on the listening surface = "I'm done": flush the trailing words, then close it. */
    override fun onMicCancel() {
        if (!micActive) return
        micActive = false
        dictation.stop()
        sysDictation.stop()
        keyboard?.stopListeningUi()
    }

    /** Insert a leading space if the cursor isn't already at a boundary, so dictated text doesn't fuse. */
    private fun spacedDictation(text: String): String {
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        return if (before.isNotEmpty() && !before.last().isWhitespace()) " $text" else text
    }

    // Tell any of our own overlays (e.g. light-assistant's edge seam) to get out of the way while the
    // keyboard is on screen, so they don't sit over the top-left keys.
    override fun onWindowShown() { super.onWindowShown(); broadcastImeVisible(true) }
    override fun onWindowHidden() { super.onWindowHidden(); broadcastImeVisible(false) }
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        currentInputConnection?.finishComposingText()
        if (micActive) { micActive = false; dictation.destroy(); sysDictation.destroy(); keyboard?.stopListeningUi() }
        barWords = emptyList(); barLiteralIndex = -1
        keyboard?.setSuggestions(emptyList())
        broadcastImeVisible(false)
    }

    private fun broadcastImeVisible(visible: Boolean) {
        runCatching { sendBroadcast(Intent(ACTION_IME_VISIBILITY).putExtra(EXTRA_VISIBLE, visible)) }
    }

    // ------------------------------------------------------------------ autocorrect

    private fun autocorrectOn(): Boolean = Prefs.autocorrect(this)

    /** A dictionary is needed if either autocorrect or the suggestion bar is on. */
    private fun dictNeeded(): Boolean = Prefs.autocorrect(this) || Prefs.suggestions(this)

    // The word each suggestion-bar slot inserts, and which slot is the user's literal word (tap = keep
    // it as typed). Rebuilt by [updateSuggestions]; read by [onSuggestionPicked].
    private var barWords: List<String> = emptyList()
    private var barLiteralIndex = -1
    // When a bar slot offers the dictionary fix for a word you previously taught the keyboard, tapping it
    // both replaces the text and forgets the learned typo. [barForgetWord] is that word; [barForgetIndex]
    // the slot. Both reset every rebuild (set only when such a fix is offered).
    private var barForgetWord: String? = null
    private var barForgetIndex = -1

    /**
     * Recompute the suggestion bar for the word being typed and push it. When autocorrect would change
     * the word, the bar leads with the **correction** (highlighted — what space will apply) next to the
     * user's **literal word** (quoted — tap to keep it), then a completion if room. Otherwise it's just
     * the prefix completions. All shown in the case they'll be inserted in.
     */
    private fun updateSuggestions() {
        val kb = keyboard ?: return
        barForgetWord = null; barForgetIndex = -1   // reset; set only when an unlearn fix is offered below
        if (!Prefs.suggestions(this)) { barWords = emptyList(); barLiteralIndex = -1; kb.setSuggestions(emptyList()); return }
        val dict = dict()
        dict?.prepare(this)   // warm on demand (idempotent) so enabling the bar mid-session works too
        if (dict == null) { barWords = emptyList(); barLiteralIndex = -1; kb.setSuggestions(emptyList()); return }
        // One text fetch per keystroke: both the word being typed and the previous word come from it.
        val before = currentInputConnection?.getTextBeforeCursor(64, 0) ?: ""
        val word = TextOps.trailingWord(before)
        val prevWord = TextOps.precedingWord(before)
        // Just after a space (no word yet): predict the likely next word from what usually follows it.
        if (word.isEmpty()) {
            // Two words of context when available (trigram, backing off to bigram), else one.
            val ctx = TextOps.trailingWords(before, 2)
            val next = when {
                ctx.size >= 2 -> dict.nextWords(ctx[0], ctx[1], 3)
                ctx.size == 1 -> dict.nextWords(ctx[0], 3)
                else -> emptyList()
            }
            barWords = next; barLiteralIndex = -1
            kb.setSuggestions(next)
            return
        }
        if (word.length < 2) { barWords = emptyList(); barLiteralIndex = -1; kb.setSuggestions(emptyList()); return }

        // The correction space would auto-apply (only when autocorrect is on and it actually differs). Falls
        // back to the run-on split so it previews in the bar too ("לארקובלתי" → "לא קיבלתי") — but only for
        // longer words, since the split search runs every keystroke and a real run-on is never short.
        val correction = if (autocorrectOn())
            (dict.correct(word, prevWord.ifEmpty { null }, keyboard?.spatialSubCost(word))
                ?: (if (word.length >= 5) dict.correctRunOn(word, prevWord.ifEmpty { null }) else null))
                ?.takeIf { !it.equals(word, ignoreCase = true) }
        else null
        val completions = dict.completions(word, 3, prevWord)
        val words = ArrayList<String>(3)
        var primary = -1
        var literal = -1
        if (correction != null) {
            words.add(applyCase(word, correction)); primary = 0   // highlighted: what space applies
            words.add(word); literal = 1                          // the user's own word, kept on tap
            for (c in completions) {                              // fill the third slot if there's room
                if (words.size >= 3) break
                val cased = applyCase(word, c)
                if (words.none { it.equals(cased, ignoreCase = true) }) words.add(cased)
            }
        } else {
            // No auto-correction. If this is a word you taught the keyboard (learned, not a dictionary
            // word) and it has a real-dictionary fix, offer it as a one-tap "replace and forget" so a
            // mistakenly-learned typo can be undone in context. Shown as a normal chip (space won't change
            // a learned word), ahead of the completions.
            val unlearn = if (autocorrectOn())
                dict.learnedTypoFix(word, prevWord.ifEmpty { null }, keyboard?.spatialSubCost(word)) else null
            if (unlearn != null && !unlearn.equals(word, ignoreCase = true)) {
                words.add(applyCase(word, unlearn)); barForgetWord = word; barForgetIndex = 0
            }
            for (c in completions) {
                if (words.size >= 3) break
                val cased = applyCase(word, c)
                if (words.none { it.equals(cased, ignoreCase = true) }) words.add(cased)
            }
        }
        barWords = words
        barLiteralIndex = literal
        kb.setSuggestions(words, primary, literal)
    }

    /** A suggestion-bar slot was tapped. The literal slot keeps the typed word (and learns it so it's no
     *  longer corrected); any other slot replaces the word with that completion/correction + a space. */
    override fun onSuggestionPicked(index: Int) {
        if (index !in barWords.indices) return
        if (index == barLiteralIndex) {
            learnTyped(trailingWord())   // "this is a real word" — stop correcting it, no text change
            updateSuggestions()          // refresh: the correction is gone now
            return
        }
        val ic = currentInputConnection ?: return
        clearUndo()
        val original = trailingWord()
        ic.beginBatchEdit()
        if (original.isNotEmpty()) ic.deleteSurroundingText(original.length, 0)
        ic.commitText("${barWords[index]} ", 1)
        ic.endBatchEdit()
        if (index == barForgetIndex && barForgetWord != null) {
            dict()?.forget(this, barForgetWord!!)   // this slot was "replace + forget the learned typo"
        } else {
            learnTyped(barWords[index])             // tapping a word counts as using it
        }
        // onUpdateSelection fires from the commit and refreshes the bar (now empty — the word is done).
    }

    /** A finished swipe gesture: decode it against the dictionary and commit the word (auto-spaced). */
    override fun onGesture(keys: List<GestureTyping.Key>, xs: FloatArray, ys: FloatArray, keyWidth: Float) {
        val ic = currentInputConnection ?: return
        val d = dict()?.takeIf { it.ready } ?: return
        val before = ic.getTextBeforeCursor(48, 0) ?: ""
        val prev = TextOps.precedingWord(before.toString())
        val word = d.gestureWord(keys, xs, ys, keyWidth, prev.ifEmpty { null }) ?: return
        clearUndo()
        val lead = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        ic.commitText("$lead$word ", 1)
        learnTyped(word)
        if (prev.isNotEmpty()) d.learnBigram(this, prev, word)
        updateSuggestions()
    }

    /**
     * The autocorrection for a finished [word], or null: the highest-frequency real word within a small
     * edit distance of what you typed (+ your learned words). A language whose dictionary isn't loaded
     * — e.g. a downloadable one you haven't downloaded — returns null (no correction).
     */
    /** English-only fixes that always apply (not the typo/learn flow): standalone "i" → "I", and
     *  apostrophe-less contractions ("dont" → "don't"). Null otherwise. */
    private fun forcedFix(word: String): String? {
        if (langCode != "en") return null
        if (word == "i") return "I"
        return dict()?.contractionOf(word)
    }

    private fun autocorrectFix(word: String): String? {
        if (!autocorrectOn()) return null
        val d = dict() ?: return null
        if (word.length < d.minCorrectLen) return null
        // The word just before this one (the cursor still sits right after `word`) gives correction its
        // context, so the fix can be biased toward what usually follows that previous word.
        val prev = TextOps.precedingWord(currentInputConnection?.getTextBeforeCursor(48, 0) ?: "")
        val p = prev.ifEmpty { null }
        // Single-word fix first (auto-apply only confident ones); then a run-on split ("לארקובלתי" → "לא
        // קיבלתי"); then the tap-typing hybrid — decode the whole tap path, catching multi-tap fat-finger
        // errors the edit-distance corrector misses. The commit path handles a space in the fix like any other.
        d.correct(word, p, keyboard?.spatialSubCost(word), confidentOnly = true)?.let { return it }
        d.correctRunOn(word, p)?.let { return it }
        keyboard?.tapPath(word)?.let { tp -> d.tapCorrect(word, tp.keys, tp.xs, tp.ys, tp.keyWidth, p)?.let { return it } }
        // Last: even a *valid* word can be the wrong one for this context ("תודה כבה" → "תודה רבה").
        prev.takeIf { it.isNotEmpty() }?.let { d.contextCorrect(word, it)?.let { c -> return c } }
        return null
    }

    // The active language's dictionary, cached so the per-keystroke paths don't re-scan Languages.ALL +
    // the instance map each time. Re-fetched only when the language actually changes.
    private var dictCode: String? = null
    private var dictRef: WordDictionary? = null
    private fun dict(): WordDictionary? {
        if (dictCode != langCode) { dictCode = langCode; dictRef = Dictionaries.get(langCode) }
        return dictRef
    }

    /** Remember a word the user typed, in the active language's dictionary. Gated on the dictionary
     *  being loaded so we don't clobber the saved learned-words file before it's read, nor hoard words
     *  for a downloadable language the user hasn't set up. */
    private fun learnTyped(word: String) {
        if (word.length < 2) return
        dict()?.takeIf { it.ready }?.learn(this, word)
    }

    /** A word was just finished: record the (previous word → this word) pair to grow the next-word
     *  model. Reads the text now before the cursor, so it works the same whether the word was corrected,
     *  reverted or typed literally. */
    private fun recordContext() {
        val dict = dict()?.takeIf { it.ready } ?: return
        val before = currentInputConnection?.getTextBeforeCursor(64, 0) ?: return
        var end = before.length
        while (end > 0 && !TextOps.isWordChar(before[end - 1])) end--   // drop the trailing terminator(s)
        if (end == 0) return
        val core = before.subSequence(0, end)
        val w = TextOps.trailingWords(core, 3)
        if (w.size >= 2) dict.learnBigram(this, w[w.size - 2], w.last())
        if (w.size >= 3) dict.learnTrigram(this, w[w.size - 3], w[w.size - 2], w.last())
    }

    // How many times an unfamiliar word has been typed this session (cleared if it grows large). Lets us
    // tell a one-off typo (correct it) from a word you actually use (learn it on the repeat).
    private val unknownUses = HashMap<String, Int>()

    /** Count one use of an unfamiliar [word]; returns true once it has been used enough to be trusted
     *  as a real word — at which point it's learned (in EN or HE) so it's never autocorrected again. */
    private fun registerUnknownUse(word: String): Boolean {
        if (word.length < 2) return false
        if (unknownUses.size > 500) unknownUses.clear()
        val key = "$langCode:$word"
        val n = (unknownUses[key] ?: 0) + 1
        unknownUses[key] = n
        if (n >= LEARN_AFTER_USES) {
            unknownUses.remove(key)
            learnTyped(word)
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ helpers

    // Word-boundary, casing and grapheme rules live in the Android-free TextOps (unit-tested); the
    // service just supplies the text around the cursor from the InputConnection.
    private fun isWordChar(c: Char): Boolean = TextOps.isWordChar(c)

    private fun isCorrectTrigger(c: Char): Boolean = TextOps.isCorrectTrigger(c)

    /** The run of word characters immediately before the cursor. */
    private fun trailingWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(48, 0) ?: return ""
        return TextOps.trailingWord(before)
    }

    private fun applyCase(original: String, fix: String): String = TextOps.applyCase(original, fix)

    private fun clearUndo() {
        undoFrom = null
        undoTo = null
        undoWord = null
        undoKeepMedial = null
    }

    companion object {
        /** Broadcast so our overlays can dodge the keyboard. Implicit; caught by a runtime receiver. */
        const val ACTION_IME_VISIBILITY = "app.lightphonekeyboard.IME_VISIBILITY"
        const val EXTRA_VISIBLE = "visible"
        /** Window of text to inspect when deleting the last grapheme cluster (covers long emoji). */
        private const val GRAPHEME_LOOKBACK = 16
        /** Use an unfamiliar word this many times → it's added to your vocabulary (and stops correcting).
         *  Kept fairly high so a repeated *typo* isn't memorised as a real word after just a couple of uses. */
        private const val LEARN_AFTER_USES = 4
    }
}
