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
 * SpeechRecognizer wrapper for Vision Agent's "Ask" mode.
 *
 * Handles:
 * - Hardware availability check via [isAvailable].
 * - Pre-listening audio tone beep (using [ToneGenerator]).
 * - Speech recognition with language selection ("en-US" or "bn-BD").
 * - Clean error mapping (e.g. "I didn't hear a question." for timeouts or empty input).
 * - Safe dispatch on Android's Main thread looper.
 */
class Listener(private val context: Context) {

    companion object {
        private const val TAG = "VisionAgentListener"

        /**
         * Checks if speech recognition services are available on this device.
         */
        fun isRecognitionAvailable(context: Context): Boolean {
            return SpeechRecognizer.isRecognitionAvailable(context)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var toneGenerator: ToneGenerator? = null

    private var isListening = false
    private var isCancelledByUser = false
    private var activeLanguage = "en"

    private var onReadyCallback: (() -> Unit)? = null
    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onEndOfSpeechCallback: (() -> Unit)? = null

    /**
     * Checks if speech recognition is available on this device.
     */
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
     * Starts listening for user speech with the given language.
     *
     * @param language "en" for en-US or "bn" for bn-BD.
     * @param onReady Called when the microphone is open and ready to record.
     * @param onEndOfSpeech Called when the user stops speaking.
     * @param onResult Called with the recognized text string.
     * @param onError Called with a friendly spoken error description if recognition fails.
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

            isCancelledByUser = false
            activeLanguage = language
            onReadyCallback = onReady
            onEndOfSpeechCallback = onEndOfSpeech
            onResultCallback = onResult
            onErrorCallback = onError

            ensureRecognizer()

            // Play short audio tone right before listening begins
            playBeepTone()

            val localeTag = if (language.equals("bn", ignoreCase = true)) "bn-BD" else "en-US"
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            try {
                speechRecognizer?.startListening(intent)
                isListening = true
            } catch (e: Exception) {
                isListening = false
                Log.e(TAG, "Error starting speech recognition: ${e.message}", e)
                val msg = if (language.equals("bn", ignoreCase = true)) {
                    "কথা শুনতে সমস্যা হয়েছে। আবার চেষ্টা করুন।"
                } else {
                    "Failed to start speech recognition. Please try again."
                }
                onError(msg)
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
            isListening = false
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling listener: ${e.message}")
            }
            clearCallbacks()
        }
    }

    /**
     * Releases speech recognizer and audio tone resources.
     */
    fun destroy() {
        runOnMainThread {
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

    private fun clearCallbacks() {
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
