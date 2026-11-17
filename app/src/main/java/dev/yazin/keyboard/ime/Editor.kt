package dev.yazin.keyboard.ime

import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import dev.yazin.keyboard.engine.Fold
import dev.yazin.keyboard.engine.Touch
import dev.yazin.keyboard.engine.llm.FieldText

/**
 * Applies keyboard actions to the focused field.
 *
 * - **Composing text:** the word being typed is held as composing text (underlined, with suggestions).
 * - **Local text cache:** a copy of the text before the cursor, so typing never waits on the app.
 * - **Selection echoes:** the app's selection reports are matched against the cursor positions our own edits will
 *   produce. Only a position we didn't cause counts as the user moving the cursor.
 * - **Gboard's rules:**
 *   - the space added after a picked suggestion moves past punctuation
 *   - double-space types a period
 *   - one backspace undoes an autocorrect
 */
class Editor(private val host: Host) {
    interface Host {
        fun connection(): InputConnection?
        fun sendKey(keyCode: Int)
        fun sendEnterKey()
    }

    /** A replacement made on a separator; one backspace right after puts [typed] (and [separator]) back. */
    data class Correction(val typed: String, val replacement: String, val separator: String)

    var multiline = false
    var composingEnabled = true
    /** Glides put spaces in automatically (not in email and URL fields). */
    var autoSpace = true

    val word = StringBuilder()
    val touches = ArrayList<Touch?>()
    var lastCorrection: Correction? = null
        private set
    /** The word was re-opened by [resumeWord] and not edited since: it was never typed here, so it is not autocorrected. */
    var resumed = false
        private set
    /** The word in progress came from a glide and nothing has edited it since (glide spec §3.4). */
    var glided = false
        private set
    /**
     * The field ends our composing text by itself (an InputFilter returning a plain String, a Flutter field written back
     * without its composing range). Composing again there would put the whole word in a second time, so words go in as
     * plain text, still tracked in [word], until another field starts.
     */
    var plainWords = false
        private set

    /** A cursor position one of our edits will report, and whether the word is composing there. */
    private data class Echo(val cursor: Int, val composing: Boolean)

    private val before = StringBuilder()
    private var cursor = -1
    private var selectionLength = 0
    private val pending = ArrayDeque<Echo>()
    private var lastWasSpace = false
    private var phantomSpace = false

    /** New field (or a restart of it): one read of the text before the cursor; everything after is tracked locally. */
    fun start(initialCursor: Int, sameField: Boolean = false) {
        word.clear(); touches.clear(); pending.clear()
        lastCorrection = null; lastWasSpace = false; phantomSpace = false; selectionLength = 0; glided = false
        if (!sameField) plainWords = false
        versions.clear()
        cursor = initialCursor
        refill()
    }

    fun typeLetter(s: String, touch: Touch?) {
        val ic = host.connection() ?: return
        resetFlags()
        glided = false // an apostrophe or mark typed onto a glided word edits it
        if (!composingEnabled) {
            commit(ic, s)
            return
        }
        word.append(s)
        repeat(s.length) { touches += touch }
        if (plainWords) ic.commitText(s, 1) else ic.setComposingText(word, 1)
        expect(cursor + s.length, composing = !plainWords)
    }

    /** Puts a glided word in as composing text, replacing any selection, with no space after it. */
    fun glide(s: String) {
        val ic = host.connection() ?: return
        resetFlags()
        word.clear(); touches.clear()
        word.append(s)
        repeat(s.length) { touches += null }
        if (plainWords) ic.commitText(s, 1) else ic.setComposingText(word, 1)
        expect(cursor + s.length, composing = !plainWords)
        glided = true
    }

    /** With no word in progress, types the space a glided word needs before it. Returns whether it did. */
    fun glideSpace(): Boolean {
        if (word.isNotEmpty() || !autoSpace || !TextRules.spaceBeforeGlide(before)) return false
        val ic = host.connection() ?: return false
        resetFlags()
        commit(ic, " ")
        return true
    }

    /** Text that is neither part of a word nor a separator (@, #, emoji…): committed as-is. */
    fun insert(s: String) {
        val ic = host.connection() ?: return
        resetFlags()
        commit(ic, s)
    }

