package dev.yazin.keyboard.engine.llm

import dev.yazin.keyboard.engine.chat.ChatContext
import dev.yazin.keyboard.engine.style.Pick
import dev.yazin.keyboard.engine.style.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTest {
    @Test fun picksRenderExactVerdictsAfterEdits() {
        val ctx = PromptContext("", "", "", "", false, edits = listOf("draft" to "edit"), picks = listOf(
            Pick(" sounds good,\nsee you there ", Verdict.PICKED, "a", "Sam", 1),
            Pick("on my way", Verdict.SENT, "a", "Sam", 2),
            Pick("haha nice", Verdict.IGNORED, "a", "Sam", 3, "lol\nperfect"),
            Pick("hidden", Verdict.EDITED, "a", "Sam", 4, "edited"),
        ))
        assertEquals(
            "## My edits\n- you wrote: draft → I sent: edit\n\n" +
                "## What landed\n- ✓ picked: sounds good, / see you there\n" +
                "- ✓ sent unchanged: on my way\n- ✗ ignored: haha nice → I wrote: lol / perfect\n\n## Instruction\nreply",
            Prompt.user(ctx, "reply"),
        )
        assertFalse(Prompt.user(ctx.copy(picks = emptyList()), "reply").contains("What landed"))
        assertTrue(Prompt.SYSTEM.contains("\"What landed\" lists earlier drafts: ✓ ones the owner picked or sent unchanged, ✗ ones they ignored, with what they wrote instead. Lean toward the ✓ kind, away from the ✗ kind."))
    }

    @Test fun anEmptyFieldAsksForNewText() {
        val r = Prompt.request("com.whatsapp", "Message", FieldText("", 0, 0))
        val u = Prompt.user(r.context.copy(screen = "Sam\nare you coming tonight?"), " reply saying yes, around 8 ")
        assertEquals("## App\ncom.whatsapp\n\n## Field hint\nMessage\n\n## Screen\nSam\nare you coming tonight?\n\n## Instruction\nreply saying yes, around 8", u)
    }

    @Test fun aFilledFieldIsRewrittenWhole() {
        val r = Prompt.request("com.google.android.gm", "", FieldText("Hi Sam, see you at 8.", 21, 21))
        assertFalse(r.context.selection)
        assertEquals("Hi Sam, see you at 8.", r.context.current)
        assertTrue(Prompt.user(r.context, "more formal").contains("## Current text\nHi Sam, see you at 8.\n\n"))
    }

    @Test fun aSelectionScopesTheRewrite() {
        val f = FieldText("Hello. thanks a lot. Bye.", 7, 20)
        val r = Prompt.request("a", "", f)
        assertTrue(r.context.selection)
        assertEquals("thanks a lot.", r.context.current)
        assertTrue(Prompt.user(r.context, "formal").contains("## Selected text to rewrite\nthanks a lot.\n\n"))
    }

    @Test fun blankSectionsAreLeftOut() =
        assertEquals("## Instruction\nhi", Prompt.user(PromptContext("", "", "", "", false), "hi"))

    @Test fun cleanStripsWrappingQuotes() {
        assertEquals("Yes, see you at 8!", Prompt.clean("\n\"Yes, see you at 8!\"\n"))
        assertEquals("Sure", Prompt.clean("“Sure”"))
        assertEquals("ok", Prompt.clean("  ok \n"))
    }

    @Test fun cleanKeepsQuotesThatArePartOfTheText() =
        assertEquals("\"Dune\" was great, and so was \"Arrival\"", Prompt.clean("\"Dune\" was great, and so was \"Arrival\""))

    @Test fun samplesAndEditsGoBetweenTheScreenAndTheInstruction() {
        val ctx = PromptContext(
            "com.whatsapp", "", "Nora\nIt's been too long", "", false,
            samples = listOf("haha thanks man", "yallah\ninshallah soon"),
            edits = listOf("Thank you so much, Nora!" to "thank u nora 🥹"),
        )
        assertEquals(
            "## App\ncom.whatsapp\n\n## Screen\nNora\nIt's been too long\n\n" +
                "## My messages\n- haha thanks man\n- yallah / inshallah soon\n\n" +
                "## My edits\n- you wrote: Thank you so much, Nora! → I sent: thank u nora 🥹\n\n" +
                "## Instruction\nwarm reply",
            Prompt.user(ctx, "warm reply"),
        )
    }

    @Test fun myLinesOnScreenAreMarked() {
        val ctx = PromptContext("a", "", "Ali Talib\nWhy I built it\nwho doesn't love free stuff\n3:03 PM", "", false,
            mine = setOf("who doesn't love free stuff"))
        assertTrue(Prompt.user(ctx, "x").contains("Why I built it\nMe: who doesn't love free stuff\n3:03 PM"))
    }

    private val plan6 = PromptContext(
        "com.whatsapp", "Message", "Sam\nyou free?\nsure\n9:41 PM", "see u at 8", false,
        samples = listOf("haha ok", "yalla\nsoon"), edits = listOf("Sure thing!" to "sure 👍"), mine = setOf("sure"),
    )

    @Test fun withoutAChatThePromptIsPlan6s() {
        assertEquals(
            "## App\ncom.whatsapp\n\n## Field hint\nMessage\n\n## Screen\nSam\nyou free?\nMe: sure\n9:41 PM\n\n" +
                "## My messages\n- haha ok\n- yalla / soon\n\n## My edits\n- you wrote: Sure thing! → I sent: sure 👍\n\n" +
                "## Current text\nsee u at 8\n\n## Instruction\nmake it warmer",
            Prompt.user(plan6, "make it warmer"),
        )
        assertEquals(Prompt.SYSTEM, Prompt.system(plan6))
    }

    private val chat = ChatContext(
        "Sam", "He's my cousin\nCousins who mix Khaleeji and English.", "Closer: ask about their life",
        listOf("Started a new job in October"), listOf("how was ur day" to "long but good"), listOf("Sam: back home", "Me: welcome back"),
    )

    @Test fun chatSectionsComeInCacheFriendlyOrder() {
        val u = Prompt.user(plan6.copy(chat = chat), "reply")
        val order = listOf(
            "## App", "## Chat\nSam", "## Profile\nHe's my cousin\nCousins", "## My goal in this chat", "## Notes\n- Started", "## How I reply to them",
            "## My messages", "## My edits", "## Earlier in this chat\nSam: back home\nMe: welcome back", "## Screen", "## Current text", "## Instruction",
        ).map { u.indexOf(it) }
        assertTrue("$order\n$u", order.none { it < 0 } && order == order.sorted())
    }

    @Test fun emptyChatSectionsAreLeftOut() {
        val u = Prompt.user(plan6.copy(chat = chat.copy(goal = " ", notes = emptyList(), pairs = emptyList(), earlier = emptyList())), "x")
        for (s in listOf("## My goal", "## Notes", "## How I reply", "## Earlier")) assertFalse(s, u.contains(s))
        assertTrue(u.contains("## Chat\nSam\n\n## Profile\n"))
    }

    @Test fun theGoalRuleComesOnlyWithAGoal() {
        val withGoal = Prompt.system(plan6.copy(chat = chat))
        val noGoal = Prompt.system(plan6.copy(chat = chat.copy(goal = "")))
        assertEquals(Prompt.SYSTEM + " " + Prompt.CHAT_RULES + " " + Prompt.GOAL_RULE, withGoal)
        assertEquals(Prompt.SYSTEM + " " + Prompt.CHAT_RULES, noGoal)
    }

    @Test fun pairsAreOneLineEach() {
        val u = Prompt.user(PromptContext("a", "", "", "", false, chat = chat.copy(pairs = listOf("a\nb" to "c"))), "x")
        assertTrue(u, u.contains("## How I reply to them\n- they: a / b → me: c\n\n"))
    }

    @Test fun theProfileCallGetsTitleCorrectionsPreviousAndHistory() {
        assertEquals(
            "## Chat\nSam\n\n## Corrections\n- He's my cousin\n\n## History\nSam: hi\nMe: hey",
            ProfilePrompt.user("Sam", listOf("He's my cousin", " "), "", "Sam: hi\nMe: hey"),
        )
        assertTrue(ProfilePrompt.user("Sam", emptyList(), "Cousins.", "x").contains("## Previous profile\nCousins.\n\n## History\nx"))
    }

    @Test fun theDebugLogKeepsTheStartOfALongHistory() {
        assertEquals("Sam: hi", ProfilePrompt.logged("Sam: hi"))
        val long = "Me: " + "y".repeat(2_500)
        assertEquals(long.take(2_000) + "… (2504 characters)", ProfilePrompt.logged(long))
    }

    @Test fun longSamplesAreCut() {
        val ctx = PromptContext("a", "", "", "", false, samples = listOf("y".repeat(500)))
        assertTrue(Prompt.user(ctx, "x").contains("- " + "y".repeat(300) + "\n\n"))
    }
}
