package dev.yazin.keyboard.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/** prefs.xml's defaults are what [Prefs] reads before the settings screen first opens; they must agree. */
class PrefsDefaultsTest {
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File("src/main/res/$path")).documentElement

    private fun Element.all(tag: String) = getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }

    private fun pref(key: String) = xml("xml/prefs.xml").all("*").single { it.getAttributeNS(APP, "key") == key }

    @Test
    fun chatAppsDefaultIsPrefsDefault() {
        assertEquals("@array/chat_apps_default", pref("chat_apps").getAttributeNS(APP, "defaultValue"))
        val arrays = xml("values/arrays.xml").all("string-array")
        val apps = arrays.single { it.getAttribute("name") == "chat_apps_default" }.all("item").map { it.textContent.trim() }
        assertEquals(Prefs.DEFAULT_CHAT_APPS, apps.toSet())
        assertEquals(apps.size, arrays.single { it.getAttribute("name") == "chat_apps_default_names" }.all("item").size)
    }

    @Test
    fun aiLearningIsOffByDefault() {
        // Nothing is learned, remembered or logged for a new install until the user turns it on (Prefs.keepOldDefaults).
        for (key in listOf("style", "chats_remember", "replies_waiting", "debug_log", "lock_chats"))
            assertEquals(key, "false", pref(key).getAttributeNS(APP, "defaultValue"))
    }

    private companion object {
        const val APP = "http://schemas.android.com/apk/res-auto"
    }
}
