package com.example.ui.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.NoCredentialException
import com.example.firebase.FirebaseRuntimeConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "GoogleAuthManager"

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@SuppressLint("DiscouragedApi")
internal fun resolveWebClientId(context: Context): String? {
    val candidateResourceNames = listOf("default_web_client_id", "oauth_web_client_id")
    for (resName in candidateResourceNames) {
        val resId = context.resources.getIdentifier(resName, "string", context.packageName)
        if (resId != 0) {
            val fromRes = runCatching { context.getString(resId) }.getOrNull()?.takeIf { it.isNotBlank() }
            if (fromRes != null) return fromRes
        }
    }

    FirebaseRuntimeConfig.getWebClientId(context)?.let { return it }

    val assetJson = runCatching {
        context.assets.open("google-services.json").bufferedReader().use { it.readText() }
    }.getOrNull()
    if (!assetJson.isNullOrBlank()) {
        val parsedId = FirebaseRuntimeConfig.parseGoogleServicesJson(assetJson, context.packageName)
            .getOrNull()
            ?.webClientId
            ?.takeIf { it.isNotBlank() }
        if (parsedId != null) return parsedId
    }

    return null
}

internal fun extractGoogleIdToken(credential: Credential): String? {
    return when {
        credential is GoogleIdTokenCredential -> credential.idToken
        credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ->
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        else -> null
    }
}

private suspend fun signInWithGoogleCredential(credential: Credential): Boolean {
    val googleIdToken = extractGoogleIdToken(credential) ?: return false
    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
    val result = Firebase.auth.signInWithCredential(authCredential).await()
    return result.user != null || Firebase.auth.currentUser != null
}

fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager?,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
): Job? {
    if (FirebaseApp.getApps(context).isEmpty()) {
        onUnauthenticated()
        return null
    }
    if (Firebase.auth.currentUser != null) {
        onAuthSuccess()
        return null
    }
    if (credentialManager == null) {
        onUnauthenticated()
        return null
    }
    val clientId = resolveWebClientId(context) ?: run {
        onUnauthenticated()
        return null
    }
    val activity = context.findActivity() ?: run {
        onUnauthenticated()
        return null
    }

    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()

    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    return scope.launch {
        try {
            val result = withTimeoutOrNull(2_500L) {
                credentialManager.getCredential(activity, request)
            }
            val credential = result?.credential
            if (credential != null && signInWithGoogleCredential(credential)) {
                withContext(Dispatchers.Main.immediate) { onAuthSuccess() }
            } else {
                onUnauthenticated()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Silent auto sign-in skipped: ${e.message}")
            onUnauthenticated()
        }
    }
}

fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager?,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {}
) {
    if (FirebaseApp.getApps(context).isEmpty()) {
        onAuthError("Firebase is not configured in this build (missing google-services.json).")
        return
    }
    if (Firebase.auth.currentUser != null) {
        onAuthSuccess()
        return
    }
    if (credentialManager == null) {
        onAuthError("Credential Manager is unavailable on this device.")
        return
    }
    val clientId = resolveWebClientId(context) ?: run {
        onAuthError("Google Sign-In Web Client ID is missing in google-services.json.")
        return
    }
    val activity = context.findActivity() ?: run {
        onAuthError("Google Sign-In requires an active Activity.")
        return
    }

    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(signInOption)
        .build()

    scope.launch {
        try {
            val result = try {
                credentialManager.getCredential(activity, request)
            } catch (primaryError: Exception) {
                if (primaryError is CancellationException) throw primaryError
                // On Android 11, selecting an account in GetSignInWithGoogleOption can return
                // GetCredentialCancellationException (when the sheet closes after account selection
                // or OAuth client / SHA-1 mismatch occurs), NoCredentialException, or
                // GetCredentialInterruptedException. Retry once with GetGoogleIdOption across all accounts.
                if (
                    primaryError is GetCredentialCancellationException ||
                    primaryError is NoCredentialException ||
                    primaryError is GetCredentialInterruptedException
                ) {
                    Log.w(
                        TAG,
                        "Primary GetSignInWithGoogleOption did not complete (${primaryError.javaClass.simpleName}: ${primaryError.message}); retrying with GetGoogleIdOption",
                        primaryError
                    )
                    val fallbackOption = GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(clientId)
                        .setAutoSelectEnabled(true)
                        .build()
                    val fallbackRequest = GetCredentialRequest.Builder()
                        .addCredentialOption(fallbackOption)
                        .build()
                    try {
                        credentialManager.getCredential(activity, fallbackRequest)
                    } catch (fallbackError: Exception) {
                        if (fallbackError is CancellationException) throw fallbackError
                        Log.w(
                            TAG,
                            "Fallback GetGoogleIdOption also failed (${fallbackError.javaClass.simpleName}: ${fallbackError.message})",
                            fallbackError
                        )
                        throw primaryError
                    }
                } else {
                    throw primaryError
                }
            }

            if (signInWithGoogleCredential(result.credential)) {
                withContext(Dispatchers.Main.immediate) { onAuthSuccess() }
            } else {
                withContext(Dispatchers.Main.immediate) {
                    onAuthError("Unexpected credential type returned from Google Sign-In.")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            withContext(Dispatchers.Main.immediate) {
                if (Firebase.auth.currentUser != null) {
                    onAuthSuccess()
                } else {
                    val detail = e.message?.trim().orEmpty()
                    val activeProjectId = runCatching { FirebaseApp.getInstance().options.projectId }.getOrNull().orEmpty()
                    onAuthError(
                        buildString {
                            append("Google Sign-In did not return an ID token after selecting your account.")
                            if (activeProjectId.isNotBlank()) {
                                append(" Make sure your GitHub GOOGLE_SERVICES_JSON secret matches project '$activeProjectId' (or commit the latest app/google-services.json) and rebuild the APK.")
                            }
                            if (detail.isNotBlank() && !detail.contains("activity is cancelled by the user", ignoreCase = true)) {
                                append(" ($detail)")
                            }
                        }
                    )
                    onAuthCancelled()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed", e)
            withContext(Dispatchers.Main.immediate) {
                if (Firebase.auth.currentUser != null) {
                    onAuthSuccess()
                } else {
                    onAuthError(e.localizedMessage ?: "Sign in failed. Please try again.")
                }
            }
        }
    }
}

fun signOut(
    credentialManager: CredentialManager?,
    scope: CoroutineScope,
    onSignOutComplete: () -> Unit = {}
) {
    scope.launch {
        withContext(NonCancellable) {
            try {
                credentialManager?.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear credential state: ${e.message}")
            } finally {
                Firebase.auth.signOut()
                withContext(Dispatchers.Main.immediate) {
                    onSignOutComplete()
                }
            }
        }
    }
}
