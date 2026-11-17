# The eduagarcia fork

This fork (`eduagarcia/ramblr`, branch `eduagarcia`) makes Ramblr dictate the way the owner's
desktop does with [whisrs](https://github.com/eduagarcia/whisrs): audio to Microsoft
MAI-Transcribe 2 through OpenRouter, the transcript through a cleanup LLM behind an
OpenAI-compatible gateway, with one vocabulary and one list of fixed replacements for both. The
original project is the remote `original` (https://github.com/trevornk/ramblr), `main` follows it,
and every change below is on `eduagarcia`.

None of the changes is on unless it is asked for: a build without a personal defaults file and
with untouched settings behaves like the original.

## What the fork adds

| Change | Where | Setting |
|---|---|---|
| **MAI-Transcribe vocabulary.** For `microsoft/mai-transcribe-*` the vocabulary goes in a `provider` form field holding an Azure phrase list (`{"options":{"azure":{"phraseList":{"phrases":[...]}}}}`). OpenRouter drops `prompt` and `keywords[]` for that model. | `TranscriberClient.vocabularyFormParts` | automatic, by model id |
| **MAI-Transcribe audio format.** The model refuses `.m4a` (HTTP 400 `invalid_audio`), so it always gets WAV, even with "compressed upload" on. | `TranscriberClient.acceptsCompressedUpload` | automatic, by model id |
| **Extra request fields for cleanup.** A provider entry can carry a JSON object merged into its chat-completions request: `reasoning_effort`, `service_tier`, its own `temperature`. `model`, `messages` and `stream` cannot be replaced. | `ProviderChainEntry.extraBody`, `PostProcessor.mergeExtraBody` | personal defaults only (no screen yet) |
| **Cleanup time budget.** The 8 s cap on cleanup can be raised for a reasoning model on a long transcript. When it runs out, the transcript is inserted as it is, as before. | `CleanupTimeout`, `hardCapMs` in `CleanupWaterfallExecutor.execute` | pref `cleanup_timeout_ms` (personal defaults only) |
| **Vocabulary list placeholder.** A custom cleanup prompt can word its own vocabulary instruction and put `{{vocabulary_list}}` where the bare, comma-separated terms go. `{{vocabulary}}` still inserts the built-in sentence. | `PostProcessor.interpolateVocabulary` | in the prompt text |
| **Snippets before cleanup.** Snippets used as fixed replacements ("Open Sense" → "OPNSense") can also be applied to the transcript before cleanup, so the LLM reads the right word. The pass on the final text still runs. | `SnippetsBeforeCleanupToggle` | Settings → Words and snippets → Snippets → "Also apply before AI cleanup" |
| **Dictation queue.** The next dictation can start while the previous one is still transcribed and cleaned. The texts are inserted in the order the dictations ended, each where the cursor is at that moment. The ring keeps its spinner while something is pending. | `DictationQueue`, `DictationQueueToggle` | Settings → Recording → "Queue dictations" |
| **Personal defaults.** One optional asset pre-configures a private build: providers and keys, language, vocabulary, replacements, cleanup prompt, any other preference. | `PersonalDefaults`, `RamblrApp` | `personal/assets/personal_defaults.json` |

The queue covers the floating icon with OpenAI-compatible cloud transcription. A dictation that
would be transcribed on the device or by Gemini, translated, previewed live or previewed before
inserting takes the original one-at-a-time path. A queued dictation cannot be cancelled with a
long press, and it is inserted where the cursor is when it is ready, not where it was started.

## Personal defaults

`personal/assets/personal_defaults.json` is packaged as an asset when it exists and applied by
`PersonalDefaults` when the app starts. `personal/` is gitignored: the file holds API keys.
[`personal-defaults.example.json`](../personal-defaults.example.json) shows every field.

- It is applied once per `version` value. A rebuild with the same file leaves alone what was
  changed in Settings since; a different `version` applies the whole file again, over those
  settings.
- Provider entries, snippets and the cleanup style it creates have fixed ids, so applying again
  replaces them. Entries and snippets added in the app are kept.
- `skipOnboarding` marks the first-run wizard as done, for a phone set up from outside (below).

To write it from a whisrs configuration:

```sh
python3 scripts/personal-defaults-from-whisrs.py ~/.config/whisrs/config.toml \
    --style-title "T3 Code" \
    --pref single_tap_restore_and_record_enabled=true \
    --pref ring_visibility_mode=KEYBOARD
```

It maps `[asr-sidecar]` to the transcription provider, `[llm]` and `llm_instruction` to the
cleanup provider and style (with whisrs' own vocabulary sentence), `vocabulary`,
`[replacements]`, `language`, and `[input] queue` to the dictation queue. `--pref KEY=VALUE` sets
any other preference of the app by its key.

## Building and installing

Needs JDK 17 and an Android SDK with `platforms;android-36`, `build-tools;36.0.0`,
`ndk;27.2.12479018` and `cmake;3.22.1`, and the submodules at the commits the repository pins
(`git submodule update --init --recursive`).

`local.properties` (gitignored):

```properties
sdk.dir=/home/you/Android/Sdk
# Optional. Build outputs and the native build tree go here instead of into the checkout: use a
# local disk when the checkout is on a network share.
ramblr.buildRoot=/home/you/.cache/ramblr-build
```

```sh
./gradlew --project-cache-dir ~/.cache/ramblr-build/gradle-project-cache assembleGithubDebug
adb install -r ~/.cache/ramblr-build/app-build/outputs/apk/github/debug/Ramblr-*-github-debug.apk
```

The debug build is signed with the machine's debug key (`~/.android/debug.keystore`): install
updates from the same machine, or uninstall first.

Setting the phone up over adb, with nothing to tap (`P=com.trevornk.ramblr`):

```sh
adb shell appops set $P ACCESS_RESTRICTED_SETTINGS allow        # a sideloaded app may not be an accessibility service otherwise
adb shell pm grant $P android.permission.RECORD_AUDIO
adb shell pm grant $P android.permission.POST_NOTIFICATIONS
adb shell dumpsys deviceidle whitelist +$P                      # no battery optimization
# Append to the services already enabled, do not replace them:
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services "<existing>:$P/$P.WhisperAccessibilityService"
```

## Testing on a device without speaking

The debug build's `DebugDeliverReceiver` (shell-only, see its header) has two actions for this:

- `DEBUG_NEXT_AUDIO --es file clip.wav`: the next dictation transcribes that clip (16 kHz mono
  16-bit WAV in the app's files directory) instead of what the microphone hears. Start and stop a
  recording on the ring afterwards; everything after the microphone is the real path.
- `DEBUG_PLAY --es file clip.wav`: plays the clip through the phone's speaker, for a test that
  includes the microphone.

```sh
adb push clip.wav /data/local/tmp/ && adb shell run-as $P cp /data/local/tmp/clip.wav files/
adb shell am broadcast -n $P/.DebugDeliverReceiver -a $P.DEBUG_NEXT_AUDIO --es file clip.wav
adb shell am start -n $P/.ImeTestHarnessActivity      # plain text fields to dictate into
adb shell run-as $P tail -n 3 files/benchmark_log.jsonl   # per-stage latency of each dictation
```

## Keeping up with the original

```sh
git fetch original
git checkout main && git merge --ff-only original/main && git push origin main
git checkout eduagarcia && git merge main
```

The fork keeps its code in new files where it can (`DictationQueue.kt`, `PersonalDefaults.kt`,
`CleanupTimeout.kt`, `SnippetsBeforeCleanupToggle.kt`, `RamblrApp.kt`) and touches the original
ones in few places: `TranscriberClient`, `PostProcessor`, `ProviderChain*`, `CleanupWaterfall*`,
`DictationRuntime` (queue hand-off in `transcribeApi`, `dictationStylePrompt`), the accessibility
service's runtime listener, and two settings screens.
