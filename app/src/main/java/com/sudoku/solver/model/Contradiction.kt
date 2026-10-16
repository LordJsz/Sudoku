package com.sudoku.solver.model

/**
 * Represents a contradiction found in the Sudoku puzzle
 * (duplicate values in a row, column, or box)
 * @param type Type of constraint violated ("row", "column", or "box")
 * @param index Index of the row/column/box (0-8)
 * @param value The duplicate value (1-9)
 * @param positions List of cell positions with the duplicate value
 */
data class Contradiction(
    val type: String,
    val index: Int,
    val value: Int,
    val positions: List<Pair<Int, Int>>
)
