package com.visionagent

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.visionagent.ai.GeminiClient
import com.visionagent.ai.GeminiException
import com.visionagent.camera.CameraController
import com.visionagent.data.SettingsData
import com.visionagent.data.SettingsRepository
import com.visionagent.speech.Listener
import com.visionagent.speech.Speaker
import com.visionagent.util.Haptics
import com.visionagent.util.ImageUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * State machine representing the user-facing phases of Vision Agent.
 */
sealed class UiState {
    object Idle : UiState()
    object Capturing : UiState()
    object Thinking : UiState()
    object Speaking : UiState()
    object Listening : UiState()
    data class Error(val message: String) : UiState()
}

/**
 * Central ViewModel orchestrating camera capture, AI analysis, speech I/O, haptics, and accessibility.
 *
 * Enforces key requirements:
 * - Silent by default: captures and speaks strictly on demand.
 * - Single-shot operation: no background looping.
 * - Universal Stop: Any trigger or volume key press while busy cancels active jobs, silences audio, and returns to Idle.
 * - Busy Guard: Rejects new action requests when already in progress (acting as Stop instead).
 * - Latency tracking: Logs duration from trigger to speech start via Log.d("VisionAgent", ...).
 * - Thinking ticker: Pulsing gentle haptic tick every 1500ms while waiting for Gemini.
 */
