package com.trevornk.ramblr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * What to do when a cloud transcription request fails for a reason that may pass: send it again
 * [retries] times, then ask [fallbackModel] once. The default does neither, which is the original
 * behavior: one request, and its failure is the dictation's failure.
 *
 * It exists because a provider's rate limit is not always the user's doing. Microsoft
 * MAI-Transcribe 2 on OpenRouter answered HTTP 429 to about one isolated request in eight
 * (measured 2026-11-17, 30 requests 6 s apart); the same request usually went through 3 s later,
 * and another model on the same endpoint answered at once.
 */
data class TranscriptionRetryPolicy(val retries: Int = 0, val fallbackModel: String = "") {
    companion object {
        val NONE = TranscriptionRetryPolicy()
    }
}

/** Where [TranscriptionRetryPolicy] is stored. Nothing stored means [TranscriptionRetryPolicy.NONE]. */
object TranscriptionRetrySettings {
    private const val PREFS_NAME = "ramblr"
    const val KEY_RETRIES = "transcription_retries"
    const val KEY_FALLBACK_MODEL = "transcription_fallback_model"
    const val MAX_RETRIES = 5

    fun policy(prefs: SharedPreferences): TranscriptionRetryPolicy = TranscriptionRetryPolicy(
        retries = retries(prefs),
        fallbackModel = fallbackModel(prefs),
    )

    fun policy(context: Context): TranscriptionRetryPolicy = policy(prefs(context))

    // Read as a string: the one type every writer (settings dialog, personal defaults) and the
    // tests' fake preferences agree on.
    fun retries(prefs: SharedPreferences): Int =
        (prefs.getString(KEY_RETRIES, null)?.trim()?.toIntOrNull() ?: 0).coerceIn(0, MAX_RETRIES)

    fun fallbackModel(prefs: SharedPreferences): String = prefs.getString(KEY_FALLBACK_MODEL, null)?.trim().orEmpty()

