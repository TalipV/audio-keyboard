package dev.yazin.keyboard.engine.emoji

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiSearchTest {
    private val xml = """
        <annotation cp="😀">face | grin | grinning face</annotation>
        <annotation cp="😀" type="tts">grinning face</annotation>
        <annotation cp="☺">face | relaxed | smile</annotation>
        <annotation cp="☺" type="tts">smiling face</annotation>
        <annotation cp="&amp;">ampersand | and</annotation>
        <annotation cp="🫠">↑↑↑</annotation>
        <annotation cp="❤">heart | red heart</annotation> <!-- 2764 FE0F -->
        <annotation cp="❤" type="tts">red heart</annotation>
    """.trimIndent()

    @Test fun parsesNamesAndKeywordsUnescapedAndSkipsInheritedOnes() {
        val words = Annotations.parse(xml.lineSequence())
        assertEquals(EmojiWords("grinning face", listOf("face", "grin", "grinning face")), words["😀"])
        assertEquals(EmojiWords("", listOf("ampersand", "and")), words["&"])
        assertEquals(null, words["🫠"])
    }

    @Test fun theAssetKeysByTheCatalogsBaseDropsTheNameKeywordAndRoundTrips() {
        val groups = listOf(EmojiGroup("Smileys & Emotion", listOf(Emoji("😀"), Emoji("☺️"), Emoji("🫥"), Emoji("❤️"))))
        val derived = mapOf("❤" to EmojiWords("derived", emptyList()), "🫥" to EmojiWords("dotted line face", emptyList()))
        val out = StringBuilder()
        Annotations.write(groups, listOf(Annotations.parse(xml.lineSequence()), derived), out)
        assertEquals(
            "😀\tgrinning face\tface|grin\n☺️\tsmiling face\tface|relaxed|smile\n🫥\tdotted line face\t\n❤️\tred heart\theart\n",
            out.toString(),
        )
        val read = Annotations.read(out.lineSequence())
        assertEquals(EmojiWords("dotted line face", emptyList()), read["🫥"])
        assertEquals(EmojiWords("red heart", listOf("heart")), read["❤️"])
    }

    @Test fun emojiTestNamesCoverWhatCldrLacks() {
        val names = EmojiTest.names(
            sequenceOf(
                "0023 FE0F 20E3 ; fully-qualified # #️⃣ E0.6 keycap: #",
                "0023 20E3      ; unqualified    # #⃣ E0.6 keycap: #",
                "1F468 200D 1F469 200D 1F467 ; fully-qualified # 👨‍👩‍👧 E2.0 family: man, woman, girl",
            ),
        )
        assertEquals(EmojiWords("keycap: #", emptyList()), names["#⃣"])
        assertEquals("family: man, woman, girl", names["👨‍👩‍👧"]?.name)
        assertEquals(2, names.size)
    }

    private fun e(base: String) = Emoji(base)
    private fun w(name: String, vararg keywords: String) = EmojiWords(name, keywords.toList())

    private val catalog = listOf(
        EmojiGroup("A", listOf(e("😺"), e("🐈"), e("🐱"), e("🐯"), e("😼"))),
        EmojiGroup("B", listOf(e("🧗"), e("❤️"), e("💔"), e("🤍"))),
    )
    private val en = mapOf(
        "😺" to w("grinning cat", "cat", "face", "grinning", "smile"),
        "🐈" to w("cat", "pet"),
        "🐱" to w("cat face", "cat", "face", "pet"),
        "🐯" to w("tiger face", "face", "tiger"),
        "😼" to w("cat with wry smile", "cat", "face", "ironic", "wry"),
        "🧗" to w("person climbing", "climber", "scatter"),
        "❤️" to w("red heart", "heart"),
        "💔" to w("broken heart", "break", "broken"),
        "🤍" to w("white heart", "heart", "white"),
    )
    private val ar = mapOf(
        "🐈" to w("قطة", "قط", "حيوان أليف"),
        "❤️" to w("قلب أحمر", "قلب"),
    )
    private val index = EmojiIndex(catalog, listOf(en, ar))

    private fun search(q: String, limit: Int = Int.MAX_VALUE) = index.search(q, limit).map { it.base }

    @Test fun exactKeywordThenKeywordPrefixThenNameWordPrefixThenSubstring() {
        // 🐈 is named "cat", ahead of the others' "cat" keywords, which keep catalog order. 🧗 has "scatter": a substring.
        assertEquals(listOf("🐈", "😺", "🐱", "😼", "🧗"), search("cat"))
        // "brok": 💔's keyword "broken" starts with it, as its name does.
        assertEquals(listOf("💔"), search("brok"))
        // "smi": keyword prefix (😺 "smile") ahead of a name word only (😼 "cat with wry smile" has no "smile" keyword).
        assertEquals(listOf("😺", "😼"), search("smi"))
        // "climb": 🧗's name word "climbing", its keyword "climber" too; "limb" only as a substring.
        assertEquals(listOf("🧗"), search("climb"))
        assertEquals(listOf("🧗"), search("limb"))
    }

    @Test fun aNameWordPrefixOutranksASubstring() {
        val index = EmojiIndex(listOf(EmojiGroup("A", listOf(e("1"), e("2")))), listOf(mapOf("1" to w("sun with face", "x"), "2" to w("big face", "x"))))
        // "fac": prefix of no keyword, of the name word "face" in both; "ith" is only a substring of "with".
        assertEquals(listOf("1", "2"), index.search("fac").map { it.base })
        assertEquals(listOf("1"), index.search("ith").map { it.base })
        val ranked = EmojiIndex(listOf(EmojiGroup("A", listOf(e("1"), e("2")))), listOf(mapOf("1" to w("x", "unsmiling"), "2" to w("big smile", "y"))))
        assertEquals(listOf("2", "1"), ranked.search("smil").map { it.base })
    }

    @Test fun phrasesAndWordsInAnyOrderMatch() {
        assertEquals(listOf("❤️"), search("red heart"))
        assertEquals(listOf("❤️"), search("red hea"))
        assertEquals(listOf("❤️"), search("heart red"))
        // 💔 has "heart" only as a word of its name, after the two whose keyword it is.
        assertEquals(listOf("❤️", "🤍", "💔"), search("heart"))
    }

    @Test fun caseSpacesAndArabicHamzaAreFolded() {
        assertEquals(search("cat"), search("  CAT "))
        assertEquals(listOf("❤️"), search("Red   Heart"))
        assertEquals(listOf("🐈"), search("قطة"))
        assertEquals(listOf("🐈"), search("حيوان اليف")) // أليف typed without the hamza
        assertEquals(listOf("❤️"), search("قلب"))
    }

    @Test fun blankQueriesFindNothingAndTheLimitHolds() {
        assertEquals(emptyList<String>(), search(""))
        assertEquals(emptyList<String>(), search("   "))
        assertEquals(emptyList<String>(), search("zebra"))
        assertEquals(listOf("🐈", "😺"), search("cat", limit = 2))
    }

    @Test fun emojiWithoutWordsAreNeverFound() {
        val index = EmojiIndex(listOf(EmojiGroup("A", listOf(e("🫥")))), listOf(emptyMap()))
        assertEquals(emptyList<Emoji>(), index.search("face"))
    }
}
