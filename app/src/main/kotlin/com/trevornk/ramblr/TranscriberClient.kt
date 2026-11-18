package com.trevornk.ramblr

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.IOException

object TranscriberClient {
    /**
     * [text] is null exactly when the request failed. The rest describes that failure for
     * [TranscriptionRetry] and the logs, and is null/false on success and for results built
     * without a response: [httpStatus] and [retryAfterMs] come from the response, [errorBody] is
     * the body of a non-2xx response (truncated), [noResponse] is a request that got none (network
     * error, timeout), and [cancelled] is a call the app itself aborted. [model] and [attempts]
     * are filled in by [transcribeWithRetries]: the model that produced this result and every
     * request made for it, in order.
     */
    data class Result(
        val text: String?,
        val error: String?,
        val httpStatus: Int? = null,
        val retryAfterMs: Long? = null,
        val errorBody: String? = null,
        val noResponse: Boolean = false,
        val cancelled: Boolean = false,
        val model: String? = null,
        val attempts: List<TranscriptionAttempt> = emptyList(),
    )

    /** Longest stretch of a failed response's body kept in a [Result] and in the failure log. */
    const val MAX_ERROR_BODY_CHARS = 2000

    /**
     * Debug-build test seam (`DebugDeliverReceiver`'s DEBUG_TRANSCRIPTION_FAULTS): HTTP statuses
     * the next [transcribe] calls answer with instead of sending anything, one per call, so the
     * retry and fallback path can be driven on a device without a failing server. Null and unused
     * in a release build.
     */
    @Volatile internal var debugFaults: java.util.concurrent.ConcurrentLinkedQueue<Int>? = null

    private val client = NetworkClients.shared

    // whisper-1 -> gpt-4o-transcribe: real 20-clip benchmark (2026-07-10) showed lower WER
    // (2.86% vs 4.29%) and ~42% faster avg latency (0.81s vs 1.40s) on identical short
    // push-to-talk-style audio, with zero dropped/wrong words on either model including a
    // one-word clip -- the earlier "drops short words" concern did not reproduce.
    // gpt-4o-transcribe -> gpt-transcribe (2026-07-31): OpenAI's successor model on the same
    // /v1/audio/transcriptions endpoint. Not benchmarked with Ramblr's own clip protocol yet;
    // adopted on OpenAI's Real World Audio numbers (8.98% vs 15.21% WER over Whisper) plus
    // Artificial Analysis AA-WER (3.31% vs ~4.0% for gpt-4o-transcribe), at a 25% LOWER price
    // ($0.0045/min vs $0.006/min). `prompt` (used for #114 vocabulary biasing) remains
    // supported; the new model returns plain JSON `text`, which parseResponse already reads.
    const val DEFAULT_MODEL = "gpt-transcribe"

    /** The `/audio/transcriptions` endpoint for [baseUrl], normalized the same way the cleanup path
     *  normalizes its base URL (M5). A blank/malformed base falls back to OpenAI's default. */
    fun transcriptionEndpoint(baseUrl: String): String =
        (PostProcessor.normalizeBaseUrl(baseUrl) ?: PostProcessor.DEFAULT_BASE_URL) + "/audio/transcriptions"

