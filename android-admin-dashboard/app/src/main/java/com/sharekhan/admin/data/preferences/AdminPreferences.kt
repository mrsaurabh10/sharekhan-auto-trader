package com.sharekhan.admin.data.preferences

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sharekhan.admin.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

data class SavedLogin(
    val baseUrl: String,
    val username: String,
    val password: String
)

class AdminPreferences(private val context: Context) {

    private val dataStore: DataStore<Preferences> = context.adminDataStore

    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyUsername = stringPreferencesKey("username")
    private val keyEncryptedPassword = stringPreferencesKey("encrypted_password")

    val baseUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[keyBaseUrl] ?: BuildConfig.DEFAULT_BASE_URL
    }

    val lastUsername: Flow<String?> = dataStore.data.map { prefs ->
        prefs[keyUsername]
    }

    suspend fun saveBaseUrl(url: String) {
        dataStore.edit { prefs ->
            prefs[keyBaseUrl] = url
        }
    }

    suspend fun saveLastUsername(username: String) {
        dataStore.edit { prefs ->
            prefs[keyUsername] = username
        }
    }

    suspend fun saveRememberedLogin(baseUrl: String, username: String, password: String) {
        val encryptedPassword = encrypt(password)
        dataStore.edit { prefs ->
            prefs[keyBaseUrl] = baseUrl
            prefs[keyUsername] = username
            prefs[keyEncryptedPassword] = encryptedPassword
        }
    }

    suspend fun rememberedLogin(): SavedLogin? {
        val prefs = dataStore.data.first()
        val baseUrl = prefs[keyBaseUrl] ?: return null
        val username = prefs[keyUsername] ?: return null
        val encryptedPassword = prefs[keyEncryptedPassword] ?: return null
        val password = runCatching { decrypt(encryptedPassword) }.getOrElse {
            clearRememberedLogin()
            return null
        }
        return SavedLogin(baseUrl, username, password)
    }

    suspend fun clearRememberedLogin() {
        dataStore.edit { it.remove(keyEncryptedPassword) }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE) { "Invalid saved login" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, bytes.copyOfRange(0, IV_SIZE)))
        return cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): javax.crypto.SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "sharekhan_admin_remembered_login"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH_BITS = 128
    }
}

private val Context.adminDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "admin_prefs"
)
