# Voice dictation: results (Plan 3, 2026-10-12)

The design is in spec §7, including the §7.5 amendments. This file records what was measured.

## Server (Speaches 0.8.3 at `http://mybox:9000`, measured while planning)

- **Speech model:** `deepdml/faster-whisper-large-v3-turbo-ct2` (`task: automatic-speech-recognition`).
- **Cold start:** loading the model takes about 1.6 s. The keyboard asks for it on mic touch-down.
- **Warm transcription:** about 0.25–0.35 s for a 2–4 s clip.
- **Hints:** `hotwords` and `prompt` both work ("Yazan" → "Yazin").
- **Vocabulary (2026-10-12):** six `say` clips (English names, Arabic words) sent with no hint, `hotwords`, and a glossary in `prompt`. `hotwords` is honoured, and with the words comma-separated it matched the prompt glossary on every clip (Oleksandr, Qdrant, Alhumaidi, Nahdi fixed; spaces were worse). A 40-word list of common words did no harm in `hotwords`, but in `prompt` it made one reply echo the list. So the vocabulary goes in `hotwords`, and `prompt` stays the cursor context.
- **Hallucinations:** silence came back as "you" and noise as "شكرا للمشاهدة", both with `no_speech_prob` 0. They are stopped by the endpointer, which doesn't send audio without speech, and by the `avg_logprob` phrase guard (`Transcript`).
- **Live test** (`./gradlew :engine:test -Pvoice`): en "Testing 1 2 3" in 274 ms, ar "مرحبا كيف حالك؟" in 249 ms.

## Emulator setup

- **Network:** the emulator can't reach Tailscale hosts. `tools/voice_proxy.py` on this Mac forwards `10.0.2.2:9100` to `mybox:9000`, and `tools/dev.sh proxy pass|drop|503|slow` injects faults.
- **Audio:** debug builds dictate from `files/voice-input.wav` (`tools/dev.sh voice <wav>`), played in real time and followed by silence.
- **Test clips:** made with macOS `say`; the Arabic ones use the Majed voice.

## Test connection (Settings → Voice typing)

> Server: OK · 821 ms / Model: deepdml/faster-whisper-large-v3-turbo-ct2 (automatic) / English: "Testing 1 2 3" · 1003 ms / Arabic: "مرحبا كيف حالك؟" · 999 ms

On the server side these requests took 218 and 250 ms. The rest is emulator networking plus the proxy.

## Scenarios (emulator)

| Scenario | Expected | Observed |
|---|---|---|
| Hands-free, 2 sentences, 1.2 s pause | 2 chunks, text in order, panel closes | "Send the report to us in by Friday. Thanks, and see you tomorrow." ("Yazin" → "us in": no personal words on the emulator) |
| Hold 7 s, release on the mic | same text on release | same text |
| Hold, slide off, release | nothing inserted, nothing kept | field empty, `no_backup/voice` empty (a mid-hold fix: texts now wait for the release) |
| Arabic layout | mirrored panel, العربية chip, Arabic text | "أرسل التقرير غدا. شكرا جزيلا"; the mic sits 22.9 dp from the left (Gboard 22.6) |
| Language chip ar→en mid-dictation | later chunks transcribed as English | chunk 1 English. Chunk 2 ("شكرا جزيلا") stays in Arabic script: Whisper does that with `language=en` too (checked with curl) |
| 6 s of silence | "Didn't catch that.", no request | as expected: only the model check, no transcription |
| Proxy `drop`, then `pass` | "Waiting for connection · 2 pending", badge, then text in order | as expected, within 10 s of the server returning; folder empty after |
| Proxy `503`, then `pass` | same | same |
| Airplane mode during dictation, off after 25 s | retry on network return, not after the backoff | text 4.9 s after airplane mode off, including emulator network start-up (the backoff would have been 16 s) |
| Force-stop while waiting, restart | texts come back as chips | 2 chips; tapping inserts each; long-press discards |
| Text arrives while in another field | chip, never inserted elsewhere | chip on the phone pad; tapped later in the text field, inserted there |
| Model `nope` (404) | Retry / Discard, audio kept | readable error panel, survives restart and keyboard hide; Retry (model fixed) → text; Discard → nothing kept |

