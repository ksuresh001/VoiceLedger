package com.example.firebase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.auth.resolveWebClientId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FirebaseRuntimeConfigTest {

    @Test
    fun `parseGoogleServicesJson extracts project info and web client id`() {
        val sampleJson = """
            {
              "project_info": {
                "project_number": "1234567890",
                "project_id": "demo-project-id",
                "storage_bucket": "demo-project-id.firebasestorage.app"
              },
              "client": [
                {
                  "client_info": {
                    "mobilesdk_app_id": "1:1234567890:android:abcdef",
                    "android_client_info": {
                      "package_name": "com.aistudio.voiceledger.kvnqrx"
                    }
                  },
                  "oauth_client": [
                    {
                      "client_id": "android-client.apps.googleusercontent.com",
                      "client_type": 1
                    },
                    {
                      "client_id": "web-client.apps.googleusercontent.com",
                      "client_type": 3
                    }
                  ],
                  "api_key": [
                    {
                      "current_key": "fake-test-api-key-value"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = FirebaseRuntimeConfig.parseGoogleServicesJson(
            rawJson = sampleJson,
            packageName = "com.aistudio.voiceledger.kvnqrx"
        )
        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow()
        assertEquals("demo-project-id", parsed.projectId)
        assertEquals("1:1234567890:android:abcdef", parsed.applicationId)
        assertEquals("fake-test-api-key-value", parsed.apiKey)
        assertEquals("1234567890", parsed.gcmSenderId)
        assertEquals("demo-project-id.firebasestorage.app", parsed.storageBucket)
        assertEquals("web-client.apps.googleusercontent.com", parsed.webClientId)
    }

    @Test
    fun `saveAndInitialize stores web client id and initializes FirebaseApp`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sampleJson = """
            {
              "project_info": {
                "project_number": "999888777",
                "project_id": "runtime-project",
                "storage_bucket": "runtime-project.firebasestorage.app"
              },
              "client": [
                {
                  "client_info": {
                    "mobilesdk_app_id": "1:999888777:android:112233",
                    "android_client_info": {
                      "package_name": "${context.packageName}"
                    }
                  },
                  "oauth_client": [
                    {
                      "client_id": "runtime-web-client.apps.googleusercontent.com",
                      "client_type": 3
                    }
                  ],
                  "api_key": [
                    {
                      "current_key": "runtime-test-key"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val res = FirebaseRuntimeConfig.saveAndInitialize(context, sampleJson)
        assertTrue(res.isSuccess)
        assertEquals(
            "runtime-web-client.apps.googleusercontent.com",
            FirebaseRuntimeConfig.getWebClientId(context)
        )
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        val expectedClientId = if (resId != 0) {
            context.getString(resId)
        } else {
            "runtime-web-client.apps.googleusercontent.com"
        }
        assertEquals(expectedClientId, resolveWebClientId(context))
        assertTrue(FirebaseRuntimeConfig.ensureInitialized(context))
    }
}
