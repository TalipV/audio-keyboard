package dev.yazin.keyboard.engine.voice

/** Spec §7.3 wrong-field protection: where a finished text goes. */
object DictationRoute {
    enum class Route { INSERT, CHIP }

    /**
     * Inserted only in the field the dictation started in, while the keyboard shows there and this process recorded it.
     * Words spoken as a [prompt] (✦) are an instruction, never narration: when they don't reach the orb they are a chip.
     */
    fun of(chunk: ChunkInfo, currentTarget: String?, recovered: Boolean, prompt: Boolean = false): Route = when {
        !prompt && !recovered && currentTarget != null && currentTarget == chunk.target -> Route.INSERT
        else -> Route.CHIP
    }
}
