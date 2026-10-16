package com.sudoku.solver.processing

/** Conservative shape gate for the target 16x16 board's open-top 4 glyph. */
internal object HexadokuFourTopologyPolicy {
    private const val MIN_HOLE_CENTER_Y_RATIO = 0.42
    private const val MAX_HOLE_CENTER_Y_RATIO = 0.58
    private const val MIN_GLYPH_WIDTH_TO_HEIGHT_RATIO = 0.60
    private const val MAX_GLYPH_WIDTH_TO_HEIGHT_RATIO = 0.80
    private const val MIN_WIDEST_ROW_Y_RATIO = 0.56
    private const val MAX_WIDEST_ROW_Y_RATIO = 0.82
    private const val MIN_RIGHT_STEM_RUN_RATIO = 0.86

    fun matches(
        enclosedHoleCount: Int,
        holeCenterYRatio: Double,
        glyphWidthToHeightRatio: Double,
        widestInkRowYRatio: Double,
        rightStemRunRatio: Double
    ): Boolean {
        if (enclosedHoleCount != 1) return false
        if (holeCenterYRatio !in MIN_HOLE_CENTER_Y_RATIO..MAX_HOLE_CENTER_Y_RATIO) return false
        if (glyphWidthToHeightRatio !in MIN_GLYPH_WIDTH_TO_HEIGHT_RATIO..MAX_GLYPH_WIDTH_TO_HEIGHT_RATIO) return false
        if (widestInkRowYRatio !in MIN_WIDEST_ROW_Y_RATIO..MAX_WIDEST_ROW_Y_RATIO) return false
        return rightStemRunRatio >= MIN_RIGHT_STEM_RUN_RATIO
    }
}
