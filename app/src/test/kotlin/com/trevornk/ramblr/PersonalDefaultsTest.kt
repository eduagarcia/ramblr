package com.trevornk.ramblr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of [PersonalDefaults]: the entries it builds and how it merges with what exists. */
class PersonalDefaultsTest {

    private val transcription = JSONObject(
        """{"preset":"openrouter","baseUrl":"https://openrouter.ai/api/v1","model":"microsoft/mai-transcribe-2","apiKey":"k1"}"""
    )
    private val cleanup = JSONObject(
        """{"baseUrl":"https://gw.example/v1","model":"gpt-6-luna","apiKey":"k2","extraBody":{"reasoning_effort":"low"},"timeoutMs":30000}"""
    )

    @Test fun `the transcription entry transcribes and never cleans up`() {
        val entry = PersonalDefaults.transcriptionEntry(transcription)
        assertEquals(ProviderKind.OPENAI, entry.kind)
        assertEquals("https://openrouter.ai/api/v1", entry.baseUrlOverride)
        assertEquals("microsoft/mai-transcribe-2", entry.transcriptionModel)
        assertEquals("openrouter", entry.presetId)
        assertTrue(entry.useForTranscription)
        assertFalse(entry.useForCleanup)
    }

    @Test fun `the cleanup entry cleans up, never transcribes, and carries its extra body`() {
        val entry = PersonalDefaults.cleanupEntry(cleanup)
        assertEquals("gpt-6-luna", entry.model)
        assertEquals("https://gw.example/v1", entry.baseUrlOverride)
        assertEquals("low", JSONObject(entry.extraBody!!).getString("reasoning_effort"))
        assertNull(entry.presetId)
        assertFalse(entry.useForTranscription)
        assertTrue(entry.useForCleanup)
    }

    @Test fun `the two entries route each job to its own provider`() {
        val chain = ProviderChain(listOf(PersonalDefaults.transcriptionEntry(transcription), PersonalDefaults.cleanupEntry(cleanup)))
        assertEquals(
            listOf(PersonalDefaults.TRANSCRIPTION_ENTRY_ID),
            ProviderChainRuntime.transcriptionCandidates(chain).map { it.id },
        )
        assertEquals(
            listOf(PersonalDefaults.CLEANUP_ENTRY_ID),
            ProviderChainRuntime.cleanupWaterfallFor(chain).steps.map { it.entryId },
        )
    }

    @Test fun `a fresh install's default entry is replaced by the personal ones`() {
        val personal = listOf(PersonalDefaults.transcriptionEntry(transcription), PersonalDefaults.cleanupEntry(cleanup))
        val merged = PersonalDefaults.mergeChain(ProviderChain.DEFAULT_SINGLE_OPENAI_ENTRY, personal)
        assertEquals(personal, merged.entries)
    }

    @Test fun `applying again replaces the personal entries and keeps the user's own, local last`() {
        val own = ProviderChainEntry(ProviderKind.GEMINI, "gemini", id = "own")
        val local = ProviderChainEntry(ProviderKind.LOCAL, "local", id = "local")
        val old = PersonalDefaults.cleanupEntry(JSONObject("""{"model":"old-model"}"""))
        val personal = listOf(PersonalDefaults.cleanupEntry(cleanup))
        val merged = PersonalDefaults.mergeChain(ProviderChain(listOf(old, local, own)), personal)
        assertEquals(listOf(PersonalDefaults.CLEANUP_ENTRY_ID, "own", "local"), merged.entries.map { it.id })
        assertEquals("gpt-6-luna", merged.entries.first().model)
    }

    @Test fun `replacements become snippets, after the user's own`() {
        val own = SnippetEntry(key = "snippet_1", trigger = "my address", expansion = "123 Main St")
        val merged = PersonalDefaults.mergeSnippets(listOf(own), listOf("btw" to "by the way", "Open Sense" to "OPNSense"))
        assertEquals(own, merged.first())
        assertEquals(listOf("btw", "Open Sense"), merged.drop(1).map { it.trigger })
        assertEquals("Na OPNSense, by the way.", SnippetExpander.expand("Na open sense, btw.", merged))
    }

    @Test fun `applying again replaces earlier personal snippets instead of adding to them`() {
        val first = PersonalDefaults.mergeSnippets(emptyList(), listOf("btw" to "by the way", "teste DSL" to "text2dsl"))
        val second = PersonalDefaults.mergeSnippets(first, listOf("btw" to "by the way"))
        assertEquals(listOf("btw"), second.map { it.trigger })
    }

    @Test fun `a replacement the user already has a snippet for, or an empty one, is skipped`() {
        val own = SnippetEntry(key = "snippet_1", trigger = "BTW", expansion = "between")
        val merged = PersonalDefaults.mergeSnippets(listOf(own), listOf("btw" to "by the way", "..." to "x", "ok" to " "))
        assertEquals(listOf(own), merged)
    }
}
