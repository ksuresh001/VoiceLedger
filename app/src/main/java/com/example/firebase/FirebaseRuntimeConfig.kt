package com.example.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONObject

data class ParsedGoogleServices(
    val projectId: String,
    val applicationId: String,
    val apiKey: String,
    val gcmSenderId: String,
    val storageBucket: String,
    val webClientId: String,
    val registeredSha1Hashes: List<String> = emptyList()
)

object FirebaseRuntimeConfig {

    private const val TAG = "FirebaseRuntimeConfig"
    private const val PREFS_NAME = "voiceledger_runtime_firebase"
    private const val KEY_JSON = "google_services_json"
    private const val KEY_WEB_CLIENT_ID = "web_client_id"
    private const val KEY_PROJECT_ID = "project_id"

    fun ensureInitialized(context: Context): Boolean {
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            syncPrefsWithActiveApp(context)
            return true
        }
        val defaultOk = runCatching { FirebaseApp.initializeApp(context) != null }.getOrDefault(false)
        if (defaultOk && FirebaseApp.getApps(context).isNotEmpty()) {
            syncPrefsWithActiveApp(context)
            return true
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedJson = prefs.getString(KEY_JSON, null)?.trim().orEmpty()
        if (savedJson.isEmpty()) return false

        return initializeFromJson(context, savedJson).isSuccess
    }

    private fun syncPrefsWithActiveApp(context: Context) {
        val activeProjectId = runCatching { FirebaseApp.getInstance().options.projectId }.getOrNull()
        if (activeProjectId.isNullOrBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedProjectId = prefs.getString(KEY_PROJECT_ID, null)
        if (!savedProjectId.isNullOrBlank() && savedProjectId != activeProjectId) {
            // Bundled google-services.json changed to a newer Firebase project; clear stale runtime override
            prefs.edit().clear().commit()
        }
    }

    fun saveAndInitialize(context: Context, rawJson: String): Result<ParsedGoogleServices> {
        val trimmed = rawJson.trim()
        val res = initializeFromJson(context, trimmed)
        if (res.isSuccess) {
            val parsed = res.getOrThrow()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_JSON, trimmed)
                .putString(KEY_WEB_CLIENT_ID, parsed.webClientId)
                .putString(KEY_PROJECT_ID, parsed.projectId)
                .commit()
        }
        return res
    }

    fun getWebClientId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WEB_CLIENT_ID, null)
            ?.takeIf { it.isNotBlank() }
    }

    internal fun parseGoogleServicesJson(rawJson: String, packageName: String): Result<ParsedGoogleServices> {
        return runCatching {
            val root = JSONObject(rawJson)
            val projectInfo = root.getJSONObject("project_info")
            val projectId = projectInfo.getString("project_id").trim()
            val gcmSenderId = projectInfo.optString("project_number", "").trim()
            val storageBucket = projectInfo.optString("storage_bucket", "").trim()

            val clients = root.getJSONArray("client")
            require(clients.length() > 0) { "No client entries found in google-services.json." }

            var matchedClient = clients.getJSONObject(0)
            for (i in 0 until clients.length()) {
                val candidate = clients.getJSONObject(i)
                val pkg = candidate.optJSONObject("client_info")
                    ?.optJSONObject("android_client_info")
                    ?.optString("package_name", "")
                    .orEmpty()
                if (pkg == packageName) {
                    matchedClient = candidate
                    break
                }
            }

            val clientInfo = matchedClient.getJSONObject("client_info")
            val applicationId = clientInfo.getString("mobilesdk_app_id").trim()
            val apiKeys = matchedClient.getJSONArray("api_key")
            require(apiKeys.length() > 0) { "No api_key found in google-services.json." }
            val apiKey = apiKeys.getJSONObject(0).getString("current_key").trim()

            var webClientId = ""
            val sha1Hashes = mutableListOf<String>()
            val oauthClients = matchedClient.optJSONArray("oauth_client")
            if (oauthClients != null) {
                for (i in 0 until oauthClients.length()) {
                    val oauth = oauthClients.getJSONObject(i)
                    val clientType = oauth.optInt("client_type", -1)
                    if (clientType == 1) {
                        val hash = oauth.optJSONObject("android_info")
                            ?.optString("certificate_hash", "")
                            ?.trim()
                            ?.lowercase()
                            .orEmpty()
                        if (hash.isNotEmpty()) {
                            sha1Hashes.add(hash)
                        }
                    } else if (clientType == 3 && webClientId.isEmpty()) {
                        webClientId = oauth.optString("client_id", "").trim()
                    }
                }
            }
            if (webClientId.isEmpty()) {
                val otherClients = matchedClient.optJSONObject("services")
                    ?.optJSONObject("appinvite_service")
                    ?.optJSONArray("other_platform_oauth_client")
                if (otherClients != null) {
                    for (i in 0 until otherClients.length()) {
                        val oauth = otherClients.getJSONObject(i)
                        if (oauth.optInt("client_type", -1) == 3) {
                            webClientId = oauth.optString("client_id", "").trim()
                            if (webClientId.isNotEmpty()) break
                        }
                    }
                }
            }

            require(projectId.isNotEmpty() && applicationId.isNotEmpty() && apiKey.isNotEmpty()) {
                "google-services.json is missing required project_id, mobilesdk_app_id, or current_key."
            }

            ParsedGoogleServices(
                projectId = projectId,
                applicationId = applicationId,
                apiKey = apiKey,
                gcmSenderId = gcmSenderId,
                storageBucket = storageBucket,
                webClientId = webClientId,
                registeredSha1Hashes = sha1Hashes
            )
        }
    }

    private fun initializeFromJson(context: Context, rawJson: String): Result<ParsedGoogleServices> {
        return parseGoogleServicesJson(rawJson, context.packageName).mapCatching { parsed ->
            if (FirebaseApp.getApps(context).isEmpty()) {
                val builder = FirebaseOptions.Builder()
                    .setProjectId(parsed.projectId)
                    .setApplicationId(parsed.applicationId)
                    .setApiKey(parsed.apiKey)
                if (parsed.gcmSenderId.isNotEmpty()) {
                    builder.setGcmSenderId(parsed.gcmSenderId)
                }
                if (parsed.storageBucket.isNotEmpty()) {
                    builder.setStorageBucket(parsed.storageBucket)
                }
                FirebaseApp.initializeApp(context.applicationContext, builder.build())
                    ?: throw IllegalStateException("Failed to initialize FirebaseApp with provided config.")
            }
            Log.d(TAG, "FirebaseApp initialized from runtime config for project=${parsed.projectId}")
            parsed
        }
    }
}
