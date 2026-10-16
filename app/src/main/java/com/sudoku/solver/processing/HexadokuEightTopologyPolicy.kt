package com.sudoku.solver.processing

/** Conservative shape gate that separates the target board's 8 from B. */
internal object HexadokuEightTopologyPolicy {
    private const val MAX_LEFT_STEM_RUN_RATIO = 0.70

    fun matches(enclosedHoleCount: Int, leftStemRunRatio: Double): Boolean =
        enclosedHoleCount == 2 && leftStemRunRatio <= MAX_LEFT_STEM_RUN_RATIO
}
