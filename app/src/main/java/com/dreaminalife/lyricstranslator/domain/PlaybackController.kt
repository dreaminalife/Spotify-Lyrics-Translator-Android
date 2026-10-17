package com.dreaminalife.lyricstranslator.domain

interface PlaybackController {
    fun seekTo(positionMs: Long)
    fun playPause()
    fun next()
    fun previous()
}
