package com.visionagent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

// Application-wide DataStore instance for settings preferences
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vision_agent_settings")

/**
 * Immutable snapshot of user preferences.
 *
 * NOTE ON API KEY SECURITY:
 * Storing or injecting an API key via BuildConfig or DataStore is suitable for personal prototypes,
 * hackathons, and accessibility research. For a public production release, API requests should
 * route through a secure backend proxy or Firebase AI Logic (Vertex AI in Firebase) with App Check
 * so that Google Cloud API credentials are never bundled into the client APK or exposed.
 */
data class SettingsData(
    val apiKey: String = "",
    val model: String = SettingsRepository.DEFAULT_MODEL,
    val language: String = SettingsRepository.DEFAULT_LANGUAGE,
    val speechRate: Float = SettingsRepository.DEFAULT_SPEECH_RATE,
    val showCameraPreview: Boolean = true
) {
    /**
     * Resolves the effective Gemini API key.
     * An explicitly entered key in Settings always takes precedence over BuildConfig.GEMINI_API_KEY.
     */
    fun getEffectiveApiKey(buildConfigKey: String = ""): String {
        return apiKey.trim().ifEmpty { buildConfigKey.trim() }
    }

    /**
     * Spoken/prompt name for the target language ("English" or "Bangla").
     */
    val languagePromptName: String
        get() = if (language.equals("bn", ignoreCase = true)) "Bangla" else "English"

    /**
     * BCP-47 locale tag for speech recognition and TTS ("en-US" or "bn-BD").
     */
    val localeTag: String
        get() = if (language.equals("bn", ignoreCase = true)) "bn-BD" else "en-US"
}

/**
 * Repository responsible for reading and writing user preferences using Jetpack DataStore Preferences.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-lite-latest"
        const val DEFAULT_LANGUAGE = "en"
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val MIN_SPEECH_RATE = 0.75f
        const val MAX_SPEECH_RATE = 1.5f

        val KEY_GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val KEY_GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_SPEECH_RATE = floatPreferencesKey("speech_rate")
        val KEY_SHOW_CAMERA_PREVIEW = booleanPreferencesKey("show_camera_preview")
    }

    /**
     * Hot Flow of [SettingsData] emitting changes in real-time.
     */
    val settingsFlow: Flow<SettingsData> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            SettingsData(
                apiKey = preferences[KEY_GEMINI_API_KEY] ?: "",
                model = preferences[KEY_GEMINI_MODEL] ?: DEFAULT_MODEL,
                language = preferences[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE,
                speechRate = preferences[KEY_SPEECH_RATE] ?: DEFAULT_SPEECH_RATE,
                showCameraPreview = preferences[KEY_SHOW_CAMERA_PREVIEW] ?: true
            )
        }

    /**
     * Updates the custom Gemini API key.
     */
    suspend fun updateApiKey(apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_GEMINI_API_KEY] = apiKey.trim()
        }
    }

    /**
     * Updates the target Gemini model name (e.g. "gemini-flash-lite-latest").
     */
    suspend fun updateModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_GEMINI_MODEL] = model.trim().ifEmpty { DEFAULT_MODEL }
        }
    }

    /**
     * Updates the target language ("en" or "bn").
     */
    suspend fun updateLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LANGUAGE] = if (language.equals("bn", ignoreCase = true)) "bn" else "en"
        }
    }

    /**
     * Updates the TTS speech rate clamped between 0.75f and 1.5f.
     */
    suspend fun updateSpeechRate(rate: Float) {
        val clampedRate = rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
        context.dataStore.edit { preferences ->
            preferences[KEY_SPEECH_RATE] = clampedRate
        }
    }

    /**
     * Toggles whether the sighted helper camera preview is displayed.
     */
    suspend fun updateShowPreview(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SHOW_CAMERA_PREVIEW] = show
        }
    }
}
