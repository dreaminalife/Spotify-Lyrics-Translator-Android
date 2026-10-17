package com.dreaminalife.lyricstranslator.ui.overlay

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dreaminalife.lyricstranslator.MainActivity
import com.dreaminalife.lyricstranslator.appContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The Quick Settings tile that turns the floating window on and off. */
class FloatingLyricsTileService : TileService() {
    private val scope = MainScope()
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening = scope.launch {
            appContainer.floatingLyrics.running.collect { running ->
                qsTile?.apply {
                    state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val controller = appContainer.floatingLyrics
        when {
            controller.running.value -> controller.stop()
            Settings.canDrawOverlays(this) -> controller.start()
            // The permission is asked for in the app, which also explains what it's for.
            else -> openApp()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = MainActivity.launchIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