    fun set(prefs: SharedPreferences, retries: Int, fallbackModel: String) {
        prefs.edit()
            .putString(KEY_RETRIES, retries.coerceIn(0, MAX_RETRIES).toString())
            .putString(KEY_FALLBACK_MODEL, fallbackModel.trim())
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * One request of a transcription: which model was asked, in which role, and how it went.
 * [waitedMs] is the wait before it. [httpStatus], [error] and [errorBody] are null when it worked
 * ([succeeded]).
 */
data class TranscriptionAttempt(
    val kind: Kind,
    val model: String,
    val succeeded: Boolean,
    val httpStatus: Int?,
    val error: String?,
    val errorBody: String?,
    val latencyMs: Long,
    val waitedMs: Long,
) {
    enum class Kind { FIRST, RETRY, FALLBACK }
}

object TranscriptionRetry {
    /** The longest wait before a retry, whatever the server asks for: someone is waiting for the text. */
    const val MAX_WAIT_MS = 5_000L
    private const val FIRST_WAIT_MS = 1_000L

    /** Rate limited, overloaded, or a gateway that got no answer from the server behind it. */
    fun isTransientStatus(status: Int): Boolean =
        status == 429 || status in 502..504 || status in 520..524

    /**
     * An account out of credit or with a billing problem. Final whatever the status: providers
     * report it as 402, but also as 429 ("insufficient_quota") or 403, and sending the request
     * again, or to another model on the same key, only repeats it.
     */
    fun isBillingError(status: Int?, error: String?, body: String?): Boolean {
        if (status == 402) return true
        val text = listOfNotNull(error, errorEnvelopeText(body)).joinToString(" ").lowercase()
        return BILLING_MARKERS.any { it in text }
    }

    private val BILLING_MARKERS = listOf(
        "credit", "billing", "payment", "insufficient_quota", "insufficient quota",
        "exceeded your current quota", "quota exhausted", "out of funds", "insufficient funds", "insufficient balance",
    )

    /** `error.message`, `error.code` and `error.type` of a provider error body; not its metadata. */
    private fun errorEnvelopeText(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val error = JSONObject(body).optJSONObject("error") ?: return null
            listOf("message", "code", "type").joinToString(" ") { error.optString(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The wait before retry number [attempt] (0 for the first) of a request that ended in
     * [result], or null when the failure is not transient and the request would fail the same way
     * again: a refused key (401/403), a bad request (400/422), no credit, an answer the app could
     * not use, a call the app cancelled. Transient is [isTransientStatus] or no response at all.
     *
     * The wait is 1 s, doubled per attempt, or what the server asks for when that is longer, and
     * never over [MAX_WAIT_MS].
     */
    fun retryWaitMs(result: TranscriberClient.Result, attempt: Int): Long? {
        if (result.text != null || result.cancelled) return null
        if (isBillingError(result.httpStatus, result.error, result.errorBody)) return null
        val status = result.httpStatus
        val transient = if (status != null) isTransientStatus(status) else result.noResponse
        if (!transient) return null
        val backoff = FIRST_WAIT_MS shl attempt.coerceIn(0, 3)
        return maxOf(backoff, result.retryAfterMs ?: 0L).coerceAtMost(MAX_WAIT_MS)
    }

    /**
     * The delay a response's headers ask for: `retry-after-ms` (OpenAI), else `Retry-After` as
     * seconds (fractions allowed) or an HTTP date, relative to [nowMs]. Null when neither is
     * there or readable; a date already past is zero.
     */
    fun retryAfterMs(retryAfterMsHeader: String?, retryAfterHeader: String?, nowMs: Long): Long? {
        retryAfterMsHeader?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { return it.toLong() }
        val value = retryAfterHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toDoubleOrNull()?.let { seconds ->
            return if (seconds.isFinite() && seconds >= 0) (seconds * 1000).toLong() else null
        }
        return try {
            val at = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            (at.toInstant().toEpochMilli() - nowMs).coerceAtLeast(0L)
        } catch (e: Exception) {
            null
        }
    }

    /** OpenRouter's `error.metadata.retry_after_seconds` in a rate-limit body, in milliseconds. */
    fun bodyRetryAfterMs(body: String?): Long? {
        if (body.isNullOrBlank()) return null
        return try {
            val seconds = JSONObject(body).optJSONObject("error")?.optJSONObject("metadata")
                ?.optDouble("retry_after_seconds", Double.NaN) ?: return null
            if (seconds.isFinite() && seconds >= 0) (seconds * 1000).toLong() else null
        } catch (e: Exception) {
            null
        }
    }

    /** [policy]'s fallback model for a transcription by [model]: null when unset or the same model. */
    fun fallbackFor(model: String, policy: TranscriptionRetryPolicy): String? =
        policy.fallbackModel.trim().takeIf { it.isNotEmpty() && it != model }

    /**
     * Runs one transcription under [policy]. [send] makes one request for a model (its second
     * argument says whether that is the fallback) and reports the result; [schedule] runs a block
     * after a delay. Both are seams: production passes [TranscriberClient.transcribe] and a
     * scheduler thread, tests pass fakes.
     *
     *  - A result with text, even empty ("no speech"), ends the run.
     *  - A failure that is not transient ([retryWaitMs] is null) ends it too, and is never taken
     *    to the fallback model: a refused or empty key is the same key there.
     *  - A transient failure is sent again after its wait, up to [policy]'s retries.
     *  - When those run out, the fallback model is asked once, with no wait and no retry.
     *
     * [shouldContinue] false before a retry or the fallback ends the run with the last failure.
     * [callback] runs once; its result carries the model that answered and every attempt.
     */
    fun run(
        model: String,
        policy: TranscriptionRetryPolicy,
        send: (model: String, isFallback: Boolean, done: (TranscriberClient.Result) -> Unit) -> Unit,
        schedule: (delayMs: Long, block: () -> Unit) -> Unit,
        shouldContinue: () -> Boolean = { true },
        onFailedAttempt: (TranscriptionAttempt) -> Unit = {},
        nowMs: () -> Long = { System.currentTimeMillis() },
        callback: (TranscriberClient.Result) -> Unit,
    ) {
        val attempts = ArrayList<TranscriptionAttempt>()

        fun attempt(index: Int, kind: TranscriptionAttempt.Kind, attemptModel: String, waitedMs: Long) {
            val startedAtMs = nowMs()
            send(attemptModel, kind == TranscriptionAttempt.Kind.FALLBACK) { result ->
                val made = TranscriptionAttempt(
                    kind = kind,
                    model = attemptModel,
                    succeeded = result.text != null,
                    httpStatus = result.httpStatus,
                    error = result.error,
                    errorBody = result.errorBody,
                    latencyMs = nowMs() - startedAtMs,
                    waitedMs = waitedMs,
                )
                attempts += made
                val outcome = result.copy(model = attemptModel, attempts = attempts.toList())
                if (made.succeeded) {
                    callback(outcome)
                    return@send
                }
                runCatching { onFailedAttempt(made) }
                val waitMs = if (kind == TranscriptionAttempt.Kind.FALLBACK) null else retryWaitMs(result, index)
                if (waitMs == null || !shouldContinue()) {
                    callback(outcome)
                    return@send
                }
                if (index < policy.retries) {
                    schedule(waitMs) {
                        if (shouldContinue()) attempt(index + 1, TranscriptionAttempt.Kind.RETRY, attemptModel, waitMs)
                        else callback(outcome)
                    }
                    return@send
                }
                val fallback = fallbackFor(model, policy)
                if (fallback == null) callback(outcome)
                else attempt(index + 1, TranscriptionAttempt.Kind.FALLBACK, fallback, 0L)
            }
        }

        attempt(0, TranscriptionAttempt.Kind.FIRST, model, 0L)
    }

    /** The attempts of a transcription for the benchmark log: one object each, no response bodies. */
    fun attemptsJson(attempts: List<TranscriptionAttempt>): org.json.JSONArray = org.json.JSONArray().apply {
        attempts.forEach { attempt ->
            put(
                JSONObject()
                    .put("kind", attempt.kind.name)
                    .put("model", attempt.model)
                    .put("success", attempt.succeeded)
                    .put("httpStatus", attempt.httpStatus ?: JSONObject.NULL)
                    .put("latencyMs", attempt.latencyMs)
                    .put("waitedMs", attempt.waitedMs)
                    .put("error", sanitizeError(attempt.error) ?: JSONObject.NULL)
            )
        }
    }
}

/**
 * Every transcription request that failed, one JSON line each in `files/transcription_failures.jsonl`:
 * when, which dictation ([BenchmarkLogger]'s correlation id), the attempt's role and model, the
 * HTTP status and the response body. The benchmark log says that a dictation needed a retry or
 * the fallback model; this file says what the provider answered. No audio and no transcript, so
 * it is written whether or not the quality log is on. Rotated like the benchmark log.
 */
object TranscriptionFailureLog {
    private const val TAG = "PhoneWhisper"
    private const val FILE_NAME = "transcription_failures.jsonl"
    const val ROTATE_AT_BYTES = 512 * 1024L
    const val KEEP_BYTES_AFTER_ROTATION = 256 * 1024L

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TranscriptionFailureLog-io").apply { isDaemon = true }
    }

    fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun buildLine(timestamp: Long, correlationId: String, attempt: TranscriptionAttempt): String = JSONObject()
        .put("timestamp", timestamp)
        .put("correlationId", correlationId)
        .put("kind", attempt.kind.name)
        .put("model", attempt.model)
        .put("httpStatus", attempt.httpStatus ?: JSONObject.NULL)
        .put("error", attempt.error ?: JSONObject.NULL)
        .put("body", attempt.errorBody ?: JSONObject.NULL)
        .put("latencyMs", attempt.latencyMs)
        .put("waitedMs", attempt.waitedMs)
        .toString()

    /** Never throws: a log that cannot be written must not cost the dictation its retry. */
    fun log(context: Context, correlationId: String, attempt: TranscriptionAttempt) {
        runCatching {
            Log.w(
                TAG,
                "Transcription request failed: ${attempt.kind} model=${attempt.model} status=${attempt.httpStatus} " +
                    "after ${attempt.latencyMs}ms: ${sanitizeError(attempt.error)}",
            )
            val line = buildLine(System.currentTimeMillis(), correlationId, attempt)
            val file = logFile(context)
            ioExecutor.execute {
                runCatching {
                    rotateIfNeeded(file)
                    file.appendText(line + "\n")
                }
            }
        }
    }

    fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < ROTATE_AT_BYTES) return
        val bytes = file.readBytes()
        val tail = String(bytes.copyOfRange((bytes.size - KEEP_BYTES_AFTER_ROTATION).coerceAtLeast(0).toInt(), bytes.size), Charsets.UTF_8)
        val firstNewline = tail.indexOf('\n')
        file.writeText(if (firstNewline >= 0 && firstNewline < tail.length - 1) tail.substring(firstNewline + 1) else tail)
    }
}
