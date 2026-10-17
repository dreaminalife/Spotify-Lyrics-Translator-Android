package com.dreaminalife.lyricstranslator.ui.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dreaminalife.lyricstranslator.AppContainer
import com.dreaminalife.lyricstranslator.domain.PlaybackController
import com.dreaminalife.lyricstranslator.domain.model.LyricsSource
import com.dreaminalife.lyricstranslator.domain.model.NowPlaying
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.flow.StateFlow

/** The lyrics screen's view of the [LyricsSession], which it keeps listening to the player while it exists. */
class LyricsViewModel(
    private val session: LyricsSession,
    playbackController: PlaybackController,
) : ViewModel(), PlaybackController by playbackController {

    val nowPlaying: StateFlow<NowPlaying?> = session.nowPlaying

    val uiState: StateFlow<LyricsUiState> = session.uiState

    init {
        AppLog.d(TAG, "Created")
        session.acquire()
    }

    /** Starts listening if notification access was granted after this ViewModel was created. */
    fun onResume() = session.retry()

    fun refresh() = session.refresh()

    fun selectSource(source: LyricsSource) = session.selectSource(source)

    override fun onCleared() {
        AppLog.d(TAG, "Cleared")
        session.release()
    }

    companion object {
        private const val TAG = "LyricsScreen"

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                LyricsViewModel(
                    session = container.lyricsSession,
                    playbackController = container.playbackController,
                )
            }
        }
    }
}
