package com.example.jizhang.data.backup

data class BackupPreview(
    val createdAt: Long,
    val entryCount: Int,
    val eventCount: Int,
    val deletedEntryCount: Int = 0,
    val pendingEntryCount: Int = 0,
)

/** One-use authenticated restore candidate. No mutable JSON or plaintext is exposed to the UI. */
class PreparedRestore private constructor(
    private val owner: Any,
    private var payload: ByteArray?,
    val preview: BackupPreview,
) {
    private val lock = Any()
    val isDisposed: Boolean get() = synchronized(lock) { payload == null }

    fun dispose() = synchronized(lock) {
        payload?.fill(0)
        payload = null
    }

    internal fun consume(expectedOwner: Any): ByteArray = synchronized(lock) {
        if (owner !== expectedOwner || payload == null) {
            throw LedgerBackupException(LedgerBackupFailure.PREVIEW_EXPIRED, "恢复预览已失效，请重新选择并检查备份。")
        }
        val bytes = payload!!.copyOf()
        payload!!.fill(0)
        payload = null
        bytes
    }

    internal companion object {
        fun create(owner: Any, payload: ByteArray, preview: BackupPreview) =
            PreparedRestore(owner, payload.copyOf(), preview)
    }
}
