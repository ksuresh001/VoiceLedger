package com.example.service

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
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
import java.io.IOException
import java.util.Locale

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
        get() = mergeTranscriptSegments(confirmedTranscript, partialTranscript)
}

internal fun mergeTranscriptSegments(confirmed: String, partial: String): String {
    val c = confirmed.trim()
    val p = partial.trim()
    if (c.isEmpty()) return p
    if (p.isEmpty()) return c
    if (c.endsWith(p, ignoreCase = true)) return c
    if (p.startsWith(c, ignoreCase = true)) return p
    return "$c $p".trim()
}

internal fun extractRecognitionText(bundle: Bundle?): String {
    if (bundle == null) return ""
    val primary = bundle
        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()
    val unstable = bundle
        .getStringArrayList("android.speech.extra.UNSTABLE_TEXT")
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()
    return mergeTranscriptSegments(primary, unstable)
}

class VoiceCaptureEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val audioRecorder = MicrophoneAudioRecorder(context, scope)
    private val _state = MutableStateFlow(CaptureLiveState())
    val state: StateFlow<CaptureLiveState> = _state.asStateFlow()

    private var pipeWriterJob: Job? = null
    private var speechRestartJob: Job? = null
    private var engineTimerJob: Job? = null
    private var speechWatchdogJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var pipeReadFd: ParcelFileDescriptor? = null
    private var pipeWriteFd: ParcelFileDescriptor? = null
    private var pipeChannel: Channel<ByteArray>? = null
    private var finalSpeechResultSignal: CompletableDeferred<Unit>? = null

    @Volatile
    private var isCapturingFlag = false
    @Volatile
    private var shouldContinueRecognition = false
    @Volatile
    private var usePipedMode = false
    private var captureStartMs = 0L

    init {
        scope.launch {
            audioRecorder.state.collect { rec ->
                if (isCapturingFlag && rec.isRecording) {
                    _state.value = _state.value.copy(
                        elapsedSec = maxOf(_state.value.elapsedSec, rec.elapsedSec),
                        amplitude = rec.amplitude,
                        hasDetectedSpeechSignal = _state.value.hasDetectedSpeechSignal || rec.hasDetectedSignal,
                        recordedFileName = rec.outputFileName.ifBlank { _state.value.recordedFileName }
                    )
                }
            }
        }
    }

    fun audioDirectory(): File = audioRecorder.audioDirectory()

    fun getAudioFile(fileName: String): File? = audioRecorder.getAudioFile(fileName)

    fun startCapture() {
        discardCapture()

        if (!MicrophoneAudioRecorder.hasPermission(context)) {
            _state.value = CaptureLiveState(
                isRecording = false,
                speechStatusMessage = "Microphone permission required to record audio."
            )
            return
        }

        isCapturingFlag = true
        shouldContinueRecognition = true
        captureStartMs = System.currentTimeMillis()
        finalSpeechResultSignal = CompletableDeferred()

        // Start a dedicated elapsed-time ticker so timer updates smoothly in all capture modes
        engineTimerJob = scope.launch {
            while (isActive && isCapturingFlag) {
                delay(150L)
                val elapsed = ((System.currentTimeMillis() - captureStartMs) / 1000L)
                    .toInt()
                    .coerceAtLeast(0)
                _state.value = _state.value.copy(elapsedSec = elapsed)
            }
        }

        // On Android 13+ (API 33+), AudioRecord can feed SpeechRecognizer via EXTRA_AUDIO_SOURCE pipe.
        // On Android 10-12 (including Android 11 / API 30), holding AudioRecord in the foreground UI
        // silences SpeechRecognizer's background service. Running direct SpeechRecognizer first on
        // API < 33 guarantees instant live speech-to-text, with AudioRecord as automatic fallback.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            usePipedMode = true
            val started = audioRecorder.startRecording(
                filePrefix = "vn",
                onPcmChunk = { chunkBytes -> pipeChannel?.trySend(chunkBytes) }
            )
            if (started) {
                _state.value = CaptureLiveState(
                    isRecording = true,
                    elapsedSec = 0,
                    speechStatusMessage = "Listening — live speech & audio capture",
                    recordedFileName = audioRecorder.state.value.outputFileName
                )
                scope.launch(Dispatchers.Main.immediate) {
                    startSpeechRecognizerSession(piped = true)
                }
                // Watchdog: if AudioRecord detects speech signal on API 33+ but piped recognizer
                // produces no text within 3 seconds (OEM recognizer ignoring EXTRA_AUDIO_SOURCE),
                // switch to direct SpeechRecognizer.
                speechWatchdogJob = scope.launch(Dispatchers.Main.immediate) {
                    delay(3_200L)
                    if (isCapturingFlag &&
                        usePipedMode &&
                        _state.value.hasDetectedSpeechSignal &&
                        _state.value.fullTranscript.isBlank()
                    ) {
                        Log.i(TAG, "Piped SpeechRecognizer produced no text after speech signal; switching to direct SpeechRecognizer")
                        usePipedMode = false
                        closePipeResources()
                        audioRecorder.stopRecording()
                        startSpeechRecognizerSession(piped = false)
                    }
                }
            } else {
                // If AudioRecord couldn't start, still start direct SpeechRecognizer
                usePipedMode = false
                _state.value = CaptureLiveState(
                    isRecording = true,
                    elapsedSec = 0,
                    speechStatusMessage = "Listening — speaking converts live to text..."
                )
                scope.launch(Dispatchers.Main.immediate) {
                    startSpeechRecognizerSession(piped = false)
                }
            }
        } else {
            usePipedMode = false
            _state.value = CaptureLiveState(
                isRecording = true,
                elapsedSec = 0,
                speechStatusMessage = "Listening — speaking converts live to text..."
            )
            scope.launch(Dispatchers.Main.immediate) {
                startSpeechRecognizerSession(piped = false)
            }
        }
    }

    private fun commitPartialToConfirmed() {
        val current = _state.value
        val merged = mergeTranscriptSegments(current.confirmedTranscript, current.partialTranscript)
        if (merged != current.confirmedTranscript || current.partialTranscript.isNotEmpty()) {
            _state.value = current.copy(
                confirmedTranscript = merged,
                partialTranscript = ""
            )
        }
    }

    private fun startSpeechRecognizerSession(piped: Boolean) {
        if (!isCapturingFlag || !shouldContinueRecognition) return
        try {
            closePipeResources()
            var readFd: ParcelFileDescriptor? = null
            if (piped && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val pipe = ParcelFileDescriptor.createPipe()
                readFd = pipe[0]
                val writeFd = pipe[1]
                pipeReadFd = readFd
                pipeWriteFd = writeFd

                val channel = Channel<ByteArray>(
                    capacity = 64,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
                pipeChannel = channel

                pipeWriterJob = scope.launch(Dispatchers.IO) {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(writeFd).use { stream ->
                            for (chunk in channel) {
                                stream.write(chunk)
                            }
                            stream.flush()
                        }
                    } catch (e: IOException) {
                        Log.d(TAG, "SpeechRecognizer pipe closed: ${e.message}")
                    } finally {
                        pipeWriteFd = null
                    }
                }
            }

            if (speechRecognizer == null) {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer.setRecognitionListener(createRecognitionListener())
                speechRecognizer = recognizer
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
                if (piped && readFd != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(
                        RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                        AudioRecordingConfig.DEFAULT_SAMPLE_RATE_HZ
                    )
                }
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "SpeechRecognizer startListening failed (piped=$piped), activating audio fallback", e)
            handleFatalSpeechError()
        }
    }

    private fun createRecognitionListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (isCapturingFlag) {
                _state.value = _state.value.copy(
                    speechStatusMessage = "Listening — speak clearly into microphone"
                )
            }
        }

        override fun onBeginningOfSpeech() {
            if (isCapturingFlag) {
                _state.value = _state.value.copy(
                    hasDetectedSpeechSignal = true,
                    speechStatusMessage = "Transcribing live speech..."
                )
            }
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (isCapturingFlag && !audioRecorder.state.value.isRecording) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.04f, 1f)
                val detected = _state.value.hasDetectedSpeechSignal || rmsdB > 1.5f
                _state.value = _state.value.copy(
                    amplitude = normalized,
                    hasDetectedSpeechSignal = detected
                )
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            commitPartialToConfirmed()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = extractRecognitionText(partialResults)
            if (partial.isNotEmpty()) {
                _state.value = _state.value.copy(
                    partialTranscript = partial,
                    hasDetectedSpeechSignal = true,
                    speechStatusMessage = "Transcribing live speech..."
                )
            }
        }

        override fun onResults(results: Bundle?) {
            val recognized = extractRecognitionText(results)
            val current = _state.value
            val segmentToCommit = recognized.ifBlank { current.partialTranscript }
            if (segmentToCommit.isNotBlank()) {
                val merged = mergeTranscriptSegments(current.confirmedTranscript, segmentToCommit)
                _state.value = current.copy(
                    confirmedTranscript = merged,
                    partialTranscript = "",
                    hasDetectedSpeechSignal = true
                )
            } else {
                commitPartialToConfirmed()
            }

            if (!isCapturingFlag || !shouldContinueRecognition) {
                finalSpeechResultSignal?.complete(Unit)
                return
            }

            scheduleSpeechRestart(delayMs = 80L)
        }

        override fun onError(error: Int) {
            commitPartialToConfirmed()

            if (!isCapturingFlag || !shouldContinueRecognition) {
                finalSpeechResultSignal?.complete(Unit)
                return
            }

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                11 /* ERROR_SERVER_DISCONNECTED */ -> {
                    scheduleSpeechRestart(delayMs = 90L)
                }

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    scheduleSpeechRestart(delayMs = 350L)
                }

                else -> {
                    Log.w(TAG, "SpeechRecognizer error code=$error (usePipedMode=$usePipedMode)")
                    if (usePipedMode) {
                        // Piped EXTRA_AUDIO_SOURCE failed on this ROM; switch to direct SpeechRecognizer
                        usePipedMode = false
                        closePipeResources()
                        scope.launch(Dispatchers.Main.immediate) {
                            audioRecorder.stopRecording()
                            runCatching { speechRecognizer?.destroy() }
                            speechRecognizer = null
                            startSpeechRecognizerSession(piped = false)
                        }
                    } else {
                        handleFatalSpeechError()
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun scheduleSpeechRestart(delayMs: Long) {
        speechRestartJob?.cancel()
        speechRestartJob = scope.launch(Dispatchers.Main.immediate) {
            closePipeResources()
            delay(delayMs)
            if (isCapturingFlag && shouldContinueRecognition) {
                startSpeechRecognizerSession(piped = usePipedMode)
            }
        }
    }

    private fun handleFatalSpeechError() {
        shouldContinueRecognition = false
        closePipeResources()
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null

        if (isCapturingFlag && !audioRecorder.state.value.isRecording) {
            val started = audioRecorder.startRecording(filePrefix = "vn")
            _state.value = _state.value.copy(
                isRecording = started,
                recordedFileName = audioRecorder.state.value.outputFileName,
                speechStatusMessage = if (started) {
                    "Recording voice audio — AI transcribes on stop"
                } else {
                    "Microphone is busy or unavailable on this device."
                }
            )
        } else if (isCapturingFlag) {
            _state.value = _state.value.copy(
                speechStatusMessage = "Recording voice audio — AI transcribes on stop"
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
        speechWatchdogJob?.cancel()
        speechWatchdogJob = null
        speechRestartJob?.cancel()
        speechRestartJob = null
        engineTimerJob?.cancel()
        engineTimerJob = null
        closePipeResources()
        finalSpeechResultSignal?.complete(Unit)

        scope.launch(Dispatchers.Main.immediate) {
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }

        audioRecorder.cancelRecording()
        _state.value = CaptureLiveState()
    }

    private suspend fun stopCaptureInternal(keepFile: Boolean): CaptureLiveState {
        val wasActiveSpeech = shouldContinueRecognition && speechRecognizer != null
        isCapturingFlag = false
        shouldContinueRecognition = false
        speechWatchdogJob?.cancel()
        speechWatchdogJob = null
        speechRestartJob?.cancel()
        speechRestartJob = null
        engineTimerJob?.cancel()
        engineTimerJob = null

        val recordingResult = if (audioRecorder.state.value.isRecording) {
            audioRecorder.stopRecording()
        } else {
            null
        }

        // Signal EOF to piped SpeechRecognizer so it can flush final results
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
            withContext(Dispatchers.Main.immediate) {
                runCatching { speechRecognizer?.stopListening() }
            }
            withTimeoutOrNull(750L) {
                finalSpeechResultSignal?.await()
            }
        }

        commitPartialToConfirmed()

        withContext(Dispatchers.Main.immediate) {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
        runCatching { pipeReadFd?.close() }
        pipeReadFd = null

        val savedFile = recordingResult?.file
            ?: _state.value.recordedFileName.takeIf { it.isNotBlank() }?.let { getAudioFile(it) }

        if (!keepFile) {
            savedFile?.let { if (it.exists()) it.delete() }
        }

        val elapsedFinal = if (captureStartMs > 0L) {
            ((System.currentTimeMillis() - captureStartMs) / 1000L)
                .toInt()
                .coerceAtLeast(recordingResult?.durationSec ?: _state.value.elapsedSec)
        } else {
            recordingResult?.durationSec ?: _state.value.elapsedSec
        }

        val finalFileName = if (keepFile && savedFile != null && savedFile.exists()) {
            savedFile.name
        } else {
            ""
        }

        val finalTranscript = _state.value.fullTranscript
        val finalState = _state.value.copy(
            isRecording = false,
            elapsedSec = elapsedFinal,
            amplitude = 0f,
            hasDetectedSpeechSignal = _state.value.hasDetectedSpeechSignal ||
                (recordingResult?.hasDetectedSignal == true) ||
                finalTranscript.isNotBlank(),
            confirmedTranscript = finalTranscript,
            partialTranscript = "",
            recordedFileName = finalFileName
        )
        _state.value = finalState
        return finalState
    }

    private companion object {
        const val TAG = "VoiceCaptureEngine"
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
