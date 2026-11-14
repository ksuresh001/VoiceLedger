package com.example.service

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class OrganizedNoteResult(
    val title: String,
    val transcript: String,
    val summary: String,
    val category: String,
    val tags: List<String>,
    val keywords: List<String>,
    val actionItem: String,
    val priority: String,
    val suggestedDueHours: Int
)

class AiOrganizerService {

    private val noteSchema = Schema.obj(
        properties = mapOf(
            "title" to Schema.string(description = "Concise 3 to 8 word actionable headline for the voice note"),
            "transcript" to Schema.string(description = "Clean, punctuated full text transcript of the spoken note"),
            "summary" to Schema.string(description = "1 to 2 sentence executive summary of the voice note"),
            "category" to Schema.string(description = "Best matching category such as Official, Personal, Client, Finance, Health, or Ideas"),
            "tags" to Schema.array(
                items = Schema.string(),
                description = "Up to 6 concise topic tags in Title Case"
            ),
            "keywords" to Schema.array(
                items = Schema.string(),
                description = "Up to 10 lowercase searchable keywords for historical retrieval"
            ),
            "actionItem" to Schema.string(description = "Clear concrete next action needed to resolve and close this item"),
            "priority" to Schema.enumeration(
                values = listOf("high", "medium", "low"),
                description = "Task priority inferred from urgency cues"
            ),
            "suggestedDueHours" to Schema.integer(description = "Hours from now until suggested deadline (0 if no deadline implied)")
        )
    )

    private val structuredConfig by lazy {
        generationConfig {
            responseMimeType = "application/json"
            responseSchema = noteSchema
        }
    }

