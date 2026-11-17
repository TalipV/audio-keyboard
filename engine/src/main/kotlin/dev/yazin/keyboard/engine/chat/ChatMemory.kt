package dev.yazin.keyboard.engine.chat

import dev.yazin.keyboard.engine.style.ChatLine
import dev.yazin.keyboard.engine.style.StyleStore
import dev.yazin.keyboard.engine.style.Picks

/** Where Chat memory keeps its files: [ChatMemory.INDEX] and one `<id>.tsv` per Chat. */
interface ChatStorage {
    /** The lines of file [name], or null when there is no such file. Throws when it exists but can't be read. */
    fun read(name: String): List<String>?

    /** Replaces file [name] with what [lines] appends, atomically. */
    fun write(name: String, lines: (Appendable) -> Unit)

    fun delete(name: String)
}

/**
 * A Chat's memory for a ✦ prompt (spec §6.1). [profile] is the corrections, one per line, then the written part.
 * [earlier] lines are "Me: …" or "<sender or title>: …".
 */
data class ChatContext(
    val title: String,
    val profile: String,
    val goal: String,
    val notes: List<String>,
    val pairs: List<Pair<String, String>>,
    val earlier: List<String>,
)

/** Which Chat a passive read on hide belongs to (spec §4.2). */
object Capture {
    /** The Chat the keyboard opened in, only if the same Chat is still showing when it hides. */
    fun target(atStart: ChatKey?, atHide: ChatKey?): ChatKey? = atStart?.takeIf { it == atHide }
}

/**
 * Every Chat's memory (spec §4–5): the index stays in memory, and a Chat's History is loaded from [storage] when it is
 * first needed. Thread-safe: the keyboard adds and reads on its threads, Profiles are written on a worker, and settings
 * import, merge and forget on the main thread. Forgetting or merging a Chat does the same to its writing samples in
 * [style].
 */
