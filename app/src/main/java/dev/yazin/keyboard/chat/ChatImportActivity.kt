package dev.yazin.keyboard.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import dev.yazin.keyboard.R
import dev.yazin.keyboard.engine.chat.ChatKey
import dev.yazin.keyboard.engine.style.ChatLine
import dev.yazin.keyboard.engine.style.WhatsAppExport
import dev.yazin.keyboard.settings.Prefs
import dev.yazin.keyboard.style.StyleFile
import java.util.zip.ZipInputStream

/**
 * The share target for WhatsApp's "Export chat" (writing-style spec §2, chat memory spec §4.2): keeps the user's own
 * lines as samples and the whole chat in its Chat. It has no window of its own: just "Which one is you?", then a toast.
 */
class ChatImportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return finish() // rotated mid-import: the dialog is gone, start over from WhatsApp
        val uri = if (intent.action == Intent.ACTION_SEND) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) else null
        if (uri == null) return done(getString(R.string.style_import_empty))
        Thread({
            val chat = runCatching { read(uri) }.onFailure { Log.w(TAG, "can't read the export: ${it.javaClass.simpleName}") }.getOrNull()
            runOnUiThread { if (!isDestroyed) onRead(chat) }
        }, "chat-import").start()
    }

    /** The chat's title and messages. A .zip export holds the chat as its .txt entry. */
    private fun read(uri: Uri): Pair<String, List<ChatLine>> {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }.orEmpty()
        val lines = contentResolver.openInputStream(uri)!!.buffered().use { input ->
            input.mark(2)
            val zip = input.read() == 'P'.code && input.read() == 'K'.code
            input.reset()
            if (!zip) return@use input.bufferedReader().readLines()
            val z = ZipInputStream(input)
            generateSequence { z.nextEntry }.firstOrNull { it.name.endsWith(".txt") } ?: return@use emptyList()
            z.bufferedReader().readLines()
        }
        return WhatsAppExport.title(name) to WhatsAppExport.parse(lines.asSequence())
    }

    private fun onRead(read: Pair<String, List<ChatLine>>?) {
        val (title, chat) = read ?: return done(getString(R.string.style_import_empty))
        val prefs = Prefs(this)
        // Always asked, the remembered sender first: any app can share here, and a mis-tap must not stick.
        val senders = WhatsAppExport.senders(chat, prefs.styleOwner)
        if (senders.isEmpty()) return done(getString(R.string.style_import_empty))
        AlertDialog.Builder(this).setTitle(R.string.style_import_who)
            .setItems(senders.toTypedArray()) { _, i ->
                prefs.styleOwner = senders[i]
                keep(title, chat, senders[i])
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun keep(title: String, chat: List<ChatLine>, owner: String) {
        val app = applicationContext
        ChatFiles.later {
            val message = runCatching {
                // Chat memory first: its one-time seeding copies style.tsv, and must not copy this import's lines too.
                val chats = ChatFiles.get(app)
                val key = ChatKey.of(WHATSAPP, title, "WhatsApp")
                if (key != null && chats.info(key)?.remember == false) return@runCatching app.getString(R.string.style_import_not_remembered)
                val mine = StyleFile.get(app).import(chat.filter { it.sender == owner }.map { it.text }, WHATSAPP, title)
                StyleFile.save(app)
                if (key == null) {
                    app.getString(R.string.style_import_done_untitled, mine)
                } else {
                    val n = chats.import(key, chat, owner)
                    ChatFiles.save(app)
                    app.getString(R.string.style_import_done, n, key.title)
                }
            }.getOrElse {
                Log.w(TAG, "can't keep the export: ${it.javaClass.simpleName}")
                app.getString(R.string.style_import_failed)
            }
            runOnUiThread { done(message) }
        }
    }

    private fun done(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private companion object {
        const val WHATSAPP = "com.whatsapp"
        const val TAG = "KbdStyle"
    }
}