    private val fastStructuredModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = structuredConfig
        )
    }

    private val defaultStructuredModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = structuredConfig
        )
    }

    private val fastTranscriptionModel by lazy {
        Firebase.ai.generativeModel(modelName = "gemini-3.5-flash-lite")
    }

    private val defaultTranscriptionModel by lazy {
        Firebase.ai.generativeModel(modelName = "gemini-3.8-flash")
    }

    suspend fun organizeFromTranscript(
        transcript: String,
        preferredCategory: String = ""
    ): Result<OrganizedNoteResult> {
        val cleanText = transcript.trim()
        if (cleanText.isBlank()) {
            return Result.failure(IllegalArgumentException("Transcript is empty."))
        }
        val prompt = buildString {
            appendLine("You are organizing a spoken voice note into a structured task ledger.")
            if (preferredCategory.isNotBlank()) {
                appendLine("Preferred user category hint (use if appropriate): $preferredCategory")
            }
            appendLine("Categorize clearly (Official, Personal, Client, Finance, Health, Ideas, or specific topic),")
            appendLine("keep the full transcript faithful and punctuated, generate AI tags and searchable keywords,")
            appendLine("extract the concrete next action required to close the item, and infer priority & deadline hours.")
            appendLine()
            appendLine("Spoken note transcript:")
            appendLine(cleanText)
        }

        return try {
            val jsonText = try {
                withTimeout(20_000L) {
                    fastStructuredModel.generateContent(prompt).text
                }
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                Log.w(TAG, "Fast structured model timed out; trying default model")
                withTimeout(35_000L) {
                    defaultStructuredModel.generateContent(prompt).text
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fast structured model failed (${e.message}); trying default model")
                withTimeout(35_000L) {
                    defaultStructuredModel.generateContent(prompt).text
                }
            }

            if (!jsonText.isNullOrBlank()) {
                Result.success(parseResult(jsonText, fallbackTranscript = cleanText))
            } else {
                Result.success(buildBasicOrganizedResult(cleanText, preferredCategory))
            }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            Log.w(TAG, "AI organization timed out; using smart local organization to preserve transcript", e)
            Result.success(buildBasicOrganizedResult(cleanText, preferredCategory))
        } catch (e: Exception) {
            Log.w(TAG, "AI organization unavailable (${e.message}); using smart local organization", e)
            Result.success(buildBasicOrganizedResult(cleanText, preferredCategory))
        }
    }

    suspend fun transcribeAndOrganizeAudio(
        audioFile: File,
        existingTranscript: String = "",
        preferredCategory: String = ""
    ): Result<OrganizedNoteResult> {
        val cleanExisting = existingTranscript.trim()
        val minSize = if (audioFile.extension.equals("wav", ignoreCase = true)) 45L else 1L
        if (!audioFile.exists() || audioFile.length() < minSize) {
            return if (cleanExisting.isNotBlank()) {
                organizeFromTranscript(cleanExisting, preferredCategory)
            } else {
                Result.failure(IllegalArgumentException("No audio recording available to transcribe."))
            }
        }
        val maxBytes = 18 * 1024 * 1024L
        if (audioFile.length() > maxBytes) {
            return if (cleanExisting.isNotBlank()) {
                organizeFromTranscript(cleanExisting, preferredCategory)
            } else {
                Result.failure(IllegalArgumentException("Audio clip exceeds 18MB inline limit."))
            }
        }

        // Prepare a compact, gain-normalized, silence-trimmed audio payload so mobile 4G uploads fast
        val preparedPayload = withContext(Dispatchers.Default) {
            prepareAudioPayloadForAi(audioFile)
        } ?: return if (cleanExisting.isNotBlank()) {
            organizeFromTranscript(cleanExisting, preferredCategory)
        } else {
            Result.failure(IllegalStateException("Recorded audio file is empty."))
        }

        // Pass 1: Fast single-pass multimodal transcription + structured organization
        val singlePassResult = singlePassAudioOrganize(
            audioBytes = preparedPayload.first,
            mimeType = preparedPayload.second,
            preferredCategory = preferredCategory
        )
        if (singlePassResult != null && singlePassResult.transcript.isNotBlank()) {
            return Result.success(singlePassResult)
        }

        // Pass 2: Streaming verbatim transcription fallback + smart organization
        val transcriptResult = transcribeAudioBytes(
            audioBytes = preparedPayload.first,
            mimeType = preparedPayload.second,
            existingTranscript = cleanExisting
        )
        val verbatimTranscript = transcriptResult.getOrElse { err ->
            if (cleanExisting.isNotBlank()) {
                cleanExisting
            } else {
                return Result.failure(err)
            }
        }

        return organizeFromTranscript(verbatimTranscript, preferredCategory)
    }

    private suspend fun singlePassAudioOrganize(
        audioBytes: ByteArray,
        mimeType: String,
        preferredCategory: String
    ): OrganizedNoteResult? {
        val prompt = buildString {
            appendLine("Listen carefully to this voice recording.")
            appendLine("1. Transcribe every spoken word verbatim into 'transcript' with proper punctuation.")
            appendLine("2. If the recording contains only silence and no speech at all, set 'transcript' to '$NO_SPEECH_MARKER'.")
            if (preferredCategory.isNotBlank()) {
                appendLine("3. Preferred category hint: $preferredCategory")
            }
            appendLine("4. Populate title, summary, category, tags, keywords, actionItem, priority, and suggestedDueHours.")
        }
        return try {
            val response = withTimeout(35_000L) {
                fastStructuredModel.generateContent(
                    content {
                        inlineData(audioBytes, mimeType)
                        text(prompt)
                    }
                )
            }
            val jsonText = response.text?.trim().orEmpty()
            if (jsonText.isBlank()) return null
            val parsed = parseResult(jsonText, fallbackTranscript = "")
            val t = parsed.transcript.trim()
            if (t.isBlank() || t.equals(NO_SPEECH_MARKER, ignoreCase = true) || t.contains(NO_SPEECH_MARKER)) {
                null
            } else {
                parsed
            }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
            Log.w(TAG, "Single-pass audio organize timed out; falling back to streaming transcription")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Single-pass audio organize failed (${e.message}); falling back to streaming transcription")
            null
        }
    }

    suspend fun transcribeAudioOnly(
        audioFile: File,
        existingTranscript: String = ""
    ): Result<String> {
        val prepared = withContext(Dispatchers.Default) {
            prepareAudioPayloadForAi(audioFile)
        } ?: return Result.failure(IllegalStateException("Recorded audio file is empty."))

        return transcribeAudioBytes(
            audioBytes = prepared.first,
            mimeType = prepared.second,
            existingTranscript = existingTranscript
        )
    }

    private suspend fun transcribeAudioBytes(
        audioBytes: ByteArray,
        mimeType: String,
        existingTranscript: String
    ): Result<String> {
        if (audioBytes.isEmpty()) {
            return Result.failure(IllegalStateException("Recorded audio file is empty."))
        }
        val promptText = buildString {
            appendLine("Transcribe every spoken word in this audio recording accurately and verbatim, with proper punctuation and capitalization.")
            if (existingTranscript.isNotBlank()) {
                appendLine("Partial on-device recognition hint (verify against the audio and include anything missing): $existingTranscript")
            }
            appendLine("Return ONLY the spoken transcript text with no markdown, no labels, and no commentary.")
            appendLine("If the recording contains only silence or no intelligible speech, output exactly: $NO_SPEECH_MARKER")
        }

        val accumulated = StringBuilder()
        return try {
            try {
                withTimeout(40_000L) {
                    fastTranscriptionModel.generateContentStream(
                        content {
                            inlineData(audioBytes, mimeType)
                            text(promptText)
                        }
                    ).collect { chunk ->
                        val part = chunk.text.orEmpty()
                        if (part.isNotEmpty()) {
                            accumulated.append(part)
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                if (accumulated.isBlank()) {
                    Log.w(TAG, "Fast streaming transcription timed out; trying default model")
                    withTimeout(45_000L) {
                        defaultTranscriptionModel.generateContentStream(
                            content {
                                inlineData(audioBytes, mimeType)
                                text(promptText)
                            }
                        ).collect { chunk ->
                            val part = chunk.text.orEmpty()
                            if (part.isNotEmpty()) {
                                accumulated.append(part)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (accumulated.isBlank()) {
                    Log.w(TAG, "Fast streaming transcription failed (${e.message}); trying default model")
                    withTimeout(45_000L) {
                        val fallbackResp = defaultTranscriptionModel.generateContent(
                            content {
                                inlineData(audioBytes, mimeType)
                                text(promptText)
                            }
                        )
                        accumulated.append(fallbackResp.text.orEmpty())
                    }
                }
            }

            val raw = accumulated.toString().trim()
            if (raw.isBlank() || raw.equals(NO_SPEECH_MARKER, ignoreCase = true) || raw.contains(NO_SPEECH_MARKER)) {
                if (existingTranscript.isNotBlank()) {
                    Result.success(existingTranscript.trim())
                } else {
                    Result.failure(
                        IllegalStateException(
                            "No spoken words were detected in the recording. Please speak closer to the microphone or use Text Mode."
                        )
                    )
                }
            } else {
                Result.success(raw)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI audio transcription timed out", e)
            val partial = accumulated.toString().trim()
            if (partial.isNotBlank() && !partial.contains(NO_SPEECH_MARKER)) {
                Result.success(partial)
            } else if (existingTranscript.isNotBlank()) {
                Result.success(existingTranscript.trim())
            } else {
                Result.failure(
                    Exception("Network connection was too slow to upload audio. Please check your connection or tap Retry.")
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI audio transcription failed", e)
            val partial = accumulated.toString().trim()
            if (partial.isNotBlank() && !partial.contains(NO_SPEECH_MARKER)) {
                Result.success(partial)
            } else if (existingTranscript.isNotBlank()) {
                Result.success(existingTranscript.trim())
            } else {
                Result.failure(Exception(e.localizedMessage ?: "Audio transcription failed."))
            }
        }
    }

    internal fun buildBasicOrganizedResult(
        transcript: String,
        preferredCategory: String
    ): OrganizedNoteResult {
        val rawClean = transcript.trim()
        val formattedTranscript = if (rawClean.isNotEmpty()) {
            val capitalized = rawClean.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            if (capitalized.last() in listOf('.', '!', '?')) capitalized else "$capitalized."
        } else {
            ""
        }

        val words = rawClean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val headline = words.take(7).joinToString(" ")
            .trimEnd('.', ',', ';', ':')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .ifBlank { "Voice Note" }

        val lower = rawClean.lowercase()
        val inferredCategory = preferredCategory.ifBlank {
            when {
                lower.containsAny("invoice", "budget", "payment", "tax", "expense", "salary", "cost", "bank") -> "Finance"
                lower.containsAny("client", "customer", "proposal", "contract", "vendor", "delivery") -> "Client"
                lower.containsAny("doctor", "appointment", "medicine", "workout", "gym", "health", "clinic") -> "Health"
                lower.containsAny("idea", "brainstorm", "concept", "design", "prototype", "feature") -> "Ideas"
                lower.containsAny("meeting", "report", "deadline", "project", "team", "review", "office", "approve") -> "Official"
                else -> "Personal"
            }
        }

        val priority = when {
            lower.containsAny("urgent", "asap", "immediately", "critical", "emergency", "right away", "high priority") -> "high"
            lower.containsAny("whenever", "low priority", "someday", "no rush", "optional") -> "low"
            else -> "medium"
        }

        val suggestedDueHours = when {
            lower.contains("in 1 hour") || lower.contains("within an hour") -> 1
            lower.contains("in 2 hours") -> 2
            lower.contains("in 3 hours") -> 3
            lower.contains("today") || lower.contains("tonight") || lower.contains("this evening") -> 8
            lower.contains("tomorrow") -> 24
            lower.contains("this week") || lower.contains("friday") -> 72
            lower.contains("next week") -> 168
            else -> 0
        }

        val keywords = words
            .map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
            .filter { it.length >= 4 && it !in STOP_WORDS }
            .distinct()
            .take(8)

        val tags = buildList {
            add(inferredCategory)
            if (priority == "high") add("Urgent")
            if (suggestedDueHours > 0) add("Time-Sensitive")
            keywords.take(3).forEach { kw ->
                add(kw.replaceFirstChar { it.titlecase() })
            }
        }.distinct().take(6)

        val actionItem = extractActionSentence(formattedTranscript, headline)

        return OrganizedNoteResult(
            title = headline,
            transcript = formattedTranscript.ifBlank { rawClean },
            summary = formattedTranscript.ifBlank { rawClean }.take(220),
            category = inferredCategory,
            tags = tags,
            keywords = keywords,
            actionItem = actionItem,
            priority = priority,
            suggestedDueHours = suggestedDueHours
        )
    }

    private fun extractActionSentence(transcript: String, fallbackHeadline: String): String {
        val sentences = transcript.split(Regex("[.!?]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val actionVerbs = listOf(
            "call", "send", "email", "review", "schedule", "follow up", "pay", "submit",
            "prepare", "check", "update", "confirm", "complete", "finish", "share", "book", "buy", "meet"
        )
        val matched = sentences.firstOrNull { sentence ->
            val l = sentence.lowercase()
            actionVerbs.any { verb -> l.contains(verb) }
        }
        return (matched ?: sentences.firstOrNull() ?: fallbackHeadline).take(200)
    }

    private fun String.containsAny(vararg needles: String): Boolean {
        return needles.any { this.contains(it) }
    }

    internal fun parseResult(jsonText: String, fallbackTranscript: String): OrganizedNoteResult {
        val obj = JSONObject(jsonText)
        val tagsArr = obj.optJSONArray("tags")
        val tags = buildList {
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) {
                    val v = tagsArr.optString(i).trim()
                    if (v.isNotEmpty()) add(v)
                }
            }
        }.distinct().take(6)

        val kwArr = obj.optJSONArray("keywords")
        val keywords = buildList {
            if (kwArr != null) {
                for (i in 0 until kwArr.length()) {
                    val v = kwArr.optString(i).trim().lowercase()
                    if (v.isNotEmpty()) add(v)
                }
            }
        }.distinct().take(10)

        val rawPriority = obj.optString("priority", "medium").lowercase()
        val priority = if (rawPriority in listOf("high", "medium", "low")) rawPriority else "medium"
        val parsedTranscript = obj.optString("transcript", fallbackTranscript).trim()

        return OrganizedNoteResult(
            title = obj.optString("title", "Voice Note").trim().ifEmpty { "Voice Note" },
            transcript = if (parsedTranscript.isNotEmpty()) parsedTranscript else fallbackTranscript,
            summary = obj.optString("summary", "").trim(),
            category = obj.optString("category", "Personal").trim().ifEmpty { "Personal" },
            tags = tags,
            keywords = keywords,
            actionItem = obj.optString("actionItem", "").trim(),
            priority = priority,
            suggestedDueHours = obj.optInt("suggestedDueHours", 0).coerceAtLeast(0)
        )
    }

    companion object {
        private const val TAG = "AiOrganizerService"
        private const val NO_SPEECH_MARKER = "[NO_SPEECH_DETECTED]"
        private val STOP_WORDS = setOf(
            "that", "this", "with", "from", "have", "will", "your", "they",
            "about", "there", "their", "would", "could", "should", "please", "need"
        )

        fun resolveAudioMimeType(file: File): String {
            return when (file.extension.lowercase()) {
                "wav" -> "audio/wav"
                "m4a" -> "audio/m4a"
                "aac" -> "audio/aac"
                "mp3" -> "audio/mp3"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                "mp4" -> "audio/mp4"
                else -> "audio/wav"
            }
        }

        /**
         * Reads an audio file and optimizes 16kHz PCM WAV files by normalizing speech gain,
         * collapsing long silent stretches, and downsampling to 8kHz mono WAV so uploads over
         * mobile 4G networks complete in seconds rather than timing out.
         */
        internal fun prepareAudioPayloadForAi(audioFile: File): Pair<ByteArray, String>? {
            if (!audioFile.exists()) return null
            val rawBytes = runCatching { audioFile.readBytes() }.getOrNull() ?: return null
            if (rawBytes.isEmpty()) return null

            val mimeType = resolveAudioMimeType(audioFile)
            if (mimeType != "audio/wav" || rawBytes.size <= 44) {
                return rawBytes to mimeType
            }

            return runCatching {
                compactWavForSpeechAi(rawBytes) to "audio/wav"
            }.getOrDefault(rawBytes to mimeType)
        }

        internal fun compactWavForSpeechAi(wavBytes: ByteArray): ByteArray {
            if (wavBytes.size <= 44) return wavBytes
            val pcmByteCount = wavBytes.size - 44
            val sampleCount = pcmByteCount / 2
            if (sampleCount < 160) return wavBytes

            val inBuf = ByteBuffer.wrap(wavBytes, 44, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
            val samples = ShortArray(sampleCount)
            var peakAbs = 0
            for (i in 0 until sampleCount) {
                val s = inBuf.short
                samples[i] = s
                val a = abs(s.toInt())
                if (a > peakAbs) peakAbs = a
            }

            if (peakAbs == 0) return wavBytes

            // Normalize quiet speech up to 8x so peak sits around ~22,000 (out of 32,767)
            val gain = if (peakAbs < 22_000) {
                (22_000f / peakAbs.toFloat()).coerceIn(1.0f, 8.0f)
            } else {
                1.0f
            }

            // Downsample 16kHz -> 8kHz (2:1 average) and collapse long silent gaps (> 300ms)
            val outSampleRate = 8_000
            val halfCount = sampleCount / 2
            val downsampled = ShortArray(halfCount)
            for (i in 0 until halfCount) {
                val s0 = samples[i * 2].toInt()
                val s1 = samples[i * 2 + 1].toInt()
                val avg = ((s0 + s1) / 2f * gain).toInt().coerceIn(-32767, 32767)
                downsampled[i] = avg.toShort()
            }

            // Frame-based silence trimming (20ms frames = 160 samples at 8kHz)
            val frameSize = 160
            val silenceThreshold = 380
            val maxConsecutiveSilentFrames = 12 // Keep up to 240ms of natural pause between words
            val keptSamples = ShortArray(downsampled.size)
            var keptCount = 0
            var silentRunFrames = 0

            var offset = 0
            while (offset < downsampled.size) {
                val end = minOf(offset + frameSize, downsampled.size)
                var framePeak = 0
                for (j in offset until end) {
                    val a = abs(downsampled[j].toInt())
                    if (a > framePeak) framePeak = a
                }
                if (framePeak >= silenceThreshold) {
                    silentRunFrames = 0
                    val len = end - offset
                    System.arraycopy(downsampled, offset, keptSamples, keptCount, len)
                    keptCount += len
                } else {
                    silentRunFrames++
                    if (silentRunFrames <= maxConsecutiveSilentFrames && keptCount > 0) {
                        val len = end - offset
                        System.arraycopy(downsampled, offset, keptSamples, keptCount, len)
                        keptCount += len
                    }
                }
                offset = end
            }

            val finalSamples = if (keptCount >= 800) {
                keptSamples.copyOf(keptCount)
            } else {
                downsampled
            }

            val dataBytesLen = finalSamples.size * 2
            val header = MicrophoneAudioRecorder.buildWavHeader(
                totalAudioLen = dataBytesLen.toLong(),
                sampleRateHz = outSampleRate
            )
            val outBytes = ByteArray(44 + dataBytesLen)
            System.arraycopy(header, 0, outBytes, 0, 44)
            val outBuf = ByteBuffer.wrap(outBytes, 44, dataBytesLen).order(ByteOrder.LITTLE_ENDIAN)
            for (s in finalSamples) {
                outBuf.putShort(s)
            }
            return outBytes
        }
    }
}
