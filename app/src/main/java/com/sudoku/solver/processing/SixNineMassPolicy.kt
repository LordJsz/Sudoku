package com.sudoku.solver.processing

/** Pure fallback for distinguishing 6 from 9 by vertical ink distribution. */
internal object SixNineMassPolicy {
    private const val MASS_THRESHOLD = 0.02

    fun classify(topInkRatio: Double): Int {
        return when {
            topInkRatio > 0.5 + MASS_THRESHOLD -> 9
            topInkRatio < 0.5 - MASS_THRESHOLD -> 6
            else -> 0
        }
    }
}
