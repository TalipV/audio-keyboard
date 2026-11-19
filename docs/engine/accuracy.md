# Autocorrect accuracy

Harness: `app/src/test/java/dev/yazin/keyboard/text/AccuracyHarnessTest.kt`.

```bash
./gradlew :app:testDebugUnitTest --tests '*AccuracyHarnessTest' -Pharness
```

**What it simulates:**
- Thumb typing on the real Gboard key geometry: Gaussian touch spread of 0.22 key pitches.
- 4% dropped letters and 3% swapped letters.
- Arabic users skip the hamza on alef 60% of the time.

**What it measures:**
- 4,000 words sampled by frequency.
- 500 held-out real words (frequency 40–90) typed exactly. These stand in for words the dictionary doesn't know.

## Results

| Run | Lang | Correct words changed (gate < 0.5%) | Typos fixed (of fixable) | Intended in top 3 | Unknown words changed | p95 ms (JVM) | Knobs |
|---|---|---|---|---|---|---|---|
| baseline | EN | 0.2% | 54.1% | 93.3% | 31.5% | 2.5 | plan defaults |
| baseline | AR | 0.0% | 52.8% | 86.0% | 30.1% | 10.7 | plan defaults |
| tuned | EN | 0.2% | 55.4% (60.4%) | 92.2% | 13.4% | 0.6 | see below |
| tuned | AR | 0.0% | 51.7% (62.4%) | 84.9% | 14.4% | 3.7 | see below |
| tuned | DE | 0.0% | 68.7% (69.3%) | 94.0% | 8.2% | 1.3 | same knobs; 250k words, [Compounds], ss → ß |

- **"Correct words changed":** these are the word list's deliberate shortcut fixes for real-but-rare words, e.g. `hum`→`him`.
- **German:** nouns are typed with their capital (no touch, like a long-press alternate). Held-out words that split
  into frequent words count as compounds and are left alone, which keeps "unknown words changed" low.
- **"Of fixable":** excludes slips that produced another real word (`of`→`if`). No autocorrect can catch those: 8% of English typos and 17% of Arabic ones.

## What changed in tuning, and why

**1. Completions are not corrections.** Gboard shows these in the strip but never swaps them in:
- inflections of what was typed (`snippet`→`snippets`, `harms`→`harm's`)
- Arabic forms that add a suffix (`إدراكهم`→`إدراكهما`)
- Arabic forms that drop a leading clitic (و ب ل ك ف س): `بالكفاح`→`الكفاح`

**2. A word's own extensions aren't rivals.** `giant` used to lose its confidence margin to `giants`, and `strange` to `stranger`.

**3. Score floor.** `minScore = −1`: rare words reached by big edits aren't worth overriding what was typed.

**4. Knob values:**
- `Params.sigma`: 0.45 → **0.50**
- `Policy.minMargin`: 0.8 → **1.2**
- `maxCostPerChar`: 0.55 → **0.45**
- `maxCompletion`: 10 → **6**, and at most typed length + 2 for short inputs
- `maxCostCap`: 7 → **4.6**

**5. The cost cap is the speed fix.** The search budget used to grow to about 7 for long words, and each long Arabic `ال…` word explored most of the trie. Capping it at 4.6 cut Arabic p95 from 10–16 ms to about 3.7 ms with no accuracy loss, since autocorrect never accepts costs above 4.2.

## Trade-off

**The plan's aims:** typos fixed ≥ 60% and unknown words changed ≤ 10%. With this model they cannot both be met:
- Every extra fixed typo past this point costs about as many wrongly changed unknown words.
- The tuned point fixes about 60% of fixable typos and changes about 13–14% of unknown words.

**What softens the misses in use:**
- one backspace (or the undo chip) restores the typed word
- that word is never corrected again in the session
- typing a word twice makes it the user's own

**Where to move the knobs:** `Autocorrect.Policy`. Raise `minMargin` toward 1.6 if wrong corrections annoy; lower it toward 0.8 if typos slip through.

## On the emulator (Pixel 5 AVD, API 34, arm64)

| Build | Suggest p50 | Suggest p95 | Max |
|---|---|---|---|
| debug (debuggable, weak JIT) | 17.7 ms | 55.6 ms | 65 ms |
| **release** | **1.6 ms** | **4.7 ms** | 19 ms |

Target (spec §6.7): < 30 ms. Release PSS: 50.7 MB, because the dictionaries are memory-mapped rather than loaded onto the heap. Release APK: 16.8 MB.

## Glide (Plan 7)

Harness: the same file, `englishGlide` and `arabicGlide` (same `-Pharness` command).

**What it simulates:** one stroke per word, 2,000 words sampled by frequency:
- each letter's key centre is aimed at with Gaussian noise of 0.25 key pitch
- the corners are rounded: a quadratic Bézier around each key, starting 0.4 pitch out, so the stroke passes within about 0.2 pitch of the key
- points are sampled at a thumb speed of 18 pitches/s every 8 ms, with 0.03-pitch jitter
- doubled letters add no movement, and Arabic users skip the hamza on alef 60% of the time

**Bars (glide spec §6):** intended word first ≥ 85% EN / 75% AR, in the top 3 ≥ 95% EN / 90% AR, decode p95 ≤ 30 ms.

| Run | Lang | Intended first | In top 3 | p50 ms (JVM) | p95 ms (JVM) | Knobs |
|---|---|---|---|---|---|---|
| plan defaults | EN | 85.2% | 95.5% | 4.8 | 12.1 | see below |
| plan defaults | AR | 71.2% | 92.6% | 12.1 | 28.6 | |
| tuned | EN | 90.5% | 97.6% | 1.1 | 3.0 | see below |
| + case twins | EN | 91.3% | 98.6% | 1.1 | 3.0 | `fox` beats `Fox` (found on the emulator) |
| tuned | AR | 80.8% | 96.3% | 2.5 | 7.3 | |

("Plan defaults" already include the per-stroke cost table below; without it EN p95 was 35–70 ms and AR 148 ms.)

**What changed, and why:**
1. **A cost table per stroke.** Each letter's cost at every stroke point is computed once per decode, not per trie node. This cut decode time 4–5×.
2. **`startSlack` 0.6 → 0.15.** With slack, the first letter could align a little way along the stroke, so Arabic words lost their leading و or ل (`وتقارير` → `تقارير`, `برفض` → `رفض`).
3. **`cornerDegrees` 50 → 25 and `corner` 3 → 5.** A stroke's turns must be explained by letters, and gentler turns count. This was the largest gain: EN 87 → 90.5%, AR 75 → 81%. 20° measured slightly better again, but real thumbs wobble more than the simulation's jitter, so 25° is kept as a margin. Check this in the phone pass.
4. **`sigma` 0.45 → 0.40.** Slightly better in both languages, and a tighter search.
5. **`maxCost` 14 → 10.** The same accuracy, at half the decode time.

**Misses that remain** are mostly ambiguous by shape:
- doubled letters (`للفئة` / `لفئة`, `ووظائف` / `وظائف`)
- adjacent-key twins (`رحلاتهم` / `رحلتهم`, `sting` / `sing`)
- inflections along the same path (`compilers` / `compiled`)

In each case the intended word is usually second, which is why the top-3 rate is high.
