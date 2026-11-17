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
