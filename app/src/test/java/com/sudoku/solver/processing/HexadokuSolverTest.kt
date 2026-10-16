package com.sudoku.solver.processing

import com.sudoku.solver.model.HexadokuGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HexadokuSolverTest {
    @Test
    fun `solves a valid 16x16 puzzle with 4x4 boxes`() {
        val solved = generatedSolution()
        val puzzle = solved.map(IntArray::clone).toTypedArray()
        for (row in 0 until HexadokuGrid.SIZE) {
            puzzle[row][(row * 5) % HexadokuGrid.SIZE] = 0
        }
        val locked = Array(HexadokuGrid.SIZE) { row ->
            BooleanArray(HexadokuGrid.SIZE) { col -> puzzle[row][col] != 0 }
        }

        val result = HexadokuSolver().solve(HexadokuGrid(puzzle, locked))

        assertEquals(HexadokuSolveStatus.SUCCESS, result.status)
        assertNotNull(result.solution)
        assertTrue(solved.contentDeepEquals(result.solution))
    }

    @Test
    fun `rejects duplicate symbols without changing the 9x9 solver`() {
        val values = generatedSolution()
        values[0][1] = values[0][0]
        val locked = Array(HexadokuGrid.SIZE) { BooleanArray(HexadokuGrid.SIZE) { true } }

        val result = HexadokuSolver().solve(HexadokuGrid(values, locked))

        assertEquals(HexadokuSolveStatus.INVALID, result.status)
    }

    @Test
    fun `excludes a symbol rejected by the game`() {
        val solved = generatedSolution()
        val puzzle = solved.map(IntArray::clone).toTypedArray()
        puzzle[0][0] = 0
        val locked = Array(HexadokuGrid.SIZE) { row ->
            BooleanArray(HexadokuGrid.SIZE) { col -> puzzle[row][col] != 0 }
        }

        val result = HexadokuSolver().solve(
            HexadokuGrid(puzzle, locked),
            forbiddenValues = mapOf((0 to 0) to setOf(solved[0][0]))
        )

        assertEquals(HexadokuSolveStatus.UNSOLVABLE, result.status)
    }

    @Test
    fun `maps the configured 1-9 A-G symbols`() {
        assertEquals(1, HexadokuGrid.valueFor('1'))
        assertEquals(9, HexadokuGrid.valueFor('9'))
        assertEquals(10, HexadokuGrid.valueFor('A'))
        assertEquals(16, HexadokuGrid.valueFor('g'))
        assertEquals("G", HexadokuGrid.symbolFor(16))
    }

    private fun generatedSolution(): Array<IntArray> =
        Array(HexadokuGrid.SIZE) { row ->
            IntArray(HexadokuGrid.SIZE) { col ->
                ((row * HexadokuGrid.BOX_SIZE + row / HexadokuGrid.BOX_SIZE + col) %
                    HexadokuGrid.SIZE) + 1
            }
        }
}
