package com.dreaminalife.lyricstranslator.ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dreaminalife.lyricstranslator.MainActivity
import com.dreaminalife.lyricstranslator.R
import com.dreaminalife.lyricstranslator.appContainer
import com.dreaminalife.lyricstranslator.domain.model.FloatingWindowPlacement
import com.dreaminalife.lyricstranslator.log.AppLog
import com.dreaminalife.lyricstranslator.ui.theme.LyricsTranslatorTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Shows [FloatingLyrics] over other apps, where it was last left. Drag it to move it, or its corner to resize it;
 * its settings button opens [FloatingSettingsPanel] under it. Locked, touches pass through it.
 *
 * It shows only while a player has a track (playing or paused), the screen is on and this app isn't open, so nothing
 * is looked up or translated from a pocket. Unlocked, a double tap on the lyrics opens this app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FloatingLyricsService : LifecycleService(), SavedStateRegistryOwner {
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val container by lazy { appContainer }
    private lateinit var windowManager: WindowManager

    /** Null until the window is added; everything onDestroy undoes is set up after that. */
    private var view: View? = null
    private val params = WindowManager.LayoutParams(
        0,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Told about touches outside it, which close the settings panel.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** The width it was resized to; null for the default. */
    private var resizedWidth: Int? = null

    /** Where the window's top was put; it sits higher only while the settings panel needs the room below. */
    private var placedY = 0

    /** Where the lyrics are drawn in the window; touches outside them (on the settings panel) never move it. */
    private var lyricsBounds = Rect.Zero

    private val panelOpen = MutableStateFlow(false)

    private val interactive = MutableStateFlow(true)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            interactive.value = intent.action == Intent.ACTION_SCREEN_ON
        }
    }

    private val shown: Flow<Boolean> by lazy {
        val hasTrack = container.lyricsSession.nowPlaying
            .map { it != null }
            .distinctUntilChanged()
            // Not hidden at once, so a player briefly dropping its track between songs doesn't make it blink.
            .transformLatest { hasTrack ->
                if (!hasTrack) delay(HIDE_DELAY_MS)
                emit(hasTrack)
            }
        combine(interactive, container.floatingLyrics.appVisible, hasTrack) { screenOn, appOpen, hasTrack ->
            screenOn && !appOpen && hasTrack
        }.distinctUntilChanged()
    }

    override fun onCreate() {
        savedStateController.performRestore(null)
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        // First, so the promise made by startForegroundService is kept even if the window can't be shown.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Floating lyrics", NotificationManager.IMPORTANCE_LOW),
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(locked = false),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (!Settings.canDrawOverlays(this)) {
            AppLog.w(TAG, "Not shown: no permission to draw over other apps")
            stopSelf()
            return
        }
        // Read before adding the window, so it opens where it was left instead of jumping there.
        lifecycleScope.launch { show(container.settingsRepository.floatingPlacement.first()) }
    }

    private fun show(placement: FloatingWindowPlacement?) {
        val root = TouchFrame().apply {
            // Compose looks these up when the view is attached, so they must be set before addView.
            setViewTreeLifecycleOwner(this@FloatingLyricsService)
            setViewTreeSavedStateRegistryOwner(this@FloatingLyricsService)
            addView(ComposeView(this@FloatingLyricsService).apply { setContent { WindowContent() } })
        }
        val metrics = resources.displayMetrics
        resizedWidth = placement?.width
        params.width = stripWidth()
        params.x = placement?.x ?: ((metrics.widthPixels - params.width) / 2)
        params.y = placement?.y ?: (metrics.heightPixels / 10)
        clampPosition()
        placedY = params.y
        try {
            windowManager.addView(root, params)
        } catch (e: Exception) {
            AppLog.e(TAG, "Couldn't show the floating window", e)
            stopSelf()
            return
        }
        view = root
        AppLog.i(TAG, "Shown")

        interactive.value = getSystemService(PowerManager::class.java).isInteractive
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        container.lyricsSession.acquire()
        container.floatingLyrics.onStarted()
        lifecycleScope.launch {
            container.floatingLyrics.locked.collect { locked ->
                if (locked) panelOpen.value = false
                applyLock(locked)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(locked))
            }
        }
        // Closed while hidden, so it isn't still open when the window comes back.
        lifecycleScope.launch {
            shown.collect {
                AppLog.d(TAG, if (it) "Lyrics shown" else "Lyrics hidden")
                if (!it) panelOpen.value = false
                // The window itself is hidden: one whose content shrinks to nothing can keep showing its last frame.
                view?.visibility = if (it) View.VISIBLE else View.GONE
            }
        }
    }

    @Composable
    private fun WindowContent() {
        val isShown by shown.collectAsStateWithLifecycle(false)
        if (!isShown) return
        val settings by container.settingsRepository.floatingLyrics.collectAsStateWithLifecycle(null)
        val locked by container.floatingLyrics.locked.collectAsStateWithLifecycle()
        val open by panelOpen.collectAsStateWithLifecycle()
        val loaded = settings ?: return
        val onChange: FloatingSettingsChange = { transform ->
            lifecycleScope.launch { container.settingsRepository.updateFloatingLyrics(transform) }
        }
        LyricsTranslatorTheme(darkTheme = true) {
            Column {
                FloatingLyrics(
                    container.lyricsSession,
                    loaded,
                    showControls = !locked,
                    onOpenSettings = { panelOpen.update { !it } },
                    onTapLyrics = if (open) ({ panelOpen.value = false }) else null,
                    // Allowed from the background: this app may draw over others and its window is showing.
                    onDoubleTapLyrics = { startActivity(MainActivity.launchIntent(this@FloatingLyricsService)) },
                    onBounds = {
                        lyricsBounds = it
                        excludeHandleFromGestures()
                    },
                )
                if (open) {
                    FloatingSettingsPanel(
                        loaded,
                        onChange,
                        onLock = { container.floatingLyrics.locked.value = true },
                        onTurnOff = { stopSelf() },
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_TOGGLE_LOCK -> container.floatingLyrics.locked.update { !it }
            ACTION_CLOSE -> stopSelf()
        }
        // Not restarted after the process is killed: a foreground service can't start from the background.
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = view ?: return
        params.width = stripWidth()
        clampPosition()
        windowManager.updateViewLayout(view, params)
    }

    override fun onDestroy() {
        view?.let {
            unregisterReceiver(screenReceiver)
            // Already gone if the permission to draw over other apps was revoked.
            runCatching { windowManager.removeView(it) }
            container.lyricsSession.release()
            AppLog.i(TAG, "Closed")
        }
        view = null
        container.floatingLyrics.onStopped()
        super.onDestroy()
    }

    private fun applyLock(locked: Boolean) {
        val view = view ?: return
        if (locked) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            // Android 12+ drops touches that pass through another app's window more opaque than this.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.alpha = getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
            }
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            params.alpha = 1f
        }
        windowManager.updateViewLayout(view, params)
    }

    /**
     * The width it was resized to, else 90% of the screen width, at most 560dp; fixed, so the window doesn't resize
     * for every line.
     */
    private fun stripWidth(): Int {
        val metrics = resources.displayMetrics
        return resizedWidth?.coerceAtMost(metrics.widthPixels)
            ?: minOf((metrics.widthPixels * 0.9f).toInt(), (560 * metrics.density).toInt())
    }

    private fun clampPosition() {
        val metrics = resources.displayMetrics
        params.x = params.x.coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (metrics.heightPixels - (view?.height ?: 0)).coerceAtLeast(0))
    }

    /** Near the screen's edge, a drag from the resize grip would otherwise be taken as the system's back gesture. */
    private fun excludeHandleFromGestures() {
        val view = view ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val handle = RESIZE_HANDLE_DP * resources.displayMetrics.density
        val bounds = lyricsBounds
        view.systemGestureExclusionRects = listOf(
            android.graphics.Rect(
                (bounds.right - handle).toInt(),
                (bounds.bottom - handle).toInt(),
                bounds.right.toInt(),
                bounds.bottom.toInt(),
            ),
        )
    }

    private fun savePlacement() {
        val placement = FloatingWindowPlacement(params.x, placedY, params.width)
        lifecycleScope.launch { container.settingsRepository.setFloatingPlacement(placement) }
    }

    private fun notification(locked: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_floating_lyrics)
            .setContentTitle("Floating lyrics")
            .setContentText(if (locked) "Locked: touches go to the app underneath" else "Drag the lyrics to move them")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, MainActivity.launchIntent(this), PendingIntent.FLAG_IMMUTABLE),
            )
            .addAction(0, if (locked) "Unlock" else "Lock", serviceIntent(ACTION_TOGGLE_LOCK))
            .addAction(0, "Close", serviceIntent(ACTION_CLOSE))
            .build()

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, FloatingLyricsService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Holds the window's content. Dragging the lyrics' bottom-end corner resizes the window's width; dragging anywhere
     * else on the lyrics moves it. Taps, and touches on the settings panel, go to Compose as usual.
     * Uses screen coordinates: view coordinates shift as the window moves under the finger, which makes it jitter.
     */
    private inner class TouchFrame : FrameLayout(this@FloatingLyricsService) {
        private val density = resources.displayMetrics.density
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var gesture = Gesture.NONE
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var startWidth = 0

        override fun onInterceptTouchEvent(event: MotionEvent) = track(event)

        override fun onTouchEvent(event: MotionEvent): Boolean {
            track(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }

        override fun performClick() = super.performClick()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Near the bottom of the screen, moves up while the settings panel is open, so it isn't cut off, and back
            // down once it closes.
            post {
                if (view == null) return@post
                params.y = placedY
                clampPosition()
                windowManager.updateViewLayout(this, params)
            }
        }

        /** Follows the gesture; true once it moves or resizes the window, which then takes the touches from Compose. */
        private fun track(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    startWidth = params.width
                    val handle = RESIZE_HANDLE_DP * density
                    val bounds = lyricsBounds
                    gesture = when {
                        !bounds.contains(Offset(event.x, event.y)) -> Gesture.NONE
                        event.x > bounds.right - handle && event.y > bounds.bottom - handle -> Gesture.RESIZE
                        else -> Gesture.PENDING
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (gesture == Gesture.PENDING && hypot(dx, dy) > touchSlop) gesture = Gesture.MOVE
                    when (gesture) {
                        Gesture.MOVE -> {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                        }
                        Gesture.RESIZE -> {
                            // The left edge stays where it is.
                            val maxWidth = resources.displayMetrics.widthPixels - params.x
                            val minWidth = (MIN_WIDTH_DP * density).toInt().coerceAtMost(maxWidth)
                            params.width = (startWidth + dx.toInt()).coerceIn(minWidth, maxWidth)
                            resizedWidth = params.width
                        }
                        else -> return false
                    }
                    clampPosition()
                    placedY = params.y
                    windowManager.updateViewLayout(this, params)
                }
                MotionEvent.ACTION_OUTSIDE -> panelOpen.value = false
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gesture == Gesture.MOVE || gesture == Gesture.RESIZE) savePlacement()
                    gesture = Gesture.NONE
                }
            }
            return gesture == Gesture.MOVE || gesture == Gesture.RESIZE
        }
    }

    private enum class Gesture { NONE, PENDING, MOVE, RESIZE }

    private companion object {
        const val TAG = "FloatingLyrics"
        const val CHANNEL_ID = "floating_lyrics"
        const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE_LOCK = "com.dreaminalife.lyricstranslator.TOGGLE_LOCK"
        const val ACTION_CLOSE = "com.dreaminalife.lyricstranslator.CLOSE_FLOATING_LYRICS"
        const val HIDE_DELAY_MS = 2_000L
        /** Small enough to stay clear of the settings button above it on a one-line window. */
        const val RESIZE_HANDLE_DP = 32
        /** Wide enough for the settings panel. */
        const val MIN_WIDTH_DP = 280
    }
}
