package com.dreaminalife.lyricstranslator.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Kept apart from the settings file so backups can exclude it (see res/xml/*backup_rules.xml).
private val Context.secretsDataStore by preferencesDataStore(name = "secrets")

/**
 * Stores the OpenRouter key and the Spotify sp_dc cookie encrypted with an AES-GCM key that never leaves the
 * Android Keystore.
 */
class SecretStore(context: Context) {
    private val dataStore = context.secretsDataStore

    /** The decrypted key, or null if none is set or it can't be decrypted (e.g. the Keystore key was reset). */
    val openRouterKey: Flow<String?> = secret(OPENROUTER_KEY, "OpenRouter key")

    /** The decrypted sp_dc cookie used for Spotify lyrics, or null as for [openRouterKey]. */
    val spDc: Flow<String?> = secret(SP_DC, "sp_dc cookie")

    suspend fun setOpenRouterKey(key: String?) = setSecret(OPENROUTER_KEY, "OpenRouter key", key)

    suspend fun setSpDc(value: String?) = setSecret(SP_DC, "sp_dc cookie", value)

    private fun secret(prefKey: Preferences.Key<String>, label: String): Flow<String?> = dataStore.data
        .map { prefs -> prefs[prefKey]?.let { decrypt(it, label) } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    private suspend fun setSecret(prefKey: Preferences.Key<String>, label: String, value: String?) {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
        val encrypted = trimmed?.let { withContext(Dispatchers.IO) { encrypt(it) } }
        dataStore.edit { prefs ->
            if (encrypted == null) prefs.remove(prefKey) else prefs[prefKey] = encrypted
        }
        // Never log the secret itself.
        AppLog.i("Settings", if (encrypted == null) "$label removed" else "$label saved (${trimmed?.length} chars)")
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String, label: String): String? = try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        }
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    } catch (e: Exception) {
        AppLog.e("Settings", "Stored $label can't be decrypted; treating it as missing", e)
        null
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        val OPENROUTER_KEY = stringPreferencesKey("openrouter_api_key")
        val SP_DC = stringPreferencesKey("sp_dc")
        const val KEYSTORE = "AndroidKeyStore"
        // Shared by all secrets; keeps its original name so keys saved before sp_dc existed still decrypt.
        const val KEY_ALIAS = "openrouter_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
