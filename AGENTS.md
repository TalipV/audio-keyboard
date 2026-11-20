# Agent notes

If `AGENTS.local.md` exists, read it too: it holds the maintainer's own setup (devices, servers, publishing) and is git-ignored.

## Building

`tools/wordlists/*.combined`, `tools/emoji/` and `local.properties` are git-ignored fetched artifacts:

```bash
tools/fetch_wordlists.sh && tools/fetch_emoji.sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :engine:test :app:testDebugUnitTest   # JVM tests
./gradlew :app:assembleDebug
```

In a fresh worktree, `.ultimate/worktree-setup.sh` links them from the main checkout instead.

## Releasing

Every push to `main` runs `.github/workflows/android-apk.yml`: JVM tests, then a release APK signed with the keystore
from the repo secrets (`ANDROID_KEYSTORE_B64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`),
named `AudioKeyboard-<versionName>.apk`, with the run number as `versionCode`. It replaces the rolling pre-release
`latest`. Bump `versionName` in `app/build.gradle.kts` for a new display version.

## Versioning

- **`versionName`** in `app/build.gradle.kts` is the version the user sees, as `MAJOR.MINOR.PATCH` (Semantic
  Versioning). The app is not finished yet, so it stays at `0.x.y`; `1.0.0` comes only when the user says so.
- **`versionCode`** is not maintained by hand. CI passes `-PversionCode=<run number>`, so every build counts up and
  installs as an update. The `7` in `app/build.gradle.kts` is only for local builds; leave it.
- **Raising the version is the agent's job.** Whenever a change to the app lands on `main`, raise `versionName` in the
  same commit, once per push, at the highest level any of the pushed changes needs:
  - **PATCH** (`0.8.0` → `0.8.1`): bug fixes, text fixes, small UI polish.
  - **MINOR** (`0.8.1` → `0.9.0`): new features, noticeable behaviour changes, and changes that break stored data or
    settings.
  - **MAJOR**: only when the user asks for it.
  - **No bump**: changes that do not reach the app (tests, docs, CI, refactoring without a behaviour change).
- **When in doubt** (PATCH or MINOR, bump or not), ask the user before committing.
- Name the new version in the commit message body (e.g. `Version 0.9.0.`).

## Commit directly to `main`

This is the owner's fork: commit changes and push them directly to `main`, without a pull request or side branch,
unless the user asks for one. Never open pull requests or send anything to the upstream repository
(yazinsai/audio-keyboard).

## Rules

- Never commit an API key, a server address of your own, or real messages. Chat-screen fixtures are scrubbed with
  `tools/scrub_dump.py` (keyed per run, so they can't be reversed); test chats use made-up names.
- Features that read chats, learn from what is sent or keep AI requests stay off by default (`Prefs.keepOldDefaults`).
- Words in `GLOSSARY.md` are the project's vocabulary; use them in code, UI and docs.

## Live tests

Live tests are skipped unless pointed at a service:

```bash
VENICE_API_KEY=... ./gradlew :engine:test -Pvoice=venice -Pllm=venice --tests '*Live*'
./gradlew :engine:test -Pvoice=http://<host>:9000/ -Pllm=http://<host>:11434/ --tests '*Live*'
```
