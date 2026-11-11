package com.visionagent.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visionagent.VisionViewModel
import com.visionagent.ai.GeminiException
import com.visionagent.ui.theme.AskLime
import com.visionagent.ui.theme.ButtonTextDark
import com.visionagent.ui.theme.DarkBorder
import com.visionagent.ui.theme.DarkSurface
import com.visionagent.ui.theme.DarkSurfaceElevated
import com.visionagent.ui.theme.DescribeYellow
import com.visionagent.ui.theme.HighContrastError
import com.visionagent.ui.theme.HighContrastWhite
import com.visionagent.ui.theme.PureBlack
import com.visionagent.ui.theme.ReadCyan
import com.visionagent.ui.theme.SecondaryTextWhite
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Accessible Settings Screen for Vision Agent.
 *
 * Controls:
 * - Back button with large touch target (>= 48dp).
 * - Language selection: English vs Bangla.
 * - Speech rate slider (0.75x to 1.5x) with accessible percentage/rate readout.
 * - Gemini model text field (defaults to "gemini-flash-lite-latest").
 * - Gemini API key password field with show/hide toggle.
 * - "Test API Key" button: performs a tiny test request and speaks result aloud.
 * - Camera preview switch: toggles 25% sighted helper preview on main screen.
 * - TTS Settings shortcut button: opens system Text-to-Speech settings.
 * - Speech recognition status indicator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: VisionViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsState()

    var apiKeyInput by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var modelInput by remember(settings.model) { mutableStateOf(settings.model) }
    var passwordVisible by remember { mutableStateOf(false) }

    var isTestingKey by remember { mutableStateOf(false) }
    var testResultStatus by remember { mutableStateOf<String?>(null) }
    var testResultIsError by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PureBlack)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        // --- TOP APP BAR ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PureBlack)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Back to main screen" }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = HighContrastWhite,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "SETTINGS",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    color = HighContrastWhite,
                    letterSpacing = 1.sp
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // --- SECTION 1: LANGUAGE ---
            SettingsSection(title = "RESPONSE LANGUAGE") {
                val languages = listOf(
                    "en" to "English (US)",
                    "bn" to "বাংলা / Bangla (BD)"
                )
                languages.forEach { (langCode, langTitle) ->
                    val isSelected = settings.language.equals(langCode, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .selectable(
                                selected = isSelected,
                                onClick = { viewModel.updateLanguage(langCode) }
                            )
                            .padding(vertical = 12.dp, horizontal = 8.dp)
                            .semantics {
                                contentDescription = "$langTitle, ${if (isSelected) "selected" else "not selected"}"
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { viewModel.updateLanguage(langCode) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = DescribeYellow,
                                unselectedColor = SecondaryTextWhite
                            )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = langTitle,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) DescribeYellow else HighContrastWhite
                            )
                        )
                    }
                }
            }

            // --- SECTION 2: SPEECH RATE SLIDER ---
            SettingsSection(title = "SPEECH RATE") {
                val formattedRate = String.format(Locale.US, "%.2fx", settings.speechRate)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Current speed:",
                        style = MaterialTheme.typography.bodyLarge,
                        color = SecondaryTextWhite
                    )
                    Text(
                        text = formattedRate,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Black,
                            color = DescribeYellow
                        )
                    )
                }

                Slider(
                    value = settings.speechRate,
                    onValueChange = { newRate ->
                        viewModel.updateSpeechRate(newRate)
                    },
                    valueRange = 0.75f..1.5f,
                    steps = 5,
                    colors = SliderDefaults.colors(
                        thumbColor = DescribeYellow,
                        activeTrackColor = DescribeYellow,
                        inactiveTrackColor = DarkBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .semantics {
                            contentDescription = "Speech rate slider, currently $formattedRate"
                        }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "0.75x (Slower)", style = MaterialTheme.typography.bodySmall, color = SecondaryTextWhite)
                    Text(text = "1.00x (Normal)", style = MaterialTheme.typography.bodySmall, color = SecondaryTextWhite)
                    Text(text = "1.50x (Faster)", style = MaterialTheme.typography.bodySmall, color = SecondaryTextWhite)
                }
            }

            // --- SECTION 3: GEMINI MODEL ---
            SettingsSection(title = "GEMINI MODEL NAME") {
                Text(
                    text = "Default: gemini-flash-lite-latest. You can enter any current Gemini Flash model from Google AI Studio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondaryTextWhite,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = modelInput,
                    onValueChange = {
                        modelInput = it
                        viewModel.updateModel(it)
                    },
                    label = { Text("Model Name", color = SecondaryTextWhite) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = HighContrastWhite,
                        unfocusedTextColor = HighContrastWhite,
                        focusedBorderColor = DescribeYellow,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = DescribeYellow
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Gemini Model Name text field" }
                )
            }

            // --- SECTION 4: GEMINI API KEY ---
            SettingsSection(title = "GEMINI API KEY") {
                Text(
                    text = "Personal prototype note: Custom key overrides BuildConfig key and is stored locally in DataStore. For public production, route via backend or Firebase AI Logic.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryTextWhite,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = {
                        apiKeyInput = it
                        viewModel.updateApiKey(it)
                    },
                    label = { Text("API Key", color = SecondaryTextWhite) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            modifier = Modifier.semantics {
                                contentDescription = if (passwordVisible) "Hide API key" else "Show API key"
                            }
                        ) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = HighContrastWhite
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = HighContrastWhite,
                        unfocusedTextColor = HighContrastWhite,
                        focusedBorderColor = DescribeYellow,
                        unfocusedBorderColor = DarkBorder,
                        focusedLabelColor = DescribeYellow
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Gemini API Key text field" }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Test API Key Button
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isTestingKey = true
                            testResultStatus = "Testing API key connection..."
                            testResultIsError = false

                            val keyToTest = apiKeyInput.trim().ifEmpty { viewModel.getEffectiveApiKey() }
                            val modelToTest = modelInput.trim()

                            val result = viewModel.testApiKey(keyToTest, modelToTest)
                            isTestingKey = false
                            result.fold(
                                onSuccess = { msg ->
                                    testResultStatus = msg
                                    testResultIsError = false
                                },
                                onFailure = { error ->
                                    val err = if (error is GeminiException) error.spokenMessage else error.message ?: "Test failed"
                                    testResultStatus = err
                                    testResultIsError = true
                                }
                            )
                        }
                    },
                    enabled = !isTestingKey,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ReadCyan,
                        contentColor = ButtonTextDark
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .semantics {
                            contentDescription = "Test API key connection and verify with Google AI Studio"
                        }
                ) {
                    if (isTestingKey) {
                        CircularProgressIndicator(
                            color = ButtonTextDark,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Verifying...", fontWeight = FontWeight.Bold, color = ButtonTextDark)
                    } else {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = ButtonTextDark)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("TEST API KEY ALOUD", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    }
                }

                if (testResultStatus != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = testResultStatus.orEmpty(),
                        color = if (testResultIsError) HighContrastError else AskLime,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.semantics {
                            contentDescription = "Test result: ${testResultStatus.orEmpty()}"
                        }
                    )
                }
            }

            // --- SECTION 5: CAMERA PREVIEW DISPLAY ---
            SettingsSection(title = "CAMERA PREVIEW DISPLAY") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show Sighted Helper Preview",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = HighContrastWhite
                        )
                        Text(
                            text = "When OFF, camera works smoothly in the background with zero lag. Turn ON for a 25% preview for sighted helpers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondaryTextWhite
                        )
                    }

                    Switch(
                        checked = settings.showCameraPreview,
                        onCheckedChange = { viewModel.updateShowCameraPreview(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DescribeYellow,
                            checkedTrackColor = DescribeYellow.copy(alpha = 0.5f),
                            uncheckedThumbColor = SecondaryTextWhite,
                            uncheckedTrackColor = DarkSurfaceElevated
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = "Show camera preview toggle, currently ${if (settings.showCameraPreview) "enabled" else "disabled"}"
                        }
                    )
                }
            }

            // --- SECTION 6: HANDS-FREE VOICE COMMANDS ---
            SettingsSection(title = "HANDS-FREE VOICE CONTROL ('VISION')") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hands-Free Voice Commands",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = HighContrastWhite
                        )
                        Text(
                            text = "Listen in background for 'Vision', 'Tell me vision', or 'Stop vision' without touching buttons.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondaryTextWhite
                        )
                    }

                    Switch(
                        checked = settings.voiceCommandsEnabled,
                        onCheckedChange = { viewModel.updateVoiceCommandsEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AskLime,
                            checkedTrackColor = AskLime.copy(alpha = 0.5f),
                            uncheckedThumbColor = SecondaryTextWhite,
                            uncheckedTrackColor = DarkSurfaceElevated
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = "Hands-free voice control toggle, currently ${if (settings.voiceCommandsEnabled) "enabled" else "disabled"}"
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkSurfaceElevated, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "Voice Commands:", fontWeight = FontWeight.Bold, color = DescribeYellow, fontSize = 14.sp)
                    Text(text = "• \"Vision\" -> Opens Ask mode for your question", color = HighContrastWhite, fontSize = 13.sp)
                    Text(text = "• \"Tell me vision\" or \"What are you seeing\" -> Describes the scene", color = HighContrastWhite, fontSize = 13.sp)
                    Text(text = "• \"Vision read\" or \"Read text\" -> Reads visible text", color = HighContrastWhite, fontSize = 13.sp)
                    Text(text = "• \"Stop vision\" or \"Stop\" -> Instantly stops speaking", color = HighContrastWhite, fontSize = 13.sp)
                }
            }

            // --- SECTION 6: SYSTEM TTS SHORTCUT ---
            SettingsSection(title = "TEXT-TO-SPEECH SYSTEM SETTINGS") {
                Text(
                    text = "If Bangla speech does not play, install Google Text-to-Speech Bangla voice data in Android system settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryTextWhite,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                        } catch (_: Exception) {
                            try {
                                context.startActivity(Intent(Settings.ACTION_SETTINGS))
                            } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .semantics {
                            contentDescription = "Open system Text-to-Speech settings to install language voices"
                        },
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(DescribeYellow)
                    )
                ) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = DescribeYellow)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "OPEN TTS SYSTEM SETTINGS",
                        color = DescribeYellow,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // --- SECTION 7: SPEECH RECOGNIZER STATUS ---
            SettingsSection(title = "SPEECH RECOGNITION STATUS") {
                val available = viewModel.isSpeechRecognitionAvailable
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = if (available) Icons.Default.Check else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (available) AskLime else HighContrastError
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (available) {
                            "Speech Recognizer is available on this device"
                        } else {
                            "Speech Recognizer is NOT available on this device"
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (available) AskLime else HighContrastError,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

/**
 * Reusable accessible container card for a settings section.
 */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Black,
                color = DescribeYellow,
                letterSpacing = 0.5.sp
            ),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        content()
    }
}
