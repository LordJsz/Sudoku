package com.sudoku.solver.model

/**
 * Represents a 9x9 Sudoku grid
 * @param values The 9x9 array of cell values (0 = empty, 1-9 = digit)
 * @param locked Boolean array indicating which cells were given (cannot be modified)
 */
data class SudokuGrid(
    val values: Array<IntArray>,
    val locked: Array<BooleanArray>
) {
    init {
        require(values.size == 9) { "Grid must be 9x9" }
        require(values.all { it.size == 9 }) { "Grid must be 9x9" }
        require(locked.size == 9) { "Locked array must be 9x9" }
        require(locked.all { it.size == 9 }) { "Locked array must be 9x9" }
    }

    /**
     * Creates an empty 9x9 grid
     */
    constructor() : this(
        Array(9) { IntArray(9) },
        Array(9) { BooleanArray(9) }
    )

    /**
     * Get value at specific cell
     */
    operator fun get(row: Int, col: Int): Int {
        require(row in 0..8 && col in 0..8) { "Invalid cell coordinates" }
        return values[row][col]
    }

    /**
     * Set value at specific cell (if not locked)
     */
    operator fun set(row: Int, col: Int, value: Int) {
        require(row in 0..8 && col in 0..8) { "Invalid cell coordinates" }
        require(value in 0..9) { "Value must be 0-9" }
        require(!locked[row][col]) { "Cannot modify locked cell" }
        values[row][col] = value
    }

    /**
     * Check if cell is locked (given in original puzzle)
     */
    fun isLocked(row: Int, col: Int): Boolean {
        require(row in 0..8 && col in 0..8) { "Invalid cell coordinates" }
        return locked[row][col]
    }

    /**
     * Get count of filled cells
     */
    fun filledCount(): Int {
        return values.sumOf { row -> row.count { it != 0 } }
    }

    /**
     * Get count of empty cells
     */
    fun emptyCount(): Int {
        return 81 - filledCount()
    }

    /**
     * Clone the grid
     */
    fun copy(): SudokuGrid {
        return SudokuGrid(
            values.map { it.clone() }.toTypedArray(),
            locked.map { it.clone() }.toTypedArray()
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SudokuGrid) return false

        if (!values.contentDeepEquals(other.values)) return false
        if (!locked.contentDeepEquals(other.locked)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = values.contentDeepHashCode()
        result = 31 * result + locked.contentDeepHashCode()
        return result
    }

    override fun toString(): String {
        return buildString {
            appendLine("Sudoku Grid:")
            for (row in 0..8) {
                if (row % 3 == 0 && row != 0) appendLine("------+-------+------")
                for (col in 0..8) {
                    if (col % 3 == 0 && col != 0) append("| ")
                    append(if (values[row][col] == 0) "." else values[row][col])
                    append(" ")
                }
                appendLine()
            }
        }
    }
}
