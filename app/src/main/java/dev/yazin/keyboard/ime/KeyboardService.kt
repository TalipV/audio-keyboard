package dev.yazin.keyboard.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.WindowManager
import android.view.KeyEvent
import android.view.View
import android.view.autofill.AutofillManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.view.WindowCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import dev.yazin.keyboard.BuildConfig
import dev.yazin.keyboard.R
import dev.yazin.keyboard.a11y.ScreenReader
import dev.yazin.keyboard.chat.ChatPrivacy
import dev.yazin.keyboard.chat.ChatUnlockActivity
import dev.yazin.keyboard.chat.ChatFiles
import dev.yazin.keyboard.chat.ProfileWorker
import dev.yazin.keyboard.engine.chat.Capture
import dev.yazin.keyboard.engine.chat.ChatContext
import dev.yazin.keyboard.engine.chat.ChatInfo
import dev.yazin.keyboard.engine.chat.ChatKey
import dev.yazin.keyboard.engine.chat.ChatMemory
import dev.yazin.keyboard.engine.chat.Message
import dev.yazin.keyboard.engine.chat.Note
import dev.yazin.keyboard.engine.voice.VoiceStatus
import dev.yazin.keyboard.keyboard.ChatSheet
import dev.yazin.keyboard.engine.chat.ScreenChat
import dev.yazin.keyboard.engine.chat.Side
import dev.yazin.keyboard.engine.llm.Prompt
import dev.yazin.keyboard.engine.llm.FieldText
import dev.yazin.keyboard.engine.llm.LlmCall
import dev.yazin.keyboard.engine.llm.LlmCancel
import dev.yazin.keyboard.engine.llm.LlmClient
import dev.yazin.keyboard.engine.llm.LlmResult
import dev.yazin.keyboard.engine.llm.ReplyDrafts
import dev.yazin.keyboard.llm.LlmLogFile
import dev.yazin.keyboard.engine.llm.PromptContext
import dev.yazin.keyboard.engine.llm.ScreenNode
import dev.yazin.keyboard.engine.llm.ScreenText
import dev.yazin.keyboard.engine.style.SendWatch
import dev.yazin.keyboard.style.StyleFile
import dev.yazin.keyboard.style.PicksFile
import dev.yazin.keyboard.engine.llm.PromptRequest
import dev.yazin.keyboard.keyboard.VoiceInput
import dev.yazin.keyboard.engine.Autocorrect
import dev.yazin.keyboard.engine.Candidate
import dev.yazin.keyboard.engine.Suggestions
import dev.yazin.keyboard.engine.GlidePoint
import dev.yazin.keyboard.engine.Touch
import dev.yazin.keyboard.engine.voice.FieldTargets
import dev.yazin.keyboard.clip.ClipFile
import dev.yazin.keyboard.clip.ClipImages
import dev.yazin.keyboard.clip.ClipboardWatcher
import dev.yazin.keyboard.emoji.EmojiCatalog
import dev.yazin.keyboard.engine.clip.ClipStore
import dev.yazin.keyboard.keyboard.ClipItem
import dev.yazin.keyboard.keyboard.ClipboardSheet
import dev.yazin.keyboard.keyboard.ToolbarInput
import dev.yazin.keyboard.emoji.EmojiStore
import dev.yazin.keyboard.keyboard.Dimens
import dev.yazin.keyboard.keyboard.EmojiSearchBar
import dev.yazin.keyboard.keyboard.EmojiSheet
import dev.yazin.keyboard.keyboard.Field
import dev.yazin.keyboard.keyboard.Key
import dev.yazin.keyboard.keyboard.KeyboardColors
import dev.yazin.keyboard.keyboard.KeyAction
import dev.yazin.keyboard.keyboard.KeyboardView
import dev.yazin.keyboard.keyboard.Lang
import dev.yazin.keyboard.keyboard.LayoutSpec
import dev.yazin.keyboard.keyboard.Layouts
import dev.yazin.keyboard.keyboard.Page
import dev.yazin.keyboard.keyboard.StripItem
import dev.yazin.keyboard.keyboard.Themes
import dev.yazin.keyboard.keyboard.TouchConfig
import dev.yazin.keyboard.keyboard.TouchEvent
import dev.yazin.keyboard.settings.Prefs
import dev.yazin.keyboard.settings.SettingsActivity
import dev.yazin.keyboard.settings.imeId
import dev.yazin.keyboard.voice.VoiceController
import java.time.ZoneId
import java.util.Locale

