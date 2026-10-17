package com.dreaminalife.lyricstranslator.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dreaminalife.lyricstranslator.domain.model.AppSettings
import com.dreaminalife.lyricstranslator.domain.model.FloatingLyricsMode
import com.dreaminalife.lyricstranslator.domain.model.FloatingLyricsSettings
import com.dreaminalife.lyricstranslator.domain.model.FloatingWindowPlacement
import com.dreaminalife.lyricstranslator.domain.model.LongLines
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
            openRouterAutoTranslate = prefs[AUTO_TRANSLATE] ?: defaults.openRouterAutoTranslate,
        )
    }.distinctUntilChanged()

    /** Kept apart from [settings] so changing the order doesn't restart translations. */
    val lyricsSources: Flow<LyricsSourcePreference> = dataStore.data.map { prefs ->
        LyricsSourcePreference(
            order = prefs.sourceOrder(),
            pinned = prefs[PINNED_SOURCE]?.let(::lyricsSource),
        )
    }.distinctUntilChanged()

    /** Kept apart from [settings] so changing it doesn't restart translations. */
    val floatingLyrics: Flow<FloatingLyricsSettings> = dataStore.data.map { it.floatingLyrics() }.distinctUntilChanged()

    /** Applies [transform] to the floating window settings, read and written in one transaction. */
    suspend fun updateFloatingLyrics(transform: (FloatingLyricsSettings) -> FloatingLyricsSettings) {
        var updated: FloatingLyricsSettings? = null
        dataStore.edit { prefs ->
            val settings = transform(prefs.floatingLyrics())
            prefs[FLOATING_MODE] = settings.mode.name
            prefs[FLOATING_LINES] = settings.lineCount
            prefs[FLOATING_LONG_LINES] = settings.longLines.name
            prefs[FLOATING_TEXT_SCALE] = settings.textScale
            updated = settings
        }
        AppLog.i("Settings", "Floating lyrics: $updated")
    }

    /** Where the floating window was last moved or resized to; null until it has been. */
    val floatingPlacement: Flow<FloatingWindowPlacement?> = dataStore.data.map { prefs ->
        val x = prefs[FLOATING_X]
        val y = prefs[FLOATING_Y]
        val width = prefs[FLOATING_WIDTH]
        if (x != null && y != null && width != null) FloatingWindowPlacement(x, y, width) else null
    }.distinctUntilChanged()

    suspend fun setFloatingPlacement(placement: FloatingWindowPlacement) {
        dataStore.edit { prefs ->
            prefs[FLOATING_X] = placement.x
            prefs[FLOATING_Y] = placement.y
            prefs[FLOATING_WIDTH] = placement.width
        }
    }

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

    suspend fun setOpenRouterAutoTranslate(enabled: Boolean) {
        dataStore.edit { it[AUTO_TRANSLATE] = enabled }
        AppLog.i("Settings", "${AUTO_TRANSLATE.name} = $enabled")
    }

    private suspend fun set(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
        AppLog.i("Settings", "${key.name} = ${if (key == PROMPT) "<${value.length} chars>\n$value" else value}")
    }

    private fun Preferences.sourceOrder() = this[SOURCE_ORDER].orEmpty().split(',').mapNotNull(::lyricsSource)

    private fun lyricsSource(name: String) = LyricsSource.entries.firstOrNull { it.name == name }

    private fun Preferences.floatingLyrics(): FloatingLyricsSettings {
        val defaults = FloatingLyricsSettings()
        return FloatingLyricsSettings(
            mode = this[FLOATING_MODE]?.let { name -> FloatingLyricsMode.entries.firstOrNull { it.name == name } }
                ?: defaults.mode,
            lineCount = this[FLOATING_LINES]?.takeIf { it in FloatingLyricsSettings.LINE_COUNTS } ?: defaults.lineCount,
            longLines = this[FLOATING_LONG_LINES]?.let { name -> LongLines.entries.firstOrNull { it.name == name } }
                ?: defaults.longLines,
            textScale = this[FLOATING_TEXT_SCALE]
                ?.coerceIn(FloatingLyricsSettings.MIN_TEXT_SCALE, FloatingLyricsSettings.MAX_TEXT_SCALE)
                ?: defaults.textScale,
        )
    }

    private companion object {
        val TRANSLATOR = stringPreferencesKey("translator")
        val TARGET_LANGUAGE = stringPreferencesKey("target_language")
        val MODEL = stringPreferencesKey("openrouter_model")
        val PROMPT = stringPreferencesKey("prompt_template")
        val AUTO_TRANSLATE = booleanPreferencesKey("openrouter_auto_translate")
        val SOURCE_ORDER = stringPreferencesKey("lyrics_source_order")
        val PINNED_SOURCE = stringPreferencesKey("pinned_lyrics_source")
        val FLOATING_MODE = stringPreferencesKey("floating_lyrics_mode")
        val FLOATING_LINES = intPreferencesKey("floating_lyrics_lines")
        val FLOATING_LONG_LINES = stringPreferencesKey("floating_lyrics_long_lines")
        val FLOATING_TEXT_SCALE = floatPreferencesKey("floating_lyrics_text_scale")
        val FLOATING_X = intPreferencesKey("floating_lyrics_x")
        val FLOATING_Y = intPreferencesKey("floating_lyrics_y")
        val FLOATING_WIDTH = intPreferencesKey("floating_lyrics_width")
    }
}
