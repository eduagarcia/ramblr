package com.trevornk.ramblr

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [TranscriptionRetry]: which failures are worth sending again, how long to wait, and the one
 * request to the fallback model when the retries run out. The last tests run the real HTTP client
 * against a local server that rate-limits like OpenRouter does for MAI-Transcribe.
 */
class TranscriptionRetryTest {

    private fun failed(status: Int?, retryAfterMs: Long? = null, body: String? = null, noResponse: Boolean = false) =
        TranscriberClient.Result(
            null, "HTTP $status", httpStatus = status, retryAfterMs = retryAfterMs, errorBody = body, noResponse = noResponse,
        )

    private fun ok(text: String) = TranscriberClient.Result(text, null, httpStatus = 200)

    // --- what is transient ---------------------------------------------------------------------

    @Test fun `rate limits, overload and gateway errors are transient, the rest is not`() {
        for (status in listOf(429, 502, 503, 504, 520, 521, 522, 523, 524)) {
            assertTrue("$status", TranscriptionRetry.isTransientStatus(status))
        }
        for (status in listOf(200, 400, 401, 402, 403, 404, 413, 422, 500, 501, 505, 525)) {
            assertFalse("$status", TranscriptionRetry.isTransientStatus(status))
        }
    }

    @Test fun `the wait is 1 s doubled per attempt, the server's when longer, 5 s at most`() {
        fun waits(result: TranscriberClient.Result) = (0..3).map { TranscriptionRetry.retryWaitMs(result, it) }
        assertEquals(listOf(1000L, 2000L, 4000L, 5000L), waits(failed(429)))
        assertEquals(listOf(1000L, 2000L, 4000L, 5000L), waits(failed(429, retryAfterMs = 1000)))
        assertEquals(listOf(3000L, 3000L, 4000L, 5000L), waits(failed(503, retryAfterMs = 3000)))
        assertEquals(listOf(5000L, 5000L, 5000L, 5000L), waits(failed(429, retryAfterMs = 60_000)))
    }

    @Test fun `a request that got no answer is transient`() {
        assertEquals(1000L, TranscriptionRetry.retryWaitMs(failed(null, noResponse = true), 0))
        // No status and no "no response" either: an unusable answer, not worth repeating.
        assertNull(TranscriptionRetry.retryWaitMs(failed(null), 0))
    }

    @Test fun `a bad request, a refused key and a cancelled call are never repeated`() {
        for (status in listOf(400, 401, 403, 404, 422, 500)) {
            assertNull("$status", TranscriptionRetry.retryWaitMs(failed(status), 0))
        }
        assertNull(TranscriptionRetry.retryWaitMs(TranscriberClient.Result(null, "Canceled", cancelled = true), 0))
        assertNull(TranscriptionRetry.retryWaitMs(ok("text"), 0))
        assertNull(TranscriptionRetry.retryWaitMs(ok(""), 0))
    }

    @Test fun `a credit or billing error is final whatever its status`() {
        assertNull(TranscriptionRetry.retryWaitMs(failed(402), 0))
        val quota = """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}"""
        assertNull(TranscriptionRetry.retryWaitMs(failed(429, body = quota), 0))
        val credits = """{"error":{"message":"Insufficient credits","code":429}}"""
        assertNull(TranscriptionRetry.retryWaitMs(failed(429, body = credits), 0))
        assertTrue(TranscriptionRetry.isBillingError(403, "Billing hard limit reached", null))
        // The rate limit this exists for is not one.
        val rateLimited = """{"error":{"message":"Provider returned 429","code":429,"metadata":{"retry_after_seconds":1}}}"""
        assertFalse(TranscriptionRetry.isBillingError(429, "HTTP 429: Provider returned 429", rateLimited))
        assertEquals(1000L, TranscriptionRetry.retryWaitMs(failed(429, body = rateLimited), 0))
    }

