package com.dreaminalife.lyricstranslator.data.cache

import com.dreaminalife.lyricstranslator.data.net.AppJson
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File
import java.security.MessageDigest

/**
 * Stores values as JSON files in [directory], one file per (group, key).
 *
 * Entries are grouped (e.g. by track) so all entries of a group can be removed together.
 * When there are more than [maxEntries] files, the least recently used ones are deleted.
 */
class JsonFileCache<T>(
    private val directory: File,
    private val serializer: KSerializer<T>,
    private val maxEntries: Int = 10_000,
) {
    private val mutex = Mutex()

    suspend fun get(group: String, key: String = ""): T? = io {
        val file = fileFor(group, key)
        if (!file.exists()) return@io null
        try {
            AppJson.decodeFromString(serializer, file.readText()).also {
                file.setLastModified(System.currentTimeMillis())
            }
        } catch (e: Exception) {
            // Unreadable (e.g. written by an older format): drop it and refetch.
            AppLog.w("Cache", "Deleting unreadable cache file ${directory.name}/${file.name}", e)
            file.delete()
            null
        }
    }

    suspend fun put(group: String, key: String = "", value: T) = io {
        directory.mkdirs()
        val file = fileFor(group, key)
        val tmp = File(directory, file.name + ".tmp")
        tmp.writeText(AppJson.encodeToString(serializer, value))
        tmp.renameTo(file)
        trim()
    }

    suspend fun removeGroup(group: String) = io {
        val prefix = hash(group) + "_"
        directory.listFiles { file -> file.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    private fun trim() {
        val files = directory.listFiles { file -> file.name.endsWith(".json") } ?: return
        if (files.size <= maxEntries) return
        val excess = files.size - maxEntries
        AppLog.d("Cache", "${directory.name}: ${files.size} entries, removing the $excess least recently used")
        // Each file's time is read once: sortedBy would read it on every comparison, slow with thousands of files.
        files.map { it to it.lastModified() }.sortedBy { it.second }.take(excess).forEach { it.first.delete() }
    }

    private fun fileFor(group: String, key: String) = File(directory, "${hash(group)}_${hash(key)}.json")

    private suspend fun <R> io(block: () -> R): R =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
}
