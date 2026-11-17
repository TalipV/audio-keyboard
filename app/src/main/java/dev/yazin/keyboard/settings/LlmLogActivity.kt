package dev.yazin.keyboard.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.PersistableBundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SimpleAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import dev.yazin.keyboard.R
import dev.yazin.keyboard.engine.llm.LlmCall
import dev.yazin.keyboard.llm.LlmLogFile
import dev.yazin.keyboard.chat.ChatUnlock

/** The last LLM calls, newest first; a tap shows the whole request and reply to copy or share (e.g. into a coding-agent session). */
class LlmLogActivity : AppCompatActivity() {
    private lateinit var empty: TextView
    private lateinit var list: ListView
    private var calls = emptyList<LlmCall>()
    /** Lock chat memory: the log holds chat text, so nothing is listed until the unlock succeeds. */
    private lateinit var unlock: ChatUnlock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_log)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        empty = findViewById(R.id.empty)
        list = findViewById<ListView>(R.id.calls).apply { setOnItemClickListener { _, _, pos, _ -> show(calls[pos]) } }
        unlock = ChatUnlock(this)
    }

    override fun onResume() {
        super.onResume()
        list.visibility = View.INVISIBLE
        // Calls arrive while the keyboard is in use, so the list is read on every return.
        unlock.request(onUnlocked = { list.visibility = View.VISIBLE; refresh() }, onDenied = ::finish)
    }

    override fun onPause() {
        unlock.cancel()
        super.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.llm_log_clear)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != 1) return super.onOptionsItemSelected(item)
        AlertDialog.Builder(this).setMessage(R.string.llm_log_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> LlmLogFile.get(this).clear(); LlmLogFile.save(this); refresh() }
            .setNegativeButton(android.R.string.cancel, null).show()
        return true
    }

    private fun refresh() {
        calls = LlmLogFile.get(this).all()
        val rows = calls.map {
            mapOf(
                "head" to "${it.id} · ${it.stamp().substring(5, 16)} · ${it.app}",
                "detail" to "${if (it.ok) "ok" else "failed"} · ${it.ms} ms · ${it.instruction()}",
            )
        }
        list.adapter = SimpleAdapter(this, rows, android.R.layout.simple_list_item_2, arrayOf("head", "detail"), intArrayOf(android.R.id.text1, android.R.id.text2))
        empty.visibility = if (calls.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun show(call: LlmCall) {
        val text = call.text()
        val body = TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this).setTitle(call.id).setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(R.string.llm_log_copy) { _, _ ->
                val clip = ClipData.newPlainText(call.id, text)
                // The log holds chat text: keep it out of the keyboard's own clipboard history.
                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }
            .setNeutralButton(R.string.llm_log_share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    .putExtra(Intent.EXTRA_SUBJECT, text.lineSequence().first())
                startActivity(Intent.createChooser(send, null))
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
