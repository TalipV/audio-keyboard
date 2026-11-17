# My Board

An AI-first Android keyboard with dictation and ✦, a voice prompt that writes into the field through the AI service the owner chooses (Venice AI or their own server). The owner is the phone's user. These terms cover how it learns the owner's voice and remembers the people they write to.

## Writing

**✦ (prompt)**:
Speech treated as an instruction; the LLM's answer goes into the field.
_Avoid_: AI mode, assistant

**Dictation**:
Speech typed verbatim into the field. It is never restyled.
_Avoid_: narrate (as a user-facing term), voice typing

**Screen context**:
The visible text of the app under the keyboard, read for ✦.
_Avoid_: context (unqualified)

**Sample**:
A message the owner sent, kept so ✦ can copy their voice.

**Pick**:
The verdict on a draft ✦ showed: picked, sent unchanged, edited, or ignored (with what the owner wrote instead). Kept so drafts drift toward what lands.

## Chats

**Chat**:
One conversation thread in one app, identified by the app and the title on screen. A group is a Chat too.
_Avoid_: conversation, thread, contact

**Chat memory**:
Everything kept about one Chat: its History, Profile, Goal and Notes.
_Avoid_: context, conversation memory, dossier

**History**:
The stored messages of a Chat, from every sender, each with who sent it and when.
_Avoid_: chat log, transcript

**Profile**:
A short written summary of a Chat: the relationship, language and dialect, register and how the owner writes there.
_Avoid_: style description, persona

**Goal**:
What the owner wants from the relationship in a Chat, such as continuous banter or getting to know them more deeply. Empty by default; most Chats are plain transactional and need none.
_Avoid_: task, objective, intent

**Note**:
A dated fact about someone in a Chat, worth bringing up later.
_Avoid_: memory, event

**Waiting reply**:
A reply ✦ wrote before being asked, shown in the suggestion strip while the field is empty.
_Avoid_: smart reply, suggestion (the strip's word completions keep that name)
