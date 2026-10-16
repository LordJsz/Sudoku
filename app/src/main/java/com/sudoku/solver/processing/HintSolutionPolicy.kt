package com.sudoku.solver.processing

import com.sudoku.solver.model.SudokuGrid

/** Pure helpers for anchoring a new solution to a value revealed by PISTA. */
internal object HintSolutionPolicy {
    fun withRevealedValue(
        grid: SudokuGrid,
        row: Int,
        col: Int,
        revealedValue: Int
    ): SudokuGrid? {
        if (row !in 0..8 || col !in 0..8 || revealedValue !in 1..9) return null

        val existingValue = grid.values[row][col]
        if (existingValue != 0 && existingValue != revealedValue) return null

        val values = grid.values.map { it.clone() }.toTypedArray()
        val locked = grid.locked.map { it.clone() }.toTypedArray()
        values[row][col] = revealedValue
        locked[row][col] = true
        return SudokuGrid(values, locked)
    }

    fun solutionHonorsReveal(
        solution: Array<IntArray>?,
        row: Int,
        col: Int,
        revealedValue: Int
    ): Boolean {
        return solution != null &&
            row in 0..8 &&
            col in 0..8 &&
            solution.size == 9 &&
            solution.all { it.size == 9 } &&
            solution[row][col] == revealedValue
    }
}
