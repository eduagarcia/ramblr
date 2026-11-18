package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Lets the next dictation start while the previous one is still being transcribed and cleaned.
 *
 * Without it the ring stays busy from the stop tap until the text is inserted, and a tap in that
 * window does nothing. With it a cloud dictation is handed to [DictationQueue] at the stop tap and
 * the ring goes back to idle at once; the texts are inserted in the order the dictations ended,
 * each where the cursor is when its turn comes.
 *
 * Off by default. Only the floating icon queues (see [RuntimeListener.supportsQueuedDelivery]),
 * and only when the dictation would go to OpenAI-compatible cloud transcription with no
 * translation and no live preview; anything else takes the original path.
 */
object DictationQueueToggle {
    private const val PREFS_NAME = "ramblr"
    const val KEY = "dictation_queue_enabled"
    private const val DEFAULT = false

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, DEFAULT)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    fun isEnabled(context: Context): Boolean = isEnabled(prefs(context))

    fun setEnabled(context: Context, enabled: Boolean) = setEnabled(prefs(context), enabled)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Results that must come out in the order their work was started, whatever order it finishes in.
 * [open] reserves the next place, [resolve] fills a place, and [pollReady] hands out the oldest
 * result only once it is there: a later result waits behind an earlier one that is still pending.
 * Not thread-safe; [DictationQueue] uses it on the main thread only.
 */
internal class DeliveryOrder<T : Any> {
    class Slot<T : Any> internal constructor() {
        internal var value: T? = null
    }

    private val slots = ArrayDeque<Slot<T>>()

    val size: Int get() = slots.size

    fun open(): Slot<T> = Slot<T>().also { slots.addLast(it) }

    /** False when [slot] already has a result or is no longer waiting (delivered or cleared). */
    fun resolve(slot: Slot<T>, value: T): Boolean {
        if (slot.value != null || slot !in slots) return false
        slot.value = value
        return true
    }

    /** The oldest result, removed, or null while the oldest place is still pending (or none is). */
    fun pollReady(): T? {
        val value = slots.firstOrNull()?.value ?: return null
        slots.removeFirst()
        return value
    }

    fun clear() = slots.clear()
}

/**
 * The detached half of a queued dictation: transcription, cleanup and in-order delivery, with no
 * tie to [DictationRuntime]'s single recording session, so a new recording can run meanwhile.
 *
 * It mirrors what the runtime does for a cloud dictation (`transcribeApi` then
 * `handleTranscriptionResult`), minus what [accepts] rules out: on-device and Gemini
 * transcription, translation, live preview, preview-before-insert. A failed or slow cleanup
 * delivers the transcript, as there.
 *
 * Threading: [add], [drain] and [shutdown] run on the main thread; network callbacks arrive on
 * OkHttp threads and hop back through [handler].
 */
