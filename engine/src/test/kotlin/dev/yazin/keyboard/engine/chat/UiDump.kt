package dev.yazin.keyboard.engine.chat

import dev.yazin.keyboard.engine.llm.ScreenNode
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** A scrubbed `uiautomator dump` from `src/test/resources/screens/` as the tree ScreenReader would hand over. */
object UiDump {
    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    /** The tree and the window width (the root node's right edge). */
    fun load(name: String): Pair<ScreenNode, Int> {
        val stream = UiDump::class.java.getResourceAsStream("/screens/$name.xml") ?: error("no fixture $name")
        val doc = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val root = Node(doc.documentElement)
        return root to root.children.maxOfOrNull { it.right }!!
    }

    private class Node(private val e: Element) : ScreenNode {
        private val b = BOUNDS.find(e.getAttribute("bounds"))?.groupValues?.drop(1)?.map(String::toInt) ?: listOf(0, 0, 0, 0)
        override val text: String? get() = e.getAttribute("text").ifEmpty { null }
        override val description: String? get() = e.getAttribute("content-desc").ifEmpty { null }
        override val editable: Boolean get() = e.getAttribute("class").endsWith("EditText") || e.getAttribute("password") == "true"
        override val visible: Boolean get() = e.tagName == "hierarchy" || e.getAttribute("visible-to-user") != "false"
        override val children: List<ScreenNode> by lazy {
            (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == "node" }.map(::Node)
        }
        override val left get() = b[0]
        override val top get() = b[1]
        override val right get() = b[2]
        override val bottom get() = b[3]
        override val className: String? get() = e.getAttribute("class").ifEmpty { null }
        override val viewId: String? get() = e.getAttribute("resource-id").ifEmpty { null }
    }
}
