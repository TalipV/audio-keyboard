#!/usr/bin/env bash
# Fetches Material Symbols (Apache-2.0) Android vector drawables used by the keyboard.
# Rounded style matches Gboard's soft icon joints. android:tint is stripped; KeyboardView tints at draw time.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE=https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android
OUT=app/src/main/res/drawable
mkdir -p "$OUT"

fetch() { # <symbol> <variant-suffix> <resource-name>
  curl -fsSL "$BASE/$1/materialsymbolsrounded/$1$2_24px.xml" | sed 's/ android:tint="[^"]*"//' >"$OUT/$3.xml"
}

fetch shift "" ic_shift
fetch shift _fill1 ic_shift_on
fetch shift_lock _fill1 ic_shift_lock
fetch backspace "" ic_delete
fetch mood "" ic_emoji
fetch search "" ic_action_search
fetch send "" ic_action_send
fetch arrow_forward "" ic_action_go
fetch check "" ic_action_done
fetch keyboard_return "" ic_action_newline
fetch settings _fill1 ic_settings
fetch undo "" ic_undo
fetch mic _fill1 ic_mic
fetch keyboard "" ic_keyboard
fetch schedule "" ic_emoji_recent
fetch emoji_people "" ic_emoji_people
fetch emoji_nature "" ic_emoji_nature
fetch emoji_food_beverage "" ic_emoji_food
fetch emoji_transportation "" ic_emoji_travel
fetch emoji_events "" ic_emoji_activities
fetch emoji_objects "" ic_emoji_objects
fetch emoji_symbols "" ic_emoji_symbols
fetch emoji_flags "" ic_emoji_flags
fetch content_paste "" ic_clipboard
fetch arrow_back "" ic_arrow_back
fetch keep _fill1 ic_pin
echo "fetched $(ls "$OUT"/ic_*.xml | wc -l | tr -d ' ') icons"
