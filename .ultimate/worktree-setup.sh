#!/bin/sh
set -e
# Worktree setup: the ignored files the Gradle build needs, taken from the source checkout.
# local.properties names the Android SDK; tools/wordlists and tools/emoji are the fetched inputs
# for :engine:buildDicts and :engine:buildEmoji (read-only, so a symlink is enough).
# Nested worktrees source from another worktree that may lack these files (setup failed or an
# older checkout), so fall back to the main checkout, which always has them.
SRC=$ULTIMATE_SOURCE
MAIN=$(cd "$SRC" && cd "$(git rev-parse --git-common-dir)/.." && pwd)
[ -f "$SRC/local.properties" ] || SRC=$MAIN
[ -f local.properties ] || cp "$SRC/local.properties" local.properties
for d in tools/wordlists tools/emoji; do
  if [ ! -e "$d" ]; then
    FROM=$SRC/$d
    [ -e "$FROM" ] || FROM=$MAIN/$d
    ln -s "$FROM" "$d"
  fi
done
# The maintainer's own agent notes, if any (git-ignored).
[ -e AGENTS.local.md ] || { [ -e "$MAIN/AGENTS.local.md" ] && ln -s "$MAIN/AGENTS.local.md" AGENTS.local.md; } || true
