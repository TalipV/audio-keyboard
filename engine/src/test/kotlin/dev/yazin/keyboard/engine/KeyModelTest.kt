package dev.yazin.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyModelTest {
    private val keys = KeyModel(
        mapOf('e' to listOf(Touch(2f, 0f)), 'r' to listOf(Touch(3f, 0f)), 'ه' to listOf(Touch(7f, 0f)), 'ة' to listOf(Touch(5f, 2f))),
        pitchX = 1f, pitchY = 1f,
    )

    @Test fun costIsZeroAtTheKeyCentreAndGrowsWithDistance() {
        assertEquals(0f, keys.cost(Touch(2f, 0f), 'e', sigma = 0.5f, far = 9f), 1e-6f)
        assertEquals(2f, keys.cost(Touch(3f, 0f), 'e', sigma = 0.5f, far = 9f), 1e-6f) // one pitch away: 1 / (2·0.25)
    }

    @Test fun costIsCappedAndMissingKeysCostFar() {
        assertEquals(9f, keys.cost(Touch(30f, 0f), 'e', sigma = 0.5f, far = 9f), 1e-6f)
        assertEquals(9f, keys.cost(Touch(2f, 0f), 'z', sigma = 0.5f, far = 9f), 1e-6f)
        assertFalse(keys.has('z'))
    }

    @Test fun foldedKeysUseTheNearestOfTheirVariants() {
        assertTrue(keys.has('ة'))
        // ة and ه fold together, so a touch on the ة key is a perfect ه.
        assertEquals(0f, keys.cost(Touch(5f, 2f), 'ه', sigma = 0.5f, far = 9f), 1e-6f)
    }
}
