package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

data class CaptureLiveState(
    val isRecording: Boolean = false,
    val elapsedSec: Int = 0,
    val amplitude: Float = 0f,
    val hasDetectedSpeechSignal: Boolean = false,
    val confirmedTranscript: String = "",
    val partialTranscript: String = "",
    val speechStatusMessage: String = "",
    val recordedFileName: String = ""
) {
    val fullTranscript: String
        get() = listOf(confirmedTranscript, partialTranscript)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
}

class VoiceCaptureEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(CaptureLiveState())
    val state: StateFlow<CaptureLiveState> = _state.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var timerJob: Job? = null
    private var pipeWriterJob: Job? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var pipeReadFd: ParcelFileDescriptor? = null
    private var pipeWriteFd: ParcelFileDescriptor? = null
    private var pipeChannel: Channel<ByteArray>? = null

    private var currentFile: File? = null
    @Volatile
    private var isCapturingFlag = false
    private var shouldContinueRecognition = false
    private var captureStartMs = 0L

    fun audioDirectory(): File {
        val dir = File(context.filesDir, "audio_notes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getAudioFile(fileName: String): File? {
        if (fileName.isBlank()) return null
        val file = File(audioDirectory(), fileName)
        if (!file.exists()) return null
        val minBytes = if (file.extension.equals("wav", ignoreCase = true)) WAV_HEADER_SIZE.toLong() + 1L else 1L
        return if (file.length() >= minBytes) file else null
    }

    @SuppressLint("MissingPermission")
    fun startCapture() {
        discardCapture()

        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPermission) {
            _state.value = CaptureLiveState(
                isRecording = false,
                speechStatusMessage = "Microphone permission required to record audio."
            )
            return
        }

        val fileName = "vn_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.wav"
        val outFile = File(audioDirectory(), fileName)

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).let { if (it <= 0) 4096 else maxOf(it * 2, 4096) }

        val recorder = createAudioRecord(minBuf)
        if (recorder == null) {
            _state.value = CaptureLiveState(
                isRecording = false,
                speechStatusMessage = "Microphone is busy or unavailable on this device."
            )
            return
        }

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord startRecording failed", e)
            runCatching { recorder.release() }
            _state.value = CaptureLiveState(
                isRecording = false,
                speechStatusMessage = "Could not start microphone capture."
            )
            return
        }

        audioRecord = recorder
        currentFile = outFile
        isCapturingFlag = true
        captureStartMs = System.currentTimeMillis()

        val canUsePipeSpeech = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isRecognitionAvailable(context)

        _state.value = CaptureLiveState(
            isRecording = true,
            elapsedSec = 0,
            amplitude = 0f,
            hasDetectedSpeechSignal = false,
            confirmedTranscript = "",
            partialTranscript = "",
            speechStatusMessage = if (canUsePipeSpeech) {
                "Listening & recording audio..."
            } else {
                "Recording audio — Gemini AI transcribes on stop"
            },
            recordedFileName = fileName
        )

        if (canUsePipeSpeech) {
            startPipedSpeechRecognizer()
        }

        recordJob = scope.launch(Dispatchers.IO) {
            writePcmToWav(recorder, outFile, minBuf)
        }

        timerJob = scope.launch {
            while (isActive && isCapturingFlag) {
                delay(100L)
                val elapsed = ((System.currentTimeMillis() - captureStartMs) / 1000L).toInt().coerceAtLeast(0)
                _state.value = _state.value.copy(elapsedSec = elapsed)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferSizeBytes: Int): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            try {
                val ar = AudioRecord(
                    source,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeBytes
                )
                if (ar.state == AudioRecord.STATE_INITIALIZED) {
                    return ar
                }
                ar.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord init failed for source=$source: ${e.message}")
            }
        }
        return null
    }

    private suspend fun writePcmToWav(
        recorder: AudioRecord,
        outFile: File,
        bufferSizeBytes: Int
    ) {
        val shortCount = (bufferSizeBytes / 2).coerceAtLeast(1024)
        val shortBuffer = ShortArray(shortCount)
        val byteBuffer = ByteBuffer.allocate(shortCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        var totalPcmBytes = 0L

        try {
            FileOutputStream(outFile).use { fos ->
                // Reserve 44-byte RIFF/WAVE header, updated after capture ends
                fos.write(ByteArray(WAV_HEADER_SIZE))

                while (isCapturingFlag) {
                    val readShorts = recorder.read(shortBuffer, 0, shortBuffer.size)
                    if (readShorts > 0) {
                        var sumSquares = 0.0
                        var peakAbs = 0
                        byteBuffer.clear()
                        for (i in 0 until readShorts) {
                            val sample = shortBuffer[i]
                            val absSample = abs(sample.toInt())
                            if (absSample > peakAbs) peakAbs = absSample
                            sumSquares += sample.toDouble() * sample.toDouble()
                            byteBuffer.putShort(sample)
                        }
                        val chunkBytes = byteBuffer.array().copyOf(readShorts * 2)
                        fos.write(chunkBytes)
                        totalPcmBytes += chunkBytes.size

                        // Forward PCM bytes to SpeechRecognizer pipe without ever blocking AudioRecord
                        pipeChannel?.trySend(chunkBytes)

                        val rms = sqrt(sumSquares / readShorts)
                        val normalizedAmp = ((rms / 6000.0).toFloat()).coerceIn(0f, 1f)
                        val detectedSignal = _state.value.hasDetectedSpeechSignal || peakAbs > 220
                        _state.value = _state.value.copy(
                            amplitude = normalizedAmp,
                            hasDetectedSpeechSignal = detectedSignal
                        )
                    } else if (readShorts < 0) {
                        Log.w(TAG, "AudioRecord.read returned error code: $readShorts")
                        break
                    }
                }
                fos.flush()
            }

            if (outFile.exists() && totalPcmBytes > 0L) {
                writeWavHeader(outFile, totalPcmBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing WAV audio", e)
        }
    }

    private fun writeWavHeader(wavFile: File, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36L
        val byteRate = SAMPLE_RATE_HZ * 1 * 16 / 8
        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen.toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM subchunk size
            putShort(1.toShort()) // AudioFormat = 1 (PCM)
            putShort(1.toShort()) // NumChannels = 1 (Mono)
            putInt(SAMPLE_RATE_HZ)
            putInt(byteRate)
            putShort(2.toShort()) // BlockAlign = NumChannels * BitsPerSample/8
            putShort(16.toShort()) // BitsPerSample = 16
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(totalAudioLen.toInt())
        }.array()

        RandomAccessFile(wavFile, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }

    private fun startPipedSpeechRecognizer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            closePipeResources()
            val pipe = ParcelFileDescriptor.createPipe()
            val readFd = pipe[0]
            val writeFd = pipe[1]
            pipeReadFd = readFd
            pipeWriteFd = writeFd

            val channel = Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            pipeChannel = channel
            shouldContinueRecognition = true

            pipeWriterJob = scope.launch(Dispatchers.IO) {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { stream ->
                        for (chunk in channel) {
                            stream.write(chunk)
                        }
                        stream.flush()
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "SpeechRecognizer pipe closed: ${e.message}")
                    shouldContinueRecognition = false
                } finally {
                    pipeWriteFd = null
                }
            }

            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (isCapturingFlag) {
                        _state.value = _state.value.copy(speechStatusMessage = "Listening — live speech & audio capture")
                    }
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    Log.w(TAG, "Live SpeechRecognizer paused (code=$error); continuing audio capture for Gemini AI")
                    shouldContinueRecognition = false
                    closePipeResources()
                    if (isCapturingFlag) {
                        _state.value = _state.value.copy(
                            speechStatusMessage = "Recording voice audio — Gemini AI transcribes on stop"
                        )
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (text.isNotEmpty()) {
                        val prev = _state.value.confirmedTranscript
                        val merged = if (prev.isBlank()) text else "$prev $text"
                        _state.value = _state.value.copy(
                            confirmedTranscript = merged,
                            partialTranscript = ""
                        )
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (partial.isNotEmpty()) {
                        _state.value = _state.value.copy(partialTranscript = partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer = recognizer

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ)
            }
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Piped SpeechRecognizer unavailable, using Gemini AI audio transcription", e)
            shouldContinueRecognition = false
            closePipeResources()
            _state.value = _state.value.copy(
                speechStatusMessage = "Recording voice audio — Gemini AI transcribes on stop"
            )
        }
    }

    private fun closePipeResources() {
        pipeChannel?.close()
        pipeChannel = null
        pipeWriterJob?.cancel()
        pipeWriterJob = null
        runCatching { pipeWriteFd?.close() }
        pipeWriteFd = null
        runCatching { pipeReadFd?.close() }
        pipeReadFd = null
    }

    suspend fun stopCapture(): CaptureLiveState {
        return stopCaptureInternal(keepFile = true)
    }

    fun discardCapture() {
        isCapturingFlag = false
        shouldContinueRecognition = false
        timerJob?.cancel()
        timerJob = null
        recordJob?.cancel()
        recordJob = null
        closePipeResources()

        runCatching { speechRecognizer?.stopListening() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null

        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        currentFile?.let { file ->
            if (file.exists()) file.delete()
        }
        currentFile = null
        _state.value = CaptureLiveState()
    }

    private suspend fun stopCaptureInternal(keepFile: Boolean): CaptureLiveState {
        val wasActiveSpeech = shouldContinueRecognition
        isCapturingFlag = false
        shouldContinueRecognition = false
        timerJob?.cancel()
        timerJob = null

        runCatching { audioRecord?.stop() }
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(1500L) {
                recordJob?.join()
            }
        }
        recordJob = null
        runCatching { audioRecord?.release() }
        audioRecord = null

        // Signal EOF to piped SpeechRecognizer so it can flush final results briefly
        pipeChannel?.close()
        pipeChannel = null
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(300L) {
                pipeWriterJob?.join()
            }
        }
        pipeWriterJob = null
        runCatching { pipeWriteFd?.close() }
        pipeWriteFd = null

        if (wasActiveSpeech) {
            runCatching { speechRecognizer?.stopListening() }
            delay(250L)
        }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        runCatching { pipeReadFd?.close() }
        pipeReadFd = null

        val file = currentFile
        val validFile = keepFile && file != null && file.exists() && file.length() > WAV_HEADER_SIZE.toLong()
        if (!keepFile && file != null && file.exists()) {
            file.delete()
        }
        if (!validFile) {
            currentFile = null
        }

        val elapsedFinal = if (captureStartMs > 0L) {
            ((System.currentTimeMillis() - captureStartMs) / 1000L).toInt().coerceAtLeast(_state.value.elapsedSec)
        } else {
            _state.value.elapsedSec
        }

        val finalState = _state.value.copy(
            isRecording = false,
            elapsedSec = elapsedFinal,
            amplitude = 0f,
            confirmedTranscript = _state.value.fullTranscript,
            partialTranscript = "",
            recordedFileName = if (validFile) file!!.name else ""
        )
        _state.value = finalState
        return finalState
    }

    private companion object {
        const val TAG = "VoiceCaptureEngine"
        const val SAMPLE_RATE_HZ = 16000
        const val WAV_HEADER_SIZE = 44
    }
}