## Latency (debug build, emulator, 5 dictations × 2 chunks)

- **Per-chunk request, client side:** median ≈ 770 ms (534–951). The server itself took 211–254 ms; the rest is emulator networking and the proxy hop. On the phone, talking straight to `mybox`, expect roughly server time plus the LAN or Tailscale round trip.
- **End of a sentence to its text:** about 0.7 s (the pause that cuts the chunk) plus the request. The panel closes after the 2 s auto-stop once everything has arrived.

## Release build

- **APK:** 17.0 MB.
- **Permissions:** VIBRATE, RECORD_AUDIO, INTERNET, ACCESS_NETWORK_STATE (plus AGP's internal receiver permission).
- **Hosts in code:** `https://api.venice.ai/api` (the default service since 2026-10-27) (My server has no default address).

## Known limits

- The real mic, audio focus during a call, and Bluetooth headsets are checked only on the phone.
- The hands-free panel's geometry is a design value until calibrated against the user's own Gboard voice screenshots.
- The endpointer is energy-based. If it misfires in noisy rooms, the upgrade path is WebRTC or Silero VAD.

## Final-review fixes (re-verified on the emulator)

- **Settings mistakes:** a malformed URL, wrong path, missing model, port out of range or TLS mismatch now shows the error panel with Retry and Discard ("Couldn't transcribe: Invalid server URL") instead of waiting forever. Real outages still wait.
- **Unexpected errors:** an error inside a transcription now shows the panel instead of freezing the queue.
- **Wrong-field protection** now tracks input sessions, not just the view id, so chat A's text can't land in chat B. Fields shown again, or after a keyboard switch, get chips.
- **One queue per process:** switching keyboards and back mid-wait keeps every text as a chip, and nothing is lost.
- **Cancel race:** a push-to-talk text that arrives just as the user cancels is dropped.
- **Odd replies:** a 200 reply without a transcription keeps the audio (Retry / Discard).
- **Incognito and password fields:** no prompt context is sent.

## AI orb (Plan 5, 2026-10-13)



### Server

- **Model:** llama-server at `http://mybox:11434/`, Qwen3.8-27B Q4, vision-capable, one slot.
- **Settings:**
  - `enable_thinking: false`
  - a `response_format` JSON schema `{"text": string}`, which llama-server enforces with a grammar
- **Live test** (`./gradlew :engine:test -Pllm`):
  - reply from screen context: 232 ms
  - rewrite to Arabic: 364 ms
- **Through the emulator proxy** (`tools/voice_proxy.py --port 9101 --upstream http://mybox:11434`):
  - `prompt → Text` in 726–995 ms
  - Settings → Test connection: "Hello there!" in 937 ms

### Scenarios (emulator)

| # | Scenario | Observed |
|---|---|---|
| 1 | 🎤 narrate | Keys give way to the see-through band and the app relayouts above it. "Testing 1 2 3" typed. The orb stays at DONE. |
| 2 | Swipe down; keyboard chip | Keys back both ways. |
| 3 | Tap the field with the orb open | Keys back. A dictation still running lands at the new cursor, as before. |
| 4 | Messages thread, ✦ "reply saying yes, I'll be there around eight" | "Yes, I'll be there around 8." in the compose box; ↶ shown. |
| 5 | ✦ "make it shorter" on a filled field, then ↶ | Rewritten shorter; ↶ restored the previous text. **Bug found and fixed:** a one-chunk instruction used to settle as "Didn't catch that". |
| 6 | Select "thanks", ✦ "make it more formal" | Only the selection changed ("Thank you"). |
| 7 | LLM proxy down | "Couldn't write: Can't reach…" with Retry / Insert what I said. Retry after restart wrote the reply; Insert typed the transcript. |
| 8 | Switch to the phone field while "Writing…" (6 s delayed proxy) | Neither field changed; the answer became a pending chip. |
| 9 | Password field | Not run here (no password field reachable on this AVD). ✦ hides on the same `mode.password` check that hides the mic. |
| 10 | Screen context off | The prompt still runs from the field (954 ms). The "screen context off" caption wasn't caught in a screenshot (thinking lasts about 1 s). |
| 11 | Arabic layout | Band mirrored (orb left, keyboard chip right); Arabic text. |
| 12 | Swipe down while a ✦ recording listens | Nothing typed; the instruction became a pending chip. It also survived a force-stop, coming back from disk. |

**Screen context:** with an empty field, "make it shorter" produced a short summary of the setup screen. The model can only have got that text from the accessibility service.

### Known limits

- Google Messages on this AVD often loses window focus (`mCurrentFocus=null`, "isn't responding"). That's unrelated to the keyboard; most scenarios ran in the test field.
- Android drops the accessibility service on every reinstall. Re-run `tools/dev.sh a11y on`. On the phone, sideloaded apps need App info → ⋮ → Allow restricted settings first.
- Once or twice the first ✦ tap right after `tools/dev.sh open` was ignored, while the keyboard window was still settling. The next tap worked; watch for it on the phone.

## Arabic (2026-10-22)

The user, who speaks Gulf Arabic, reported poor Arabic dictation. Measured with `curl` and the real `WhisperClient` against the live server. The clips are macOS `say` (Majed, padded like the first chunk): 28 Arabic clips (10 sentences of 2.6–5.5 s, 6 phrases, 12 one-word Gulf replies such as زين، ابشر، خلاص، وينك) and 6 English ones (Samantha). Majed speaks clean MSA with a TTS accent, so this tests the client's request logic, not how well the model hears real Gulf speech.

### What was wrong in the app

- **Latin hotwords romanize short Arabic.** Ten English hotwords (the user's names and jargon) turned one-word replies into Latin: "Zajin", "Abshar", "Mashcour", "Mashi". This happened with `language=ar` named, and while the reply still said `"language":"ar"`. With Arabic hotwords, or with any Arabic word in the list, the replies stayed in Arabic. Long sentences were unaffected.
- **Whisper misdetects short Gulf words.** With both languages enabled, the app lets Whisper detect the language. 6 of 15 short Arabic clips came back as another language: `id` "Awaki" (اوكي), `fi` "Viinu" (وينك), `af` "Ty im gelaas" (طيب خلاص), and `en` "Bye-bye" (طيب), "All right" (خلاص), "Yep" (يب). It placed all 6 English clips, short or long, correctly, and every Arabic clip of 1.3 s or more correctly.
- **The retry for an unlisted language named the active layout.** On the English layout that was `en`, and Arabic forced to English becomes English: "What's your name? … I'll go to the market later." (شلونك… أبي أروح السوق بعدين), "Tamam.", "Abshar.". Forcing `ar` on English speech fails just as badly: "Send the report…" became "شكرا للمشاهدة" and "Thanks" became "شكرا". A forced language is not a fix.
- **Not the cause:**
  - **Hallucination guard:** `no_speech_prob` was 0 on every clip, and none of the outputs is a listed stock phrase.
  - **English cursor context in `prompt`:** it lowered `avg_logprob` (for example −0.01 → −0.39) but never changed the script or the words. Wrong for longer context, see below.
  - **Endpointer and sample rate:** chunks are 16 kHz and cut only at pauses of 700 ms or more, with 200 ms padding.

### Fix (`WhisperClient`)

- A request that names a language sends only the hotwords written in its script.
- With several languages, a reply is asked again as Arabic when Whisper picked a language outside the set, or picked Arabic but wrote it in Latin letters. Before, the retry named the active layout's language, with every hotword.
- **Result:** Arabic clips that came out in the wrong language or script fell from 10/28 to 3/28 on the English layout, and from 9/28 to 3/28 on the Arabic layout. All 6 English clips are unchanged.
- **Still wrong:** "Bye yim!" (طيب), "Halas!" (خلاص) and "Yep!" (يب), which Whisper detects as `en`. Nothing in the reply tells them apart from real short English, so this is left to the model.

### English context turns Arabic into English (2026-10-23)

The user said "إن الحمد لله نحمده ونستعينه ونستغفره" and got English back. Majed's clip of it came back right with no prompt, with `ar` named, and with a 3-word English prompt. With 11 words of English context ("Hey Sam, sounds good. I'll be there around 8 and then") it came back as "I'll be there around 8 and then I'll be there", with `"language":"ar"` detected, and the same with `ar` named. Whisper continued the prompt instead of hearing the audio. English audio under an Arabic prompt stayed English.

Later the same day, the user's Bahraini dictation came back as an English translation of what they said. Measured on 8 clips (4 Arabic incl. two Gulf sentences, 3 English, 1 English with an Arabic word):

- **Context in `prompt` pulls speech into its language.** With 11 words of English before the cursor, every Arabic clip went wrong while Whisper still said `"language":"ar"`: translated ("Hello, how are you?" for مرحبا كيف حالك), echoed, or half romanized ("Shlounik Shano Shui, اليوم…"). With Arabic context, English speech with one Arabic word became "ترجمة نانسي قنقر". Without context, or with hotwords only, all 8 were right.
- **A named language that is wrong translates or hallucinates.** Arabic decoded as `en` came back as an English translation ("What can I do? I worked like this…"); English decoded as `ar` came back as stock Arabic phrases.
- **The right language is the surer one.** Mean segment `avg_logprob` was higher for the right language on all 8 clips: Arabic −0.02…−0.09 vs −0.13…−0.74 as English; English −0.02…−0.30 vs −0.32…−0.85 as Arabic. One decode takes about 0.3 s.

**Fix:**
- With both languages enabled, no context goes in `prompt`; with Arabic alone, only Arabic context. English alone keeps it.
- Whisper's guess is checked against a decode in the other language, and the reply with the higher mean `avg_logprob` is kept. A chunk now takes two requests (about 0.6 s instead of 0.3 s).
- Arabic that comes back mostly in Latin letters is asked again as Arabic, even with one Arabic word left in.
- Without context, Whisper takes its casing from the hotword list and writes "send the report…", so dictation at a sentence start gets the same capital that typing gets.
- **Result:** all 8 clips came back right, with English context and with Arabic context before the cursor.

### Server model: what the client can't fix

On sentences, turbo's misses were dialect words, not the client: تسوي → "شوي", نشوفك → "شوفك", الاجتماع → "لاجتماع". Published Gulf numbers: stock Whisper large-v3 has 37–53% WER on Gulf/Saudi test sets (DataHive's Najdi/Hejazi set; dev-ahmedhany's Casablanca UAE set). Turbo is a pruned large-v3 (4 decoder layers instead of 32), and its dialect accuracy is no better.

**Recommended:** `parseh/whisper-ar-dialectal-ct2`.
- **What it is:** a float16 CTranslate2 build of `oddadmix/whisper-large-v3-turbo-arabic-dialectal-v2` (Apache-2.0). That is large-v3-turbo fine-tuned on 52k clips covering 13 Arabic dialects.
- **Accuracy:** its card reports 17.3% WER (CER 4.9%) on Saudi/Gulf, while stock turbo scored about 59% zero-shot across all dialects on the same author's earlier test set.
- **Cost:** the same size and speed as today's model, about 1.6 GB of weights and roughly 2–3 GB of VRAM in float16.
- **Install:** Speaches already lists it in `GET /v1/registry`. Download it with `POST http://mybox:9000/v1/models/parseh/whisper-ar-dialectal-ct2` (or `uvx speaches-cli model download parseh/whisper-ar-dialectal-ct2`).
- **Select it in the app:** Settings → AI service (My server) → Speech model. Pick it explicitly, because "automatic" takes the server's first speech model and the order can change after a download. Run Test connection afterwards.
- **Check English first.** The fine-tune is Arabic-only, so check English dictation and language detection with it before keeping it. If English suffers, switch the picker back for English-heavy days.

**Alternatives:**
- `oddadmix/whisper-large-v3-arabic-dialectal-v2`:
  - **What it is:** full large-v3, Apache-2.0, 16.9% Gulf WER.
  - **Install:** it is Transformers-only. Convert it with `ct2-transformers-converter --model oddadmix/whisper-large-v3-arabic-dialectal-v2 --output_dir … --quantization float16 --copy_files tokenizer.json preprocessor_config.json`, then serve it from the Hugging Face cache.
  - **Cost:** about 3.1 GB of weights, 4–5 GB of VRAM, and slower decoding than turbo.
- `Systran/faster-whisper-large-v3`: stock full large-v3, if a fine-tune hurts English too much.
- `datahiveai/whisper-large-v3-ar-dialects`: has good Najdi/Hejazi numbers, but it is a gated LoRA under a no-redistribution licence, with no CTranslate2 build.

## Venice AI (2026-10-27)

Dictation and ✦ now default to Venice AI (`https://api.venice.ai/api`, OpenAI-compatible). Your own Speaches and llama-server servers stay available as Settings → AI service → My server.

### How Venice requests differ

- **Key:** `Authorization: Bearer <key>`, from Settings → AI service → API key. Without a key nothing is sent, and dictation shows "Add your Venice API key in Settings → AI service". My server sends a key only if one is set.
- **✦:** Venice answers unknown JSON keys with a 400, so `chat_template_kwargs` is replaced by `venice_parameters: {disable_thinking: true, include_venice_system_prompt: false}`. A model is always named; the default is `deepseek-v4-1-flash`. Models that can only think answer 400, and the message is shown as it is.
- **Dictation:** `openai/whisper-large-v3`. `response_format` takes only `json`, so there is no detected language, no segments and no `avg_logprob`. `hotwords` is accepted and ignored, so it isn't sent, and Recognize your words is greyed out.
- **Errors:** Venice replies `{"error":"…"}` or `{"error":"Invalid request parameters","issues":[{"message":…}]}`. A 401 reads "API key not accepted", and a 402 "Venice account has no credit".
- **Key check:** `GET /v1/api_keys/rate_limits` returns the balance without billing anything. Settings runs it when the key changes ("works, $4.97 left"). `GET /v1/models` is public; the pickers show only `private` models, and for ✦ only those that follow a JSON schema and can answer without thinking.

### Latency

- **curl, 24 clips:** median about 1.9 s; 22 of 24 came back in 1.5–4.2 s, but two took 10.2 and 16.1 s. Under the old 12 s read timeout those became retries ("Waiting for connection") and held up every text after them.
- **Fix:** a Venice transcription still open after 5 s is sent once more, and the first good reply wins. The read timeout is 30 s plus twice the clip.
- **Through `WhisperClient` afterwards:** median 1005 ms, max 1888 ms; the hedge never fired.
- **Live tests** (`VENICE_API_KEY=... ./gradlew :engine:test -Pvoice=venice -Pllm=venice --tests '*Live*'`):
  - en "Testing 1 2 3" in 1181 ms, ar "مرحباً، كيف حالك؟" in 1582 ms
  - ✦: rewrite to Arabic 1860 ms, "15% of 80" → "12" 359 ms, other replies 679–751 ms
- **Emulator, Test connection:** Venice OK · 255 ms; English "Testing 1 2 3" · 2029 ms; Arabic "مرحباً، كيف حالك؟" · 3829 ms. ✦: "Hey, how's it going?" · 626 ms. Dictating test-en.wav typed "Testing 1, 2, 3.", and a Majed خلاص typed خلاص.

### Arabic without scores

Without a detected language or `avg_logprob`, the Speaches checks can't run. With Arabic among the languages, a reply that is mostly Latin letters is asked again with `language=ar` when most of its words aren't in the English dictionary. The Arabic reading is kept only if it is in Arabic script and isn't a stock phrase.

- **Measured** (the 15 Majed and 6 Samantha clips, with en,ar and ar,en): romanized replies came back in Arabic (Halas → خلاص, Wiinuk → وينو, Bayyib → طيب, Awaki → أوكي, Shlomik → شلوني, Mascuro → مشكور). All 6 English clips were unchanged, each with a single request.
- **Still wrong:** "Yep" (يب) is a real English word, so it stays. The model itself mishears زين → زجن and تمام → دمام.

### Known limits

- Gulf Arabic that Whisper translates into real English can't be caught on Venice: there are no scores to compare.
- No word hints on Venice, and no `avg_logprob` phrase guard: the endpointer is the only defence against "you" and "شكرا للمشاهدة" on silence.
- The speech model is fixed to `openai/whisper-large-v3`. Of Venice's other private speech models, `nvidia/parakeet-tdt-0.6b-v3` has no Arabic, and `fal-ai/wizper` translated مرحبا كيف حالك to "Hello, how are you?" (it is faster: 1.2–1.6 s against 1.5–2.9 s).
