package com.example.jizhang.recognition

import org.junit.Assert.*
import org.junit.Test

class EventStateMachineTest {
    @Test fun automaticUnfinishedFailedAndCancelledEvidenceCanLaterComplete() {
        listOf(CompletionState.UNCONFIRMED, CompletionState.PENDING, CompletionState.FAILED,
            CompletionState.CANCELLED).forEach {
            assertTrue(EventStateMachine.canTransition(it, CompletionState.COMPLETED, false))
        }
    }
    @Test fun completionCannotAutomaticallyRegressToFailedPendingOrCancelled() {
        CompletionState.entries.filter { it != CompletionState.COMPLETED }.forEach {
            assertFalse(EventStateMachine.canTransition(CompletionState.COMPLETED, it, false))
        }
        assertTrue(EventStateMachine.canTransition(CompletionState.COMPLETED, CompletionState.COMPLETED, false))
    }
    @Test fun explicitHumanIgnoreLatchesEveryAutomaticTransition() {
        CompletionState.entries.forEach { old -> CompletionState.entries.forEach { new ->
            assertFalse(EventStateMachine.canTransition(old, new, true))
        } }
    }
    @Test fun aPendingEventCanFailWithoutReturningToUnknown() {
        assertTrue(EventStateMachine.canTransition(CompletionState.PENDING, CompletionState.FAILED, false))
        assertFalse(EventStateMachine.canTransition(CompletionState.PENDING, CompletionState.UNCONFIRMED, false))
    }
}
