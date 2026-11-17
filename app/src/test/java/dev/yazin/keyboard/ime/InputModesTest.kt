package dev.yazin.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.yazin.keyboard.keyboard.Field
import dev.yazin.keyboard.keyboard.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputModesTest {
    private val text = InputType.TYPE_CLASS_TEXT

    @Test fun fieldClassPicksThePage() {
        assertEquals(Page.LETTERS, InputModes.of(text, 0).page)
        assertEquals(Page.NUMBER, InputModes.of(InputType.TYPE_CLASS_NUMBER, 0).page)
        assertEquals(Page.NUMBER, InputModes.of(InputType.TYPE_CLASS_DATETIME, 0).page)
        assertEquals(Page.PHONE, InputModes.of(InputType.TYPE_CLASS_PHONE, 0).page)
    }

    @Test fun emailAndUrlFieldsAreDetected() {
        assertEquals(Field.EMAIL, InputModes.of(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0).field)
        assertEquals(Field.EMAIL, InputModes.of(text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, 0).field)
        assertEquals(Field.URL, InputModes.of(text or InputType.TYPE_TEXT_VARIATION_URI, 0).field)
        assertEquals(Field.TEXT, InputModes.of(text, 0).field)
    }

    @Test fun passwordFieldsAreDetected() {
        assertTrue(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0).password)
        assertTrue(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0).password)
        assertTrue(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0).password)
        assertTrue(InputModes.of(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0).password)
        assertFalse(InputModes.of(text, 0).password)
    }

    @Test fun doubleSpacePeriodOnlyInVisibleTextFields() {
        assertTrue(InputModes.of(text, 0).doubleSpacePeriod)
        assertFalse(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0).doubleSpacePeriod)
        assertFalse(InputModes.of(InputType.TYPE_CLASS_PHONE, 0).doubleSpacePeriod)
        assertFalse(InputModes.of(InputType.TYPE_CLASS_NUMBER, 0).doubleSpacePeriod)
    }

    @Test fun enterKeyFollowsTheEditorAction() {
        val search = InputModes.of(text, EditorInfo.IME_ACTION_SEARCH)
        assertEquals(EnterAction.SEARCH, search.enter)
        assertEquals(EditorInfo.IME_ACTION_SEARCH, search.actionId)
        assertEquals(EnterAction.SEND, InputModes.of(text, EditorInfo.IME_ACTION_SEND).enter)
        assertEquals(EnterAction.NEWLINE, InputModes.of(text, EditorInfo.IME_ACTION_UNSPECIFIED).enter)
        assertEquals(
            EnterAction.NEWLINE,
            InputModes.of(text, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION).enter,
        )
    }

    @Test fun suggestionsAndAutocorrectFollowTheField() {
        assertTrue(InputModes.of(text, 0).suggestions)
        assertTrue(InputModes.of(text, 0).autocorrect)
        // Keep sets NO_SUGGESTIONS; like Gboard, the strip and autocorrect still work there.
        val noSuggestions = InputModes.of(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0)
        assertTrue(noSuggestions.suggestions)
        assertTrue(noSuggestions.autocorrect)
        assertTrue(noSuggestions.learning)
        assertFalse(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0).suggestions)
        assertTrue(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0).suggestions)
        assertFalse(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0).autocorrect)
        assertFalse(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_URI, 0).autocorrect)
        assertFalse(InputModes.of(InputType.TYPE_CLASS_NUMBER, 0).suggestions)
        assertFalse(InputModes.of(0, 0).suggestions) // TYPE_NULL: terminals and games
    }

    @Test fun incognitoFieldsHaveNoStripAndNoLearning() {
        val incognito = InputModes.of(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertFalse(incognito.suggestions)
        assertFalse(incognito.autocorrect)
        assertFalse(incognito.learning)
        assertTrue(InputModes.of(text, 0).learning)
    }

    @Test fun glideWorksInEveryVisibleTextFieldIncognitoToo() {
        assertTrue(InputModes.of(text, 0).glide)
        // Signal's compose field with "Incognito keyboard" on: textAutoCorrect|textCapSentences|textMultiLine, NO_ENTER_ACTION and the flag.
        val signal = InputModes.of(
            text or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
        assertTrue(signal.glide)
        assertTrue(signal.incognito)
        assertFalse(signal.learning)
        assertFalse(signal.suggestions)
        assertTrue(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0).glide)
        assertTrue(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).glide)
        assertTrue(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, 0).glide)
    }

    @Test fun noGlideInPasswordNumberPhoneOrNullFields() {
        assertFalse(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0).glide)
        assertFalse(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).glide)
        assertFalse(InputModes.of(InputType.TYPE_CLASS_NUMBER, 0).glide)
        assertFalse(InputModes.of(InputType.TYPE_CLASS_PHONE, 0).glide)
        assertFalse(InputModes.of(0, 0).glide) // TYPE_NULL: terminals and games
    }

    @Test fun passwordFieldsHaveNoStripAndNoLearning() {
        val password = InputModes.of(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0)
        assertFalse(password.suggestions)
        assertFalse(password.learning)
    }

    @Test fun onlyPlainMessageFieldsAreLearnedAsMessages() {
        assertTrue(InputModes.of(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_FLAG_NO_ENTER_ACTION).messages)
        assertTrue(InputModes.of(text, EditorInfo.IME_ACTION_SEND).messages)
        assertFalse(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0).messages)
        assertFalse(InputModes.of(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).messages)
        assertFalse(InputModes.of(text, EditorInfo.IME_ACTION_SEARCH).messages) // search boxes cleared with ✕
        assertFalse(InputModes.of(text, EditorInfo.IME_ACTION_GO).messages)
        assertFalse(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_FILTER, 0).messages)
        assertFalse(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS, 0).messages)
        assertFalse(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0).messages)
        assertFalse(InputModes.of(InputType.TYPE_CLASS_NUMBER, 0).messages) // OTP boxes that clear on a wrong code
        assertFalse(InputModes.of(InputType.TYPE_CLASS_PHONE, 0).messages)
    }

    @Test fun loginFieldsOfferThePasswordManager() {
        assertTrue(InputModes.login(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0), null))
        assertTrue(InputModes.login(InputModes.of(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0), null))
        // Plain-text fields (React Native, WebView) only say it in their hint.
        assertTrue(InputModes.login(InputModes.of(text, 0), "Email"))
        assertTrue(InputModes.login(InputModes.of(text, 0), "Enter your username"))
        assertTrue(InputModes.login(InputModes.of(text, 0), "كلمة المرور"))
        assertFalse(InputModes.login(InputModes.of(text, 0), "Search tickers"))
        assertFalse(InputModes.login(InputModes.of(text, 0), "Catalog index"))
        assertFalse(InputModes.login(InputModes.of(text, 0), null))
    }
}