class KeyboardService : InputMethodService(), Editor.Host {
    private lateinit var prefs: Prefs
    private lateinit var feedback: Feedback
    private lateinit var engine: TextEngine
    private val editor = Editor(this)
    private val shift = ShiftState()
    private var view: KeyboardView? = null
    private var emojiSheet: EmojiSheet? = null
    /** While shown (KeyboardView.searchBar), the keys type its query; nothing reaches the field but a picked emoji. */
    private var emojiSearch: EmojiSearchBar? = null
    private lateinit var clips: ClipStore
    private lateinit var clipWatcher: ClipboardWatcher
    private var clipSheet: ClipboardSheet? = null
    /** Hides the clipboard chip when its minute is up. */
    private val chipExpiry = Runnable { updateStrip() }
    private var lang = Lang.EN
    private var page = Page.LETTERS
    private var previousPage = Page.LETTERS
    private var typedSymbol = false
    private var mode = InputModes.of(0, 0)
    private var inputType = 0
    /** Opens the autofill provider's app (1Password) from the toolbar key, for fields its chips miss. */
    private var passwordApp: Intent? = null
    private var learning = true
    private var suggestions: Suggestions? = null
    /** A glide is in progress: its previews may still show (glide spec §3.2). */
    private var glideActive = false
    private var lastGlidePreview = 0L
    /** The strip after a glide: the next-best words, cased like the glided one. */
    private var glideAlternatives: List<String> = emptyList()
    /** Typed words whose autocorrect the user undid this session; never corrected again. */
    private val rejected = HashSet<String>()
    /** Bumped per autofill response and field, so a late inflation can't show chips for another field. */
    private var autofillGeneration = 0
    /** Notices sent messages for the writing style (writing-style spec §2). */
    private val sendWatch = SendWatch()
    /** The field as ✦'s last answer left it, until it is sent or the field changes: sent unedited, it isn't learned. */
    private var aiDraft: String? = null
    /** Reads the field once editing pauses, so a send can be told from what was there. */
    /** Which field [aiDraft] was written in: a WebView restarts input between its fields without a new session. */
    private var aiDraftIn = ""
    private val readField = Runnable { if (messageField() && prefs.style) sendWatch.read(editor.peekText(), fieldKey()) }
    private val main = Handler(Looper.getMainLooper())
    /** The Chat the keyboard opened in, from the read [CHAT_READ_DELAY_MS] after it showed; used up on hide. Chat thread. */
    private var chatAtStart: ChatKey? = null
    /**
     * The opening read (chat memory spec §4.2): which Chat this is, for the chip, and its visible messages. With
     * Remember chats off only the title is read, and nothing is kept.
     */
    private val chatRead = Runnable {
        val app = chatApp() ?: return@Runnable
        val rtl = lang.rtl
        val remember = prefs.chatsRemember
        val generation = chatGeneration
        ChatFiles.later {
            val read = if (remember) readChat(app, rtl) else ScreenReader.title()?.let { chatKey(app, it) }?.let { it to null }
            val key = read?.first
            val messages = read?.second
            if (remember) {
                chatAtStart = key
                if (key != null && messages != null) ChatFiles.get(this).capture(key, messages)
            }
            main.post { if (generation == chatGeneration) showChat(key) }
        }
    }
    private var repliesCancel: LlmCancel? = null
    private var repliesGeneration = 0
    private var repliesScreen: Pair<ChatKey, String>? = null
    /** The replies waiting in the strip, or null. Main thread. */
    private var waitingReplies: List<String>? = null
    /** Survives typing until send: choosing one's own text is an ignored verdict (Picks spec §2). */
    private var shownReplies: List<String>? = null
    private var shownRepliesField = ""
    /** False once this visit's prefetch was cancelled (typing, ✦, a sheet), so a late chat read can't re-arm it. */
    private var repliesArmed = false
    private val repliesDrafted = HashSet<Pair<ChatKey, String>>()
    private val repliesPrefetch = Runnable {
        val key = chat
        if (BuildConfig.DEBUG) Log.d(TAG, "replies prefetch: chat=$key waiting=${prefs.repliesWaiting} remember=${prefs.chatsRemember} learning=$learning password=${mode.password} screen=${ScreenReader.enabled} field=${editor.peekText()?.length}")
        if (prefs.repliesWaiting && prefs.chatsRemember && key != null && learning && !mode.password &&
            ScreenReader.enabled && editor.peekText()?.isEmpty() == true) {
            val generation = repliesGeneration
            val rtl = lang.rtl
            val hint = currentInputEditorInfo?.hintText?.toString().orEmpty()
            ChatFiles.later {
                val info = ChatFiles.get(this).info(key)
                main.post {
                    if (generation != repliesGeneration) return@post
                    if (info?.remember != true) return@post logSilentReplies("the chat is not remembered")
                    if (!ReplyDrafts.shouldDraft(info.count, editor.peekText()?.isEmpty() == true, true, false))
                        return@post logSilentReplies(if (info.count == 0) "the history is empty" else "the field is not empty")
                    repliesScreen = null
                    val cancel = LlmCancel()
                    repliesCancel = cancel
                    val drafted = repliesDrafted.toSet()
                    Thread({
                        var failed: String? = null // why the call gave no text: an exception or an LlmResult.Failed
                        var silent = false // a rule already logged why it stays silent: the empty result is not the model's
                        val raw = runCatching {
                            val root = ScreenReader.readBoth()?.second
                            // The newest message comes from the screen; where the app's screen can't be parsed, History's
                            // last message decides instead (spec §4), so unreadable builds still draft.
                            val newest = root?.let { chatMessages(key.app, it, rtl) }?.lastOrNull()
                                ?: ChatFiles.get(this).lastMessage(key)
                            val screen = newest?.let { key to "${it.side}|${it.sender}|${it.text}" }
                            // Visit state and the field belong to the main thread.
                            main.post {
                                if (generation != repliesGeneration) return@post
                                repliesScreen = screen
                                if (BuildConfig.DEBUG) Log.d(TAG, "replies screen: $screen")
                            }
                            // A readable screen must still show the same Chat; an unreadable one can't disprove it.
                            if (!ReplyDrafts.shouldDraft(info.count, true, newest?.side == Side.THEM, screen in drafted)) {
                                silent = true
                                logSilentReplies(if (screen in drafted) "already drafted for this message" else "the newest message is mine", newest)
                                return@runCatching ""
                            }
                            if (root != null && chatKey(key.app, ScreenText.title(root).orEmpty()) != key) {
                                silent = true
                                logSilentReplies("the screen shows another chat", newest)
                                return@runCatching ""
                            }
                            val ctx = voiceHost.withMemory(Prompt.request(key.app, hint, FieldText("", 0, 0)).context)
                            if (cancel.cancelled) { failed = LlmClient.CANCELLED; return@runCatching "" }
                            val req = ReplyDrafts.request(ctx)
                            val record = LlmLogFile.record(this)
                            val client = prefs.llm { call -> if (!cancel.cancelled) record(call) }
                            when (val result = client.complete(
                                prefs.llmModel, req.system, req.user, "replies",
                                maxTokens = ReplyDrafts.MAX_TOKENS, readTimeoutMs = ReplyDrafts.READ_TIMEOUT_MS,
                                cancel = cancel, format = ReplyDrafts.SCHEMA,
                            )) {
                                is LlmResult.Text -> result.text
                                is LlmResult.Failed -> { failed = result.message; "" }
                            }
                        }.getOrElse { failed = "${it.javaClass.simpleName}: ${it.message?.take(100)}"; "" }
                        main.post {
                            if (generation != repliesGeneration) return@post
                            repliesCancel = null
                            val replies = ReplyDrafts.parse(raw)
                            if (BuildConfig.DEBUG) Log.d(TAG, "replies parsed: ${replies.size}")
                            if (replies.isEmpty() && !silent) logSilentReplies(
                                failed?.let { "the reply call failed — $it" } ?: "the model returned no usable replies",
                            )
                            // The field may have been filled meanwhile (a paste): replies only ever wait on an empty field.
                            waitingReplies = replies.takeIf { it.isNotEmpty() && editor.peekText()?.isEmpty() == true }
                            if (replies.isNotEmpty()) repliesScreen?.let { repliesDrafted.add(it) }
                            updateStrip()
                        }
                    }, "llm").start()
                }
            }
        } else if (key != null) {
            logSilentReplies(when {
                !prefs.repliesWaiting -> "the Waiting replies switch is off"
                !prefs.chatsRemember -> "chat memory is off"
                !learning -> "learning is off"
                mode.password -> "this is a password field"
                !ScreenReader.enabled -> "screen reading is off"
                else -> "the field is not empty"
            })
        }
    }

    /** Why replies stayed silent, in the Debug log (Settings → AI writing): the rule that fired and the newest message as parsed. */
    private fun logSilentReplies(rule: String, newest: Message? = null) {
        val call = LlmCall(
            LlmCall.newId(), System.currentTimeMillis(), "replies", "", "",
            "", "## Instruction\nreplies silent: $rule", "", 0, "", false,
            newest?.let { "newest as parsed: ${it.side} · ${it.sender.ifBlank { "(no sender)" }} · ${it.text.take(80)}" }
                ?: "newest as parsed: none",
            0,
        )
        Thread({ LlmLogFile.record(this)(call) }, "replies-log").start()
    }

    private fun stopReplies() {
        if (BuildConfig.DEBUG && (repliesCancel != null || waitingReplies != null)) Log.d(TAG, "replies stopped")
        main.removeCallbacks(repliesPrefetch)
        repliesGeneration++
        repliesArmed = false
        if (waitingReplies != null) {
            waitingReplies = null
            updateStrip() // no stale reply stays tappable in the strip
        }
        val cancel = repliesCancel
        repliesCancel = null
        // Flag first, on this thread, so the log and result guards see the cancel; disconnect off it.
        if (cancel != null) {
            cancel.softCancel()
            Thread(cancel::cancel, "replies-cancel").start()
        }
    }

    /** A waiting reply tapped in the strip: it goes in as an ✦ answer would (waiting-replies spec §2). */
    private fun onReplyTap(reply: String) {
        val f = editor.field() ?: return
        editor.replace(f, 0, f.text.length, reply)
        aiDraft = reply.also { StyleFile.get(this).answered(it) }
        aiDraftIn = fieldKey()
        waitingReplies = null
        afterEdit()
        view?.orbUndo = editor.canUndoReplace()
    }

