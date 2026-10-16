package com.sudoku.solver.processing

import kotlinx.coroutines.delay

internal enum class CellEntryState { EMPTY, FILLED, WRONG }
internal enum class EntryConfirmation { CONFIRMED, WRONG, UNCONFIRMED }

/** A gesture callback alone cannot prove the game accepted a digit. */
internal object VerifiedDigitEntry {
    suspend fun confirm(
        read: suspend () -> CellEntryState?,
        retry: suspend () -> Boolean,
        settle: suspend () -> Unit = { delay(80L) }
    ): EntryConfirmation {
        for (attempt in 0..1) {
            for (poll in 0..2) {
                when (read()) {
                    CellEntryState.FILLED -> return EntryConfirmation.CONFIRMED
                    CellEntryState.WRONG -> return EntryConfirmation.WRONG
                    null -> return EntryConfirmation.UNCONFIRMED
                    CellEntryState.EMPTY -> Unit
                }
                if (poll < 2) settle()
            }
            if (attempt == 0 && !retry()) return EntryConfirmation.UNCONFIRMED
        }
        return EntryConfirmation.UNCONFIRMED
    }
}
