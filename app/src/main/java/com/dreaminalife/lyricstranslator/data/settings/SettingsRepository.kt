package com.dreaminalife.lyricstranslator.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dreaminalife.lyricstranslator.domain.model.AppSettings
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

    suspend fun setTranslatorType(type: TranslatorType) = set(TRANSLATOR, type.name)
    suspend fun setTargetLanguage(code: String) = set(TARGET_LANGUAGE, code)
    suspend fun setOpenRouterModel(model: String) = set(MODEL, model)
    suspend fun setPromptTemplate(template: String) = set(PROMPT, template)

    private suspend fun set(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
        AppLog.i("Settings", "${key.name} = ${if (key == PROMPT) "<${value.length} chars>\n$value" else value}")
    }

    private companion object {
        val TRANSLATOR = stringPreferencesKey("translator")
        val TARGET_LANGUAGE = stringPreferencesKey("target_language")
        val MODEL = stringPreferencesKey("openrouter_model")
        val PROMPT = stringPreferencesKey("prompt_template")
    }
}
