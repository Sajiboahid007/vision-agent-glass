package com.visionagent.speech

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Commands recognized from hands-free speech input.
 */
sealed class VoiceCommand {
    /**
     * Triggers Ask mode. If [directQuestion] is non-null, question is already provided.
     */
    data class Ask(val directQuestion: String? = null) : VoiceCommand()

    /**
     * Triggers Describe mode ("tell me vision", "vision describe", "what are you seeing").
     */
    object Describe : VoiceCommand()

    /**
     * Triggers Read Text mode ("vision read", "read text").
     */
    object ReadText : VoiceCommand()

    /**
     * Triggers Live Continuous Vision mode while moving ("live read", "live vision", "live text").
     */
    object LiveRead : VoiceCommand()

    /**
     * Triggers Universal Stop ("stop vision", "stop").
     */
    object Stop : VoiceCommand()
}

/**
 * SpeechRecognizer wrapper supporting:
 * 1. Single-shot Ask mode listening with audio beep.
 * 2. Background continuous hands-free voice command wake listening ("Vision", "Stop vision", "Tell me vision").
 */
class Listener(private val context: Context) {

    companion object {
        private const val TAG = "VisionAgentListener"
        private const val RESTART_DELAY_MS = 350L
        private const val BEEP_DURATION_MS = 150
        private const val BEEP_SETTLE_DELAY_MS = 200L

        /**
         * Checks if speech recognition services are available on this device.
         */
        fun isRecognitionAvailable(context: Context): Boolean {
            return SpeechRecognizer.isRecognitionAvailable(context)
        }

        /**
         * Parses spoken text for Vision Agent voice commands.
         */
        fun parseVoiceCommand(spokenText: String, language: String): VoiceCommand? {
            val text = spokenText
                .lowercase()
                .trim()
                .replace(Regex("[.,?!।]+$"), "")
                .trim()
            val isBn = language.equals("bn", ignoreCase = true)

            // 1. Universal Stop
            if (text == "stop" || text == "stop vision" || text == "vision stop" ||
                text.contains("stop vision") || text.contains("vision stop") ||
                (isBn && (text == "থামো" || text.contains("থামো") || text.contains("বন্ধ করো") || text.contains("থামাও")))) {
                return VoiceCommand.Stop
            }

            // 2. Describe ("tell me vision", "vision describe", "what are you seeing", "describe")
            if (text == "describe" || text == "vision describe" || text == "tell me vision" ||
                text.contains("tell me vision") || text.contains("tell vision") || text.contains("vision tell") ||
                text.contains("vision describe") || text.contains("what are you seeing") ||
                text.contains("what do you see") || text.contains("describe what you see") ||
                text.contains("describe what are you seeing") || text.contains("describe scene") ||
                (isBn && (text.contains("বর্ণনা করো") || text.contains("বর্ণনা") || text.contains("কি দেখছো") || text.contains("বলো ভিশন")))) {
                return VoiceCommand.Describe
            }

            // 3. Live Continuous Vision / Safe Walking
            if (text == "live" || text == "live vision" || text == "live camera" ||
                text == "live read" || text == "live reading" || text == "live text" ||
                text.contains("live vision") || text.contains("live camera") ||
                text.contains("live read") || text.contains("live reading") ||
                text.contains("live text") || text.contains("read live") ||
                (isBn && (text.contains("লাইভ") || text.contains("লাইভ ভিশন") || text.contains("লাইভ ক্যামেরা") || text.contains("লাইভ পড়ো")))) {
                return VoiceCommand.LiveRead
            }

            // 4. Read Text ("vision read", "read text", "read this", "read")
            if (text == "read" || text == "read text" || text == "vision read" ||
                text.contains("vision read") || text.contains("read text") || text.contains("read this") ||
                (isBn && (text.contains("পড়ে শোনাও") || text.contains("লেখা পড়ো") || text.contains("পড়ো")))) {
                return VoiceCommand.ReadText
            }

            // 5. Ask mode / "Vision" wake word / "Ask"
            if (text == "ask" || text.startsWith("ask ") ||
                text.startsWith("vision") || text.startsWith("hey vision") || text.contains("vision ask") ||
                (isBn && (text.startsWith("ভিশন") || text.contains("জিজ্ঞাসা") || text.contains("প্রশ্ন")))) {
                var cleaned = text
                    .removePrefix("hey vision")
                    .removePrefix("vision")
                    .removePrefix("ভিশন")
                    .trim()
                cleaned = cleaned
                    .removePrefix("ask vision")
                    .removePrefix("ask")
                    .removePrefix("vision")
                    .removePrefix("ভিশন")
                    .removePrefix("প্রশ্ন")
                    .removePrefix("জিজ্ঞাসা")
                    .trim()
                    .removePrefix(",")
                    .trim()
                val directQuestion = if (cleaned.length >= 3) cleaned else null
                return VoiceCommand.Ask(directQuestion)
            }

            return null
        }
    }

