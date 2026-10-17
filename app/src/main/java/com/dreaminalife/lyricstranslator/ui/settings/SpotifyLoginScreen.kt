package com.dreaminalife.lyricstranslator.ui.settings

import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.dreaminalife.lyricstranslator.appContainer
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.launch

private const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

/**
 * Spotify's web login in a WebView. Once the sp_dc cookie appears it is saved to the SecretStore and the WebView's
 * cookies are cleared, so the cookie is kept only in encrypted form. Google sign-in refuses to run in WebViews;
 * those users paste the cookie in Settings instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyLoginScreen(onDone: () -> Unit) {
    val secretStore = LocalContext.current.appContainer.secretStore
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log in to Spotify") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    // The default WRAP_CONTENT makes CSS vh units 0, which collapses Spotify's page to nothing.
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (saving) return
                            val spDc = readSpDc() ?: return
                            saving = true
                            AppLog.i("Settings", "Captured sp_dc from Spotify login")
                            scope.launch {
                                secretStore.setSpDc(spDc)
                                CookieManager.getInstance().removeAllCookies(null)
                                onDone()
                            }
                        }
                    }
                    loadUrl(LOGIN_URL)
                }
            },
            onRelease = WebView::destroy,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

private fun readSpDc(): String? = CookieManager.getInstance().getCookie("https://open.spotify.com")
    ?.split(';')
    ?.map { it.trim() }
    ?.firstOrNull { it.startsWith("sp_dc=") }
    ?.removePrefix("sp_dc=")
    ?.takeIf { it.isNotEmpty() }