data class AudioPlaybackState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Int = 0,
    val durationMs: Int = 0
)

class VoiceAudioPlayer(
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = _state.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var loadedPath: String? = null

    fun togglePlayback(file: File) {
        if (!file.exists()) return
        if (loadedPath == file.absolutePath && mediaPlayer != null) {
            val player = mediaPlayer!!
            if (player.isPlaying) {
                player.pause()
                progressJob?.cancel()
                _state.value = _state.value.copy(isPlaying = false)
            } else {
                player.start()
                startProgressLoop()
                _state.value = _state.value.copy(isPlaying = true)
            }
            return
        }

        release()
        try {
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    progressJob?.cancel()
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        currentPositionMs = 0
                    )
                    seekTo(0)
                }
                start()
            }
            mediaPlayer = player
            loadedPath = file.absolutePath
            _state.value = AudioPlaybackState(
                isPlaying = true,
                currentPositionMs = 0,
                durationMs = player.duration.coerceAtLeast(1000)
            )
            startProgressLoop()
        } catch (e: Exception) {
            Log.e("VoiceAudioPlayer", "Playback failed", e)
            release()
        }
    }

    fun seekTo(positionMs: Int) {
        val player = mediaPlayer ?: return
        val clamped = positionMs.coerceIn(0, _state.value.durationMs)
        player.seekTo(clamped)
        _state.value = _state.value.copy(currentPositionMs = clamped)
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && mediaPlayer?.isPlaying == true) {
                _state.value = _state.value.copy(
                    currentPositionMs = mediaPlayer?.currentPosition ?: 0
                )
                delay(150L)
            }
        }
    }

    fun release() {
        progressJob?.cancel()
        progressJob = null
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        loadedPath = null
        _state.value = AudioPlaybackState()
    }
}
