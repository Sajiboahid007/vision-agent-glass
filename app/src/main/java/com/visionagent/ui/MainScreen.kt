package com.visionagent.ui

import android.content.Intent
import android.provider.Settings
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.visionagent.UiState
import com.visionagent.VisionViewModel
import com.visionagent.ui.theme.AskLime
import com.visionagent.ui.theme.ButtonTextDark
import com.visionagent.ui.theme.DarkBorder
import com.visionagent.ui.theme.DarkSurface
import com.visionagent.ui.theme.DarkSurfaceElevated
import com.visionagent.ui.theme.DescribeButtonTextStyle
import com.visionagent.ui.theme.DescribeYellow
import com.visionagent.ui.theme.HighContrastError
import com.visionagent.ui.theme.HighContrastWhite
import com.visionagent.ui.theme.PureBlack
import com.visionagent.ui.theme.ReadCyan
import com.visionagent.ui.theme.SecondaryActionButtonTextStyle
import com.visionagent.ui.theme.SecondaryTextWhite
import com.visionagent.ui.theme.StatusBannerTextStyle
import kotlinx.coroutines.launch

/**
 * Main accessible interface for Vision Agent.
 *
 * Layout:
 * 1. Top bar: Settings gear icon button (>= 48dp touch target) & app title.
 * 2. Camera Preview (optional ~25% height): For sighted helpers to inspect camera framing.
 * 3. Bangla Voice Missing alert (conditional).
 * 4. Status Announcement Banner: TalkBack live-region announcing current state.
 * 5. Three huge vertically stacked buttons:
 *    - DESCRIBE (Yellow, weight 1.3, >= 32sp)
 *    - READ TEXT (Cyan, weight 1.0, >= 28sp)
 *    - ASK (Neon Lime, weight 1.0, >= 28sp)
 *
 * Any button press while state is NOT Idle instantly stops the active operation.
 */
