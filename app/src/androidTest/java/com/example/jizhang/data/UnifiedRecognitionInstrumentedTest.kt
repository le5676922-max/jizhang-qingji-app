package com.example.jizhang.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.domain.*
import com.example.jizhang.recognition.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnifiedRecognitionInstrumentedTest {
    private lateinit var context:Context
    private lateinit var repository:LedgerRepository
    private lateinit var databaseName:String
    private lateinit var preferencesName:String
    @Before fun setup() {
        context=InstrumentationRegistry.getInstrumentation().targetContext
        val suffix=UUID.randomUUID().toString()
        databaseName="unified-$suffix.db";preferencesName="unified-$suffix"
        repository=LedgerRepository.isolated(context,databaseName,preferencesName)
        runBlocking { repository.retryPendingRestore() }
        repository.updateSettings(repository.settings.value.copy(screenEnabled=true,screenOcrEnabled=true))
    }
    @After fun cleanup()=runBlocking {
        repository.closeForTesting();context.deleteDatabase(databaseName);context.deleteSharedPreferences(preferencesName);Unit
    }
    @Test fun completedMerchantPaymentPostsOneEntryWithIndependentEvidence()=runBlocking {
        val candidate=payment()
        val result=repository.ingest(candidate)
        assertEquals(RecognitionDecision.AUTO,result.decision)
        assertEquals(1,repository.entries.value.size)
        assertEquals(BusinessType.EXPENSE,repository.entries.value.single().businessType)
        val event=repository.recognitionEvents.value.single()
        assertEquals(result.entryId,event.linkedEntryId)
        assertEquals(EvidenceType.ACCESSIBILITY,event.candidate.evidence)
        assertEquals(candidate.eventFingerprint,event.candidate.eventFingerprint)
        assertFalse(event.reasons.isEmpty())
    }
    @Test fun transferAndImageCandidatesDoNotCreatePostedRows()=runBlocking {
        assertEquals(RecognitionDecision.REVIEW,repository.ingest(payment("b",BusinessType.TRANSFER)).decision)
        assertEquals(RecognitionDecision.REVIEW,repository.ingest(payment("c").copy(evidence=EvidenceType.OCR)).decision)
        assertTrue(repository.entries.value.isEmpty())
        assertEquals(2,repository.recognitionEvents.value.size)
        assertTrue(repository.recognitionEvents.value.all { it.linkedEntryId==null })
    }
    @Test fun updateAliasesAndDeletedEventsStayIdempotentAcrossRestart()=runBlocking {
        val candidate=payment().copy(alternateFingerprints=listOf("d".repeat(64)))
        val first=repository.ingest(candidate)
        repository.delete(requireNotNull(first.entryId))
        repository.closeForTesting()
        repository=LedgerRepository.isolated(context,databaseName,preferencesName)
        runBlocking { repository.retryPendingRestore() }
        val repeated=repository.ingest(candidate.copy(eventFingerprint="e".repeat(64)))
        assertTrue(repeated.duplicate)
        assertTrue(repository.entries.value.isEmpty())
        assertEquals(1,repository.deletedEntries.value.size)
        assertEquals(1,repository.recognitionEvents.value.size)
        assertTrue(repository.recognitionEvents.value.single().linkedEntryDeleted)
    }
    @Test fun disabledSourceDoesNotWriteAnEvent()=runBlocking {
        repository.updateSettings(repository.settings.value.copy(wechatEnabled=false))
        assertTrue(repository.ingest(payment()).disabled)
        assertTrue(repository.entries.value.isEmpty());assertTrue(repository.recognitionEvents.value.isEmpty())
    }
    @Test fun failedAndPendingCandidatesCannotBecomePostedTransactions()=runBlocking {
        listOf(CompletionState.FAILED,CompletionState.PENDING,CompletionState.CANCELLED).forEachIndexed { i,state ->
            assertEquals(RecognitionDecision.IGNORE,repository.ingest(payment((i+1).toString()).copy(completion=state)).decision)
        }
        assertTrue(repository.entries.value.isEmpty())
        assertEquals(3,repository.recognitionEvents.value.size)
    }
    private fun payment(hash:String="a",kind:BusinessType=BusinessType.EXPENSE)=TransactionCandidate(
        provider=EntrySource.WECHAT,eventFingerprint=hash.repeat(64),amountCents=2800,
        direction=FundsDirection.OUTGOING,businessType=kind,merchant="合成咖啡店",
        occurredAt=System.currentTimeMillis(),categorySuggestion="餐饮",completion=CompletionState.COMPLETED,
        evidence=EvidenceType.ACCESSIBILITY,hasStableIdentity=true,ruleVersion="synthetic/unit-v2",
    )
}
