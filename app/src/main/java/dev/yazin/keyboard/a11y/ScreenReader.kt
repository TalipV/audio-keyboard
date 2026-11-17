package dev.yazin.keyboard.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.yazin.keyboard.engine.llm.ScreenNode
import dev.yazin.keyboard.engine.llm.ScreenText

/**
 * Reads the app window under the keyboard when ✦ asks (AI orb spec §4) and when the keyboard opens and hides in a chat
 * (chat memory spec §4.2). It keeps nothing, and ignores every event unless [onScroll] is set.
 */
class ScreenReader : AccessibilityService() {
    override fun onServiceConnected() {
        live = this
        if (onScroll != null) listen(true) // reconnected mid-recording: the XML's event types came back
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (live === this) live = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val listener = onScroll ?: return
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_VIEW_SCROLLED && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        if (event.windowId == runCatching { appWindow()?.id }.getOrNull()) listener()
    }

    override fun onInterrupt() = Unit

    private fun appWindow(): AccessibilityWindowInfo? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return apps.firstOrNull { it.isFocused } ?: apps.firstOrNull()
    }

    private fun appRoot(): ScreenNode? = appWindow()?.root?.let(::Node)

    /** Children are fetched once: walking the tree again sees the same read, not the screen as it is now. */
    private class Node(private val n: AccessibilityNodeInfo) : ScreenNode {
        override val text: String? get() = n.text?.toString()
        override val description: String? get() = n.contentDescription?.toString()
        override val editable: Boolean get() = n.isEditable || n.isPassword
        override val visible: Boolean get() = n.isVisibleToUser
        override val children: List<ScreenNode> by lazy { (0 until n.childCount).mapNotNull { n.getChild(it) }.map(::Node) }
        private val bounds by lazy { Rect().also { n.getBoundsInScreen(it) } }
        override val top: Int get() = bounds.top
        override val left: Int get() = bounds.left
        override val right: Int get() = bounds.right
        override val bottom: Int get() = bounds.bottom
        override val className: String? get() = n.className?.toString()
        override val viewId: String? get() = n.viewIdResourceName
    }

    companion object {
        @Volatile private var live: ScreenReader? = null

        val enabled: Boolean get() = live != null

        /**
         * Called on the main thread for scrolls and content changes in the app window while set (chat memory spec §4.2,
         * recording); null ignores every event.
         */
        @Volatile var onScroll: (() -> Unit)? = null

        /**
         * Subscribes to scrolls and content changes ([on]) for recording, or back to the XML's window changes only, so
         * no app streams events to the service otherwise. Main thread.
         */
        fun listen(on: Boolean) {
            val s = live ?: return
            runCatching {
                s.serviceInfo = s.serviceInfo.apply {
                    eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                        if (on) AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else 0
                }
            }
        }

        /** The visible text of the app the user is typing in, or null when the service is off. Any thread. */
        fun read(): String? = live?.let { runCatching { it.appRoot()?.let(ScreenText::flatten) }.getOrNull() }

        /** The app's first visible text, a chat's title in messaging apps; null when the service is off. Any thread. */
        fun title(): String? = live?.let { s -> runCatching { s.appRoot()?.let(ScreenText::title) }.getOrNull() }

        /** The app window's tree, or null when the service is off. Its nodes are fetched as they are walked. Any thread. */
        fun root(): ScreenNode? = live?.let { runCatching { it.appRoot() }.getOrNull() }

        /** [read]'s text and the tree it came from, walked once, so both show the same moment. Any thread. */
        fun readBoth(): Pair<String, ScreenNode>? =
            live?.let { s -> runCatching { s.appRoot()?.let { ScreenText.flatten(it) to it } }.getOrNull() }

        /** The app window's width in pixels, 0 when the service is off. Any thread. */
        fun width(): Int = live?.let { s ->
            runCatching { s.appWindow()?.let { w -> Rect().also(w::getBoundsInScreen).width() } }.getOrNull()
        } ?: 0
    }
}
