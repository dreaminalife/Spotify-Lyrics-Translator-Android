package com.dreaminalife.lyricstranslator.ui.permission

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dreaminalife.lyricstranslator.data.playback.NotificationAccess
import com.dreaminalife.lyricstranslator.log.AppLog

/** Asks for notification access, which Android requires before an app can see other apps' media sessions. */
@Composable
fun PermissionScreen(onGranted: () -> Unit) {
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        val granted = NotificationAccess.isGranted(context)
        AppLog.i("Permission", "Notification access granted: $granted")
        if (granted) onGranted()
        onPauseOrDispose { }
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Allow notification access", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Lyrics Translator needs notification access to see what Spotify is playing and where it is in the song. " +
                    "It doesn't read your notifications.",
                textAlign = TextAlign.Center,
            )
            Button(onClick = {
                try {
                    context.startActivity(NotificationAccess.settingsIntent(context))
                } catch (e: ActivityNotFoundException) {
                    AppLog.w("Permission", "Per-app access screen missing; opening the full list", e)
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }) {
                Text("Open settings")
            }
            Text(
                "If the switch is greyed out, open App info for Lyrics Translator, tap ⋮ → Allow restricted settings, then try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
