package com.trevornk.ramblr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Debug-only test seam for on-device verification of insertion behaviour without speaking.
 * Lives in app/src/debug (see the manifest overlay), so it is compiled into debug builds only and
 * never reaches a release APK. The receiver requires `android.permission.DUMP`, which only the
 * shell (adb) and system hold, so no installed app can drive it -- debug builds are daily-driven,
 * and an open receiver would let any app type into the focused field or log on-screen text.
 *
 * Deliver a canned transcript through the same path a finished dictation takes
 * ([DictationRuntime.finalizeForDelivery] then `RuntimeListener.deliverText`):
 *
 *     adb shell am broadcast -n com.trevornk.ramblr/.DebugDeliverReceiver \
 *         -a com.trevornk.ramblr.DEBUG_DELIVER --es text "hello world"
 *
 * Feed a streaming (live-preview) partial through the real [WhisperAccessibilityService]
 * partial-injection path without a live recording, then finish with DEBUG_DELIVER (the real final
 * path) or abandon with DEBUG_PARTIAL_CANCEL (the real teardown path, no final text):
 *
 *     adb shell am broadcast -n com.trevornk.ramblr/.DebugDeliverReceiver \
 *         -a com.trevornk.ramblr.DEBUG_PARTIAL --es text "hello wor"
 *     adb shell am broadcast -n com.trevornk.ramblr/.DebugDeliverReceiver \
 *         -a com.trevornk.ramblr.DEBUG_PARTIAL_CANCEL
 *
 * Feed a Cloud Live (Gemini) interim through the live-preview field path exactly as the service's
 * `onCloudLiveInterim` does (display as received, no smartCapitalize). `--ez normalize true` first runs
 * it through the same [CloudInterimNormalizer] DictationRuntime applies per attempt; `--ez reset true`
 * starts a fresh attempt's normalizer:
 *
 *     adb shell am broadcast -n com.trevornk.ramblr/.DebugDeliverReceiver \
 *         -a com.trevornk.ramblr.DEBUG_CLOUD_INTERIM --es text "send areport to" --ez normalize true
 *
 * Log the editable nodes of the active window (class, focus, selection, hint flags, text length,
 * first 40 chars of text -- up to 4000 with `--ez full true`) under tag PhoneWhisper:
 *
 *     adb shell am broadcast -n com.trevornk.ramblr/.DebugDeliverReceiver -a com.trevornk.ramblr.DEBUG_DUMP
 *
 * Play an audio file of the app's own files directory through the media stream, so a real
 * dictation (microphone, upload, cleanup, insertion) can be tested with nobody speaking: start a
 * recording on the ring, play the clip from the phone's speaker, stop the recording.
 * DEBUG_PLAY_STOP ends it early.
 *
 *     adb push clip.wav /data/local/tmp/ && adb shell run-as com.trevornk.ramblr cp /data/local/tmp/clip.wav files/
 *     adb shell am broadcast -n com.trevornk.ramblr/.DebugDeliverReceiver \
 *         -a com.trevornk.ramblr.DEBUG_PLAY --es file clip.wav
 *
 * Make the next dictation transcribe a prepared clip instead of what the microphone hears: a
 * 16 kHz mono 16-bit WAV in the app's files directory replaces that one recording's audio after
 * the stop tap (see `DictationRuntime.debugPcmOverride`). Everything after the microphone is the
 * real path, and nothing has to be played aloud.
 *
 *     adb shell am broadcast -n com.trevornk.ramblr/.DebugDeliverReceiver \
 *         -a com.trevornk.ramblr.DEBUG_NEXT_AUDIO --es file clip.wav
 *
 * Make the next transcription requests fail without being sent, one HTTP status per request, to
 * drive the retry and fallback path (`TranscriptionRetry`) with no failing server: "429,429"
 * fails the first two requests and lets the third reach the provider. An empty list clears it.
 *
 *     adb shell am broadcast -n com.trevornk.ramblr/.DebugDeliverReceiver \
 *         -a com.trevornk.ramblr.DEBUG_TRANSCRIPTION_FAULTS --es statuses 429,429
 *
 * Replace the "pause near apps" list (`PauseNearAppsStore`), to test the pause with a harmless app
 * instead of a bank; `--ez log true` only logs the list as it is:
 *
 *     adb shell am broadcast -n com.trevornk.ramblr/.DebugDeliverReceiver \
 *         -a com.trevornk.ramblr.DEBUG_PAUSE_NEAR_APPS --es packages com.example.calculator
 */
