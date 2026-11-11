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
        private const val RESTART_DELAY_MS = 250L

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
            val text = spokenText.lowercase().trim()
            val isBn = language.equals("bn", ignoreCase = true)

            // 1. Universal Stop
            if (text == "stop vision" || text == "vision stop" || text == "stop" ||
                text.contains("stop vision") || text.contains("vision stop") ||
                (isBn && (text.contains("থামো") || text.contains("বন্ধ করো")))) {
                return VoiceCommand.Stop
            }

            // 2. Describe ("tell me vision", "vision describe", "what are you seeing")
            if (text.contains("tell me vision") ||
                text.contains("vision describe") ||
                text.contains("what are you seeing") ||
                text.contains("what do you see") ||
                text.contains("describe what you see") ||
                text.contains("describe what are you seeing") ||
                text == "describe" ||
                (isBn && (text.contains("বর্ণনা করো") || text.contains("কি দেখছো") || text.contains("বলো ভিশন")))) {
                return VoiceCommand.Describe
            }

            // 3. Read Text ("vision read", "read text", "read this")
            if (text.contains("vision read") ||
                text.contains("read text") ||
                text.contains("read this") ||
                text == "read" ||
                (isBn && (text.contains("পড়ে শোনাও") || text.contains("লেখা পড়ো")))) {
                return VoiceCommand.ReadText
            }

            // 4. Ask mode / "Vision" wake word
            if (text.startsWith("vision") || text.startsWith("hey vision") || text.contains("vision ask") ||
                (isBn && text.startsWith("ভিশন"))) {
                val cleaned = text
                    .removePrefix("hey vision")
                    .removePrefix("vision")
                    .removePrefix("ভিশন")
                    .trim()
                    .removePrefix("ask")
                    .removePrefix(",")
                    .trim()
                val directQuestion = if (cleaned.length >= 3) cleaned else null
                return VoiceCommand.Ask(directQuestion)
            }

            return null
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var toneGenerator: ToneGenerator? = null

    private var isListening = false
    private var isCancelledByUser = false
    private var activeLanguage = "en"

    // Single-shot callbacks
    private var onReadyCallback: (() -> Unit)? = null
    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onEndOfSpeechCallback: (() -> Unit)? = null

    // Continuous voice command listening state
    private var isContinuousListening = false
    private var isContinuousListeningPaused = false
    private var onCommandCallback: ((VoiceCommand) -> Unit)? = null
    private val restartRunnable = Runnable { restartContinuousListening() }

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator initialization failed: ${e.message}")
        }
    }

    private fun ensureRecognizer() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
            }
        }
    }

    private fun playBeepTone() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
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
            isContinuousListening = true
            isContinuousListeningPaused = false
            activeLanguage = language
            onCommandCallback = onCommand

            startRecognitionInternal(withBeep = false)
        }
    }

    /**
     * Temporarily pauses continuous voice listening (e.g. while capturing, speaking, or in settings).
     */
    fun pauseContinuousListening() {
        runOnMainThread {
            mainHandler.removeCallbacks(restartRunnable)
            isContinuousListeningPaused = true
            if (isListening) {
                cancelInternal()
            }
        }
    }

    /**
     * Resumes continuous voice listening if it was active.
     */
    fun resumeContinuousListening() {
        runOnMainThread {
            if (isContinuousListening && isContinuousListeningPaused) {
                isContinuousListeningPaused = false
                mainHandler.removeCallbacks(restartRunnable)
                mainHandler.postDelayed(restartRunnable, RESTART_DELAY_MS)
            }
        }
    }

    /**
     * Completely stops continuous voice listening.
     */
    fun stopContinuousCommandListening() {
        runOnMainThread {
            isContinuousListening = false
            isContinuousListeningPaused = false
            onCommandCallback = null
            mainHandler.removeCallbacks(restartRunnable)
            if (isListening) {
                cancelInternal()
            }
        }
    }

    private fun restartContinuousListening() {
        if (isContinuousListening && !isContinuousListeningPaused && !isListening) {
            startRecognitionInternal(withBeep = false)
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

            // Temporarily suspend continuous listener during single-shot Ask mode
            mainHandler.removeCallbacks(restartRunnable)

            isCancelledByUser = false
            activeLanguage = language
            onReadyCallback = onReady
            onEndOfSpeechCallback = onEndOfSpeech
            onResultCallback = onResult
            onErrorCallback = onError

            startRecognitionInternal(withBeep = true)
        }
    }

    private fun startRecognitionInternal(withBeep: Boolean) {
        ensureRecognizer()

        if (withBeep) {
            playBeepTone()
        }

        val localeTag = if (activeLanguage.equals("bn", ignoreCase = true)) "bn-BD" else "en-US"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            isListening = false
            Log.e(TAG, "Error starting speech recognition: ${e.message}", e)
            if (isContinuousListening && !isContinuousListeningPaused) {
                mainHandler.postDelayed(restartRunnable, 1000L)
            } else {
                val msg = if (activeLanguage.equals("bn", ignoreCase = true)) {
                    "কথা শুনতে সমস্যা হয়েছে। আবার চেষ্টা করুন।"
                } else {
                    "Failed to start speech recognition. Please try again."
                }
                onErrorCallback?.invoke(msg)
            }
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
            cancelInternal()
            clearSingleShotCallbacks()
        }
    }

    private fun cancelInternal() {
        isListening = false
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling listener: ${e.message}")
        }
    }

    /**
     * Releases speech recognizer and audio tone resources.
     */
    fun destroy() {
        runOnMainThread {
            stopContinuousCommandListening()
            cancel()
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying speech recognizer: ${e.message}")
            }
            speechRecognizer = null

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

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onReadyCallback?.invoke()
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
                onEndOfSpeechCallback?.invoke()
            }

            override fun onError(error: Int) {
                isListening = false
                if (isCancelledByUser) {
                    return
                }

                // If continuous command mode is running:
                if (isContinuousListening && !isContinuousListeningPaused) {
                    // Recreate recognizer if client or busy error
                    if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        try {
                            speechRecognizer?.destroy()
                        } catch (_: Exception) {}
                        speechRecognizer = null
                    }
                    mainHandler.removeCallbacks(restartRunnable)
                    mainHandler.postDelayed(restartRunnable, RESTART_DELAY_MS)
                    return
                }

                // Single-shot Ask mode error reporting
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
                    else -> {
                        if (isBn) "আমি কোনো প্রশ্ন শুনতে পাইনি।" else "I didn't hear a question."
                    }
                }

                onErrorCallback?.invoke(errorMessage)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                if (isCancelledByUser) {
                    return
                }

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull()?.trim()

                // Check continuous command listening first
                if (isContinuousListening && !isContinuousListeningPaused) {
                    if (!spokenText.isNullOrEmpty()) {
                        Log.d(TAG, "Continuous listener heard: $spokenText")
                        val command = parseVoiceCommand(spokenText, activeLanguage)
                        if (command != null) {
                            Log.d(TAG, "Recognized Voice Command: $command")
                            onCommandCallback?.invoke(command)
                            return
                        }
                    }
                    // No command recognized -> smoothly continue listening
                    mainHandler.removeCallbacks(restartRunnable)
                    mainHandler.postDelayed(restartRunnable, RESTART_DELAY_MS)
                    return
                }

                // Single shot Ask mode
                if (!spokenText.isNullOrEmpty()) {
                    onResultCallback?.invoke(spokenText)
                } else {
                    val fallback = if (activeLanguage.equals("bn", ignoreCase = true)) {
                        "আমি কোনো প্রশ্ন শুনতে পাইনি।"
                    } else {
                        "I didn't hear a question."
                    }
                    onErrorCallback?.invoke(fallback)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
}