    /** Profiles start a while after the keyboard hides: a field switch hides and shows it again at once. */
    private val profileStart = Runnable { ProfileWorker.start(this) }
    private var chatSheet: ChatSheet? = null
    private var cancelChatUnlock: (() -> Unit)? = null
    private var pendingChatOpen: ChatKey? = null
    private var pendingChatField: Pair<String, Int>? = null
    private var chatAuthenticated = false
    private fun cancelChatOpen() {
        cancelChatUnlock?.invoke()
        cancelChatUnlock = null
        pendingChatOpen = null
        pendingChatField = null
        chatAuthenticated = false
    }
    private fun resumeChatOpen() {
        val key = pendingChatOpen ?: return
        val field = currentInputEditorInfo?.let { it.packageName to it.fieldId }
        if (chatAuthenticated && ChatPrivacy.unlocked() && isInputViewShown && chat == key && field == pendingChatField) {
            cancelChatOpen()
            openChat(fresh = true)
        }
    }
    private val lockChat = {
        cancelChatOpen()
        chatInfo = null
        if (view?.sheet === chatSheet) closeSheet()
    }
    /** The Chat the keyboard is in, from the opening read: the chip's and the panel's. Main thread. */
    private var chat: ChatKey? = null
    /** [chat]'s memory as last loaded on the Chat thread, for the panel. Main thread. */
    private var chatInfo: ChatInfo? = null
    /** Bumped per field and hide, so an opening read that lands late can't name the Chat of another field. */
    private var chatGeneration = 0
    /** The panel's prompt for the memo being dictated, shown until speech starts. */
    private var memoCaption: String? = null
    /** The Chat being recorded (spec §4.2, Record), or null. Main thread. */
    private var recording: ChatKey? = null
    private var recordReadAt = 0L
    /** A recording read is waiting for its slot ([recordPosted]), or running on the Chat thread ([recordBusy]). */
    private var recordPosted = false
    private var recordBusy = false
    /** Events came in while a read ran: read once more when it is done. */
    private var recordAgain = false
    private val recordRead = Runnable {
        recordPosted = false
        val key = recording ?: return@Runnable
        if (recordBusy) {
            recordAgain = true
            return@Runnable
        }
        recordBusy = true
        recordReadAt = SystemClock.uptimeMillis()
        val rtl = lang.rtl
        ChatFiles.later {
            var count = -1
            try {
                val read = readChat(key.app, rtl)
                val memory = ChatFiles.get(this)
                // Another Chat on screen (the owner backed out mid-recording) isn't stored under this one.
                if (read?.first == key) read.second?.let { memory.capture(key, it) }
                count = memory.info(key)?.count ?: 0
            } finally {
                main.post {
                    recordBusy = false
                    if (recording != key) return@post
                    if (count >= 0) view?.recordCaption = getString(R.string.chat_recording, count)
                    if (recordAgain) {
                        recordAgain = false
                        onRecordEvent()
                    }
                }
            }
        }
    }
    private val chatActions = object : ChatSheet.Actions {
        override fun goal(preset: String?) {
            if (preset == null) memo(R.string.chat_say_goal) { m, key, text -> m.setGoal(key, text) }
            else changeChat { m, key -> m.setGoal(key, preset) }
        }
        override fun correction() = memo(R.string.chat_say_correction) { m, key, text -> m.addCorrection(key, text) }
        override fun regenerate() {
            val key = chat ?: return
            ChatFiles.later {
                ChatFiles.get(this@KeyboardService).markDue(key)
                main.post { ProfileWorker.writeNow(this@KeyboardService, key) }
            }
        }
        override fun note() = memo(R.string.chat_say_note) { m, key, text -> m.addNote(key, text) }
        override fun removeNote(n: Note) = changeChat { m, key -> m.removeNote(key, n) }
        override fun record() = startRecording()
        override fun importSteps() {
            chatSheet?.showSteps()
        }
        override fun remember(on: Boolean) = changeChat { m, key -> m.setRemember(key, on) }
        override fun forget() = changeChat { m, key ->
            m.forget(key)
            ChatFiles.save(this@KeyboardService)
        }
        override fun back() = closeSheet()
        override fun canRecord() = prefs.chatsRemember
    }

