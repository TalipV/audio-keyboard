package dev.yazin.keyboard.settings

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.StyleSpan
import android.view.WindowManager
import android.view.View
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import dev.yazin.keyboard.R
import dev.yazin.keyboard.chat.ChatPrivacy
import dev.yazin.keyboard.chat.ChatUnlock
import dev.yazin.keyboard.chat.ChatFiles
import dev.yazin.keyboard.engine.chat.ChatInfo
import dev.yazin.keyboard.engine.chat.ChatMemory

/**
 * Settings → Chats (chat memory spec §8): every Chat with memory, last seen first. A tap shows what is kept about it,
 * to merge it into another Chat of its app or forget it.
 */
class ChatsActivity : AppCompatActivity() {
    private lateinit var empty: TextView
    private lateinit var list: ListView
    private var chats = emptyList<ChatInfo>()
    private val labels = HashMap<String, String>()
    private lateinit var unlock: ChatUnlock
    private var resumed = false
    private var generation = 0
    private val dialogs = ArrayList<AlertDialog>()
    private val onLocked = {
        hideMemory()
        unlock.cancel()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        unlock = ChatUnlock(this)
        ChatPrivacy.observeLock(onLocked)
        setContentView(R.layout.activity_chats)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        empty = findViewById(R.id.empty)
        list = findViewById<ListView>(R.id.chats).apply { setOnItemClickListener { _, _, pos, _ -> details(chats[pos]) } }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideMemory()
        unlock.request(onUnlocked = { if (resumed) load() }, onDenied = { finish() })
    }

    override fun onPause() {
        resumed = false
        hideMemory()
        unlock.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        ChatPrivacy.stopObserving(onLocked)
        unlock.cancel()
        super.onDestroy()
    }

    private fun hideMemory() {
        generation++
        chats = emptyList()
        list.adapter = null
        list.visibility = View.INVISIBLE
        empty.visibility = View.GONE
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
    }

    private fun show(dialog: AlertDialog) {
        if (!resumed || !ChatPrivacy.unlocked()) return
        dialogs += dialog
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Runs [change] on the Chat thread, then shows the Chats as they are after it. */
    private fun load(change: (ChatMemory) -> Unit = {}) {
        if (!resumed || !ChatPrivacy.unlocked()) return
        val request = ++generation
        val app = applicationContext
        ChatFiles.later {
            val memory = ChatFiles.get(app)
            change(memory)
            ChatFiles.save(app)
            val all = listed(memory.all())
            runOnUiThread {
                if (!isDestroyed && resumed && request == generation && ChatPrivacy.unlocked()) showList(all)
            }
        }
    }

    private fun showList(all: List<ChatInfo>) {
        list.visibility = View.VISIBLE
        chats = all
        val rows = all.map { c ->
            val seen = if (c.lastSeen > 0) DateUtils.getRelativeTimeSpanString(c.lastSeen, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) else null
            mapOf("title" to c.key.title, "detail" to listOfNotNull(appLabel(c.key.app), messages(c.count), seen).joinToString(" · "))
        }
        list.adapter = SimpleAdapter(this, rows, android.R.layout.simple_list_item_2, arrayOf("title", "detail"), intArrayOf(android.R.id.text1, android.R.id.text2))
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    /** The Chat's Goal, Corrections, written Profile and Notes, read-only, with Merge into… and Forget. */
    private fun details(c: ChatInfo) {
        if (!ChatPrivacy.unlocked()) return
        val body = SpannableStringBuilder()
        fun section(title: Int, lines: List<String>) {
            if (body.isNotEmpty()) body.append("\n\n")
            body.append(getString(title), StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            for (l in lines) body.append('\n').append(l)
        }
        section(R.string.chat_section_topics, c.topics.ifEmpty { listOf(getString(R.string.chat_no_topics)) })
        section(R.string.chat_section_goal, listOf(c.goal.ifBlank { getString(R.string.chat_no_goal) }))
        if (c.corrections.isNotEmpty()) section(R.string.chats_section_corrections, c.corrections.map { "• $it" })
        section(
            R.string.chat_section_profile,
            if (c.written.isBlank()) listOf(getString(R.string.chat_profile_unwritten)) else listOf(c.written, getString(R.string.chat_profile_written, day(c.writtenAt))),
        )
        section(R.string.chat_section_notes, c.notes.map { "• ${it.text} · ${day(it.day)}" }.ifEmpty { listOf(getString(R.string.chat_no_notes)) })
        section(R.string.chat_section_history, listOf(messages(c.count)))
        val others = chats.filter { it.key.app == c.key.app && it.key != c.key }
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.chat_title, c.key.title, appLabel(c.key.app))).setMessage(body)
            .setNeutralButton(R.string.chats_forget) { _, _ -> confirmForget(c) }
            .setNegativeButton(android.R.string.ok, null)
        if (others.isNotEmpty()) dialog.setPositiveButton(R.string.chats_merge) { _, _ -> pickMerge(c, others) }
        show(dialog.create())
    }

    private fun pickMerge(from: ChatInfo, others: List<ChatInfo>) {
        show(AlertDialog.Builder(this).setTitle(getString(R.string.chats_merge_title, from.key.title))
            .setItems(others.map { "${it.key.title} · ${messages(it.count)}" }.toTypedArray()) { _, i -> confirmMerge(from, others[i]) }
            .setNegativeButton(android.R.string.cancel, null).create())
    }

    private fun confirmMerge(from: ChatInfo, into: ChatInfo) {
        show(AlertDialog.Builder(this).setMessage(getString(R.string.chats_merge_confirm, from.key.title, into.key.title))
            .setPositiveButton(R.string.chats_merge_ok) { _, _ -> load { it.merge(from.key, into.key) } }
            .setNegativeButton(android.R.string.cancel, null).create())
    }

    private fun confirmForget(c: ChatInfo) {
        show(AlertDialog.Builder(this).setMessage(getString(R.string.chats_forget_confirm, c.key.title))
            .setPositiveButton(R.string.chats_forget) { _, _ -> load { it.forget(c.key) } }
            .setNegativeButton(android.R.string.cancel, null).create())
    }

    private fun messages(n: Int) = resources.getQuantityString(R.plurals.chats_messages, n, n)

    private fun day(ms: Long) = DateUtils.formatDateTime(this, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

    private fun appLabel(app: String): String = labels.getOrPut(app) { appName(this, app) }

    companion object {
        /** [app]'s name: its launcher label, a default messaging app's known name while it isn't installed, or the package. */
        fun appName(context: Context, app: String): String {
            val pm = context.packageManager
            runCatching { return pm.getApplicationLabel(pm.getApplicationInfo(app, 0)).toString() }
            val i = context.resources.getStringArray(R.array.chat_apps_default).indexOf(app)
            return if (i >= 0) context.resources.getStringArray(R.array.chat_apps_default_names)[i] else app
        }

        /** The Chats worth listing: not the empty ones Forget leaves to keep "Don't remember", or opening the panel makes. */
        fun listed(all: List<ChatInfo>): List<ChatInfo> = all.filter {
            it.count > 0 || it.goal.isNotBlank() || it.written.isNotBlank() || it.corrections.isNotEmpty() || it.notes.isNotEmpty()
        }
    }
}
