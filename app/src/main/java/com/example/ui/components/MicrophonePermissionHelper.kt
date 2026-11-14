package com.example.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.service.MicrophoneAudioRecorder
import com.example.service.MicrophonePermissionStatus

@Stable
class MicrophonePermissionController internal constructor(
    val hasMicrophoneHardware: Boolean,
    val statusProvider: () -> MicrophonePermissionStatus,
    private val launchPermissionRequest: () -> Unit,
    private val openAppSettingsAction: () -> Unit,
    private val refreshAction: () -> Unit
) {
    val status: MicrophonePermissionStatus
        get() = statusProvider()

    val isGranted: Boolean
        get() = status == MicrophonePermissionStatus.GRANTED

    val isPermanentlyDenied: Boolean
        get() = status == MicrophonePermissionStatus.PERMANENTLY_DENIED

    fun requestPermission() {
        if (isPermanentlyDenied) {
            openAppSettingsAction()
        } else {
            launchPermissionRequest()
        }
    }

    fun openSettings() {
        openAppSettingsAction()
    }

    fun refresh() {
        refreshAction()
    }
}

@Composable
fun rememberMicrophonePermissionState(
    onPermissionGranted: () -> Unit = {},
    onPermissionDenied: (permanentlyDenied: Boolean) -> Unit = {}
): MicrophonePermissionController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnGranted by rememberUpdatedState(onPermissionGranted)
    val currentOnDenied by rememberUpdatedState(onPermissionDenied)

    var hasRequested by rememberSaveable { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            MicrophoneAudioRecorder.resolvePermissionStatus(
                context = context,
                hasRequestedBefore = hasRequested
            )
        )
    }

    val hasHardware = remember(context) {
        MicrophoneAudioRecorder.hasMicrophoneHardware(context)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRequested = true
        val resolved = MicrophoneAudioRecorder.resolvePermissionStatus(
            context = context,
            hasRequestedBefore = true
        )
        status = resolved
        if (granted || resolved == MicrophonePermissionStatus.GRANTED) {
            currentOnGranted()
        } else {
            currentOnDenied(resolved == MicrophonePermissionStatus.PERMANENTLY_DENIED)
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val previous = status
                val updated = MicrophoneAudioRecorder.resolvePermissionStatus(
                    context = context,
                    hasRequestedBefore = hasRequested
                )
                status = updated
                if (previous != MicrophonePermissionStatus.GRANTED &&
                    updated == MicrophonePermissionStatus.GRANTED
                ) {
                    currentOnGranted()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(context, launcher) {
        MicrophonePermissionController(
            hasMicrophoneHardware = hasHardware,
            statusProvider = { status },
            launchPermissionRequest = {
                hasRequested = true
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            },
            openAppSettingsAction = {
                MicrophoneAudioRecorder.openAppSettings(context)
            },
            refreshAction = {
                status = MicrophoneAudioRecorder.resolvePermissionStatus(
                    context = context,
                    hasRequestedBefore = hasRequested
                )
            }
        )
    }
}

@Composable
fun MicrophonePermissionCard(
    controller: MicrophonePermissionController,
    modifier: Modifier = Modifier,
    onFallbackToText: (() -> Unit)? = null
) {
    val isPermanent = controller.isPermanentlyDenied
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = when {
                    !controller.hasMicrophoneHardware ->
                        "No device microphone hardware was detected."
                    isPermanent ->
                        "Microphone permission was denied. Enable Microphone access in App Settings to record audio."
                    else ->
                        "Microphone permission is needed to record voice audio and live speech."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (controller.hasMicrophoneHardware) {
                    Button(
                        onClick = { controller.requestPermission() },
                        modifier = Modifier.testTag("grant_mic_permission_button")
                    ) {
                        Text(if (isPermanent) "Open Settings" else "Grant Mic Access")
                    }
                }
                if (onFallbackToText != null) {
                    OutlinedButton(
                        onClick = onFallbackToText,
                        modifier = Modifier.testTag("fallback_text_mode_button")
                    ) {
                        Text("Type Note Instead")
                    }
                }
            }
        }
    }
}