class ChatMemory(
    private val storage: ChatStorage,
    private val style: StyleStore? = null,
    private val picks: Picks? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val index = ChatIndex()
    /** Loaded Histories by Chat id, least recently used first. */
    private val loaded = LinkedHashMap<Int, ChatHistory>(16, 0.75f, true)
    private val dirty = HashSet<Int>()
    private var indexDirty = false

    init {
        storage.read(INDEX)?.let { index.read(it.asSequence()) }
    }

    @Synchronized
    fun info(key: ChatKey): ChatInfo? = index.get(key)

    /** [key]'s newest stored message: the waiting-reply trigger's fallback when the screen can't be read (spec §4). */
    @Synchronized
    fun lastMessage(key: ChatKey): Message? = index.get(key)?.let { history(it.id).messages.lastOrNull() }

    /** [key]'s memory, making an empty Chat when there is none: the panel opened on it (spec §7). */
    @Synchronized
    fun open(key: ChatKey): ChatInfo = chat(key)

    /** Every Chat, newest [ChatInfo.lastSeen] first. */
    @Synchronized
    fun all(): List<ChatInfo> = index.all()

    /** The owner sent [text] in [key], replacing the ✦ [draft] if any. Nothing is kept in a Chat not to remember. */
    @Synchronized
    fun sent(key: ChatKey, text: String, draft: String?) {
        if (index.get(key)?.remember == false) return
        add(key, listOf(Message(Side.ME, "", text, now(), false, Source.SENT, draft)), seen = true)
    }

    /** Merges a screen read of [key] into its History, making the Chat if needed. Returns how many messages were new. */
    @Synchronized
    fun capture(key: ChatKey, screen: List<Message>): Int {
        if (index.get(key)?.remember == false) return 0
        return add(key, screen, seen = true)
    }

    /**
     * Merges an export of [key] in which [owner] is the owner's sender name; everyone else keeps their name. Lines without
     * a time get guessed times, in order, just before the oldest message here or in the export. Returns how many were new;
     * any new one makes the Profile due. A Chat not to remember keeps nothing (spec §4.3).
     */
    @Synchronized
    fun import(key: ChatKey, lines: List<ChatLine>, owner: String): Int {
        if (index.get(key)?.remember == false) return 0
        val h = index.get(key)?.let { history(it.id) }
        val oldest = listOfNotNull(h?.messages?.firstOrNull()?.time, lines.mapNotNull { it.time }.minOrNull(), now()).min()
        var guess = oldest - lines.count { it.time == null }
        val ms = lines.map { l ->
            val time = l.time
            val side = if (l.sender == owner) Side.ME else Side.THEM
            Message(side, if (side == Side.ME) "" else l.sender, l.text, time ?: guess++, time == null, Source.EXPORT)
        }
        val added = add(key, ms, seen = false)
        if (added > 0) update(key) { it.copy(profileDue = true) }
        return added
    }

    /** [key]'s memory for ✦ given what is on [screen] ([screenText] as one string), or null when it has no History. */
    @Synchronized
    fun context(key: ChatKey, screen: List<Message>, screenText: String): ChatContext? {
        val info = index.get(key)?.takeIf { it.count > 0 } ?: return null
        val h = history(info.id)
        if (h.size == 0) return null
        val wanted = StyleStore.words(screenText)
        val notes = info.notes.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Note>> { n -> StyleStore.words(n.value.text).count { it in wanted } }
                    .thenByDescending { it.value.day }.thenByDescending { it.index },
            )
            .take(NOTES)
            .map { it.value.text }
        val profile = (info.corrections + info.written).filter { it.isNotBlank() }.joinToString("\n")
        return ChatContext(key.title, profile, info.goal, notes, h.pairs(h.latestTheirs(screen)), h.earlier(screen).map { line(it, key.title) })
    }

    @Synchronized
    fun setGoal(key: ChatKey, goal: String) {
        ensure(key) { it.copy(goal = goal.trim()) }
    }

    @Synchronized
    fun addCorrection(key: ChatKey, text: String) {
        val t = text.trim().ifEmpty { return }
        ensure(key) { if (t in it.corrections) it else it.copy(corrections = it.corrections + t) }
    }

    @Synchronized
    fun addNote(key: ChatKey, text: String) {
        val t = text.trim().ifEmpty { return }
        ensure(key) { it.copy(notes = it.notes + Note(t, now())) }
    }

    @Synchronized
    fun removeNote(key: ChatKey, note: Note) {
        update(key) { it.copy(notes = it.notes - note) }
    }

    /** A new written Profile part. It never makes a Chat: one forgotten while its Profile was being written stays gone. */
    @Synchronized
    fun setWritten(key: ChatKey, text: String, topics: List<String>? = null) {
        update(key) {
            it.copy(
                topics = topics?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()?.take(6) ?: it.topics,
                written = text.trim(), writtenAt = now(), sinceProfile = 0, profileDue = false,
            )
        }
    }

    /** The owner asked for a new Profile (Regenerate): [due] lists [key] once it has History. */
    @Synchronized
    fun markDue(key: ChatKey) {
        update(key) { it.copy(profileDue = true) }
    }

    @Synchronized
    fun setRemember(key: ChatKey, on: Boolean) {
        ensure(key) { it.copy(remember = on) }
    }

    /** Deletes [key]'s History, memory and writing samples. A Chat not to remember keeps only that switch. */
    @Synchronized
    fun forget(key: ChatKey) {
        style?.forget(key.app, key.title)
        picks?.forget(key.app, key.title)
        drop(index.get(key) ?: return, keepSwitch = true)
        save()
    }

    /**
     * Moves [from]'s History into [into] (merging as §4.5) and combines their memory: [into]'s Goal and written part win
     * when both are set, Corrections and Notes are joined, and either Chat's "don't remember" carries over. [from]'s
     * writing samples are filed under [into] (Settings merges only within one app), and [from] is deleted.
     */
    @Synchronized
    fun merge(from: ChatKey, into: ChatKey) {
        if (from == into) return
        val src = index.get(from) ?: return
        val dst = chat(into)
        val h = history(dst.id)
        val added = if (src.count > 0) h.addAll(history(src.id).messages) else 0
        val newest = h.messages.lastOrNull()?.time ?: 0
        index.update(dst.id) { c ->
            c.copy(
                count = h.size,
                first = h.messages.firstOrNull()?.time ?: 0,
                lastSeen = maxOf(c.lastSeen, src.lastSeen, newest),
                goal = c.goal.ifBlank { src.goal },
                written = c.written.ifBlank { src.written },
                writtenAt = if (c.written.isBlank()) src.writtenAt else c.writtenAt,
                topics = (c.topics + src.topics).distinct().take(6),
                corrections = (c.corrections + src.corrections).distinct(),
                notes = (c.notes + src.notes).distinct(),
                sinceProfile = c.sinceProfile + added,
                profileDue = c.profileDue || added > 0,
                remember = c.remember && src.remember,
            )
        }
        dirty += dst.id
        save() // [into]'s History and the index first: dying before [from]'s file goes leaves a copy, never neither
        style?.move(from.app, from.title, into.title)
        picks?.move(from.app, from.title, into.title)
        drop(src, keepSwitch = false)
        save()
    }

    /** Forgets every Chat, as [forget] does. */
    @Synchronized
    fun clear() {
        for (c in index.all()) {
            style?.forget(c.key.app, c.key.title)
            picks?.forget(c.key.app, c.key.title)
            drop(c, keepSwitch = true)
        }
        save()
    }

    /** Profiles are due after an import, the first incoming message, or [PROFILE_EVERY] new messages. */
    @Synchronized
    fun due(): List<ChatKey> =
        index.all().filter {
            it.count > 0 && (it.profileDue || it.sinceProfile >= PROFILE_EVERY ||
                (it.writtenAt == 0L && history(it.id).messages.any { m -> m.side == Side.THEM }))
        }.map { it.key }

    /** [key]'s newest messages as "Me: …" / "<sender or title>: …" lines, oldest first, within [max] characters. */
    @Synchronized
    fun historyText(key: ChatKey, max: Int = HISTORY_CHARS): String {
        val info = index.get(key)?.takeIf { it.count > 0 } ?: return ""
        val lines = ArrayDeque<String>()
        var size = 0
        for (m in history(info.id).messages.asReversed()) {
            val l = line(m, key.title)
            val cost = l.length + if (lines.isEmpty()) 0 else 1
            if (size + cost > max) break
            lines.addFirst(l)
            size += cost
        }
        return lines.joinToString("\n")
    }

    /**
     * Copies `style.tsv`'s titled samples into their Chats on the owner's side (spec §4.6), once. [StyleStore.import]
     * stamps imports 1 ms apart and real sends never are, so a sample without a draft that has one of its Chat 1 ms
     * before or after it was imported and its time is a guess; every other time is exact. They don't count toward a
     * Profile: one written from the owner's side alone would mis-describe the relationship.
     */
    @Synchronized
    fun seed(style: StyleStore) {
        if (index.seeded) return
        val byChat = style.samples().filter { it.chat.isNotBlank() }.groupBy { ChatKey.of(it.app, it.chat, "") }
        for ((key, samples) in byChat) {
            if (key == null || index.get(key)?.remember == false) continue
            val times = samples.mapTo(HashSet()) { it.time }
            val ms = samples.map {
                val imported = it.draft == null && (it.time - 1 in times || it.time + 1 in times)
                Message(Side.ME, "", it.text, it.time, imported, Source.SENT, it.draft)
            }
            add(key, ms, seen = false, counts = false)
        }
        index.seeded = true
        indexDirty = true
    }

    /** Writes the index and every changed History, then keeps only the [KEEP] most recently used Histories loaded. */
    @Synchronized
    fun save() {
        for ((id, h) in loaded) if (id in dirty) storage.write(file(id)) { h.write(it) }
        dirty.clear()
        if (indexDirty) {
            storage.write(INDEX) { index.write(it) }
            indexDirty = false
        }
        loaded.keys.take(maxOf(0, loaded.size - KEEP)).forEach { loaded.remove(it) }
    }

    /**
     * Merges [ms] into [key]'s History, making the Chat only when one is new. Being in the Chat ([seen]) stamps its last
     * seen time; otherwise that moves only up to the newest message. New ones count toward the next Profile when
     * [counts]. Returns how many were new.
     */
    private fun add(key: ChatKey, ms: List<Message>, seen: Boolean, counts: Boolean = true): Int {
        val info = index.get(key)
        val h = info?.let { history(it.id) } ?: ChatHistory()
        val before = h.messages
        val added = h.addAll(ms)
        if (info == null && added == 0) return 0
        val id = info?.id ?: chat(key).id.also { loaded[it] = h }
        if (added > 0 || h.messages != before) dirty += id
        val all = h.messages
        index.update(id) { c ->
            c.copy(
                count = all.size,
                first = all.firstOrNull()?.time ?: 0,
                lastSeen = if (seen) now() else maxOf(c.lastSeen, all.lastOrNull()?.time ?: 0),
                sinceProfile = c.sinceProfile + if (counts) added else 0,
            )
        }
        indexDirty = true
        return added
    }

    private fun ensure(key: ChatKey, change: (ChatInfo) -> ChatInfo) {
        index.update(chat(key).id, change)
        indexDirty = true
    }

    /**
     * [key]'s Chat, made when there is none. A new id starts with an empty History marked changed, so a stale `<id>.tsv`
     * (the process died after writing a History and before the index that counts its id) is overwritten, never read.
     */
    private fun chat(key: ChatKey): ChatInfo = index.get(key) ?: index.ensure(key).also {
        loaded[it.id] = ChatHistory()
        dirty += it.id
        indexDirty = true
    }

    private fun update(key: ChatKey, change: (ChatInfo) -> ChatInfo) {
        val info = index.get(key) ?: return
        index.update(info.id, change)
        indexDirty = true
    }

    private fun drop(c: ChatInfo, keepSwitch: Boolean) {
        storage.delete(file(c.id))
        loaded.remove(c.id)
        dirty.remove(c.id)
        if (keepSwitch && !c.remember) {
            index.update(c.id) { ChatInfo(c.id, c.key, 0, 0, c.lastSeen, "", "", 0, emptyList(), emptyList(), 0, false, false) }
        } else {
            index.remove(c.id)
        }
        indexDirty = true
    }

    private fun history(id: Int): ChatHistory = loaded.getOrPut(id) {
        ChatHistory().also { h -> storage.read(file(id))?.let { h.read(it.asSequence()) } }
    }

    private fun line(m: Message, title: String) = if (m.side == Side.ME) "Me: ${m.text}" else "${m.sender.ifEmpty { title }}: ${m.text}"

    private fun file(id: Int) = "$id.tsv"

    companion object {
        const val INDEX = "index.tsv"
        /** Histories kept loaded after [save]. */
        const val KEEP = 3
        const val NOTES = 5
        const val PROFILE_EVERY = 30
        const val HISTORY_CHARS = 60_000
    }
}
