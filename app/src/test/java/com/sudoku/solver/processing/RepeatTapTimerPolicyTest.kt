package com.sudoku.solver.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepeatTapTimerPolicyTest {
    @Test
    fun `whole seconds are converted to milliseconds`() {
        assertEquals(5_000L, RepeatTapTimerPolicy.parseIntervalMillis("5"))
        assertEquals(60_000L, RepeatTapTimerPolicy.parseIntervalMillis(" 60 "))
    }

    @Test
    fun `invalid and unsafe intervals are rejected`() {
        assertNull(RepeatTapTimerPolicy.parseIntervalMillis(null))
        assertNull(RepeatTapTimerPolicy.parseIntervalMillis(""))
        assertNull(RepeatTapTimerPolicy.parseIntervalMillis("0"))
        assertNull(RepeatTapTimerPolicy.parseIntervalMillis("1.5"))
        assertNull(RepeatTapTimerPolicy.parseIntervalMillis("86401"))
    }

    @Test
    fun `overdue deadlines skip missed intervals without a tap burst`() {
        assertEquals(
            2_000L,
            RepeatTapTimerPolicy.nextDeadline(
                previousDeadlineMs = 1_000L,
                nowMs = 1_500L,
                intervalMs = 1_000L
            )
        )
        assertEquals(
            5_000L,
            RepeatTapTimerPolicy.nextDeadline(
                previousDeadlineMs = 1_000L,
                nowMs = 4_250L,
                intervalMs = 1_000L
            )
        )
    }
}
