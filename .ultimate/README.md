# Ultimate in audio-keyboard

Configuration for working on My Board with [Ultimate](https://github.com/HazAT/ultimate).

- `settings.json`: worktree setup for coordinator branches and the lander; the lander runs the JVM unit tests (`:engine:test :app:testDebugUnitTest`) before every merge into main and pushes main to `origin`.
- `skills/emulator-check/`: how branches verify a change on the emulator (device targeting, proxies, debug prefs, driving typing, glide, dictation and ✦, evidence).
- `worktree-setup.sh`: gives each fresh worktree the ignored files the Gradle build needs from the main checkout: `local.properties` (Android SDK path) and links to the fetched `tools/wordlists` and `tools/emoji` data. With them, `./gradlew :engine:test :app:testDebugUnitTest` runs in a new worktree.

Project rules for agents are in `AGENTS.md` at the repository root.
