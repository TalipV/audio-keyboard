package dev.yazin.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldTest {
    @Test fun latinFoldsCaseAndAccents() {
        assertEquals('e', Fold.key('É'))
        assertEquals('c', Fold.key('ç'))
        assertEquals('n', Fold.key('ñ'))
        assertEquals('a', Fold.key('A'))
        assertEquals('ß', Fold.key('ß'))
    }

    @Test fun arabicFoldsTheFormsPeopleTypeWithoutHamza() {
        for (c in "أإآٱ") assertEquals('ا', Fold.key(c))
        assertEquals('ه', Fold.key('ة'))
        assertEquals('ي', Fold.key('ى'))
        assertEquals('و', Fold.key('ؤ'))
        assertEquals('ي', Fold.key('ئ'))
        assertEquals('ب', Fold.key('ب'))
    }

    @Test fun marksAndApostrophesAreSkippable() {
        for (c in "َّْـ'’") assertTrue("$c", Fold.isSkippable(c))
        assertFalse(Fold.isSkippable('a'))
        assertFalse(Fold.isSkippable('ب'))
    }

    @Test fun wordsFoldToTheirTypingForm() {
        assertEquals("dont", Fold.word("Don't"))
        assertEquals("مدرسه", Fold.word("مَدْرَسَة"))
        assertEquals("انا", Fold.word("أنا"))
    }
}
