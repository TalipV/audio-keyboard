package dev.yazin.keyboard.engine.llm

import org.junit.Assert.*
import org.junit.Test

class ProfilePromptTest {
    @Test fun separatesTopicsFromProfileAndSupportsOlderResponses() {
        val result = ProfilePrompt.parse("Cousin.\nTOPICS:\n- Learning pottery: how was class?\n\nLearning pottery: how was class?")
        assertEquals("Cousin.", result.profile)
        assertEquals(listOf("Learning pottery: how was class?"), result.topics)
        assertNull(ProfilePrompt.parse("Cousin.").topics)
        assertEquals(emptyList<String>(), ProfilePrompt.parse("Cousin.\nTOPICS:").topics)
    }

    @Test fun previousTopicsAreProvidedToPreserveEvergreenInterests() {
        assertTrue(ProfilePrompt.user("Sam", emptyList(), "Cousin.", "Sam: hi", listOf("Pottery"))
            .contains("## Previous topics\nPottery"))
    }
}
