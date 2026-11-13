package com.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiOrganizerServiceTest {

    private val service = AiOrganizerService()

    @Test
    fun `resolveAudioMimeType maps audio extensions accurately`() {
        assertEquals("audio/wav", AiOrganizerService.resolveAudioMimeType(File("note.wav")))
        assertEquals("audio/m4a", AiOrganizerService.resolveAudioMimeType(File("note.m4a")))
        assertEquals("audio/aac", AiOrganizerService.resolveAudioMimeType(File("note.aac")))
        assertEquals("audio/mp3", AiOrganizerService.resolveAudioMimeType(File("note.mp3")))
        assertEquals("audio/ogg", AiOrganizerService.resolveAudioMimeType(File("note.ogg")))
        assertEquals("audio/flac", AiOrganizerService.resolveAudioMimeType(File("note.flac")))
    }

    @Test
    fun `parseResult preserves fallbackTranscript when JSON transcript is blank`() {
        val json = """
            {
              "title": "Q4 Budget Review",
              "transcript": "   ",
              "summary": "Review Q4 numbers by Friday.",
              "category": "Official",
              "tags": ["Finance", "Budget"],
              "keywords": ["budget", "q4", "finance"],
              "actionItem": "Send revised spreadsheet to team",
              "priority": "high",
              "suggestedDueHours": 24
            }
        """.trimIndent()

        val parsed = service.parseResult(
            jsonText = json,
            fallbackTranscript = "Please review the Q4 budget before Friday afternoon."
        )

        assertEquals("Q4 Budget Review", parsed.title)
        assertEquals("Please review the Q4 budget before Friday afternoon.", parsed.transcript)
        assertEquals("Official", parsed.category)
        assertEquals("high", parsed.priority)
        assertEquals(24, parsed.suggestedDueHours)
    }

    @Test
    fun `buildBasicOrganizedResult retains full verbatim transcript`() {
        val transcript = "Call vendor tomorrow morning at 9 AM to confirm shipping schedule."
        val result = service.buildBasicOrganizedResult(transcript, preferredCategory = "Official")

        assertEquals(transcript, result.transcript)
        assertEquals("Official", result.category)
        assertTrue(result.title.isNotBlank())
        assertTrue(result.keywords.contains("vendor"))
    }

    @Test
    fun `CaptureLiveState fullTranscript joins confirmed and partial segments`() {
        val state = CaptureLiveState(
            confirmedTranscript = "Prepare weekly ledger",
            partialTranscript = "for Monday review"
        )
        assertEquals("Prepare weekly ledger for Monday review", state.fullTranscript)
    }
}