internal class DictationQueue(
    private val context: Context,
    private val listener: RuntimeListener,
    private val handler: Handler,
    private val vocabularyTerms: () -> List<String>,
    /** The cleanup prompt for the current style, with [vocabularyTerms] interpolated. */
    private val cleanupPrompt: (List<String>) -> String,
    private val finalizeForDelivery: (String) -> String,
    private val userMessage: (String) -> Unit,
    /** Whether "Translate to" is set: a translated dictation is not queued. The runtime reads the
     *  setting, so this class stays out of the translation plumbing. */
    private val translationRequested: () -> Boolean,
) {
    private sealed interface Outcome {
        data class Text(
            val text: String,
            val rawText: String?,
            val paidFallbackGroup: CleanupStepGroup?,
            val cleanupError: String?,
            val feedbackDurationMs: Long,
        ) : Outcome

        /** Nothing to insert: no speech, every provider failed, or the job timed out. */
        data class Message(val message: String) : Outcome
    }

    private class Job(val pcm: File, val correlationId: String) {
        val call = InFlightCall()
        lateinit var slot: DeliveryOrder.Slot<Outcome>
        var timeout: Runnable? = null
    }

    private val order = DeliveryOrder<Outcome>()
    private val jobs = HashSet<Job>()
    private var gapPending = false
    private var closed = false

    /** Dictations handed over and not yet delivered. Main thread. */
    val pendingCount: Int get() = order.size

    /**
     * Whether a dictation that would transcribe through [candidates] can be queued. Read on the
     * runtime's transcription thread, before any network call.
     */
    fun accepts(candidates: List<ProviderChainEntry>): Boolean {
        if (!DictationQueueToggle.isEnabled(context) || !listener.supportsQueuedDelivery()) return false
        // A live preview types into the field while recording; a queued insert would collide with it.
        if (prefs().getBoolean("streaming_preview_enabled", false)) return false
        if (translationRequested()) return false
        return candidates.isNotEmpty() && candidates.all { it.kind == ProviderKind.OPENAI } &&
            candidates.any { ProviderCredentialStore.getOrLegacy(context, it).isNotBlank() }
    }

    /** Takes over [pcm] (deleted when done) as the newest dictation. Main thread. */
    fun add(pcm: File, correlationId: String, candidates: List<ProviderChainEntry>) {
        if (closed) {
            pcm.delete()
            return
        }
        val job = Job(pcm, correlationId)
        job.slot = order.open()
        jobs += job
        val timeout = Runnable {
            job.call.cancel()
            finishOnMain(job, Outcome.Message("Transcription timed out"))
        }
        job.timeout = timeout
        handler.postDelayed(timeout, JOB_TIMEOUT_MS)
        listener.onQueuedDictationsChanged(order.size)
        Log.i(TAG, "Queued dictation ${job.correlationId} (${order.size} pending)")
        thread { transcribe(job, candidates, 0, null) }
    }

    /** Drops every pending dictation and aborts its calls. Main thread. */
    fun shutdown() {
        closed = true
        jobs.forEach { job ->
            job.timeout?.let(handler::removeCallbacks)
            job.call.cancel()
            job.pcm.delete()
        }
        jobs.clear()
        order.clear()
    }

    private fun transcribe(job: Job, candidates: List<ProviderChainEntry>, index: Int, lastError: String?) {
        if (index >= candidates.size) {
            finish(job, Outcome.Message("Error: ${lastError ?: "transcription failed"}"))
            return
        }
        val entry = candidates[index]
        val apiKey = ProviderCredentialStore.getOrLegacy(context, entry)
        if (apiKey.isBlank()) {
            transcribe(job, candidates, index + 1, lastError ?: "Set API key in Ramblr app")
            return
        }
        val model = entry.transcriptionModel?.ifBlank { null } ?: TranscriberClient.DEFAULT_MODEL
        val startedAtMs = System.currentTimeMillis()
        TranscriberClient.transcribeWithRetries(
            job.pcm, apiKey, job.call,
            baseUrl = entry.baseUrlOverride ?: PostProcessor.DEFAULT_BASE_URL,
            model = model,
            vocabularyTerms = vocabularyTerms(),
            language = DictationLanguage.languageOrNull(context),
            policy = TranscriptionRetrySettings.policy(context),
            // The job's timeout and shutdown both cancel its call: no retry after that.
            shouldContinue = { !job.call.isCancelled },
            onFailedAttempt = { TranscriptionFailureLog.log(context, job.correlationId, it) },
        ) { result ->
            val servedModel = result.model ?: model
            val roundTripMs = System.currentTimeMillis() - startedAtMs
            val text = result.text
            val success = !text.isNullOrBlank()
            Log.i(TAG, "Queued transcription ${job.correlationId} took ${roundTripMs}ms success=$success model=$servedModel requests=${result.attempts.size}")
            BenchmarkLogger.log(
                context = context,
                correlationId = job.correlationId,
                transcription = BenchmarkStage(
                    provider = entry.kind.name,
                    model = servedModel,
                    latencyMs = roundTripMs,
                    success = success,
                    compressedUpload = false,
                    error = sanitizeError(result.error),
                    attempts = result.attempts,
                ),
                rawTextLength = text?.length,
            )
            if (listener.allowsTranscriptRetention()) QualityLogger.log(
                context = context,
                correlationId = job.correlationId,
                transcription = QualityStage(provider = entry.kind.name, model = servedModel),
                rawText = text,
            )
            when {
                success -> clean(job, text!!)
                // An empty transcript is "no speech": the next provider would hear the same silence.
                isNoSpeechTranscript(text, result.error) -> finish(job, Outcome.Message(NO_SPEECH_MESSAGE))
                else -> thread { transcribe(job, candidates, index + 1, result.error ?: "empty transcript") }
            }
        }
    }

    private fun clean(job: Job, transcript: String) {
        job.pcm.delete()
        val text = SnippetsBeforeCleanupToggle.apply(context, transcript)
        fun raw(cleanupError: String? = null) = Outcome.Text(
            text, rawText = null, paidFallbackGroup = null, cleanupError = cleanupError,
            feedbackDurationMs = if (cleanupError == null) 2000 else 4000,
        )
        if (isJunkTranscript(text) || !PostProcessingToggle.shouldRunCleanup(PostProcessingToggle.isEnabled(context))) {
            finish(job, raw())
            return
        }
        val chain = ProviderChainRuntime.effectiveChainForCleanup(
            ProviderChainStore.load(context), CloudFeatureToggle.cleanupEnabled(context), DictationModeToggle.allowLocalFallback(context),
        )
        val waterfall = ProviderChainRuntime.cleanupWaterfallFor(chain)
        if (waterfall.steps.isEmpty()) {
            finish(job, raw())
            return
        }
        val vocabulary = vocabularyTerms()
        val prompt = cleanupPrompt(vocabulary)
        // Its own cursor: queued cleanups overlap, and the runtime's cursor belongs to its one session.
        val cursor = CleanupWaterfallCursor()
        PostProcessor.processProviderChain(
            text = text,
            prompt = prompt,
            chain = chain,
            cursor = cursor,
            cancelHolder = job.call,
            credentialLookup = { kind -> ProviderCredentialStore.getLegacyByKind(context, kind) },
            entryCredentialLookup = { entryId -> ProviderCredentialStore.get(context, entryId) },
            localModelPath = { ModelDownloader.localCleanupModelFile(context, LocalCleanupProvider.selectedModel(context))?.absolutePath },
            localPrompt = LocalCleanupProvider.selectedSystemPrompt(context),
            localVocabulary = vocabulary,
            temperatureCacheContext = context.applicationContext,
            benchmarkContext = context.takeIf { listener.allowsTranscriptRetention() },
            benchmarkCorrelationId = job.correlationId,
            fidelityGuard = PostProcessor.preservesWording(prompt),
            hardCapMs = CleanupTimeout.hardCapMs(context),
        ) { result ->
            val cleaned = result.text
            if (!cleaned.isNullOrBlank()) {
                val step = waterfall.steps.getOrNull(cursor.startIndex(System.currentTimeMillis()))
                step?.let { CleanupStepStatusStore.record(context, it, CleanupStepHealth.SUCCESS) }
                finish(
                    job,
                    Outcome.Text(
                        cleaned, rawText = text, paidFallbackGroup = step?.group?.takeIf { it.isPaidFallback() },
                        cleanupError = null, feedbackDurationMs = 2000,
                    ),
                )
            } else {
                val reason = result.error?.takeIf { it.isNotBlank() } ?: "unknown error"
                Log.w(TAG, "Queued cleanup ${job.correlationId} failed, delivering raw text: $reason")
                finish(job, raw(cleanupError = reason))
            }
        }
    }

    private fun finish(job: Job, outcome: Outcome) {
        handler.post { finishOnMain(job, outcome) }
    }

    private fun finishOnMain(job: Job, outcome: Outcome) {
        job.pcm.delete()
        job.timeout?.let(handler::removeCallbacks)
        job.timeout = null
        if (closed || !jobs.remove(job)) return
        order.resolve(job.slot, outcome)
        drain()
    }

    /** Delivers the oldest finished dictation, then the next one after a short gap. Main thread. */
    private fun drain() {
        if (gapPending || closed) return
        val outcome = order.pollReady() ?: return
        listener.onQueuedDictationsChanged(order.size)
        try {
            when (outcome) {
                is Outcome.Text -> listener.deliverText(
                    finalizeForDelivery(outcome.text),
                    rawText = outcome.rawText,
                    paidFallbackGroup = outcome.paidFallbackGroup,
                    cleanupError = outcome.cleanupError,
                    feedbackDurationMs = outcome.feedbackDurationMs,
                )
                is Outcome.Message -> userMessage(outcome.message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Queued delivery failed", e)
        }
        if (order.size == 0) return
        // The host reads the field again for every insert; give the previous one time to land.
        gapPending = true
        handler.postDelayed({
            gapPending = false
            drain()
        }, DELIVERY_GAP_MS)
    }

    private fun prefs() = context.getSharedPreferences("ramblr", Context.MODE_PRIVATE)

    private companion object {
        const val TAG = "PhoneWhisper"
        const val NO_SPEECH_MESSAGE = "No speech detected"
        const val DELIVERY_GAP_MS = 400L

        /** Backstop for a callback that never arrives, like the runtime's own watchdog. */
        const val JOB_TIMEOUT_MS = 240_000L
    }
}
