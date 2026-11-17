package dev.yazin.keyboard.keyboard

import dev.yazin.keyboard.engine.KeyModel
import dev.yazin.keyboard.engine.Touch

/** The spatial model for this grid: one centre per single-letter key (not the number row), pitch from the letter rows. */
fun KeyGrid.keyModel(): KeyModel {
    val centers = HashMap<Char, MutableList<Touch>>()
    for (k in keys) {
        if (k.key.action == KeyAction.TEXT && k.key.label.length == 1 && k.row == RowKind.LETTER) {
            centers.getOrPut(k.key.label[0]) { ArrayList() } += Touch(k.centerX, k.centerY)
        }
    }
    val letters = keys.filter { it.row == RowKind.LETTER }
    val firstRow = letters.filter { it.top == letters.first().top }
    val pitchX = if (firstRow.size > 1) firstRow[1].left - firstRow[0].left else 1f
    val pitchY = letters.map { it.top }.distinct().sorted().let { if (it.size > 1) it[1] - it[0] else 1f }
    return KeyModel(centers, pitchX, pitchY)
}
