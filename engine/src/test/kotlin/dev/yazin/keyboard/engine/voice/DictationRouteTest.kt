package dev.yazin.keyboard.engine.voice

import dev.yazin.keyboard.engine.voice.DictationRoute.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class DictationRouteTest {
    private val chunk = ChunkInfo("1-0001", "en", "", "com.app/42", 0)

    @Test fun theSameFieldGetsTheText() = assertEquals(Route.INSERT, DictationRoute.of(chunk, "com.app/42", recovered = false))

    @Test fun anotherFieldGetsAChip() = assertEquals(Route.CHIP, DictationRoute.of(chunk, "com.app/43", recovered = false))

    @Test fun aHiddenKeyboardGetsAChip() = assertEquals(Route.CHIP, DictationRoute.of(chunk, null, recovered = false))

    @Test fun textFromBeforeARestartIsAChip() = assertEquals(Route.CHIP, DictationRoute.of(chunk, "com.app/42", recovered = true))

    @Test fun promptWordsAreNeverInsertedAsNarration() =
        assertEquals(Route.CHIP, DictationRoute.of(chunk, "com.app/42", recovered = false, prompt = true))
}
