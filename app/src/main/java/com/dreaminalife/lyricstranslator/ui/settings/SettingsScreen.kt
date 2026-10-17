package com.dreaminalife.lyricstranslator.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreaminalife.lyricstranslator.appContainer
import com.dreaminalife.lyricstranslator.data.translation.PromptTemplate
import com.dreaminalife.lyricstranslator.data.translation.openrouter.ModelInfo
import com.dreaminalife.lyricstranslator.domain.model.AppSettings
import com.dreaminalife.lyricstranslator.domain.model.TARGET_LANGUAGES
import com.dreaminalife.lyricstranslator.domain.model.TranslatorType
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onSpotifyLogin: () -> Unit) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.appContainer))
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val loaded = settings ?: return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionTitle("Translator")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TranslatorType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = loaded.translatorType == type,
                        onClick = { viewModel.setTranslator(type) },
                        shape = SegmentedButtonDefaults.itemShape(index, TranslatorType.entries.size),
                    ) { Text(type.displayName) }
                }
            }

            TargetLanguagePicker(loaded.targetLanguage, viewModel::setTargetLanguage)

            if (loaded.translatorType == TranslatorType.OPENROUTER) {
                HorizontalDivider()
                SectionTitle("OpenRouter")
                ApiKeyField(viewModel)
                ModelField(loaded.openRouterModel, viewModel)
                ModelBodyField(loaded.openRouterModel, viewModel)
                PromptField(loaded.promptTemplate, viewModel)
            }

            HorizontalDivider()
            SectionTitle("Spotify lyrics")
            SpDcField(viewModel, onSpotifyLogin)

            HorizontalDivider()
            DebugLogSection()
        }
    }
}

/** Lets a tester copy the app's log (see AppLog) and paste it into a bug report. */
@Composable
private fun DebugLogSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Debug log")
        Text(
            "If something goes wrong, copy the log and paste it into your bug report. It never contains your API key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val log = withContext(Dispatchers.IO) { AppLog.readTail(MAX_COPIED_LOG_CHARS) }
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Lyrics Translator log", log))
                    Toast.makeText(context, "Copied ${log.length / 1000} KB of log", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Copy debug log") }
            TextButton(onClick = {
                AppLog.clear()
                Toast.makeText(context, "Log cleared", Toast.LENGTH_SHORT).show()
            }) { Text("Clear log") }
        }
    }
}