    private lateinit var voice: VoiceController
    /** The field the keyboard is showing for, "package/fieldId" (spec §7.3); null while hidden. */
    private var voiceTarget: String? = null
    private val fieldTargets = FieldTargets()
    private val voiceHost = object : VoiceController.Host {
        override fun target(): String? = voiceTarget
        override fun language(): String {
            val enabled = enabledSubtypes().map(::langOf)
            return (listOf(lang) + enabled).distinct().joinToString(",") { it.code }
        }
        // Incognito and password fields: their text isn't sent as context or kept on disk with the audio.
        override fun textBeforeCursor(): String =
            if (!learning) "" else currentInputConnection?.getTextBeforeCursor(200, 0)?.toString().orEmpty()
        override fun hotwords(language: String): String? {
            if (!prefs.voiceHotwords) return null
            val langs = language.split(',').map { Lang.of(it) }
            // Learned words the dictionary already knows would crowd out the names and jargon Whisper misses.
            return langs.mapNotNull { l -> engine.history(l).hotwords(200 / langs.size) { engine.isWord(l, it) || engine.isWord(l, it.lowercase()) } }
                .joinToString(", ").ifEmpty { null }
        }
        // Every Latin-script language counts, so German speech isn't taken for romanized Arabic.
        override fun latinWord(word: String) = Lang.values().any { !it.rtl && engine.isWord(it, word) }
        override fun insert(text: String, group: String?) {
            finishWord(correct = false)
            // Whisper writes lowercase without context (hotwords set its style), so a sentence start gets the typing capital.
            val caps = prefs.autoCaps && !mode.password && editor.capsWanted(inputType)
            editor.insertDictation(if (caps) text.trimStart().replaceFirstChar { it.uppercaseChar() } else text, group) { engine.isWord(if (lang.cased) lang else Lang.EN, it) }
            afterEdit()
            view?.orbUndo = editor.canUndoReplace()
        }
        override fun haptic() = feedback.voiceTick()
        override fun promptRequest(): PromptRequest? {
            if (!learning || mode.password) return null // ✦ is hidden there; never read such a field
            finishWord(correct = false)
            val field = editor.field() ?: return null
            val info = currentInputEditorInfo
            // The package name, not the label: an IME may not see other apps' labels (package visibility), and the LLM knows them.
            return Prompt.request(info?.packageName.orEmpty(), info?.hintText?.toString().orEmpty(), field, languageName(lang))
        }
        override fun withMemory(context: PromptContext): PromptContext {
            val (text, root) = ScreenReader.readBoth() ?: ("" to null)
            val ctx = context.copy(screen = text)
            if (!prefs.style) return ctx
            val title = root?.let(ScreenText::title).orEmpty()
            // Chat memory failing (a file that can't be read) leaves Plan 6's prompt, as in a Chat without memory.
            val (chat, mineOnScreen) = runCatching { chatMemory(root, title, text) }
                .onFailure { Log.w(TAG, "chat memory failed", it) }
                .getOrNull() ?: (null to emptyList())
            val store = StyleFile.get(this@KeyboardService)
            return ctx.copy(
                samples = store.pick(ctx.app, title, text, if (chat?.pairs.isNullOrEmpty()) PICK_ALONE else PICK_WITH_PAIRS).map { it.text },
                edits = store.edits(ctx.app, title).mapNotNull { s -> s.draft?.let { it to s.text } },
                mine = store.mine(ctx.app, title) + mineOnScreen,
                chat = chat,
                picks = PicksFile.get(this@KeyboardService).pick(ctx.app, title), // stored under the raw title, like samples
            )
        }
        override fun replace(request: PromptRequest, text: String): Boolean {
            val f = request.field
            if (editor.field()?.text != f.text) return false // edited meanwhile: the answer becomes a chip
            if (f.selected) editor.replace(f, f.start, f.end, text) else editor.replace(f, 0, f.text.length, text)
            aiDraft = (if (f.selected) f.text.replaceRange(f.start, f.end, text) else text)
                .also { StyleFile.get(this@KeyboardService).answered(it) }
            aiDraftIn = fieldKey()
            afterEdit()
            view?.orbUndo = editor.canUndoReplace()
            return true
        }
        override fun voiceChanged() {
            val ui = voice.ui
            if (ui.orb) {
                stopRecording() // the band shows one thing at a time
                closeEmojiSearch(toPanel = false)
                view?.sheet = null
                view?.screenContext = ScreenReader.enabled
                if (view?.voice?.orb != true) view?.orbUndo = editor.canUndoReplace() // the field may have been edited since
            }
            val prompt = memoCaption?.takeIf { voice.memoing && ui.status == VoiceStatus.SPEAK_NOW }
            view?.voice = if (prompt != null) ui.copy(caption = prompt) else ui
            updateStrip()
            clipSheet?.refresh()
        }
        override fun voiceLevel(level: Float) {
            view?.voiceLevel = level
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        ChatPrivacy.initialize(this)
        ChatPrivacy.observeLock(lockChat)
        feedback = Feedback(this)
        engine = TextEngine(this)
        EmojiCatalog.preload(this)
        clips = ClipFile.get(this)
        clipWatcher = ClipboardWatcher(this, clips) {
            updateStrip()
            clipSheet?.refresh()
        }
        voice = VoiceController(this, voiceHost)
    }

    override fun onDestroy() {
        cancelChatOpen()
        ChatPrivacy.stopObserving(lockChat)
        main.removeCallbacks(chatRead)
        stopReplies()
        main.removeCallbacks(profileStart)
        stopRecording()
        clipWatcher.enabled = false
        voice.destroy()
        engine.close()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, ::onTouch, ::openSettings, ::onStrip, ::onDelete, onVoice = ::onVoice, onToolbar = ::onToolbar)
        view = v
        emojiSheet = EmojiSheet(
            v, v.iconPainter, EmojiCatalog::get, EmojiStore.get(this), remember = { learning }, onEmoji = ::onEmoji,
            onAbc = ::closeSheet, onDelete = ::onDelete, onSearch = ::openEmojiSearch,
        )
        emojiSearch = EmojiSearchBar(
            v, v.iconPainter, EmojiCatalog::get, EmojiCatalog::index, EmojiStore.get(this), remember = { learning },
            onEmoji = ::onEmoji, onBack = { closeEmojiSearch(toPanel = true) },
        )
        clipSheet = ClipboardSheet(v, v.iconPainter, ::clipItems, { prefs.clipboard }, ::onClipPaste, ::onClipPin, ::onClipDelete, onBack = ::closeSheet)
        chatSheet = ChatSheet(v, v.iconPainter, { chatInfo?.takeIf { it.key == chat && ChatPrivacy.unlocked() } }, chatActions)
        return FrameLayout(this).apply {
            addView(v)
            addView(v.autofill)
        }
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        stopReplies()
        // A new field (not a restart, and not the keyboard shown again after BACK): what was read and drafted there is gone.
        if (shownReplies != null && fieldKey() != shownRepliesField) shownReplies = null // a restart can switch WebView fields
        if (!restarting) {
            repliesDrafted.clear()
            repliesScreen = null
            shownReplies = null
            sendWatch.reset()
            aiDraft = null
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        clips.lifetimeMs = prefs.clipLifetimeMs // the owner may have changed it in Settings
        // Each app decides whether its navigation bar shows: ask again, or the keys can end up under it.
        view?.let { androidx.core.view.ViewCompat.requestApplyInsets(it) }
        if (pendingChatOpen != null && pendingChatField != (info.packageName to info.fieldId)) cancelChatOpen()
        main.removeCallbacks(profileStart)
        ProfileWorker.stop() // frees a one-slot server for ✦
        // Apps restart the same field (e.g. after sending); keep page, caps lock and undo state then.
        val sameField = restarting && info.inputType == inputType
        // Apps that restart the field on send (Google Messages) are caught here; SendWatch checks it is the same field.
        if (restarting && info.initialSelStart == 0) checkSent()
        inputType = info.inputType
        mode = InputModes.of(info.inputType, info.imeOptions)
        editor.multiline = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        editor.composingEnabled = mode.suggestions
        editor.autoSpace = mode.field == Field.TEXT // glide spec §3.3: no automatic spaces in email and URL fields
        editor.start(info.initialSelStart, sameField)
        learning = mode.learning
        voiceTarget = fieldTargets.start(info.packageName, info.fieldId, sameField)
        view?.showMic = !mode.password // Gboard hides the mic in password fields (§7.5)
        view?.showSpark = !mode.password && learning // AI orb spec §2: never in password or incognito fields
        // Sign-in fields always get the key (and the provider's chips in the strip); elsewhere only if the owner asked.
        passwordApp = if (InputModes.login(mode, info.hintText) || prefs.passwordKeyAlways) launchablePasswordApp() else null
        view?.showPasswords = passwordApp != null
        view?.orbUndo = editor.canUndoReplace()
        voice.onKeyboardShown()
        view?.voice = voice.ui
        lang = langOf(getSystemService(InputMethodManager::class.java).currentInputMethodSubtype)
        chatGeneration++
        if (!sameField) {
            stopRecording()
            showChat(null) // until this field's opening read names its Chat
            closeSheet()
            view?.searchBar = null
            shift.reset()
            page = mode.page
            previousPage = page
            rejected.clear()
        }
        feedback.hapticLevel = prefs.haptics
        feedback.sound = prefs.sound
        clipWatcher.enabled = prefs.clipboard
        refresh()
        afterEdit()
        main.removeCallbacks(chatRead)
        stopReplies()
        // Every show may draft once its opening read names the Chat — WhatsApp restarts the field as the
        // keyboard opens, and disarming on any restart silenced the visit entirely. Only a restart behind an
        // open sheet or the orb keeps the visit's cancellation (review finding: no prefetch competing with ✦).
        if (view?.sheet == null && view?.voice?.orb != true) repliesArmed = true
        main.postDelayed(chatRead, CHAT_READ_DELAY_MS) // once the chat's layout has settled
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (view?.sheet === chatSheet) closeSheet()
        view?.cancelTouches()
        view?.searchBar = null // the next show's refresh brings the field's own keys back
        voice.onKeyboardHidden()
        voiceTarget = null
        main.removeCallbacks(chatRead)
        stopReplies()
        shownReplies = null
        repliesDrafted.clear()
        repliesScreen = null
        chatGeneration++
        stopRecording()
        val app = if (prefs.chatsRemember) chatApp() else null
        val rtl = lang.rtl
        ClipFile.save(this) // drops clips past their hour from disk
        EmojiStore.save(this)
        StyleFile.save(this)
        PicksFile.save(this)
        ChatFiles.later {
            val atStart = chatAtStart
            chatAtStart = null
            val read = app?.let { readChat(it, rtl) }
            Capture.target(atStart, read?.first)?.let { key -> read?.second?.let { ChatFiles.get(this).capture(key, it) } }
            ChatFiles.save(this)
        }
        main.removeCallbacks(profileStart)
        main.postDelayed(profileStart, PROFILE_DELAY_MS)
        engine.save()
    }

    override fun onFinishInput() {
        currentInputConnection?.finishComposingText()
        autofillGeneration++
        view?.setAutofill(emptyList(), emptyList())
        super.onFinishInput()
    }

    /** Asks autofill providers (1Password, Google) for chips in the strip, as Gboard does (Android 11+). */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest =
        InlineAutofill.request(this, palette(), lang.rtl)

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val generation = ++autofillGeneration
        InlineAutofill.inflate(this, response) { pinned, chips ->
            if (generation == autofillGeneration) view?.setAutofill(pinned, chips)
        }
        return true
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        finishWord(correct = false)
        lang = langOf(newSubtype)
        if (page != Page.NUMBER && page != Page.PHONE) page = Page.LETTERS
        refresh()
        afterEdit()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Before the echo check: after the keyboard's own Enter key event the cursor is unknown, and the app may have sent.
        if (newSelStart == 0 && newSelEnd == 0) checkSent()
        if (editor.onSelectionChanged(newSelStart, newSelEnd, composing = candidatesStart in 0 until candidatesEnd)) {
            if (newSelStart == newSelEnd && voice.ui.orb) voice.onInput(VoiceInput.Dismiss)
            editor.resumeWord()
            afterEdit()
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val v = view ?: return
        if (!v.isLaidOut) return
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        if (v.orbShown) {
            // The app ends at the orb's centre, so half the orb floats over it and the field above stays clear. Only the
            // controls take touches.
            val top = loc[1] + v.orbEdge
            outInsets.contentTopInsets = top
            outInsets.visibleTopInsets = top
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            v.orbTouchable(outInsets.touchableRegion)
            outInsets.touchableRegion.translate(loc[0], loc[1])
            return
        }
        val top = loc[1] + v.keyboardTop
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, top, v.width, loc[1] + v.height)
    }

    override fun connection(): InputConnection? = currentInputConnection
    override fun sendKey(keyCode: Int) = sendDownUpKeyEvents(keyCode)
    override fun sendEnterKey() = sendKeyChar('\n')

    private fun refresh() {
        val v = view ?: return
        val colors = palette()
        val dark = colors === Themes.DARK
        v.colors = colors
        window.window?.let { w ->
            @Suppress("DEPRECATION")
            w.navigationBarColor = colors.background
            WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars = !dark
        }
        val searching = searching()
        // A search query is plain text whatever the field takes.
        val spec = LayoutSpec(lang, page, if (searching) Field.TEXT else mode.field, prefs.numberRow || mode.password, prefs.easternDigits, prefs.emojiKey)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val scale = prefs.heightScale * if (landscape) Dimens.LANDSCAPE_KEY_SCALE else 1f
        v.landscape = landscape
        v.rtl = lang.rtl
        v.spaceLabel = labelOf(lang)
        val previous = languageAfter(-1)
        val next = languageAfter(1)
        v.spaceNeighbours = if (prefs.spaceSwipe && previous != null && next != null) labelOf(previous) to labelOf(next) else null
        v.enterAction = if (searching) EnterAction.SEARCH else mode.enter
        v.showPreview = prefs.keyPreview
        val glide = prefs.glide && mode.glide && page == Page.LETTERS && !searching
        v.touchConfig = TouchConfig(
            longPressMs = prefs.longPressMs,
            glideStartPx = if (glide) Dimens.GLIDE_START * resources.displayMetrics.density else Float.POSITIVE_INFINITY,
            spaceSwipeStartPx = if (v.spaceNeighbours != null) Dimens.SPACE_SWIPE_START * resources.displayMetrics.density else Float.POSITIVE_INFINITY,
            spaceCursor = prefs.spaceCursor,
            cursorStepPx = Dimens.CURSOR_STEP * resources.displayMetrics.density,
            cursorLinePx = Dimens.CURSOR_LINE * resources.displayMetrics.density,
        )
        val referencePage = if (mode.page == Page.LETTERS || searching) Page.LETTERS else mode.page
        v.setLayout(Layouts.build(spec), Layouts.build(spec.copy(page = referencePage, field = Field.TEXT)), scale)
        // The search's keys sit a row lower; autocorrect's touch model keeps the keyboard's own.
        if (page == Page.LETTERS) v.post { if (!searching()) v.keyModel()?.let { engine.setKeyboard(lang, it) } }
        refreshCaps()
    }

