package com.pdfapk.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "pdf_apk_settings")

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class RecentPdfEntry(
    val uri: String,
    val title: String,
    val timestamp: Long
)

/**
 * Persistent settings backed by DataStore.
 */
class SettingsManager(private val context: Context) {

    companion object {
        private const val MAX_RECENT_PDFS = 5
    }

    // ── Keys ────────────────────────────────────────────────────────────────

    private object Keys {
        val MODEL_DOWNLOADED = booleanPreferencesKey("model_downloaded")
        val MODEL_PATH = stringPreferencesKey("model_path")
        val DEFAULT_LENGTH = stringPreferencesKey("default_summary_length")
        val DEFAULT_TARGET_LANG = stringPreferencesKey("default_target_lang")
        val TRANSLATION_FORMALITY = stringPreferencesKey("translation_formality")
        val THEME_MODE = stringPreferencesKey("theme_mode")  // "system", "light", "dark"
        val FIRST_LAUNCH = booleanPreferencesKey("first_launch")
        val CONNECTION_MODE = stringPreferencesKey("connection_mode")  // "local" or "online"
        val GROQ_API_KEY = stringPreferencesKey("groq_api_key")
        val GROQ_MODEL = stringPreferencesKey("groq_model")
        val RECENT_PDFS = stringPreferencesKey("recent_pdfs")
    }

    // ── Flows ───────────────────────────────────────────────────────────────

    val isModelDownloaded: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.MODEL_DOWNLOADED] ?: false
    }

    val defaultSummaryLength: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_LENGTH] ?: "medium"
    }

    val defaultTargetLanguage: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_TARGET_LANG] ?: "English"
    }

    val translationFormality: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.TRANSLATION_FORMALITY] ?: "professional"
    }

    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE] ?: "system"
    }

    val isFirstLaunch: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.FIRST_LAUNCH] ?: true
    }

    val connectionMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.CONNECTION_MODE] ?: "local"
    }

    val groqApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.GROQ_API_KEY] ?: ""
    }

    val groqModel: Flow<String> = context.dataStore.data.map { prefs ->
        val saved = prefs[Keys.GROQ_MODEL] ?: "llama-3.3-70b-versatile"
        // Migrate decommissioned model names to current ones
        when (saved) {
            "llama3-70b-8192", "llama3-8b-8192", "mixtral-8x7b-32768", "gemma2-9b-it" -> "llama-3.3-70b-versatile"
            else -> saved
        }
    }

    val recentPdfs: Flow<List<RecentPdfEntry>> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.RECENT_PDFS] ?: return@map emptyList()
        try {
            json.decodeFromString<List<RecentPdfEntry>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun addRecentPdf(uri: String, title: String) {
        context.dataStore.edit { prefs ->
            val current = try {
                json.decodeFromString<List<RecentPdfEntry>>(
                    prefs[Keys.RECENT_PDFS] ?: "[]"
                )
            } catch (_: Exception) {
                emptyList()
            }
            val updated = listOf(
                RecentPdfEntry(uri, title, System.currentTimeMillis())
            ) + current.filter { it.uri != uri }.take(MAX_RECENT_PDFS - 1)
            prefs[Keys.RECENT_PDFS] = json.encodeToString(updated)
        }
    }

    // ── Suspend setters ────────────────────────────────────────────────────

    suspend fun setModelDownloaded(downloaded: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MODEL_DOWNLOADED] = downloaded
        }
    }

    suspend fun setModelPath(path: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.MODEL_PATH] = path
        }
    }

    suspend fun setDefaultSummaryLength(length: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_LENGTH] = length
        }
    }

    suspend fun setDefaultTargetLanguage(lang: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_TARGET_LANG] = lang
        }
    }

    suspend fun setTranslationFormality(formality: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TRANSLATION_FORMALITY] = formality
        }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode
        }
    }

    suspend fun setFirstLaunchComplete() {
        context.dataStore.edit { prefs ->
            prefs[Keys.FIRST_LAUNCH] = false
        }
    }

    suspend fun setConnectionMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CONNECTION_MODE] = mode
        }
    }

    suspend fun setGroqApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.GROQ_API_KEY] = key
        }
    }

    suspend fun setGroqModel(model: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.GROQ_MODEL] = model
        }
    }
}
