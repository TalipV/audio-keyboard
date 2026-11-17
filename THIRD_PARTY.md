# Third-party material

- **Material Symbols** (Rounded), Google, Apache License 2.0: icons in `app/src/main/res/drawable/ic_*.xml`.
  https://github.com/google/material-design-icons
- **Dictionaries**: Leipzig Corpora Collection word lists (CC BY 4.0), prepared by HeliBoard's aosp-dictionaries
  (`wordlists_experimental/main_en_US.combined`, `main_ar.combined`). Built into `en.dict` / `ar.dict` by `:engine:buildDicts`.
  https://wortschatz.uni-leipzig.de/en/download/ · https://codeberg.org/Helium314/aosp-dictionaries
- **Unicode emoji data**: `emoji-test.txt` (Emoji 15.1) and the CLDR 44 emoji annotations
  (`common/annotations/{en,ar}.xml`, `common/annotationsDerived/{en,ar}.xml`), © Unicode, Inc., Unicode License v3.
  Fetched by `tools/fetch_emoji.sh` and built into `emoji.txt` and `emoji-words-{en,ar}.txt` by `:engine:buildEmoji`.
  https://www.unicode.org/license.txt · https://unicode.org/Public/emoji/15.1/ · https://github.com/unicode-org/cldr
