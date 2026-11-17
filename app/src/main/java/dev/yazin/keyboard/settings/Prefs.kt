package dev.yazin.keyboard.settings

import android.content.Context
import androidx.preference.PreferenceManager
import dev.yazin.keyboard.engine.Backend
import dev.yazin.keyboard.engine.llm.LlmCall
import dev.yazin.keyboard.engine.llm.LlmClient
import dev.yazin.keyboard.engine.voice.WhisperClient

/** Typed reads of the settings screen's values. Defaults must match res/xml/prefs.xml. */
class Prefs(context: Context) {
    private val sp = PreferenceManager.getDefaultSharedPreferences(context)

    init {
        if (!sp.contains(MIGRATED)) keepOldDefaults(context)
    }

    /**
     * The features that learn from what you write, read your chats or keep AI requests are off for a new install, so
     * nothing goes to an AI service until you turn it on; so is the fingerprint lock. An install from before these were
     * settings keeps them on, as it had them.
     */
    private fun keepOldDefaults(context: Context) {
        val used = sp.all.isNotEmpty() || context.noBackupFilesDir.list().orEmpty().isNotEmpty()
        val e = sp.edit()
        if (used) for (key in OLD_DEFAULT_ON) if (!sp.contains(key)) e.putBoolean(key, true)
        e.putBoolean(MIGRATED, true).apply()
    }

    val numberRow: Boolean get() = sp.getBoolean("number_row", true)
    val easternDigits: Boolean get() = sp.getBoolean("eastern_digits", false)
    val glide: Boolean get() = sp.getBoolean("glide", true)
    val autoCaps: Boolean get() = sp.getBoolean("auto_caps", true)
    val doubleSpacePeriod: Boolean get() = sp.getBoolean("double_space_period", true)
    val keyPreview: Boolean get() = sp.getBoolean("key_preview", true)
    val longPressMs: Long get() = sp.getString("long_press_ms", "300")!!.toLong()
    val heightScale: Float get() = sp.getString("height", "1.0")!!.toFloat()
    val haptics: Int get() = sp.getString("haptics", "1")!!.toInt()
    val sound: Boolean get() = sp.getBoolean("sound", false)
    val autocorrect: Boolean get() = sp.getBoolean("autocorrect", true)
    val suggestions: Boolean get() = sp.getBoolean("suggestions", true)
    val nextWord: Boolean get() = sp.getBoolean("next_word", true)
    val clipboard: Boolean get() = sp.getBoolean("clipboard", true)

    /** Where dictation and ✦ go (Settings → AI service). */
    val backend: Backend get() = if (sp.getString("service", "venice") == "server") Backend.SERVER else Backend.VENICE
    private val venice get() = backend == Backend.VENICE
    /** The key for [backend]'s requests; "" sends none. */
    val apiKey: String get() = sp.getString(if (venice) "venice_key" else "server_key", "")!!.trim()

    val voiceUrl: String get() = if (venice) Backend.VENICE.voiceUrl else sp.getString("voice_url", "")!!.trim()
    /** Empty means automatic: the server's first speech model (spec §7.2). */
    val voiceModel: String get() = if (venice) Backend.VENICE.voiceModel else sp.getString("voice_model", "")!!.trim()
    val voiceSilenceMs: Int get() = sp.getString("voice_silence", "2000")!!.toInt()
    val voiceHotwords: Boolean get() = sp.getBoolean("voice_hotwords", true)
    val llmUrl: String get() = if (venice) Backend.VENICE.llmUrl else sp.getString("llm_url", "")!!.trim()
    /** Empty means the server's own model (llama-server serves the one it loaded), or Venice's recommended one. */
    val llmModel: String get() = sp.getString(if (venice) "venice_llm_model" else "llm_model", "")!!.trim()

    /** A client for the chosen service; [english] tells it which words are English. */
    fun whisper(english: (String) -> Boolean = { false }) = WhisperClient(voiceUrl, apiKey, backend, english)

    fun llm(log: ((LlmCall) -> Unit)? = null) = LlmClient(llmUrl, apiKey, backend, log = log)
    /** Learn from sent messages and send samples with ✦ (writing-style spec §4). */
    val style: Boolean get() = sp.getBoolean("style", false)
    /** Read the visible chat when the keyboard opens and hides in a messaging app (chat memory spec §4.2). */
    val chatsRemember: Boolean get() = sp.getBoolean("chats_remember", false)

    /** Waiting replies: prefetch drafts when the keyboard opens in a remembered Chat (waiting-replies spec §6). */
    val repliesWaiting: Boolean get() = sp.getBoolean("replies_waiting", false)
    /** Keep the last AI requests and replies on this phone (Settings → AI writing → Debug log). */
    val debugLog: Boolean get() = sp.getBoolean("debug_log", false)
    /** Ask for a fingerprint or the screen lock before showing what is kept about a Chat (Settings → AI writing). */
    val lockChats: Boolean get() = sp.getBoolean("lock_chats", false)
    /** The messaging apps whose chats are remembered (chat memory spec §3). */
    val chatApps: Set<String> get() = sp.getStringSet("chat_apps", DEFAULT_CHAT_APPS)!!
    /** The sender name that is the user in WhatsApp exports; "" until the first import asks. */
    var styleOwner: String
        get() = sp.getString("style_owner", "")!!
        set(value) = sp.edit().putString("style_owner", value).apply()

    companion object {
        private const val MIGRATED = "privacy_defaults_v1"
        private val OLD_DEFAULT_ON = listOf("style", "chats_remember", "replies_waiting", "debug_log", "lock_chats")
        /** The same set as `@array/chat_apps_default`, prefs.xml's default (PrefsDefaultsTest). */
        val DEFAULT_CHAT_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "org.telegram.messenger",
            "org.thoughtcrime.securesms", "com.instagram.android", "com.Slack",
        )
    }
}
