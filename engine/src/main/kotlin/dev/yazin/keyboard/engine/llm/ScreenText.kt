package dev.yazin.keyboard.engine.llm

import dev.yazin.keyboard.engine.style.WhatsAppExport

/** One accessibility node as ScreenReader sees it; an interface so flattening is testable on the JVM. */
interface ScreenNode {
    val text: String?
    val description: String?
    val editable: Boolean
    val visible: Boolean
    val children: List<ScreenNode>
    /** Position on screen, for finding the title. */
    val top: Int
    val left: Int
    /** The rest of the bounds and the view's identity, for reading chat bubbles (chat-memory spec §2). */
    val right: Int get() = left
    val bottom: Int get() = top
    val className: String? get() = null
    /** "com.whatsapp:id/message_text"; null unless the service asks for view ids. */
    val viewId: String? get() = null
}

object ScreenText {
    const val MAX_CHARS = 6_000
    private const val MAX_NODES = 2_000 // ponytail: caps a pathological tree; raise if long chats get cut short
    private val SPACE = Regex("\\s+")

    /**
     * Visible text top to bottom, one line per node, with editable fields left out (the field itself goes in separately).
     * Keeps the last [max] characters, the bottom of a chat, cut at a line start.
     */
    fun flatten(root: ScreenNode, max: Int = MAX_CHARS): String {
        val lines = ArrayList<String>()
        var seen = 0
        fun walk(n: ScreenNode) {
            if (!n.visible || ++seen > MAX_NODES) return
            if (!n.editable) {
                val s = (n.text?.takeIf { it.isNotBlank() } ?: n.description)?.replace(SPACE, " ")?.trim()
                if (!s.isNullOrEmpty() && s != lines.lastOrNull()) lines += s
            }
            n.children.forEach(::walk)
        }
        walk(root)
        val all = lines.joinToString("\n")
        if (all.length <= max) return all
        val nl = all.indexOf('\n', all.length - max - 1)
        return if (nl < 0) all.takeLast(max) else all.substring(nl + 1)
    }

    /** The toolbar's title, a chat's name in messaging apps: [titleNode]'s text. */
    fun title(root: ScreenNode): String? = titleNode(root)?.text?.replace(SPACE, " ")?.trim()?.take(TITLE_CHARS)

    /**
     * The visible text that is neither a field nor only a description and sits beside the node described "Back": its
     * vertical centre within Back's span, nearest Back on either side, topmost on ties. Without such a node, the topmost
     * text (leftmost on ties). Tree order won't do: Google Messages lists its banner before its toolbar, and its messages
     * scroll under the toolbar while staying visible, above the title (chat-memory spec §2).
     */
    private fun titleNode(root: ScreenNode): ScreenNode? {
        val texts = ArrayList<ScreenNode>()
        var back: ScreenNode? = null
        var seen = 0
        fun walk(n: ScreenNode) {
            if (!n.visible || ++seen > MAX_NODES) return
            if (!n.editable && !n.text.isNullOrBlank()) texts += n
            if (back == null && n.description?.let { WhatsAppExport.INVISIBLE.replace(it, "").trim() } == BACK) back = n
            n.children.forEach(::walk)
        }
        walk(root)
        back?.let { b ->
            fun gap(n: ScreenNode) = when {
                n.left >= b.right -> n.left - b.right
                n.right <= b.left -> b.left - n.right
                else -> -1
            }
            texts.filter { (it.top + it.bottom) / 2 in b.top..b.bottom && gap(it) >= 0 }
                .minWithOrNull(compareBy({ gap(it) }, { it.top }))
                ?.let { return it }
        }
        return texts.minWithOrNull(compareBy({ it.top }, { it.left }))
    }

    private const val TITLE_CHARS = 80
    private const val BACK = "Back"
}
