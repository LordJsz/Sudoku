package com.sudoku.solver.processing

import com.sudoku.solver.model.SudokuGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SudokuSolverSafetyTest {
    @Test
    fun `live highlighted puzzle is unique when black givens are preserved`() {
        val values = arrayOf(
            intArrayOf(0, 2, 0, 6, 0, 9, 3, 0, 0),
            intArrayOf(5, 6, 0, 0, 0, 0, 0, 0, 0),
            intArrayOf(7, 4, 9, 8, 5, 0, 0, 0, 1),
            intArrayOf(2, 0, 0, 4, 3, 0, 7, 9, 8),
            intArrayOf(0, 0, 7, 0, 1, 2, 0, 6, 4),
            intArrayOf(4, 9, 6, 5, 7, 0, 0, 0, 3),
            intArrayOf(3, 8, 0, 0, 6, 1, 0, 0, 0),
            intArrayOf(9, 7, 5, 0, 8, 0, 0, 0, 6),
            intArrayOf(0, 0, 0, 3, 9, 0, 8, 0, 7)
        )
        val locked = Array(9) { row ->
            BooleanArray(9) { col -> values[row][col] != 0 }
        }

        val result = SudokuSolver().validateAndSolve(SudokuGrid(values, locked))

        assertEquals(ValidationStatus.SUCCESS, result.status)
        assertTrue(result.corrections.isEmpty())
        assertEquals(
            listOf(1, 2, 8, 6, 4, 9, 3, 7, 5),
            result.solution?.firstOrNull()?.toList()
        )
    }

    @Test
    fun `multiple solutions do not rewrite valid confused digits`() {
        // Captured from a live hard-puzzle run. The old recovery path changed
        // three valid 1 clues to 7 simply to manufacture a unique solution.
        val values = arrayOf(
            intArrayOf(8, 0, 2, 0, 1, 3, 9, 0, 5),
            intArrayOf(0, 0, 9, 2, 0, 4, 0, 1, 0),
            intArrayOf(1, 6, 0, 0, 0, 0, 0, 0, 0),
            intArrayOf(0, 0, 6, 1, 0, 0, 5, 0, 9),
            intArrayOf(0, 0, 0, 8, 4, 9, 1, 0, 6),
            intArrayOf(0, 8, 1, 0, 5, 0, 4, 0, 0),
            intArrayOf(0, 9, 0, 0, 0, 0, 8, 5, 3),
            intArrayOf(5, 2, 0, 3, 7, 0, 6, 0, 0),
            intArrayOf(0, 0, 0, 5, 0, 0, 0, 0, 0)
        )
        val locked = Array(9) { row ->
            BooleanArray(9) { col -> values[row][col] != 0 }
        }

        val result = SudokuSolver().validateAndSolve(SudokuGrid(values, locked))

        assertEquals(ValidationStatus.MULTIPLE_SOLUTIONS, result.status)
        assertTrue(result.corrections.isEmpty())
        assertEquals(1, values[1][7])
        assertEquals(1, values[2][0])
        assertEquals(1, values[4][6])
    }
}
