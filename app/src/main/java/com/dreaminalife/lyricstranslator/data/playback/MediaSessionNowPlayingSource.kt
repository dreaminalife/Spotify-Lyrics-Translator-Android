package com.dreaminalife.lyricstranslator.data.playback

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dreaminalife.lyricstranslator.domain.NowPlayingSource
import com.dreaminalife.lyricstranslator.domain.PlaybackController
import com.dreaminalife.lyricstranslator.domain.model.NowPlaying
import com.dreaminalife.lyricstranslator.domain.model.PlaybackSnapshot
import com.dreaminalife.lyricstranslator.domain.model.Track
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Follows the active media sessions (needs notification access). Prefers a session that is playing,
 * then Spotify. Pause, seek and track changes arrive as session callbacks, so nothing is polled.
 */
class MediaSessionNowPlayingSource(private val context: Context) : NowPlayingSource, PlaybackController {
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, MediaNotificationListener::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _artwork = MutableStateFlow<Bitmap?>(null)

    /** The current track's cover as the player sends it, or null when it sends none or nothing is playing. */
    val artwork: StateFlow<Bitmap?> = _artwork.asStateFlow()
    private var artworkTrackKey: String? = null

    private var controllers: List<MediaController> = emptyList()
    private var current: MediaController? = null
    private var started = false

    // Only used to avoid logging the same thing on every callback.
    private var lastLoggedTrack: String? = null
    private var lastLoggedState: String? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        setControllers(sessions.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = refreshSessions()
    }

    override fun start() {
        if (started) return
        if (!NotificationAccess.isGranted(context)) {
            AppLog.w(TAG, "Not starting: notification access is not granted")
            return
        }
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, mainHandler)
            started = true
            AppLog.i(TAG, "Started listening to media sessions")
            refreshSessions()
        } catch (e: SecurityException) {
            AppLog.e(TAG, "Couldn't listen to media sessions (notification access?)", e)
        }
    }

    override fun stop() {
        if (!started) return
        sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        setControllers(emptyList())
        started = false
        AppLog.i(TAG, "Stopped listening to media sessions")
    }

    override fun seekTo(positionMs: Long) = control("seekTo($positionMs)") { it.transportControls.seekTo(positionMs) }

    override fun playPause() = control("playPause") { controller ->
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    override fun next() = control("next") { it.transportControls.skipToNext() }

    override fun previous() = control("previous") { it.transportControls.skipToPrevious() }

    private fun control(action: String, block: (MediaController) -> Unit) {
        val controller = current
        if (controller == null) {
            AppLog.w(TAG, "$action ignored: no active session")
            return
        }
        AppLog.d(TAG, "$action -> ${controller.packageName}")
        block(controller)
    }

    private fun refreshSessions() {
        try {
            setControllers(sessionManager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            AppLog.e(TAG, "Notification access revoked", e)
            setControllers(emptyList())
        }
    }

    private fun setControllers(newControllers: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = newControllers
        controllers.forEach { it.registerCallback(controllerCallback, mainHandler) }
        AppLog.d(TAG, "Active sessions: " + controllers.joinToString { "${it.packageName}(${stateName(it.playbackState?.state)})" }.ifEmpty { "none" })
        publish()
    }

    private fun publish() {
        val controller = controllers.sortedWith(
            compareByDescending<MediaController> { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                .thenByDescending { it.packageName == SPOTIFY_PACKAGE }
        ).firstOrNull()
        current = controller
        val nowPlaying = controller?.let(::toNowPlaying)
        logChanges(controller, nowPlaying)
        _nowPlaying.value = nowPlaying
        updateArtwork(controller, nowPlaying)
    }

    /**
     * Each read of the metadata unpacks a new bitmap, so the artwork is only read again when the track changes, or
     * while the track has none yet (players can send it after the title).
     */
    private fun updateArtwork(controller: MediaController?, nowPlaying: NowPlaying?) {
        val key = nowPlaying?.track?.key
        if (key == artworkTrackKey && (key == null || _artwork.value != null)) return
        artworkTrackKey = key
        val metadata = controller?.metadata?.takeIf { key != null }
        _artwork.value = metadata?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
    }

    private fun toNowPlaying(controller: MediaController): NowPlaying? {
        val metadata = controller.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        val spotifyUri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.takeIf { it.startsWith(SPOTIFY_TRACK_PREFIX) }
        val track = Track(
            key = spotifyUri ?: "${artist.lowercase()}|${title.lowercase()}|${durationMs / 1000}",
            title = title,
            artist = artist,
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationMs = durationMs,
            spotifyTrackId = spotifyUri?.removePrefix(SPOTIFY_TRACK_PREFIX),
        )

        val state = controller.playbackState
        val now = SystemClock.elapsedRealtime()
        val playback = PlaybackSnapshot(
            positionMs = state?.position?.coerceAtLeast(0) ?: 0,
            updatedAtElapsedMs = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: now,
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
        )
        return NowPlaying(track, playback)
    }

    private fun logChanges(controller: MediaController?, nowPlaying: NowPlaying?) {
        val metadata = controller?.metadata
        // Raw metadata as the player reports it, to debug lookups (e.g. how several artists are joined).
        val trackDescription = if (controller == null) "no session" else if (metadata == null) "${controller.packageName}: no metadata" else
            "${controller.packageName}: title='${metadata.getString(MediaMetadata.METADATA_KEY_TITLE)}'" +
                " artist='${metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)}'" +
                " albumArtist='${metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)}'" +
                " album='${metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)}'" +
                " durationMs=${metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)}" +
                " mediaId='${metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)}'" +
                " -> key='${nowPlaying?.track?.key}'"
        if (trackDescription != lastLoggedTrack) {
            lastLoggedTrack = trackDescription
            AppLog.i(TAG, "Now playing: $trackDescription")
        }

        val state = controller?.playbackState
        val stateDescription = state?.let {
            "${stateName(it.state)} position=${it.position} speed=${it.playbackSpeed} updatedAt=${it.lastPositionUpdateTime}"
        }
        if (stateDescription != null && stateDescription != lastLoggedState) {
            lastLoggedState = stateDescription
            AppLog.d(TAG, "Playback state: $stateDescription (now=${SystemClock.elapsedRealtime()})")
        }
    }

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_NONE -> "NONE"
        null -> "null"
        else -> "state$state"
    }

    private companion object {
        const val TAG = "Playback"
        const val SPOTIFY_PACKAGE = "com.spotify.music"
        const val SPOTIFY_TRACK_PREFIX = "spotify:track:"
    }
}
