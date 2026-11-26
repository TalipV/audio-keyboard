package dev.yazin.keyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {
    private fun labels(row: Row) = row.keys.joinToString("") { it.label }

    @Test fun englishMatchesGboardWithNumberRow() {
        val l = Layouts.build(LayoutSpec())
        assertEquals(5, l.rows.size)
        assertEquals("1234567890", labels(l.rows[0]))
        assertEquals(RowKind.NUMBER, l.rows[0].kind)
        assertEquals("qwertyuiop", labels(l.rows[1]))
        assertEquals("asdfghjkl", labels(l.rows[2]))
        assertEquals("zxcvbnm", labels(l.rows[3]))
        assertEquals(KeyAction.SHIFT, l.rows[3].keys.first().action)
        assertEquals(KeyAction.DELETE, l.rows[3].keys.last().action)
        assertEquals(
            listOf(KeyAction.SYMBOLS, KeyAction.TEXT, KeyAction.EMOJI, KeyAction.SPACE, KeyAction.TEXT, KeyAction.ENTER),
            l.rows[4].keys.map { it.action },
        )
        assertEquals(",", l.rows[4].keys[1].label)
    }

    @Test fun arabicMatchesGboard() {
        val l = Layouts.build(LayoutSpec(lang = Lang.AR))
        assertTrue(l.rtl)
        assertEquals("ضصثقفغعهخحج", labels(l.rows[1]))
        assertEquals("شسيبلاتنمكط", labels(l.rows[2]))
        assertEquals("ذءؤرىةوزظد", labels(l.rows[3]))
        assertEquals(KeyAction.DELETE, l.rows[3].keys.last().action)
        assertEquals("\u200E؟\u200E123", l.rows[4].keys[0].label)
        assertEquals("،", l.rows[4].keys[1].label)
    }

    @Test fun germanIsQwertzWithUmlautKeys() {
        val l = Layouts.build(LayoutSpec(lang = Lang.DE))
        assertTrue(!l.rtl)
        assertEquals("1234567890", labels(l.rows[0]))
        assertEquals("qwertzuiopü", labels(l.rows[1]))
        assertEquals("asdfghjklöä", labels(l.rows[2]))
        assertEquals("yxcvbnm", labels(l.rows[3]))
        assertEquals(KeyAction.SHIFT, l.rows[3].keys.first().action)
        assertEquals(KeyAction.DELETE, l.rows[3].keys.last().action)
        assertEquals("ß", l.rows[2].keys.first { it.label == "s" }.alts.first())
        assertEquals(",", l.rows[4].keys[1].label)
    }

    @Test fun withoutTheEmojiKeySpaceIsWiderAndTheCommaHoldsEmoji() {
        for (lang in Lang.values()) {
            val bottom = Layouts.build(LayoutSpec(lang = lang, emojiKey = false)).rows.last()
            assertTrue(bottom.keys.none { it.action == KeyAction.EMOJI })
            assertEquals(5f, bottom.keys.first { it.action == KeyAction.SPACE }.width)
            assertEquals(10f, bottom.units, 0.001f)
            assertEquals(KeyAction.EMOJI, bottom.keys[1].longPress)
            assertEquals(null, bottom.keys[1].hint)
        }
        assertTrue(Layouts.build(LayoutSpec()).rows.last().keys.any { it.action == KeyAction.EMOJI })
    }

    @Test fun germanLongPressesFollowSamsungThenAddDmgAndTurkish() {
        val keys = Layouts.build(LayoutSpec(lang = Lang.DE, numberRow = false)).rows.flatMap { it.keys }
        fun alts(label: String) = keys.first { it.label == label }.alts
        // Read off the owner's Samsung keyboard (number row off, so the digit leads), minus what this keyboard lacks.
        assertEquals(listOf("8", "ì", "í", "î", "ï", "ī", "į", "ı", "İ"), alts("i"))
        assertEquals(listOf("ß", "ś", "š", "ş", "ṣ"), alts("s"))
        // DMG and Turkish are all there, after Samsung's own letters.
        assertEquals(listOf("ḥ", "ḫ"), alts("h"))
        assertTrue(alts("d").containsAll(listOf("ḍ", "ḏ")))
        assertTrue(alts("t").containsAll(listOf("ṭ", "ṯ", "ẗ")))
        assertEquals("ẓ", alts("z").last())
        assertEquals(listOf("ğ", "ġ", "ǧ"), alts("g"))
        assertEquals("ç", alts("c").first())
        assertTrue(alts("a").containsAll(listOf("â", "ā")) && alts("u").containsAll(listOf("û", "ū")))
        assertTrue(keys.filter { it.alts.isNotEmpty() && it.style == KeyStyle.LETTER }.all { it.altDefault == 0 })
        val period = keys.first { it.label == "." }
        assertEquals(listOf("ʿ", "ʾ"), period.alts.take(2))
        // Every alternate is one character, so none falls apart on the panel.
        assertTrue(keys.filter { it.style == KeyStyle.LETTER }.flatMap { it.alts }.all { it.codePointCount(0, it.length) == 1 })
    }

    @Test fun languagesComeFromSubtypeTags() {
        assertEquals(Lang.DE, Lang.of("de-DE"))
        assertEquals(Lang.DE, Lang.of("de_AT"))
        assertEquals(Lang.AR, Lang.of("ar"))
        assertEquals(Lang.EN, Lang.of("en_US"))
        assertEquals(Lang.EN, Lang.of(null))
        assertEquals("de.dict", Lang.DE.dictFile)
    }

    @Test fun symbolPagesMatchSamsung() {
        val sym = Layouts.build(LayoutSpec(lang = Lang.DE, page = Page.SYMBOLS))
        assertEquals("1234567890", labels(sym.rows[0]))
        assertEquals("!@#€%^&*()", labels(sym.rows[1]))
        assertEquals("1/2-'\":;,?", labels(sym.rows[2]))
        assertEquals(KeyAction.SYMBOLS_MORE, sym.rows[2].keys[0].action)
        assertEquals(listOf(KeyAction.ALPHA, KeyAction.TEXT, KeyAction.SPACE, KeyAction.TEXT, KeyAction.ENTER), sym.rows[3].keys.map { it.action })
        assertEquals("\$", Layouts.build(LayoutSpec(page = Page.SYMBOLS)).rows[1].keys[3].label) // English keeps the dollar
        val more = Layouts.build(LayoutSpec(lang = Lang.DE, page = Page.SYMBOLS_MORE))
        assertEquals("+×÷=<>{}[]", labels(more.rows[0]))
        assertEquals("\$£¥₩/~`¤°♡", labels(more.rows[1]))
        assertEquals("2/2_\\|《》¡¿", labels(more.rows[2]))
        assertEquals("€", Layouts.build(LayoutSpec(page = Page.SYMBOLS_MORE)).rows[1].keys[0].label) // English: € here, $ on page 1
        assertEquals(KeyAction.SYMBOLS, more.rows[2].keys[0].action)
    }

    @Test fun phonePadMatchesGboard() {
        val l = Layouts.build(LayoutSpec(page = Page.PHONE))
        assertEquals(Dimens.PAD_ROW_GAP, l.rowGap, 0f)
        assertTrue(l.rows.all { it.kind == RowKind.PAD })
        assertTrue(l.rows.flatMap { it.keys }.all { it.pill })
        assertEquals(listOf("1", "2", "3", "–"), l.rows[0].keys.map { it.label })
        assertEquals("-", l.rows[0].keys[3].output)
        assertEquals(listOf("", "ABC", "DEF"), l.rows[0].keys.take(3).map { it.sublabel })
        assertEquals(listOf("PQRS", "TUV", "WXYZ"), l.rows[2].keys.take(3).map { it.sublabel })
        assertEquals(listOf(KeyStyle.LETTER, KeyStyle.LETTER, KeyStyle.LETTER, KeyStyle.FUNCTION), l.rows[1].keys.map { it.style })
        val bottom = l.rows[3].keys
        assertEquals(listOf("* #", "0", "."), bottom.take(3).map { it.label })
        assertEquals("*", bottom[0].output)
        assertEquals("#", bottom[0].alts.first())
        assertEquals("+", bottom[1].sublabel)
        assertEquals(listOf("+"), bottom[1].alts)
        assertTrue(bottom.take(3).all { it.style == KeyStyle.LETTER })
        assertEquals(KeyAction.ENTER, bottom[3].action)
    }

    @Test fun longPressOrdersMatchGboard() {
        val en = Layouts.build(LayoutSpec())
        val e = en.rows[1].keys.first { it.label == "e" }
        assertEquals(listOf("ê", "é", "è", "ë", "ē"), e.alts)
        assertEquals("é", e.alts[e.altDefault])
        val period = en.rows[4].keys[4]
        assertEquals(listOf("&", "%", "+", "\"", "-", ":", "'", "@", ";", "/", "(", ")", "#", "!", ",", "?"), period.alts)
        assertEquals(",", period.alts[period.altDefault])
        val alef = Layouts.build(LayoutSpec(lang = Lang.AR)).rows[2].keys.first { it.label == "ا" }
        assertEquals(listOf("آ", "ء", "أ", "إ", "ٱ"), alef.alts)
        assertEquals("أ", alef.alts[alef.altDefault])
        val q = Layouts.build(LayoutSpec(numberRow = false)).rows[0].keys[2]
        assertEquals("3", q.alts[q.altDefault])
    }

    @Test fun everyRowFillsTheWidth() {
        for (lang in Lang.values()) for (page in Page.values()) {
            for (row in Layouts.build(LayoutSpec(lang, page)).rows) {
                val expected = if (lang != Lang.EN && page == Page.LETTERS && row.kind == RowKind.LETTER) 11f else 10f
                assertEquals("$lang $page ${labels(row)}", expected, row.units, 0.001f)
            }
        }
    }

    @Test fun withoutNumberRowTheTopRowCarriesDigitHints() {
        val l = Layouts.build(LayoutSpec(numberRow = false))
        assertEquals(4, l.rows.size)
        val q = l.rows[0].keys[0]
        assertEquals("1", q.hint)
        assertEquals("1", q.alts.first())
        assertEquals("0", l.rows[0].keys[9].hint)
    }

    @Test fun emailAndUrlFieldsSwapTheCommaKey() {
        assertEquals("@", Layouts.build(LayoutSpec(field = Field.EMAIL)).rows.last().keys[1].label)
        val url = Layouts.build(LayoutSpec(field = Field.URL)).rows.last()
        assertEquals("/", url.keys[1].label)
        assertTrue(".com" in url.keys[4].alts)
    }

    @Test fun arabicDigitStyleFollowsTheSetting() {
        assertEquals("1234567890", labels(Layouts.build(LayoutSpec(lang = Lang.AR)).rows[0]))
        val eastern = Layouts.build(LayoutSpec(lang = Lang.AR, easternDigits = true)).rows[0]
        assertEquals("١٢٣٤٥٦٧٨٩٠", labels(eastern))
        assertEquals(listOf("1"), eastern.keys[0].alts)
    }

    @Test fun arabicPeriodOffersTashkeel() {
        val period = Layouts.build(LayoutSpec(lang = Lang.AR)).rows.last().keys[4]
        assertEquals(".", period.label)
        assertTrue("َ" in period.alts && "ّ" in period.alts)
    }

    @Test fun textKeysAlwaysProduceText() {
        for (lang in Lang.values()) for (page in Page.values()) for (field in Field.values()) {
            Layouts.build(LayoutSpec(lang, page, field)).rows.flatMap { it.keys }
                .filter { it.action == KeyAction.TEXT }
                .forEach { assertTrue("$lang $page '${it.label}'", it.output.isNotEmpty()) }
        }
    }

    @Test fun splitCodePointsKeepsSupplementaryCharactersWhole() {
        assertEquals(listOf("a", "😀", "ب"), "a😀ب".splitCodePoints())
    }
}
