package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.Workspace
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
import java.security.SecureRandom

class WorkspaceRepository(
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
            ?: throw IllegalStateException("User must be signed in with Google before accessing workspaces.")
    }

    fun observeWorkspaces(userId: String): Flow<List<Workspace>> = flow {
        val path = "workspaces"
        emitAll(
            db.collection(path)
                .whereArrayContains("memberUids", userId)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        Workspace::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.name.lowercase() }
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

    suspend fun createWorkspace(name: String, ownerName: String): Result<Workspace> {
        val uid = requireUserId()
        val cleanName = name.trim().take(60)
        require(cleanName.length >= 2) { "Workspace name must be at least 2 characters." }
        val cleanOwnerName = ownerName.trim().ifEmpty { "Owner" }.take(80)
        val joinCode = generateJoinCode()

        val wsRef = db.collection("workspaces").document()
        val inviteRef = db.collection("workspace_invites").document(joinCode)

        val wsPayload = mapOf(
            "name" to cleanName,
            "ownerId" to uid,
            "ownerName" to cleanOwnerName,
            "joinCode" to joinCode,
            "memberUids" to listOf(uid),
            "memberNames" to listOf(cleanOwnerName),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        val invitePayload = mapOf(
            "workspaceId" to wsRef.id,
            "workspaceName" to cleanName,
            "ownerId" to uid,
            "createdAt" to FieldValue.serverTimestamp()
        )

        return try {
            val batch = db.batch()
            batch.set(wsRef, wsPayload)
            batch.set(inviteRef, invitePayload)
            batch.commit().await()
            Result.success(
                Workspace(
                    id = wsRef.id,
                    name = cleanName,
                    ownerId = uid,
                    ownerName = cleanOwnerName,
                    joinCode = joinCode,
                    memberUids = listOf(uid),
                    memberNames = listOf(cleanOwnerName)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, wsRef.path)
            Result.failure(e)
        }
    }

    suspend fun joinWorkspaceByCode(rawCode: String, memberName: String): Result<String> {
        val uid = requireUserId()
        val code = rawCode.trim().uppercase()
        if (code.length < 6 || !code.matches(Regex("^[A-Z0-9]+$"))) {
            return Result.failure(IllegalArgumentException("Enter a valid 6-character invite code."))
        }
        val cleanMemberName = memberName.trim().ifEmpty { "Member" }.take(80)

        val inviteRef = db.collection("workspace_invites").document(code)
        return try {
            val inviteSnap = inviteRef.get().await()
            if (!inviteSnap.exists()) {
                return Result.failure(IllegalArgumentException("Invite code not found."))
            }
            val workspaceId = inviteSnap.getString("workspaceId")
                ?: return Result.failure(IllegalStateException("Invite missing workspace reference."))
            val workspaceName = inviteSnap.getString("workspaceName") ?: "Team Workspace"

            val wsRef = db.collection("workspaces").document(workspaceId)
            wsRef.update(
                mapOf(
                    "memberUids" to FieldValue.arrayUnion(uid),
                    "memberNames" to FieldValue.arrayUnion(cleanMemberName),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            Result.success(workspaceName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, "workspaces (join $code)")
            Result.failure(e)
        }
    }

    suspend fun deleteWorkspace(workspace: Workspace): Result<Unit> {
        requireUserId()
        return try {
            val batch = db.batch()
            batch.delete(db.collection("workspaces").document(workspace.id))
            if (workspace.joinCode.isNotBlank()) {
                batch.delete(db.collection("workspace_invites").document(workspace.joinCode))
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "workspaces/${workspace.id}")
            Result.failure(e)
        }
    }

    private fun generateJoinCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return buildString {
            repeat(6) {
                append(chars[random.nextInt(chars.length)])
            }
        }
    }
}
