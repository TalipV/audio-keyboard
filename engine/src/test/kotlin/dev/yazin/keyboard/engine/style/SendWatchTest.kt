package dev.yazin.keyboard.engine.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendWatchTest {
    private val w = SendWatch()

    @Test fun theAppEmptyingTheFieldIsASend() {
        w.read("who doesn't love free stuff")
        assertEquals("who doesn't love free stuff", w.check(""))
        assertNull(w.check("")) // reported once
    }

    @Test fun anEditBeforeTheClearIsNotASend() {
        w.read("oops")
        w.edited() // select-all + delete on the keyboard
        assertNull(w.check(""))
    }

    @Test fun textThatChangesButStaysIsNotASend() {
        w.read("see you")
        assertNull(w.check("see you at 8")) // the app changed it (autocomplete); still a draft
        assertEquals("see you at 8", w.check(""))
    }

    @Test fun unreadableOrBlankFieldsAreNeverSends() {
        w.read(null)
        assertNull(w.check(""))
        w.read("   ")
        assertNull(w.check(""))
        w.read("hi")
        assertNull(w.check(null)) // the app won't say: keep what was read
        assertEquals("hi", w.check(" "))
    }

    @Test fun resetForgets() {
        w.read("hi")
        w.reset()
        assertNull(w.check(""))
    }

    @Test fun aRestartIntoAnotherFieldIsNotASend() {
        w.read("my home address", field = "com.android.chrome|web|Address")
        assertNull(w.check("", field = "com.android.chrome|web|Search")) // a WebView restarts input between its fields
        assertNull(w.check("", field = "com.android.chrome|web|Address")) // and what was read is forgotten
    }
}
