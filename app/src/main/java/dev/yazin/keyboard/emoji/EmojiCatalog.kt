package dev.yazin.keyboard.emoji

import android.content.Context
import android.graphics.Paint
import dev.yazin.keyboard.engine.emoji.Annotations
import dev.yazin.keyboard.engine.emoji.EMOJI_LANGS
import dev.yazin.keyboard.engine.emoji.EmojiGroup
import dev.yazin.keyboard.engine.emoji.EmojiIndex
import dev.yazin.keyboard.engine.emoji.EmojiTest

/** The panel's emoji (spec §8): the build-time asset, minus what this device's font can't draw. Loaded once, off the main thread. */
object EmojiCatalog {
    @Volatile private var groups: List<EmojiGroup>? = null
    @Volatile private var index: EmojiIndex? = null
    @Volatile private var indexing = false

    /** Starts loading; [get] is empty until ready (a fraction of a second after the keyboard starts). */
    fun preload(context: Context) {
        if (groups == null) Thread({ load(context.applicationContext) }, "emoji").start()
    }

    fun get(): List<EmojiGroup> = groups ?: emptyList()

    /** Starts building the search index, the first time the panel opens; [onReady] runs on its thread once it is built. */
    fun preloadIndex(context: Context, onReady: () -> Unit) {
        if (index != null || indexing) return
        indexing = true
        Thread({
            loadIndex(context.applicationContext)
            onReady()
        }, "emoji-index").start()
    }

    /** Null until [preloadIndex] has built it. */
    fun index(): EmojiIndex? = index

    @Synchronized
    private fun load(context: Context) {
        if (groups != null) return
        val paint = Paint()
        val all = context.assets.open("emoji.txt").bufferedReader().useLines { EmojiTest.read(it) }
        groups = EmojiTest.supported(all) { paint.hasGlyph(it) }
    }

    @Synchronized
    private fun loadIndex(context: Context) {
        if (index != null) return
        load(context)
        val words = EMOJI_LANGS.map { lang -> context.assets.open("emoji-words-$lang.txt").bufferedReader().useLines { Annotations.read(it) } }
        index = EmojiIndex(get(), words)
    }
}
