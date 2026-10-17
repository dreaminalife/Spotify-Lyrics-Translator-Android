package com.dreaminalife.lyricstranslator

import android.app.Application
import android.content.Context
import android.os.Build
import com.dreaminalife.lyricstranslator.log.AppLog

class LyricsTranslatorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.crash(thread, error)
            previousHandler?.uncaughtException(thread, error)
        }
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        AppLog.i("App", "Started v$version on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        container = AppContainer(this)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as LyricsTranslatorApp).container
