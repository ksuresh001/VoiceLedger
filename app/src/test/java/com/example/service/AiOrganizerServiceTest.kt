package com.example.service

import android.os.Bundle
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    fun `buildBasicOrganizedResult retains full verbatim transcript and infers metadata`() {
        val transcript = "Urgent call vendor tomorrow morning at 9 AM to confirm shipping schedule."
        val result = service.buildBasicOrganizedResult(transcript, preferredCategory = "")

        assertEquals(transcript, result.transcript)
        assertEquals("Client", result.category)
        assertEquals("high", result.priority)
        assertEquals(24, result.suggestedDueHours)
        assertTrue(result.title.isNotBlank())
        assertTrue(result.actionItem.isNotBlank())
        assertTrue(result.keywords.contains("vendor"))
    }

    @Test
    fun `CaptureLiveState fullTranscript and extractRecognitionText merge stable and unstable words`() {
        val state = CaptureLiveState(
            confirmedTranscript = "Prepare weekly ledger",
            partialTranscript = "for Monday review"
        )
        assertEquals("Prepare weekly ledger for Monday review", state.fullTranscript)

        val bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("Send invoice"))
            putStringArrayList("android.speech.extra.UNSTABLE_TEXT", arrayListOf("to client today"))
        }
        assertEquals("Send invoice to client today", extractRecognitionText(bundle))
    }

    @Test
    fun `compactWavForSpeechAi downsamples and trims silence for fast upload`() {
        // 1 second of speech (16,000 samples) followed by 2 seconds of silence (32,000 samples)
        val totalSamples = 48_000
        val pcmByteCount = totalSamples * 2
        val header = MicrophoneAudioRecorder.buildWavHeader(pcmByteCount.toLong(), 16_000)
        val rawWav = ByteArray(44 + pcmByteCount)
        System.arraycopy(header, 0, rawWav, 0, 44)
        val pcmBuf = ByteBuffer.wrap(rawWav, 44, pcmByteCount).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 16_000) {
            pcmBuf.putShort(2500.toShort())
        }
        for (i in 16_000 until totalSamples) {
            pcmBuf.putShort(0.toShort())
        }

        val compacted = AiOrganizerService.compactWavForSpeechAi(rawWav)
        // Compacted 8kHz silence-trimmed WAV should be significantly smaller than 96KB raw WAV
        assertTrue("Expected compacted WAV (${compacted.size}) < ${rawWav.size / 2}", compacted.size < rawWav.size / 2)
        val outHeader = ByteBuffer.wrap(compacted, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        outHeader.position(24)
        assertEquals(8_000, outHeader.int) // 8kHz sample rate
    }
}
