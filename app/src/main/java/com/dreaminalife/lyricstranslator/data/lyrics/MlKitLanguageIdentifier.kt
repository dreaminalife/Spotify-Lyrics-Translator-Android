package com.dreaminalife.lyricstranslator.data.lyrics

import com.dreaminalife.lyricstranslator.log.AppLog
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** ML Kit's on-device language identification; the model ships with the app, so it works offline. */
class MlKitLanguageIdentifier {
    // Strict: a song it isn't sure about gets translated rather than left as is.
    private val client = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.9f).build(),
    )

    /** BCP-47 code of [text] ("en", "es", ...), or null if unsure or identification failed. */
    suspend fun identify(text: String): String? = suspendCancellableCoroutine { continuation ->
        client.identifyLanguage(text)
            .addOnSuccessListener { continuation.resume(it.takeIf { it != LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG }) }
            .addOnFailureListener {
                AppLog.w(TAG, "Language identification failed", it)
                continuation.resume(null)
            }
    }

    private companion object {
        const val TAG = "LanguageId"
    }
}
