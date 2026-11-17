package dev.yazin.keyboard.engine.dict

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryTest {
    private val list = """
        dictionary=main:en,locale=en
         word=the,f=222
          bigram=first,f=1
          bigram=same,f=2
         word=them,f=190
         word=then,f=180
         word=sex,f=135,possibly_offensive=true
         word=im,f=0,not_a_word=true
          shortcut=I'm,f=whitelist
         word=nave,f=57
          shortcut=have,f=whitelist
         word=café,f=90
         word=rare,f=1
    """.trimIndent()

    private fun dict(max: Int = Int.MAX_VALUE) =
        Dictionary(ByteBuffer.wrap(DictWriter.build(WordList.parse(list.lineSequence()), max)))

    @Test fun findsWordsAndTheirFrequencies() {
        val d = dict()
        assertEquals(222, d.freq(d.find("the")))
        assertEquals(-1, d.freq(d.find("th")))
        assertEquals(-1, d.find("xyz"))
        assertEquals(90, d.freq(d.find("café")))
    }

    @Test fun childrenAreContiguousAndSorted() {
        val d = dict()
        val the = d.find("the")
        val kids = (0 until d.childCount(the)).map { d.char(d.firstChild(the) + it) }
        assertEquals(listOf('m', 'n'), kids)
        assertEquals(d.find("then"), d.child(the, 'n'))
        assertEquals(-1, d.child(the, 'z'))
    }

    @Test fun nextWordsComeBestFirst() {
        val d = dict()
        assertEquals(listOf("first", "same"), d.nextWords(d.find("the")))
        assertEquals(emptyList<String>(), d.nextWords(d.find("them")))
    }

    @Test fun notAWordEntriesOnlyCarryTheirShortcut() {
        val d = dict()
        val im = d.find("im")
        assertEquals(-1, d.freq(im))
        assertEquals("I'm", d.shortcut(im))
        assertEquals("have", d.shortcut(d.find("nave")))
        assertNull(d.shortcut(d.find("the")))
    }

    @Test fun offensiveWordsAreFlagged() {
        val d = dict()
        assertTrue(d.isOffensive(d.find("sex")))
        assertEquals(false, d.isOffensive(d.find("the")))
    }

    @Test fun maxWordsKeepsTheMostFrequentAndAllShortcuts() {
        val d = dict(max = 3)
        assertEquals(222, d.freq(d.find("the")))
        assertEquals(-1, d.find("rare"))
        assertEquals("I'm", d.shortcut(d.find("im")))
        assertEquals("have", d.shortcut(d.find("nave")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherFiles() {
        Dictionary(ByteBuffer.wrap(ByteArray(64)))
    }
}