/** Large clipboard contents can fail to transfer; this keeps the newest part, which is what matters. */
private const val MAX_COPIED_LOG_CHARS = 200_000

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun TargetLanguagePicker(selected: String, onSelect: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = TARGET_LANGUAGES.firstOrNull { it.code == selected }?.let { "${it.name} (${it.code})" } ?: selected
    ListItem(
        headlineContent = { Text("Target language") },
        supportingContent = { Text(label) },
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
            title = { Text("Target language") },
            text = {
                LazyColumn {
                    items(TARGET_LANGUAGES) { language ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = language.code == selected) {
                                    onSelect(language.code)
                                    open = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = language.code == selected, onClick = null)
                            Text("${language.name} (${language.code})", modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ApiKeyField(viewModel: SettingsViewModel) {
    val hasKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
    // Plain remember: rememberSaveable would put the unsaved key into the saved-state bundle as plain text.
    var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("API key") },
            placeholder = { Text(if (hasKey) "Saved. Enter a new key to replace it." else "sk-or-…") },
            supportingText = { Text(if (hasKey) "A key is saved (encrypted on this device)." else "No key saved.") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    viewModel.saveApiKey(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) { Text("Save key") }
            if (hasKey) {
                TextButton(onClick = viewModel::removeApiKey) { Text("Remove key") }
            }
        }
    }
}

/** The sp_dc cookie for Spotify's own lyrics: pasted by hand, or captured by logging in (SpotifyLoginScreen). */
@Composable
private fun SpDcField(viewModel: SettingsViewModel, onSpotifyLogin: () -> Unit) {
    val hasSpDc by viewModel.hasSpDc.collectAsStateWithLifecycle()
    // Plain remember, as in ApiKeyField: the cookie must not reach the saved-state bundle.
    var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Log in to Spotify, or paste the sp_dc cookie from open.spotify.com, to use Spotify's own lyrics " +
                "for Spotify tracks. Other sources are used when this is not set or fails.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("sp_dc cookie") },
            placeholder = { Text(if (hasSpDc) "Saved. Enter a new cookie to replace it." else "AQ…") },
            supportingText = { Text(if (hasSpDc) "A cookie is saved (encrypted on this device)." else "No cookie saved.") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    viewModel.saveSpDc(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) { Text("Save cookie") }
            if (hasSpDc) {
                TextButton(onClick = viewModel::removeSpDc) { Text("Remove cookie") }
            }
        }
        OutlinedButton(onClick = onSpotifyLogin) { Text("Log in to Spotify") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelField(savedModel: String, viewModel: SettingsViewModel) {
    var draft by rememberSaveable(savedModel) { mutableStateOf(savedModel) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Model") },
            supportingText = { Text("Pick from the list, or type a model ID and save.") },
            trailingIcon = {
                IconButton(onClick = { pickerOpen = true }) {
                    Icon(Icons.Default.Search, contentDescription = "Choose a model")
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { viewModel.selectModel(draft) }, enabled = draft.isNotBlank() && draft.trim() != savedModel) {
            Text("Save model")
        }
    }
    if (pickerOpen) {
        ModelPickerSheet(
            viewModel = viewModel,
            onPick = {
                viewModel.selectModel(it)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(viewModel: SettingsViewModel, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val state by viewModel.models.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    val filtered = remember(state.models, filter) {
        state.models.filter { filter.isBlank() || it.id.contains(filter.trim(), ignoreCase = true) || it.name.contains(filter.trim(), ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Search models") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::refreshModels, enabled = !state.loading) { Text("Refresh") }
            }
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
                state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error)
                state.models.isEmpty() -> Text("No models loaded. Tap Refresh.")
            }
        }
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(filtered, key = { it.id }) { model ->
                ListItem(
                    headlineContent = { Text(model.id) },
                    supportingContent = { Text(describe(model)) },
                    modifier = Modifier.clickable { onPick(model.id) },
                )
            }
        }
    }
}

private fun describe(model: ModelInfo): String = listOfNotNull(
    model.contextLength?.let { "${it / 1000}k context" },
    if (model.promptPricePerM != null && model.completionPricePerM != null) {
        "$%.2f / $%.2f per 1M in/out".format(model.promptPricePerM, model.completionPricePerM)
    } else null,
).joinToString(" · ")

@Composable
private fun ModelBodyField(model: String, viewModel: SettingsViewModel) {
    val saved by viewModel.modelBody.collectAsStateWithLifecycle()
    val savedText = saved ?: return
    var draft by rememberSaveable(model, savedText) { mutableStateOf(savedText) }
    var error by remember(model, savedText) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                error = null
            },
            label = { Text("Request JSON for $model") },
            supportingText = {
                Text(error ?: "Merged into the request, e.g. temperature, max_tokens, reasoning, provider.")
            },
            isError = error != null,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace),
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { error = viewModel.saveModelBody(draft) }, enabled = draft != savedText) { Text("Save JSON") }
            TextButton(onClick = viewModel::resetModelBody) { Text("Reset to default") }
        }
    }
}

@Composable
private fun PromptField(savedPrompt: String, viewModel: SettingsViewModel) {
    var draft by rememberSaveable(savedPrompt) { mutableStateOf(savedPrompt) }
    val missingLyrics = !PromptTemplate.hasLyricsPlaceholder(draft)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Prompt") },
            supportingText = {
                Text(
                    if (missingLyrics) "Add {lyrics} where the lyrics should go."
                    else "{target_language} and {lyrics} are filled in for each song."
                )
            },
            isError = missingLyrics,
            minLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.savePrompt(draft) }, enabled = draft != savedPrompt && !missingLyrics) {
                Text("Save prompt")
            }
            TextButton(
                onClick = viewModel::resetPrompt,
                enabled = savedPrompt != AppSettings.DEFAULT_PROMPT_TEMPLATE,
            ) { Text("Reset to default") }
        }
    }
}
