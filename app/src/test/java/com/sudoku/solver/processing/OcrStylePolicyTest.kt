package com.sudoku.solver.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrStylePolicyTest {
    @Test
    fun `colored entries are excluded after most of a sparse puzzle is filled`() {
        assertTrue(
            OcrStylePolicy.shouldTreatStyledDigitsAsUserEntries(
                digitCells = 57,
                styledDigitCount = 40
            )
        )
    }

    @Test
    fun `an entirely colored theme is not mistaken for user input`() {
        assertFalse(
            OcrStylePolicy.shouldTreatStyledDigitsAsUserEntries(
                digitCells = 28,
                styledDigitCount = 28
            )
        )
    }

    @Test
    fun `a board without colored digits keeps all givens`() {
        assertFalse(
            OcrStylePolicy.shouldTreatStyledDigitsAsUserEntries(
                digitCells = 17,
                styledDigitCount = 0
            )
        )
    }

    @Test
    fun `too few neutral digits does not activate style filtering`() {
        assertFalse(
            OcrStylePolicy.shouldTreatStyledDigitsAsUserEntries(
                digitCells = 17,
                styledDigitCount = 12
            )
        )
    }
}
