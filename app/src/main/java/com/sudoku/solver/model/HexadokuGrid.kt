package com.sudoku.solver.model

/**
 * A 16x16 Sudoku (Hexadoku) grid.
 *
 * Values use 0 for an empty cell and 1..16 for the visible symbols
 * 1..9 and A..G respectively.
 */
data class HexadokuGrid(
    val values: Array<IntArray>,
    val locked: Array<BooleanArray>
) {
    init {
        require(values.size == SIZE && values.all { it.size == SIZE }) {
            "Grid must be 16x16"
        }
        require(locked.size == SIZE && locked.all { it.size == SIZE }) {
            "Locked array must be 16x16"
        }
        require(values.all { row -> row.all { it in 0..SIZE } }) {
            "Cell values must be in 0..16"
        }
    }

    fun filledCount(): Int = values.sumOf { row -> row.count { it != 0 } }

    fun copy(): HexadokuGrid = HexadokuGrid(
        values.map(IntArray::clone).toTypedArray(),
        locked.map(BooleanArray::clone).toTypedArray()
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is HexadokuGrid &&
                values.contentDeepEquals(other.values) &&
                locked.contentDeepEquals(other.locked))

    override fun hashCode(): Int = 31 * values.contentDeepHashCode() + locked.contentDeepHashCode()

    override fun toString(): String = buildString {
        appendLine("16x16 Sudoku Grid:")
        for (row in 0 until SIZE) {
            if (row > 0 && row % BOX_SIZE == 0) {
                appendLine("---------+---------+---------+---------")
            }
            for (col in 0 until SIZE) {
                if (col > 0 && col % BOX_SIZE == 0) append("| ")
                append(symbolFor(values[row][col])).append(' ')
            }
            appendLine()
        }
    }

    companion object {
        const val SIZE = 16
        const val BOX_SIZE = 4
        const val SYMBOLS = "123456789ABCDEFG"

        fun empty(): HexadokuGrid = HexadokuGrid(
            Array(SIZE) { IntArray(SIZE) },
            Array(SIZE) { BooleanArray(SIZE) }
        )

        fun symbolFor(value: Int): String = when (value) {
            0 -> "."
            in 1..9 -> value.toString()
            in 10..16 -> ('A'.code + value - 10).toChar().toString()
            else -> "?"
        }

        fun valueFor(symbol: Char): Int = when (val normalized = symbol.uppercaseChar()) {
            in '1'..'9' -> normalized.digitToInt()
            in 'A'..'G' -> normalized.code - 'A'.code + 10
            else -> 0
        }
    }
}
