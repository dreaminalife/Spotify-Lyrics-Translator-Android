package com.dreaminalife.lyricstranslator.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dreaminalife.lyricstranslator.data.playback.NotificationAccess
import com.dreaminalife.lyricstranslator.ui.lyrics.LyricsScreen
import com.dreaminalife.lyricstranslator.ui.permission.PermissionScreen
import com.dreaminalife.lyricstranslator.ui.settings.SettingsScreen
import com.dreaminalife.lyricstranslator.ui.settings.SpotifyLoginScreen

private object Routes {
    const val PERMISSION = "permission"
    const val LYRICS = "lyrics"
    const val SETTINGS = "settings"
    const val SPOTIFY_LOGIN = "spotify_login"
}

@Composable
fun AppNavHost() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val startDestination = remember {
        if (NotificationAccess.isGranted(context)) Routes.LYRICS else Routes.PERMISSION
    }
    // The lyrics screen is always dark, so it needs light system bar icons even in light mode. Set from the route, so
    // they change as soon as the screen does rather than after its exit animation.
    val route = navController.currentBackStackEntryAsState().value?.destination?.route ?: startDestination
    val lightBars = route != Routes.LYRICS && !isSystemInDarkTheme()
    val window = LocalActivity.current?.window
    val view = LocalView.current
    SideEffect {
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.PERMISSION) {
            PermissionScreen(onGranted = {
                navController.navigate(Routes.LYRICS) {
                    popUpTo(Routes.PERMISSION) { inclusive = true }
                }
            })
        }
        composable(Routes.LYRICS) {
            LyricsScreen(onOpenSettings = { navController.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onSpotifyLogin = { navController.navigate(Routes.SPOTIFY_LOGIN) },
            )
        }
        composable(Routes.SPOTIFY_LOGIN) {
            SpotifyLoginScreen(onDone = { navController.popBackStack() })
        }
    }
}
