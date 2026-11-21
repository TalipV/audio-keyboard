package dev.yazin.keyboard.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentTransaction
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import dev.yazin.keyboard.R
import dev.yazin.keyboard.chat.ChatFiles
import dev.yazin.keyboard.chat.ChatPrivacy
import dev.yazin.keyboard.chat.ChatUnlock
import dev.yazin.keyboard.clip.ClipFile
import dev.yazin.keyboard.a11y.ScreenReader
import dev.yazin.keyboard.engine.Account
import dev.yazin.keyboard.engine.Backend
import dev.yazin.keyboard.engine.Venice
import dev.yazin.keyboard.engine.llm.LlmResult
import dev.yazin.keyboard.engine.llm.Prompt
import dev.yazin.keyboard.engine.voice.Outcome
import dev.yazin.keyboard.engine.voice.PrivateHost
import dev.yazin.keyboard.engine.voice.TranscribeRequest
import dev.yazin.keyboard.ime.KeyboardService
import dev.yazin.keyboard.llm.LlmLogFile
import dev.yazin.keyboard.style.StyleFile
import dev.yazin.keyboard.style.PicksFile
import java.net.URI
import java.util.Locale

/** Gboard-style settings: a short top-level list where each topic opens its own screen (nested in res/xml/prefs.xml). */
class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartScreenCallback {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.settings, SettingsFragment()).commit()
            intent.getStringExtra(EXTRA_OPEN)?.let(::open)
            if (intent.getBooleanExtra(EXTRA_SCREEN_SETUP, false)) {
                val ai = SettingsFragment().apply { arguments = bundleOf(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT to "screen_ai", EXTRA_SCREEN_SETUP to true) }
                supportFragmentManager.beginTransaction().replace(R.id.settings, ai).addToBackStack("screen_ai").commit()
            }
        }
    }

    companion object {
        private const val EXTRA_SCREEN_SETUP = "screen_setup"
        private const val EXTRA_OPEN = "open"

        /** Settings opened at the screen [key] of res/xml/prefs.xml, e.g. "screen_ai". */
        fun screenIntent(context: Context, key: String): Intent = Intent(context, SettingsActivity::class.java).putExtra(EXTRA_OPEN, key)

        /** The AI screen with the screen-context steps open, from ✦'s "Turn on screen context". */
        fun screenSetupIntent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
            .putExtra(EXTRA_SCREEN_SETUP, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) // land here even if settings was open
    }

    override fun onPreferenceStartScreen(caller: PreferenceFragmentCompat, pref: PreferenceScreen): Boolean {
        open(pref.key)
        return true
    }

    /** Opens the screen [key] of res/xml/prefs.xml as if its row had been tapped. */
    fun open(key: String) {
        val screen = SettingsFragment().apply { arguments = bundleOf(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT to key) }
        supportFragmentManager.beginTransaction().replace(R.id.settings, screen)
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN).addToBackStack(key).commit()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!supportFragmentManager.popBackStackImmediate()) finish()
        return true
    }

    /** One fragment class for every screen; each wires up only the preferences its screen holds. */
    class SettingsFragment : PreferenceFragmentCompat() {
        /** Speech models the server offered, once the status check has answered. */
        private var serverModels: List<String>? = null
        /** Language models the AI server offered. */
        private var llmModels: List<String>? = null
        /** The screen-context steps, open until the service is on. */
        private var screenSetup: AlertDialog? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
            findPreference<Preference>("languages")?.setOnPreferenceClickListener {
                startActivity(languagesIntent(requireContext()))
                true
            }
            findPreference<Preference>("screen_service")?.summaryProvider = Preference.SummaryProvider<Preference> { serviceName() }
            for (key in listOf("service_voice", "service_ai")) findPreference<Preference>(key)?.let { p ->
                p.summaryProvider = Preference.SummaryProvider<Preference> { getString(R.string.pref_service_link, serviceName()) }
                p.setOnPreferenceClickListener { (activity as? SettingsActivity)?.open("screen_service"); true }
            }
            findPreference<ListPreference>("service")?.let { p ->
                p.summaryProvider = Preference.SummaryProvider<ListPreference> { lp ->
                    getString(if (lp.value == "server") R.string.pref_service_server else R.string.pref_service_venice)
                }
                p.setOnPreferenceChangeListener { _, _ -> view?.post(::showService); true } // once the choice is saved
            }
            for (key in listOf("venice_key", "server_key")) findPreference<EditTextPreference>(key)?.let { p ->
                p.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD } // setSingleLine() would unmask it
                p.setOnPreferenceChangeListener { _, _ -> view?.post(::showService); true }
            }
            findPreference<EditTextPreference>("server_key")?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { ep ->
                ep.text.orEmpty().trim().takeIf { it.isNotEmpty() }?.let(::masked) ?: getString(R.string.server_key_none)
            }
            findPreference<EditTextPreference>("voice_url")?.let { p ->
                p.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; it.setSingleLine() }
                p.summaryProvider = Preference.SummaryProvider<EditTextPreference> { ep ->
                    val url = ep.text.orEmpty().trim()
                    when {
                        url.isEmpty() -> getString(R.string.pref_url_unset)
                        PrivateHost.insecure(url) -> getString(R.string.pref_voice_url_insecure, url)
                        else -> url
                    }
                }
                p.setOnPreferenceChangeListener { _, _ -> view?.post(::checkServer); true } // re-check once the new address is saved
            }
            findPreference<ListPreference>("voice_model")?.let { p ->
                showModels(p)
                p.summaryProvider = Preference.SummaryProvider<ListPreference> { lp ->
                    lp.value?.takeIf { it.isNotBlank() }
                        ?: serverModels?.firstOrNull()?.let { getString(R.string.pref_voice_model_auto_named, it) }
                        ?: getString(R.string.pref_voice_model_auto)
                }
            }
            findPreference<Preference>("voice_test")?.setOnPreferenceClickListener {
                testConnection()
                true
            }
            findPreference<Preference>("about")?.let { p ->
                // The build number too: CI builds share a versionName, and only the code says which one is installed.
                val version = requireContext().run {
                    packageManager.getPackageInfo(packageName, 0).let { "${it.versionName} (Build ${it.longVersionCode})" }
                }
                p.summary = getString(R.string.pref_about_summary, version)
                p.setOnPreferenceClickListener {
                    AlertDialog.Builder(requireContext()).setTitle(getString(R.string.licenses_title, version)).setMessage(R.string.licenses)
                        .setPositiveButton(android.R.string.ok, null).show()
                    true
                }
            }
            findPreference<Preference>("clipboard")?.setOnPreferenceChangeListener { _, on ->
                if (on == false) clearClips() // turning it off forgets what was kept (§8.1)
                true
            }
            findPreference<Preference>("clipboard_check")?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext()).setTitle(R.string.pref_clipboard_check)
                    .setMessage(dev.yazin.keyboard.clip.clipboardReport(requireContext()))
                    .setPositiveButton(android.R.string.ok, null).show()
                true
            }
            findPreference<Preference>("clipboard_clear")?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext()).setMessage(R.string.pref_clipboard_clear_confirm)
                    .setPositiveButton(R.string.clip_delete) { _, _ -> clearClips() }
                    .setNegativeButton(android.R.string.cancel, null).show()
                true
            }
            findPreference<EditTextPreference>("llm_url")?.let { p ->
                p.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; it.setSingleLine() }
                p.summaryProvider = Preference.SummaryProvider<EditTextPreference> { ep ->
                    val url = ep.text.orEmpty().trim()
                    when {
                        url.isEmpty() -> getString(R.string.pref_url_unset)
                        PrivateHost.insecure(url) -> getString(R.string.pref_llm_url_insecure, url)
                        else -> url
                    }
                }
                p.setOnPreferenceChangeListener { _, _ -> view?.post(::checkLlm); true }
            }
            for (key in listOf("llm_model", "venice_llm_model")) findPreference<ListPreference>(key)?.let { p ->
                showLlmModels(p)
                p.summaryProvider = Preference.SummaryProvider<ListPreference> { lp -> lp.value?.takeIf { it.isNotBlank() } ?: llmDefault(lp) }
            }
            findPreference<Preference>("llm_test")?.setOnPreferenceClickListener {
                testLlm()
                true
            }
            findPreference<Preference>("screen_context")?.setOnPreferenceClickListener {
                if (ScreenReader.enabled) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) else showScreenSetup()
                true
            }
            findPreference<MultiSelectListPreference>("chat_apps")?.let { p ->
                showChatApps(p)
                p.summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> { mp ->
                    val on = mp.entryValues.indices.filter { mp.entryValues[it] in mp.values }.map { mp.entries[it] }
                    getString(R.string.pref_chat_apps_summary, on.joinToString(", ").ifEmpty { getString(R.string.chat_apps_none) })
                }
            }
            findPreference<Preference>("style_import")?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext()).setTitle(R.string.pref_style_import).setMessage(R.string.style_import_steps)
                    .setPositiveButton(android.R.string.ok, null).show()
                true
            }
            findPreference<Preference>("style_clear")?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext()).setMessage(R.string.pref_style_clear_confirm)
                    .setPositiveButton(R.string.clip_delete) { _, _ -> clearStyle() }
                    .setNegativeButton(android.R.string.cancel, null).show()
                true
            }
            findPreference<SwitchPreferenceCompat>("lock_chats")?.let { p ->
                // Turning the lock on or off asks first too, or anyone holding the phone could just switch it off.
                p.setOnPreferenceChangeListener { _, on ->
                    // With no fingerprint left to ask for, the lock can't be opened; removing one takes the screen lock.
                    if (on == false && !ChatPrivacy.available(requireContext())) return@setOnPreferenceChangeListener true
                    ChatUnlock(requireActivity()).request(onUnlocked = { p.isChecked = on == true }, always = true)
                    false
                }
            }
            findPreference<Preference>("debug_log")?.setOnPreferenceChangeListener { _, on ->
                if (on == false) LlmLogFile.clear(requireContext()) // turning it off forgets what was kept
                true
            }
            if (savedInstanceState == null && arguments?.getBoolean(EXTRA_SCREEN_SETUP) == true && !ScreenReader.enabled) showScreenSetup()
            if (findPreference<Preference>("service") != null) showService()
        }

        override fun onResume() {
            super.onResume()
            activity?.title = preferenceScreen.title
            // Back from the system language screen, which may have changed what's on.
            findPreference<Preference>("languages")?.summary = enabledLanguages(requireContext())
            findPreference<Preference>("screen_context")?.summary =
                getString(if (ScreenReader.enabled) R.string.pref_screen_context_on else R.string.pref_screen_context_off)
            if (ScreenReader.enabled) screenSetup?.dismiss()
            showStyleCount()
            showChatCount()
            // Back from the AI service screen, which may have changed what dictation can send.
            findPreference<Preference>("voice_hotwords")?.let { p ->
                val venice = Prefs(requireContext()).backend == Backend.VENICE
                p.isEnabled = !venice
                p.summary = getString(if (venice) R.string.pref_voice_hotwords_venice else R.string.pref_voice_hotwords_summary)
            }
        }

        /** "Venice AI", or "My server · mybox". */
        private fun serviceName(): String {
            val prefs = Prefs(requireContext())
            if (prefs.backend == Backend.VENICE) return getString(R.string.service_venice)
            return listOfNotNull(getString(R.string.service_server), runCatching { URI(prefs.voiceUrl).host }.getOrNull()).joinToString(" · ")
        }

        /** Shows only the rows the chosen service needs, then checks it. */
        private fun showService() {
            val prefs = Prefs(requireContext())
            val venice = prefs.backend == Backend.VENICE
            for (key in listOf("venice_key", "venice_llm_model")) findPreference<Preference>(key)?.isVisible = venice
            for (key in listOf("voice_url", "llm_url", "server_key", "voice_model", "llm_model")) findPreference<Preference>(key)?.isVisible = !venice
            findPreference<Preference>("venice_get_key")?.isVisible = venice && prefs.apiKey.isEmpty()
            if (venice) checkVenice() else {
                checkServer()
                checkLlm()
            }
        }

        /** Live status for Venice: one key check fills the key row and both test rows. The model list needs no key. */
        private fun checkVenice() {
            val prefs = Prefs(requireContext())
            val key = prefs.apiKey
            val keyRow = findPreference<Preference>("venice_key") ?: return
            val voice = findPreference<Preference>("voice_test")
            val llm = findPreference<Preference>("llm_test")
            val client = prefs.llm()
            if (key.isEmpty()) {
                keyRow.summary = getString(R.string.venice_key_unset)
                listOfNotNull(voice, llm).forEach { it.summary = getString(R.string.venice_status_no_key) }
            } else {
                keyRow.summary = getString(R.string.venice_key_checking, masked(key))
                listOfNotNull(voice, llm).forEach { it.summary = getString(R.string.voice_status_checking) }
            }
            Thread {
                val account = if (key.isEmpty()) null else Venice.account(prefs.llmUrl, key)
                val models = runCatching { client.models() }.getOrNull()
                activity?.runOnUiThread {
                    if (!isAdded || Prefs(requireContext()).run { backend != Backend.VENICE || apiKey != key }) return@runOnUiThread // a newer check is coming
                    llmModels = models
                    findPreference<ListPreference>("venice_llm_model")?.let { showLlmModels(it); it.summaryProvider = it.summaryProvider }
                    findPreference<Preference>("venice_get_key")?.isVisible = account !is Account.Ok
                    when (account) {
                        null -> {}
                        is Account.Ok -> {
                            val credit = when {
                                account.usd > 0 -> "$" + "%.2f".format(Locale.US, account.usd)
                                account.diem > 0 -> "%.2f DIEM".format(Locale.US, account.diem)
                                else -> null
                            }
                            keyRow.summary = credit?.let { getString(R.string.venice_key_ok, masked(key), it) }
                                ?: getString(R.string.venice_key_no_credit, masked(key))
                            voice?.summary = getString(R.string.voice_status_ok)
                            llm?.summary = getString(R.string.llm_status_ok)
                        }
                        is Account.Failed -> {
                            keyRow.summary = getString(R.string.venice_key_failed, masked(key), account.message)
                            listOfNotNull(voice, llm).forEach { it.summary = account.message }
                        }
                    }
                }
            }.start()
        }

        /** Enough of a key to tell which one is saved. */
        private fun masked(key: String) = "•••• " + key.takeLast(4)

        private fun llmDefault(p: ListPreference): String =
            if (p.key == "venice_llm_model") getString(R.string.pref_llm_model_recommended, Backend.VENICE.llmModel) else getString(R.string.pref_llm_model_auto)

        override fun onDestroyView() {
            screenSetup?.dismiss()
            super.onDestroyView()
        }

        /**
         * How to turn the service on. Its buttons leave it open, so it is still here after a trip to system settings; it
         * closes once the service is on. Sideloaded apps on Android 13+ also need "Allow restricted settings" first.
         */
        private fun showScreenSetup() {
            val ctx = requireContext()
            val restricted = Build.VERSION.SDK_INT >= 33
            val builder = AlertDialog.Builder(ctx).setTitle(R.string.screen_setup_title)
                .setMessage(if (restricted) R.string.screen_setup_steps else R.string.screen_setup_steps_old)
                .setPositiveButton(R.string.screen_setup_a11y, null) // back or a tap outside closes it
            if (restricted) builder.setNeutralButton(R.string.screen_setup_app_info, null)
            val dialog = builder.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            }
            dialog.setOnDismissListener { if (screenSetup === dialog) screenSetup = null }
            screenSetup = dialog
        }

        /** Model choices: Automatic, then the server's models, keeping a saved one the server no longer lists. */
        private fun showModels(p: ListPreference) {
            val models = (serverModels.orEmpty() + listOfNotNull(p.value?.takeIf { it.isNotBlank() })).distinct()
            p.entries = (listOf(getString(R.string.pref_voice_model_auto)) + models).toTypedArray()
            p.entryValues = (listOf("") + models).toTypedArray()
        }

        /** Live status on the Test connection row: /health and /v1/models only, no transcription. */
        private fun checkServer() {
            val test = findPreference<Preference>("voice_test") ?: return
            val prefs = Prefs(requireContext())
            val url = prefs.voiceUrl
            val client = prefs.whisper()
            test.summary = getString(R.string.voice_status_checking)
            Thread {
                val models = runCatching { client.run { if (health()) speechModels() else null } }.getOrNull()
                activity?.runOnUiThread {
                    if (!isAdded || url != Prefs(requireContext()).voiceUrl) return@runOnUiThread // a newer check is coming
                    serverModels = models
                    test.summary = getString(when {
                        models == null -> R.string.voice_status_offline
                        models.isEmpty() -> R.string.voice_status_no_model
                        else -> R.string.voice_status_ok
                    })
                    findPreference<ListPreference>("voice_model")?.let { showModels(it); it.summaryProvider = it.summaryProvider } // re-setting refreshes the summary
                }
            }.start()
        }

        private fun showLlmModels(p: ListPreference) {
            val models = (llmModels.orEmpty() + listOfNotNull(p.value?.takeIf { it.isNotBlank() })).distinct()
            p.entries = (listOf(llmDefault(p)) + models).toTypedArray()
            p.entryValues = (listOf("") + models).toTypedArray()
        }

        /** Live status on the AI Test connection row: /v1/models only. */
        private fun checkLlm() {
            val test = findPreference<Preference>("llm_test") ?: return
            val prefs = Prefs(requireContext())
            val url = prefs.llmUrl
            val client = prefs.llm()
            test.summary = getString(R.string.voice_status_checking)
            Thread {
                val models = runCatching { client.models() }.getOrNull()
                activity?.runOnUiThread {
                    if (!isAdded || url != Prefs(requireContext()).llmUrl) return@runOnUiThread
                    llmModels = models
                    test.summary = getString(if (models == null) R.string.voice_status_offline else R.string.llm_status_ok)
                    findPreference<ListPreference>("llm_model")?.let { showLlmModels(it); it.summaryProvider = it.summaryProvider }
                }
            }.start()
        }

        /** Spec §5: one short completion, with its round trip and reply. */
        private fun testLlm() {
            val ctx = requireContext()
            val prefs = Prefs(ctx)
            val dialog = AlertDialog.Builder(ctx).setTitle(R.string.pref_voice_test).setMessage(R.string.voice_test_running)
                .setPositiveButton(android.R.string.ok, null).show()
            Thread {
                val t0 = System.nanoTime()
                val out = prefs.llm(LlmLogFile.record(ctx))
                    .complete(prefs.llmModel, Prompt.SYSTEM, "## Instruction\nSay hello in five words or fewer.", "Test connection")
                val ms = (System.nanoTime() - t0) / 1_000_000
                val report = when (out) {
                    is LlmResult.Text -> "Reply: “\u2068${out.text}\u2069” · $ms ms" // isolated, so Arabic doesn't reorder the line
                    is LlmResult.Failed -> out.message
                }
                activity?.runOnUiThread { if (dialog.isShowing) dialog.setMessage(report) }
            }.start()
        }

        private fun clearStyle() {
            val ctx = requireContext()
            StyleFile.get(ctx).clear()
            StyleFile.save(ctx)
            PicksFile.get(ctx).clear()
            PicksFile.save(ctx)
            Prefs(ctx).styleOwner = ""
            showStyleCount()
            val app = ctx.applicationContext
            ChatFiles.later { ChatFiles.get(app).clear() }
            showChatCount()
        }

        /** How many messages ✦ has to go on; refreshed on return, e.g. after an import from WhatsApp. */
        private fun showStyleCount() {
            val p = findPreference<Preference>("style_import") ?: return
            val n = StyleFile.get(requireContext()).size
            p.summary = resources.getQuantityString(R.plurals.style_count, n, n)
        }

        /** How many Chats Settings → Chats lists; read on the Chat thread, after any clear asked for before it. */
        private fun showChatCount() {
            val p = findPreference<Preference>("chats") ?: return
            val app = requireContext().applicationContext
            ChatFiles.later {
                val n = ChatsActivity.listed(ChatFiles.get(app).all()).size
                activity?.runOnUiThread { if (isAdded) p.summary = resources.getQuantityString(R.plurals.chats_count, n, n) }
            }
        }

        /**
         * The choices for Messaging apps (chat memory spec §8): the default list, then installed apps the keyboard has
         * learned sent messages in, and any other app already chosen, so it can be unticked.
         */
        private fun showChatApps(p: MultiSelectListPreference) {
            val ctx = requireContext()
            val defaults = resources.getStringArray(R.array.chat_apps_default).toList()
            val typedIn = StyleFile.get(ctx).samples().map { it.app }.distinct()
                .filter { it !in defaults && it != ctx.packageName && ctx.packageManager.getLaunchIntentForPackage(it) != null }
            val others = (typedIn + p.values.filter { it !in defaults }).distinct()
                .map { it to ChatsActivity.appName(ctx, it) }.sortedBy { it.second.lowercase() }
            val apps = defaults.map { it to ChatsActivity.appName(ctx, it) } + others
            p.entries = apps.map { it.second }.toTypedArray()
            p.entryValues = apps.map { it.first }.toTypedArray()
        }

        private fun clearClips() {
            val ctx = requireContext()
            ClipFile.get(ctx).clear()
            ClipFile.save(ctx)
        }

        private fun testConnection() {
            val ctx = requireContext()
            val prefs = Prefs(ctx)
            val dialog = AlertDialog.Builder(ctx).setTitle(R.string.pref_voice_test).setMessage(R.string.voice_test_running)
                .setPositiveButton(android.R.string.ok, null).show()
            Thread {
                val report = connectionReport(prefs) { name -> ctx.assets.open("voice/$name").use { it.readBytes() } }
                activity?.runOnUiThread { if (dialog.isShowing) dialog.setMessage(report) }
            }.start()
        }
    }
}

