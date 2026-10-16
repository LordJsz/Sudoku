package com.sudoku.solver.processing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VerifiedDigitEntryTest {
    @Test fun acceptedEntryDoesNotRepeatDigit() = runBlocking {
        var retries = 0
        assertEquals(EntryConfirmation.CONFIRMED, VerifiedDigitEntry.confirm(
            read = { CellEntryState.FILLED }, retry = { retries++; true }, settle = {}))
        assertEquals(0, retries)
    }

    @Test fun delayedRenderWaitsBeforeRepeatingTap() = runBlocking {
        val states = ArrayDeque(listOf(CellEntryState.EMPTY, CellEntryState.EMPTY, CellEntryState.FILLED))
        var retries = 0
        assertEquals(EntryConfirmation.CONFIRMED, VerifiedDigitEntry.confirm(
            read = { states.removeFirst() }, retry = { retries++; true }, settle = {}))
        assertEquals(0, retries)
    }

    @Test fun silentlyDroppedDigitIsRetriedAndConfirmedBeforeAdvancing() = runBlocking {
        var filled = false
        var retries = 0
        assertEquals(EntryConfirmation.CONFIRMED, VerifiedDigitEntry.confirm(
            read = { if (filled) CellEntryState.FILLED else CellEntryState.EMPTY },
            retry = { retries++; filled = true; true }, settle = {}))
        assertEquals(1, retries)
    }

    @Test fun repeatedDroppedOrCancelledTapNeverCountsAsConfirmed() = runBlocking {
        assertEquals(EntryConfirmation.UNCONFIRMED, VerifiedDigitEntry.confirm(
            read = { CellEntryState.EMPTY }, retry = { true }, settle = {}))
        assertEquals(EntryConfirmation.UNCONFIRMED, VerifiedDigitEntry.confirm(
            read = { CellEntryState.EMPTY }, retry = { false }, settle = {}))
    }

    @Test fun wrongDigitOrMissingCaptureStopsWithoutBlindRetry() = runBlocking {
        assertEquals(EntryConfirmation.WRONG, VerifiedDigitEntry.confirm(
            read = { CellEntryState.WRONG }, retry = { fail("must not retry wrong entry"); false }, settle = {}))
        assertEquals(EntryConfirmation.UNCONFIRMED, VerifiedDigitEntry.confirm(
            read = { null }, retry = { fail("must not retry unobserved entry"); false }, settle = {}))
    }

    @Test(expected = GameAutoCompletionException::class)
    fun gameCompletionInterruptsEvenAQueuedEntryRetry() = runBlocking<Unit> {
        val gate = GameCompletionGate()
        VerifiedDigitEntry.confirm(read = { CellEntryState.EMPTY },
            settle = { gate.notice(GameInputPolicy.GAME_PACKAGE, listOf("Autocompletando")) },
            retry = { gate.ensureInputAllowed(); fail("retry after completion"); false })
    }
}
