package com.dreaminalife.lyricstranslator.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Logs to logcat (tag [TAG], filter with `adb logcat -s LyricsTranslator`) and to a rotating file
 * that Settings → "Copy debug log" puts on the clipboard. Never log the OpenRouter key.
 * Each message is prefixed with an area such as "Playback" or "LRCLib".
 */
object AppLog {
    const val TAG = "LyricsTranslator"
    private const val MAX_FILE_BYTES = 1_000_000L

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "AppLog").apply { isDaemon = true } }
    private val lock = Any()
    private var logFile: File? = null
    private var oldLogFile: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(dir, "app.log")
        oldLogFile = File(dir, "app.log.1")
    }

    fun d(area: String, message: String) = log(Log.DEBUG, area, message, null)
    fun i(area: String, message: String) = log(Log.INFO, area, message, null)
    fun w(area: String, message: String, error: Throwable? = null) = log(Log.WARN, area, message, error)
    fun e(area: String, message: String, error: Throwable? = null) = log(Log.ERROR, area, message, error)

    /** Writes synchronously, for the crash handler (the process dies right after). */
    fun crash(thread: Thread, error: Throwable) {
        val message = "Uncaught exception on thread ${thread.name}"
        Log.e(TAG, "[Crash] $message", error)
        append(format(Log.ERROR, "Crash", message, error))
    }

    /** The newest [maxChars] characters of the log files. */
    fun readTail(maxChars: Int): String = synchronized(lock) {
        val text = listOfNotNull(oldLogFile, logFile).filter { it.exists() }.joinToString("") { it.readText() }
        text.takeLast(maxChars)
    }

    fun clear() = synchronized(lock) {
        oldLogFile?.delete()
        logFile?.delete()
    }

    private fun log(priority: Int, area: String, message: String, error: Throwable?) {
        val text = "[$area] $message"
        if (error != null) Log.println(priority, TAG, text + "\n" + Log.getStackTraceString(error))
        else Log.println(priority, TAG, text)
        if (logFile == null) return
        val line = format(priority, area, message, error)
        writer.execute { append(line) }
    }

    private fun append(line: String) = synchronized(lock) {
        val file = logFile ?: return@synchronized
        try {
            if (file.length() > MAX_FILE_BYTES) {
                oldLogFile?.delete()
                file.renameTo(oldLogFile!!)
            }
            file.appendText(line)
        } catch (e: Exception) {
            Log.w(TAG, "[AppLog] Couldn't write the log file", e)
        }
    }

    private fun format(priority: Int, area: String, message: String, error: Throwable?): String {
        val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val level = when (priority) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            else -> "E"
        }
        val trace = error?.let { StringWriter().also { sw -> it.printStackTrace(PrintWriter(sw)) }.toString() }.orEmpty()
        return "$time $level [$area] $message\n$trace"
    }
}
