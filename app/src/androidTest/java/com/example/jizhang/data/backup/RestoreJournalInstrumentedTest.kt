package com.example.jizhang.data.backup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Only random private test filenames are used; no production checkpoint or ledger is opened. */
@RunWith(AndroidJUnit4::class)
class RestoreJournalInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun removeOnlyRandomTestArtifacts() {
        names.forEach { name ->
            listOf("", ".bak", ".new").forEach { suffix ->
                val file = File(context.filesDir, name + suffix)
                if (file.isDirectory) {
                    file.listFiles().orEmpty().forEach { child ->
                        assertTrue(child.isFile)
                        assertTrue(child.delete())
                    }
                }
                assertTrue(!file.exists() || file.delete())
            }
        }
        names.clear()
    }

    @Test
    fun checkpointSurvivesANewInstanceAndClearRemovesAllArtifacts() {
        val (name, journal) = isolatedJournal()
        val original = snapshot()
        assertFalse(journal.exists())
        assertNull(journal.readOriginal())

        journal.writeOriginal(original)

        assertTrue(journal.exists())
        val reopened = RestoreJournal(context, name)
        assertArrayEquals(original, reopened.readOriginal())
        assertTrue(BackupDocumentValidator.validate(reopened.readOriginal()!!).settings.autoEnabled)
        reopened.clear()
        assertFalse(journal.exists())
        assertNull(journal.readOriginal())
        listOf("", ".bak", ".new").forEach { assertFalse(File(context.filesDir, name + it).exists()) }
        reopened.clear() // Clearing a successfully removed checkpoint is idempotent.
    }

    @Test
    fun unfinishedRestoreCannotOverwriteTheOriginalCheckpoint() {
        val (name, journal) = isolatedJournal()
        val original = snapshot()
        journal.writeOriginal(original)

        expectFailure { RestoreJournal(context, name).writeOriginal(snapshot(12_345)) }

        assertArrayEquals(original, journal.readOriginal())
        assertTrue(journal.exists())
    }

    @Test
    fun interruptedAtomicWriteRecoversTheBakInsteadOfReadingPartialReplacement() {
        val (name, journal) = isolatedJournal()
        val original = snapshot()
        journal.writeOriginal(original)
        val base = File(context.filesDir, name)
        val backup = File(context.filesDir, "$name.bak")
        assertTrue(base.renameTo(backup))
        base.writeBytes(byteArrayOf(0x51, 0x4A))

        val reopened = RestoreJournal(context, name)
        assertTrue(reopened.exists())
        assertArrayEquals(original, reopened.readOriginal())
        assertTrue(base.isFile)
        assertFalse(backup.exists())
    }

    @Test
    fun corruptedUnsupportedTruncatedAndOversizedFilesRemainAvailableForRetry() {
        val (name, journal) = isolatedJournal()
        journal.writeOriginal(snapshot())
        val base = File(context.filesDir, name)
        val complete = base.readBytes()
        val invalid = listOf(
            complete.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() },
            complete.copyOf().apply { ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN).putInt(8, 99) },
            complete.copyOf().apply {
                ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN).putInt(12, EncryptedBackupCodec.MAX_PAYLOAD_BYTES + 1)
            },
            complete.copyOf().apply { this[48] = (this[48].toInt() xor 1).toByte() },
            complete.copyOf(47),
            complete.copyOf(complete.size - 1),
            complete + byteArrayOf(0),
        )
        invalid.forEach { corrupted ->
            base.writeBytes(corrupted)
            val failure = expectFailure { journal.readOriginal() }
            assertFalse(failure.message.orEmpty().contains("个人备注"))
            assertNull(failure.cause)
            assertTrue(journal.exists())
            assertArrayEquals(corrupted, base.readBytes())
            expectFailure { journal.writeOriginal(snapshot(500)) }
            assertArrayEquals(corrupted, base.readBytes())
        }
    }

    @Test
    fun newFileWithoutACommittedCheckpointBlocksReplacementAndNeverLooksAbsent() {
        val (name, journal) = isolatedJournal()
        File(context.filesDir, "$name.new").writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(journal.exists())
        expectFailure { journal.readOriginal() }
        expectFailure { journal.writeOriginal(snapshot()) }
        assertTrue(File(context.filesDir, "$name.new").exists())
    }

    @Test
    fun invalidSnapshotAndOversizedInputCreateNoCheckpoint() {
        val (_, journal) = isolatedJournal()

        listOf(ByteArray(0), "{}".toByteArray(), ByteArray(EncryptedBackupCodec.MAX_PAYLOAD_BYTES + 1))
            .forEach { invalid ->
                expectFailure { journal.writeOriginal(invalid) }
                assertFalse(journal.exists())
                assertNull(journal.readOriginal())
            }
    }

    @Test
    fun filenamesCannotEscapePrivateDirectoryAndFailedClearCannotReportSuccess() {
        listOf("../checkpoint", "/sdcard/checkpoint", "folder/checkpoint", "folder\\checkpoint", "checkpoint.bak",
            "checkpoint.new", ".hidden", "").forEach { invalid ->
            expectFailure { RestoreJournal(context, invalid) }
        }
        val (name, journal) = isolatedJournal()
        val original = snapshot()
        journal.writeOriginal(original)
        val newDirectory = File(context.filesDir, "$name.new")
        assertTrue(newDirectory.mkdir())
        File(newDirectory, "blocked").writeText("fixture")

        expectFailure { journal.clear() }

        assertTrue(File(context.filesDir, name).isFile)
        assertTrue(File(newDirectory, "blocked").isFile)
        assertTrue(File(newDirectory, "blocked").delete())
        assertTrue(newDirectory.delete())
        assertArrayEquals(original, journal.readOriginal())
    }

    private fun isolatedJournal(): Pair<String, RestoreJournal> {
        val name = "restore-journal-test-${UUID.randomUUID()}.bin"
        names += name
        return name to RestoreJournal(context, name)
    }

    private fun snapshot(amount: Long = 10_000): ByteArray {
        val tables = JSONObject()
        BackupDocumentValidator.tableColumns.keys.forEach { tables.put(it, JSONArray()) }
        tables.getJSONArray("entries").put(JSONObject().put("id", 1).put("amount_cents", amount)
            .put("type", "EXPENSE").put("category", "餐饮").put("merchant", "星巴克").put("note", "个人备注")
            .put("occurred_at", NOW).put("source", "MANUAL").put("fingerprint", JSONObject.NULL)
            .put("needs_review", 0).put("capture_kind", "MANUAL").put("business_type", "EXPENSE")
            .put("related_entry_id", JSONObject.NULL).put("edited_by_user", 1).put("deleted_at", JSONObject.NULL))
        return JSONObject().put("formatVersion", 2).put("schemaVersion", 3).put("createdAt", NOW)
            .put("tables", tables).put("settings", JSONObject().put("autoEnabled", true).put("wechatEnabled", false)
                .put("alipayEnabled", true).put("monthlyBudgetCents", 420_000).put("screenEnabled", true)
                .put("screenOcrEnabled", false)).toString().toByteArray(Charsets.UTF_8)
    }

    private fun expectFailure(action: () -> Any?): RestoreJournalException {
        try {
            action()
            fail("Expected safe journal failure")
        } catch (failure: RestoreJournalException) {
            return failure
        }
        error("Expected journal failure")
    }

    companion object {
        private const val NOW = 1_760_000_000_000L
    }
}
