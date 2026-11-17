package com.trevornk.ramblr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a transcription request looks like for Microsoft MAI-Transcribe on OpenRouter: the
 * vocabulary goes in a `provider` field as an Azure phrase list, and the audio is always WAV.
 */
class MaiTranscribeRequestTest {

    @Test fun `mai transcribe models are recognized whatever their case or version`() {
        assertTrue(TranscriberClient.isMaiTranscribe("microsoft/mai-transcribe-2"))
        assertTrue(TranscriberClient.isMaiTranscribe(" Microsoft/MAI-Transcribe-3 "))
        assertFalse(TranscriberClient.isMaiTranscribe("gpt-transcribe"))
        assertFalse(TranscriberClient.isMaiTranscribe("openai/whisper-large-v3"))
    }

    @Test fun `vocabulary goes to mai transcribe as one provider field holding an azure phrase list`() {
        val parts = TranscriberClient.vocabularyFormParts("microsoft/mai-transcribe-2", listOf("Claude Code", "AWS"))
        assertEquals(1, parts.size)
        assertEquals("provider", parts[0].first)
        val phrases = JSONObject(parts[0].second)
            .getJSONObject("options").getJSONObject("azure").getJSONObject("phraseList").getJSONArray("phrases")
        assertEquals(listOf("Claude Code", "AWS"), (0 until phrases.length()).map { phrases.getString(it) })
    }

    @Test fun `no vocabulary sends no provider field`() {
        assertTrue(TranscriberClient.vocabularyFormParts("microsoft/mai-transcribe-2", emptyList()).isEmpty())
    }

    @Test fun `other models keep their own vocabulary fields`() {
        assertEquals(
            listOf("keywords[]" to "Claude Code"),
            TranscriberClient.vocabularyFormParts("gpt-transcribe", listOf("Claude Code")),
        )
        assertEquals("prompt", TranscriberClient.vocabularyFormParts("whisper-1", listOf("Claude Code")).single().first)
    }

    @Test fun `mai transcribe gets wav even when a compressed copy exists`() {
        val pcm = File.createTempFile("mai", ".pcm").apply { deleteOnExit() }
        val m4a = File.createTempFile("mai", ".m4a").apply { deleteOnExit() }
        assertEquals(
            TranscriberClient.WAV_FILENAME,
            TranscriberClient.uploadPart(pcm, m4a, "microsoft/mai-transcribe-2").first,
        )
        assertEquals(TranscriberClient.COMPRESSED_FILENAME, TranscriberClient.uploadPart(pcm, m4a, "gpt-transcribe").first)
        assertEquals(TranscriberClient.COMPRESSED_FILENAME, TranscriberClient.uploadPart(pcm, m4a).first)
        assertEquals(TranscriberClient.WAV_FILENAME, TranscriberClient.uploadPart(pcm, null, "gpt-transcribe").first)
    }
}
