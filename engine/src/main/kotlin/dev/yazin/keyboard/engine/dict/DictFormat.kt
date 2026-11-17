package dev.yazin.keyboard.engine.dict

/**
 * Binary dictionary v1, big-endian.
 *
 * - **Header (32 bytes):** magic, version, nodeCount, bigramCount, shortcutCount, poolSize, 2 reserved ints.
 * - **Nodes (nodeCount × 8 bytes):** u16 char, i32 firstChild, u8 childCount | 0x80 if offensive,
 *   u8 freq + 1 (0 = not a word). Breadth-first order, so every node's children are contiguous and sorted.
 *   Node 0 is the root.
 * - **Bigrams (bigramCount × 9 bytes):** i32 source node, i32 pool offset of the next word, u8 rank.
 *   Sorted by source, then rank.
 * - **Shortcuts (shortcutCount × 8 bytes):** i32 source node, i32 pool offset of the replacement. Sorted by source.
 * - **Pool (poolSize bytes):** strings, each as u8 length + UTF-8 bytes.
 */
object DictFormat {
    const val MAGIC = 0x4B424431 // "KBD1"
    const val VERSION = 1
    const val HEADER = 32
    const val NODE = 8
    const val BIGRAM = 9
    const val SHORTCUT = 8
    const val OFFENSIVE = 0x80
}
