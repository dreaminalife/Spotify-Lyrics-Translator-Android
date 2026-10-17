package com.dreaminalife.lyricstranslator.ui.overlay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet

/** Starts and stops the floating window; whether it runs and is locked isn't saved, so both reset with the app. */
class FloatingLyricsController(private val context: Context) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Locked: touches pass through the window to the app underneath. */
    val locked = MutableStateFlow(false)

    // Counted, not a flag: going back from a second copy of the screen starts the first copy before the second stops.
    private val startedActivities = MutableStateFlow(0)

    /** This app is on screen, showing its own lyrics, so the window hides. */
    val appVisible: Flow<Boolean> = startedActivities.map { it > 0 }.distinctUntilChanged()

    fun onActivityStarted() {
        val count = startedActivities.updateAndGet { it + 1 }
        AppLog.d(TAG, "App on screen ($count started)")
    }

    fun onActivityStopped() {
        val count = startedActivities.updateAndGet { (it - 1).coerceAtLeast(0) }
        AppLog.d(TAG, "App left the screen ($count started)")
    }

    /** Call only while the app or its Quick Settings tile is in use: Android doesn't let it start from the background. */
    fun start() {
        ContextCompat.startForegroundService(context, Intent(context, FloatingLyricsService::class.java))
    }

    fun stop() {
        context.stopService(Intent(context, FloatingLyricsService::class.java))
    }

    internal fun onStarted() {
        _running.value = true
    }

    internal fun onStopped() {
        _running.value = false
        locked.value = false
    }
}

private const val TAG = "FloatingLyrics"