    private fun onTouch(ev: TouchEvent) {
        when (ev) {
            is TouchEvent.Press -> feedback.keyDown(ev.key.key.action)
            is TouchEvent.Commit -> onKey(ev.key.key, Touch(ev.x, ev.y))
            is TouchEvent.Repeat -> onDelete()
            is TouchEvent.LongPress -> when {
                ev.key.key.action == KeyAction.SPACE -> getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                ev.key.key.longPress == KeyAction.EMOJI -> openEmoji()
            }
            is TouchEvent.CursorStart -> {
                finishWord(correct = false) // the composing word is committed as typed, so arrows move a plain cursor
                feedback.voiceTick()
                view?.cursorCaption = getString(R.string.cursor_control)
            }
            is TouchEvent.CursorMove -> moveCursor(ev.dx, ev.dy)
            TouchEvent.CursorEnd -> {
                view?.cursorCaption = null
                afterEdit(edited = false)
            }
            is TouchEvent.SpaceSwipe -> if (ev.direction != 0) switchLanguage(ev.direction)
            is TouchEvent.CommitAlt -> onText(ev.text, null)
            is TouchEvent.ModifierUp -> onModifierUp(ev.key.key, ev.used)
            is TouchEvent.Glide -> if (ev.ended) onGlideEnd(ev.points) else onGlideMove(ev.points)
            TouchEvent.GlideCancelled -> { glideActive = false; updateStrip() }
            else -> Unit
        }
    }

    private fun onKey(key: Key, touch: Touch) {
        stopReplies()
        if (searching() && onSearchKey(key)) return
        when (key.action) {
            KeyAction.TEXT -> onText(key.output, touch)
            KeyAction.SPACE -> {
                onSeparator(" ")
                // Like Gboard: after typing symbols, space returns to letters (digits alone don't count).
                if (typedSymbol && (page == Page.SYMBOLS || page == Page.SYMBOLS_MORE) && mode.page == Page.LETTERS) setPage(Page.LETTERS)
            }
            KeyAction.DELETE -> onDelete()
            KeyAction.ENTER -> {
                finishWord(correct = false) // spec §6.4: only space and punctuation autocorrect; Send must not submit a surprise
                editor.enter(mode.enter, mode.actionId)
                afterEdit(edited = mode.enter == EnterAction.NEWLINE && editor.multiline) // Send/Go leave the text for the app to send
            }
            KeyAction.SHIFT -> { shift.onShiftDown(SystemClock.uptimeMillis()); refreshCaps() }
            KeyAction.SYMBOLS -> setPage(Page.SYMBOLS)
            KeyAction.SYMBOLS_MORE -> setPage(Page.SYMBOLS_MORE)
            KeyAction.NUMPAD -> setPage(Page.NUMBER)
            KeyAction.ALPHA -> setPage(Page.LETTERS)
            KeyAction.EMOJI -> openEmoji()
        }
    }

    /** The live preview: the best word so far, alone in the strip (glide spec §3.2). */
    private fun onGlideMove(points: List<GlidePoint>) {
        stopReplies()
        glideActive = true
        val now = SystemClock.uptimeMillis()
        if (now - lastGlidePreview < GLIDE_PREVIEW_MS) return
        lastGlidePreview = now
        val previous = editor.word.takeIf { it.isNotEmpty() }?.toString() ?: editor.previousWord()
        engine.requestGlide(points, previous) { c ->
            if (glideActive) c.firstOrNull()?.let { view?.strip = listOf(null, StripItem(glideCase(it.word)), null) }
        }
    }

    /** Lift: the best word goes in as composing text, with a space before it when needed (glide spec §3.3). */
    private fun onGlideEnd(points: List<GlidePoint>) {
        glideActive = false
        lastGlidePreview = 0L
        finishWord(correct = true) // a tapped word ends at a glide as at a space
        val candidates = engine.glideNow(points, editor.previousWord())
        if (candidates.isNullOrEmpty()) return updateStrip()
        if (editor.glideSpace()) updateAutoCaps()
        val words = candidates.map { glideCase(it.word) }.distinct()
        editor.glide(words[0])
        glideAlternatives = words.drop(1).take(3)
        shift.onCharCommitted()
        afterEdit()
    }

    private fun glideCase(word: String) = TextRules.glideCase(word, if (lang.cased) shift.caps else Caps.OFF)

    private fun onText(text: String, touch: Touch?) {
        stopReplies()
        if (searching()) {
            val upper = shift.isUpper && lang.cased && page == Page.LETTERS
            emojiSearch?.type(if (upper) text.uppercase(Locale.US) else text)
            shift.onCharCommitted()
            refreshCaps()
            return
        }
        // A letter right after a glided word starts a new word, with a space between (glide spec §3.4).
        if (editor.glided && Editor.startsWordAfterGlide(text)) {
            finishWord(correct = false)
            if (editor.glideSpace()) updateAutoCaps()
        }
        val upper = shift.isUpper && lang.cased && page == Page.LETTERS
        val s = if (upper) text.uppercase(Locale.US) else text
        when {
            editor.composingEnabled && Editor.isWordChar(s) -> editor.typeLetter(s, touch)
            s in Editor.SEPARATORS -> { onSeparator(s); shift.onCharCommitted(); return }
            else -> { finishWord(correct = false); editor.insert(s) }
        }
        shift.onCharCommitted()
        if (page == Page.SYMBOLS || page == Page.SYMBOLS_MORE) typedSymbol = typedSymbol || !s[0].isDigit()
        afterEdit()
    }

    private fun onSeparator(sep: String) {
        finishWord(correct = true)
        editor.separator(sep, prefs.doubleSpacePeriod && mode.doubleSpacePeriod)
        if (page == Page.SYMBOLS || page == Page.SYMBOLS_MORE) typedSymbol = typedSymbol || sep != " "
        afterEdit()
    }

    private fun onVoice(input: VoiceInput) {
        if (input is VoiceInput.Start) stopReplies()
        if (recording != null) {
            if (input == VoiceInput.OrbTap || input == VoiceInput.Dismiss) stopRecording()
            return
        }
        if (input == VoiceInput.ScreenSetup) {
            voice.onInput(VoiceInput.Dismiss)
            return startActivity(SettingsActivity.screenSetupIntent(this))
        }
        if (input != VoiceInput.Undo) return voice.onInput(input)
        editor.undoReplace()
        aiDraft = if (editor.canUndoReplace() && editor.lastEdit == Editor.AI) editor.field()?.text else null // ↶ went back to ✦'s answer
        afterEdit()
        view?.orbUndo = editor.canUndoReplace()
    }

    private fun onToolbar(input: ToolbarInput) {
        when (input) {
            ToolbarInput.Clipboard -> {
                stopReplies()
                finishWord(correct = false)
                clipSheet?.let {
                    it.onOpen()
                    view?.sheet = it
                }
                afterEdit()
            }
            is ToolbarInput.ClipChip -> {
                val clip = clips.chip() ?: return
                clips.dismissChip()
                if (!input.longPress) paste(clip.text)
                updateStrip()
            }
            ToolbarInput.Passwords -> passwordApp?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            ToolbarInput.Chat -> openChat(fresh = true)
        }
    }

