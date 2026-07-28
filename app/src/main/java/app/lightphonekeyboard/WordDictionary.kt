package app.lightphonekeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.File

/**
 * The keyboard's offline autocorrect dictionary — one instance per language, configured by
 * [Dictionaries]. The word list comes from one of two places:
 *
 *   • **bundled** in the APK as an asset ([assetName]) — English only, always available; or
 *   • **downloaded** on demand into internal storage ([DictModel]) — every other language (Hebrew,
 *     Spanish, French, German, Italian, Portuguese, Arabic, Mandarin pinyin), fetched when enabled.
 *
 * Either way it's a `word<space>count` list, loaded into a frequency map and fed to the shared
 * keyboard-aware corrector ([WordPredict]): [correct] returns the most likely real word within one edit
 * of what was typed (preferring transpositions and adjacent-key slips), or — for longer words with no
 * single-edit fix — a conservative distance-2 fallback, or null.
 * The keyboard also [learn]s the words you type — weighted so your own vocabulary competes with common
 * words and persisted per language — so they stop being "corrected" and can win as suggestions.
 */
class WordDictionary(
    private val code: String,
    private val alphabet: String,            // letters used to generate edit candidates
    adjacencyRows: List<String>,             // layout letter rows, for keyboard-aware edit costs
    private val assetName: String? = null,   // bundled source; null → downloaded file in filesDir
    private val bigramAsset: String? = null, // bundled pre-trained next-word model (English); null otherwise
    private val maxLearnLen: Int = 20,       // longest word we'll learn (Hebrew caps lower)
    freqSizeHint: Int = 32_000,
) {
    private val tag = "Dict-$code"
    private val learnedFile = "${code}_learned.txt"
    private val bigramFile = "${code}_bigrams.txt"
    private val adj = WordPredict.adjacency(adjacencyRows)
    private val letterSet = alphabet.toHashSet()

    private val main = Handler(Looper.getMainLooper())
    private val freq = HashMap<String, Long>(freqSizeHint)
    private val learned = HashMap<String, Long>()
    // Curated exact typo→word overrides for the few hard cases edit-distance can't reach (multi-edit slips,
    // or where it confidently picks the wrong real word): "wich"→"which", "מאציו"→"מאמין". Bundled per
    // language in assets/confusions_<code>.txt; applied only to a word that isn't itself real.
    private val confusions = HashMap<String, String>()
    // Next-word model: prev word -> (next word -> times seen), learned from your own typing. Powers the
    // suggestion bar after a space, and biases completions of a partly-typed word toward what usually
    // follows the previous word.
    private val bigrams = HashMap<String, HashMap<String, Long>>()
    // Learned trigrams: "prev2 prev1" -> next -> times seen. Sharpens prediction when two words of context
    // are known; backs off to bigrams otherwise. Learned-only (no pre-trained trigram corpus is available).
    private val trigrams = HashMap<String, HashMap<String, Long>>()
    private val trigramFile = "${code}_trigrams.txt"
    // Pre-trained next-word model (prev -> next -> corpus count): gives prediction & context out of the box,
    // before you've typed anything. Bundled (English) or downloaded ([PRE_BIGRAM_FILE]); read-only, never
    // persisted, kept separate from the learned [bigrams] so saving stays small.
    private val pretrained = HashMap<String, HashMap<String, Long>>()
    private val preBigramFile = "${code}_bigrams_pre.txt"
    private val memo = HashMap<String, String?>()   // word -> fix (null = checked, no correction)
    private var appContext: Context? = null

    @Volatile
    var ready = false
        private set
    private var loading = false

    /** Whether the word list is present: always for a bundled language, file-dependent for a download. */
    fun isInstalled(context: Context): Boolean =
        assetName != null || DictModel.isInstalled(context, code)

    /** Load the word list into memory (background). No-op once loaded / in flight, or — for a
     *  downloadable language — if it hasn't been downloaded yet. */
    fun prepare(context: Context) {
        if (ready || loading) return
        val app = context.applicationContext
        if (assetName == null && !DictModel.dictFile(app, code).exists()) return
        loading = true
        appContext = app
        Thread {
            try {
                openReader(app).use { r ->
                    r.forEachLine { line ->
                        val sp = line.indexOf(' ')
                        if (sp <= 0) return@forEachLine
                        val c = line.substring(sp + 1).toLongOrNull() ?: return@forEachLine
                        freq[line.substring(0, sp)] = c
                    }
                }
                loadConfusions(app)
                loadLearned(app)
                loadBigrams(app)
                loadTrigrams(app)
                if (pretrained.isEmpty()) loadPretrainedBigrams(app)   // load once; survives reload()
                main.post { ready = true; loading = false; Log.i(tag, "loaded ${freq.size}+${learned.size}") }
            } catch (e: Throwable) {
                main.post { loading = false }
                Log.e(tag, "load failed", e)
            }
        }.start()
    }

    /** Drop the in-memory word list and re-read it from disk — used after the downloaded dictionary file
     *  has been refreshed to a newer version. Predictions briefly return nothing while it reloads; learned
     *  words and the next-word model are re-read from their own files too. */
    fun reload(context: Context) {
        if (loading) return
        ready = false
        freq.clear(); learned.clear(); bigrams.clear(); trigrams.clear(); memo.clear()
        sorted = null; learnedSorted = null
        prepare(context)
    }

    private fun openReader(context: Context): BufferedReader =
        if (assetName != null) context.assets.open(assetName).bufferedReader(Charsets.UTF_8)
        else DictModel.dictFile(context, code).bufferedReader(Charsets.UTF_8)

    private fun loadLearned(context: Context) {
        val f = File(context.filesDir, learnedFile)
        if (!f.exists()) return
        f.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val sp = line.indexOf(' ')
                if (sp <= 0) return@forEach
                val c = line.substring(sp + 1).toLongOrNull() ?: return@forEach
                learned[line.substring(0, sp)] = c
            }
        }
        pruneRedundantLearned()
        learnedSorted = null   // rebuild the prefix index on first use after loading
    }

    /** Drop learned entries the base dictionary already recognises — an exact word, or (Hebrew) a glued
     *  proclitic+stem form. Older over-eager learning saved every word you typed, so "my words" filled with
     *  ordinary dictionary words; these are pure noise (the word stays known regardless). Words you genuinely
     *  taught (not in the dictionary) are kept. Runs once at load, after [freq] is populated. */
    private fun pruneRedundantLearned() {
        if (freq.isEmpty() || learned.isEmpty()) return
        val iter = learned.keys.iterator()
        var removed = 0
        while (iter.hasNext()) {
            val w = iter.next()
            val redundant = freq.containsKey(w) ||
                (hebrew && TextOps.hebrewProcliticSplits(w).any { (_, stem) -> freq.containsKey(stem) })
            if (redundant || (hebrew && isConfidentTypo(w))) { iter.remove(); removed++ }
        }
        if (removed > 0) { memo.clear(); scheduleSave() }   // persist the cleaned list
    }

    /** True if the learned, non-dictionary word [w] is a confident typo of a *common* word — one
     *  transposition or adjacent-key slip from a dictionary word of substantial frequency (e.g. אנט→אני).
     *  Older learning saved such slips, and — being "learned" — they were then never corrected; dropping
     *  them lets the word autocorrect again. Conservative: only a cheap slip toward a genuinely common word,
     *  so a rarer taught word (or one no cheap edit from a common word, like מאציו) stays. A word you truly
     *  want is re-learned by typing it again. (Hebrew only; English's far larger counts would over-match.) */
    private fun isConfidentTypo(w: String): Boolean {
        if (freq.containsKey(w) || w.length < minCorrectLen) return false
        val co = IntArray(1)
        val fix = WordPredict.bestCorrection(
            w, alphabet, adj,
            isKnown = { x -> x != w && isWord(x) },
            freqOf = { freq[it] ?: 0L },
            isTarget = { x -> x != w && freq.containsKey(x) },
            cheapIndel = cheapIndel, costOut = co,
        ) ?: return false
        return co[0] <= WordPredict.SHORT_WORD_MAX_COST && (freq[fix] ?: 0L) >= COMMON_WORD_FREQ
    }

    /** Load the curated typo→word overrides bundled at assets/confusions_<code>.txt (absent for most
     *  languages, which is fine — the map just stays empty). One "typo correction" per line; the
     *  correction may be two words (a split, e.g. "alot" → "a lot"). */
    private fun loadConfusions(context: Context) {
        runCatching {
            context.assets.open("confusions_$code.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val sp = line.indexOf(' ')
                    if (sp > 0) confusions[line.substring(0, sp)] = line.substring(sp + 1).trim()
                }
            }
        }
    }

    private fun loadBigrams(context: Context) {
        val f = File(context.filesDir, bigramFile)
        if (!f.exists()) return
        readBigramLines(f.bufferedReader(Charsets.UTF_8), bigrams)
    }

    private fun loadTrigrams(context: Context) {
        val f = File(context.filesDir, trigramFile)
        if (!f.exists()) return
        f.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->                                  // "prev2 prev1 next count"
                val a = line.indexOf(' '); if (a <= 0) return@forEach
                val b = line.indexOf(' ', a + 1); if (b <= a + 1) return@forEach
                val c = line.indexOf(' ', b + 1); if (c <= b + 1) return@forEach
                val cnt = line.substring(c + 1).toLongOrNull() ?: return@forEach
                trigrams.getOrPut(line.substring(0, b)) { HashMap() }[line.substring(b + 1, c)] = cnt
            }
        }
    }

    /** Load the pre-trained model into [pretrained]: a bundled asset (English) or a downloaded file. */
    private fun loadPretrainedBigrams(context: Context) {
        runCatching {
            when {
                bigramAsset != null -> readBigramLines(context.assets.open(bigramAsset).bufferedReader(Charsets.UTF_8), pretrained)
                else -> File(context.filesDir, preBigramFile).takeIf { it.exists() }
                    ?.let { readBigramLines(it.bufferedReader(Charsets.UTF_8), pretrained) }
            }
        }.onFailure { Log.e(tag, "load pretrained bigrams failed", it) }
    }

    /** Parse "prev next count" lines into [into]. */
    private fun readBigramLines(reader: java.io.BufferedReader, into: HashMap<String, HashMap<String, Long>>) {
        reader.useLines { lines ->
            lines.forEach { line ->
                val a = line.indexOf(' '); if (a <= 0) return@forEach
                val b = line.indexOf(' ', a + 1); if (b <= a + 1) return@forEach
                val c = line.substring(b + 1).toLongOrNull() ?: return@forEach
                into.getOrPut(line.substring(0, a)) { HashMap() }[line.substring(a + 1, b)] = c
            }
        }
    }

    /** Combined next-word counts after [prev]: pre-trained model + what you've typed. Null if neither. */
    private fun nextAfter(prev: String): Map<String, Long>? {
        val a = bigrams[prev]; val b = pretrained[prev]
        return when {
            b == null -> a
            a == null -> b
            else -> HashMap<String, Long>(a.size + b.size).also {
                it.putAll(b); for ((k, v) in a) it[k] = (it[k] ?: 0L) + v
            }
        }
    }

    /** Count of the pair [prev]→[next] across both models. */
    private fun pairCount(prev: String, next: String): Long =
        (bigrams[prev]?.get(next) ?: 0L) + (pretrained[prev]?.get(next) ?: 0L)

    private val hebrew = code == "he"
    private val english = code == "en"
    // Hebrew matres lectionis: ו/י are written optionally (ktiv male/haser), so inserting or dropping one
    // is treated as a cheap edit — e.g. היתה ↔ הייתה, אתך ↔ איתך — not a full typo.
    private val cheapIndel: Set<Char> = if (hebrew) setOf('ו', 'י') else emptySet()
    // Shortest word we'll autocorrect. English 2-letter tokens (of/or/on/in/it/is/…) are a dense, ambiguous
    // cloud, so we leave them at 3; Hebrew 2-letter words (מה/זה/כן/לא/…) are common and all in the
    // dictionary, so a 2-letter non-word like "צה" can be safely fixed to "מה" without touching real ones.
    val minCorrectLen = if (hebrew) 2 else 3

    /** Is [w] a real word? Directly in the dictionary/learned, or — for Hebrew — a known stem with one or
     *  more glued-on proclitics (ו/ה/ש/ב/כ/ל/מ), e.g. "ושלום" = ו + שלום. */
    fun isWord(w: String): Boolean {
        if (freq.containsKey(w) || learned.containsKey(w)) return true
        if (hebrew) for ((_, stem) in TextOps.hebrewProcliticSplits(w)) {
            if (freq.containsKey(stem) || learned.containsKey(stem)) return true
        }
        return false
    }

    /** A *base-dictionary* entry — the only thing we may autocorrect *to*. Deliberately excludes both
     *  Hebrew proclitic+stem reconstructions (so corrections never invent a glued surface form) and your
     *  *learned* words: a learned word is still left alone (see [isWord]), but must never be a correction
     *  target — otherwise a typo that slipped into "my words" (with its large learned weight) could hijack
     *  corrections, pulling real words toward it. Corrections only ever land on a real listed word. */
    private fun isDictWord(w: String): Boolean = freq.containsKey(w)

    /** Raw count of a known word/stem (no proclitic handling). */
    private fun rawFreq(w: String): Long = freq[w] ?: learned[w]?.let { it * LEARN_WEIGHT } ?: 0L

    /** Ranking frequency: dictionary/learned count; for an unknown Hebrew surface form, its stem's count
     *  (discounted) so a proclitic+word reconstruction still ranks sensibly behind exact matches. */
    private fun effectiveFreq(w: String): Long {
        val direct = freq[w] ?: learned[w]?.let { it * LEARN_WEIGHT }
        if (direct != null) return direct
        if (hebrew) {
            var best = 0L
            for ((_, stem) in TextOps.hebrewProcliticSplits(w)) { val f = rawFreq(stem); if (f > best) best = f }
            if (best > 0) return best / 4
        }
        return 0L
    }

    /**
     * Best correction for [word], or null if it's already known / too short (< 3) / nothing confident.
     * Lowercased for lookup; the caller reapplies the original case. (Hebrew is caseless, so lowercasing
     * is a no-op there.)
     */
    /** Best word for a swipe-typing path, biased by the previous word; null if nothing. */
    fun gestureWord(keys: List<GestureTyping.Key>, xs: FloatArray, ys: FloatArray, keyWidth: Float, prevWord: String?): String? {
        if (!ready) return null
        val ctx = prevWord?.lowercase()
        val contextOf: (String) -> Long = if (ctx == null) NO_CONTEXT else { w -> pairCount(ctx, w) }
        return GestureTyping.decode(xs, ys, keys, sortedWords(), { effectiveFreq(it) }, keyWidth, contextOf, 1).firstOrNull()
    }

    /**
     * Tap-typing hybrid: decode the tap path of a typed non-word as if it were a swipe, and return a real
     * word only if it fits the finger path about as well as what was typed (so a deliberate, confident word
     * is never overridden). Catches multi-tap fat-finger errors the edit-distance corrector misses.
     */
    fun tapCorrect(typed: String, keys: List<GestureTyping.Key>, xs: FloatArray, ys: FloatArray, keyWidth: Float, prevWord: String?): String? {
        if (!ready) return null
        val w = typed.lowercase()
        if (isWord(w)) return null
        val ctx = prevWord?.lowercase()
        val contextOf: (String) -> Long = if (ctx == null) NO_CONTEXT else { x -> pairCount(ctx, x) }
        val best = GestureTyping.decode(xs, ys, keys, sortedWords(), { effectiveFreq(it) }, keyWidth, contextOf, 1)
            .firstOrNull() ?: return null
        if (best == w || !isDictWord(best)) return null
        val cb = GestureTyping.costOf(best, keys, xs, ys, keyWidth) ?: return null
        val ct = GestureTyping.costOf(w, keys, xs, ys, keyWidth) ?: return null
        return if (cb <= ct + TAP_HYBRID_MARGIN) best else null   // fits the taps about as well → trust the real word
    }

    /**
     * Context-correct a *valid* word: if [word] doesn't follow [prevWord] in the next-word model but an
     * adjacent-key/transposition neighbour does (strongly), switch to it — "תודה כבה" → "תודה רבה".
     * Conservative: only when the typed word has no context support and the neighbour's is real.
     */
    fun contextCorrect(word: String, prevWord: String): String? {
        if (!ready || prevWord.isEmpty() || word.length < 2) return null
        val p = prevWord.lowercase(); val w = word.lowercase()
        if (pairCount(p, w) > 0) return null    // the typed word already fits the context → leave it
        val (cand, ctx) = WordPredict.bestContextNeighbor(w, adj, ::isDictWord) { pairCount(p, it) } ?: return null
        return if (ctx >= CONTEXT_CORRECT_MIN) cand else null
    }

    /**
     * If [word] is one you taught the keyboard (a *learned* word that isn't in the base dictionary) and the
     * corrector would otherwise fix it to a real dictionary word, return that word — so the bar can offer a
     * one-tap "replace and forget" to undo a mistakenly-learned typo in context. Computes the fix as if
     * [word] were *not* learned (a learned word is otherwise treated as known and never corrected). Returns
     * null for genuine words, dictionary words, or when there's no real-dictionary fix within reach.
     */
    fun learnedTypoFix(word: String, prevWord: String? = null, subCost: ((Int, Char, Char) -> Int?)? = null): String? {
        if (!ready) return null
        val w = word.lowercase()
        if (!learned.containsKey(w) || freq.containsKey(w) || w.length < minCorrectLen) return null
        // Recognised without its learned entry (e.g. a Hebrew proclitic+stem form)? Then it isn't a stray typo.
        if (hebrew && TextOps.hebrewProcliticSplits(w).any { (_, stem) -> freq.containsKey(stem) }) return null
        val pw = prevWord?.lowercase()
        val hasCtx = pw != null && (bigrams[pw]?.isNotEmpty() == true || pretrained[pw]?.isNotEmpty() == true)
        val contextOf: (String) -> Long = if (!hasCtx) NO_CONTEXT else { c -> pairCount(pw!!, c) }
        // Treat w as unknown (isKnown/isTarget exclude it); correct only *to* a real base-dictionary word.
        return WordPredict.bestCorrection(
            w, alphabet, adj,
            isKnown = { x -> x != w && isWord(x) },
            freqOf = { effectiveFreq(it) }, sortedDict = sortedWords(), contextOf = contextOf,
            isTarget = { x -> x != w && freq.containsKey(x) },
            subCost = subCost ?: NO_SUBCOST, cheapIndel = cheapIndel,
        )
    }

    /** The real contraction for an apostrophe-less English form ("dont" → "don't"), or null. */
    fun contractionOf(word: String): String? = if (english) CONTRACTIONS[word.lowercase()] else null

    /** Merge [prev]+[current] into one word when [current] is only a fragment ("to gether" → "together"),
     *  or null. The merged word is lowercase; the caller reapplies case. */
    fun mergeWord(prev: String, current: String): String? {
        if (!ready || prev.isEmpty()) return null
        return WordPredict.mergeCorrection(prev.lowercase(), current.lowercase(), ::isWord, ::isDictWord)
    }

    fun correct(
        word: String,
        prevWord: String? = null,
        subCost: ((Int, Char, Char) -> Int?)? = null,
        confidentOnly: Boolean = false,   // the auto-apply path passes true: only commit a confident fix
    ): String? {
        if (!ready) return null
        val w = word.lowercase()
        // Apostrophe-less contractions → the real contraction (English). Checked before the length gate so
        // short ones like "im" are caught, and before correction so we don't mangle them into a near-word.
        if (english) CONTRACTIONS[w]?.let { return it }
        // Curated hard-case overrides (see [confusions]): an exact typo→word fix for slips edit-distance
        // can't reach. Only when the typed form isn't itself a real word, so a genuine word is never remapped.
        if (!isWord(w)) confusions[w]?.let { return it }
        if (word.length < minCorrectLen) return null
        // The plain result is memoized; a context- or touch-aware one depends on this typing instance, so
        // it's computed fresh (still only when a word finishes / the bar updates, not in a tight loop).
        val pw = prevWord?.lowercase()
        val hasCtx = pw != null && (bigrams[pw]?.isNotEmpty() == true || pretrained[pw]?.isNotEmpty() == true)
        val memoable = !hasCtx && subCost == null && !confidentOnly
        if (memoable && memo.containsKey(w)) return memo[w]
        val contextOf: (String) -> Long = if (!hasCtx) NO_CONTEXT else { cand -> pairCount(pw!!, cand) }
        // For a 2-letter word the neighbourhood is tiny and ambiguous, so only trust a *confident* slip — a
        // transposition or adjacent-key fix (e.g. צה→מה) — never an insert/delete or a far substitution.
        val costOut = if (word.length < 3) IntArray(1) else null
        // sortedWords() enables the conservative distance-2 fallback (longer words only) at no per-key cost.
        // isWord stays permissive (so a correctly-typed prefixed word is left alone), but we only ever
        // correct *to* a real listed word via isDictWord — never to an invented proclitic+stem form.
        var fix = WordPredict.bestCorrection(
            w, alphabet, adj, ::isWord, { effectiveFreq(it) }, sortedWords(), contextOf,
            isTarget = ::isDictWord, subCost = subCost ?: NO_SUBCOST, cheapIndel = cheapIndel, costOut = costOut,
        )
        if (fix != null && costOut != null && costOut[0] > WordPredict.SHORT_WORD_MAX_COST) fix = null
        if (memoable) { if (memo.size > 4000) memo.clear(); memo[w] = fix }
        return fix
    }

    /**
     * Run-on correction: when [word] isn't a real word and has no single-word fix, try splitting it into
     * two words (inserting a space) — "thisis" → "this is", "לארקובלתי" → "לא קיבלתי". Each half must be a
     * real word or a confident single edit from one. Returns "a b" or null. Tried by the caller only after
     * [correct] comes up empty.
     */
    fun correctRunOn(word: String, prevWord: String? = null): String? {
        if (!ready || word.length < 4 || word.length > 18) return null
        val w = word.lowercase()
        if (isWord(w)) return null                       // a real word (incl. proclitic) — never split
        val ctx = prevWord?.lowercase()
        return WordPredict.splitCorrection(
            w,
            isWord = ::isWord,
            // a part isn't a real word → its best *distance-1* fix to a listed word (no dist-2, stay tight)
            fixPart = { p ->
                WordPredict.bestCorrection(
                    p, alphabet, adj, ::isWord, { effectiveFreq(it) }, isTarget = ::isDictWord, cheapIndel = cheapIndel,
                )
            },
            freqOf = { effectiveFreq(it) },
            // a learned previous→a (first half) pairing, then a→b, gently favour seen sequences
            bigramOf = { a, b -> pairCount(a, b) + (ctx?.let { pairCount(it, a) } ?: 0L) },
        )
    }

    // Lexically-sorted view of the dictionary keys, built once on first use, for prefix completion: a
    // binary search to the prefix range then a short scan of just that range. freq is never mutated
    // after loading, so this stays valid; learned words (added later) are scanned separately below.
    @Volatile
    private var sorted: Array<String>? = null

    private fun sortedWords(): Array<String> =
        sorted ?: freq.keys.toTypedArray().also { it.sort(); sorted = it }

    // Sorted view of the learned-word keys, so prefix lookups in completions() are a binary search of the
    // matching run rather than a full scan of the (up to MAX_LEARNED) map on every keystroke. Rebuilt
    // lazily and invalidated (set to null) whenever the key set changes — a new word, forget, or clear.
    @Volatile
    private var learnedSorted: Array<String>? = null

    private fun learnedSortedArr(): Array<String> =
        learnedSorted ?: learned.keys.toTypedArray().also { it.sort(); learnedSorted = it }

    /**
     * Up to [limit] word completions of [prefix], most-frequent first (your learned words weighted in).
     * Empty until the dictionary is loaded, or for a prefix shorter than 2 (too many, unhelpful matches).
     * If [prevWord] is given, words that usually follow it (learned bigrams) are floated to the front.
     */
    fun completions(prefix: String, limit: Int = 3, prevWord: String? = null): List<String> {
        if (!ready) return emptyList()
        val p = prefix.lowercase()
        if (p.length < 2) return emptyList()
        // Learned words aren't in the sorted dictionary array, so pass the matching ones as extras
        // (weighted like effectiveFreq); the dictionary itself is ranked by raw frequency. Binary-search
        // the learned index to the prefix run instead of scanning the whole learned map each keystroke.
        var extra: HashMap<String, Long>? = null
        val ls = learnedSortedArr()
        var lo = 0; var hi = ls.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (ls[mid] < p) lo = mid + 1 else hi = mid }
        var i = lo
        while (i < ls.size && ls[i].startsWith(p)) {
            val w = ls[i]; i++
            if (w !in freq) (extra ?: HashMap<String, Long>().also { extra = it })[w] = (learned[w] ?: 0L) * LEARN_WEIGHT
        }
        // Hebrew: also complete the stem under any glued proclitics — typing "ושל" suggests "ושלום" — by
        // completing the stem in the dictionary and re-attaching the prefix. Added as discounted extras so
        // they rank alongside the ordinary completions.
        if (hebrew) for ((prefix, stem) in TextOps.hebrewProcliticSplits(p)) {
            for (sc in WordPredict.completions(sortedWords(), stem, limit, { freq[it] ?: 0L })) {
                val cand = prefix + sc
                if (cand == p || freq.containsKey(cand)) continue
                val f = (freq[sc] ?: 0L) / 4
                val m = extra ?: HashMap<String, Long>().also { extra = it }
                if ((m[cand] ?: -1L) < f) m[cand] = f
            }
        }
        val base = WordPredict.completions(sortedWords(), p, limit, { freq[it] ?: 0L }, extra ?: emptyMap())
        val ctxMap = prevWord?.lowercase()?.let { nextAfter(it) } ?: return base
        val ctx = WordPredict.topNext(ctxMap, limit, p).filter { it.length > p.length }
        if (ctx.isEmpty()) return base
        // Lead with the context predictions, then fill from the frequency-ranked completions.
        val out = LinkedHashSet<String>()
        for (w in ctx) { out.add(w); if (out.size >= limit) break }
        for (w in base) { if (out.size >= limit) break; out.add(w) }
        return out.toList()
    }

    /** Up to [limit] words that usually follow [prevWord] (learned next-word predictions), most-used first. */
    fun nextWords(prevWord: String, limit: Int = 3): List<String> {
        if (!ready) return emptyList()
        val m = nextAfter(prevWord.lowercase()) ?: return emptyList()
        return WordPredict.topNext(m, limit)
    }

    /** Next-word prediction from two words of context: the learned trigram if it has data, backfilled from
     *  (and otherwise backing off to) the bigram model. */
    fun nextWords(prev2: String, prev1: String, limit: Int = 3): List<String> {
        if (!ready) return emptyList()
        val tri = trigrams["${prev2.lowercase()} ${prev1.lowercase()}"]
        if (tri.isNullOrEmpty()) return nextWords(prev1, limit)
        val out = LinkedHashSet(WordPredict.topNext(tri, limit))
        if (out.size < limit) nextAfter(prev1.lowercase())?.let {
            for (w in WordPredict.topNext(it, limit)) { if (out.size >= limit) break; out.add(w) }
        }
        return out.toList()
    }

    /** Record that [next] followed the pair [prev2] [prev1], to grow the trigram model. */
    fun learnTrigram(context: Context, prev2: String, prev1: String, next: String) {
        if (!ready) return
        val p2 = prev2.lowercase(); val p1 = prev1.lowercase(); val n = next.lowercase()
        if (!validForLearn(p2) || !validForLearn(p1) || !validForLearn(n)) return
        appContext = context.applicationContext
        val key = "$p2 $p1"
        val m = trigrams[key]
        if (m == null) {
            if (trigrams.size >= MAX_BIGRAM_PREV) return
            trigrams[key] = HashMap<String, Long>().apply { put(n, 1L) }
        } else {
            m[n] = (m[n] ?: 0L) + 1L
        }
        scheduleTrigramSave()
    }

    /** Record that [next] was typed right after [prev] (a word pair), to grow the next-word model. */
    fun learnBigram(context: Context, prev: String, next: String) {
        if (!ready) return
        val p = prev.lowercase(); val n = next.lowercase()
        if (!validForLearn(p) || !validForLearn(n)) return
        appContext = context.applicationContext
        val m = bigrams[p]
        if (m == null) {
            if (bigrams.size >= MAX_BIGRAM_PREV) return   // keep the key set bounded; existing keys keep learning
            bigrams[p] = HashMap<String, Long>().apply { put(n, 1L) }
        } else {
            m[n] = (m[n] ?: 0L) + 1L
        }
        scheduleBigramSave()
    }

    private fun validForLearn(w: String): Boolean =
        w.isNotEmpty() && w.length <= maxLearnLen && w.all { it in letterSet }

    /** Remember a word the user typed (and kept). Becomes known and rankable; persisted (debounced).
     *  Only this language's own letters, length 2..[maxLearnLen]. */
    fun learn(context: Context, word: String) {
        val w = word.lowercase()
        if (w.length < 2 || w.length > maxLearnLen || w.any { it !in letterSet }) return
        appContext = context.applicationContext
        val isNew = !isWord(w)
        val newKey = w !in learned                    // a brand-new learned key → the prefix index is stale
        learned[w] = (learned[w] ?: 0L) + 1L
        if (isNew) memo.clear() else memo.remove(w)   // a newly-known word changes corrections
        if (newKey) learnedSorted = null
        scheduleSave()
    }

    /** Words you've taught the keyboard, most-used first (loaded from disk if not already in memory). */
    fun learnedWords(context: Context): List<String> {
        if (learned.isEmpty()) { appContext = context.applicationContext; loadLearned(context.applicationContext) }
        return learned.entries.sortedByDescending { it.value }.map { it.key }
    }

    /** Forget one learned word. */
    fun forget(context: Context, word: String) {
        if (learned.remove(word.lowercase()) != null) {
            memo.clear(); learnedSorted = null; appContext = context.applicationContext; scheduleSave()
        }
    }

    /** Forget every learned word — and the next-word model, which is learned from the same typing. */
    fun clearLearned(context: Context) {
        learned.clear(); memo.clear(); bigrams.clear(); learnedSorted = null
        main.removeCallbacks(saveRunnable)
        main.removeCallbacks(bigramSaveRunnable)
        runCatching { File(context.applicationContext.filesDir, learnedFile).delete() }
        runCatching { File(context.applicationContext.filesDir, bigramFile).delete() }
    }

    private val saveRunnable = Runnable { writeLearned() }
    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 4000)   // coalesce bursts of typing into one write
    }

    private fun writeLearned() {
        val ctx = appContext ?: return
        val snapshot = ArrayList(learned.entries)
        Thread {
            try {
                val top = snapshot.sortedByDescending { it.value }.take(MAX_LEARNED)
                val sb = StringBuilder(top.size * 12)
                for (e in top) sb.append(e.key).append(' ').append(e.value).append('\n')
                File(ctx.filesDir, learnedFile).writeText(sb.toString(), Charsets.UTF_8)
            } catch (e: Throwable) {
                Log.e(tag, "save learned failed", e)
            }
        }.start()
    }

    private val bigramSaveRunnable = Runnable { writeBigrams() }
    private fun scheduleBigramSave() {
        main.removeCallbacks(bigramSaveRunnable)
        main.postDelayed(bigramSaveRunnable, 5000)   // coalesce bursts of typing into one write
    }

    private val trigramSaveRunnable = Runnable { writeTrigrams() }
    private fun scheduleTrigramSave() {
        main.removeCallbacks(trigramSaveRunnable)
        main.postDelayed(trigramSaveRunnable, 5000)
    }

    private fun writeTrigrams() {
        val ctx = appContext ?: return
        val snapshot = ArrayList<Triple<String, String, Long>>()
        for ((k, m) in trigrams) for ((n, c) in m) snapshot.add(Triple(k, n, c))   // k = "prev2 prev1"
        Thread {
            try {
                val top = snapshot.sortedByDescending { it.third }.take(MAX_BIGRAM_LINES)
                val sb = StringBuilder(top.size * 20)
                for (t in top) sb.append(t.first).append(' ').append(t.second).append(' ').append(t.third).append('\n')
                File(ctx.filesDir, trigramFile).writeText(sb.toString(), Charsets.UTF_8)
            } catch (e: Throwable) {
                Log.e(tag, "save trigrams failed", e)
            }
        }.start()
    }

    private fun writeBigrams() {
        val ctx = appContext ?: return
        val snapshot = ArrayList<Triple<String, String, Long>>()
        for ((p, m) in bigrams) for ((n, c) in m) snapshot.add(Triple(p, n, c))
        Thread {
            try {
                val top = snapshot.sortedByDescending { it.third }.take(MAX_BIGRAM_LINES)
                val sb = StringBuilder(top.size * 16)
                for (t in top) sb.append(t.first).append(' ').append(t.second).append(' ').append(t.third).append('\n')
                File(ctx.filesDir, bigramFile).writeText(sb.toString(), Charsets.UTF_8)
            } catch (e: Throwable) {
                Log.e(tag, "save bigrams failed", e)
            }
        }.start()
    }

    private companion object {
        const val LEARN_WEIGHT = 50_000L
        const val MAX_LEARNED = 2000
        const val MAX_BIGRAM_PREV = 4000     // cap distinct context words held in memory
        const val MAX_BIGRAM_LINES = 6000    // cap pairs persisted to disk (most-used kept)
        val NO_CONTEXT: (String) -> Long = { 0L }   // shared no-op so correct() allocates nothing
        val NO_SUBCOST: (Int, Char, Char) -> Int? = { _, _, _ -> null }   // no spatial info → grid costs
        const val TAP_HYBRID_MARGIN = 0.22   // how much worse a real word may fit the taps and still win (tune on device)
        const val CONTEXT_CORRECT_MIN = 2L   // min previous→neighbour count to override a valid typed word
        const val COMMON_WORD_FREQ = 50_000L // a learned word one cheap slip from a word this frequent is a typo

        // Apostrophe-less → real contraction. Deliberately excludes forms that collide with common words
        // (its, were, well, ill, hell, shell, wed, shed, lets, id) so we never rewrite a word you meant.
        val CONTRACTIONS: Map<String, String> = mapOf(
            "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't", "isnt" to "isn't",
            "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't", "wont" to "won't",
            "cant" to "can't", "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
            "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't", "mustnt" to "mustn't",
            "im" to "I'm", "ive" to "I've",
            "youre" to "you're", "youve" to "you've", "youll" to "you'll", "youd" to "you'd",
            "theyre" to "they're", "theyve" to "they've", "theyll" to "they'll", "theyd" to "they'd",
            "weve" to "we've", "hes" to "he's", "shes" to "she's", "hed" to "he'd",
            "whats" to "what's", "thats" to "that's", "theres" to "there's", "whos" to "who's",
            "hows" to "how's", "wheres" to "where's", "whens" to "when's",
            "aint" to "ain't", "yall" to "y'all", "cmon" to "c'mon",
        )
    }
}

