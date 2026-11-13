package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.NoteStatus
import com.example.data.model.VoiceNote
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class NotesRepository(
    private val db: FirebaseFirestore
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeNotes(userId: String): Flow<List<VoiceNote>> = flow {
        val path = "notes"
        emitAll(
            db.collection(path)
                .whereArrayContains("memberUids", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        VoiceNote::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt?.seconds ?: Long.MAX_VALUE }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun getNoteById(noteId: String): Result<VoiceNote?> {
        val path = "notes/$noteId"
        return try {
            val snap = db.collection("notes").document(noteId).get().await()
            val note = snap.toObject(
                VoiceNote::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(note)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun createNote(note: VoiceNote): Result<String> {
        val uid = requireUserId()
        val docRef = db.collection("notes").document()
        val safeMembers = (note.memberUids + uid).distinct().take(25)
        val payload = mapOf(
            "ownerId" to uid,
            "ownerName" to note.ownerName.take(80),
            "workspaceId" to note.workspaceId.take(128),
            "memberUids" to safeMembers,
            "title" to note.title.trim().ifEmpty { "Voice Note" }.take(160),
            "transcript" to note.transcript.take(15000),
            "aiSummary" to note.aiSummary.take(2000),
            "category" to note.category.trim().ifEmpty { "Personal" }.take(40),
            "tags" to note.tags.map { it.trim() }.filter { it.isNotEmpty() }.take(8),
            "keywords" to note.keywords.map { it.trim() }.filter { it.isNotEmpty() }.take(12),
            "actionItem" to note.actionItem.trim().take(500),
            "priority" to note.priority,
            "status" to if (note.status == NoteStatus.IN_PROGRESS.value) NoteStatus.IN_PROGRESS.value else NoteStatus.OPEN.value,
            "resolutionNote" to "",
            "resolvedByUid" to "",
            "resolvedByName" to "",
            "assigneeName" to note.assigneeName.trim().take(80),
            "localAudioFileName" to note.localAudioFileName.take(200),
            "audioDurationSec" to note.audioDurationSec.coerceIn(0, 7200),
            "dueAtMs" to note.dueAtMs.coerceAtLeast(0L),
            "reminderMinutesBefore" to note.reminderMinutesBefore.coerceIn(0, 10080),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateNoteDetails(note: VoiceNote): Result<Unit> {
        requireUserId()
        require(note.id.isNotBlank()) { "Note ID must not be blank" }
        val docRef = db.collection("notes").document(note.id)
        val updates = mapOf(
            "title" to note.title.trim().ifEmpty { "Voice Note" }.take(160),
            "transcript" to note.transcript.take(15000),
            "aiSummary" to note.aiSummary.take(2000),
            "category" to note.category.trim().ifEmpty { "Personal" }.take(40),
            "tags" to note.tags.map { it.trim() }.filter { it.isNotEmpty() }.take(8),
            "keywords" to note.keywords.map { it.trim() }.filter { it.isNotEmpty() }.take(12),
            "actionItem" to note.actionItem.trim().take(500),
            "priority" to note.priority,
            "status" to if (note.status == NoteStatus.IN_PROGRESS.value) NoteStatus.IN_PROGRESS.value else NoteStatus.OPEN.value,
            "assigneeName" to note.assigneeName.trim().take(80),
            "dueAtMs" to note.dueAtMs.coerceAtLeast(0L),
            "reminderMinutesBefore" to note.reminderMinutesBefore.coerceIn(0, 10080),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun resolveAndCloseNote(
        noteId: String,
        resolutionNote: String,
        resolvedByName: String
    ): Result<Unit> {
        val uid = requireUserId()
        require(noteId.isNotBlank()) { "Note ID must not be blank" }
        val cleanResolution = resolutionNote.trim().ifEmpty { "Resolved and closed." }.take(2000)
        val docRef = db.collection("notes").document(noteId)
        val updates = mapOf(
            "status" to NoteStatus.CLOSED.value,
            "resolutionNote" to cleanResolution,
            "resolvedByUid" to uid,
            "resolvedByName" to resolvedByName.take(80),
            "closedAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun syncWorkspaceMembersOnNotes(
        workspaceId: String,
        memberUids: List<String>,
        notesInWorkspace: List<VoiceNote>
    ): Result<Unit> {
        requireUserId()
        if (workspaceId.isBlank() || memberUids.isEmpty()) return Result.success(Unit)
        val normalizedMembers = memberUids.distinct().take(25)
        val staleNotes = notesInWorkspace.filter { note ->
            note.workspaceId == workspaceId && note.memberUids.toSet() != normalizedMembers.toSet()
        }
        if (staleNotes.isEmpty()) return Result.success(Unit)
        return try {
            val batch = db.batch()
            staleNotes.forEach { note ->
                val ref = db.collection("notes").document(note.id)
                batch.update(
                    ref,
                    mapOf(
                        "memberUids" to normalizedMembers,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "notes (workspace member sync)")
            Result.failure(e)
        }
    }

    suspend fun deleteNote(noteId: String): Result<Unit> {
        requireUserId()
        val docRef = db.collection("notes").document(noteId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }
}
