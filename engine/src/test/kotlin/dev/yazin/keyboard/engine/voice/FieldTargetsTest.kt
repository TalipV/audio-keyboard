package dev.yazin.keyboard.engine.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FieldTargetsTest {
    private val t = FieldTargets()

    @Test fun aRestartedFieldKeepsItsTarget() {
        val a = t.start("com.whatsapp", 7, sameField = false)
        assertEquals(a, t.start("com.whatsapp", 7, sameField = true))
    }

    @Test fun theSameViewIdInANewSessionIsAnotherTarget() {
        // Every chat's message box has the same view id: chat B must not receive chat A's dictation.
        val chatA = t.start("com.whatsapp", 7, sameField = false)
        val chatB = t.start("com.whatsapp", 7, sameField = false)
        assertNotEquals(chatA, chatB)
    }

    @Test fun aRecreatedKeyboardServiceNeverReusesATarget() {
        // Switching keyboards and back creates a new service (and FieldTargets) in the same process.
        val before = t.start("com.whatsapp", 7, sameField = false)
        val after = FieldTargets().start("com.whatsapp", 7, sameField = false)
        assertNotEquals(before, after)
    }
}
