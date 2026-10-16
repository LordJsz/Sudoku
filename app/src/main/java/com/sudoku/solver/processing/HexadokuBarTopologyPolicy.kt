package com.sudoku.solver.processing

/** Conservative shape gate for the target 16x16 board's E and F glyphs. */
internal object HexadokuBarTopologyPolicy {
    enum class Symbol {
        E,
        F
    }

    private const val MIN_LEFT_STEM_RUN_RATIO = 0.90
    private const val MIN_TOP_BAR_DENSITY = 0.35
    private const val MIN_MIDDLE_BAR_DENSITY = 0.15
    private const val MIN_E_BOTTOM_BAR_DENSITY = 0.20
    private const val MAX_F_BOTTOM_BAR_DENSITY = 0.08

    fun classify(
        enclosedHoleCount: Int,
        leftStemRunRatio: Double,
        topBarDensity: Double,
        middleBarDensity: Double,
        bottomBarDensity: Double
    ): Symbol? {
        if (enclosedHoleCount != 0) return null
        if (leftStemRunRatio < MIN_LEFT_STEM_RUN_RATIO) return null
        if (topBarDensity < MIN_TOP_BAR_DENSITY) return null
        if (middleBarDensity < MIN_MIDDLE_BAR_DENSITY) return null

        return when {
            bottomBarDensity >= MIN_E_BOTTOM_BAR_DENSITY -> Symbol.E
            bottomBarDensity <= MAX_F_BOTTOM_BAR_DENSITY -> Symbol.F
            else -> null
        }
    }
}