class DebugDeliverReceiver : BroadcastReceiver() {
    private companion object {
        var cloudNormalizer = CloudInterimNormalizer()
        var dumpFull = false
        var player: android.media.MediaPlayer? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val service = WhisperAccessibilityService.instance
        if (service == null) {
            Log.w("PhoneWhisper", "DebugDeliverReceiver: accessibility service not running")
            return
        }
        when (intent.action) {
            "com.trevornk.ramblr.DEBUG_DELIVER" -> {
                val text = intent.getStringExtra("text") ?: return
                service.runtimeListener.deliverText(
                    service.runtime.finalizeForDelivery(text),
                    rawText = null,
                    paidFallbackGroup = null,
                    cleanupError = null,
                    feedbackDurationMs = 2000,
                )
            }
            "com.trevornk.ramblr.DEBUG_PARTIAL" -> {
                val text = intent.getStringExtra("text") ?: return
                service.maybeInjectPartial(text, requireRecording = false)
            }
            "com.trevornk.ramblr.DEBUG_CLOUD_INTERIM" -> {
                if (intent.getBooleanExtra("reset", false)) cloudNormalizer = CloudInterimNormalizer()
                val text = intent.getStringExtra("text") ?: return
                val shown = if (intent.getBooleanExtra("normalize", false)) cloudNormalizer.normalize(text) else text
                service.maybeInjectPartial(shown, requireRecording = false, alreadyCased = true)
            }
            "com.trevornk.ramblr.DEBUG_PARTIAL_CANCEL" -> service.runtimeListener.onStreamingTeardown()
            "com.trevornk.ramblr.DEBUG_PLAY" -> {
                val name = intent.getStringExtra("file") ?: return
                val file = java.io.File(context.filesDir, java.io.File(name).name)
                player?.release()
                player = try {
                    android.media.MediaPlayer().apply {
                        setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(file.absolutePath)
                        setOnCompletionListener {
                            Log.i("PhoneWhisper", "DEBUG_PLAY finished ${file.name}")
                            it.release()
                            if (player === it) player = null
                        }
                        prepare()
                        start()
                    }
                } catch (e: Exception) {
                    Log.w("PhoneWhisper", "DEBUG_PLAY could not play ${file.name}", e)
                    null
                }
                if (player != null) Log.i("PhoneWhisper", "DEBUG_PLAY started ${file.name}")
            }
            "com.trevornk.ramblr.DEBUG_NEXT_AUDIO" -> {
                val name = intent.getStringExtra("file") ?: return
                val wav = java.io.File(context.filesDir, java.io.File(name).name)
                try {
                    val bytes = wav.readBytes()
                    // PCM starts after the "data" chunk header; 44 for a plain WAV.
                    val marker = "data".toByteArray()
                    val at = (12 until bytes.size - 8).firstOrNull { i -> marker.indices.all { bytes[i + it] == marker[it] } }
                    val start = if (at != null) at + 8 else 44
                    val pcm = java.io.File(context.cacheDir, "debug_next_audio_${System.currentTimeMillis()}.pcm")
                    pcm.writeBytes(bytes.copyOfRange(start, bytes.size))
                    service.runtime.debugPcmOverride = pcm
                    Log.i("PhoneWhisper", "DEBUG_NEXT_AUDIO armed ${wav.name} (${pcm.length()} PCM bytes)")
                } catch (e: Exception) {
                    Log.w("PhoneWhisper", "DEBUG_NEXT_AUDIO could not read ${wav.name}", e)
                }
            }
            "com.trevornk.ramblr.DEBUG_TRANSCRIPTION_FAULTS" -> {
                val statuses = intent.getStringExtra("statuses").orEmpty()
                    .split(',').mapNotNull { it.trim().toIntOrNull() }
                TranscriberClient.debugFaults =
                    if (statuses.isEmpty()) null else java.util.concurrent.ConcurrentLinkedQueue(statuses)
                Log.i("PhoneWhisper", "DEBUG_TRANSCRIPTION_FAULTS armed $statuses")
            }
            "com.trevornk.ramblr.DEBUG_PAUSE_NEAR_APPS" -> {
                if (!intent.getBooleanExtra("log", false)) {
                    val packages = intent.getStringExtra("packages").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    PauseNearAppsStore.setPackages(context, packages.toSet())
                }
                Log.i("PhoneWhisper", "DEBUG_PAUSE_NEAR_APPS list=${PauseNearAppsStore.packages(context).sorted()}")
            }
            "com.trevornk.ramblr.DEBUG_PLAY_STOP" -> {
                player?.release()
                player = null
            }
            "com.trevornk.ramblr.DEBUG_DUMP" -> {
                val root = service.rootInActiveWindow ?: return
                dumpFull = intent.getBooleanExtra("full", false)
                walk(root)
            }
        }
    }

    private fun walk(node: AccessibilityNodeInfo) {
        if (node.isEditable || node.className?.contains("EditText") == true) {
            Log.i(
                "PhoneWhisper",
                "DUMP class=${node.className} focused=${node.isFocused} editable=${node.isEditable} " +
                    "showingHint=${node.isShowingHintText} hint=${node.hintText} " +
                    "sel=${node.textSelectionStart}/${node.textSelectionEnd} len=${node.text?.length} " +
                    "text=${node.text?.toString()?.take(if (dumpFull) 4000 else 40)?.replace("\n", "\\n")}",
            )
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
    }
}