    /**
     * Parses `/audio/transcriptions`' JSON body.
     *
     * The transcript is trimmed (#140): every other transcription parser in the app already trims
     * its model text -- [GeminiTranscriberClient.parseResponse], [LocalTranscriber.transcribe] and
     * [StreamingTranscriber] all do -- and so does every cleanup provider
     * ([PostProcessor.parseResponse], [AnthropicCleanupProvider.parseResponse], and the LOCAL_LLM
     * step per #84). This parser was the sole exception, so leading/trailing whitespace and
     * newlines in the model's transcript were injected verbatim into the user's field, pushing the
     * cursor onto a new line after the dictated text. Cleanup-off (raw injection) makes this
     * user-visible directly; with cleanup on, the cleanup step's own trim used to mask it.
     */
    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        when {
            obj.has("text") -> Result(obj.getString("text").trim(), null)
            obj.has("error") -> Result(null, obj.getJSONObject("error").getString("message"))
            else -> Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    /** MIME type used for the compressed-upload (#109) `.m4a` file part -- AAC-in-MP4-container
     *  audio, the same content-type OkHttp/browsers use for `.m4a`. Mirrors [PcmWavRequestBody]'s
     *  hardcoded `audio/wav` for the uncompressed path. */
    const val COMPRESSED_MEDIA_TYPE = "audio/mp4"

    /** Filename for the compressed-upload (#109) multipart file part. OpenAI's
     *  `/v1/audio/transcriptions` infers the audio format from this extension -- no separate
     *  mime-type form field exists for it -- so this must stay in sync with [COMPRESSED_MEDIA_TYPE]. */
    const val COMPRESSED_FILENAME = "audio.m4a"

    /** Filename for the uncompressed (raw WAV) multipart file part -- unchanged default path. */
    const val WAV_FILENAME = "audio.wav"

    /**
     * Picks the multipart filename + [RequestBody] for the audio upload (#109): [compressedFile]
     * wins whenever it's non-null (toggle was on and [AacEncoderSession] produced usable audio),
     * falling back to streaming [pcmFile] as WAV exactly as before otherwise. Pulled out as its
     * own pure-ish decision point so the null-check that drives compressed-vs-raw upload format
     * is easy to read (and to unit test) independent of the network/multipart plumbing around it.
     */
    fun uploadPart(pcmFile: File, compressedFile: File?, model: String = DEFAULT_MODEL): Pair<String, RequestBody> =
        if (compressedFile != null && acceptsCompressedUpload(model)) {
            COMPRESSED_FILENAME to compressedFile.asRequestBody(COMPRESSED_MEDIA_TYPE.toMediaType())
        } else {
            WAV_FILENAME to PcmWavRequestBody(pcmFile)
        }

    /**
     * Whether [model] is a Microsoft MAI-Transcribe model served through OpenRouter
     * (`microsoft/mai-transcribe-2`). Two things differ from the OpenAI-family models:
     *  - it refuses the `.m4a` (AAC) compressed upload with HTTP 400 `invalid_audio ... Format not
     *    recognised`, and takes WAV or Ogg/Opus (see [acceptsCompressedUpload]);
     *  - OpenRouter drops `prompt`/`keywords[]` for it, but forwards a `provider` form field to
     *    Azure, whose `phraseList` is the vocabulary hint (see [vocabularyFormParts]).
     * Both measured against the live API on 2026-11-17.
     */
    fun isMaiTranscribe(model: String): Boolean =
        model.trim().lowercase().startsWith("microsoft/mai-transcribe")

    /** False for models that refuse the compressed `.m4a` upload (#109); those always get WAV. */
    fun acceptsCompressedUpload(model: String): Boolean = !isMaiTranscribe(model)

    /**
     * The `provider` form field that carries [terms] to MAI-Transcribe as an Azure phrase list:
     * `{"options":{"azure":{"phraseList":{"phrases":[...]}}}}`, sent as text.
     */
    fun maiPhraseListProvider(terms: List<String>): String =
        JSONObject().put(
            "options",
            JSONObject().put(
                "azure",
                JSONObject().put("phraseList", JSONObject().put("phrases", org.json.JSONArray(terms))),
            ),
        ).toString()

    /** Whether [model] supports the structured `keywords` array on `/v1/audio/transcriptions`
     *  (#114 follow-up, 2026-07-31): OpenAI documents `keywords[]` for the gpt-transcribe family
     *  as literal terms that may appear in the audio -- exactly what Ramblr's user-taught
     *  vocabulary is. Older models (whisper-1, gpt-4o-transcribe) don't document it, and an
     *  unrecognized form field risks a 400 from OpenAI or an OpenAI-compatible proxy, so those
     *  keep the comma-joined `prompt` fallback. Prefix match so dated snapshots
     *  (gpt-transcribe-2026-07-xx) qualify too. */
    fun supportsKeywords(model: String): Boolean = model.startsWith("gpt-transcribe")

    /**
     * The multipart form parts that carry [terms] into the transcription request (#114): one
     * `keywords[]` part per term on models that [supportsKeywords] (structured hints beat one
     * comma-joined string -- multi-word terms like "Claude Code" stay intact instead of being
     * ambiguous list fragments), or a single legacy `prompt` part otherwise. Empty terms yield
     * no parts. Pure so the model-gating decision is unit-testable without the network plumbing.
     */
    fun vocabularyFormParts(model: String, terms: List<String>): List<Pair<String, String>> = when {
        terms.isEmpty() -> emptyList()
        isMaiTranscribe(model) -> listOf("provider" to maiPhraseListProvider(terms))
        supportsKeywords(model) -> terms.map { "keywords[]" to it }
        else -> listOf("prompt" to VocabularyTerms.asTranscriptionPrompt(terms))
    }

    /**
     * A parsed response with the prompt-echo guard applied (#306): `prompt` / `keywords[]` are
     * documented to be echoed by Whisper-family models on silence, so a transcript that is just
     * the vocabulary list becomes an empty transcript, which callers treat as "no speech".
     */
    fun resultFor(responseBody: String, terms: List<String>): Result {
        val parsed = parseResponse(responseBody)
        return if (VocabularyEchoGuard.isEcho(parsed.text, terms)) Result("", null) else parsed
    }

    /**
     * The [Result] for a response with [status] and [body]. A 2xx response is parsed as before. Any
     * other status is a failure even when its body happens to parse: the status, the server's
     * retry delay (header or OpenRouter's `metadata.retry_after_seconds`, whichever is longer)
     * and the body are kept for [TranscriptionRetry] and the failure log.
     */
    fun responseResult(status: Int, retryAfterMs: Long?, body: String, terms: List<String>): Result {
        if (status in 200..299) return resultFor(body, terms).copy(httpStatus = status)
        val message = parseResponse(body).error?.takeIf { it.isNotBlank() } ?: "HTTP $status"
        val asked = listOfNotNull(retryAfterMs, TranscriptionRetry.bodyRetryAfterMs(body)).maxOrNull()
        return Result(
            null, "HTTP $status: $message", httpStatus = status, retryAfterMs = asked,
            errorBody = body.take(MAX_ERROR_BODY_CHARS),
        )
    }

    private val retryScheduler by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "TranscriptionRetry").apply { isDaemon = true }
        }
    }

    /**
     * [transcribe], ridden through a transient failure according to [policy] (see
     * [TranscriptionRetry.run]): the same request again after a wait, up to [policy]'s retries,
     * then one request for its fallback model, which gets no vocabulary and always WAV.
     * [TranscriptionRetryPolicy.NONE] is one plain [transcribe] call.
     *
     * [shouldContinue] is asked before every request after the first: false (the dictation was
     * cancelled or timed out meanwhile) ends the run with the last failure. [onFailedAttempt] sees
     * each request that failed, as it fails. [callback] runs exactly once, on an OkHttp or the
     * retry thread; its [Result.attempts] lists every request made.
     */
    fun transcribeWithRetries(
        pcmFile: File,
        apiKey: String,
        cancelHolder: InFlightCall,
        baseUrl: String = PostProcessor.DEFAULT_BASE_URL,
        model: String = DEFAULT_MODEL,
        vocabularyTerms: List<String> = emptyList(),
        compressedFile: File? = null,
        language: String? = null,
        policy: TranscriptionRetryPolicy = TranscriptionRetryPolicy.NONE,
        shouldContinue: () -> Boolean = { true },
        onFailedAttempt: (TranscriptionAttempt) -> Unit = {},
        // Test seam: how a retry's wait is spent. Production waits on the scheduler thread.
        schedule: (delayMs: Long, block: () -> Unit) -> Unit = { delayMs, block ->
            retryScheduler.schedule({ block() }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        },
        callback: (Result) -> Unit,
    ) {
        val mainModel = model.ifBlank { DEFAULT_MODEL }
        TranscriptionRetry.run(
            model = mainModel,
            policy = policy,
            send = { attemptModel, isFallback, done ->
                transcribe(
                    pcmFile, apiKey, cancelHolder,
                    baseUrl = baseUrl,
                    model = attemptModel,
                    vocabularyTerms = if (isFallback) emptyList() else vocabularyTerms,
                    compressedFile = if (isFallback) null else compressedFile,
                    language = language,
                    callback = done,
                )
            },
            schedule = schedule,
            shouldContinue = shouldContinue,
            onFailedAttempt = onFailedAttempt,
            callback = callback,
        )
    }

    /**
     * The `language` form part for [language] (#290): the ISO-639-1 field OpenAI, Groq, Open
     * WebUI and Whisper-based servers all accept to skip auto-detection. Without it, detection
     * can settle on English for non-English speech, and the model returns English or
     * English-phonetic text. Null ([DictationLanguage.AUTO]) sends nothing, matching every
     * pre-#290 build.
     */
    fun languageFormParts(language: String?): List<Pair<String, String>> =
        if (language.isNullOrBlank()) emptyList() else listOf("language" to language)

    /**
     * Streams [pcmFile]'s bytes into the multipart upload as a WAV file without ever holding the
     * full audio as one contiguous byte array: [PcmWavRequestBody] writes the 44-byte header
     * directly to the sink, then streams the PCM file's bytes straight through. When
     * [compressedFile] is non-null (#109: compressed-upload toggle on and the AAC encode
     * succeeded) it is uploaded instead, as `.m4a`/`audio/mp4` -- see [uploadPart].
     *
     * [baseUrl]/[model] are honored so a proxy/self-hosted user's recordings actually go to their
     * configured OpenAI-compatible endpoint and model, not always api.openai.com/whisper-1 (M5) --
     * the cleanup path already honors the per-provider override, and [NetworkWarmup] even pre-warms
     * the override host, but the audio upload itself was hardcoded.
     */
    fun transcribe(
        pcmFile: File,
        apiKey: String,
        cancelHolder: InFlightCall,
        baseUrl: String = PostProcessor.DEFAULT_BASE_URL,
        model: String = DEFAULT_MODEL,
        vocabularyTerms: List<String> = emptyList(),
        compressedFile: File? = null,
        language: String? = null,
        callback: (Result) -> Unit,
    ) {
        debugFaults?.poll()?.let { status ->
            callback(
                Result(
                    null, "Provider returned $status", httpStatus = status, retryAfterMs = 1000L,
                    errorBody = """{"error":{"message":"Provider returned $status","code":$status,"metadata":{"injected":true}}}""",
                )
            )
            return
        }
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model.ifBlank { DEFAULT_MODEL })
        for ((name, value) in languageFormParts(language)) {
            bodyBuilder.addFormDataPart(name, value)
        }
        // #114 part 1: bias decoding toward vocabulary the user has taught Ramblr (project
        // names/jargon), the same terms already interpolated into cleanup-stage prompts (see
        // PostProcessor.vocabularyClause). Sent as structured `keywords[]` parts on models that
        // support them, else as the legacy comma-joined `prompt` -- see [vocabularyFormParts].
        for ((name, value) in vocabularyFormParts(model.ifBlank { DEFAULT_MODEL }, vocabularyTerms)) {
            bodyBuilder.addFormDataPart(name, value)
        }
        val (filename, filePart) = uploadPart(pcmFile, compressedFile, model.ifBlank { DEFAULT_MODEL })
        val body = bodyBuilder
            .addFormDataPart("file", filename, filePart)
            .build()

        val endpoint = transcriptionEndpoint(baseUrl)
        // A malformed override URL throws IllegalArgumentException from url(); report it through the
        // same Result/callback path (query-redacted) instead of crashing (mirrors GeminiTranscriberClient).
        val request = try {
            Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
        } catch (e: IllegalArgumentException) {
            callback(Result(null, "Invalid transcription endpoint: ${UrlRedaction.redact(e.message)}"))
            return
        }

        val call = client.newCall(request)
        cancelHolder.set(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cancelHolder.clear(call)
                val cancelled = call.isCanceled()
                callback(Result(null, e.message ?: e.javaClass.simpleName, noResponse = !cancelled, cancelled = cancelled))
            }
            override fun onResponse(call: Call, response: Response) {
                cancelHolder.clear(call)
                // Read via HttpBodyReader (#62): a body read that throws inside onResponse is
                // swallowed by OkHttp, so the callback (and the temp PCM file's deletion in it)
                // would otherwise never run and the dictation would hang until the watchdog.
                val status = response.code
                val retryAfterMs = TranscriptionRetry.retryAfterMs(
                    response.header("retry-after-ms"), response.header("Retry-After"), System.currentTimeMillis(),
                )
                HttpBodyReader.read(response).fold(
                    onSuccess = { body -> callback(responseResult(status, retryAfterMs, body, vocabularyTerms)) },
                    // The body died mid-read: no usable answer, the same as no answer.
                    onFailure = { e -> callback(Result(null, e.message, httpStatus = status, noResponse = true)) },
                )
            }
        })
    }
}

/**
 * A [RequestBody] that streams a WAV file (44-byte header + PCM body) straight from [pcmFile] to
 * OkHttp's sink, so uploading never requires one contiguous byte array holding the whole
 * recording. See #16.
 */
class PcmWavRequestBody(
    private val pcmFile: File,
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16
) : RequestBody() {
    override fun contentType(): MediaType = "audio/wav".toMediaType()

    override fun contentLength(): Long = 44L + pcmFile.length()

    override fun writeTo(sink: BufferedSink) {
        sink.write(WavWriter.header(pcmFile.length(), sampleRate, channels, bitsPerSample))
        pcmFile.source().use { source -> sink.writeAll(source) }
    }
}
