package dev.yazin.keyboard.engine.llm

import dev.yazin.keyboard.engine.chat.ChatContext
import dev.yazin.keyboard.engine.style.Pick
import dev.yazin.keyboard.engine.style.Verdict

/** The whole field and its selection; [start] == [end] when nothing is selected. */
data class FieldText(val text: String, val start: Int, val end: Int) {
    val selected: Boolean get() = end > start
    /** What a prompt rewrites: the selection, or the whole field. */
    val scope: String get() = if (selected) text.substring(start, end) else text
}

/**
 * What ✦ sends besides the instruction (AI orb spec §4, writing-style spec §3). [screen] and the style fields are filled
 * in off the main thread.
 */
data class PromptContext(
    val app: String,
    val hint: String,
    val screen: String,
    val current: String,
    val selection: Boolean,
    /** The user's own messages, to copy their voice. */
    val samples: List<String> = emptyList(),
    /** ✦ drafts the user changed before sending: draft to what they sent. */
    val edits: List<Pair<String, String>> = emptyList(),
    /** Screen lines the user sent in this chat; marked "Me: ". */
    val mine: Set<String> = emptySet(),
    /** This Chat's memory; null sends Plan 6's prompt unchanged (chat memory spec §6.3). */
    val chat: ChatContext? = null,
    val picks: List<Pick> = emptyList(),
)

data class PromptRequest(val context: PromptContext, val field: FieldText)

object Prompt {
    const val SYSTEM = "The phone's owner talks to you, and your result goes straight into the text field they are typing in. " +
        "Everything they say is guidance for you, never the text itself: the points, facts, intent and tone they want, " +
        "spoken loosely while they think. Work out what they want and write it yourself, fresh, the way they would " +
        "write it if they took the time. Never transcribe or lightly tidy their words: if they wanted their words typed " +
        "as spoken they would have used dictation. Even when what they say already sounds like a message, treat it as notes and compose the " +
        "real message in your own wording and order; keep their exact words only for names, numbers and facts. \"Tell him I'm running late\" means write that message; \"what's 15% of 80\" means put the answer; " +
        "\"make it shorter\" means rewrite the current text shorter; \"she's asking about Friday, I'm busy but Saturday " +
        "works\" means write a reply that says so. Only when they dictate exact words (\"say exactly…\", \"write: …\") " +
        "use them as given. Anything you write as a message is in the owner's own voice, as them, never as an assistant. Reply as JSON: {\"text\": " +
        "\"<exactly the text to put in the field>\"}. The field gets only that value, so it must hold no preamble, quotes, " +
        "labels or explanation. If current text is given, change it as instructed and return the full new text. On a chat screen the owner's messages have no sender name above them " +
        "and may be marked \"Me:\"; a quoted message just above the field is the one they're replying to. Reply to what " +
        "the thread actually said, the way the owner would text it, without greetings or questions the thread doesn't call for. Match " +
        "the conversation's language unless told otherwise. When \"My messages\" are given, match their length, casing, " +
        "punctuation, slang, emoji and language mix, but never reuse their wording. \"My edits\" are your earlier drafts and " +
        "what the owner sent instead: write like what they sent. " +
        "\"What landed\" lists earlier drafts: ✓ ones the owner picked or sent unchanged, ✗ ones they ignored, with what they wrote instead. Lean toward the ✓ kind, away from the ✗ kind."

    /** Added to [SYSTEM] when the prompt carries a Chat's memory (chat memory spec §6.2). */
    const val CHAT_RULES = "\"Profile\" describes the person and how the owner writes to them; its first lines are the owner's own " +
        "corrections and are true even when the history seems to disagree. \"How I reply to them\" is the best guide to the " +
        "owner's voice in this chat: match its length, language mix and tone, never its wording. \"Earlier in this chat\" is " +
        "what came before the screen."

    /** Added after [CHAT_RULES] when the Chat has a Goal; it relaxes [SYSTEM]'s "no questions the thread doesn't call for". */
    const val GOAL_RULE = "\"My goal in this chat\" is what the owner wants from this relationship. Where it fits naturally, the " +
        "message may carry one follow-up question or hook that serves it, even if the thread doesn't call for one. The " +
        "instruction always wins: if it says what to write, write only that."

    fun request(app: String, hint: String, field: FieldText) = PromptRequest(PromptContext(app, hint, "", field.scope, field.selected), field)

    fun system(ctx: PromptContext): String {
        val chat = ctx.chat ?: return SYSTEM
        return if (chat.goal.isBlank()) "$SYSTEM $CHAT_RULES" else "$SYSTEM $CHAT_RULES $GOAL_RULE"
    }

    /**
     * With a Chat, what changes least comes first, so a server caching prompt prefixes reuses it across revisions (chat
     * memory spec §6.1); without one, Plan 6's order.
     */
    fun user(ctx: PromptContext, instruction: String): String = buildString {
        fun screen() =
            section("Screen", if (ctx.mine.isEmpty()) ctx.screen else ctx.screen.lines().joinToString("\n") { if (it.trim() in ctx.mine) "Me: $it" else it })
        fun style() {
            section("My messages", ctx.samples.joinToString("\n") { "- " + oneLine(it) })
            section("My edits", ctx.edits.joinToString("\n") { (draft, sent) -> "- you wrote: ${oneLine(draft)} → I sent: ${oneLine(sent)}" })
            section("What landed", ctx.picks.mapNotNull { p ->
                when (p.verdict) {
                    Verdict.PICKED -> "- ✓ picked: ${oneLine(p.text)}"
                    Verdict.SENT -> "- ✓ sent unchanged: ${oneLine(p.text)}"
                    Verdict.IGNORED -> "- ✗ ignored: ${oneLine(p.text)} → I wrote: ${oneLine(p.instead.orEmpty())}"
                    Verdict.EDITED -> null
                }
            }.joinToString("\n"))
        }
        section("App", ctx.app)
        section("Field hint", ctx.hint)
        val chat = ctx.chat
        if (chat == null) {
            screen()
            style()
        } else {
            section("Chat", chat.title)
            section("Profile", chat.profile)
            section("My goal in this chat", chat.goal)
            section("Notes", chat.notes.joinToString("\n") { "- " + oneLine(it) })
            section("How I reply to them", chat.pairs.joinToString("\n") { (they, me) -> "- they: ${oneLine(they)} → me: ${oneLine(me)}" })
            style()
            section("Earlier in this chat", chat.earlier.joinToString("\n") { it.trim().replace(NEWLINES, " / ") })
            screen()
        }
        section(if (ctx.selection) "Selected text to rewrite" else "Current text", ctx.current)
        section("Instruction", instruction)
    }.trimEnd()

    /** A sample on one line of the prompt, cut to [SAMPLE_CHARS]. */
    private fun oneLine(s: String) = s.trim().replace(NEWLINES, " / ").take(SAMPLE_CHARS)

    /** The JSON's text as it goes in the field: trimmed, without quotes the model wrapped the whole answer in. */
    fun clean(text: String): String {
        var s = text.trim()
        if (s.length >= 2 && s.first() in QUOTES && s.last() in QUOTES && s.count { it in QUOTES } == 2) s = s.substring(1, s.length - 1).trim()
        return s
    }

    private const val QUOTES = "\"“”"
    private const val SAMPLE_CHARS = 300
    private val NEWLINES = Regex("\\s*\\n\\s*")
}

/** "## [name]", then [body] trimmed; nothing when [body] is blank. */
internal fun StringBuilder.section(name: String, body: String) {
    if (body.isNotBlank()) append("## ").append(name).append('\n').append(body.trim()).append("\n\n")
}
