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
| **Transcription retries and a fallback model.** A request that is rate limited (HTTP 429), hits an overloaded or unreachable server (502, 503, 504, 520 to 524) or gets no answer is sent again, waiting 1 s, then 2 s (or the server's `Retry-After` when longer, 5 s at most). When the retries run out, a second model is asked once at the same provider with the same key, without the vocabulary. A bad request, a refused key and any credit or billing error are never repeated and never go to the fallback. | `TranscriptionRetry`, `TranscriberClient.transcribeWithRetries` | Settings → Recording → "Transcription retries" and "Fallback transcription model" (0 and none by default) |
| **Pause near apps.** The accessibility service turns itself off the moment a listed app comes to the front and back on when the user leaves it. For banking apps that warn about, or refuse to run next to, an accessibility service they do not know. | `PauseNearApps`, `PauseWatchService`, `PauseNearAppsActivity` | Settings → Privacy → "Pause near apps" (empty by default; needs the two grants below) |
| **Personal defaults.** One optional asset pre-configures a private build: providers and keys, language, vocabulary, replacements, cleanup prompt, any other preference. | `PersonalDefaults`, `RamblrApp` | `personal/assets/personal_defaults.json` |

The queue covers the floating icon with OpenAI-compatible cloud transcription. A dictation that
would be transcribed on the device or by Gemini, translated, previewed live or previewed before
inserting takes the original one-at-a-time path. A queued dictation cannot be cancelled with a
long press, and it is inserted where the cursor is when it is ready, not where it was started.

### Why the retries exist, and what gets logged

Microsoft MAI-Transcribe 2 has one provider on OpenRouter (Azure), and its rate limit is not the
caller's doing: on 2026-11-17, 4 of 30 isolated requests sent 6 s apart got
`429 {"error":{"message":"Provider returned 429","code":429,"metadata":{"retry_after_seconds":1}}}`
with `Retry-After: 1`. Sending again after 1 s failed all 4 times; again 2 s later worked in 3. A
second model on the same endpoint (`elevenlabs/scribe-v2`) answered at once. So: two retries,
then one request to the other model. That model takes no Azure phrase list, which is why the
fallback request carries no vocabulary; the cleanup step still has it.

Retries apply to dictation (both the one-at-a-time path and the queue) through an
OpenAI-compatible provider. Gemini, on-device transcription and the audio-file feature are
unchanged. With several transcription providers configured, the walk to the next provider happens
after the retries and the fallback, as before.

- `files/benchmark_log.jsonl`: the transcription stage of a dictation that took more than one
  request has an `attempts` array (kind `FIRST`/`RETRY`/`FALLBACK`, model, HTTP status, latency,
  wait), and its `model` is the one that answered.
- `files/transcription_failures.jsonl`: one line per failed request with the model, the HTTP
  status and the response body (first 2000 characters). No audio, no transcript. Settings →
  Data & logs → "Share transcription failures log" sends it to another app.
- logcat, tag `PhoneWhisper`: `Transcription request failed: ...` per failed request.

### Pause near apps

The original project's exclusion list only keeps Ramblr quiet in an app; the service stays
enabled, and a banking app that looks at the enabled accessibility services still sees it. This
is the "Turn Ramblr off" switch done automatically:

1. The service listens for two things, only while the list has entries:
   - a tap in the home app on something named like a listed app: its icon, or its card in the
     recent apps. This arrives before the launch begins, so the service is off before the app's
     process exists. Measured on a Galaxy S23 Ultra: off 0 to 10 ms before the system starts the
     launch, about 40 ms before the app's process starts.
   - a window of a listed app coming to the front, for every other way in (a notification, a
     link from another app). This arrives with the app's first window, 230 to 380 ms after the
     launch: too late for an app that reads the enabled services while it starts. One banking
     app warned on the first open and not on the second, before the tap was handled.
2. Either way it first starts a small foreground service, the watcher, then disables itself.
3. The watcher shows a "Ramblr paused" notification and reads the system's usage events to see
   which app is in front. Once the user has been out of the listed apps for 1.5 s, it puts the
   service back in `enabled_accessibility_services` and stops (about 2.5 s after leaving). A
   permission or biometric prompt over the app, another listed app, or the screen going off
   while the app is in front does not count as leaving. A tap that turns out not to launch the
   app (nothing listed in front within 8 s) ends the pause. Tapping the notification turns
   Ramblr back on at once.

It needs two things an app cannot give itself, granted once from a computer:

```sh
adb shell pm grant $P android.permission.WRITE_SECURE_SETTINGS   # lets Ramblr switch its own service back on
adb shell appops set $P GET_USAGE_STATS allow                    # "usage access": lets the watcher see the app in front
```

Without the first, Ramblr never pauses (it could not come back). Without the second, it pauses
only on the window event and stays off until the notification is tapped. Run the second one again after the first
install of a build that declares the permission. A pause interrupted by an app update or a
restart is undone when the app next starts.

To know the name the launcher shows for each listed app, and which app the launcher is, the
manifest declares two `<queries>` (launchable apps and home apps). The original project avoids
package visibility altogether; this fork takes that much.

What it cannot promise: an app opened from a notification or a link still gets the late
signal, so one that reads the enabled services while it starts may see Ramblr that once
(closing and opening it again works, since Ramblr is off by then). And an app that objects to
Ramblr merely being installed is not helped by this at all.

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
    --pref ring_visibility_mode=KEYBOARD \
    --pref self_update_notify_enabled=false
```

The last one matters for a fork build: the `github` flavor otherwise announces the original
project's releases, which cannot install over a build signed with another key and do not have
the fork's changes.

It maps `[asr-sidecar]` to the transcription provider, `transcription_retries` and
`transcription_fallback_model` to its retries and fallback model, `[llm]` and `llm_instruction`
to the cleanup provider and style (with whisrs' own vocabulary sentence), `vocabulary`,
`[replacements]`, `language`, and `[input] queue` to the dictation queue.
`--pause-near-app PACKAGE` (repeatable) fills the "pause near apps" list. Keep the exact command
of a phone in `personal/regenerate.sh` (gitignored), so the next rebuild does not lose its flags. `--pref KEY=VALUE` sets
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
- `DEBUG_TRANSCRIPTION_FAULTS --es statuses 429,429`: the next transcription requests fail with
  those HTTP statuses without being sent, one per request. `429,429` exercises two retries and
  then the real provider; `429,429,429` (with two retries configured) the real fallback model.
- `DEBUG_PAUSE_NEAR_APPS --es packages a.b,c.d`: replaces the "pause near apps" list, to test the
  pause with a harmless app (a calculator) instead of a bank. Put the real list back afterwards.

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
`CleanupTimeout.kt`, `SnippetsBeforeCleanupToggle.kt`, `TranscriptionRetry.kt`, `PauseNearApps.kt`,
`PauseWatchService.kt`, `RamblrApp.kt`) and touches the original
ones in few places: `TranscriberClient`, `PostProcessor`, `ProviderChain*`, `CleanupWaterfall*`,
`DictationRuntime` (queue hand-off in `transcribeApi`, `dictationStylePrompt`), the accessibility
service's runtime listener, and two settings screens.