    /** The opening read named [key] (null: no Chat here): the chip shows its title. */
    private fun showChat(key: ChatKey?) {
        if (pendingChatOpen != null && key != null && key != pendingChatOpen) cancelChatOpen()
        chat = key
        view?.chatChip = key?.title
        resumeChatOpen()
        // The reply prefetch waits on the opening read naming the Chat; a fixed delay raced it (a slow read left
        // chat null at 400 ms and the visit drafted nothing).
        main.removeCallbacks(repliesPrefetch)
        if (key != null && repliesArmed) main.postDelayed(repliesPrefetch, REPLIES_PREFETCH_MS)
    }

    /** Opens [chat]'s panel: from the toolbar ([fresh], from the top), or back after a memo. */
    private fun openChat(fresh: Boolean) {
        stopReplies()
        val key = chat ?: return
        if (!ChatPrivacy.unlocked()) {
            if (pendingChatOpen != null) return
            pendingChatOpen = key
            pendingChatField = currentInputEditorInfo?.let { it.packageName to it.fieldId }
            cancelChatUnlock = ChatUnlockActivity.open(this) { success ->
                if (!success) cancelChatOpen()
                else {
                    chatAuthenticated = true
                    main.postDelayed({
                        if (pendingChatOpen != null && ChatPrivacy.unlocked()) {
                            requestShowSelf(InputMethodManager.SHOW_IMPLICIT)
                            resumeChatOpen()
                        }
                    }, 300)
                }
            }
            return
        }
        val sheet = chatSheet ?: return
        finishWord(correct = false)
        loadChat(key, create = fresh)
        if (fresh) sheet.onOpen() else sheet.refresh()
        window?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        view?.sheet = sheet
        afterEdit()
    }

    /**
     * Loads [key]'s memory on the Chat thread and shows it in the panel. Only the owner opening the panel ([create]) makes
     * the Chat; one that is gone otherwise (forgotten) closes the panel.
     */
    private fun loadChat(key: ChatKey, create: Boolean = false) = ChatFiles.later {
        val m = ChatFiles.get(this)
        val info = if (create) m.open(key) else m.info(key)
        main.post {
            if (chat != key || !ChatPrivacy.unlocked()) return@post
            chatInfo = info
            if (info != null) chatSheet?.refresh()
            else if (view?.sheet === chatSheet) closeSheet()
        }
    }

    /** Applies [change] to [chat]'s memory on the Chat thread, then shows the result. */
    private fun changeChat(change: (ChatMemory, ChatKey) -> Unit) {
        if (!ChatPrivacy.unlocked()) return
        val key = chat ?: return
        ChatFiles.later { change(ChatFiles.get(this), key) }
        loadChat(key)
    }

    /** Dictates one verbatim line ([say] prompts for it) into [chat]'s memory with [keep], then reopens the panel. */
    private fun memo(say: Int, keep: (ChatMemory, ChatKey, String) -> Unit) {
        if (!ChatPrivacy.unlocked()) return
        val key = chat ?: return
        stopReplies()
        memoCaption = getString(say)
        voice.memo { text ->
            ChatFiles.later { keep(ChatFiles.get(this), key, text) }
            main.post { if (chat == key) openChat(fresh = false) } // once the orb has closed: it closes sheets
        }
    }

    /** Record (spec §4.2): the keyboard becomes the orb band and every scroll of the chat is read, at most every 500 ms. */
    private fun startRecording() {
        val key = chat ?: return
        if (!prefs.chatsRemember || recording != null) return
        closeSheet()
        recording = key
        recordAgain = false
        ScreenReader.onScroll = ::onRecordEvent
        ScreenReader.listen(true)
        view?.recordCaption = getString(R.string.chat_recording, chatInfo?.takeIf { it.key == key }?.count ?: 0)
        onRecordEvent() // what is on screen now
    }

    private fun stopRecording() {
        if (recording == null) return
        recording = null
        ScreenReader.onScroll = null
        ScreenReader.listen(false)
        main.removeCallbacks(recordRead)
        recordPosted = false
        recordAgain = false
        view?.recordCaption = null
    }

