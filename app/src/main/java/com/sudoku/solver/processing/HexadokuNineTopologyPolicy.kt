package com.sudoku.solver.processing

/** Conservative shape gate for the target 16x16 board's otherwise unreadable 9 glyph. */
internal object HexadokuNineTopologyPolicy {
    private const val MAX_HOLE_CENTER_Y_RATIO = 0.40
    private const val MAX_LOWER_LEFT_TO_RIGHT_INK_RATIO = 0.42

    fun matches(
        enclosedHoleCount: Int,
        holeCenterYRatio: Double,
        lowerLeftInk: Int,
        lowerRightInk: Int
    ): Boolean {
        if (enclosedHoleCount != 1 || lowerRightInk <= 0) return false
        if (holeCenterYRatio > MAX_HOLE_CENTER_Y_RATIO) return false
        return lowerLeftInk.toDouble() / lowerRightInk <= MAX_LOWER_LEFT_TO_RIGHT_INK_RATIO
    }
}
