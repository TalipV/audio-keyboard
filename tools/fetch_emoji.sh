#!/usr/bin/env bash
# Downloads Unicode's emoji-test.txt (Emoji 15.1) and CLDR 44's English and Arabic emoji annotations (both Unicode
# License v3); :engine:buildEmoji turns them into the panel's catalog and its search words.
# Verified by SHA-256 so builds are reproducible.
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)/emoji"
mkdir -p "$DIR" && cd "$DIR"
CLDR=https://raw.githubusercontent.com/unicode-org/cldr/release-44/common

fetch() { # <file> <url> <sha256>
  if ! { [ -f "$1" ] && echo "$3  $1" | shasum -a 256 -c --status; }; then
    curl -fsSL -o "$1" "$2"
    echo "$3  $1" | shasum -a 256 -c --quiet
  fi
}

fetch emoji-test.txt https://unicode.org/Public/emoji/15.1/emoji-test.txt d876ee249aa28eaa76cfa6dfaa702847a8d13b062aa488d465d0395ee8137ed9
fetch annotations-en.xml "$CLDR/annotations/en.xml" 13db8bb0a85a1ab9c46dd70f6170d72e7938063eb04cb2ee65e46d0378cfebf6
fetch annotations-derived-en.xml "$CLDR/annotationsDerived/en.xml" cfcadefede165fdad566f32894d4a7975a0bce76ca3d6758a5f8f448e550e6b2
fetch annotations-ar.xml "$CLDR/annotations/ar.xml" a58fc9797bd4caa2cb4f718cdb43b9a98b417cd2e8fd7884bf0598a73caef0fc
fetch annotations-derived-ar.xml "$CLDR/annotationsDerived/ar.xml" d718358c65a6481203581c7a55477795bbf559807f5076e7a7341908c7c39cdc
echo "emoji data ready in $DIR"
