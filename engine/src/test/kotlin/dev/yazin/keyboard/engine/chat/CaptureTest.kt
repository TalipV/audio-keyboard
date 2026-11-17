package dev.yazin.keyboard.engine.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureTest {
    @Test fun aDifferentTitleOnHideStoresNothing() =
        assertNull(Capture.target(ChatKey("com.whatsapp", "Nora"), ChatKey("com.whatsapp", "Sam")))

    @Test fun theSameTitleOnHideStores() {
        assertEquals(ChatKey("com.whatsapp", "Nora"), Capture.target(ChatKey("com.whatsapp", "Nora"), ChatKey("com.whatsapp", "Nora")))
        assertNull(Capture.target(null, null))
        assertNull(Capture.target(ChatKey("com.whatsapp", "Nora"), null))
        assertNull(Capture.target(null, ChatKey("com.whatsapp", "Nora")))
    }
}
