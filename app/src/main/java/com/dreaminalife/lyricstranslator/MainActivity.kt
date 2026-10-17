package com.dreaminalife.lyricstranslator

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
}
