package dev.yazin.keyboard.engine.chat

import dev.yazin.keyboard.engine.Tsv
import dev.yazin.keyboard.engine.style.StyleStore

/** A Chat (chat-memory spec §3): the app's package and the Chat's title. */
data class ChatKey(val app: String, val title: String) {
    companion object {
        /** Top texts of screens in messaging apps that aren't a Chat: tabs, and WhatsApp's chat list (spec §2). */
        val NOT_CHATS = setOf("Status", "Updates", "Calls", "Communities", "Chats", "Messages", "Search", "Ask Meta AI or Search")

        /** The Chat [rawTitle] names in [app], or null when it is blank, the app's own [appLabel] or one of [NOT_CHATS]. */
        fun of(app: String, rawTitle: String, appLabel: String): ChatKey? {
            val title = StyleStore.title(rawTitle)
            if (title.isEmpty() || title.equals(appLabel, ignoreCase = true) || title in NOT_CHATS) return null
            return ChatKey(app, title)
        }
    }
}

/** A dated fact about someone in a Chat (spec §5.3). [day] is when it was added, epoch ms. */
data class Note(val text: String, val day: Long)

/**
 * What is kept about a Chat besides its History (spec §5): [count] messages from [first] (epoch ms, 0 without any),
 * last in the Chat at [lastSeen]. The Profile is [corrections] and the [written] part from [writtenAt];
 * [sinceProfile] messages were added since. [remember] is off for a Chat set to "Don't remember this chat".
 */
data class ChatInfo(
    val id: Int,
    val key: ChatKey,
    val count: Int,
    val first: Long,
    val lastSeen: Long,
    val goal: String,
    val written: String,
    val writtenAt: Long,
    val corrections: List<String>,
    val notes: List<Note>,
    val sinceProfile: Int,
    val profileDue: Boolean,
    val remember: Boolean,
    val topics: List<String> = emptyList(),
)

/** Every Chat with memory, by key and by id. Not thread-safe: `ChatMemory` locks it. Ids are never reused. */
class ChatIndex {
    private val byKey = HashMap<ChatKey, ChatInfo>()
    private val byId = HashMap<Int, ChatInfo>()
    private var next = 1

    /** Whether `style.tsv` has been copied into the Chats (spec §4.6). */
    var seeded = false

    fun get(key: ChatKey): ChatInfo? = byKey[key]

    fun byId(id: Int): ChatInfo? = byId[id]

    /** The Chat for [key], made empty when there is none. */
    fun ensure(key: ChatKey): ChatInfo = byKey[key]
        ?: ChatInfo(next++, key, 0, 0, 0, "", "", 0, emptyList(), emptyList(), 0, false, true).also(::put)

    /** Newest [ChatInfo.lastSeen] first. */
    fun all(): List<ChatInfo> = byId.values.sortedWith(compareByDescending<ChatInfo> { it.lastSeen }.thenByDescending { it.id })

    /** Replaces Chat [id] with [change] of it, keeping its id and key. Returns the new value, or null without that Chat. */
    fun update(id: Int, change: (ChatInfo) -> ChatInfo): ChatInfo? {
        val old = byId[id] ?: return null
        return change(old).copy(id = id, key = old.key).also(::put)
    }

    fun remove(id: Int) {
        val old = byId.remove(id) ?: return
        byKey.remove(old.key)
    }

    fun write(out: Appendable) {
        out.append("f\t").append(next.toString()).append('\t').append(if (seeded) "1" else "0").append('\n')
        for (c in byId.values.sortedBy { it.id }) {
            out.append("c\t").append(c.id.toString()).append('\t').append(Tsv.escape(c.key.app)).append('\t')
                .append(Tsv.escape(c.key.title)).append('\t').append(c.count.toString()).append('\t')
                .append(c.first.toString()).append('\t').append(c.lastSeen.toString()).append('\t')
                .append(Tsv.escape(c.goal)).append('\t').append(Tsv.escape(c.written)).append('\t')
                .append(c.writtenAt.toString()).append('\t').append(c.sinceProfile.toString()).append('\t')
                .append(if (c.profileDue) "1" else "0").append('\t').append(if (c.remember) "1" else "0").append('\n')
            for (topic in c.topics) out.append("t\t").append(c.id.toString()).append('\t').append(Tsv.escape(topic)).append('\n')
            for (r in c.corrections) out.append("r\t").append(c.id.toString()).append('\t').append(Tsv.escape(r)).append('\n')
            for (n in c.notes) {
                out.append("n\t").append(c.id.toString()).append('\t').append(n.day.toString()).append('\t')
                    .append(Tsv.escape(n.text)).append('\n')
            }
        }
    }

    fun read(lines: Sequence<String>) {
        val corrections = HashMap<Int, MutableList<String>>()
        val notes = HashMap<Int, MutableList<Note>>()
        val topics = HashMap<Int, MutableList<String>>()
        for (line in lines) {
            val f = line.split('\t')
            when (f[0]) { // a damaged line is skipped
                "f" -> if (f.size == 3) {
                    f[1].toIntOrNull()?.let { next = maxOf(next, it) }
                    seeded = seeded || f[2] == "1"
                }
                "c" -> if (f.size == 13) chat(f)?.takeIf { it.id !in byId && it.key !in byKey }?.let(::put)
                "t" -> if (f.size == 3) f[1].toIntOrNull()?.let { topics.getOrPut(it) { ArrayList() } += Tsv.unescape(f[2]) }
                "r" -> if (f.size == 3) f[1].toIntOrNull()?.let { corrections.getOrPut(it) { ArrayList() } += Tsv.unescape(f[2]) }
                "n" -> if (f.size == 4) {
                    val id = f[1].toIntOrNull()
                    val day = f[2].toLongOrNull()
                    if (id != null && day != null) notes.getOrPut(id) { ArrayList() } += Note(Tsv.unescape(f[3]), day)
                }
            }
        }
        for ((id, ts) in topics) update(id) { it.copy(topics = ts.distinct().take(6)) }
        for ((id, rs) in corrections) update(id) { it.copy(corrections = it.corrections + rs) }
        for ((id, ns) in notes) update(id) { it.copy(notes = it.notes + ns) }
        next = maxOf(next, (byId.keys.maxOrNull() ?: 0) + 1)
    }

    private fun chat(f: List<String>): ChatInfo? {
        fun flag(s: String) = when (s) {
            "1" -> true
            "0" -> false
            else -> null
        }
        return ChatInfo(
            id = f[1].toIntOrNull() ?: return null,
            key = ChatKey(Tsv.unescape(f[2]), Tsv.unescape(f[3])),
            count = f[4].toIntOrNull() ?: return null,
            first = f[5].toLongOrNull() ?: return null,
            lastSeen = f[6].toLongOrNull() ?: return null,
            goal = Tsv.unescape(f[7]),
            written = Tsv.unescape(f[8]),
            writtenAt = f[9].toLongOrNull() ?: return null,
            corrections = emptyList(),
            notes = emptyList(),
            sinceProfile = f[10].toIntOrNull() ?: return null,
            profileDue = flag(f[11]) ?: return null,
            remember = flag(f[12]) ?: return null,
        )
    }

    private fun put(c: ChatInfo) {
        byKey[c.key] = c
        byId[c.id] = c
    }
}