/** The enabled subtypes' names, e.g. "English, العربية"; null until the keyboard is enabled. */
private fun enabledLanguages(context: Context): String? {
    val imm = context.getSystemService(InputMethodManager::class.java)
    val ime = imm.enabledInputMethodList.firstOrNull { it.id == imeId(context) } ?: return null
    return imm.getEnabledInputMethodSubtypeList(ime, true)
        .joinToString(", ") { it.getDisplayName(context, context.packageName, context.applicationInfo) }
}

/** The id Android uses for this IME, e.g. "dev.yazin.keyboard/.ime.KeyboardService". */
fun imeId(context: Context): String = ComponentName(context, KeyboardService::class.java).flattenToShortString()

/** System screen where the user enables our English/Arabic subtypes. */
fun languagesIntent(context: Context): Intent =
    Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS).putExtra(Settings.EXTRA_INPUT_METHOD_ID, imeId(context))

/** Settings → Test connection (spec §7.4): /health (Venice: the key), /v1/models, then both bundled clips, with round-trip times. */
private fun connectionReport(prefs: Prefs, clip: (String) -> ByteArray): String {
    val url = prefs.voiceUrl
    val settingsModel = prefs.voiceModel
    val client = prefs.whisper()
    val lines = ArrayList<String>()
    fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
    try {
        var t0 = System.nanoTime()
        if (prefs.backend == Backend.VENICE) {
            (Venice.account(url, prefs.apiKey) as? Account.Failed)?.let { return it.message }
            lines += "Venice: OK · ${ms(t0)} ms"
        } else {
            if (!client.health()) return "Server answered, but isn't healthy"
            lines += "Server: OK · ${ms(t0)} ms"
        }
        val models = client.speechModels()
        val model = settingsModel.ifBlank { models.firstOrNull() } ?: return (lines + "No speech model on the server").joinToString("\n")
        lines += "Model: $model" + when {
            settingsModel.isBlank() -> " (automatic)"
            model !in models -> " (not on the server!)"
            else -> ""
        }
        client.preload(model)
        for ((lang, name) in listOf("en" to "English", "ar" to "Arabic")) {
            t0 = System.nanoTime()
            lines += when (val out = client.transcribe(clip("test-$lang.wav"), TranscribeRequest(model, lang, "", null))) {
                is Outcome.Text -> "$name: “\u2068${out.text}\u2069” · ${ms(t0)} ms"
                is Outcome.Retry -> "$name: failed (${out.reason})"
                is Outcome.Failed -> "$name: ${out.message}"
            }
        }
    } catch (e: Exception) {
        lines += "Couldn't reach $url (${e.javaClass.simpleName})"
    }
    return lines.joinToString("\n")
}
