package com.example.jizhang.data.backup

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EncryptedBackupCodecTest {
    @Test
    fun authenticatedRoundTripPreservesExactChineseJsonBytes() {
        val payload = """{"tables":{"entries":[{"amount_cents":1230,"merchant":"张三"}]},"settings":{"screen_enabled":false}}"""
            .toByteArray(Charsets.UTF_8)
        val password = "备份口令 with spaces 123".toCharArray()
        val originalPassword = password.copyOf()
        try {
            val encrypted = EncryptedBackupCodec.encrypt(payload, password)
            assertFalse(payload.contentEquals(encrypted))
            assertArrayEquals(payload, EncryptedBackupCodec.decrypt(encrypted, password))
            assertTrue(password.contentEquals(originalPassword))
        } finally {
            password.fill('\u0000')
            originalPassword.fill('\u0000')
        }
    }

    @Test
    fun repeatedEncryptionUsesFreshSaltAndNonce() {
        val payload = "same financial snapshot".toByteArray()
        val password = password()
        try {
            val first = EncryptedBackupCodec.encrypt(payload, password)
            val second = EncryptedBackupCodec.encrypt(payload, password)
            assertFalse(first.contentEquals(second))
            assertFalse(first.copyOfRange(20, 36).contentEquals(second.copyOfRange(20, 36)))
            assertFalse(first.copyOfRange(36, 48).contentEquals(second.copyOfRange(36, 48)))
            assertArrayEquals(payload, EncryptedBackupCodec.decrypt(first, password))
            assertArrayEquals(payload, EncryptedBackupCodec.decrypt(second, password))
        } finally { password.fill('\u0000') }
    }

    @Test
    fun wrongPasswordNeverReturnsPlaintext() {
        val correct = password()
        val wrong = "different backup password".toCharArray()
        try {
            val encrypted = EncryptedBackupCodec.encrypt(byteArrayOf(0, 1, -1, 42), correct)
            expectFailure(BackupFailure.AUTHENTICATION_FAILED) {
                EncryptedBackupCodec.decrypt(encrypted, wrong)
            }
        } finally { correct.fill('\u0000'); wrong.fill('\u0000') }
    }

    @Test
    fun tamperingWithCiphertextSaltOrNonceFailsAuthentication() {
        val password = password()
        try {
            val encrypted = EncryptedBackupCodec.encrypt("private ledger".toByteArray(), password)
            listOf(20, 36, 48, encrypted.lastIndex).forEach { position ->
                val corrupted = encrypted.copyOf()
                corrupted[position] = (corrupted[position].toInt() xor 1).toByte()
                expectFailure(BackupFailure.AUTHENTICATION_FAILED) {
                    EncryptedBackupCodec.decrypt(corrupted, password)
                }
            }
        } finally { password.fill('\u0000') }
    }

    @Test
    fun truncationAndTrailingBytesAreRejectedBeforeDecryption() {
        val password = password()
        try {
            val encrypted = EncryptedBackupCodec.encrypt("snapshot".toByteArray(), password)
            listOf(0, 7, 47, 63, encrypted.size - 1).forEach { retained ->
                expectFailure(BackupFailure.INVALID_FORMAT) {
                    EncryptedBackupCodec.decrypt(encrypted.copyOf(retained), password)
                }
            }
            expectFailure(BackupFailure.INVALID_FORMAT) {
                EncryptedBackupCodec.decrypt(encrypted + byteArrayOf(0), password)
            }
        } finally { password.fill('\u0000') }
    }

    @Test
    fun unsupportedVersionAlgorithmsFlagsAndIterationsCannotControlTheKdf() {
        val password = password()
        try {
            val encrypted = EncryptedBackupCodec.encrypt(byteArrayOf(42), password)
            listOf(0, 8, 9, 10, 11).forEach { position ->
                val unsupported = encrypted.copyOf()
                unsupported[position] = (unsupported[position].toInt() xor 0x7F).toByte()
                expectFailure(BackupFailure.UNSUPPORTED_FORMAT) {
                    EncryptedBackupCodec.decrypt(unsupported, password)
                }
            }
            val excessiveIterations = encrypted.copyOf()
            ByteBuffer.wrap(excessiveIterations).order(ByteOrder.BIG_ENDIAN).putInt(12, Int.MAX_VALUE)
            expectFailure(BackupFailure.UNSUPPORTED_FORMAT) {
                EncryptedBackupCodec.decrypt(excessiveIterations, password)
            }
        } finally { password.fill('\u0000') }
    }

    @Test
    fun invalidAndOversizedDeclaredLengthsAreRejectedWithoutAllocation() {
        val password = password()
        try {
            val encrypted = EncryptedBackupCodec.encrypt(byteArrayOf(42), password)
            listOf(-1, 0, 15, 100).forEach { length ->
                val invalid = encrypted.copyOf()
                ByteBuffer.wrap(invalid).order(ByteOrder.BIG_ENDIAN).putInt(16, length)
                expectFailure(BackupFailure.INVALID_FORMAT) {
                    EncryptedBackupCodec.decrypt(invalid, password)
                }
            }
            val oversized = encrypted.copyOf()
            ByteBuffer.wrap(oversized).order(ByteOrder.BIG_ENDIAN).putInt(16, Int.MAX_VALUE)
            expectFailure(BackupFailure.SIZE_LIMIT) {
                EncryptedBackupCodec.decrypt(oversized, password)
            }
        } finally { password.fill('\u0000') }
    }

    @Test
    fun payloadAndEnvelopeSizeCapsAreEnforced() {
        val password = password()
        try {
            expectFailure(BackupFailure.SIZE_LIMIT) {
                EncryptedBackupCodec.encrypt(ByteArray(EncryptedBackupCodec.MAX_PAYLOAD_BYTES + 1), password)
            }
            expectFailure(BackupFailure.SIZE_LIMIT) {
                EncryptedBackupCodec.decrypt(ByteArray(EncryptedBackupCodec.MAX_PAYLOAD_BYTES + 65), password)
            }
        } finally { password.fill('\u0000') }
    }

    @Test
    fun emptyPayloadCanRoundTripButEmptyOrUnboundedPasswordsAreRejected() {
        val password = password()
        try {
            val encrypted = EncryptedBackupCodec.encrypt(byteArrayOf(), password)
            assertArrayEquals(byteArrayOf(), EncryptedBackupCodec.decrypt(encrypted, password))
            expectFailure(BackupFailure.INVALID_PASSWORD) {
                EncryptedBackupCodec.encrypt(byteArrayOf(1), charArrayOf())
            }
            expectFailure(BackupFailure.INVALID_PASSWORD) {
                EncryptedBackupCodec.decrypt(encrypted, CharArray(EncryptedBackupCodec.MAX_PASSWORD_CHARS + 1) { 'x' })
            }
        } finally { password.fill('\u0000') }
    }

    private fun password() = "a locally chosen backup password".toCharArray()

    private inline fun expectFailure(reason: BackupFailure, action: () -> Unit) {
        try {
            action()
            fail("Expected backup failure: $reason")
        } catch (failure: BackupCodecException) {
            assertEquals(reason, failure.reason)
        }
    }
}
