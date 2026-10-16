package com.sudoku.solver.processing

/** Stable coordinate fallback for the target game's two-page 16x16 keypad. */
internal object HexadokuKeypadGeometry {
    private const val DIGIT_BUTTON_COUNT = 9
    private const val LETTER_BUTTON_COUNT = 7
    private const val KEYPAD_VERTICAL_FRACTION = 0.55f

    fun fallbackTapPoint(
        value: Int,
        screenWidth: Int,
        screenHeight: Int,
        boardBottom: Int,
        visibleKeypadCenterY: Int? = null,
        keypadLeft: Int = 0,
        keypadRight: Int = screenWidth
    ): Pair<Int, Int>? {
        if (value !in 1..16 || screenWidth <= 0 || screenHeight <= 0) return null

        val buttonCount = if (value <= 9) DIGIT_BUTTON_COUNT else LETTER_BUTTON_COUNT
        val buttonIndex = if (value <= 9) value - 1 else value - 10
        val left = keypadLeft.coerceIn(0, screenWidth - 1)
        val right = keypadRight.coerceIn(left + 1, screenWidth)
        val keypadWidth = (right - left).coerceAtLeast(1)
        val x = (left + (((buttonIndex + 0.5f) * keypadWidth) / buttonCount).toInt())
            .coerceIn(left, right - 1)
        val fallbackY = boardBottom +
            ((screenHeight - boardBottom).coerceAtLeast(1) * KEYPAD_VERTICAL_FRACTION).toInt()
        val y = (visibleKeypadCenterY ?: fallbackY).coerceIn(0, screenHeight - 1)
        return Pair(x, y)
    }
}
