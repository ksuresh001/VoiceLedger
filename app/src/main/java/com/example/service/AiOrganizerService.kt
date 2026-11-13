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

    private val transcriptionModel by lazy {
        Firebase.ai.generativeModel(modelName = "gemini-3.8-flash")
    }

    private val structuredModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = noteSchema
            }
        )
    }

    suspend fun organizeFromTranscript(
        transcript: String,
        preferredCategory: String = ""
    ): Result<OrganizedNoteResult> {
        val cleanText = transcript.trim()
        if (cleanText.isBlank()) {
            return Result.failure(IllegalArgumentException("Transcript is empty."))
        }
        return try {
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
            val response = withTimeout(60_000L) {
                structuredModel.generateContent(prompt)
            }
            val jsonText = response.text ?: throw IllegalStateException("AI model returned empty response.")
            Result.success(parseResult(jsonText, fallbackTranscript = cleanText))
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI organization timed out", e)
            Result.failure(Exception("AI organization timed out after 60 seconds. You can still save manually."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI organization failed", e)
            Result.failure(Exception(e.localizedMessage ?: "AI organization failed."))
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

        // Phase 1: Dedicated verbatim speech-to-text conversion from audio bytes
        val transcriptResult = transcribeAudioOnly(audioFile, cleanExisting)
        val verbatimTranscript = transcriptResult.getOrElse { err ->
            if (cleanExisting.isNotBlank()) {
                Log.w(TAG, "Audio transcription failed, falling back to live transcript", err)
                cleanExisting
            } else {
                return Result.failure(err)
            }
        }

        // Phase 2: Organize the verified transcript into structured fields; if Phase 2 fails,
        // preserve the verbatim transcript so audio-to-text conversion is never lost.
        return organizeFromTranscript(verbatimTranscript, preferredCategory).fold(
            onSuccess = { organized ->
                Result.success(
                    organized.copy(
                        transcript = organized.transcript.ifBlank { verbatimTranscript }
                    )
                )
            },
            onFailure = { orgErr ->
                Log.w(TAG, "Structured organization failed after transcription; preserving verbatim text", orgErr)
                Result.success(
                    buildBasicOrganizedResult(
                        transcript = verbatimTranscript,
                        preferredCategory = preferredCategory
                    )
                )
            }
        )
    }

    suspend fun transcribeAudioOnly(
        audioFile: File,
        existingTranscript: String = ""
    ): Result<String> {
        return try {
            val audioBytes = withContext(Dispatchers.IO) { audioFile.readBytes() }
            if (audioBytes.isEmpty()) {
                return Result.failure(IllegalStateException("Recorded audio file is empty."))
            }
            val mimeType = resolveAudioMimeType(audioFile)
            val promptText = buildString {
                appendLine("Transcribe every spoken word in this audio recording accurately and verbatim, with proper punctuation and capitalization.")
                if (existingTranscript.isNotBlank()) {
                    appendLine("Partial on-device recognition hint (verify against the audio and include anything missing): $existingTranscript")
                }
                appendLine("Return ONLY the spoken transcript text with no markdown, no labels, and no commentary.")
                appendLine("If the recording contains only silence or no intelligible speech, output exactly: $NO_SPEECH_MARKER")
            }

            val response = withTimeout(45_000L) {
                transcriptionModel.generateContent(
                    content {
                        inlineData(audioBytes, mimeType)
                        text(promptText)
                    }
                )
            }
            val raw = response.text?.trim().orEmpty()
            if (raw.isBlank() || raw.equals(NO_SPEECH_MARKER, ignoreCase = true) || raw.contains(NO_SPEECH_MARKER)) {
                if (existingTranscript.isNotBlank()) {
                    Result.success(existingTranscript.trim())
                } else {
                    Result.failure(
                        IllegalStateException(
                            "No spoken words were detected in the audio recording. Please speak closer to the microphone or use Text Mode."
                        )
                    )
                }
            } else {
                Result.success(raw)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI audio transcription timed out", e)
            Result.failure(Exception("Audio transcription timed out after 45 seconds. Tap Retry AI Transcription to try again."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI audio transcription failed", e)
            Result.failure(Exception(e.localizedMessage ?: "Audio transcription failed."))
        }
    }

    internal fun buildBasicOrganizedResult(
        transcript: String,
        preferredCategory: String
    ): OrganizedNoteResult {
        val clean = transcript.trim()
        val words = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val headline = words.take(7).joinToString(" ").ifBlank { "Voice Note" }
        val keywords = words
            .map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
            .filter { it.length >= 4 }
            .distinct()
            .take(8)
        return OrganizedNoteResult(
            title = headline,
            transcript = clean,
            summary = clean.take(220),
            category = preferredCategory.ifBlank { "Personal" },
            tags = listOf(preferredCategory.ifBlank { "Personal" }),
            keywords = keywords,
            actionItem = "",
            priority = "medium",
            suggestedDueHours = 0
        )
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
    }
}
