package dev.yazin.keyboard.voice

import android.content.Context

/** Release builds always use the mic. */
object DebugAudio {
    @Suppress("UNUSED_PARAMETER")
    fun source(context: Context): AudioSource? = null
}