    private enum class Mode {
        IDLE,
        CONTINUOUS,
        SINGLE_SHOT
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var toneGenerator: ToneGenerator? = null

    private var activeMode = Mode.IDLE
    private var isListening = false
    private var isCancelledByUser = false
    private var activeLanguage = "en"
    private var currentSessionId = 0L

    // Continuous voice command listening state
    private var isContinuousConfigured = false
    private var isContinuousPaused = false
    private var onCommandCallback: ((VoiceCommand) -> Unit)? = null
    private val restartRunnable = Runnable { restartContinuousListening() }

    // Single-shot callbacks
    private var onReadyCallback: (() -> Unit)? = null
    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onEndOfSpeechCallback: (() -> Unit)? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator initialization failed: ${e.message}")
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying speech recognizer: ${e.message}")
        }
        speechRecognizer = null
        isListening = false
    }

    private fun playBeepTone() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play beep tone: ${e.message}")
        }
    }

    /**
     * Starts continuous background listening for hands-free voice commands.
     * Listens quietly without beeps when the app is in Idle state.
     */
    fun startContinuousCommandListening(
        language: String = "en",
        onCommand: (VoiceCommand) -> Unit
    ) {
        runOnMainThread {
            if (!isAvailable) return@runOnMainThread
            isContinuousConfigured = true
            isContinuousPaused = false
            activeLanguage = language
            onCommandCallback = onCommand
            isCancelledByUser = false

            // Do not interrupt active Ask single-shot session
            if (activeMode != Mode.SINGLE_SHOT) {
                mainHandler.removeCallbacks(restartRunnable)
                destroyRecognizer()
                activeMode = Mode.CONTINUOUS
                startContinuousRecognizerInternal()
            }
        }
    }

    /**
     * Temporarily pauses continuous voice listening (e.g. while capturing, speaking, or in settings).
     */
    fun pauseContinuousListening() {
        runOnMainThread {
            isContinuousPaused = true
            mainHandler.removeCallbacks(restartRunnable)
            if (activeMode == Mode.CONTINUOUS) {
                destroyRecognizer()
                activeMode = Mode.IDLE
            }
        }
    }

    /**
     * Resumes continuous voice listening if it was active.
     */
    fun resumeContinuousListening() {
        runOnMainThread {
            if (isContinuousConfigured) {
                isContinuousPaused = false
                isCancelledByUser = false
                if (activeMode != Mode.SINGLE_SHOT) {
                    mainHandler.removeCallbacks(restartRunnable)
                    mainHandler.postDelayed(restartRunnable, RESTART_DELAY_MS)
                }
            }
        }
    }

    /**
     * Completely stops continuous voice listening.
     */
    fun stopContinuousCommandListening() {
        runOnMainThread {
            isContinuousConfigured = false
            isContinuousPaused = false
            onCommandCallback = null
            mainHandler.removeCallbacks(restartRunnable)
            if (activeMode == Mode.CONTINUOUS) {
                destroyRecognizer()
                activeMode = Mode.IDLE
            }
        }
    }

    private fun restartContinuousListening() {
        if (isContinuousConfigured && !isContinuousPaused && activeMode != Mode.SINGLE_SHOT && !isListening) {
            isCancelledByUser = false
            activeMode = Mode.CONTINUOUS
            startContinuousRecognizerInternal()
        }
    }

    private fun startContinuousRecognizerInternal() {
        destroyRecognizer()
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                }

                override fun onError(error: Int) {
                    isListening = false
                    if (isCancelledByUser || activeMode != Mode.CONTINUOUS) {
                        return
                    }

                    destroyRecognizer()
                    if (isContinuousConfigured && !isContinuousPaused) {
                        mainHandler.removeCallbacks(restartRunnable)
                        mainHandler.postDelayed(restartRunnable, RESTART_DELAY_MS)
                    } else {
                        activeMode = Mode.IDLE
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    if (isCancelledByUser || activeMode != Mode.CONTINUOUS) {
                        return
                    }

                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()?.trim()

                    if (isContinuousConfigured && !isContinuousPaused) {
                        if (!spokenText.isNullOrEmpty()) {
                            Log.d(TAG, "Continuous listener heard: $spokenText")
                            val command = parseVoiceCommand(spokenText, activeLanguage)
                            if (command != null) {
                                Log.d(TAG, "Recognized Voice Command: $command")
                                destroyRecognizer()
                                onCommandCallback?.invoke(command)
                                return
                            }
                        }
                        // No recognized command -> smoothly re-arm
                        destroyRecognizer()
                        mainHandler.removeCallbacks(restartRunnable)
                        mainHandler.postDelayed(restartRunnable, RESTART_DELAY_MS)
                    } else {
                        activeMode = Mode.IDLE
                        destroyRecognizer()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val localeTag = if (activeLanguage.equals("bn", ignoreCase = true)) "bn-BD" else "en-US"
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            recognizer.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            isListening = false
            Log.e(TAG, "Error starting continuous speech recognizer: ${e.message}", e)
            destroyRecognizer()
            if (isContinuousConfigured && !isContinuousPaused) {
                mainHandler.postDelayed(restartRunnable, 1000L)
            }
        }
    }

    /**
     * Starts single-shot listening for user speech with the given language (for Ask mode).
     */
    fun startListening(
        language: String = "en",
        onReady: (() -> Unit)? = null,
        onEndOfSpeech: (() -> Unit)? = null,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        runOnMainThread {
            if (!isAvailable) {
                val errorMsg = if (language.equals("bn", ignoreCase = true)) {
                    "এই ডিভাইসে স্পিচ রিকগনিশন সমর্থিত নয়।"
                } else {
                    "Speech recognition is not available on this device."
                }
                onError(errorMsg)
                return@runOnMainThread
            }

            // 1. Immediately pause continuous mode and cancel any pending restart callbacks
            isContinuousPaused = true
            mainHandler.removeCallbacks(restartRunnable)

            // 2. Completely destroy any active recognizer so no stale IPC callbacks can leak
            destroyRecognizer()

            // 3. Mark single-shot active and prepare callbacks
            val sessionId = ++currentSessionId
            activeMode = Mode.SINGLE_SHOT
            isCancelledByUser = false
            activeLanguage = language
            onReadyCallback = onReady
            onEndOfSpeechCallback = onEndOfSpeech
            onResultCallback = onResult
            onErrorCallback = onError

            // 4. Play beep tone
            playBeepTone()

            // 5. Allow the 150ms beep tone to completely finish before opening mic
            mainHandler.postDelayed({
                if (sessionId == currentSessionId && activeMode == Mode.SINGLE_SHOT && !isCancelledByUser) {
                    startSingleShotRecognizer(sessionId)
                }
            }, BEEP_SETTLE_DELAY_MS)
        }
    }

    private fun startSingleShotRecognizer(sessionId: Long) {
        if (sessionId != currentSessionId || activeMode != Mode.SINGLE_SHOT || isCancelledByUser) {
            return
        }

        try {
            destroyRecognizer()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (sessionId == currentSessionId && activeMode == Mode.SINGLE_SHOT && !isCancelledByUser) {
                        onReadyCallback?.invoke()
                    }
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isListening = false
                    if (sessionId == currentSessionId && activeMode == Mode.SINGLE_SHOT && !isCancelledByUser) {
                        onEndOfSpeechCallback?.invoke()
                    }
                }

                override fun onError(error: Int) {
                    isListening = false
                    if (sessionId != currentSessionId || isCancelledByUser || activeMode != Mode.SINGLE_SHOT) {
                        return
                    }

                    activeMode = Mode.IDLE
                    val errCb = onErrorCallback
                    clearSingleShotCallbacks()
                    destroyRecognizer()

                    val isBn = activeLanguage.equals("bn", ignoreCase = true)
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            if (isBn) "আমি কোনো প্রশ্ন শুনতে পাইনি।" else "I didn't hear a question."
                        }
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                            if (isBn) "কোনো ইন্টারনেট সংযোগ নেই।" else "No internet connection."
                        }
                        SpeechRecognizer.ERROR_AUDIO -> {
                            if (isBn) "মাইক্রোফোন ত্রুটি হয়েছে।" else "Audio recording error."
                        }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            if (isBn) "মাইক্রোফোনের অনুমতি প্রয়োজন।" else "Microphone permission is required."
                        }
                        SpeechRecognizer.ERROR_CLIENT,
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                            if (isBn) "কথা শুনতে সমস্যা হয়েছে। আবার চেষ্টা করুন।" else "Failed to start speech recognition. Please try again."
                        }
                        else -> {
                            if (isBn) "আমি কোনো প্রশ্ন শুনতে পাইনি।" else "I didn't hear a question."
                        }
                    }

                    errCb?.invoke(errorMessage)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    if (sessionId != currentSessionId || isCancelledByUser || activeMode != Mode.SINGLE_SHOT) {
                        return
                    }

                    activeMode = Mode.IDLE
                    val cb = onResultCallback
                    val errCb = onErrorCallback
                    clearSingleShotCallbacks()
                    destroyRecognizer()

                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()?.trim()

                    if (!spokenText.isNullOrEmpty()) {
                        cb?.invoke(spokenText)
                    } else {
                        val fallback = if (activeLanguage.equals("bn", ignoreCase = true)) {
                            "আমি কোনো প্রশ্ন শুনতে পাইনি।"
                        } else {
                            "I didn't hear a question."
                        }
                        errCb?.invoke(fallback)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val localeTag = if (activeLanguage.equals("bn", ignoreCase = true)) "bn-BD" else "en-US"
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            recognizer.startListening(intent)
            isListening = true
            Log.d(TAG, "Single-shot Ask mode recognizer started successfully (sessionId: $sessionId)")
        } catch (e: Exception) {
            isListening = false
            activeMode = Mode.IDLE
            Log.e(TAG, "Error starting single-shot speech recognition: ${e.message}", e)
            val msg = if (activeLanguage.equals("bn", ignoreCase = true)) {
                "কথা শুনতে সমস্যা হয়েছে। আবার চেষ্টা করুন।"
            } else {
                "Failed to start speech recognition. Please try again."
            }
            val cb = onErrorCallback
            clearSingleShotCallbacks()
            destroyRecognizer()
            cb?.invoke(msg)
        }
    }

    /**
     * Stops listening for speech and requests partial/final results.
     */
    fun stopListening() {
        runOnMainThread {
            if (isListening) {
                try {
                    speechRecognizer?.stopListening()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping listener: ${e.message}")
                }
                isListening = false
            }
        }
    }

    /**
     * Cancels any active recognition immediately without invoking error callbacks.
     */
    fun cancel() {
        runOnMainThread {
            isCancelledByUser = true
            currentSessionId++
            mainHandler.removeCallbacks(restartRunnable)
            clearSingleShotCallbacks()
            activeMode = Mode.IDLE
            destroyRecognizer()
        }
    }

    /**
     * Releases speech recognizer and audio tone resources.
     */
    fun destroy() {
        runOnMainThread {
            stopContinuousCommandListening()
            cancel()
            destroyRecognizer()

            try {
                toneGenerator?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing tone generator: ${e.message}")
            }
            toneGenerator = null
        }
    }

    private fun clearSingleShotCallbacks() {
        onReadyCallback = null
        onResultCallback = null
        onErrorCallback = null
        onEndOfSpeechCallback = null
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