/**
 * The dictionary for each language, built lazily and cached. Every supported language has one (English
 * and Hebrew bundled in the APK; the rest downloaded on demand), so callers don't special-case codes.
 * Returns null only for an unknown code or a language with no dictionary configured.
 */
object Dictionaries {
    private val instances = HashMap<String, WordDictionary>()

    fun get(code: String): WordDictionary? {
        val def = Languages.byCode(code)
        if (def.code != code) return null                       // byCode falls back to EN for unknowns
        if (def.dictAsset == null && def.dictUrl == null) return null
        return instances.getOrPut(code) { build(def) }
    }

    private fun build(def: LangDef): WordDictionary = when (def.code) {
        // Hebrew is caseless, and its layout has two symbol keys (geresh, maqaf) before the letters,
        // which shift the column alignment the key-adjacency model depends on — so it keeps the exact
        // alphabet and rows the keyboard-aware corrector was tuned against, plus a shorter learn cap.
        "he" -> WordDictionary(
            code = "he",
            alphabet = "אבגדהוזחטיךכלםמןנסעףפץצקרשת",
            adjacencyRows = listOf("׳־קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ"),
            assetName = def.dictAsset,
            maxLearnLen = 15,
            freqSizeHint = 96_000,   // curated list now holds ~70k words; size to avoid rehashing on load
        )
        // English corrects over plain a–z — its bundled dictionary has no accents, unlike the other
        // Latin languages whose alphabets include their accented letters (derived from the layout).
        "en" -> WordDictionary(
            code = "en",
            alphabet = "abcdefghijklmnopqrstuvwxyz",
            adjacencyRows = def.letterRows,
            assetName = def.dictAsset,
            bigramAsset = "en_bigrams.txt",   // pre-trained next-word model, bundled
            freqSizeHint = 34_000,
        )
        else -> WordDictionary(
            code = def.code,
            alphabet = def.autocorrectAlphabet,
            adjacencyRows = def.letterRows,
        )
    }
}
