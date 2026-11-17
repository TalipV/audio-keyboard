package dev.yazin.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.yazin.keyboard.keyboard.Field
import dev.yazin.keyboard.keyboard.Page

enum class EnterAction { NEWLINE, GO, SEARCH, SEND, NEXT, DONE, PREVIOUS }

data class InputMode(
    val page: Page,
    val field: Field,
    val enter: EnterAction,
    /** The raw IME action to perform when [enter] is not NEWLINE. */
    val actionId: Int,
    val password: Boolean,
    /** The strip shows suggestions (and words are composing text) here: any visible text field, except incognito. */
    val suggestions: Boolean = false,
    /** Words may be replaced on a separator (not in email/URL fields). */
    val autocorrect: Boolean = false,
    /** The app asked for no personalized learning (browsers' incognito tabs). */
    val incognito: Boolean = false,
    /**
     * Text sent here is a message worth learning (writing-style spec §2): a visible plain-text field, not a search box,
     * address bar, filter, postal address or number field, whose clearing would otherwise read as a send.
     */
    val messages: Boolean = false,
    /**
     * Glide typing works here (glide spec §3.1): any visible text field, incognito ones too. Gliding learns nothing, and
     * incognito only withholds learning, so Signal's "Incognito keyboard" does not turn it off.
     */
    val glide: Boolean = false,
) {
    /** Words, emoji recents and clips are remembered. */
    val learning: Boolean get() = !password && !incognito

    /** Only in visible text: a hidden "." in a password, or "555. " in a phone field, is a silent error. */
    val doubleSpacePeriod: Boolean get() = page == Page.LETTERS && !password
}

/** Reads an EditorInfo's inputType/imeOptions. Pure: the constants are compile-time ints. */
object InputModes {
    fun of(inputType: Int, imeOptions: Int): InputMode {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val text = cls == InputType.TYPE_CLASS_TEXT
        val page = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> Page.NUMBER
            InputType.TYPE_CLASS_PHONE -> Page.PHONE
            else -> Page.LETTERS
        }
        val field = when {
            text && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> Field.EMAIL
            text && variation == InputType.TYPE_TEXT_VARIATION_URI -> Field.URL
            else -> Field.TEXT
        }
        val password = (text && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val actionId = imeOptions and EditorInfo.IME_MASK_ACTION
        val enter = if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) EnterAction.NEWLINE else when (actionId) {
            EditorInfo.IME_ACTION_GO -> EnterAction.GO
            EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
            EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
            EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
            EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
            EditorInfo.IME_ACTION_PREVIOUS -> EnterAction.PREVIOUS
            else -> EnterAction.NEWLINE
        }
        val incognito = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        // TYPE_TEXT_FLAG_NO_SUGGESTIONS is only the app opting out of its own spell-check (Keep sets it); Gboard still
        // shows its strip and autocorrects there, so the flag is ignored.
        val suggestions = text && !password && !incognito
        val messages = suggestions && field == Field.TEXT && enter != EnterAction.SEARCH && enter != EnterAction.GO &&
            variation != InputType.TYPE_TEXT_VARIATION_FILTER && variation != InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
        val glide = text && !password
        return InputMode(page, field, enter, actionId, password, suggestions, suggestions && field == Field.TEXT, incognito, messages, glide)
    }

    /** A sign-in field, where the toolbar offers the password manager: a password or email field, or one whose hint asks for one. */
    fun login(mode: InputMode, hint: CharSequence?): Boolean =
        mode.password || mode.field == Field.EMAIL || (hint != null && LOGIN_HINT.containsMatchIn(hint))

    private val LOGIN_HINT = Regex("\\b(e-?mail|user ?name|log ?in|sign ?in|password)|البريد|اسم المستخدم|كلمة المرور", RegexOption.IGNORE_CASE)
}
