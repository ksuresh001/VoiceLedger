package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.NotePriority
import com.example.data.model.NoteStatus
import com.example.data.model.VoiceNote
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class NotesRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createNote_andObserveNotes_emitsCreatedNoteForOwner() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = NotesRepository(firestore)

        val uniqueTitle = "Vendor follow-up ${UUID.randomUUID().toString().take(6)}"
        val note = VoiceNote(
            ownerId = uid,
            ownerName = "Alice",
            memberUids = listOf(uid),
            title = uniqueTitle,
            transcript = "Check Q4 invoice discrepancy.",
            aiSummary = "Verify Q4 invoice discrepancy with finance.",
            category = "Official",
            tags = listOf("finance", "invoice"),
            keywords = listOf("q4", "invoice", "vendor"),
            actionItem = "Send invoice breakdown to finance",
            priority = NotePriority.HIGH.value,
            status = NoteStatus.OPEN.value,
            localAudioFileName = "rec_1.m4a",
            audioDurationSec = 14
        )

        val noteId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createNote(note).getOrThrow()
        }
        assertTrue(noteId.isNotBlank())

        val notes = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeNotes(uid).first { list -> list.any { it.id == noteId } }
        }
        val saved = notes.first { it.id == noteId }
        assertEquals(uniqueTitle, saved.title)
        assertEquals("Official", saved.category)
        assertNotNull(saved.createdAt)
    }

    @Test
    fun resolveAndCloseNote_updatesStatusAndEnforcesTerminalLock() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repository = NotesRepository(firestore)

        val noteId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createNote(
                VoiceNote(
                    ownerId = uid,
                    ownerName = "Alice",
                    memberUids = listOf(uid),
                    title = "Client contract review ${UUID.randomUUID().toString().take(6)}",
                    transcript = "Review NDA redlines.",
                    category = "Client"
                )
            ).getOrThrow()
        }

        val resolveResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.resolveAndCloseNote(
                noteId = noteId,
                resolutionNote = "Signed and returned NDA.",
                resolvedByName = "Alice"
            )
        }
        assertTrue(resolveResult.isSuccess)

        val closedNote = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.getNoteById(noteId).getOrThrow()!!
        }
        assertEquals(NoteStatus.CLOSED.value, closedNote.status)
        assertEquals("Signed and returned NDA.", closedNote.resolutionNote)

        // Terminal lock: editing a closed note must fail with PERMISSION_DENIED
        val tamperResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.updateNoteDetails(closedNote.copy(title = "Tampered title"))
        }
        assertTrue(tamperResult.isFailure)
        val fsEx = tamperResult.exceptionOrNull()?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    @Test
    fun getNoteById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = NotesRepository(firestore)
        val noteId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createNote(
                VoiceNote(
                    ownerId = aliceUid,
                    ownerName = "Alice",
                    memberUids = listOf(aliceUid),
                    title = "Private personal note ${UUID.randomUUID().toString().take(6)}",
                    category = "Personal"
                )
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = NotesRepository(firestore)
        val crossRead = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getNoteById(noteId)
        }
        assertTrue(crossRead.isFailure)
        val fsEx = crossRead.exceptionOrNull()?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    @Test
    fun observeNotes_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = NotesRepository(firestore)
        var caught: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeNotes("some_uid").first()
            }
        } catch (t: Throwable) {
            caught = t
        }
        val fsEx = checkNotNull(caught?.unwrapFirestoreException()) {
            "Expected nested FirebaseFirestoreException, got $caught"
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx.code)
    }

    @Test
    fun workspaceCreateAndJoin_allowsTeammateToReadWorkspace() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceWsRepo = WorkspaceRepository(firestore)

        val ws = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceWsRepo.createWorkspace("Team ${UUID.randomUUID().toString().take(5)}", "Alice").getOrThrow()
        }

        val bobUid = signInTestUser(BOB_EMAIL)
        val bobWsRepo = WorkspaceRepository(firestore)
        val joinResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobWsRepo.joinWorkspaceByCode(ws.joinCode, "Bob")
        }
        assertTrue(joinResult.isSuccess)

        val bobWorkspaces = withTimeout(FLOW_TIMEOUT_MS) {
            bobWsRepo.observeWorkspaces(bobUid).first { list -> list.any { it.id == ws.id } }
        }
        val joined = bobWorkspaces.first { it.id == ws.id }
        assertTrue(joined.memberUids.containsAll(listOf(aliceUid, bobUid)))
    }

    private companion object {
        const val ALICE_EMAIL = "alice@voiceledger.test"
        const val BOB_EMAIL = "bob@voiceledger.test"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
