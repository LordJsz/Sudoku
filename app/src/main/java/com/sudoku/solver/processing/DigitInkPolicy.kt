package com.sudoku.solver.processing

/**
 * Rejects grid borders and other thin horizontal artifacts while retaining digit glyphs.
 */
internal object DigitInkPolicy {
    private const val MIN_INK_PIXELS = 16
    private const val MIN_INK_RATIO = 0.030f
    private const val MIN_ACTIVE_ROW_RATIO = 0.18f

    fun isDigitLike(
        inkPixels: Int,
        sampleCount: Int,
        activeRows: Int,
        sampledRows: Int
    ): Boolean {
        if (sampleCount <= 0 || sampledRows <= 0) return false
        val inkRatio = inkPixels.toFloat() / sampleCount
        val activeRowRatio = activeRows.toFloat() / sampledRows
        return inkPixels >= MIN_INK_PIXELS &&
            inkRatio >= MIN_INK_RATIO &&
            activeRowRatio >= MIN_ACTIVE_ROW_RATIO
    }
}
