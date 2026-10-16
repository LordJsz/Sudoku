package com.sudoku.solver.processing

import com.sudoku.solver.model.HexadokuGrid

/** Pure cell selection used when a failed 16x16 attempt needs one PISTA clue. */
internal object HexadokuHintRecoveryPolicy {
    fun selectCell(
        grid: HexadokuGrid,
        excludedCells: Set<Pair<Int, Int>> = emptySet()
    ): Pair<Int, Int>? {
        var bestCell: Pair<Int, Int>? = null
        var bestCandidateCount = Int.MAX_VALUE

        for (row in 0 until HexadokuGrid.SIZE) {
            for (col in 0 until HexadokuGrid.SIZE) {
                val cell = Pair(row, col)
                if (
                    grid.values[row][col] != 0 ||
                    grid.locked[row][col] ||
                    cell in excludedCells
                ) continue

                val candidateCount = candidateCount(grid, row, col)
                if (candidateCount in 1 until bestCandidateCount) {
                    bestCell = cell
                    bestCandidateCount = candidateCount
                    if (candidateCount == 1) return bestCell
                } else if (bestCell == null) {
                    // Invalid OCR can leave an empty cell with no candidates. Keep
                    // a deterministic fallback so PISTA can still add evidence.
                    bestCell = cell
                }
            }
        }
        return bestCell
    }

    private fun candidateCount(grid: HexadokuGrid, row: Int, col: Int): Int {
        var used = 0
        for (index in 0 until HexadokuGrid.SIZE) {
            used = used or bitFor(grid.values[row][index])
            used = used or bitFor(grid.values[index][col])
        }

        val boxRow = (row / HexadokuGrid.BOX_SIZE) * HexadokuGrid.BOX_SIZE
        val boxCol = (col / HexadokuGrid.BOX_SIZE) * HexadokuGrid.BOX_SIZE
        for (r in boxRow until boxRow + HexadokuGrid.BOX_SIZE) {
            for (c in boxCol until boxCol + HexadokuGrid.BOX_SIZE) {
                used = used or bitFor(grid.values[r][c])
            }
        }

        return Integer.bitCount(FULL_MASK and used.inv())
    }

    private fun bitFor(value: Int): Int =
        if (value in 1..HexadokuGrid.SIZE) 1 shl (value - 1) else 0

    private const val FULL_MASK = (1 shl HexadokuGrid.SIZE) - 1
}