    @Test fun `the server's delay is read from its headers and from the body`() {
        val now = 1_000_000L
        assertEquals(1000L, TranscriptionRetry.retryAfterMs(null, "1", now))
        assertEquals(1500L, TranscriptionRetry.retryAfterMs(null, " 1.5 ", now))
        assertEquals(250L, TranscriptionRetry.retryAfterMs("250", "9", now))
        assertNull(TranscriptionRetry.retryAfterMs(null, "-3", now))
        assertNull(TranscriptionRetry.retryAfterMs(null, "soon", now))
        assertNull(TranscriptionRetry.retryAfterMs(null, null, now))
        val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.Instant.ofEpochMilli(now + 60_000).atZone(java.time.ZoneOffset.UTC))
        assertEquals(60_000L, TranscriptionRetry.retryAfterMs(null, date, now))
        assertEquals(0L, TranscriptionRetry.retryAfterMs(null, date, now + 120_000))
        assertEquals(
            2000L,
            TranscriptionRetry.bodyRetryAfterMs("""{"error":{"code":429,"metadata":{"retry_after_seconds":2}}}"""),
        )
        assertNull(TranscriptionRetry.bodyRetryAfterMs("""{"error":{"code":429}}"""))
        assertNull(TranscriptionRetry.bodyRetryAfterMs("not json"))
    }

    // --- the run -------------------------------------------------------------------------------

    /** Runs a transcription whose requests end in [script], in order, with waits that do not sleep. */
    private class Run(
        script: List<TranscriberClient.Result>,
        policy: TranscriptionRetryPolicy,
        shouldContinue: () -> Boolean = { true },
    ) {
        val asked = ArrayList<String>()
        val waits = ArrayList<Long>()
        val failures = ArrayList<TranscriptionAttempt>()
        var results = ArrayList<TranscriberClient.Result>()
        val result: TranscriberClient.Result get() = results.single()

        init {
            val pending = ArrayDeque(script)
            TranscriptionRetry.run(
                model = "main",
                policy = policy,
                send = { model, isFallback, done ->
                    asked += if (isFallback) "$model (fallback)" else model
                    done(pending.removeFirst())
                },
                schedule = { delayMs, block -> waits += delayMs; block() },
                shouldContinue = shouldContinue,
                onFailedAttempt = { failures += it },
                nowMs = { 0L },
            ) { results += it }
        }
    }

    private val twoRetriesAndOther = TranscriptionRetryPolicy(retries = 2, fallbackModel = "other")

    @Test fun `a rate-limited request is sent again after 1 s and 2 s`() {
        val run = Run(listOf(failed(429, 1000), failed(429, 1000), ok("oi")), twoRetriesAndOther)
        assertEquals("oi", run.result.text)
        assertEquals(listOf("main", "main", "main"), run.asked)
        assertEquals(listOf(1000L, 2000L), run.waits)
        assertEquals("main", run.result.model)
        assertEquals(
            listOf(TranscriptionAttempt.Kind.FIRST, TranscriptionAttempt.Kind.RETRY, TranscriptionAttempt.Kind.RETRY),
            run.result.attempts.map { it.kind },
        )
        assertEquals(listOf(false, false, true), run.result.attempts.map { it.succeeded })
        assertEquals(2, run.failures.size)
    }

    @Test fun `the fallback model is asked once when the retries run out`() {
        val run = Run(listOf(failed(429), failed(429), failed(429), ok("from other")), twoRetriesAndOther)
        assertEquals("from other", run.result.text)
        assertEquals(listOf("main", "main", "main", "other (fallback)"), run.asked)
        // No wait before the fallback: the waits were for the main model.
        assertEquals(listOf(1000L, 2000L), run.waits)
        assertEquals("other", run.result.model)
        assertEquals(TranscriptionAttempt.Kind.FALLBACK, run.result.attempts.last().kind)
    }

    @Test fun `a fallback model that fails is the dictation's error, with no retry of its own`() {
        val run = Run(listOf(failed(429), failed(429), failed(429), failed(429)), twoRetriesAndOther)
        assertNull(run.result.text)
        assertEquals(listOf("main", "main", "main", "other (fallback)"), run.asked)
        assertEquals(listOf(1000L, 2000L), run.waits)
        assertEquals(4, run.failures.size)
        assertEquals("other", run.result.model)
    }

    @Test fun `a failure that is not transient gets no retry and no fallback`() {
        for (final in listOf(failed(400), failed(401), failed(402), failed(403), failed(422))) {
            val run = Run(listOf(final), twoRetriesAndOther)
            assertEquals("${final.httpStatus}", listOf("main"), run.asked)
            assertTrue(run.waits.isEmpty())
            assertNull(run.result.text)
            assertEquals(final.httpStatus, run.result.httpStatus)
        }
    }

    @Test fun `a final failure after a retry stops there too`() {
        val run = Run(listOf(failed(429), failed(401)), twoRetriesAndOther)
        assertEquals(listOf("main", "main"), run.asked)
        assertEquals(401, run.result.httpStatus)
    }

    @Test fun `an empty transcript is an answer, not a failure`() {
        val run = Run(listOf(ok("")), twoRetriesAndOther)
        assertEquals("", run.result.text)
        assertEquals(listOf("main"), run.asked)
        assertTrue(run.failures.isEmpty())
    }

    @Test fun `by default there is no retry and no fallback`() {
        val run = Run(listOf(failed(429)), TranscriptionRetryPolicy.NONE)
        assertEquals(listOf("main"), run.asked)
        assertTrue(run.waits.isEmpty())
        assertEquals(429, run.result.httpStatus)
        assertEquals(TranscriptionRetryPolicy.NONE, TranscriptionRetrySettings.policy(FakeStringPrefs()))
    }

    @Test fun `a fallback model without retries is asked right after the first failure`() {
        val run = Run(listOf(failed(503), ok("from other")), TranscriptionRetryPolicy(retries = 0, fallbackModel = "other"))
        assertEquals(listOf("main", "other (fallback)"), run.asked)
        assertTrue(run.waits.isEmpty())
        assertEquals("from other", run.result.text)
    }

    @Test fun `retries without a fallback model end with the last failure`() {
        val run = Run(listOf(failed(429), failed(429)), TranscriptionRetryPolicy(retries = 1))
        assertEquals(listOf("main", "main"), run.asked)
        assertNull(run.result.text)
    }

    @Test fun `the main model is never its own fallback`() {
        val run = Run(listOf(failed(429)), TranscriptionRetryPolicy(retries = 0, fallbackModel = " main "))
        assertEquals(listOf("main"), run.asked)
    }

    @Test fun `a dictation cancelled meanwhile is not sent again`() {
        val run = Run(listOf(failed(429)), twoRetriesAndOther, shouldContinue = { false })
        assertEquals(listOf("main"), run.asked)
        assertNull(run.result.text)
    }

    @Test fun `the settings are stored, clamped and trimmed`() {
        val prefs = FakeStringPrefs()
        TranscriptionRetrySettings.set(prefs, 2, " elevenlabs/scribe-v2 ")
        assertEquals(TranscriptionRetryPolicy(2, "elevenlabs/scribe-v2"), TranscriptionRetrySettings.policy(prefs))
        TranscriptionRetrySettings.set(prefs, 99, "")
        assertEquals(TranscriptionRetryPolicy(TranscriptionRetrySettings.MAX_RETRIES, ""), TranscriptionRetrySettings.policy(prefs))
        TranscriptionRetrySettings.set(prefs, -1, "")
        assertEquals(TranscriptionRetryPolicy.NONE, TranscriptionRetrySettings.policy(prefs))
    }

    // --- the logs ------------------------------------------------------------------------------

    @Test fun `the benchmark line lists the attempts only when there was more than one`() {
        fun line(attempts: List<TranscriptionAttempt>) = JSONObject(
            BenchmarkLogger.buildLine(
                1L, "id",
                transcription = BenchmarkStage("OPENAI", "other", 4200, true, attempts = attempts),
                cleanup = null, rawTextLength = 10, cleanedTextLength = null,
            )
        ).getJSONObject("transcription")
        val run = Run(listOf(failed(429), failed(429), failed(429), ok("x")), twoRetriesAndOther)
        val logged = line(run.result.attempts).getJSONArray("attempts")
        assertEquals(4, logged.length())
        assertEquals("FIRST", logged.getJSONObject(0).getString("kind"))
        assertEquals(429, logged.getJSONObject(0).getInt("httpStatus"))
        assertEquals("FALLBACK", logged.getJSONObject(3).getString("kind"))
        assertEquals("other", logged.getJSONObject(3).getString("model"))
        assertTrue(logged.getJSONObject(3).getBoolean("success"))
        assertFalse(line(Run(listOf(ok("x")), twoRetriesAndOther).result.attempts).has("attempts"))
    }

    @Test fun `the failure log line holds the model, the status and the response body`() {
        val body = """{"error":{"message":"Provider returned 429","code":429}}"""
        val run = Run(listOf(failed(429, body = body)), TranscriptionRetryPolicy.NONE)
        val line = JSONObject(TranscriptionFailureLog.buildLine(5L, "tok-1", run.failures.single()))
        assertEquals("tok-1", line.getString("correlationId"))
        assertEquals("FIRST", line.getString("kind"))
        assertEquals("main", line.getString("model"))
        assertEquals(429, line.getInt("httpStatus"))
        assertEquals(body, line.getString("body"))
    }

    // --- against a server ----------------------------------------------------------------------

    private var server: MockWebServer? = null

    @After fun closeServer() {
        server?.close()
    }

    private val rateLimitedBody =
        """{"error":{"message":"Provider returned 429","code":429,"metadata":{"retry_after_seconds":1,"provider_name":"Azure"}}}"""

    private fun rateLimited() = MockResponse.Builder().code(429)
        .addHeader("Retry-After", "1").addHeader("x-openrouter-error-origin", "provider")
        .body(rateLimitedBody).build()

    private fun transcript(text: String) = MockResponse.Builder().body(JSONObject().put("text", text).toString()).build()

    private class Served(val result: TranscriberClient.Result, val waits: List<Long>, val failures: List<TranscriptionAttempt>)

    private fun transcribeAgainst(responses: List<MockResponse>, policy: TranscriptionRetryPolicy): Served {
        val started = MockWebServer().apply { start() }
        server = started
        responses.forEach(started::enqueue)
        val pcm = File.createTempFile("retry", ".pcm").apply { writeBytes(ByteArray(3200)); deleteOnExit() }
        val waits = java.util.Collections.synchronizedList(ArrayList<Long>())
        val failures = java.util.Collections.synchronizedList(ArrayList<TranscriptionAttempt>())
        val done = CountDownLatch(1)
        var result: TranscriberClient.Result? = null
        TranscriberClient.transcribeWithRetries(
            pcm, "key", InFlightCall(),
            baseUrl = started.url("/api/v1").toString(),
            model = "microsoft/mai-transcribe-2",
            vocabularyTerms = listOf("Claude Code"),
            language = "pt",
            policy = policy,
            onFailedAttempt = { failures += it },
            schedule = { delayMs, block -> waits += delayMs; block() },
        ) { result = it; done.countDown() }
        assertTrue("no result in time", done.await(20, TimeUnit.SECONDS))
        return Served(result!!, waits.toList(), failures.toList())
    }

    private fun MockWebServer.requestBodies(count: Int): List<String> =
        (1..count).map { takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8() }

    private fun String.formValue(name: String): String? =
        Regex("name=\"$name\"\\r\\n(?:Content-Length: \\d+\\r\\n)?\\r\\n([^\\r]*)").find(this)?.groupValues?.get(1)

    private val scribe = TranscriptionRetryPolicy(retries = 2, fallbackModel = "elevenlabs/scribe-v2")

    @Test fun `two 429 answers and then the transcript - same model three times`() {
        val served = transcribeAgainst(listOf(rateLimited(), rateLimited(), transcript("oi")), scribe)
        assertEquals("oi", served.result.text)
        assertEquals(listOf(1000L, 2000L), served.waits)
        assertEquals(3, server!!.requestCount)
        val bodies = server!!.requestBodies(3)
        assertEquals(List(3) { "microsoft/mai-transcribe-2" }, bodies.map { it.formValue("model") })
        assertTrue(bodies.all { it.formValue("provider")!!.contains("Claude Code") })
        // What was kept of each refusal.
        val refused = served.failures.first()
        assertEquals(429, refused.httpStatus)
        assertEquals(rateLimitedBody, refused.errorBody)
        assertEquals("HTTP 429: Provider returned 429", refused.error)
    }

    @Test fun `the main model always 429 - the fallback model answers, asked without the vocabulary`() {
        val served = transcribeAgainst(listOf(rateLimited(), rateLimited(), rateLimited(), transcript("do reserva")), scribe)
        assertEquals("do reserva", served.result.text)
        assertEquals("elevenlabs/scribe-v2", served.result.model)
        assertEquals(listOf(1000L, 2000L), served.waits)
        val bodies = server!!.requestBodies(4)
        assertEquals(
            List(3) { "microsoft/mai-transcribe-2" } + "elevenlabs/scribe-v2",
            bodies.map { it.formValue("model") },
        )
        val fallback = bodies.last()
        assertNull(fallback.formValue("provider"))
        assertNull(fallback.formValue("prompt"))
        assertFalse(fallback.contains("keywords"))
        assertEquals("pt", fallback.formValue("language"))
        assertTrue(fallback.contains("filename=\"audio.wav\""))
    }

    @Test fun `everything 429 - four requests and the error`() {
        val served = transcribeAgainst(List(4) { rateLimited() }, scribe)
        assertNull(served.result.text)
        assertEquals(429, served.result.httpStatus)
        assertEquals(4, server!!.requestCount)
        assertEquals(4, served.failures.size)
        assertEquals(TranscriptionAttempt.Kind.FALLBACK, served.failures.last().kind)
    }

    @Test fun `a refused key is asked once`() {
        val refused = MockResponse.Builder().code(401).body("""{"error":{"message":"No auth credentials found","code":401}}""").build()
        val served = transcribeAgainst(listOf(refused), scribe)
        assertNull(served.result.text)
        assertEquals(1, server!!.requestCount)
        assertTrue(served.waits.isEmpty())
        assertEquals("HTTP 401: No auth credentials found", served.result.error)
    }

    @Test fun `no credit is asked once`() {
        val noCredit = MockResponse.Builder().code(402).body("""{"error":{"message":"Insufficient credits","code":402}}""").build()
        val served = transcribeAgainst(listOf(noCredit), scribe)
        assertEquals(1, server!!.requestCount)
        assertEquals(402, served.result.httpStatus)
    }

    @Test fun `with the default policy a 429 is one request`() {
        val served = transcribeAgainst(listOf(rateLimited()), TranscriptionRetryPolicy.NONE)
        assertEquals(1, server!!.requestCount)
        assertEquals(429, served.result.httpStatus)
        assertEquals(1000L, served.result.retryAfterMs)
    }
}