    /**
     * Inserts dictated [text] at the cursor, replacing any selection (spec §7.2). [isWord] says whether a lowercase form
     * is an ordinary dictionary word, for mid-sentence capitals. Inserts of one [group] are one ↶ step.
     */
    fun insertDictation(text: String, group: String? = null, isWord: (String) -> Boolean) {
        if (word.isNotEmpty()) finishWord(null)
        val ic = host.connection() ?: return
        val s = TextRules.dictation(before, text, isWord)
        if (s.isEmpty()) return
        val f = field()
        // Later chunks of the same dictation join its ↶ step while nothing else touched the field.
        val joins = group != null && lastEdit == group && f?.text == written
        resetFlags()
        commit(ic, s) // at the cursor, not by offset: right even where field() is cut short
        if (f == null) return
        if (!joins) remember(f, group)
        written = f.text.replaceRange(f.start, f.end, s)
    }

    /**
     * Field versions before each ✦ or dictation edit, newest last, each with the edit that followed it: [AI] or the
     * dictation's group (null: a step of its own). ↶ restores them (AI orb spec §2).
     */
    private val versions = ArrayList<Pair<FieldText, String?>>()

    /** What made the field's current text when ↶ applies: [AI], a dictation group or null. */
    val lastEdit: String? get() = versions.lastOrNull()?.second

    /** The field as our last ✦ or dictation edit left it; ↶ only applies while the field still holds exactly that. */
    private var written: String? = null

    /** ↶ applies: there is a version to go back to and nobody has edited the field since (else the history is dropped). */
    fun canUndoReplace(): Boolean = versions.isNotEmpty() && untouched(field())

    private fun untouched(now: FieldText?): Boolean {
        if (now != null && now.text == written) return true
        versions.clear() // typed, narrated, sent or cleared since: going back would wipe that
        return false
    }

