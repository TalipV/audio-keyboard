package dev.yazin.keyboard.ime

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorTest {
    /** A text field with selection and composing span; the InputConnection is a dynamic proxy (no Android runtime). */
    private class FakeField(initial: String) : Editor.Host {
        val text = StringBuilder(initial)
        var selStart = initial.length
        var selEnd = initial.length
        var compStart = -1
        var compEnd = -1
        var textUnavailable = false
        /**
         * Every text change drops the composing span, as in a field whose InputFilter returns a plain String or whose
         * framework (Flutter, a controller reset) writes the text back without a composing range. setComposingRegion
         * still works.
         */
        var dropsComposing = false
        /** Rewrites what the keyboard puts in, as an app's InputFilter can. */
        var filter: (String) -> String = { it }
        val keys = mutableListOf<Int>()
        var enterKeys = 0
        var editorAction = -1
        val composing: String? get() = if (compStart >= 0) text.substring(compStart, compEnd) else null
        /** What InputMethodManager.updateSelection would send: selection and composing span, unchanged repeats skipped. */
        val reports = ArrayDeque<IntArray>()

        private fun replace(start: Int, end: Int, s: String) {
            text.replace(start, end, s)
            selStart = start + s.length
            selEnd = selStart
        }

        private fun report() {
            val r = intArrayOf(selStart, selEnd, compStart, compEnd)
            if (last?.contentEquals(r) != true) { reports.addLast(r); last = r }
        }
        private var last: IntArray? = intArrayOf(selStart, selEnd, -1, -1)

        private val ic = Proxy.newProxyInstance(InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)) { _, m, a ->
            val textBefore = text.toString()
            when (m.name) {
                "beginBatchEdit" -> batch++
                "endBatchEdit" -> batch--
            }
            handle(m, a).also {
                if (dropsComposing && text.toString() != textBefore) { compStart = -1; compEnd = -1 }
                if (batch == 0) report()
            }
        } as InputConnection
        private var batch = 0

        private fun handle(m: java.lang.reflect.Method, a: Array<out Any?>?): Any? =
            when (m.name) {
                "getTextBeforeCursor" -> if (textUnavailable) null else text.substring(maxOf(0, selStart - a!![0] as Int), selStart)
                "getTextAfterCursor" -> if (textUnavailable) null else text.substring(selEnd, minOf(text.length, selEnd + a!![0] as Int))
                "getSelectedText" -> if (selEnd > selStart) text.substring(selStart, selEnd) else null
                "commitText" -> {
                    val s = filter(a!![0].toString())
                    if (compStart >= 0) replace(compStart, compEnd, s) else replace(selStart, selEnd, s)
                    compStart = -1; compEnd = -1; true
                }
                "setComposingText" -> {
                    val s = filter(a!![0].toString())
                    val (st, en) = if (compStart >= 0) compStart to compEnd else selStart to selEnd
                    replace(st, en, s)
                    compStart = st; compEnd = st + s.length; true
                }
                "finishComposingText" -> { compStart = -1; compEnd = -1; true }
                "setComposingRegion" -> { compStart = a!![0] as Int; compEnd = a[1] as Int; true }
                "deleteSurroundingText" -> {
                    val n = a!![0] as Int
                    text.delete(selStart - n, selStart); selStart -= n; selEnd = selStart; true
                }
                "performEditorAction" -> { editorAction = a!![0] as Int; true }
                "setSelection" -> { selStart = a!![0] as Int; selEnd = a[1] as Int; compStart = -1; compEnd = -1; true }
                "getExtractedText" -> android.view.inputmethod.ExtractedText().apply { text = this@FakeField.text.toString(); startOffset = 0; selectionStart = selStart; selectionEnd = selEnd }
                else -> when (m.returnType) {
                    Boolean::class.javaPrimitiveType -> true
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }

        override fun connection(): InputConnection = ic
        override fun sendKey(keyCode: Int) { keys += keyCode }
        override fun sendEnterKey() { enterKeys++ }
        override fun toString() = text.toString()
    }

    private fun editor(initial: String = ""): Pair<FakeField, Editor> {
        val f = FakeField(initial)
        return f to Editor(f).apply { start(initial.length) }
    }

    private fun Editor.type(word: String) = word.forEach { typeLetter(it.toString(), null) }

    /** Hands the app's selection reports to [e] as KeyboardService.onUpdateSelection does. */
    private fun FakeField.deliver(e: Editor) {
        while (reports.isNotEmpty()) {
            val r = reports.removeFirst()
            if (e.onSelectionChanged(r[0], r[1], composing = r[2] in 0 until r[3])) e.resumeWord()
        }
    }

    private fun dropping(initial: String = ""): Pair<FakeField, Editor> =
        editor(initial).also { (f, e) -> f.dropsComposing = true; e.typeLetter("x", null); f.deliver(e); e.delete(); f.deliver(e) }

    private fun FakeField.type(e: Editor, word: String) = word.forEach { e.typeLetter(it.toString(), null); deliver(e) }

    // BenefitPay: typing "rashed" gave "rrasrrasherrasrrashed".
    @Test fun aFieldThatDropsComposingTextGetsEachLetterOnce() {
        val (f, e) = editor()
        f.dropsComposing = true
        f.type(e, "rashed")
        assertEquals("rashed", f.toString())
        assertEquals("rashed", e.word.toString())
        assertTrue(e.plainWords)
        e.finishWord(null); e.separator(" ", true); f.deliver(e)
        f.type(e, "ali")
        assertEquals("rashed ali", f.toString())
        assertEquals("rashed", e.previousWord())
    }

    @Test fun aLetterSentBeforeTheFirstReportIsTakenOutAgain() {
        val (f, e) = editor("x ")
        f.dropsComposing = true
        e.type("ra") // the app already put "r" + "ra" in
        assertEquals("x rra", f.toString())
        f.deliver(e)
        assertEquals("x ra", f.toString())
        f.type(e, "shed")
        assertEquals("x rashed", f.toString())
        assertEquals("rashed", e.word.toString())
    }

    @Test fun threeLettersSentBeforeTheFirstReportAreTakenOutAgain() {
        val (f, e) = editor()
        f.dropsComposing = true
        e.type("ras") // "r" + "ra" + "ras"
        f.deliver(e)
        assertEquals("ras", f.toString())
        f.type(e, "hed")
        assertEquals("rashed", e.word.toString()) // the stray copies' late reports weren't taken for a tap
        e.finishWord(null); e.separator(" ", true); f.deliver(e)
        assertEquals("rashed ", f.toString())
    }

    @Test fun textTheAppRewroteIsNotTakenOut() {
        val (f, e) = editor("x ")
        f.dropsComposing = true
        f.filter = { it.uppercase() }
        e.type("ra")
        f.deliver(e)
        assertEquals("x RRA", f.toString())
        f.type(e, "s")
        assertEquals("x RRAS", f.toString())
    }

    @Test fun aFieldThatKeepsComposingTextStaysComposing() {
        val (f, e) = editor()
        f.type(e, "rashed")
        assertEquals("rashed", f.composing)
        assertFalse(e.plainWords)
    }

    @Test fun oneReportForAWordAndItsEndIsNotADroppedSpan() {
        val (_, e) = editor()
        e.type("hi"); e.finishWord(null)
        assertFalse(e.onSelectionChanged(2, 2, composing = false))
        e.typeLetter("s", null)
        assertFalse(e.plainWords)
    }

    @Test fun plainWordsAutocorrectAndOneBackspaceRestores() {
        val (f, e) = dropping("x ")
        f.type(e, "teh")
        e.finishWord("the"); e.separator(" ", true); f.deliver(e)
        assertEquals("x the ", f.toString())
        assertTrue(e.delete())
        assertEquals("x teh", f.toString())
    }

    @Test fun plainWordsTakeAPickedSuggestion() {
        val (f, e) = dropping()
        f.type(e, "hel")
        e.pick("hello"); f.deliver(e)
        assertEquals("hello ", f.toString())
    }

    @Test fun plainWordsBackspaceOneLetter() {
        val (f, e) = dropping()
        f.type(e, "abc")
        e.delete(); f.deliver(e)
        assertEquals("ab", f.toString())
        assertEquals("ab", e.word.toString())
        f.type(e, "d")
        assertEquals("abd", f.toString())
    }

    @Test fun plainWordsBackspaceRemovesAGlide() {
        val (f, e) = dropping("Hello ")
        e.glide("there"); f.deliver(e)
        e.delete(); f.deliver(e)
        assertEquals("Hello ", f.toString())
    }

    @Test fun plainWordsAreNotReopenedByATap() {
        val (f, e) = dropping("hello world")
        f.selStart = 5; f.selEnd = 5
        assertTrue(e.onSelectionChanged(5, 5, composing = false))
        assertFalse(e.resumeWord())
    }

    @Test fun plainWordsLastUntilAnotherFieldStarts() {
        val (f, e) = dropping()
        e.start(f.selStart, sameField = true)
        assertTrue(e.plainWords)
        e.start(f.selStart)
        assertFalse(e.plainWords)
    }

    @Test fun lettersComposeAndSpaceCommits() {
        val (f, e) = editor()
        e.type("hi")
        assertEquals("hi", f.composing)
        e.finishWord(null)
        e.separator(" ", doubleSpacePeriod = true)
        assertEquals("hi ", f.toString())
        assertNull(f.composing)
    }

    @Test fun autocorrectReplacesAndOneBackspaceRestores() {
        val (f, e) = editor()
        e.type("teh")
        e.finishWord("the")
        e.separator(" ", doubleSpacePeriod = true)
        assertEquals("the ", f.toString())
        assertTrue(e.delete())
        assertEquals("teh", f.toString())
    }

    @Test fun undoRestoresTypedWordAndPunctuation() {
        val (f, e) = editor("x ")
        e.type("teh")
        e.finishWord("the")
        e.separator(",", doubleSpacePeriod = true)
        assertEquals("x the,", f.toString())
        assertTrue(e.delete())
        assertEquals("x teh,", f.toString())
    }

    @Test fun undoOnlyRightAfterTheFirstSeparator() {
        val (f, e) = editor("x ")
        e.type("teh"); e.finishWord("the")
        e.separator(",", true); e.separator(" ", true)
        assertEquals(null, e.lastCorrection)
        assertFalse(e.delete())
        assertEquals("x the,", f.toString())
    }

    @Test fun anUndoneCorrectionKnowsTheWordBeforeIt() {
        val (_, e) = editor("I said ")
        e.type("teh"); e.finishWord("the"); e.separator(" ", true)
        assertEquals("said", e.wordBeforeCorrection())
        val (_, start) = editor()
        start.type("Teh"); start.finishWord("The"); start.separator(" ", true)
        assertEquals(null, start.wordBeforeCorrection())
    }

    @Test fun unknownCursorResyncsWithoutDuplicatingTheWord() {
        val (f, e) = editor()
        e.delete() // empty field: a real DEL goes to the app, the cursor is still 0
        e.type("hi")
        assertFalse(e.onSelectionChanged(1, 1))
        assertFalse(e.onSelectionChanged(2, 2))
        e.finishWord(null); e.separator(" ", true)
        assertEquals("hi", e.previousWord())
        assertEquals("hi ", f.toString())
    }

    @Test fun belatedEchoesAfterAnEnterKeyResyncFromTheApp() {
        val (f, e) = editor("a")
        e.enter(EnterAction.NEWLINE, EditorInfo.IME_ACTION_UNSPECIFIED) // key event: our cursor is unknown now
        f.text.append("\n"); f.selStart = 2; f.selEnd = 2 // what the app did with it
        e.type("hi")
        assertFalse(e.onSelectionChanged(3, 3)) // belated echo of "h"
        assertFalse(e.onSelectionChanged(4, 4))
        assertEquals("hi", e.word.toString())
    }

    @Test fun resumedWordsAreMarkedUntilEdited() {
        val (f, e) = editor("hello world")
        f.selStart = 5; f.selEnd = 5
        e.onSelectionChanged(5, 5)
        e.resumeWord()
        assertTrue(e.resumed)
        e.typeLetter("s", null)
        assertFalse(e.resumed)
    }

    @Test fun backspaceAfterOtherInputDoesNotUndo() {
        val (f, e) = editor()
        e.type("teh"); e.finishWord("the"); e.separator(" ", true)
        e.typeLetter("a", null)
        assertFalse(e.delete())
        assertEquals("the ", f.toString())
    }

    @Test fun pickedSuggestionGetsASpaceThatPunctuationTakesOver() {
        val (f, e) = editor()
        e.type("hel")
        e.pick("hello")
        assertEquals("hello ", f.toString())
        e.separator(",", doubleSpacePeriod = true)
        assertEquals("hello, ", f.toString())
    }

    @Test fun doubleSpaceAfterAWordTypesAPeriod() {
        val (f, e) = editor()
        e.type("hello"); e.finishWord(null)
        e.separator(" ", true); e.separator(" ", true)
        assertEquals("hello. ", f.toString())
    }

    @Test fun doubleSpacePeriodCanBeTurnedOff() {
        val (f, e) = editor()
        e.type("hello"); e.finishWord(null)
        e.separator(" ", false); e.separator(" ", false)
        assertEquals("hello  ", f.toString())
    }

    @Test fun belatedEchoesAreNotCursorMoves() {
        val (f, e) = editor()
        e.type("ab")
        assertFalse(e.onSelectionChanged(1, 1)) // echo of "a", arriving after "b" was typed
        assertFalse(e.onSelectionChanged(2, 2))
        assertEquals("ab", e.word.toString())
        assertEquals("ab", f.composing)
    }

    @Test fun movingTheCursorBetweenSpacesCancelsThePeriod() {
        val (f, e) = editor()
        e.type("hello"); e.finishWord(null)
        e.separator(" ", true)
        assertFalse(e.onSelectionChanged(6, 6))
        assertTrue(e.onSelectionChanged(2, 2))
        assertTrue(e.onSelectionChanged(6, 6))
        e.separator(" ", true)
        assertEquals("hello  ", f.toString())
    }

    @Test fun aUserMoveAbandonsTheWordInProgress() {
        val (f, e) = editor("one ")
        e.type("tw")
        f.selStart = 1; f.selEnd = 1
        assertTrue(e.onSelectionChanged(1, 1))
        assertEquals("", e.word.toString())
        assertNull(f.composing)
        assertEquals("one tw", f.toString())
    }

    @Test fun tappingAtTheEndOfAWordReopensIt() {
        val (f, e) = editor("hello world")
        f.selStart = 5; f.selEnd = 5
        assertTrue(e.onSelectionChanged(5, 5))
        assertTrue(e.resumeWord())
        assertEquals("hello", e.word.toString())
        assertEquals("hello", f.composing)
    }

    @Test fun tappingInsideAWordLeavesIt() {
        val (f, e) = editor("hello")
        f.selStart = 2; f.selEnd = 2
        e.onSelectionChanged(2, 2)
        assertFalse(e.resumeWord())
    }

    @Test fun deleteInsideAWordEditsTheComposingText() {
        val (f, e) = editor()
        e.type("abc")
        assertFalse(e.delete())
        assertEquals("ab", f.composing)
        e.delete(); e.delete()
        assertEquals("", f.toString())
        assertNull(f.composing)
    }

    @Test fun deleteRemovesAWholeEmoji() {
        val (f, e) = editor("hi 👨‍👩‍👧")
        e.delete()
        assertEquals("hi ", f.toString())
    }

    @Test fun deleteRemovesTheSelection() {
        val f = FakeField("hello").apply { selStart = 1; selEnd = 4 }
        val e = Editor(f).apply { start(1); onSelectionChanged(1, 4) }
        e.delete()
        assertEquals("ho", f.toString())
    }

    @Test fun deleteFallsBackToKeyEventWhenAppHidesText() {
        val f = FakeField("secret").apply { textUnavailable = true }
        Editor(f).apply { start(6) }.delete()
        assertEquals(listOf(KeyEvent.KEYCODE_DEL), f.keys)
        assertEquals("secret", f.toString())
    }

    @Test fun deleteAtStartSendsKeyEvent() {
        val (f, e) = editor()
        e.delete()
        assertEquals(listOf(KeyEvent.KEYCODE_DEL), f.keys)
    }

    @Test fun noComposingWhereSuggestionsAreOff() {
        val (f, e) = editor()
        e.composingEnabled = false
        e.typeLetter("a", null)
        assertEquals("a", f.toString())
        assertNull(f.composing)
    }

    @Test fun capsFollowTheCachedText() {
        val sentences = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        val (_, e) = editor("Hi.")
        assertFalse(e.capsWanted(sentences))
        e.separator(" ", true)
        assertTrue(e.capsWanted(sentences))
        e.typeLetter("O", null)
        assertFalse(e.capsWanted(sentences))
    }

    @Test fun enterPerformsTheEditorAction() {
        val (f, e) = editor("query")
        e.enter(EnterAction.SEARCH, EditorInfo.IME_ACTION_SEARCH)
        assertEquals(EditorInfo.IME_ACTION_SEARCH, f.editorAction)
    }

    @Test fun enterInAMultilineFieldInsertsANewline() {
        val (f, e) = editor("a")
        e.multiline = true
        e.enter(EnterAction.NEWLINE, EditorInfo.IME_ACTION_UNSPECIFIED)
        assertEquals("a\n", f.toString())
    }

    @Test fun enterInASingleLineFieldSendsTheEnterKey() {
        val (f, e) = editor("a")
        e.enter(EnterAction.NEWLINE, EditorInfo.IME_ACTION_UNSPECIFIED)
        assertEquals(1, f.enterKeys)
    }

    @Test fun wordCharactersIncludeArabicMarksAndApostrophes() {
        assertTrue(Editor.isWordChar("a"))
        assertTrue(Editor.isWordChar("ب"))
        assertTrue(Editor.isWordChar("َ"))
        assertTrue(Editor.isWordChar("'"))
        assertFalse(Editor.isWordChar(","))
        assertFalse(Editor.isWordChar(" "))
        assertTrue("." in Editor.SEPARATORS && "؟" in Editor.SEPARATORS)
    }

    private fun dictate(before: String, text: String, isWord: (String) -> Boolean = { false }): String {
        val (f, e) = editor(before)
        e.insertDictation(text, isWord = isWord)
        return f.toString()
    }

    @Test fun dictationAddsASpaceOnlyWhenNeeded() {
        assertEquals("Hi there", dictate("Hi", "there"))
        assertEquals("Hi there", dictate("Hi ", " there "))
        assertEquals("Hello", dictate("", "Hello"))
        assertEquals("(note", dictate("(", "note"))
        assertEquals("line\nnext", dictate("line\n", "next"))
        assertEquals("word, and more", dictate("word", ", and more"))
    }

    @Test fun dictationLowercasesAMidSentenceCapitalOnlyWhenItIsAWord() {
        assertEquals("I sent it and then left.", dictate("I sent it and", " Then left.") { it == "then" })
        assertEquals("I met Yazin today", dictate("I met", "Yazin today") { false })
        assertEquals("Done. Then", dictate("Done.", "Then") { true })
        assertEquals("I said I agree", dictate("I said", "I agree") { true })
        assertEquals("Go to NASA now", dictate("Go to", "NASA now") { true })
    }

    @Test fun arabicDictationIsSpacedAndNeverRecased() = assertEquals("مرحبا كيف حالك", dictate("مرحبا", "كيف حالك"))

    @Test fun dictationReplacesTheSelection() {
        val f = FakeField("Hello world").apply { selStart = 6; selEnd = 11 }
        Editor(f).apply { start(6) }.insertDictation("there") { false }
        assertEquals("Hello there", f.toString())
    }

    @Test fun dictationFinishesTheWordBeingTyped() {
        val (f, e) = editor()
        e.type("hel")
        e.insertDictation("world") { false }
        assertEquals("hel world", f.toString())
        assertNull(f.composing)
    }

    @Test fun emojiAreDeletedWhole() {
        fun cp(vararg c: Int) = c.joinToString("") { String(Character.toChars(it)) }
        for (emoji in listOf(
            cp(0x1F469, 0x1F3FD, 0x200D, 0x1F4BB), // woman technologist: medium skin tone
            cp(0x1F1E6, 0x1F1EA), // flag: United Arab Emirates
            cp(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467, 0x200D, 0x1F466), // family
            cp(0x2764, 0xFE0F), // red heart
        )) {
            val (f, e) = editor("hi $emoji")
            e.delete()
            assertEquals(emoji, "hi ", f.toString())
        }
    }

    @Test fun fieldReadsTheWholeTextAndSelection() {
        val f = FakeField("Hello there. Bye")
        val e = Editor(f)
        e.start(f.selStart)
        f.selStart = 6; f.selEnd = 11
        assertEquals(dev.yazin.keyboard.engine.llm.FieldText("Hello there. Bye", 6, 11), e.field())
    }

    @Test fun replaceSwapsTheWholeFieldAndUndoRestoresIt() {
        val f = FakeField("hi sam see u at 8")
        val e = Editor(f)
        e.start(f.selStart)
        val before = e.field()!!
        e.replace(before, 0, before.text.length, "Hi Sam, see you at 8.")
        assertEquals("Hi Sam, see you at 8.", f.toString())
        assertEquals(21, f.selStart)
        assertTrue(e.canUndoReplace())
        assertTrue(e.undoReplace())
        assertEquals("hi sam see u at 8", f.toString())
        assertEquals(17, f.selStart)
        assertFalse(e.canUndoReplace())
    }

    @Test fun replaceOnlyTheSelectionAndUndoReselectsIt() {
        val f = FakeField("Hello. thanks a lot. Bye.")
        val e = Editor(f)
        e.start(f.selStart)
        f.selStart = 7; f.selEnd = 20
        val field = e.field()!!
        e.replace(field, field.start, field.end, "Thank you very much.")
        assertEquals("Hello. Thank you very much. Bye.", f.toString())
        e.undoReplace()
        assertEquals("Hello. thanks a lot. Bye.", f.toString())
        assertEquals(7 to 20, f.selStart to f.selEnd)
    }

    @Test fun replaceEchoIsNotAUserMove() {
        val f = FakeField("draft")
        val e = Editor(f)
        e.start(f.selStart)
        e.replace(e.field()!!, 0, 5, "Final text")
        assertFalse(e.onSelectionChanged(10, 10))
    }

    @Test fun undoEchoIsNotAUserMove() {
        val f = FakeField("draft")
        val e = Editor(f)
        e.start(f.selStart)
        e.replace(e.field()!!, 0, 5, "Final text")
        e.onSelectionChanged(10, 10)
        e.undoReplace()
        assertFalse(e.onSelectionChanged(5, 5))
    }

    @Test fun aNewFieldForgetsTheVersions() {
        val f = FakeField("a")
        val e = Editor(f)
        e.start(f.selStart)
        e.replace(e.field()!!, 0, 1, "b")
        e.start(f.selStart)
        assertFalse(e.canUndoReplace())
    }

    @Test fun undoIsDroppedOnceTheUserEditsTheField() {
        val f = FakeField("hi sam see u at 8")
        val e = Editor(f)
        e.start(f.selStart)
        val before = e.field()!!
        e.replace(before, 0, before.text.length, "Hi Sam, see you at 8.")
        e.insert(" PS bring snacks") // typed after the ✦ edit
        assertFalse(e.canUndoReplace())
        assertFalse(e.undoReplace())
        assertEquals("Hi Sam, see you at 8. PS bring snacks", f.toString())
    }

    @Test fun undoIsDroppedWhenTheAppClearsTheField() {
        val f = FakeField("draft")
        val e = Editor(f)
        e.start(f.selStart)
        e.replace(e.field()!!, 0, 5, "Final text")
        f.text.setLength(0); f.selStart = 0; f.selEnd = 0 // sent: the app empties the box without restarting input
        assertFalse(e.undoReplace())
        assertEquals("", f.toString())
    }

    @Test fun undoStepsBackThroughSeveralEdits() {
        val f = FakeField("a")
        val e = Editor(f)
        e.start(f.selStart)
        e.replace(e.field()!!, 0, 1, "b")
        e.replace(e.field()!!, 0, 1, "c")
        assertTrue(e.undoReplace())
        assertEquals("b", f.toString())
        assertTrue(e.canUndoReplace())
        assertTrue(e.undoReplace())
        assertEquals("a", f.toString())
    }

    @Test fun undoTakesBackAWholeDictation() {
        val f = FakeField("Hi.")
        val e = Editor(f)
        e.start(f.selStart)
        e.replace(e.field()!!, 0, 3, "Hello.")
        e.insertDictation("See you at 8", "d1") { false }
        e.insertDictation("bring snacks", "d1") { false }
        e.insertDictation("thanks", "d2") { false }
        assertEquals("Hello. See you at 8 bring snacks thanks", f.toString())
        assertEquals("d2", e.lastEdit)
        assertTrue(e.undoReplace())
        assertEquals("Hello. See you at 8 bring snacks", f.toString())
        assertTrue(e.undoReplace())
        assertEquals("Hello.", f.toString())
        assertEquals(Editor.AI, e.lastEdit)
        assertTrue(e.undoReplace())
        assertEquals("Hi.", f.toString())
    }

    @Test fun aDictationAfterTypingCantUndoPastTheTyping() {
        val f = FakeField("a")
        val e = Editor(f)
        e.start(f.selStart)
        e.replace(e.field()!!, 0, 1, "b")
        e.insert(" typed")
        e.insertDictation("said", "d1") { false }
        assertTrue(e.undoReplace())
        assertEquals("b typed", f.toString())
        assertFalse(e.canUndoReplace())
    }

    @Test fun peekTextReadsTheWholeFieldWithoutEndingTheWord() {
        val (_, e) = editor("hi ")
        e.type("the")
        assertEquals("hi the", e.peekText())
        assertEquals("the", e.word.toString()) // still composing
    }

    // Glide (glide spec §3.3–3.4)

    @Test fun glideComposesWithoutASpaceAfter() {
        val (f, e) = editor()
        e.glide("Hello")
        assertEquals("Hello", f.toString())
        assertEquals("Hello", f.composing)
        assertTrue(e.glided)
    }

    @Test fun glideSpaceGoesBeforeTheSecondWord() {
        val (f, e) = editor()
        e.glide("Hello")
        e.finishWord(null)
        assertTrue(e.glideSpace())
        e.glide("there")
        assertEquals("Hello there", f.toString())
    }

    @Test fun glideSpaceSkippedAfterASpace() {
        val (_, e) = editor("Hello ")
        assertFalse(e.glideSpace())
    }

    @Test fun noGlideSpaceInEmailFields() {
        val (f, e) = editor("john")
        e.autoSpace = false
        assertFalse(e.glideSpace())
        assertEquals("john", f.toString())
    }

    @Test fun backspaceDeletesTheWholeGlidedWordKeepingTheSpace() {
        val (f, e) = editor("Hello ")
        e.glide("there")
        e.delete()
        assertEquals("Hello ", f.toString())
        assertFalse(e.glided)
        e.delete()
        assertEquals("Hello", f.toString())
    }

    @Test fun backspaceAfterGlideDeletesTheGlideNotAnOldCorrection() {
        val (f, e) = editor()
        e.type("teh")
        e.finishWord("the")
        e.separator(" ", doubleSpacePeriod = true)
        e.glide("cat")
        assertFalse(e.delete())
        assertEquals("the ", f.toString())
    }

    @Test fun glideReplacesTheSelection() {
        val (f, e) = editor("a big dog")
        f.selStart = 2; f.selEnd = 5
        e.onSelectionChanged(2, 5)
        e.glide("small")
        assertEquals("a small dog", f.toString())
    }

    @Test fun pickingAnAlternativeReplacesTheGlidedWord() {
        val (f, e) = editor()
        e.glide("Hello")
        e.pick("Help")
        assertEquals("Help ", f.toString())
        assertFalse(e.glided)
    }

    @Test fun movingTheCursorEndsFreshness() {
        val (f, e) = editor("x ")
        e.glide("Hello")
        f.selStart = 0; f.selEnd = 0
        e.onSelectionChanged(0, 0)
        assertFalse(e.glided)
    }

    @Test fun lettersAndDigitsStartANewWordAfterAGlideButMarksAttach() {
        assertTrue(Editor.startsWordAfterGlide("t"))
        assertTrue(Editor.startsWordAfterGlide("ب"))
        assertTrue(Editor.startsWordAfterGlide("5"))
        assertFalse(Editor.startsWordAfterGlide("'"))
        assertFalse(Editor.startsWordAfterGlide("\u0651"))
    }

    @Test fun anApostropheAfterAGlideAttachesAndEndsFreshness() {
        val (f, e) = editor()
        e.glide("don")
        e.typeLetter("'", null)
        assertFalse(e.glided)
        e.typeLetter("t", null)
        assertEquals("don't", f.toString())
    }

    // Incognito fields: glide on, composing off (the strip's learning and suggestions stay off)

    @Test fun glideWorksWhereComposingIsOff() {
        val (f, e) = editor("Hi ")
        e.composingEnabled = false
        e.glide("there")
        assertEquals("Hi there", f.toString())
        assertTrue(e.glided)
        assertEquals("there", e.finishWord(null)) // a letter after a glide first ends it (KeyboardService.onText)
        assertNull(f.composing)
        assertTrue(e.glideSpace())
        e.typeLetter("a", null)
        assertEquals("Hi there a", f.toString())
        assertNull(f.composing)
    }

    @Test fun backspaceAfterAGlideDeletesItWhereComposingIsOff() {
        val (f, e) = editor("Hi ")
        e.composingEnabled = false
        e.glide("there")
        e.delete()
        assertEquals("Hi ", f.toString())
    }

    @Test fun pickingAnAlternativeReplacesTheGlidedWordWhereComposingIsOff() {
        val (f, e) = editor()
        e.composingEnabled = false
        e.glide("Hello")
        e.pick("Help")
        assertEquals("Help ", f.toString())
    }
}
