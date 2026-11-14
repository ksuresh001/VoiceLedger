package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ui.auth.findActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

enum class MicrophonePermissionStatus {
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED
}

data class AudioRecordingConfig(
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    val audioEncoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    val outputSubdir: String = "audio_notes"
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val WAV_HEADER_SIZE = 44
    }
}

data class MicrophoneRecordingState(
    val isRecording: Boolean = false,
    val elapsedSec: Int = 0,
    val amplitude: Float = 0f,
    val hasDetectedSignal: Boolean = false,
    val outputFileName: String = "",
    val statusMessage: String = "",
    val errorMessage: String? = null
)

data class AudioRecordingResult(
    val file: File,
    val durationSec: Int,
    val hasDetectedSignal: Boolean
)

/**
 * Reusable service/helper managing Android microphone permission checks, hardware availability,
 * AudioRecord PCM capture on Dispatchers.IO, live amplitude metering, and WAV file output.
 */
class MicrophoneAudioRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: AudioRecordingConfig = AudioRecordingConfig()
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(MicrophoneRecordingState())
    val state: StateFlow<MicrophoneRecordingState> = _state.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var timerJob: Job? = null
    private var currentFile: File? = null
    @Volatile
    private var isRecordingFlag = false
    private var recordingStartMs = 0L

    fun audioDirectory(): File {
        val dir = File(appContext.filesDir, config.outputSubdir)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getAudioFile(fileName: String): File? {
        if (fileName.isBlank()) return null
        val file = File(audioDirectory(), fileName)
        if (!file.exists()) return null
        val minBytes = if (file.extension.equals("wav", ignoreCase = true)) {
            AudioRecordingConfig.WAV_HEADER_SIZE.toLong() + 1L
        } else {
            1L
        }
        return if (file.length() >= minBytes) file else null
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        filePrefix: String = "vn",
        onPcmChunk: ((ByteArray) -> Unit)? = null
    ): Boolean {
        cancelRecording()

        if (!hasPermission(appContext)) {
            _state.value = MicrophoneRecordingState(
                isRecording = false,
                statusMessage = "Microphone permission required.",
                errorMessage = "Please grant microphone access before recording audio."
            )
            return false
        }

        val minBuf = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            config.channelConfig,
            config.audioEncoding
        ).let { if (it <= 0) 4096 else maxOf(it * 2, 4096) }

        val recorder = createAudioRecord(minBuf)
        if (recorder == null) {
            _state.value = MicrophoneRecordingState(
                isRecording = false,
                statusMessage = "Microphone unavailable.",
                errorMessage = "Microphone is busy or unavailable on this device."
            )
            return false
        }

        val fileName = "${filePrefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.wav"
        val outFile = File(audioDirectory(), fileName)

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord.startRecording failed", e)
            runCatching { recorder.release() }
            _state.value = MicrophoneRecordingState(
                isRecording = false,
                statusMessage = "Could not start recording.",
                errorMessage = e.localizedMessage ?: "Failed to access device microphone."
            )
            return false
        }

        audioRecord = recorder
        currentFile = outFile
        isRecordingFlag = true
        recordingStartMs = System.currentTimeMillis()

        _state.value = MicrophoneRecordingState(
            isRecording = true,
            elapsedSec = 0,
            amplitude = 0f,
            hasDetectedSignal = false,
            outputFileName = fileName,
            statusMessage = "Recording audio from microphone...",
            errorMessage = null
        )

        recordJob = scope.launch(Dispatchers.IO) {
            writePcmToWav(recorder, outFile, minBuf, onPcmChunk)
        }

        timerJob = scope.launch {
            while (isActive && isRecordingFlag) {
                delay(100L)
                val elapsed = ((System.currentTimeMillis() - recordingStartMs) / 1000L)
                    .toInt()
                    .coerceAtLeast(0)
                _state.value = _state.value.copy(elapsedSec = elapsed)
            }
        }

        return true
    }

    suspend fun stopRecording(): AudioRecordingResult? {
        isRecordingFlag = false
        timerJob?.cancel()
        timerJob = null

        runCatching { audioRecord?.stop() }
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(1_500L) {
                recordJob?.join()
            }
        }
        recordJob = null
        runCatching { audioRecord?.release() }
        audioRecord = null

        val file = currentFile
        val elapsedFinal = if (recordingStartMs > 0L) {
            ((System.currentTimeMillis() - recordingStartMs) / 1000L)
                .toInt()
                .coerceAtLeast(_state.value.elapsedSec)
        } else {
            _state.value.elapsedSec
        }

        val hasSignal = _state.value.hasDetectedSignal
        val validFile = file != null &&
            file.exists() &&
            file.length() > AudioRecordingConfig.WAV_HEADER_SIZE.toLong()

        if (!validFile) {
            file?.let { if (it.exists()) it.delete() }
            currentFile = null
        }

        _state.value = _state.value.copy(
            isRecording = false,
            elapsedSec = elapsedFinal,
            amplitude = 0f,
            outputFileName = if (validFile) file!!.name else "",
            statusMessage = if (validFile) "Recording saved." else "No audio captured."
        )

        return if (validFile) {
            AudioRecordingResult(
                file = file!!,
                durationSec = elapsedFinal,
                hasDetectedSignal = hasSignal
            )
        } else {
            null
        }
    }

    fun cancelRecording() {
        isRecordingFlag = false
        timerJob?.cancel()
        timerJob = null
        recordJob?.cancel()
        recordJob = null

        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null

        currentFile?.let { file ->
            if (file.exists()) file.delete()
        }
        currentFile = null
        recordingStartMs = 0L
        _state.value = MicrophoneRecordingState()
    }

    fun release() {
        cancelRecording()
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferSizeBytes: Int): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )
        for (source in sources) {
            try {
                val format = AudioFormat.Builder()
                    .setEncoding(config.audioEncoding)
                    .setSampleRate(config.sampleRateHz)
                    .setChannelMask(config.channelConfig)
                    .build()
                val builder = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSizeBytes)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    builder.setPrivacySensitive(false)
                }
                val ar = builder.build()
                if (ar.state == AudioRecord.STATE_INITIALIZED) {
                    return ar
                }
                ar.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord.Builder init failed for source=$source: ${e.message}")
                try {
                    val fallbackAr = AudioRecord(
                        source,
                        config.sampleRateHz,
                        config.channelConfig,
                        config.audioEncoding,
                        bufferSizeBytes
                    )
                    if (fallbackAr.state == AudioRecord.STATE_INITIALIZED) {
                        return fallbackAr
                    }
                    fallbackAr.release()
                } catch (inner: Exception) {
                    Log.w(TAG, "Fallback AudioRecord init failed for source=$source: ${inner.message}")
                }
            }
        }
        return null
    }

    private fun writePcmToWav(
        recorder: AudioRecord,
        outFile: File,
        bufferSizeBytes: Int,
        onPcmChunk: ((ByteArray) -> Unit)?
    ) {
        val shortCount = (bufferSizeBytes / 2).coerceAtLeast(1024)
        val shortBuffer = ShortArray(shortCount)
        val byteBuffer = ByteBuffer.allocate(shortCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        var totalPcmBytes = 0L

        try {
            FileOutputStream(outFile).use { fos ->
                fos.write(ByteArray(AudioRecordingConfig.WAV_HEADER_SIZE))

                while (isRecordingFlag) {
                    val readShorts = recorder.read(shortBuffer, 0, shortBuffer.size)
                    if (readShorts > 0) {
                        byteBuffer.clear()
                        for (i in 0 until readShorts) {
                            byteBuffer.putShort(shortBuffer[i])
                        }
                        val chunkBytes = byteBuffer.array().copyOf(readShorts * 2)
                        fos.write(chunkBytes)
                        totalPcmBytes += chunkBytes.size

                        onPcmChunk?.invoke(chunkBytes)

                        val (normalizedAmp, signalDetected) = computeNormalizedRms(shortBuffer, readShorts)
                        val prevDetected = _state.value.hasDetectedSignal
                        _state.value = _state.value.copy(
                            amplitude = normalizedAmp,
                            hasDetectedSignal = prevDetected || signalDetected
                        )
                    } else if (readShorts < 0) {
                        Log.w(TAG, "AudioRecord.read returned error code: $readShorts")
                        break
                    }
                }
                fos.flush()
            }

            if (outFile.exists() && totalPcmBytes > 0L) {
                val header = buildWavHeader(totalPcmBytes, config.sampleRateHz)
                RandomAccessFile(outFile, "rw").use { raf ->
                    raf.seek(0)
                    raf.write(header)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing WAV audio", e)
            _state.value = _state.value.copy(
                errorMessage = e.localizedMessage ?: "Failed writing audio recording."
            )
        }
    }

    companion object {
        private const val TAG = "MicrophoneAudioRecorder"

        fun hasPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun hasMicrophoneHardware(context: Context): Boolean {
            return context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        }

        fun resolvePermissionStatus(
            context: Context,
            hasRequestedBefore: Boolean
        ): MicrophonePermissionStatus {
            if (hasPermission(context)) return MicrophonePermissionStatus.GRANTED
            val activity = context.findActivity()
            if (!hasRequestedBefore || activity == null) {
                return MicrophonePermissionStatus.DENIED
            }
            val shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO
            )
            return if (shouldShowRationale) {
                MicrophonePermissionStatus.DENIED
            } else {
                MicrophonePermissionStatus.PERMANENTLY_DENIED
            }
        }

        fun openAppSettings(context: Context) {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).apply {
                if (context.findActivity() == null) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            runCatching { context.startActivity(intent) }
        }

        internal fun computeNormalizedRms(samples: ShortArray, count: Int): Pair<Float, Boolean> {
            if (count <= 0) return 0f to false
            var sumSquares = 0.0
            var peakAbs = 0
            val safeCount = count.coerceAtMost(samples.size)
            for (i in 0 until safeCount) {
                val sample = samples[i]
                val absSample = abs(sample.toInt())
                if (absSample > peakAbs) peakAbs = absSample
                sumSquares += sample.toDouble() * sample.toDouble()
            }
            val rms = sqrt(sumSquares / safeCount)
            val normalizedAmp = ((rms / 6000.0).toFloat()).coerceIn(0f, 1f)
            return normalizedAmp to (peakAbs > 220)
        }

        internal fun buildWavHeader(
            totalAudioLen: Long,
            sampleRateHz: Int = AudioRecordingConfig.DEFAULT_SAMPLE_RATE_HZ
        ): ByteArray {
            val totalDataLen = totalAudioLen + 36L
            val byteRate = sampleRateHz * 1 * 16 / 8
            return ByteBuffer.allocate(AudioRecordingConfig.WAV_HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray(Charsets.US_ASCII))
                    putInt(totalDataLen.toInt())
                    put("WAVE".toByteArray(Charsets.US_ASCII))
                    put("fmt ".toByteArray(Charsets.US_ASCII))
                    putInt(16) // PCM subchunk size
                    putShort(1.toShort()) // AudioFormat = 1 (PCM)
                    putShort(1.toShort()) // NumChannels = 1 (Mono)
                    putInt(sampleRateHz)
                    putInt(byteRate)
                    putShort(2.toShort()) // BlockAlign = NumChannels * BitsPerSample / 8
                    putShort(16.toShort()) // BitsPerSample = 16
                    put("data".toByteArray(Charsets.US_ASCII))
                    putInt(totalAudioLen.toInt())
                }
                .array()
        }
    }
}
