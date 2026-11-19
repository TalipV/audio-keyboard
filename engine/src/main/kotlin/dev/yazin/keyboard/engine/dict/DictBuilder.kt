package dev.yazin.keyboard.engine.dict

import java.io.File

/** Arabic keeps its 300k most frequent forms (≈3.7 MB); English keeps all 280k words (≈7.6 MB). */
private const val ARABIC_MAX_WORDS = 300_000

/** German keeps its 250k most frequent forms of 1.3M (≈8.7 MB); [Compounds] covers the long tail of compounds. */
private const val GERMAN_MAX_WORDS = 250_000

/** `:engine:buildDicts` entry point. Args: <word-list dir> <output dir>. */
fun main(args: Array<String>) {
    val src = File(args[0])
    val out = File(args[1]).apply { mkdirs() }
    for ((list, name, max) in listOf(
        Triple("main_en_US.combined", "en.dict", Int.MAX_VALUE),
        Triple("main_ar.combined", "ar.dict", ARABIC_MAX_WORDS),
        Triple("main_de.combined", "de.dict", GERMAN_MAX_WORDS),
    )) {
        val file = File(src, list)
        require(file.exists()) { "Missing $file: run tools/fetch_wordlists.sh" }
        val bytes = DictWriter.build(file.useLines { WordList.parse(it) }, max)
        File(out, name).writeBytes(bytes)
        println("$name: ${bytes.size / 1024} KB")
    }
}