class VisionViewModel @JvmOverloads constructor(
    application: Application,
    val settingsRepository: SettingsRepository = SettingsRepository(application),
    val cameraController: CameraController = CameraController(application),
    val geminiClient: GeminiClient = GeminiClient(),
    val speaker: Speaker = Speaker(application),
    val listener: Listener = Listener(application),
    val haptics: Haptics = Haptics(application)
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VisionAgent"
        private const val TICKER_INTERVAL_MS = 1500L
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val settings: StateFlow<SettingsData> = settingsRepository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SettingsData()
        )

    private val _banglaMissingNotice = MutableStateFlow(false)
    val banglaMissingNotice: StateFlow<Boolean> = _banglaMissingNotice.asStateFlow()

    private var activeJob: Job? = null
    private var tickerJob: Job? = null

    val isSpeechRecognitionAvailable: Boolean
        get() = listener.isAvailable

    init {
        speaker.onBanglaMissing = {
            _banglaMissingNotice.value = true
        }
    }

    /**
     * Resolves the effective API key (User custom key in Settings if provided, else BuildConfig.GEMINI_API_KEY).
     */
    fun getEffectiveApiKey(): String {
        return settings.value.getEffectiveApiKey(BuildConfig.GEMINI_API_KEY)
    }

    /**
     * UNIVERSAL STOP:
     * If the app is speaking, listening, or thinking, cancels active operations, stops speech,
     * cancels recognizer, vibrates briefly, and resets to [UiState.Idle].
     */
    fun stop() {
        activeJob?.cancel()
        activeJob = null
        stopThinkingTicker()
        speaker.stop()
        listener.cancel()
        haptics.vibrateActionStart()
        _uiState.value = UiState.Idle
        Log.d(TAG, "Active action cancelled by user (Universal Stop)")
    }

    /**
     * Hardware Volume Up key pressed:
     * - If busy -> Acts as Stop.
     * - If idle -> Triggers Describe action.
     */
    fun onVolumeUpPressed() {
        if (_uiState.value !is UiState.Idle) {
            stop()
        } else {
            onDescribeTrigger()
        }
    }

    /**
     * Hardware Volume Down key pressed:
     * - If busy -> Acts as Stop.
     * - If idle -> Triggers Read Text action.
     */
    fun onVolumeDownPressed() {
        if (_uiState.value !is UiState.Idle) {
            stop()
        } else {
            onReadTextTrigger()
        }
    }

    /**
     * DESCRIBE ACTION:
     * Captures a single image from the back camera and requests a safe-walking scene description.
     */
    fun onDescribeTrigger() {
        if (_uiState.value !is UiState.Idle) {
            stop()
            return
        }

        executeVisualAction("Describe") { apiKey, base64Image, model, lang ->
            geminiClient.describe(
                apiKey = apiKey,
                base64Image = base64Image,
                model = model,
                language = lang
            )
        }
    }

    /**
     * READ TEXT ACTION:
     * Captures a single image and reads out all legible text in natural reading order.
     */
    fun onReadTextTrigger() {
        if (_uiState.value !is UiState.Idle) {
            stop()
            return
        }

        executeVisualAction("ReadText") { apiKey, base64Image, model, lang ->
            geminiClient.readText(
                apiKey = apiKey,
                base64Image = base64Image,
                model = model,
                language = lang
            )
        }
    }

    /**
     * ASK ACTION:
     * 1. Listens for user spoken question via SpeechRecognizer.
     * 2. Immediately takes a back camera photo upon question capture.
     * 3. Sends photo + voice question to Gemini and speaks the answer.
     */
    fun onAskTrigger() {
        if (_uiState.value !is UiState.Idle) {
            stop()
            return
        }

        val currentSettings = settings.value
        if (!isSpeechRecognitionAvailable) {
            val errorMsg = if (currentSettings.language.equals("bn", ignoreCase = true)) {
                "এই ডিভাইসে স্পিচ রিকগনিশন সমর্থিত নয়।"
            } else {
                "Speech recognition is not available on this device."
            }
            speakError(errorMsg, currentSettings)
            return
        }

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            _uiState.value = UiState.Listening
            haptics.vibrateActionStart()

            listener.startListening(
                language = currentSettings.language,
                onReady = {
                    Log.d(TAG, "Listener ready for user speech")
                },
                onResult = { spokenQuestion ->
                    Log.d(TAG, "Spoken question recognized: $spokenQuestion")
                    processAskWithImage(spokenQuestion, startTime)
                },
                onError = { errorMessage ->
                    Log.w(TAG, "Speech listener error: $errorMessage")
                    speakError(errorMessage, currentSettings)
                }
            )
        }
    }

    private fun processAskWithImage(question: String, startTime: Long) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            val currentSettings = settings.value
            val effectiveKey = getEffectiveApiKey()
            if (effectiveKey.isBlank()) {
                val errorMsg = geminiClient.mapHttpError(400, currentSettings.language)
                speakError(errorMsg, currentSettings)
                return@launch
            }

            try {
                // 1. Capture photo
                _uiState.value = UiState.Capturing
                val imageProxy = cameraController.captureImage()

                // 2. Transition to Thinking & start ticker
                _uiState.value = UiState.Thinking
                startThinkingTicker()

                // 3. Process image to base64 jpeg
                val base64Image = ImageUtils.processImageForGemini(imageProxy)

                // 4. Send to Gemini
                val result = geminiClient.ask(
                    apiKey = effectiveKey,
                    base64Image = base64Image,
                    question = question,
                    model = currentSettings.model,
                    language = currentSettings.language
                )

                stopThinkingTicker()

                result.fold(
                    onSuccess = { answer ->
                        val latency = System.currentTimeMillis() - startTime
                        Log.d(TAG, "Action Ask took $latency ms")
                        speakAnswer(answer, currentSettings)
                    },
                    onFailure = { error ->
                        val errorMsg = if (error is GeminiException) {
                            error.spokenMessage
                        } else {
                            geminiClient.mapNetworkException(error, currentSettings.language)
                        }
                        speakError(errorMsg, currentSettings)
                    }
                )
            } catch (e: CancellationException) {
                stopThinkingTicker()
                throw e
            } catch (e: Exception) {
                stopThinkingTicker()
                Log.e(TAG, "Error in processAskWithImage: ${e.message}", e)
                val errorMsg = geminiClient.mapNetworkException(e, currentSettings.language)
                speakError(errorMsg, currentSettings)
            }
        }
    }

    /**
     * Executes standard visual requests (Describe or Read Text).
     */
    private fun executeVisualAction(
        actionName: String,
        callGemini: suspend (apiKey: String, base64: String, model: String, language: String) -> Result<String>
    ) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val currentSettings = settings.value
            val effectiveKey = getEffectiveApiKey()

            if (effectiveKey.isBlank()) {
                val errorMsg = geminiClient.mapHttpError(400, currentSettings.language)
                speakError(errorMsg, currentSettings)
                return@launch
            }

            try {
                // 1. Start action
                haptics.vibrateActionStart()
                _uiState.value = UiState.Capturing

                // 2. Capture image frame
                val imageProxy = cameraController.captureImage()

                // 3. Enter Thinking phase & start ticker
                _uiState.value = UiState.Thinking
                startThinkingTicker()

                // 4. Downscale & compress to Base64
                val base64Image = ImageUtils.processImageForGemini(imageProxy)

                // 5. Call Gemini REST endpoint
                val result = callGemini(
                    effectiveKey,
                    base64Image,
                    currentSettings.model,
                    currentSettings.language
                )

                stopThinkingTicker()

                result.fold(
                    onSuccess = { answer ->
                        val latency = System.currentTimeMillis() - startTime
                        Log.d(TAG, "Action $actionName took $latency ms")
                        speakAnswer(answer, currentSettings)
                    },
                    onFailure = { error ->
                        val errorMsg = if (error is GeminiException) {
                            error.spokenMessage
                        } else {
                            geminiClient.mapNetworkException(error, currentSettings.language)
                        }
                        speakError(errorMsg, currentSettings)
                    }
                )
            } catch (e: CancellationException) {
                stopThinkingTicker()
                throw e
            } catch (e: Exception) {
                stopThinkingTicker()
                Log.e(TAG, "Error executing action $actionName: ${e.message}", e)
                val errorMsg = geminiClient.mapNetworkException(e, currentSettings.language)
                speakError(errorMsg, currentSettings)
            }
        }
    }

    private fun speakAnswer(answer: String, currentSettings: SettingsData) {
        _uiState.value = UiState.Speaking
        haptics.vibrateAnswerStart()
        speaker.speak(
            text = answer,
            language = currentSettings.language,
            speechRate = currentSettings.speechRate,
            onDone = {
                if (_uiState.value is UiState.Speaking) {
                    _uiState.value = UiState.Idle
                }
            }
        )
    }

    private fun speakError(errorMessage: String, currentSettings: SettingsData) {
        _uiState.value = UiState.Error(errorMessage)
        haptics.vibrateError()
        speaker.speak(
            text = errorMessage,
            language = currentSettings.language,
            speechRate = currentSettings.speechRate,
            onDone = {
                if (_uiState.value is UiState.Error) {
                    _uiState.value = UiState.Idle
                }
            }
        )
    }

    private fun startThinkingTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(TICKER_INTERVAL_MS)
                haptics.vibrateGentleTick()
            }
        }
    }

    private fun stopThinkingTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    /**
     * Tests a candidate Gemini API key and speaks the result aloud.
     */
    suspend fun testApiKey(apiKey: String, model: String): Result<String> {
        val lang = settings.value.language
        val speechRate = settings.value.speechRate
        val result = geminiClient.testApiKey(apiKey = apiKey, model = model, language = lang)
        result.fold(
            onSuccess = { message ->
                speaker.speak(message, language = lang, speechRate = speechRate)
            },
            onFailure = { error ->
                val errorMsg = if (error is GeminiException) {
                    error.spokenMessage
                } else {
                    geminiClient.mapNetworkException(error, lang)
                }
                speaker.speak(errorMsg, language = lang, speechRate = speechRate)
            }
        )
        return result
    }

    // Setting update delegates
    fun updateApiKey(apiKey: String) {
        viewModelScope.launch { settingsRepository.updateApiKey(apiKey) }
    }

    fun updateModel(model: String) {
        viewModelScope.launch { settingsRepository.updateModel(model) }
    }

    fun updateLanguage(language: String) {
        viewModelScope.launch { settingsRepository.updateLanguage(language) }
    }

    fun updateSpeechRate(rate: Float) {
        viewModelScope.launch { settingsRepository.updateSpeechRate(rate) }
    }

    fun updateShowCameraPreview(show: Boolean) {
        viewModelScope.launch { settingsRepository.updateShowPreview(show) }
    }

    fun dismissBanglaMissingNotice() {
        _banglaMissingNotice.value = false
    }

    override fun onCleared() {
        super.onCleared()
        stop()
        cameraController.shutdown()
        speaker.shutdown()
        listener.destroy()
        haptics.cancel()
    }
}
