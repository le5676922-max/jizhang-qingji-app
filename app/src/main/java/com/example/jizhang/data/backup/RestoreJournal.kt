package com.example.jizhang.data.backup

import android.content.Context
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class RestoreJournalException(message: String) : Exception(message)

/**
 * Private crash-recovery checkpoint, containing the same validated rows/settings as SQLite backup.
 * The repository must recover it before reading/capturing and clear it only after DB and prefs succeed.
 * No external path, original image, notification text or password is accepted by this API.
 * Returned bytes belong to the caller, who should clear them after recovery.
 */
class RestoreJournal(context: Context, fileName: String = DEFAULT_FILE_NAME) {
    private val directory = privateDirectory(context)
    private val baseFile = File(directory, checkedName(fileName))
    private val backupFile = File(directory, "${baseFile.name}.bak")
    private val newFile = File(directory, "${baseFile.name}.new")
    private val atomicFile = AtomicFile(baseFile)
    // AtomicFile supplies durability, not synchronization. Share a JVM lock across instances.
    private val lock = synchronized(locks) { locks.getOrPut(baseFile.absolutePath) { Any() } }

    fun writeOriginal(payload: ByteArray) = synchronized(lock) {
        checkPrivateFiles()
        if (hasArtifacts()) throw RestoreJournalException("存在尚未处理的恢复检查点，请先恢复原账本。")
        if (payload.isEmpty() || payload.size > EncryptedBackupCodec.MAX_PAYLOAD_BYTES) {
            throw RestoreJournalException("恢复检查点的数据大小不正确，尚未开始替换账本。")
        }
        val original = payload.copyOf()
        var stream: FileOutputStream? = null
        try {
            BackupDocumentValidator.validate(original)
            val digest = MessageDigest.getInstance("SHA-256").digest(original)
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC).putInt(VERSION).putInt(original.size).put(digest).array()
            val output = atomicFile.startWrite()
            stream = output
            output.write(header)
            output.write(original)
            atomicFile.finishWrite(output)
            stream = null
            val persisted = readOriginalLocked() ?: throw RestoreJournalException(WRITE_FAILED)
            try {
                if (!persisted.contentEquals(original)) throw RestoreJournalException(WRITE_FAILED)
            } finally {
                persisted.fill(0)
            }
        } catch (_: Exception) {
            stream?.let { output ->
                try { atomicFile.failWrite(output) } catch (_: Exception) { }
            }
            // Keep any completed file if readback failed; the repository must not alter the DB.
            throw RestoreJournalException(WRITE_FAILED)
        } finally {
            original.fill(0)
        }
    }

    fun readOriginal(): ByteArray? = synchronized(lock) {
        checkPrivateFiles()
        readOriginalLocked()
    }

    fun exists(): Boolean = synchronized(lock) {
        checkPrivateFiles()
        hasArtifacts()
    }

    fun clear() = synchronized(lock) {
        checkPrivateFiles()
        try {
            atomicFile.delete()
            // Older Android uses .bak; newer releases also use .new. Verify every artifact.
            listOf(baseFile, backupFile, newFile).forEach { file ->
                if (file.exists() && !file.delete()) throw RestoreJournalException(CLEAR_FAILED)
            }
            if (hasArtifacts()) throw RestoreJournalException(CLEAR_FAILED)
        } catch (_: Exception) {
            throw RestoreJournalException(CLEAR_FAILED)
        }
    }

    private fun readOriginalLocked(): ByteArray? {
        if (!hasArtifacts()) return null
        var payload: ByteArray? = null
        try {
            // openRead repairs AtomicFile's interrupted .bak write before reading the checkpoint.
            DataInputStream(atomicFile.openRead()).use { input ->
                // A failed legacy .bak rename must never fall back to an uncommitted base file.
                if (backupFile.exists()) invalid()
                val header = ByteArray(HEADER_BYTES)
                input.readFully(header)
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
                if (!magic.contentEquals(MAGIC) || buffer.int != VERSION) invalid()
                val length = buffer.int
                if (length !in 1..EncryptedBackupCodec.MAX_PAYLOAD_BYTES) invalid()
                val expectedDigest = ByteArray(DIGEST_BYTES).also { buffer.get(it) }
                val bytes = ByteArray(length)
                payload = bytes
                input.readFully(bytes)
                if (input.read() != -1) invalid()
                val actualDigest = MessageDigest.getInstance("SHA-256").digest(bytes)
                if (!MessageDigest.isEqual(expectedDigest, actualDigest)) invalid()
                BackupDocumentValidator.validate(bytes)
                return bytes
            }
        } catch (_: Exception) {
            payload?.fill(0)
            throw RestoreJournalException(READ_FAILED)
        }
    }

    private fun hasArtifacts(): Boolean = baseFile.exists() || backupFile.exists() || newFile.exists()

    private fun checkPrivateFiles() {
        try {
            if (!directory.isDirectory) throw RestoreJournalException(PATH_FAILED)
            listOf(baseFile, backupFile, newFile).forEach { file ->
                // Prevent symlinks or directories from redirecting AtomicFile's read/write/delete.
                if (file.canonicalFile != file.absoluteFile || file.exists() && !file.isFile) {
                    throw RestoreJournalException(PATH_FAILED)
                }
            }
        } catch (_: Exception) {
            throw RestoreJournalException(PATH_FAILED)
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME = "restore-original.checkpoint"
        private val MAGIC = byteArrayOf(0x51, 0x4A, 0x52, 0x45, 0x53, 0x54, 0x4F, 0x52) // QJRESTOR
        private const val VERSION = 1
        private const val DIGEST_BYTES = 32
        private const val HEADER_BYTES = 48 // magic[8], version[4], length[4], SHA-256[32]
        private const val WRITE_FAILED = "未能完整保存恢复检查点，尚未开始替换账本。"
        private const val READ_FAILED = "恢复检查点不完整或已损坏，请保留检查点后重试；原账本尚未安全恢复。"
        private const val CLEAR_FAILED = "恢复检查点未能清除，请保留当前账本后重试。"
        private const val PATH_FAILED = "恢复检查点的私有文件不可用，请保留当前账本后重试。"
        private val locks = mutableMapOf<String, Any>()

        private fun checkedName(name: String): String {
            if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}").matches(name) ||
                name.endsWith(".bak") || name.endsWith(".new")) {
                throw RestoreJournalException("恢复检查点文件名不正确。")
            }
            return name
        }

        private fun privateDirectory(context: Context): File = try {
            context.applicationContext.filesDir.canonicalFile
        } catch (_: Exception) {
            throw RestoreJournalException(PATH_FAILED)
        }

        private fun invalid(): Nothing = throw RestoreJournalException(READ_FAILED)
    }
}
