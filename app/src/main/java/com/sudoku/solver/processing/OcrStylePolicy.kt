package com.sudoku.solver.processing

/**
 * Decides when colored digits are player-entered values rather than puzzle givens.
 *
 * A normal colored theme generally colors every digit, leaving no neutral givens.
 * Sudoku apps that distinguish givens from player input keep a plausible set of
 * neutral clues and color the editable values. The latter remains true even when
 * most of the board has already been filled.
 */
internal object OcrStylePolicy {
    private const val MIN_NEUTRAL_GIVENS = 10

    fun shouldTreatStyledDigitsAsUserEntries(
        digitCells: Int,
        styledDigitCount: Int
    ): Boolean {
        if (digitCells <= 0 || styledDigitCount <= 0 || styledDigitCount > digitCells) {
            return false
        }

        val neutralDigitCount = digitCells - styledDigitCount
        return neutralDigitCount >= MIN_NEUTRAL_GIVENS
    }
}