    /** A scroll or content change in the app while recording: read once its [RECORD_READ_MS] slot comes. */
    private fun onRecordEvent() {
        if (recording == null || recordPosted) return
        recordPosted = true
        main.postDelayed(recordRead, (recordReadAt + RECORD_READ_MS - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    /**
     * The autofill provider's launcher screen, or null (none set, or one like Google's without an app to open).
     * Providers fill only fields they recognise, and the keyboard can't ask them to fill (the app must), so this is the
     * fallback: the user searches there and copies. Only the provider's name is read, never what it holds.
     */
    private fun launchablePasswordApp(): Intent? =
        runCatching { getSystemService(AutofillManager::class.java)?.autofillServiceComponentName?.packageName }
            .getOrNull()?.let { packageManager.getLaunchIntentForPackage(it) }

    private fun paste(text: String) {
        stopReplies() // pasting fills the field: no replies over it, and no prefetch competing
        finishWord(correct = false)
        editor.insert(text)
        afterEdit()
    }

    private fun clipItems(): List<ClipItem> =
        voice.pending.map { (id, text) -> ClipItem(text, ClipItem.Kind.DICTATION, id) } +
            (if (prefs.clipboard) clips.clips() else emptyList()).map {
                ClipItem(it.text, if (it.pinned) ClipItem.Kind.PINNED else ClipItem.Kind.RECENT, it.key, it.image?.let { n -> ClipImages.file(this, n) })
            }

    private fun onClipPaste(item: ClipItem) {
        stopReplies() // pasting fills the field: no replies over it, and no prefetch competing
        when {
            item.kind == ClipItem.Kind.DICTATION -> voice.usePending(item.key, insert = true)
            item.image != null -> pasteImage(item.image.name)
            else -> paste(item.text)
        }
    }

    /**
     * Pastes a copied image the way Gboard sends a GIF: as content, if the app says it takes images (Telegram,
     * WhatsApp, Signal…). Otherwise it goes on the system clipboard for the app's own Paste, with a note why.
     */
    private fun pasteImage(name: String) {
        if (!ClipImages.file(this, name).exists()) return
        val uri = ClipImages.uri(this, name)
        val mime = ClipImages.mime(name)
        val info = currentInputEditorInfo
        val ic = currentInputConnection
        val accepted = info != null && EditorInfoCompat.getContentMimeTypes(info).any { ClipDescription.compareMimeTypes(mime, it) }
        if (accepted && ic != null) {
            finishWord(correct = false)
            val content = InputContentInfoCompat(uri, ClipDescription(getString(R.string.clip_image), arrayOf(mime)), null)
            if (InputConnectionCompat.commitContent(ic, info!!, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)) return
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(contentResolver, getString(R.string.clip_image), uri))
        Toast.makeText(this, R.string.clip_image_not_accepted, Toast.LENGTH_LONG).show()
    }

    private fun onClipPin(item: ClipItem, pinned: Boolean) {
        clips.pin(item.key, pinned)
        ClipFile.save(this)
        clipSheet?.refresh()
    }

    private fun onClipDelete(item: ClipItem) {
        if (item.kind == ClipItem.Kind.DICTATION) {
            voice.usePending(item.key, insert = false)
        } else {
            clips.delete(item.key)
            ClipFile.save(this)
        }
        clipSheet?.refresh()
    }

    private fun openEmoji() {
        stopReplies()
        EmojiCatalog.preloadIndex(this) {} // ready by the time the pill is tapped
        finishWord(correct = false)
        emojiSheet?.let {
            it.onOpen()
            view?.sheet = it
        }
        afterEdit()
    }

    private fun closeSheet() {
        window?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        view?.sheet = null
    }

    private fun searching() = view?.searchBar != null

    /** The panel's "Search emoji": the letter keys come back, typing into [emojiSearch] (Gboard's emoji search). */
    private fun openEmojiSearch() {
        val v = view ?: return
        val bar = emojiSearch ?: return
        EmojiCatalog.preloadIndex(this) { main.post { if (view?.searchBar === bar) bar.refresh() } }
        bar.onOpen()
        v.sheet = null
        v.searchBar = bar
        shift.reset()
        page = Page.LETTERS
        previousPage = page
        refresh()
    }

    /** Leaves the search: back to the emoji panel ([toPanel]), or to the keyboard. */
    private fun closeEmojiSearch(toPanel: Boolean) {
        if (!searching()) return
        view?.searchBar = null
        page = Page.LETTERS
        previousPage = page
        refresh()
        if (toPanel) openEmoji() else updateAutoCaps()
    }

    /**
     * A key while searching, if the search takes it: space and delete edit the query, Enter types the best match and
     * the emoji key goes back to the panel. Letters come through [onText]; shift and page keys work as usual.
     */
    private fun onSearchKey(key: Key): Boolean {
        val bar = emojiSearch ?: return false
        when (key.action) {
            KeyAction.SPACE -> bar.type(" ")
            KeyAction.DELETE -> bar.delete()
            KeyAction.ENTER -> bar.pickFirst()
            KeyAction.EMOJI -> closeEmojiSearch(toPanel = true)
            else -> return false
        }
        return true
    }

    private fun onEmoji(emoji: String) {
        feedback.keyDown(KeyAction.TEXT)
        finishWord(correct = false)
        editor.insert(emoji)
        afterEdit()
    }

    private fun onDelete() {
        stopReplies()
        if (searching()) return emojiSearch?.delete() ?: Unit
        val undone = editor.lastCorrection?.typed
        val previous = editor.wordBeforeCorrection()
        if (editor.delete() && undone != null) {
            rejected += undone
            // Undoing teaches the word, so a word the user means (dud) stops being corrected once it is theirs.
            if (learning) Learning.form(undone, previous == null, ::isNoun)?.let { engine.history(lang).learn(it, previous) }
        }
        afterEdit()
    }

    /** Ends the word being typed, applying autocorrect when allowed, and learns the result. */
    private fun finishWord(correct: Boolean): String? {
        if (editor.word.isEmpty()) return null
        val typed = editor.word.toString()
        val previous = editor.previousWord()
        val replacement = if (correct && !editor.resumed && !editor.glided && mode.autocorrect && prefs.autocorrect) {
            val s = suggestions?.takeIf { it.typed == typed } ?: engine.suggestNow(typed, editor.touches.toList(), previous)
            s?.let { Autocorrect.decide(it, rejected) }
        } else {
            null
        }
        val final = editor.finishWord(replacement)
        if (final != null && learning) Learning.form(final, previous == null, ::isNoun)?.let { engine.history(lang).learn(it, previous) }
        suggestions = null
        return final
    }

    private fun onStrip(item: StripItem, longPress: Boolean) {
        // A waiting reply is inserted as an ✦ answer, never learned as a typed word; a long-press is a no-op
        // (it is not a learned word to forget) (waiting-replies spec §3).
        if (waitingReplies?.contains(item.text) == true) {
            if (!longPress) onReplyTap(item.text)
            return
        }
        val history = engine.history(lang)
        if (longPress) {
            // Long-press forgets a word the keyboard learned (Gboard's "remove suggestion").
            val forms = listOf(item.text, item.text.replaceFirstChar { it.lowercaseChar() }).distinct().filter { history.isKnown(it) }
            if (forms.isNotEmpty()) {
                forms.forEach { history.remove(it) }
                afterEdit()
            }
            return
        }
        val previous = editor.previousWord()
        editor.pick(item.text)
        if (learning) Learning.form(item.text, previous == null, ::isNoun)?.let { if (item.literal) history.add(it) else history.learn(it, previous) }
        suggestions = null
        shift.onCharCommitted()
        afterEdit()
    }

    /** The focused field takes messages worth learning. Read from the field itself: [mode] lags while the keyboard is hidden. */
    private fun messageField(): Boolean = currentInputEditorInfo?.let { InputModes.of(it.inputType, it.imeOptions).messages } == true

    /** Names the focused field for [SendWatch]: a restart into another field must not read as a send. */
    private fun fieldKey(): String = currentInputEditorInfo?.run { "$packageName|$fieldId|$fieldName|$hintText|$inputType|$imeOptions" }.orEmpty()

    /** [edited]: the keyboard changed the field's text, so what was read of it is stale. */
    private fun afterEdit(edited: Boolean = true) {
        updateAutoCaps()
        updateStrip()
        if (edited) sendWatch.edited()
        view?.removeCallbacks(readField)
        view?.postDelayed(readField, FIELD_READ_DELAY_MS)
    }

    /** The app moved the cursor to the start: if it emptied a field the keyboard didn't, that was sent. Learned off the main thread. */
    private fun checkSent() {
        val now = editor.peekText()
        val sent = sendWatch.check(now, fieldKey())
        val draft = aiDraft?.takeIf { aiDraftIn == fieldKey() }
        if (now != null && now.isBlank()) aiDraft = null // sent, missed or cleared: the ✦ answer has left the field
        if (sent == null || !messageField()) return
        val app = currentInputEditorInfo?.packageName.orEmpty()
        val chatApp = chatApp()
        val shown = shownReplies.takeIf { shownRepliesField == fieldKey() }.orEmpty()
        shownReplies = null // Main-thread visit state is consumed once, before the asynchronous classification (§2).
        // The title is read after the send, while the chat is still on screen: at once, on its own thread, as a busy
        // Chat thread could read it once the next chat is showing.
        Thread({
            val title = ScreenReader.title().orEmpty()
            ChatFiles.later {
                val key = chatKey(app, title)
                // A Chat set to Don't remember keeps no trace: its messages are only the app's samples. So is every
                // message while Chat memory can't be read, since it can't say which Chats those are.
                val remember = key == null || runCatching { ChatFiles.get(this).info(key)?.remember != false }.getOrDefault(false)
                // Picks includes unchanged AI drafts even though Samples and Chat memory exclude them (§2).
                PicksFile.get(this).sent(sent, app, if (remember) title else "", draft, shown)
                // Plan 6's never-learn rules (an ✦ answer sent unchanged) keep it out of the Chat too.
                if (!StyleFile.get(this).add(sent, app, if (remember) title else "", draft)) return@later
                if (chatApp != null && remember && key != null) ChatFiles.get(this).sent(key, sent, draft)
            }
        }, "style").start()
    }

    /**
     * The focused field's package when the keyboard may be in a Chat there (plan 8's rule; the screen's title decides),
     * else null. Read from the field itself: [mode] and [learning] lag while the keyboard is hidden.
     */
    private fun chatApp(): String? {
        val info = currentInputEditorInfo ?: return null
        val m = InputModes.of(info.inputType, info.imeOptions)
        if (!prefs.style || !m.messages || !m.learning) return null
        return info.packageName?.takeIf { it in prefs.chatApps }
    }

    /** The Chat [title] names in [app], or null when it is no Chat (blank, the app's own name, a tab). */
    private fun chatKey(app: String, title: String): ChatKey? {
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(app, 0)).toString() }.getOrNull()
        return ChatKey.of(app, title, label.orEmpty())
    }

    /** The Chat on screen in [app] and its visible messages (null where they can't be read), from one read. Off the main thread. */
    private fun readChat(app: String, rtl: Boolean): Pair<ChatKey, List<Message>?>? {
        val root = ScreenReader.root() ?: return null
        val key = chatKey(app, ScreenText.title(root).orEmpty()) ?: return null
        return key to chatMessages(app, root, rtl)
    }

    /**
     * For ✦: the Chat on screen's memory, and the owner's messages on [root] for the prompt to mark "Me:". Only with
     * memory (a Chat without it gets Plan 6's prompt, §6.3), and only what nobody else on screen also said: the prompt
     * marks screen lines by their text alone. Off the main thread.
     */
    private fun chatMemory(root: ScreenNode?, title: String, text: String): Pair<ChatContext?, List<String>> {
        val key = chatApp()?.let { chatKey(it, title) }
        val screen = if (key == null || root == null) emptyList() else chatMessages(key.app, root, lang.rtl).orEmpty()
        val chat = key?.let { ChatFiles.get(this).context(it, screen, text) } ?: return null to emptyList()
        val theirs = screen.filter { it.side == Side.THEM }.mapTo(HashSet()) { collapse(it.text) }
        return chat to screen.filter { it.side == Side.ME }.map { collapse(it.text) }.filter { it.isNotEmpty() && it !in theirs }
    }

    private fun chatMessages(app: String, root: ScreenNode, rtl: Boolean): List<Message>? =
        runCatching { ScreenChat.read(root, ScreenReader.width(), app, rtl, System.currentTimeMillis(), ZoneId.systemDefault()) }.getOrNull()

    private fun collapse(s: String) = s.replace(SPACE, " ").trim()

    private fun updateStrip() {
        val v = view ?: return
        v.showUndo = editor.lastCorrection != null // Gboard's undo chip; tapping it is the same as one backspace
        v.pendingChip = if (mode.password) null else voice.chip
        // Gboard's clipboard chip; never in password or incognito fields (spec §4).
        v.clipChip = if (mode.password || !learning || !prefs.clipboard) null else clips.chip()?.text
        v.removeCallbacks(chipExpiry)
        if (v.clipChip != null) clips.chipRemainingMs()?.let { v.postDelayed(chipExpiry, it + 50) }
        if (editor.word.isEmpty() && (v.pendingChip ?: v.clipChip) != null) {
            v.strip = emptyList() // a waiting dictation or a fresh copy outranks predictions
            return
        }
        // Waiting replies hold the strip's three slots while the field is empty (waiting-replies spec §3).
        waitingReplies?.let {
            if (editor.word.isEmpty() && editor.peekText()?.isEmpty() == true) {
                v.strip = it.map { r -> StripItem(r) }
                // Only what the owner actually saw can earn a verdict (picks spec §2): chips and autofill outrank replies.
                if (v.autofill.isEmpty) { // drawToolbar draws nothing over autofill chips
                    shownReplies = it
                    shownRepliesField = fieldKey()
                }
                return
            }
        }
        // A glided word's alternatives show in incognito fields too: they come from the dictionary, and nothing is learned.
        if (!(mode.suggestions || editor.glided) || !prefs.suggestions) {
            v.strip = emptyList()
            return
        }
        if (editor.glided) {
            v.strip = (0..2).map { i -> glideAlternatives.getOrNull(i)?.let { StripItem(it) } }
            return
        }
        val typed = editor.word.toString()
        if (typed.isEmpty()) {
            suggestions = null
            if (!prefs.nextWord || !editor.endsWithSpace()) {
                v.strip = emptyList()
                return
            }
            engine.requestPredictions(editor.previousWord()) { preds ->
                if (editor.word.isEmpty()) v.strip = predictionStrip(preds)
            }
            return
        }
        engine.request(typed, editor.touches.toList(), editor.previousWord()) { s ->
            if (s.typed == editor.word.toString()) {
                suggestions = s
                v.strip = suggestionStrip(s)
            }
        }
    }

    /** [typed] [**correction**] [next best] (as Gboard), or [alt] [typed/best] [alt] when nothing will be replaced. */
    private fun suggestionStrip(s: Suggestions): List<StripItem?> {
        val correction = if (mode.autocorrect && prefs.autocorrect) Autocorrect.decide(s, rejected) else null
        val words = s.candidates.map { it.word }.distinct()
        val centre = correction ?: if (s.typedIsWord) s.typed else words.firstOrNull() ?: s.typed
        val others = words.filter { it != centre }
        return if (centre != s.typed) {
            listOf(StripItem(s.typed, literal = true), StripItem(centre, bold = correction != null), others.firstOrNull()?.let { StripItem(it) })
        } else {
            listOf(others.getOrNull(0)?.let { StripItem(it) }, StripItem(centre), others.getOrNull(1)?.let { StripItem(it) })
        }
    }

    private fun predictionStrip(preds: List<Candidate>): List<StripItem?> {
        if (preds.isEmpty()) return emptyList()
        val words = preds.map { if (shift.isUpper) it.word.replaceFirstChar { c -> c.uppercaseChar() } else it.word }
        return listOf(words.getOrNull(1), words.getOrNull(0), words.getOrNull(2)).map { w -> w?.let { StripItem(it) } }
    }

    private fun onModifierUp(key: Key, used: Boolean) {
        if (key.action == KeyAction.SHIFT) {
            shift.onShiftUp()
            refreshCaps()
        } else if (used) {
            setPage(previousPage)
        }
    }

    private fun setPage(p: Page) {
        if (p == page) return
        previousPage = page
        page = p
        typedSymbol = false
        refresh()
        updateAutoCaps()
    }

    private fun updateAutoCaps() {
        val wanted = prefs.autoCaps && lang.cased && page == Page.LETTERS && !mode.password && !searching() && editor.capsWanted(inputType)
        shift.onAutoCaps(wanted)
        refreshCaps()
    }

    private fun refreshCaps() {
        view?.caps = if (lang.cased && page == Page.LETTERS) shift.caps else Caps.OFF
    }

    private fun palette(): KeyboardColors =
        if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) Themes.DARK else Themes.LIGHT

