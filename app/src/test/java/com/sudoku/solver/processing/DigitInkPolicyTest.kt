package com.sudoku.solver.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitInkPolicyTest {
    @Test
    fun `thin horizontal grid line is not treated as a digit`() {
        assertFalse(
            DigitInkPolicy.isDigitLike(
                inkPixels = 66,
                sampleCount = 1122,
                activeRows = 2,
                sampledRows = 34
            )
        )
    }

    @Test
    fun `digit with enough ink and vertical spread is accepted`() {
        assertTrue(
            DigitInkPolicy.isDigitLike(
                inkPixels = 80,
                sampleCount = 1122,
                activeRows = 20,
                sampledRows = 34
            )
        )
    }

    @Test
    fun `sparse noise is not treated as a digit`() {
        assertFalse(
            DigitInkPolicy.isDigitLike(
                inkPixels = 10,
                sampleCount = 1122,
                activeRows = 8,
                sampledRows = 34
            )
        )
    }
}
