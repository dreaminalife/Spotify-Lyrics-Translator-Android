package com.dreaminalife.lyricstranslator.ui.settings

import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dreaminalife.lyricstranslator.AppContainer
import com.dreaminalife.lyricstranslator.data.net.AppJson
import com.dreaminalife.lyricstranslator.data.settings.SecretStore
import com.dreaminalife.lyricstranslator.data.settings.SettingsRepository
import com.dreaminalife.lyricstranslator.data.translation.openrouter.ModelBodyStore
import com.dreaminalife.lyricstranslator.data.translation.openrouter.ModelInfo
import com.dreaminalife.lyricstranslator.data.translation.openrouter.OpenRouterModelCatalog
import com.dreaminalife.lyricstranslator.domain.model.AppSettings
import com.dreaminalife.lyricstranslator.domain.model.TranslatorType
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

data class ModelListState(
    val loading: Boolean = false,
    val models: List<ModelInfo> = emptyList(),
    val error: String? = null,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
    private val modelBodyStore: ModelBodyStore,
    private val modelCatalog: OpenRouterModelCatalog,
) : ViewModel() {

    /** Null until loaded. */
    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val hasApiKey: StateFlow<Boolean> = secretStore.openRouterKey
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val hasSpDc: StateFlow<Boolean> = secretStore.spDc
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _models = MutableStateFlow(ModelListState())
    val models: StateFlow<ModelListState> = _models.asStateFlow()

    /** The saved JSON body of the selected model, pretty-printed; null until loaded. */
    private val _modelBody = MutableStateFlow<String?>(null)
    val modelBody: StateFlow<String?> = _modelBody.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.map { it.openRouterModel }.distinctUntilChanged().collect { model ->
                _modelBody.value = PrettyJson.encodeToString(JsonObject.serializer(), modelBodyStore.get(model))
            }
        }
        // Load the model list as soon as OpenRouter is usable.
        viewModelScope.launch {
            combine(settingsRepository.settings, hasApiKey) { settings, hasKey ->
                settings.translatorType == TranslatorType.OPENROUTER && hasKey
            }.distinctUntilChanged().collect { usable ->
                if (usable && _models.value.models.isEmpty() && !_models.value.loading) refreshModels()
            }
        }
    }

    fun setTranslator(type: TranslatorType) = launch { settingsRepository.setTranslatorType(type) }

    fun setTargetLanguage(code: String) = launch { settingsRepository.setTargetLanguage(code) }

    fun saveApiKey(key: String) = launch { secretStore.setOpenRouterKey(key) }

    fun removeApiKey() = launch { secretStore.setOpenRouterKey(null) }

    fun saveSpDc(value: String) = launch { secretStore.setSpDc(value) }

    fun removeSpDc() = launch {
        secretStore.setSpDc(null)
        // Also forget any Spotify session left in the login WebView.
        CookieManager.getInstance().removeAllCookies(null)
    }

    fun selectModel(model: String) {
        val trimmed = model.trim()
        if (trimmed.isNotEmpty()) launch { settingsRepository.setOpenRouterModel(trimmed) }
    }

    fun refreshModels() = launch {
        _models.value = _models.value.copy(loading = true, error = null)
        _models.value = try {
            ModelListState(models = modelCatalog.fetch(secretStore.openRouterKey.first()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("Settings", "Model list failed", e)
            _models.value.copy(loading = false, error = "Couldn't load models: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Validates and saves the selected model's JSON body. Returns an error message, or null when saved. */
    fun saveModelBody(text: String): String? {
        val body = try {
            AppJson.parseToJsonElement(text) as? JsonObject ?: return "Must be a JSON object, e.g. {\"temperature\": 0.2}"
        } catch (e: Exception) {
            return "Invalid JSON: ${e.message}"
        }
        launch { storeModelBody(body) }
        return null
    }

    fun resetModelBody() = launch { storeModelBody(ModelBodyStore.DEFAULT_BODY) }

    fun savePrompt(template: String) = launch { settingsRepository.setPromptTemplate(template) }

    fun resetPrompt() = launch { settingsRepository.setPromptTemplate(AppSettings.DEFAULT_PROMPT_TEMPLATE) }

    private suspend fun storeModelBody(body: JsonObject) {
        val model = settingsRepository.settings.first().openRouterModel
        modelBodyStore.set(model, body)
        _modelBody.value = PrettyJson.encodeToString(JsonObject.serializer(), body)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        private val PrettyJson = Json { prettyPrint = true }

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    secretStore = container.secretStore,
                    modelBodyStore = container.modelBodyStore,
                    modelCatalog = container.modelCatalog,
                )
            }
        }
    }
}
