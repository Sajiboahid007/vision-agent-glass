package com.visionagent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.visionagent.ui.MainScreen
import com.visionagent.ui.SettingsScreen
import com.visionagent.ui.theme.ButtonTextDark
import com.visionagent.ui.theme.DarkBorder
import com.visionagent.ui.theme.DarkSurface
import com.visionagent.ui.theme.DescribeYellow
import com.visionagent.ui.theme.HighContrastError
import com.visionagent.ui.theme.HighContrastWhite
import com.visionagent.ui.theme.PureBlack
import com.visionagent.ui.theme.SecondaryTextWhite
import com.visionagent.ui.theme.VisionAgentTheme

/**
 * Main Activity hosting Vision Agent.
 *
 * Responsibilities:
 * - Keeps display permanently awake via [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON].
 * - Intercepts hardware Volume Up and Volume Down keys to trigger Describe and Read Text.
 * - Manages runtime permissions for CAMERA and RECORD_AUDIO with spoken accessibility explanations.
 * - Navigates between [MainScreen] and [SettingsScreen].
 */
class MainActivity : ComponentActivity() {

    private val viewModel: VisionViewModel by viewModels()

    private var hasCameraPermission by mutableStateOf(false)
    private var hasAudioPermission by mutableStateOf(false)
    private var showPermissionRationaleDialog by mutableStateOf(false)
    private var showPermissionDeniedDialog by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: hasCameraPermission
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission

        hasCameraPermission = cameraGranted
        hasAudioPermission = audioGranted

        if (!cameraGranted) {
            showPermissionDeniedDialog = true
            speakPermissionNotice(
                "Camera permission was denied. Vision Agent cannot operate as your eyes without camera access. " +
                        "Please open app settings to grant camera permission."
            )
        } else if (!audioGranted) {
            // Camera granted, but audio denied
            speakPermissionNotice(
                "Microphone permission was denied. You can still use Describe and Read Text, but Ask mode will be disabled."
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake continuously while app is in foreground
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        checkAndRequestPermissions()

        setContent {
            VisionAgentTheme {
                var currentScreenIsSettings by remember { mutableStateOf(false) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PureBlack
                ) {
                    if (currentScreenIsSettings) {
                        BackHandler {
                            currentScreenIsSettings = false
                        }
                        SettingsScreen(
                            viewModel = viewModel,
                            onNavigateBack = { currentScreenIsSettings = false }
                        )
                    } else {
                        MainScreen(
                            viewModel = viewModel,
                            onOpenSettings = { currentScreenIsSettings = true }
                        )
                    }

                    // Permission rationale dialog if required
                    if (showPermissionRationaleDialog) {
                        PermissionRationaleDialog(
                            onConfirm = {
                                showPermissionRationaleDialog = false
                                launchPermissionRequest()
                            },
                            onDismiss = {
                                showPermissionRationaleDialog = false
                                launchPermissionRequest()
                            }
                        )
                    }

                    // Permission denied permanently dialog
                    if (showPermissionDeniedDialog) {
                        PermissionDeniedDialog(
                            onOpenAppSettings = {
                                openAppSettings()
                            },
                            onDismiss = {
                                showPermissionDeniedDialog = false
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    private fun updatePermissionStates() {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCameraPermission && showPermissionDeniedDialog) {
            showPermissionDeniedDialog = false
        }
    }

    private fun checkAndRequestPermissions() {
        updatePermissionStates()

        if (!hasCameraPermission || !hasAudioPermission) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ||
                shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            ) {
                showPermissionRationaleDialog = true
                speakPermissionNotice(
                    "Vision Agent needs camera permission to see obstacles and text, " +
                            "and microphone permission to hear your voice questions."
                )
            } else {
                launchPermissionRequest()
            }
        }
    }

    private fun launchPermissionRequest() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun speakPermissionNotice(text: String) {
        viewModel.speaker.speak(
            text = text,
            language = viewModel.settings.value.language,
            speechRate = viewModel.settings.value.speechRate
        )
    }

    /**
     * Volume keys override:
     * - KEYCODE_VOLUME_UP -> ViewModel onVolumeUpPressed (Describe or Stop)
     * - KEYCODE_VOLUME_DOWN -> ViewModel onVolumeDownPressed (Read Text or Stop)
     * Consumes the event while app is in foreground so system media/ringer volume is not changed.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            viewModel.onVolumeUpPressed()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            viewModel.onVolumeDownPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

/**
 * Accessible rationale dialog explaining why permissions are required.
 */
@Composable
private fun PermissionRationaleDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Text(
                text = "Permissions Required",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    color = DescribeYellow
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Vision Agent acts as your eyes and requires:",
                    style = MaterialTheme.typography.bodyLarge,
                    color = HighContrastWhite
                )
                Text(
                    text = "1. Camera: To take photos of your surroundings for obstacle warnings and reading text.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondaryTextWhite
                )
                Text(
                    text = "2. Microphone: To listen to your spoken questions in Ask mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondaryTextWhite
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DescribeYellow,
                    contentColor = ButtonTextDark
                ),
                modifier = Modifier.semantics {
                    contentDescription = "Grant permissions button"
                }
            ) {
                Text("CONTINUE", fontWeight = FontWeight.Black)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = "Dismiss dialog button"
                }
            ) {
                Text("CANCEL", color = HighContrastWhite)
            }
        }
    )
}

/**
 * Accessible dialog displayed when permissions have been denied.
 */
@Composable
private fun PermissionDeniedDialog(
    onOpenAppSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        title = {
            Text(
                text = "Camera Permission Required",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    color = HighContrastError
                )
            )
        },
        text = {
            Text(
                text = "Vision Agent cannot function without Camera permission. Please open App Settings and grant Camera access to proceed.",
                style = MaterialTheme.typography.bodyLarge,
                color = HighContrastWhite
            )
        },
        confirmButton = {
            Button(
                onClick = onOpenAppSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DescribeYellow,
                    contentColor = ButtonTextDark
                ),
                modifier = Modifier.semantics {
                    contentDescription = "Open Android app settings to enable camera permission"
                }
            ) {
                Text("OPEN SETTINGS", fontWeight = FontWeight.Black)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = "Dismiss permission notice"
                }
            ) {
                Text("DISMISS", color = HighContrastWhite)
            }
        }
    )
}