@Composable
fun MainScreen(
    viewModel: VisionViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val uiState by viewModel.uiState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val banglaMissing by viewModel.banglaMissingNotice.collectAsState()
    val isBusy = uiState !is UiState.Idle
    val isBn = settings.language.equals("bn", ignoreCase = true)

    // Bind CameraX with or without PreviewView depending on showCameraPreview setting
    LaunchedEffect(settings.showCameraPreview, lifecycleOwner) {
        if (!settings.showCameraPreview) {
            viewModel.cameraController.bindCamera(lifecycleOwner, null)
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            viewModel.cameraController.unbind()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
    ) {
        // --- TOP BAR ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PureBlack)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isBn) "ভিশন এজেন্ট" else "VISION AGENT",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    color = HighContrastWhite,
                    letterSpacing = 1.sp
                )
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Quick 1-Tap Language Switcher (EN / বাংলা)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isBn) DescribeYellow else DarkSurfaceElevated)
                        .clickable { viewModel.toggleLanguage() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics {
                            contentDescription = if (isBn) {
                                "বর্তমান ভাষা বাংলা। ইংরেজিতে পরিবর্তন করতে ট্যাপ করুন।"
                            } else {
                                "Current language English. Tap to switch to Bangla."
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isBn) "বাংলা" else "EN",
                        color = if (isBn) ButtonTextDark else HighContrastWhite,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Voice Control toggle icon button
                IconButton(
                    onClick = {
                        viewModel.updateVoiceCommandsEnabled(!settings.voiceCommandsEnabled)
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = if (settings.voiceCommandsEnabled) {
                                if (isBn) "ভয়েস কমান্ড সক্রিয়। বন্ধ করতে ট্যাপ করুন।" else "Hands-free voice control enabled. Tap to disable."
                            } else {
                                if (isBn) "ভয়েস কমান্ড নিষ্ক্রিয়। চালু করতে ট্যাপ করুন।" else "Hands-free voice control disabled. Tap to enable."
                            }
                        }
                ) {
                    Icon(
                        imageVector = if (settings.voiceCommandsEnabled) Icons.Default.Mic else Icons.Default.Close,
                        contentDescription = null,
                        tint = if (settings.voiceCommandsEnabled) AskLime else SecondaryTextWhite,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Settings gear button with large touch target >= 48dp
                IconButton(
                    onClick = {
                        if (isBusy) viewModel.stop()
                        onOpenSettings()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = if (isBn) "সেটিংস খুলুন" else "Open Settings screen"
                        }
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = HighContrastWhite,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // --- HANDS-FREE VOICE CONTROL PROMPT BANNER ---
        if (settings.voiceCommandsEnabled) {
            val voicePrompt = if (isBn) {
                "কথা বলুন: \"বলো ভিশন\", \"কি দেখছো\", বা \"থামো ভিশন\""
            } else {
                "Say \"Vision\", \"Tell me vision\", or \"Stop vision\""
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurfaceElevated)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .semantics {
                        contentDescription = voicePrompt
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = AskLime,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = voicePrompt,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = HighContrastWhite
                )
            }
        }

        // --- CAMERA PREVIEW (Top ~25% height when enabled) ---
        if (settings.showCameraPreview) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.25f)
                    .background(Color.Black)
                    .border(2.dp, DarkBorder)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        }.also { previewView ->
                            coroutineScope.launch {
                                try {
                                    viewModel.cameraController.bindCamera(lifecycleOwner, previewView)
                                } catch (e: Exception) {
                                    android.util.Log.e("MainScreen", "Error binding camera preview", e)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // --- BANGLA VOICE MISSING BANNER ---
        AnimatedVisibility(visible = banglaMissing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HighContrastError)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Bangla voice missing. Tap to install.",
                    color = ButtonTextDark,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            try {
                                context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                            } catch (_: Exception) {
                                context.startActivity(Intent(Settings.ACTION_SETTINGS))
                            }
                        }
                )
                IconButton(
                    onClick = { viewModel.dismissBanglaMissingNotice() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss notice",
                        tint = ButtonTextDark
                    )
                }
            }
        }

        // --- STATUS ANNOUNCEMENT BANNER (Polite Live Region for TalkBack) ---
        val statusText = when (val state = uiState) {
            is UiState.Idle -> if (isBn) "প্রস্তুত" else "Ready"
            is UiState.Capturing -> if (isBn) "ছবি তোলা হচ্ছে..." else "Capturing photo..."
            is UiState.Thinking -> if (isBn) "বিশ্লেষণ করা হচ্ছে..." else "Thinking..."
            is UiState.Speaking -> if (isBn) "উত্তর দেওয়া হচ্ছে..." else "Speaking..."
            is UiState.Listening -> if (isBn) "আপনার প্রশ্ন শুনছি..." else "Listening for question..."
            is UiState.Error -> state.message
        }

        val bannerBackground = when (uiState) {
            is UiState.Error -> HighContrastError
            is UiState.Thinking -> DescribeYellow
            is UiState.Speaking -> AskLime
            is UiState.Listening -> ReadCyan
            is UiState.Capturing -> DescribeYellow
            is UiState.Idle -> DarkSurfaceElevated
        }

        val bannerTextColor = when (uiState) {
            is UiState.Idle -> HighContrastWhite
            else -> ButtonTextDark
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bannerBackground)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clickable {
                    if (isBusy) viewModel.stop()
                }
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = if (isBusy) "$statusText. ${if (isBn) "থামতে ট্যাপ করুন।" else "Tap to stop."}" else statusText
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isBusy) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        tint = bannerTextColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (isBusy) "$statusText (${if (isBn) "থামতে ট্যাপ করুন" else "TAP TO STOP"})" else statusText,
                    style = StatusBannerTextStyle.copy(color = bannerTextColor),
                    textAlign = TextAlign.Center
                )
            }
        }

        // --- THREE HUGE ACTION BUTTONS ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. DESCRIBE BUTTON (Top, flex weight ~1.3, minimum 32sp text)
            BigActionButton(
                label = if (isBusy) (if (isBn) "থামুন" else "STOP") else (if (isBn) "বর্ণনা করুন" else "DESCRIBE"),
                subtitle = if (isBusy) (if (isBn) "বাতিল করতে ট্যাপ করুন" else "Tap to cancel active action") else (if (isBn) "সামনের দৃশ্য ও বাধা। ভলিউম আপ কী" else "Describe scene ahead. Shortcut: Volume Up"),
                backgroundColor = DescribeYellow,
                textStyle = DescribeButtonTextStyle,
                contentDescription = if (isBusy) {
                    if (isBn) "চলমান কাজ বন্ধ করুন" else "Stop current operation"
                } else {
                    if (isBn) "সামনের দৃশ্য ও বাধা বর্ণনা করুন। শর্টকাট: ভলিউম আপ কী।" else "Describe the scene ahead. Shortcut: Volume Up key."
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.3f),
                onClick = {
                    if (isBusy) {
                        viewModel.stop()
                    } else {
                        viewModel.onDescribeTrigger()
                    }
                }
            )

            // 2. READ TEXT BUTTON (Middle, flex weight 1.0, minimum 28sp text)
            BigActionButton(
                label = if (isBusy) (if (isBn) "থামুন" else "STOP") else (if (isBn) "লেখা পড়ুন" else "READ TEXT"),
                subtitle = if (isBusy) (if (isBn) "বাতিল করতে ট্যাপ করুন" else "Tap to cancel active action") else (if (isBn) "কাগজ বা সাইনবোর্ড। ভলিউম ডাউন কী" else "Read visible text. Shortcut: Volume Down"),
                backgroundColor = ReadCyan,
                textStyle = SecondaryActionButtonTextStyle,
                contentDescription = if (isBusy) {
                    if (isBn) "চলমান কাজ বন্ধ করুন" else "Stop current operation"
                } else {
                    if (isBn) "দৃশ্যমান সব লেখা পড়ুন। শর্টকাট: ভলিউম ডাউন কী।" else "Read visible text in the scene. Shortcut: Volume Down key."
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.0f),
                onClick = {
                    if (isBusy) {
                        viewModel.stop()
                    } else {
                        viewModel.onReadTextTrigger()
                    }
                }
            )

            // 3. ASK BUTTON (Bottom, flex weight 1.0, minimum 28sp text)
            if (viewModel.isSpeechRecognitionAvailable) {
                BigActionButton(
                    label = if (isBusy) (if (isBn) "থামুন" else "STOP") else (if (isBn) "প্রশ্ন করুন" else "ASK"),
                    subtitle = if (isBusy) (if (isBn) "বাতিল করতে ট্যাপ করুন" else "Tap to cancel active action") else (if (isBn) "মুখে প্রশ্ন বলুন" else "Speak a question about what is ahead"),
                    backgroundColor = AskLime,
                    textStyle = SecondaryActionButtonTextStyle,
                    contentDescription = if (isBusy) {
                        if (isBn) "চলমান কাজ বন্ধ করুন" else "Stop current operation"
                    } else {
                        if (isBn) "কথা বলে প্রশ্ন করুন। ছবি ও আপনার প্রশ্ন পাঠানো হবে।" else "Ask a question by speaking. Photo and voice question will be sent."
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.0f),
                    onClick = {
                        if (isBusy) {
                            viewModel.stop()
                        } else {
                            viewModel.onAskTrigger()
                        }
                    }
                )
            } else {
                // Notice when Speech Recognizer is not present on device
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.7f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurface)
                        .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Ask mode unavailable (Speech recognition not supported on this device)",
                        color = SecondaryTextWhite,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * High-contrast, large touch-target button optimized for blind and low-vision users.
 */
@Composable
private fun BigActionButton(
    label: String,
    subtitle: String,
    backgroundColor: Color,
    textStyle: androidx.compose.ui.text.TextStyle,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .semantics {
                this.contentDescription = contentDescription
            },
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = ButtonTextDark
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Text(
                text = label,
                style = textStyle,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = ButtonTextDark.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
        }
    }
}
