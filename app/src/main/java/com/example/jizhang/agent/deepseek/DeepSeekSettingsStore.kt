package com.example.jizhang.agent.deepseek

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The API key is an AES-GCM ciphertext; its non-exportable AES key lives in AndroidKeyStore.
 * This separate preference file must be excluded from system and financial backups.
 * A cryptographic or storage failure never falls back to plaintext storage.
 */
class DeepSeekSettingsStore internal constructor(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
) {
    constructor(context: Context) : this(context, PREFERENCES_NAME, KEY_ALIAS)

    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun configuration(): DeepSeekConfiguration = synchronized(LOCK) {
        safely {
            DeepSeekConfiguration(
                enabled = preferences.getBoolean(ENABLED, false),
                model = preferences.getString(MODEL, DeepSeekModels.DEFAULT)
                    .takeIf { it in DeepSeekModels.SUPPORTED } ?: DeepSeekModels.DEFAULT,
                hasKey = !preferences.getString(KEY_CIPHERTEXT, null).isNullOrEmpty(),
            )
        }
    }

    /** A null API key preserves the current key; a blank replacement is not accepted. */
    fun save(apiKey: String?, model: String, enabled: Boolean) = synchronized(LOCK) {
        DeepSeekModels.requireSupported(model)
        val replacement = apiKey?.let(::validatedKey)
        safely {
            if (enabled && replacement == null) readApiKeyLocked()
            val encrypted = replacement?.let(::encrypt)
            val editor = preferences.edit().putString(MODEL, model).putBoolean(ENABLED, enabled)
            if (encrypted != null) editor.putString(KEY_CIPHERTEXT, encrypted)
            if (!editor.commit()) throw DeepSeekException("无法保存 DeepSeek 设置，请重试。")
        }
    }

    /** Returned only to the request owner; callers must never log or persist this plaintext. */
    fun readApiKey(): String = synchronized(LOCK) { safely { readApiKeyLocked() } }

    fun clear() = synchronized(LOCK) {
        safely {
            if (!preferences.edit().clear().commit()) {
                throw DeepSeekException("无法清除 DeepSeek 设置，请重试。")
            }
            keyStore().deleteEntry(keyAlias)
        }
    }

    private fun readApiKeyLocked(): String {
        val envelope = preferences.getString(KEY_CIPHERTEXT, null)
            ?: throw DeepSeekException("请先配置 DeepSeek API Key。")
        if (envelope.length > MAX_ENVELOPE_CHARS) invalidKeyStorage()
        val fields = envelope.split(':')
        if (fields.size != 3 || fields[0] != "v1") invalidKeyStorage()
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            iv = Base64.decode(fields[1], Base64.NO_WRAP)
            ciphertext = Base64.decode(fields[2], Base64.NO_WRAP)
            if (iv.size != 12 || ciphertext.size !in 17..(MAX_KEY_CHARS + 16)) invalidKeyStorage()
            val key = keyStore().getKey(keyAlias, null) as? SecretKey ?: invalidKeyStorage()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(AAD)
            plaintext = cipher.doFinal(ciphertext)
            return validatedKey(String(plaintext, StandardCharsets.UTF_8))
        } finally {
            plaintext?.fill(0)
            ciphertext?.fill(0)
            iv?.fill(0)
        }
    }

    private fun encrypt(apiKey: String): String {
        val plaintext = apiKey.toByteArray(StandardCharsets.UTF_8)
        var ciphertext: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(AAD)
            ciphertext = cipher.doFinal(plaintext)
            if (cipher.iv.size != 12) invalidKeyStorage()
            return "v1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } finally {
            plaintext.fill(0)
            ciphertext?.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        if (store.containsAlias(keyAlias)) {
            return store.getKey(keyAlias, null) as? SecretKey ?: invalidKeyStorage()
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(
                keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private inline fun <T> safely(operation: () -> T): T = try {
        operation()
    } catch (failure: DeepSeekException) {
        throw failure
    } catch (_: Exception) {
        throw DeepSeekException("无法读取或安全保存 DeepSeek 密钥，请重新配置；账本不受影响。")
    }

    private fun invalidKeyStorage(): Nothing =
        throw DeepSeekException("DeepSeek 密钥已失效或存储损坏，请重新配置。")

    companion object {
        const val PREFERENCES_NAME = "deepseek_provider_settings"
        private const val KEY_ALIAS = "qingji_deepseek_api_key_v1"
        private const val ENABLED = "enabled"
        private const val MODEL = "model"
        internal const val KEY_CIPHERTEXT = "api_key_ciphertext"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MAX_KEY_CHARS = 512
        private const val MAX_ENVELOPE_CHARS = 1_024
        private val AAD = "qingji.deepseek.api-key.v1".toByteArray(StandardCharsets.US_ASCII)
        private val LOCK = Any()

        internal fun validatedKey(value: String): String {
            // Bearer tokens are printable ASCII, without separators or hidden controls.
            val key = value.trim()
            if (key.length !in 1..MAX_KEY_CHARS || key.any { it.code !in 33..126 }) {
                throw DeepSeekException("API Key 格式不正确，请完整粘贴密钥。")
            }
            return key
        }
    }
}
