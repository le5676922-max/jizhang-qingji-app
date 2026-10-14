package com.example.jizhang.recognition

/** Human ignoring is an explicit latch. Automatic evidence cannot undo it. */
object EventStateMachine {
    fun canTransition(old: CompletionState, new: CompletionState, userIgnoredFlag: Boolean): Boolean {
        if (userIgnoredFlag) return false
        if (old == new) return true // Metadata can become richer without reversing completion.
        if (old == CompletionState.COMPLETED) return false
        if (new == CompletionState.COMPLETED) return true
        return when (old) {
            CompletionState.UNCONFIRMED -> true
            CompletionState.PENDING -> new in setOf(CompletionState.FAILED, CompletionState.CANCELLED)
            CompletionState.FAILED, CompletionState.CANCELLED -> false
            CompletionState.COMPLETED -> false
        }
    }
}
