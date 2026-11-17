package dev.yazin.keyboard.chat

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import dev.yazin.keyboard.engine.chat.ChatMemory
import dev.yazin.keyboard.engine.chat.ChatStorage
import dev.yazin.keyboard.style.StyleFile
import dev.yazin.keyboard.style.PicksFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Every Chat's memory (chat memory spec §4.6), one per process, in `noBackupFilesDir/chats/`. Never on the main thread. */
object ChatFiles {
    @Volatile private var memory: ChatMemory? = null
    /** Why the index couldn't be read: Chat memory is off for this process, so nothing overwrites `index.tsv`. */
    @Volatile private var unreadable: IOException? = null

    /** The one thread for screen reads into Chats, sends and saves, in the order they were asked for. */
    private val io: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "chats") }

    /**
     * Runs [task] on the Chat thread. A failure is logged: a screen read can fail as the app's window goes away, and a
     * file can fail to read.
     */
    fun later(task: () -> Unit) = io.execute {
        try {
            task()
        } catch (e: Exception) {
            Log.w(TAG, "chat task failed", e)
        }
    }

    /** Throws when `index.tsv` can't be read, then and for the rest of the process, rather than starting empty. */
    @Synchronized
    fun get(context: Context): ChatMemory {
        memory?.let { return it }
        unreadable?.let { throw IOException("Chat memory is off: the index couldn't be read", it) }
        val style = StyleFile.get(context)
        val m = try {
            ChatMemory(Storage(File(context.noBackupFilesDir, "chats")), style, PicksFile.get(context))
        } catch (e: IOException) {
            unreadable = e
            throw e
        }
        m.seed(style)
        memory = m
        return m
    }

    /** Writes what changed, with `style.tsv`: forgetting or merging a Chat changes its writing samples too. */
    fun save(context: Context) {
        memory?.save()
        StyleFile.save(context)
        PicksFile.save(context)
    }

    private class Storage(private val dir: File) : ChatStorage {
        /** Null only when there is no such file: one that exists but can't be opened throws, like a failed read. */
        override fun read(name: String): List<String>? = try {
            AtomicFile(File(dir, name)).openRead().bufferedReader().useLines { it.toList() }
        } catch (e: FileNotFoundException) {
            if (File(dir, name).exists()) throw e
            null
        }

        override fun write(name: String, lines: (Appendable) -> Unit) {
            dir.mkdirs()
            val f = AtomicFile(File(dir, name))
            val out = try { f.startWrite() } catch (_: IOException) { return }
            try {
                val w = out.writer()
                lines(w)
                w.flush()
                f.finishWrite(out)
            } catch (_: IOException) {
                f.failWrite(out)
            }
        }

        override fun delete(name: String) = AtomicFile(File(dir, name)).delete()
    }

    private const val TAG = "KbdChats"
}