    @Suppress("DEPRECATION")
    /**
     * Cursor control: arrow keys, so the app moves its own cursor (visually, also in Arabic text) and multi-line fields
     * go up and down a line. A tick per step.
     */
    private fun moveCursor(dx: Int, dy: Int) {
        repeat(kotlin.math.abs(dx)) { sendDownUpKeyEvents(if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT) }
        repeat(kotlin.math.abs(dy)) { sendDownUpKeyEvents(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
        feedback.voiceTick()
    }

    /** [l]'s name for the AI prompt, in English like the rest of it. */
    private fun languageName(l: Lang) = when (l) {
        Lang.EN -> "English"
        Lang.AR -> "Arabic"
        Lang.DE -> "German"
    }

    /** The space bar's name for [l]. */
    private fun labelOf(l: Lang): String = getString(
        when (l) {
            Lang.EN -> R.string.subtype_en
            Lang.AR -> R.string.subtype_ar
            Lang.DE -> R.string.subtype_de
        },
    )

    /** This keyboard's enabled languages with their subtypes, in method.xml order (English, Arabic, German). */
    private fun languages(): List<Pair<Lang, InputMethodSubtype>> =
        enabledSubtypes().map { langOf(it) to it }.distinctBy { it.first }

    /**
     * This keyboard's enabled subtypes. Asked for by our own InputMethodInfo, as the settings screen does: passing null
     * ("the current input method") came back with fewer than the enabled languages on a Samsung phone.
     */
    private fun enabledSubtypes(): List<InputMethodSubtype> {
        val imm = getSystemService(InputMethodManager::class.java)
        val me = imm.enabledInputMethodList.firstOrNull { it.id == imeId(this) }
        return imm.getEnabledInputMethodSubtypeList(me, true)
    }

    /** The language [step] places after the current one (−1 = before), wrapping round; null with one language. */
    private fun languageAfter(step: Int): Lang? = languageStep(step)?.first

    private fun languageStep(step: Int): Pair<Lang, InputMethodSubtype>? {
        val all = languages()
        if (all.size < 2) return null
        val i = all.indexOfFirst { it.first == lang }.coerceAtLeast(0)
        return all[Math.floorMod(i + step, all.size)]
    }

    /** Space-bar swipe: +1 = next language, −1 = previous. The subtype change then rebuilds the keyboard. */
    private fun switchLanguage(step: Int) {
        val (_, subtype) = languageStep(step) ?: return
        feedback.keyDown(KeyAction.SPACE)
        switchInputMethod(imeId(this), subtype)
    }

    /** A capitalised dictionary word with no lowercase form ("Haus"): its capital is not just the sentence start. */
    private fun isNoun(word: String) = engine.isWord(lang, word) && !engine.isWord(lang, word.lowercase())

    private fun langOf(subtype: InputMethodSubtype?): Lang =
        Lang.of(subtype?.languageTag?.takeIf { it.isNotEmpty() } ?: subtype?.locale)

    private fun openSettings() =
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private companion object {
        /**
         * The field is read this long after the last keyboard edit. ponytail: a send tapped sooner than this after the last
         * key isn't learned (a missed sample, never a wrong one); listen to GET_EXTRACTED_TEXT_MONITOR if too many are missed.
         */
        const val FIELD_READ_DELAY_MS = 200L
        /** After the opening read names the Chat: lets the field settle before the replies prefetch (spec §4). */
        const val REPLIES_PREFETCH_MS = 150L
        /** The glide preview is decoded at most this often. */
        const val GLIDE_PREVIEW_MS = 50L
        /** The opening read of a chat waits this long for its layout (chat memory spec §4.2). */
        const val CHAT_READ_DELAY_MS = 300L
        /** Profiles start this long after the keyboard hides, if it stays hidden. */
        const val PROFILE_DELAY_MS = 5_000L
        /** While recording, the screen is read at most this often (chat memory spec §4.2). */
        const val RECORD_READ_MS = 500L
        /** ✦'s "My messages" beside "How I reply to them" pairs, and without them (chat memory spec §6.1). */
        const val PICK_WITH_PAIRS = 5
        const val PICK_ALONE = 15
        val SPACE = Regex("\\s+")
        const val TAG = "KbdChats"
    }
}
