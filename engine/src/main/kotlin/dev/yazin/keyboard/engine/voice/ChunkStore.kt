package dev.yazin.keyboard.engine.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

/** One piece of a dictation. [id] = "<dictation start ms>-<seq>", so ids sort in spoken order across dictations. */
data class ChunkInfo(
    val id: String,
    val language: String,
    val prompt: String,
    /** "package/fieldId" the dictation started in (spec §7.3 wrong-field protection). */
    val target: String,
    val createdAt: Long,
    /** Set once transcribed; the audio is deleted then. */
    val text: String? = null,
    /** Set when the server refused it (4xx); the audio is kept for Retry. */
    val error: String? = null,
)

/**
 * Chunks on disk (spec §7.3, §4). `<id>.wav` is written before the first request and deleted once the text is safe;
 * `<id>.meta` holds the rest until the text has been used. Every write is atomic (temp file, fsync, rename).
 */
class ChunkStore(private val dir: File) {
    init {
        dir.mkdirs()
    }

    fun save(info: ChunkInfo, wav: ByteArray) {
        write(File(dir, "${info.id}.wav"), wav)
        saveInfo(info)
    }

    fun saveInfo(info: ChunkInfo) {
        val p = Properties()
        p["language"] = info.language
        p["prompt"] = info.prompt
        p["target"] = info.target
        p["createdAt"] = info.createdAt.toString()
        info.text?.let { p["text"] = it }
        info.error?.let { p["error"] = it }
        val out = ByteArrayOutputStream()
        p.store(out, null)
        write(File(dir, "${info.id}.meta"), out.toByteArray())
    }

    fun wav(id: String): ByteArray? = File(dir, "$id.wav").takeIf { it.isFile }?.readBytes()

    fun deleteAudio(id: String) {
        File(dir, "$id.wav").delete()
    }

    fun delete(id: String) {
        deleteAudio(id)
        File(dir, "$id.meta").delete()
    }

    /** Everything stored, in spoken order. Deletes what's older than [maxAgeMs], damaged, or lost its audio untranscribed. */
    fun load(now: Long, maxAgeMs: Long = DAY_MS): List<ChunkInfo> {
        val out = ArrayList<ChunkInfo>()
        for (f in dir.listFiles().orEmpty()) {
            val id = f.name.substringBeforeLast('.')
            when {
                f.name.endsWith(".tmp") -> f.delete()
                f.name.endsWith(".wav") -> if (!File(dir, "$id.meta").isFile) f.delete()
                f.name.endsWith(".meta") -> {
                    val info = read(id, f)
                    val lost = info != null && info.text == null && !File(dir, "$id.wav").isFile
                    if (info == null || info.createdAt < now - maxAgeMs || lost) delete(id) else out += info
                }
            }
        }
        return out.sortedBy { it.id }
    }

    private fun read(id: String, f: File): ChunkInfo? = try {
        val p = Properties().apply { f.inputStream().use { load(it) } }
        ChunkInfo(
            id, p.getProperty("language")!!, p.getProperty("prompt").orEmpty(), p.getProperty("target")!!,
            p.getProperty("createdAt")!!.toLong(), p.getProperty("text"), p.getProperty("error"),
        )
    } catch (_: Exception) {
        null
    }

    private fun write(target: File, bytes: ByteArray) {
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("can't write ${target.name}")
        }
    }

    companion object {
        const val DAY_MS = 24 * 3_600_000L
    }
}
