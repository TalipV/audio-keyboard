#!/usr/bin/env bash
# Dev loop: boot the emulator at the user's phone metrics, install, enable the IME, open the test field, screenshot.
set -euo pipefail
SDK="$HOME/Library/Android/sdk"
AVD="${AVD:-Pixel_5_API_34_Clean}"
PKG="dev.yazin.keyboard"
IME="$PKG/.ime.KeyboardService"
cd "$(dirname "$0")/.."
mkdir -p build/shots

case "${1:-}" in
  boot)
    if ! adb get-state >/dev/null 2>&1; then
      nohup "$SDK/emulator/emulator" -avd "$AVD" -no-snapshot-save -no-boot-anim >build/emulator.log 2>&1 &
    fi
    adb wait-for-device
    until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
    # Match the user's phone: 1080x2400 at 420 dpi (411 dp wide), gesture navigation.
    adb shell wm size 1080x2400
    adb shell wm density 420
    adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural >/dev/null 2>&1 || true
    ;;
  install)
    ./gradlew -q :app:installDebug
    # The system rescans input methods shortly after install; wait for ours to appear.
    for _ in $(seq 1 20); do adb shell ime list -a -s | tr -d '\r' | grep -qx "$IME" && break; sleep 0.5; done
    adb shell ime enable "$IME" >/dev/null
    # Enable both subtypes (ids from res/xml/method.xml) so the system language switcher offers them.
    others="$(adb shell settings get secure enabled_input_methods | tr -d '\r' | tr ':' '\n' \
      | grep -v -e "^$PKG/" -e '^null$' -e '^$' | paste -sd: - || true)"
    adb shell settings put secure enabled_input_methods "'${others:+$others:}$IME;1;2'"
    adb shell ime set "$IME" >/dev/null
    sleep 2 # let the system rebind the freshly installed keyboard before a field asks for it
    ;;
  open)
    # Clear the activity task (not force-stop: that would also kill the keyboard process).
    adb shell am start -W --activity-clear-task -n "$PKG/.setup.SetupActivity" --ez focus true >/dev/null
    sleep 1
    ;;
  lang)
    case "${2:-}" in en) id=1 ;; ar) id=2 ;; *) echo "usage: tools/dev.sh lang en|ar"; exit 1 ;; esac
    adb shell settings put secure selected_input_method_subtype "$id"
    "$0" open
    ;;
  shot)
    sleep 0.5
    adb exec-out screencap -p >"build/shots/${2:?usage: tools/dev.sh shot <name>}.png"
    echo "build/shots/$2.png"
    ;;
  voice)
    # Debug builds dictate from files/voice-input.wav instead of the mic (emulator tests); "voice off" removes it.
    if [ "${2:-}" = off ]; then adb shell run-as dev.yazin.keyboard rm -f files/voice-input.wav; exit 0; fi
    adb push "${2:?usage: tools/dev.sh voice <file.wav>|off}" /data/local/tmp/voice-input.wav >/dev/null
    adb shell "run-as dev.yazin.keyboard sh -c 'mkdir -p files && cat /data/local/tmp/voice-input.wav > files/voice-input.wav'"
    adb shell pm grant dev.yazin.keyboard android.permission.RECORD_AUDIO
    ;;
  glide)
    # Glides a word through the key centres of the active layout (tools/glide.py); a shot name also captures mid-stroke.
    lang="$(adb shell settings get secure selected_input_method_subtype | tr -d '\r')"
    [ "$lang" = 2 ] && lang=ar || lang=en
    if [ -n "${3:-}" ]; then
      adb shell "$(python3 tools/glide.py "$lang" "${2:?usage: tools/dev.sh glide <word> [shot-name]}" /data/local/tmp/glide.png)"
      adb pull /data/local/tmp/glide.png "build/shots/$3.png" >/dev/null 2>&1 && echo "build/shots/$3.png"
    else
      adb shell "$(python3 tools/glide.py "$lang" "${2:?usage: tools/dev.sh glide <word> [shot-name]}")"
    fi
    sleep 0.6
    ;;
  proxy)
    # Fault mode for tools/voice_proxy.py.
    mkdir -p build && echo "${2:?usage: tools/dev.sh proxy pass|drop|503|slow}" >build/voice-proxy.mode
    ;;
  llmlog)
    # The ✦ debug log (last 50 LLM calls, JSONL) from a debug build; release builds: Settings → AI writing → Debug log.
    adb exec-out run-as "$PKG" cat no_backup/llm-log.jsonl >"build/llm-log.jsonl"
    echo "build/llm-log.jsonl"
    ;;
  a11y)
    # Turns the screen-context accessibility service on or off without the Settings UI (emulator only).
    svc="dev.yazin.keyboard/.a11y.ScreenReader"
    if [ "${2:-}" = off ]; then
      adb shell settings put secure enabled_accessibility_services null
      adb shell settings put secure accessibility_enabled 0
    else
      adb shell settings put secure enabled_accessibility_services "$svc"
      adb shell settings put secure accessibility_enabled 1
    fi
    ;;
  dump)
    # The focused window's accessibility tree, with bounds, into build/dumps/<name>.xml (chat-memory spike, Plan 8 Task 1).
    # Read-only: works on the phone too (ANDROID_SERIAL=<phone>). Raw dumps hold real messages: scrub before committing.
    mkdir -p build/dumps
    adb shell uiautomator dump /sdcard/kbd-dump.xml >/dev/null
    adb exec-out cat /sdcard/kbd-dump.xml >"build/dumps/${2:?usage: tools/dev.sh dump <name>}.xml"
    adb shell rm -f /sdcard/kbd-dump.xml
    echo "build/dumps/$2.xml"
    ;;
  publish)
    # Builds the release APK and serves it on port 8088 to an APK updater on your phone (e.g. Obtainium over Tailscale).
    # Each publish gets a new filename: Obtainium's HTML source ("APK Link Hash") sees a new link as an update.
    # The server survives publishes and serves the main checkout's dist/obtainium, so the APK always lands there,
    # even when published from a worktree.
    ./gradlew -q :app:assembleRelease
    main="$(dirname "$(git rev-parse --git-common-dir)")"
    out="$main/dist/obtainium"
    rm -rf "$out" && mkdir -p "$out"
    cp app/build/outputs/apk/release/app-release.apk "$out/my-board-$(date +%Y%m%d-%H%M%S).apk"
    if ! nc -z localhost 8088 2>/dev/null; then
      mkdir -p "$main/build"
      # Detach the process group as well as stdio so an agent shell's cleanup doesn't stop the server.
      /usr/bin/python3 - "$main" "$out" <<'PY'
import pathlib
import subprocess
import sys

main, out = (pathlib.Path(p).resolve() for p in sys.argv[1:])
with (main / "build/apk-server.log").open("a") as log:
    subprocess.Popen(
        [sys.executable, "-m", "http.server", "8088", "--directory", str(out)],
        stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True,
    )
PY
      for _ in $(seq 1 20); do nc -z localhost 8088 2>/dev/null && break; sleep 0.1; done
      if ! nc -z localhost 8088 2>/dev/null; then
        echo "APK server failed to start; see $main/build/apk-server.log" >&2
        exit 1
      fi
    fi
    ls "$out"
    ;;
  *)
    echo "usage: tools/dev.sh boot|install|open|lang en|ar|shot <name>|glide <word> [shot]|voice <wav>|off|proxy <mode>|llmlog|a11y on|off|dump <name>|publish"
    exit 1
    ;;
esac
