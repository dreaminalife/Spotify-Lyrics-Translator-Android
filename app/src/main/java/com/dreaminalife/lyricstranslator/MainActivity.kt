package com.dreaminalife.lyricstranslator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dreaminalife.lyricstranslator.ui.AppNavHost
import com.dreaminalife.lyricstranslator.ui.theme.LyricsTranslatorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LyricsTranslatorTheme {
                AppNavHost()
            }
        }
    }

    // The floating window hides while the app is on screen.
    override fun onStart() {
        super.onStart()
        appContainer.floatingLyrics.onActivityStarted()
    }

    override fun onStop() {
        appContainer.floatingLyrics.onActivityStopped()
        super.onStop()
    }

    companion object {
        /** Opens the app the way its launcher icon does: back to the screen it was on, not a second copy of it. */
        fun launchIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
