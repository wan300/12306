package com.railhelper.ticket

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedLoginCredentials(
    val username: String = "",
    val password: String = "",
    val rememberUsername: Boolean = false,
    val rememberPassword: Boolean = false
)

class SecureCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("native_login_credentials", Context.MODE_PRIVATE)
    private val legacyPrefs = appContext.getSharedPreferences("web_login_credentials", Context.MODE_PRIVATE)

    init {
        migrateLegacyPrefs()
    }

    fun load(): SavedLoginCredentials {
        val rememberUsername = prefs.getBoolean(KEY_REMEMBER_USERNAME, false)
        val rememberPassword = prefs.getBoolean(KEY_REMEMBER_PASSWORD, false)
        val username = if (rememberUsername || rememberPassword) prefs.getString(KEY_USERNAME, "").orEmpty() else ""
        val password = if (rememberPassword) decryptPassword().orEmpty() else ""
        return SavedLoginCredentials(
            username = username,
            password = password,
            rememberUsername = rememberUsername,
            rememberPassword = rememberPassword && password.isNotBlank()
        )
    }

    fun save(username: String, password: String, rememberUsername: Boolean, rememberPassword: Boolean) {
        val normalizedUsername = username.trim()
        prefs.edit().apply {
            putBoolean(KEY_REMEMBER_USERNAME, rememberUsername || rememberPassword)
            putBoolean(KEY_REMEMBER_PASSWORD, rememberPassword)
            if (rememberUsername || rememberPassword) {
                putString(KEY_USERNAME, normalizedUsername)
            } else {
                remove(KEY_USERNAME)
            }
            if (rememberPassword && password.isNotBlank()) {
                putString(KEY_PASSWORD, encryptPassword(password))
            } else {
                remove(KEY_PASSWORD)
            }
        }.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        legacyPrefs.edit().clear().apply()
    }

    private fun migrateLegacyPrefs() {
        if (prefs.contains(KEY_REMEMBER_USERNAME) || !legacyPrefs.contains(KEY_REMEMBER_USERNAME)) return

        val rememberUsername = legacyPrefs.getBoolean(KEY_REMEMBER_USERNAME, false)
        val rememberPassword = legacyPrefs.getBoolean(KEY_REMEMBER_PASSWORD, false)
        val username = if (rememberUsername || rememberPassword) {
            legacyPrefs.getString(KEY_USERNAME, "").orEmpty()
        } else {
            ""
        }
        val password = if (rememberPassword) decryptPassword(legacyPrefs).orEmpty() else ""
        prefs.edit().apply {
            putBoolean(KEY_REMEMBER_USERNAME, rememberUsername || rememberPassword)
            putBoolean(KEY_REMEMBER_PASSWORD, rememberPassword && password.isNotBlank())
            if (username.isNotBlank()) putString(KEY_USERNAME, username)
            if (password.isNotBlank()) putString(KEY_PASSWORD, encryptPassword(password))
        }.apply()
    }

    private fun encryptPassword(password: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decryptPassword(): String? {
        return decryptPassword(prefs)
    }

    private fun decryptPassword(sourcePrefs: android.content.SharedPreferences): String? {
        val encoded = sourcePrefs.getString(KEY_PASSWORD, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= GCM_IV_BYTES) return null
            val iv = payload.copyOfRange(0, GCM_IV_BYTES)
            val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "railhelper_12306_web_login"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMEMBER_USERNAME = "remember_username"
        private const val KEY_REMEMBER_PASSWORD = "remember_password"
    }
}
