---
name: emulator-check
description: Verify a My Board change on the Android emulator the way this project does: boot at a Pixel's metrics, install the debug build, drive typing, glide, dictation and ✦ through tools/dev.sh and the proxies, and capture screenshots and the ✦ log as evidence. Use whenever a change touches the keyboard's behaviour or UI and needs checking beyond unit tests.
---

# Checking a change on the emulator

`tools/dev.sh` is the dev loop; read its `case` arms for the details. Run everything from the checkout you changed.

## 1. Target the emulator

A Nexus 5X may be plugged in over USB, so every adb command targets the emulator:

```bash
export ANDROID_SERIAL=emulator-5554
tools/dev.sh boot      # AVD Pixel_5_API_34_Clean, resized to the phone's 1080x2400 @ 420 dpi, gesture nav
tools/dev.sh install   # debug build, enables both subtypes (1 = EN, 2 = AR) and selects the IME
tools/dev.sh open      # the app's own test field, focused (more reliable than Google Messages)
```

Only one emulator runs at a time on this Mac. If another branch already has it booted, reuse it and reinstall from your
checkout; say so in your report, since your install replaces theirs.

- Reinstalling drops the accessibility service (screen context): run `tools/dev.sh a11y on` after every install when
  the check involves ✦ or Chat memory.
- A force-stop switches the IME away: `adb shell ime set dev.yazin.keyboard/.ime.KeyboardService`.
- The AVD has Gboard 12.4 preinstalled as a layout reference.

## 2. Reach the AI services

Venice AI (the default service) works from the emulator over the internet, but the key must be entered in Settings → AI
service; never write a key into the device's prefs or the repo. For automated checks, use your own server through the
proxies instead, because the emulator can't reach Tailscale hosts (AGENTS.local.md may name the maintainer's):

```bash
python3 tools/voice_proxy.py --port 9100 --upstream http://<host>:9000 &    # dictation (Speaches)
python3 tools/voice_proxy.py --port 9101 --upstream http://<host>:11434 &   # ✦ (llama-server)
```

Point the debug build at them by writing its prefs with run-as (force-stop first, then reopen):

```bash
adb shell am force-stop dev.yazin.keyboard
adb shell "run-as dev.yazin.keyboard sh -c 'mkdir -p shared_prefs && cat > shared_prefs/dev.yazin.keyboard_preferences.xml'" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="service">server</string>
    <string name="voice_url">http://10.0.2.2:9100/</string>
    <string name="llm_url">http://10.0.2.2:9101/</string>
</map>
EOF
adb shell ime set dev.yazin.keyboard/.ime.KeyboardService && tools/dev.sh open
```

This overwrites the debug build's other prefs; add any the check needs. Stop the proxies when done.
Fault injection for voice: `tools/dev.sh proxy pass|drop|503|slow`.

## 3. Drive it

- **Typing:** `adb shell input text hello` (it also triggers sent-message capture), `adb shell input keyevent …`.
- **Language:** `tools/dev.sh lang en|ar`.
- **Glide:** `tools/dev.sh glide <word> [shot]` traces the key centres of the active layout.
- **Dictation and ✦:** debug builds read `files/voice-input.wav` instead of the mic. Make a clip with
  `say -o /tmp/c.aiff "…" && afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/c.aiff /tmp/c.wav` (Arabic: `say -v Majed`),
  then `tools/dev.sh voice /tmp/c.wav`, and tap 🎤 or ✦. `tools/dev.sh voice off` returns to the mic. Ready clips:
  `app/src/main/assets/voice/test-{en,ar}.wav`.
- **Screen reading:** `tools/dev.sh dump <name>` saves the focused window's accessibility tree. Dumps hold real text:
  scrub them (`tools/scrub_dump.py`) before committing.
- Google Messages on the AVD often loses window focus and is an old View-based build that ScreenChat can't read;
  WhatsApp isn't installed. Use Files → Share for WhatsApp exports (`am start` can't grant MediaStore URIs).

## 4. Collect evidence

- `tools/dev.sh shot <name>` saves `build/shots/<name>.png`; look at it.
- `tools/dev.sh llmlog` pulls the last 50 ✦ calls (request, raw reply, latency) to `build/llm-log.jsonl`.
- `adb logcat -s KbdVoice` explains why listening ended.
- Report measured numbers (latency, counts) and name what could only be checked on a real phone. Never install on a
  phone yourself: shipping is `tools/dev.sh publish` after landing.
