package com.visionagent.speech

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * TextToSpeech engine wrapper for Vision Agent.
 *
 * Features:
 * - Initializes TTS once with an [TextToSpeech.OnInitListener].
 * - Supports English (Locale.US) and Bangla (Locale("bn", "BD")).
 * - If Bangla voice data is missing or unsupported, notifies via [onBanglaMissing],
 *   falls back to English, and speaks an actionable explanation.
 * - Strips Markdown formatting (*, #, _, `, lists, links) before synthesis.
 * - Always uses [TextToSpeech.QUEUE_FLUSH] to interrupt older speech with new answers.
 * - Dispatches [onSpeechStarted] and [onSpeechDone] events on the main thread.
 */
class Speaker(
    private val context: Context,
    var onBanglaMissing: (() -> Unit)? = null
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "VisionAgentSpeaker"

        /**
         * Strips common markdown formatting characters and syntax to ensure clean spoken speech.
         */
        fun stripMarkdown(text: String): String {
            return text
                // Remove fenced code blocks
                .replace(Regex("""```[\s\S]*?```"""), " ")
                // Remove header indicators (# Title)
                .replace(Regex("""^#{1,6}\s+""", RegexOption.MULTILINE), "")
                // Remove bold and italics (***text***, **text**, *text*, ___text___, __text__, _text_)
                .replace(Regex("""[*_]{1,3}(.*?)[*_]{1,3}"""), "$1")
                // Remove inline code (`code`)
                .replace(Regex("""`+([^`]+)`+"""), "$1")
                // Remove blockquotes (> text)
                .replace(Regex("""^>\s+""", RegexOption.MULTILINE), "")
                // Remove unordered list markers (- item, * item, + item)
                .replace(Regex("""^\s*[-*+]\s+""", RegexOption.MULTILINE), "")
                // Remove ordered list numbers (1. item)
                .replace(Regex("""^\s*\d+\.\s+""", RegexOption.MULTILINE), "")
                // Remove markdown links [Label](url) -> Label
                .replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
                // Normalize consecutive newlines and extra spaces
                .replace(Regex("""\s+"""), " ")
                .trim()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeechAction: (() -> Unit)? = null

    private var currentUtteranceId: String? = null
    private var activeStartCallback: (() -> Unit)? = null
    private var activeDoneCallback: (() -> Unit)? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing TextToSpeech: ${e.message}", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            setupProgressListener()
            // Execute any speak call queued before initialization completed
            pendingSpeechAction?.invoke()
            pendingSpeechAction = null
        } else {
            Log.e(TAG, "Failed to initialize TextToSpeech engine. Status: $status")
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == currentUtteranceId) {
                    mainHandler.post {
                        activeStartCallback?.invoke()
                    }
                }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == currentUtteranceId) {
                    mainHandler.post {
                        activeDoneCallback?.invoke()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == currentUtteranceId) {
                    mainHandler.post {
                        activeDoneCallback?.invoke()
                    }
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "TTS utterance error: code $errorCode for utteranceId $utteranceId")
                if (utteranceId == currentUtteranceId) {
                    mainHandler.post {
                        activeDoneCallback?.invoke()
                    }
                }
            }
        })
    }

    /**
     * Speaks the provided [text] aloud.
     *
     * @param text The text to synthesize (markdown is automatically stripped).
     * @param language "en" for English or "bn" for Bangla.
     * @param speechRate Speech rate multiplier (clamped between 0.75f and 1.5f).
     * @param onStarted Callback fired when speech synthesis actually begins playing.
     * @param onDone Callback fired when speech synthesis completes or is stopped.
     */
    fun speak(
        text: String,
        language: String = "en",
        speechRate: Float = 1.0f,
        onStarted: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (!isInitialized || tts == null) {
            pendingSpeechAction = {
                speak(text, language, speechRate, onStarted, onDone)
            }
            return
        }

        val cleanedText = stripMarkdown(text)
        if (cleanedText.isBlank()) {
            onDone?.invoke()
            return
        }

        // Configure speech rate
        val clampedRate = speechRate.coerceIn(0.75f, 1.5f)
        tts?.setSpeechRate(clampedRate)

        // Configure language with Bangla fallback detection
        val banglaRequested = language.equals("bn", ignoreCase = true)
        val banglaAvailable = if (banglaRequested) applyBanglaLanguage() else false

        if (banglaRequested && !banglaAvailable) {
            // Fallback to English and notify UI to offer TTS Settings Intent
            tts?.language = Locale.US
            onBanglaMissing?.invoke()

            val fallbackExplanation = "Bangla voice data is missing. Please install the Bangla voice in Text to Speech settings. " + cleanedText
            speakInternal(fallbackExplanation, onStarted, onDone)
            return
        }

        if (!banglaRequested) {
            tts?.language = Locale.US
        }

        speakInternal(cleanedText, onStarted, onDone)
    }

    private fun applyBanglaLanguage(): Boolean {
        val ttsEngine = tts ?: return false

        // Check Locale("bn", "BD") then Locale("bn", "IN") then Locale("bn")
        val candidateLocales = listOf(
            Locale("bn", "BD"),
            Locale("bn", "IN"),
            Locale("bn")
        )

        for (loc in candidateLocales) {
            try {
                val result = ttsEngine.isLanguageAvailable(loc)
                if (result >= TextToSpeech.LANG_AVAILABLE) {
                    ttsEngine.language = loc
                    return true
                }
            } catch (_: Exception) {}
        }

        return false
    }

    private fun speakInternal(
        text: String,
        onStarted: (() -> Unit)?,
        onDone: (() -> Unit)?
    ) {
        val utteranceId = UUID.randomUUID().toString()
        currentUtteranceId = utteranceId
        activeStartCallback = onStarted
        activeDoneCallback = onDone

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    /**
     * Stops current speech synthesis immediately and interrupts any pending utterances.
     */
    fun stop() {
        try {
            tts?.stop()
            currentUtteranceId = null
            activeStartCallback = null
            activeDoneCallback = null
            pendingSpeechAction = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS: ${e.message}")
        }
    }

    /**
     * Checks if the TTS engine is currently playing speech.
     */
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    /**
     * Releases TTS resources. Should be called when the hosting component/ViewModel is cleared.
     */
    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TTS: ${e.message}")
        }
        tts = null
        isInitialized = false
    }
}
