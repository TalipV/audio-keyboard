package dev.yazin.keyboard.engine.voice

import java.net.URI

/** Spec §4: plain http is fine on a private network; warn when audio would cross the internet unencrypted. */
object PrivateHost {
    fun insecure(url: String): Boolean {
        val uri = try { URI(url.trim()) } catch (_: Exception) { return false }
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        val host = uri.host ?: return false
        return !isPrivate(host)
    }

    fun isPrivate(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || (!h.contains('.') && !h.contains(':'))) return true // single-label names like mybox
        if (h.endsWith(".local") || h.endsWith(".ts.net")) return true
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127) // 100.64/10: Tailscale
        }
        if (h.contains(':')) return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.matches(Regex("fe[89ab].*"))
        return false
    }
}
