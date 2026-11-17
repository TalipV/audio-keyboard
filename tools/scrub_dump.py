#!/usr/bin/env python3
"""Scrubs a uiautomator dump of a chat screen so it can be a test fixture without anyone's words in it.

Every text and content-desc keeps its shape: words become same-length filler in the same script, digits become 0.
The filler is keyed by a random secret made for each run and never saved, so it can't be reversed by scrubbing a word
list the same way. Within one run the same word always gets the same filler, so scrub related dumps in one call. Left as they are: whole-node times and dates, times inside a description, date
and time words (Today, Tuesday, Oct, اليوم, ص), the app's own chrome words, emoji and punctuation, and any text passed
with --keep. Tests match messages by their scrubbed text, so --keep is rarely needed.

usage: tools/scrub_dump.py <in.xml> <out.xml> [<in.xml> <out.xml>]... [--keep "<text>"]...
"""
import argparse
import hashlib
import hmac
import secrets
import re
import xml.etree.ElementTree as ET

KEEP_WORDS = {
    w.lower()
    for w in """
    today yesterday monday tuesday wednesday thursday friday saturday sunday
    mon tue tues wed thu thur thurs fri sat sun
    january february march april may june july august september october november december
    jan feb mar apr jun jul aug sep sept oct nov dec am pm
    you me message type a send attach camera emoji back voice video call more options search chats updates communities
    calls status online typing last seen read delivered sent unread messages edited deleted this was photo sticker gif
    audio document contact location missed pinned forwarded reply mute whatsapp said played
    اليوم أمس الأحد الاثنين الإثنين الثلاثاء الأربعاء الخميس الجمعة السبت
    يناير فبراير مارس أبريل إبريل مايو يونيو يوليو أغسطس سبتمبر أكتوبر نوفمبر ديسمبر ص م
    """.split()
}
# A fresh secret per run: an unkeyed hash of each word could be undone by hashing a dictionary.
KEY = secrets.token_bytes(32)
ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"
D = r"[0-9٠-٩]"
TIME = rf"{D}{{1,2}}[:.]{D}{{2}}(?:\s?(?:[AaPp]\.?\s?[Mm]\.?|ص|م))?"
WHOLE_TIME = re.compile(rf"^\s*{TIME}\s*$")
WHOLE_DATE = re.compile(rf"^\s*(?:{D}{{1,4}}[./-]{D}{{1,2}}[./-]{D}{{1,4}}|{D}{{1,2}}\s+\S+(?:\s+{D}{{4}})?|\S+\s+{D}{{1,2}}(?:,\s*{D}{{4}})?)\s*$")
TIME_IN_TEXT = re.compile(TIME)
WORD = re.compile(r"[^\W\d_]+", re.UNICODE)
LATIN = "abcdefghijklmnopqrstuvwxyz"
ARABIC = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"


def filler(word: str) -> str:
    if word.lower() in KEEP_WORDS:
        return word
    letters = ARABIC if any("\u0600" <= c <= "\u06ff" for c in word) else LATIN
    h = hmac.new(KEY, word.lower().encode(), hashlib.sha256).digest()
    out = "".join(letters[h[i % len(h)] % len(letters)] for i in range(len(word)))
    return out.capitalize() if word[:1].isupper() else out


def date_words_only(s: str) -> bool:
    return all(w.lower() in KEEP_WORDS for w in WORD.findall(s))


def scrub(s: str, keep: set) -> str:
    if not s or s in keep or WHOLE_TIME.match(s) or (WHOLE_DATE.match(s) and date_words_only(s)):
        return s
    if date_words_only(s) and not re.search(D, s):
        return s  # "Today", "Yesterday", "Tuesday"
    times = {m.span(): m.group() for m in TIME_IN_TEXT.finditer(s)}
    out, i = [], 0
    for (a, b), t in sorted(times.items()):
        out.append(rest(s[i:a]))
        out.append(t)
        i = b
    out.append(rest(s[i:]))
    return "".join(out)


def rest(s: str) -> str:
    s = WORD.sub(lambda m: filler(m.group()), s)
    return re.sub(r"[0-9]", "0", s).translate(str.maketrans(ARABIC_DIGITS, "٠" * 10))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("files", nargs="+", help="pairs of <in.xml> <out.xml>")
    p.add_argument("--keep", action="append", default=[])
    a = p.parse_args()
    if len(a.files) % 2:
        p.error("files come in <in.xml> <out.xml> pairs")
    keep = set(a.keep)
    for src, dst in zip(a.files[::2], a.files[1::2]):
        scrub_file(src, dst, keep)


def scrub_file(src: str, dst: str, keep: set):
    tree = ET.parse(src)
    for node in tree.iter("node"):
        rid = node.get("resource-id") or ""
        if " " in rid:  # Compose test tags can be UI text ("Texting with <name> (SMS/MMS)"), not ids
            node.set("resource-id", scrub(rid, keep))
        text, desc = node.get("text"), node.get("content-desc")
        if text:
            node.set("text", scrub(text, keep))
        if desc:
            # "You said <text> Sat 10:34 AM": the bubble's own text is scrubbed as the text was, so the two still match.
            if text and text.strip() and text in desc:
                before, after = desc.split(text, 1)
                node.set("content-desc", scrub(before, keep) + scrub(text, keep) + scrub(after, keep))
            else:
                node.set("content-desc", scrub(desc, keep))
    tree.write(dst, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    main()
