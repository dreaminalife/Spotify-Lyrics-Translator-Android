package com.dreaminalife.lyricstranslator.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dreaminalife.lyricstranslator.domain.model.AppSettings
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.LyricsSourcePreference
import com.dreaminalife.lyricstranslator.domain.model.TranslatorType
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val dataStore = context.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            translatorType = prefs[TRANSLATOR]
                ?.let { name -> TranslatorType.entries.firstOrNull { it.name == name } }
                ?: defaults.translatorType,
            targetLanguage = prefs[TARGET_LANGUAGE] ?: defaults.targetLanguage,
            openRouterModel = prefs[MODEL] ?: defaults.openRouterModel,
            promptTemplate = prefs[PROMPT] ?: defaults.promptTemplate,
        )
    }.distinctUntilChanged()

    /** Kept apart from [settings] so changing the order doesn't restart translations. */
    val lyricsSources: Flow<LyricsSourcePreference> = dataStore.data.map { prefs ->
        LyricsSourcePreference(
            order = prefs.sourceOrder(),
            pinned = prefs[PINNED_SOURCE]?.let(::lyricsSource),
        )
    }.distinctUntilChanged()

    /** Moves [source] by [offset] places in the Settings order, and clears the source picked on the lyrics screen. */
    suspend fun moveSource(source: LyricsSource, offset: Int, defaultOrder: List<LyricsSource>) {
        dataStore.edit { prefs ->
            // Read and written in one transaction, so quick taps don't overwrite each other.
            val order = LyricsSourcePreference(prefs.sourceOrder()).resolve(defaultOrder).toMutableList()
            val from = order.indexOf(source)
            val to = (from + offset).coerceIn(order.indices)
            order.add(to, order.removeAt(from))
            if (order == defaultOrder) prefs.remove(SOURCE_ORDER) else prefs[SOURCE_ORDER] = order.joinToString(",") { it.name }
            prefs.remove(PINNED_SOURCE)
            AppLog.i("Settings", "Lyrics source order = ${order.joinToString { it.displayName }}")
        }
    }

    /** Tries [source] first for every song; null goes back to the Settings order. */
    suspend fun setPinnedSource(source: LyricsSource?) {
        dataStore.edit { if (source == null) it.remove(PINNED_SOURCE) else it[PINNED_SOURCE] = source.name }
        AppLog.i("Settings", "Pinned lyrics source = ${source?.displayName}")
    }

    suspend fun resetSourceOrder() {
        dataStore.edit {
            it.remove(SOURCE_ORDER)
            it.remove(PINNED_SOURCE)
        }
        AppLog.i("Settings", "Lyrics source order reset to default")
    }

    suspend fun setTranslatorType(type: TranslatorType) = set(TRANSLATOR, type.name)
    suspend fun setTargetLanguage(code: String) = set(TARGET_LANGUAGE, code)
    suspend fun setOpenRouterModel(model: String) = set(MODEL, model)
    suspend fun setPromptTemplate(template: String) = set(PROMPT, template)

    private suspend fun set(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
        AppLog.i("Settings", "${key.name} = ${if (key == PROMPT) "<${value.length} chars>\n$value" else value}")
    }

    private fun Preferences.sourceOrder() = this[SOURCE_ORDER].orEmpty().split(',').mapNotNull(::lyricsSource)

    private fun lyricsSource(name: String) = LyricsSource.entries.firstOrNull { it.name == name }

    private companion object {
        val TRANSLATOR = stringPreferencesKey("translator")
        val TARGET_LANGUAGE = stringPreferencesKey("target_language")
        val MODEL = stringPreferencesKey("openrouter_model")
        val PROMPT = stringPreferencesKey("prompt_template")
        val SOURCE_ORDER = stringPreferencesKey("lyrics_source_order")
        val PINNED_SOURCE = stringPreferencesKey("pinned_lyrics_source")
    }
}
