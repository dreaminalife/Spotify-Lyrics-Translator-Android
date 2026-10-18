package com.dreaminalife.lyricstranslator.ui.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dreaminalife.lyricstranslator.appContainer
import com.dreaminalife.lyricstranslator.domain.model.Lyrics
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.NowPlaying
import com.dreaminalife.lyricstranslator.domain.model.TARGET_LANGUAGES
import com.dreaminalife.lyricstranslator.ui.theme.AlbumTheme
import com.dreaminalife.lyricstranslator.ui.theme.NoArtColor
import kotlinx.coroutines.delay

/** After the user scrolls by hand, auto-scroll waits this long before following the song again (as on desktop). */
private const val USER_SCROLL_PAUSE_MS = 2_500L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val viewModel: LyricsViewModel = viewModel(factory = LyricsViewModel.factory(context.appContainer))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    // The theme switches to the new cover's color at once; only the background fades to it, so the fade just redraws.
    val albumColor by viewModel.albumColor.collectAsStateWithLifecycle()
    val color = albumColor ?: NoArtColor
    val background = animateColorAsState(color, tween(800), label = "albumColor")

    AlbumTheme(color) {
        Scaffold(
            modifier = Modifier.drawBehind {
                val top = background.value
                drawRect(Brush.verticalGradient(listOf(top, lerp(top, Color.Black, 0.7f))))
            },
            // Transparent shows the gradient; the content color must be set, or text without a color would be black.
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                    title = {
                        val track = state.track
                        if (track == null) {
                            Text("Lyrics Translator")
                        } else {
                            Column {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val subtitle = listOfNotNull(state.currentTranslation?.translatedTitle, track.artist)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = {
                        if (state.track != null) {
                            IconButton(onClick = viewModel::refresh) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reload lyrics and translation")
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
            bottomBar = {
                nowPlaying?.let {
                    MiniPlayerBar(
                        nowPlaying = it,
                        onPrevious = viewModel::previous,
                        onPlayPause = viewModel::playPause,
                        onNext = viewModel::next,
                    )
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                StatusRow(state, onOpenSettings, onSelectSource = viewModel::selectSource)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (val content = state.content) {
                        LyricsContent.NothingPlaying -> Message("Play a song in Spotify and its lyrics will show up here.")
                        LyricsContent.Loading -> Loading()
                        LyricsContent.NotFound -> Message("No lyrics found for this song.", onRetry = viewModel::refresh)
                        is LyricsContent.Error -> Message(content.message, onRetry = viewModel::refresh)
                        is LyricsContent.Loaded -> key(state.track?.key) {
                            LyricsList(
                                lyrics = content.lyrics,
                                translations = state.translationLines,
                                nowPlaying = nowPlaying,
                                onSeek = viewModel::seekTo,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(state: LyricsUiState, onOpenSettings: () -> Unit, onSelectSource: (LyricsSource) -> Unit) {
    if (state.content == LyricsContent.NothingPlaying) return
    val lyrics = (state.content as? LyricsContent.Loaded)?.lyrics
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Also shown while loading or without lyrics, so a source that is slow or has nothing can be skipped.
        SourcePicker(
            label = lyrics?.source?.displayName ?: "Lyrics source",
            sources = state.sourceOrder,
            first = state.pinnedSource ?: state.sourceOrder.firstOrNull(),
            onSelect = onSelectSource,
        )
        if (lyrics == null) return@FlowRow
        state.pinnedSource?.takeIf { it != lyrics.source }?.let {
            Tag("Couldn't get lyrics from ${it.displayName}", color = MaterialTheme.colorScheme.tertiaryContainer)
        }
        when (val translation = state.translation) {
            TranslationState.Translating -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Translating…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
            }
            is TranslationState.Done -> state.currentTranslation?.let { Tag(it.translatorName) }
            TranslationState.MissingKey -> Tag(
                "OpenRouter selected but API key missing. Add it in Settings.",
                color = MaterialTheme.colorScheme.errorContainer,
                onClick = onOpenSettings,
            )
            is TranslationState.Failed -> Tag(translation.message, color = MaterialTheme.colorScheme.errorContainer)
            is TranslationState.SameLanguage -> {
                val language = TARGET_LANGUAGES.find { it.code == translation.targetLanguage }
                // "Chinese", not "Chinese (Simplified)": the lyrics may be in either script.
                Tag("Already in ${language?.name?.substringBefore(" (") ?: translation.targetLanguage}")
            }
            // The source's own translation may still be on screen.
            TranslationState.NotRequested -> if (state.translationLines == null) Tag("Not translated")
            TranslationState.None -> Unit
        }
        if (!lyrics.synced) {
            Tag("Not time-synced", color = MaterialTheme.colorScheme.tertiaryContainer)
        }
    }
}

/** Shows [label] (the source of the lyrics on screen); tapping it picks the source to try first for every song. */
@Composable
private fun SourcePicker(
    label: String,
    sources: List<LyricsSource>,
    first: LyricsSource?,
    onSelect: (LyricsSource) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Tag("$label ▾", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sources.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.displayName) },
                    trailingIcon = if (source == first) ({ Icon(Icons.Default.Check, contentDescription = "Tried first") }) else null,
                    onClick = {
                        open = false
                        if (source != first) onSelect(source)
                    },
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer, onClick: (() -> Unit)? = null) {
    Surface(
        color = color,
        shape = MaterialTheme.shapes.small,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun LyricsList(
    lyrics: Lyrics,
    translations: List<String>?,
    nowPlaying: NowPlaying?,
    onSeek: (Long) -> Unit,
) {
    val position = rememberPlaybackPosition(nowPlaying)
    val currentIndex by remember(lyrics) { derivedStateOf { lyrics.indexAt(position.value) } }
    val listState = rememberLazyListState()

    // Follow the current line, except for a short while after the user scrolls by hand.
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var autoScroll by remember { mutableStateOf(true) }
    LaunchedEffect(isDragged) {
        if (isDragged) {
            autoScroll = false
        } else if (!autoScroll) {
            delay(USER_SCROLL_PAUSE_MS)
            autoScroll = true
        }
    }
    LaunchedEffect(currentIndex, autoScroll) {
        if (autoScroll && currentIndex >= 0) listState.centerOn(currentIndex)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Lyrics start at the top, so the first lines sit above the middle until scrolling catches up.
        // Synced lyrics get half a screen of bottom padding so the last lines can still reach the middle.
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 16.dp, bottom = if (lyrics.synced) maxHeight / 2 else 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lyrics.lines) { index, line ->
                LyricLine(
                    text = line.text,
                    translation = translations?.getOrNull(index),
                    highlight = if (lyrics.synced) index == currentIndex else null,
                    onClick = if (lyrics.synced) ({ onSeek(line.startTimeMs) }) else null,
                )
            }
        }
    }
}

/** Scrolls so the middle of item [index] is in the middle of the viewport. */
private suspend fun LazyListState.centerOn(index: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) animateScrollToItem(index)
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    animateScrollBy((item.offset + item.size / 2 - viewportCenter).toFloat())
}

@Composable
private fun Loading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("Finding lyrics…", modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun Message(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}
