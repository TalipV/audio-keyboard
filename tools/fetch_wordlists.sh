#!/usr/bin/env bash
# Downloads the word lists the dictionaries are built from: Leipzig Corpora (CC BY 4.0) via HeliBoard's aosp-dictionaries.
# Verified by SHA-256 so builds are reproducible.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)/wordlists"
mkdir -p "$DIR" && cd "$DIR"
BASE=https://codeberg.org/Helium314/aosp-dictionaries/raw/branch/main/wordlists_experimental

fetch() { # <file> <sha256>
  if [ -f "$1" ] && echo "$2  $1" | shasum -a 256 -c --status; then return; fi
  curl -fsSL -o "$1" "$BASE/$1"
  echo "$2  $1" | shasum -a 256 -c --quiet
}

fetch main_en_US.combined 466c2eb4737fa1a9453e5f4a33337db8dd703f75012b70c2534490fd189c9487
fetch main_ar.combined d5fd8e726331fccfc9bb3818b2414e048760bd4ae41fc01c57afe9df11abaf07
echo "word lists ready in $DIR"
