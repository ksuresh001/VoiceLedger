package com.example.service

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioRecordingServiceTest {

    @Test
    fun `buildWavHeader writes valid 16kHz mono PCM RIFF header`() {
        val pcmBytes = 32_000L
        val header = MicrophoneAudioRecorder.buildWavHeader(totalAudioLen = pcmBytes, sampleRateHz = 16_000)
        assertEquals(44, header.size)

        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { buf.get(it) }
        assertEquals("RIFF", String(riff, Charsets.US_ASCII))
        assertEquals((pcmBytes + 36L).toInt(), buf.int)

        val wave = ByteArray(4).also { buf.get(it) }
        assertEquals("WAVE", String(wave, Charsets.US_ASCII))

        val fmt = ByteArray(4).also { buf.get(it) }
        assertEquals("fmt ", String(fmt, Charsets.US_ASCII))
        assertEquals(16, buf.int)
        assertEquals(1.toShort(), buf.short) // PCM
        assertEquals(1.toShort(), buf.short) // Mono
        assertEquals(16_000, buf.int)
        assertEquals(32_000, buf.int) // byteRate
        assertEquals(2.toShort(), buf.short) // blockAlign
        assertEquals(16.toShort(), buf.short) // bitsPerSample

        val data = ByteArray(4).also { buf.get(it) }
        assertEquals("data", String(data, Charsets.US_ASCII))
        assertEquals(pcmBytes.toInt(), buf.int)
    }

    @Test
    fun `computeNormalizedRms distinguishes silence from speech signal`() {
        val silence = ShortArray(256) { 0 }
        val (silenceAmp, silenceDetected) = MicrophoneAudioRecorder.computeNormalizedRms(silence, silence.size)
        assertEquals(0f, silenceAmp, 0.0001f)
        assertFalse(silenceDetected)

        val loud = ShortArray(256) { 6000 }
        val (loudAmp, loudDetected) = MicrophoneAudioRecorder.computeNormalizedRms(loud, loud.size)
        assertEquals(1.0f, loudAmp, 0.001f)
        assertTrue(loudDetected)
    }

    @Test
    fun `permission helper detects granted and denied states`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = Shadows.shadowOf(app)

        shadowApp.denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(MicrophoneAudioRecorder.hasPermission(app))
        assertEquals(
            MicrophonePermissionStatus.DENIED,
            MicrophoneAudioRecorder.resolvePermissionStatus(app, hasRequestedBefore = false)
        )

        val recorder = MicrophoneAudioRecorder(app, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        assertFalse(recorder.startRecording())
        assertTrue(recorder.state.value.errorMessage?.contains("microphone", ignoreCase = true) == true)

        shadowApp.grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(MicrophoneAudioRecorder.hasPermission(app))
        assertEquals(
            MicrophonePermissionStatus.GRANTED,
            MicrophoneAudioRecorder.resolvePermissionStatus(app, hasRequestedBefore = true)
        )
    }
}
