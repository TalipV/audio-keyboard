package dev.yazin.keyboard.clip

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Copied images in the clipboard history: copied into app storage (the copying app's permission to read them ends),
 * named by their content so the same image copied twice is one file, and handed back to apps through a FileProvider.
 * Excluded from backup like everything else (data_extraction_rules.xml).
 */
object ClipImages {
    /** Bigger images aren't kept (a phone screenshot is 1–5 MB); they still paste from the system clipboard. */
    private const val MAX_BYTES = 20 * 1024 * 1024

    private fun dir(context: Context) = File(context.filesDir, "clipboard-images").apply { mkdirs() }

    fun file(context: Context, name: String) = File(dir(context), name)

    fun uri(context: Context, name: String): Uri = FileProvider.getUriForFile(context, "${context.packageName}.clips", file(context, name))

    fun mime(name: String): String = when (name.substringAfterLast('.')) {
        "jpg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    /** Copies the image at [uri] in; returns its file name, or null when it can't be read or is too big. Does IO. */
    fun save(context: Context, uri: Uri, mime: String): String? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) return null
            }
            out.toByteArray()
        } ?: return null
        if (bytes.isEmpty()) return null
        val ext = when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "png"
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(32)
        val name = "$hash.$ext"
        val f = file(context, name)
        if (!f.exists()) {
            val tmp = File(f.parentFile, "$name.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
        }
        return name
    }

    fun delete(context: Context, name: String) {
        file(context, name).delete()
    }

    /** Deletes files no clip refers to (left behind by a crash between saving the file and the history). */
    fun sweep(context: Context, keep: Set<String>) {
        dir(context).listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }
}
