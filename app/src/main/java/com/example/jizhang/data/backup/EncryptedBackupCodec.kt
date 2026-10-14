package com.example.jizhang.data.backup

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class BackupFailure {
    INVALID_PASSWORD,
    UNSUPPORTED_FORMAT,
    INVALID_FORMAT,
    SIZE_LIMIT,
    AUTHENTICATION_FAILED,
    CRYPTO_UNAVAILABLE,
}

class BackupCodecException(
    val reason: BackupFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Password-encrypted, authenticated backup envelope. Contains no Android framework calls.
 * The caller owns its password and plaintext arrays and should clear them after use.
 * Only a bounded, supported header is accepted before invoking the expensive KDF.
 * All header bytes, including salt, nonce and declared length, are authenticated as AAD.
 */
object EncryptedBackupCodec {
    const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
    const val MAX_ENCRYPTED_BYTES = MAX_PAYLOAD_BYTES + 64
    const val MAX_PASSWORD_CHARS = 1_024
    const val KDF_ITERATIONS = 300_000

    // Big-endian format v1: magic[8], version/kdf/cipher/flags[1 each],
    // iterations[4], ciphertext length[4], salt[16], nonce[12], ciphertext + tag.
    private val MAGIC = byteArrayOf(0x51, 0x4A, 0x42, 0x41, 0x43, 0x4B, 0x55, 0x50) // QJBACKUP
    private const val VERSION = 1
    private const val KDF_ID = 1
    private const val CIPHER_ID = 1
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val KEY_BITS = 256
    private const val HEADER_BYTES = 48
    private const val MIN_FILE_BYTES = HEADER_BYTES + TAG_BYTES
    private const val MAX_FILE_BYTES = MAX_ENCRYPTED_BYTES
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA1"
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    private val random = SecureRandom()

    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        validatePassword(password)
        if (payload.size > MAX_PAYLOAD_BYTES) sizeFailure()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(VERSION.toByte()).put(KDF_ID.toByte()).put(CIPHER_ID.toByte()).put(0.toByte())
            .putInt(KDF_ITERATIONS).putInt(payload.size + TAG_BYTES)
            .put(salt).put(nonce).array()
        try {
            return withKey(password, salt) { key ->
                val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(header)
                header + cipher.doFinal(payload)
            }
        } catch (failure: GeneralSecurityException) {
            throw BackupCodecException(BackupFailure.CRYPTO_UNAVAILABLE,
                "设备暂时无法加密备份，请保留原账本后重试。", failure)
        }
    }

    fun decrypt(encrypted: ByteArray, password: CharArray): ByteArray {
        validatePassword(password)
        if (encrypted.size > MAX_FILE_BYTES) sizeFailure()
        if (encrypted.size < MIN_FILE_BYTES) formatFailure()
        val input = ByteBuffer.wrap(encrypted).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also { input.get(it) }
        if (!magic.contentEquals(MAGIC)) unsupportedFailure()
        val version = input.get().toInt() and 0xFF
        val kdf = input.get().toInt() and 0xFF
        val cipherId = input.get().toInt() and 0xFF
        val flags = input.get().toInt() and 0xFF
        val iterations = input.int
        if (version != VERSION || kdf != KDF_ID || cipherId != CIPHER_ID || flags != 0 ||
            iterations != KDF_ITERATIONS) unsupportedFailure()
        val ciphertextLength = input.int
        if (ciphertextLength < TAG_BYTES) formatFailure()
        if (ciphertextLength > MAX_PAYLOAD_BYTES + TAG_BYTES) sizeFailure()
        if (encrypted.size != HEADER_BYTES + ciphertextLength) formatFailure()
        val salt = ByteArray(SALT_BYTES).also { input.get(it) }
        val nonce = ByteArray(NONCE_BYTES).also { input.get(it) }
        try {
            return withKey(password, salt) { key ->
                val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(encrypted, 0, HEADER_BYTES)
                // doFinal authenticates before returning any plaintext to the caller.
                cipher.doFinal(encrypted, HEADER_BYTES, ciphertextLength)
            }
        } catch (failure: BadPaddingException) {
            // Includes AEADBadTagException; do not distinguish wrong passwords from tampering.
            throw BackupCodecException(BackupFailure.AUTHENTICATION_FAILED,
                "备份校验失败：密码不正确或文件已损坏。", failure)
        } catch (failure: GeneralSecurityException) {
            throw BackupCodecException(BackupFailure.CRYPTO_UNAVAILABLE,
                "设备暂时无法解密此备份，请保留原文件后重试。", failure)
        }
    }

    private inline fun <T> withKey(password: CharArray, salt: ByteArray, use: (SecretKeySpec) -> T): T {
        // PBEKeySpec makes its own password copy. Clear that copy even if derivation fails.
        val specification = PBEKeySpec(password, salt, KDF_ITERATIONS, KEY_BITS)
        val keyBytes = try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(specification).encoded
        } finally {
            specification.clearPassword()
        }
        try {
            return use(SecretKeySpec(keyBytes, "AES"))
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun validatePassword(password: CharArray) {
        if (password.isEmpty() || password.size > MAX_PASSWORD_CHARS) {
            throw BackupCodecException(BackupFailure.INVALID_PASSWORD, "请输入不超过1024个字符的备份密码。")
        }
    }

    private fun formatFailure(): Nothing = throw BackupCodecException(
        BackupFailure.INVALID_FORMAT, "备份格式不正确或文件不完整。",
    )

    private fun unsupportedFailure(): Nothing = throw BackupCodecException(
        BackupFailure.UNSUPPORTED_FORMAT, "该备份版本或加密格式暂不受支持。",
    )

    private fun sizeFailure(): Nothing = throw BackupCodecException(
        BackupFailure.SIZE_LIMIT, "备份数据超过16MiB上限，请保留原账本。",
    )
}
