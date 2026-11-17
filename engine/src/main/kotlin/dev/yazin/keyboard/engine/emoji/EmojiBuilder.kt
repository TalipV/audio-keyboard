package dev.yazin.keyboard.engine.emoji

import java.io.File

/** Search words are built for these languages, from annotations-<lang>.xml and annotations-derived-<lang>.xml. */
val EMOJI_LANGS = listOf("en", "ar")

/**
 * :engine:buildEmoji: emoji-test.txt → emoji.txt, the panel's asset, and CLDR's annotations → emoji-words-<lang>.txt,
 * its search words. Args: <tools/emoji dir> <out dir>.
 */
fun main(args: Array<String>) {
    val src = File(args[0])
    val test = File(src, "emoji-test.txt")
    val groups = test.useLines { EmojiTest.parse(it) }
    val out = File(args[1]).apply { mkdirs() }
    File(out, "emoji.txt").bufferedWriter().use { EmojiTest.write(groups, it) }
    println("emoji.txt: ${groups.size} groups, ${groups.sumOf { it.emoji.size }} emoji")
    for (lang in EMOJI_LANGS) {
        // annotations/ first: annotationsDerived/ only adds what it lacks (flags, keycaps, people with a role). CLDR
        // leaves out a few (untoned families, subdivision flags), which keep at least their English name.
        val sources = listOf("annotations-$lang.xml", "annotations-derived-$lang.xml").map { File(src, it).useLines(block = Annotations::parse) } +
            if (lang == "en") listOf(test.useLines { EmojiTest.names(it) }) else emptyList()
        val file = File(out, "emoji-words-$lang.txt")
        file.bufferedWriter().use { Annotations.write(groups, sources, it) }
        println("${file.name}: ${file.useLines { it.count() }} emoji, ${file.length() / 1024} KB")
    }
}
