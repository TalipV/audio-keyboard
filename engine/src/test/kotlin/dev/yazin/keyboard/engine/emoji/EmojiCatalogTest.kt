package dev.yazin.keyboard.engine.emoji

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiCatalogTest {
    private fun cp(vararg c: Int) = c.joinToString("") { String(Character.toChars(it)) }

    private val wave = cp(0x1F44B)
    private val pointUp = cp(0x261D, 0xFE0F)
    private val holdingHands = cp(0x1F9D1, 0x200D, 0x1F91D, 0x200D, 0x1F9D1)

    private val sample = """
        # group: Smileys & Emotion
        # subgroup: face-smiling
        1F600                                  ; fully-qualified     # x E1.0 grinning face
        263A FE0F                              ; fully-qualified     # x E0.6 smiling face
        263A                                   ; unqualified         # x E0.6 smiling face
        # group: People & Body
        # subgroup: hand-fingers-open
        1F44B                                  ; fully-qualified     # x E0.6 waving hand
        1F44B 1F3FB                            ; fully-qualified     # x E1.0 waving hand: light skin tone
        1F44B 1F3FF                            ; fully-qualified     # x E1.0 waving hand: dark skin tone
        261D FE0F                              ; fully-qualified     # x E0.6 index pointing up
        261D 1F3FD                             ; fully-qualified     # x E1.0 index pointing up: medium skin tone
        1F9D1 200D 1F91D 200D 1F9D1            ; fully-qualified     # x E12.0 people holding hands
        1F9D1 1F3FB 200D 1F91D 200D 1F9D1 1F3FC ; fully-qualified    # x E12.1 people holding hands: light, medium-light
        # group: Component
        1F3FB                                  ; fully-qualified     # x E1.0 light skin tone
    """.trimIndent()

    private val groups = EmojiTest.parse(sample.lineSequence())

    @Test fun keepsFullyQualifiedEmojiInGroupsAndDropsComponents() {
        assertEquals(listOf("Smileys & Emotion", "People & Body"), groups.map { it.name })
        assertEquals(listOf(cp(0x1F600), cp(0x263A, 0xFE0F)), groups[0].emoji.map { it.base })
    }

    @Test fun skinTonesBecomeVariantsOfTheirBase() {
        val people = groups[1].emoji.associateBy { it.base }
        assertEquals(listOf(wave, pointUp, holdingHands), groups[1].emoji.map { it.base })
        assertEquals(listOf(cp(0x1F44B, 0x1F3FB), cp(0x1F44B, 0x1F3FF)), people[wave]!!.variants)
        // The base carries the emoji presentation selector; its toned form doesn't.
        assertEquals(listOf(cp(0x261D, 0x1F3FD)), people[pointUp]!!.variants)
        assertEquals(listOf(cp(0x1F9D1, 0x1F3FB, 0x200D, 0x1F91D, 0x200D, 0x1F9D1, 0x1F3FC)), people[holdingHands]!!.variants)
    }

    @Test fun theAssetFormatRoundTrips() {
        val out = StringBuilder()
        EmojiTest.write(groups, out)
        assertEquals(groups, EmojiTest.read(out.lineSequence()))
    }

    @Test fun emojiTheDeviceCantDrawAreHidden() {
        val shown = EmojiTest.supported(groups) { it != cp(0x1F600) && it != cp(0x1F44B, 0x1F3FF) }
        assertEquals(listOf(cp(0x263A, 0xFE0F)), shown[0].emoji.map { it.base })
        assertEquals(listOf(cp(0x1F44B, 0x1F3FB)), shown[1].emoji.first { it.base == wave }.variants)
        assertEquals(emptyList<EmojiGroup>(), EmojiTest.supported(groups) { false })
    }

    @Test fun stripTonesKeepsEverythingElse() =
        assertEquals(holdingHands, EmojiTest.stripTones(cp(0x1F9D1, 0x1F3FB, 0x200D, 0x1F91D, 0x200D, 0x1F9D1, 0x1F3FC)))

    @Test fun mixedToneCouplesJoinTheBaseListedBeforeThem() {
        // 👫's uniform tones keep its code point, but mixed tones are ZWJ sequences with no untoned form of their own.
        val couples = EmojiTest.parse("""
            # group: People & Body
            1F46B                                    ; fully-qualified     # x E0.6 woman and man holding hands
            1F46B 1F3FB                              ; fully-qualified     # x E12.0 woman and man holding hands: light skin tone
            1F469 1F3FB 200D 1F91D 200D 1F468 1F3FC  ; fully-qualified     # x E12.0 woman and man holding hands: light, medium-light
        """.trimIndent().lineSequence())
        assertEquals(
            listOf(Emoji(cp(0x1F46B), listOf(cp(0x1F46B, 0x1F3FB), cp(0x1F469, 0x1F3FB, 0x200D, 0x1F91D, 0x200D, 0x1F468, 0x1F3FC)))),
            couples[0].emoji,
        )
    }

    @Test fun keycapsSurviveTheAssetFormat() {
        // #️⃣ starts with '#': the asset's group lines must not be mistaken for it, nor it for one.
        val keycaps = listOf(EmojiGroup("Symbols", listOf(Emoji(cp(0x23, 0xFE0F, 0x20E3)), Emoji(cp(0x2A, 0xFE0F, 0x20E3)))))
        val out = StringBuilder()
        EmojiTest.write(keycaps, out)
        assertEquals(keycaps, EmojiTest.read(out.lineSequence()))
    }
}
