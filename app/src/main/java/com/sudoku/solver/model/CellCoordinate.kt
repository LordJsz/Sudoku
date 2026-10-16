package com.sudoku.solver.model

/**
 * Maps a Sudoku grid cell to screen coordinates
 * @param row Grid row (0-8)
 * @param col Grid column (0-8)
 * @param screenX Screen X coordinate (center of cell)
 * @param screenY Screen Y coordinate (center of cell)
 */
data class CellCoordinate(
    val row: Int,
    val col: Int,
    val screenX: Int,
    val screenY: Int
) {
    init {
        require(row in 0..8) { "Row must be 0-8" }
        require(col in 0..8) { "Column must be 0-8" }
        require(screenX >= 0) { "screenX must be non-negative" }
        require(screenY >= 0) { "screenY must be non-negative" }
    }

    /**
     * Get the box number (0-8) for this cell
     * Boxes are numbered left-to-right, top-to-bottom
     */
    val boxNumber: Int
        get() = (row / 3) * 3 + (col / 3)

    /**
     * Get the index within the box (0-8)
     */
    val indexInBox: Int
        get() = (row % 3) * 3 + (col % 3)
}
