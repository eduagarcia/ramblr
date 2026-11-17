package com.trevornk.ramblr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An entry's extra request fields ([ProviderChainEntry.extraBody]) and the cleanup time budget
 * ([CleanupTimeout]): stored with the chain, carried to the step, merged into the request.
 */
class CleanupExtraBodyTest {

    private val extra = """{"reasoning_effort":"low","service_tier":"priority","temperature":0.3}"""

    @Test fun `extra body fields are merged over the built body`() {
        val body = PostProcessor.buildRequestBody("text", "prompt", "gpt-6-luna", extraBody = extra)
        assertEquals("low", body.getString("reasoning_effort"))
        assertEquals("priority", body.getString("service_tier"))
        assertEquals(0.3, body.getDouble("temperature"), 0.0)
        assertEquals("gpt-6-luna", body.getString("model"))
        assertFalse(body.getBoolean("stream"))
    }

    @Test fun `extra body cannot replace the model, the messages or the stream flag`() {
        val body = PostProcessor.buildRequestBody(
            "text", "prompt", "gpt-6-luna",
            extraBody = """{"model":"other","messages":[],"stream":true,"top_p":0.9}""",
        )
        assertEquals("gpt-6-luna", body.getString("model"))
        assertEquals(2, body.getJSONArray("messages").length())
        assertFalse(body.getBoolean("stream"))
        assertEquals(0.9, body.getDouble("top_p"), 0.0)
    }

    @Test fun `no extra body, or one that is not a json object, leaves the body as it was`() {
        val plain = PostProcessor.buildRequestBody("text", "prompt", "gpt-6-luna").toString()
        assertEquals(plain, PostProcessor.buildRequestBody("text", "prompt", "gpt-6-luna", extraBody = null).toString())
        assertEquals(plain, PostProcessor.buildRequestBody("text", "prompt", "gpt-6-luna", extraBody = "  ").toString())
        assertEquals(plain, PostProcessor.buildRequestBody("text", "prompt", "gpt-6-luna", extraBody = "not json").toString())
    }

    @Test fun `extra body survives the chain store and reaches the cleanup step`() {
        val chain = ProviderChain(
            listOf(
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-6-luna", baseUrlOverride = "https://gw.example/v1", id = "a", extraBody = extra),
                ProviderChainEntry(ProviderKind.OPENAI, "gpt-5.4-mini", id = "b"),
            )
        )
        val restored = ProviderChainStore.deserialize(ProviderChainStore.serialize(chain))!!
        assertEquals(chain, restored)
        val steps = ProviderChainRuntime.cleanupWaterfallFor(restored).steps
        assertEquals(extra, steps[0].extraBody)
        assertNull(steps[1].extraBody)
    }

    @Test fun `a chain saved before extra body existed loads without one`() {
        val legacy = """[{"kind":"OPENAI","model":"gpt-5.4-mini","baseUrlOverride":null,"id":"a"}]"""
        assertNull(ProviderChainStore.deserialize(legacy)!!.entries.single().extraBody)
    }

    @Test fun `the executor sends the step's extra body`() {
        var sent = ""
        val transport = CleanupHttpTransport { _, _, jsonBody, _, _, callback ->
            sent = jsonBody
            callback(CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"cleaned"}}]}"""))
        }
        var result: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw transcript", prompt = "cleanup",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-6-luna", extraBody = extra))),
            cursor = CleanupWaterfallCursor(), cancelHolder = InFlightCall(),
            credentialLookup = { "auth" }, transport = transport, fidelityGuard = false,
        ) { result = it }
        assertEquals("cleaned", result?.text)
        val body = JSONObject(sent)
        assertEquals("low", body.getString("reasoning_effort"))
        assertEquals("priority", body.getString("service_tier"))
        assertEquals(0.3, body.getDouble("temperature"), 0.0)
    }

    @Test fun `a raised time budget lets a slow step finish and widens its read timeout`() {
        var now = 0L
        val seen = ArrayList<CleanupStepTimeouts>()
        val transport = CleanupHttpTransport { _, _, _, timeouts, _, callback ->
            seen += timeouts
            now += 20_000L // longer than the 8 s default, inside the raised budget
            callback(CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"cleaned"}}]}"""))
        }
        var result: PostProcessor.Result? = null
        CleanupWaterfallExecutor.execute(
            text = "raw transcript", prompt = "cleanup",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-6-luna"))),
            cursor = CleanupWaterfallCursor(), cancelHolder = InFlightCall(),
            credentialLookup = { "auth" }, transport = transport, nowMs = { now }, fidelityGuard = false,
            hardCapMs = 30_000L,
        ) { result = it }
        assertEquals("cleaned", result?.text)
        assertEquals(30_000L, seen.single().readMs)
        assertEquals(30_000L, seen.single().callMs)
    }

    @Test fun `the default budget keeps the default timeouts`() {
        val seen = ArrayList<CleanupStepTimeouts>()
        val transport = CleanupHttpTransport { _, _, _, timeouts, _, callback ->
            seen += timeouts
            callback(CleanupHttpOutcome.Ok("""{"choices":[{"message":{"content":"cleaned"}}]}"""))
        }
        CleanupWaterfallExecutor.execute(
            text = "raw transcript", prompt = "cleanup",
            waterfall = CleanupWaterfall(listOf(CleanupStep(CleanupStepGroup.OPENAI_DIRECT, "gpt-6-luna"))),
            cursor = CleanupWaterfallCursor(), cancelHolder = InFlightCall(),
            credentialLookup = { "auth" }, transport = transport, nowMs = { 0L }, fidelityGuard = false,
        ) { }
        assertEquals(CleanupStepTimeouts.DEFAULT.readMs, seen.single().readMs)
        assertEquals(CLEANUP_WATERFALL_HARD_CAP_MS, seen.single().callMs)
    }

    @Test fun `the stored budget is clamped and nothing stored means the default`() {
        assertEquals(CLEANUP_WATERFALL_HARD_CAP_MS, CleanupTimeout.resolve(0L))
        assertEquals(CLEANUP_WATERFALL_HARD_CAP_MS, CleanupTimeout.resolve(-5L))
        assertEquals(30_000L, CleanupTimeout.resolve(30_000L))
        assertEquals(CleanupTimeout.MIN_MS, CleanupTimeout.resolve(1L))
        assertEquals(CleanupTimeout.MAX_MS, CleanupTimeout.resolve(10_000_000L))
    }

    @Test fun `the vocabulary list placeholder is the bare term list`() {
        val prompt = "Clean it.\n\nVocabulary:\n{{vocabulary_list}}"
        assertEquals("Clean it.\n\nVocabulary:\nClaude Code, AWS", PostProcessor.interpolateVocabulary(prompt, listOf("Claude Code", "AWS")))
        assertEquals("Clean it.\n\nVocabulary:\n", PostProcessor.interpolateVocabulary(prompt, emptyList()))
        assertTrue(PostProcessor.interpolateVocabulary("Fix it.{{vocabulary}}", listOf("AWS")).contains("AWS"))
    }
}
