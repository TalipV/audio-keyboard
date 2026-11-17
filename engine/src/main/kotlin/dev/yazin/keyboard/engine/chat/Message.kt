package dev.yazin.keyboard.engine.chat

/** Whose side of a Chat a message is on: the owner's or someone else's. */
enum class Side { ME, THEM }

/** Where a message came from: a send the keyboard saw, a screen read, or a WhatsApp export. */
enum class Source { SENT, SCREEN, EXPORT }

/**
 * One message of a Chat's history. [sender] is "" for the owner and for the other person in a 1:1 Chat.
 * [time] is epoch ms; [guessed] when it is the time the message was first seen rather than read from the chat.
 * [draft] is the ✦ answer the owner edited into [text].
 */
data class Message(
    val side: Side,
    val sender: String,
    val text: String,
    val time: Long,
    val guessed: Boolean,
    val source: Source,
    val draft: String? = null,
)