    /** The whole field and its selection (AI orb spec §4), or null when the app won't say. Ends the word being typed first. */
    fun field(): FieldText? {
        if (word.isNotEmpty()) finishWord(null)
        val ic = host.connection() ?: return null
        val et = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = FIELD_MAX }, 0)
        val t = et?.text
        if (t != null && et.startOffset == 0 && et.selectionStart >= 0) {
            return FieldText(t.toString(), minOf(et.selectionStart, et.selectionEnd), maxOf(et.selectionStart, et.selectionEnd))
        }
        // ponytail: apps without extracted text; exact while the field is shorter than FIELD_MAX on each side
        val before = ic.getTextBeforeCursor(FIELD_MAX, 0)?.toString() ?: return null
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(FIELD_MAX, 0)?.toString().orEmpty()
        return FieldText(before + selected + after, before.length, before.length + selected.length)
    }

    /** The whole field's text, word in progress included and left composing; null when the app won't say. One IPC call. */
    fun peekText(): String? {
        val et = host.connection()?.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = FIELD_MAX }, 0) ?: return null
        return if (et.startOffset == 0) et.text?.toString() else null
    }

    /** Puts [text] in place of [start]..[end] of [field] as one edit, cursor after it; [undoReplace] brings [field] back. */
    fun replace(field: FieldText, start: Int, end: Int, text: String) {
        val result = field.text.substring(0, start) + text + field.text.substring(end)
        remember(field, AI)
        rewrite(start, end, text, result, start + text.length, start + text.length)
    }

    /** Call before writing over [field]. */
    private fun remember(field: FieldText, edit: String?) {
        if (field.text != written) versions.clear() // changed since our last edit: older versions would wipe that
        versions += field to edit
        if (versions.size > MAX_VERSIONS) versions.removeAt(0)
    }

    /** ↶: the field as it was before the last [replace] or dictation, selection included. */
    fun undoReplace(): Boolean {
        if (versions.isEmpty()) return false
        val now = field()
        if (!untouched(now)) return false
        val previous = versions.removeAt(versions.lastIndex).first
        rewrite(0, now!!.text.length, previous.text, previous.text, previous.start, previous.end)
        return true
    }

    /**
     * Replaces [start]..[end] with [text] in one batch edit and leaves [selStart]..[selEnd] of [result] (the whole new
     * field) selected. Apps report the selection once, at the end of the batch: a collapsed cursor is expected as our echo.
     */
    private fun rewrite(start: Int, end: Int, text: String, result: String, selStart: Int, selEnd: Int) {
        val ic = host.connection() ?: return
        resetFlags()
        word.clear(); touches.clear(); glided = false
        ic.beginBatchEdit()
        ic.setSelection(start, end)
        ic.commitText(text, 1)
        if (selStart != start + text.length || selEnd != selStart) ic.setSelection(selStart, selEnd)
        ic.endBatchEdit()
        before.setLength(0)
        before.append(result, maxOf(0, selStart - CACHE), selStart)
        cursor = selStart
        selectionLength = selEnd - selStart
        pending.clear()
        if (selEnd == selStart) pending.addLast(Echo(selStart, false))
        written = result
    }

    /** Ends the word being typed, swapping in [replacement] when autocorrect chose one. Returns the final word. */
    fun finishWord(replacement: String?): String? {
        if (word.isEmpty()) return null
        val ic = host.connection() ?: return null
        val typed = word.toString()
        val final = replacement ?: typed
        when {
            final != typed -> replaceWord(ic, typed.length, final)
            !plainWords -> ic.finishComposingText()
        }
        before.append(final)
        trimCache()
        if (final != typed || !plainWords) expect(cursor - typed.length + final.length)
        lastCorrection = if (final != typed) Correction(typed, final, "") else null
        word.clear(); touches.clear()
        resumed = false
        glided = false
        return final
    }

    /** Types a space or punctuation after a word (call [finishWord] first). */
    fun separator(s: String, doubleSpacePeriod: Boolean) {
        val ic = host.connection() ?: return
        val correction = lastCorrection
        if (s == " ") {
            if (doubleSpacePeriod && TextRules.isDoubleSpacePeriod(before, lastWasSpace)) {
                replaceLast(ic, 1, ". ")
                resetFlags()
                return
            }
            commit(ic, " ")
            lastWasSpace = true
            phantomSpace = false
        } else if (phantomSpace && s in SWAP_PUNCTUATION && before.endsWith(" ")) {
            // "hello |" + "," → "hello, |": Gboard moves the automatic space after the punctuation.
            replaceLast(ic, 1, "$s ")
            lastWasSpace = false
            lastCorrection = null
            return
        } else {
            commit(ic, s)
            lastWasSpace = false
            phantomSpace = false
        }
        // Only the separator right after the replaced word can be undone along with it.
        lastCorrection = correction?.takeIf { it.separator.isEmpty() }?.copy(separator = s)
    }

    /** Replaces the word being typed (if any) with a suggestion, followed by an automatic space. */
    fun pick(text: String) {
        val ic = host.connection() ?: return
        val typedLength = word.length
        replaceWord(ic, typedLength, "$text ")
        before.append(text).append(' ')
        trimCache()
        expect(cursor - typedLength + text.length + 1)
        word.clear(); touches.clear()
        glided = false
        lastCorrection = null
        phantomSpace = true
        lastWasSpace = true
    }

    /** Backspace. Returns true when it undid an autocorrect. */
    fun delete(): Boolean {
        val ic = host.connection() ?: return false
        val correction = lastCorrection
        resetFlags()
        if (glided && word.isNotEmpty()) {
            // Backspace straight after a glide removes the whole word, keeping the space before it.
            val n = word.length
            word.clear(); touches.clear(); glided = false
            replaceWord(ic, n, "")
            expect(cursor - n)
            return false
        }
        if (correction != null) {
            replaceLast(ic, correction.replacement.length + correction.separator.length, correction.typed + correction.separator.takeIf { it != " " }.orEmpty())
            return true
        }
        if (word.isNotEmpty()) {
            val n = TextRules.lastGraphemeLength(word)
            word.setLength(word.length - n)
            repeat(n) { touches.removeAt(touches.lastIndex) }
            when {
                plainWords -> ic.deleteSurroundingText(n, 0)
                word.isEmpty() -> ic.commitText("", 1)
                else -> ic.setComposingText(word, 1)
            }
            expect(cursor - n, composing = word.isNotEmpty() && !plainWords)
            return false
        }
        if (selectionLength > 0) {
            ic.commitText("", 1)
            selectionLength = 0
            refill()
            return false
        }
        if (before.isEmpty() && cursor > 0) refill()
        val n = TextRules.lastGraphemeLength(before)
        if (n > 0) {
            ic.deleteSurroundingText(n, 0)
            before.setLength(before.length - n)
            expect(cursor - n)
        } else {
            host.sendKey(KeyEvent.KEYCODE_DEL)
            // At the very start nothing moves; elsewhere the app decides what DEL did, so resync on its next report.
            if (cursor != 0) cursor = -1
        }
        return false
    }

    fun enter(action: EnterAction, actionId: Int) {
        val ic = host.connection() ?: return
        resetFlags()
        when {
            action != EnterAction.NEWLINE -> ic.performEditorAction(actionId)
            multiline -> commit(ic, "\n")
            else -> { host.sendEnterKey(); cursor = -1 }
        }
    }

    /**
     * A selection report from the app; [composing] says whether it reports a composing span. Returns true when the user
     * moved the cursor (not an echo of our edits). Then the word in progress is left as plain text and the text cache is
     * read again.
     */
    fun onSelectionChanged(start: Int, end: Int, composing: Boolean = true): Boolean {
        selectionLength = end - start
        if (start == end) {
            val i = pending.indexOfFirst { it.cursor == start }
            if (i >= 0) {
                // One report can stand for several of our edits at this position: the span is the app's to drop only
                // when each of them composed.
                val dropped = !composing && !plainWords && word.isNotEmpty() &&
                    (i until pending.size).all { pending[it].cursor != start || pending[it].composing }
                repeat(i + 1) { pending.removeFirst() }
                if (dropped) dropComposing()
                return false
            }
            if (cursor < 0) { resync(start); return false }
            if (start == cursor) return false
        }
        if (word.isNotEmpty() && !plainWords) host.connection()?.finishComposingText()
        word.clear(); touches.clear(); pending.clear()
        glided = false
        resetFlags()
        cursor = start
        refill()
        return true
    }

    /** Gboard re-opens the word the cursor lands at the end of, so its suggestions come back. */
    fun resumeWord(): Boolean {
        if (!composingEnabled || plainWords || selectionLength != 0 || word.isNotEmpty()) return false
        val ic = host.connection() ?: return false
        val after = ic.getTextAfterCursor(1, 0)
        if (!after.isNullOrEmpty() && isWordChar(after.toString())) return false
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1].toString())) i--
        if (i == before.length) return false
        val w = before.substring(i)
        ic.setComposingRegion(cursor - w.length, cursor)
        before.setLength(i)
        word.append(w)
        repeat(w.length) { touches += null }
        resumed = true
        return true
    }

    /**
     * Our cursor was unknown (after a DEL or Enter key event). Ask the app where it is now, expect the echoes of letters
     * already typed, and keep the word in progress out of the cache.
     */
    private fun resync(reported: Int) {
        val et = host.connection()?.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 0 }, 0)
        cursor = if (et != null && et.selectionStart >= 0) et.startOffset + et.selectionStart else reported
        pending.clear()
        for (k in 1 until word.length) pending.addLast(Echo(cursor - word.length + k, !plainWords))
        val i = pending.indexOfFirst { it.cursor == reported }
        if (i >= 0) repeat(i + 1) { pending.removeFirst() }
        refill()
        if (word.isNotEmpty() && before.endsWith(word)) before.setLength(before.length - word.length)
    }

    fun capsWanted(inputType: Int): Boolean = word.isEmpty() && TextRules.capsMode(before, inputType)

    /** The word before the one being typed. */
    fun previousWord(): String? = TextRules.previousWord(before)

    /** The word before [lastCorrection]'s replacement: the context the undone word was typed in. */
    fun wordBeforeCorrection(): String? = lastCorrection?.let {
        TextRules.previousWord(before.substring(0, maxOf(0, before.length - it.replacement.length - it.separator.length)))
    }

    fun endsWithSpace(): Boolean = word.isEmpty() && before.endsWith(" ")

    private fun commit(ic: InputConnection, s: String) {
        ic.commitText(s, 1)
        before.append(s)
        trimCache()
        expect(cursor + s.length)
    }

    /** Deletes [n] chars before the cursor and types [s], as one edit. */
    private fun replaceLast(ic: InputConnection, n: Int, s: String) {
        ic.beginBatchEdit()
        ic.deleteSurroundingText(n, 0)
        ic.commitText(s, 1)
        ic.endBatchEdit()
        before.setLength(maxOf(0, before.length - n))
        before.append(s)
        trimCache()
        expect(cursor - n + s.length)
    }

    /** Puts [s] in place of the [n] chars of the word before the cursor. */
    private fun replaceWord(ic: InputConnection, n: Int, s: String) {
        if (!plainWords || n == 0) { ic.commitText(s, 1); return }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(n, 0)
        if (s.isNotEmpty()) ic.commitText(s, 1)
        ic.endBatchEdit()
    }

    /** The app ended our composing span by itself: the word stays as the plain text it left, and later words go in plain. */
    private fun dropComposing() {
        plainWords = true
        val ic = host.connection() ?: return
        ic.finishComposingText() // in case it keeps a span it doesn't report
        val now = ic.getTextBeforeCursor(CACHE, 0)?.toString() ?: return
        val w = word.toString()
        if (now.endsWith(w) && before.endsWith(now.dropLast(w.length))) return
        val copies = if (cursor >= 0) strayCopies(now, w) else null
        if (copies == null) {
            // The app changed the text some other way: leave it as it is and type on from what it holds.
            word.clear(); touches.clear(); glided = false
            before.setLength(0)
            before.append(now)
            return
        }
        // Each letter sent before this report put the word so far in again, after the copy before it.
        ic.beginBatchEdit()
        ic.deleteSurroundingText(copies.sum() + w.length, 0)
        ic.commitText(w, 1)
        ic.endBatchEdit()
        pending.clear()
        var c = cursor - w.length
        for (n in copies + w.length) { c += n; pending.addLast(Echo(c, false)) } // where those letters' reports still land
        pending.addLast(Echo(cursor, false))
    }

    /**
     * The lengths of the copies of [w]'s beginnings between the cached text and [w] at the end of [now], or null unless
     * exactly one split fits.
     */
    private fun strayCopies(now: String, w: String): List<Int>? {
        if (w.isEmpty() || !now.endsWith(w)) return null
        val rest = now.dropLast(w.length)
        return (1..rest.length)
            .mapNotNull { n -> if (before.endsWith(rest.dropLast(n))) beginnings(rest.takeLast(n), w, 0) else null }
            .singleOrNull()
    }

    /** [s] as beginnings of [w], each longer than the one before (and than [after]) and shorter than [w]; null if it isn't. */
    private fun beginnings(s: String, w: String, after: Int): List<Int>? {
        if (s.isEmpty()) return emptyList()
        for (n in after + 1..minOf(w.length - 1, s.length)) {
            if (!s.regionMatches(0, w, 0, n)) continue
            beginnings(s.substring(n), w, n)?.let { return listOf(n) + it }
        }
        return null
    }

    private fun expect(c: Int, composing: Boolean = false) {
        if (cursor < 0) return
        cursor = c
        pending.addLast(Echo(c, composing))
        if (pending.size > 64) pending.removeFirst()
    }

    private fun refill() {
        before.setLength(0)
        host.connection()?.getTextBeforeCursor(CACHE, 0)?.let { before.append(it) }
    }

    private fun trimCache() {
        if (before.length > CACHE * 2) before.delete(0, before.length - CACHE)
    }

    private fun resetFlags() {
        resumed = false
        lastCorrection = null
        lastWasSpace = false
        phantomSpace = false
    }

    companion object {
        private const val CACHE = 128
        /** Characters read on each side of the cursor when an app has no extracted text. */
        private const val FIELD_MAX = 10_000
        private const val MAX_VERSIONS = 20
        /** [lastEdit] for a ✦ answer. */
        const val AI = "✦"

        /** Punctuation that ends a word: it triggers autocorrect and takes over the space after a picked suggestion. */
        val SEPARATORS = setOf(".", ",", "?", "!", ":", ";", "،", "؟", "؛")
        private val SWAP_PUNCTUATION = SEPARATORS

        /** A tapped letter or digit after a glided word starts a new word; apostrophes and marks attach (glide spec §3.4). */
        fun startsWordAfterGlide(s: String): Boolean = s.firstOrNull()?.isLetterOrDigit() == true

        fun isWordChar(s: String): Boolean = s.isNotEmpty() && s.all { c ->
            Character.isLetterOrDigit(c) || Fold.isSkippable(c) || Character.getType(c) == Character.NON_SPACING_MARK.toInt()
        }
    }
}
