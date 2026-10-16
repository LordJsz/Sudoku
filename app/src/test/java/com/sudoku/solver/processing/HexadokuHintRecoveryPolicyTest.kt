package com.sudoku.solver.processing

import com.sudoku.solver.model.HexadokuGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexadokuHintRecoveryPolicyTest {
    @Test
    fun `selects the most constrained empty cell`() {
        val solved = generatedSolution()
        val values = solved.map(IntArray::clone).toTypedArray()
        values[0][0] = 0
        values[0][1] = 0
        values[1][1] = 0
        val grid = HexadokuGrid(
            values,
            Array(HexadokuGrid.SIZE) { row ->
                BooleanArray(HexadokuGrid.SIZE) { col -> values[row][col] != 0 }
            }
        )

        assertEquals(Pair(0, 0), HexadokuHintRecoveryPolicy.selectCell(grid))
    }

    @Test
    fun `does not select a cell already used for recovery`() {
        val values = generatedSolution()
        values[0][0] = 0
        values[0][1] = 0
        val grid = HexadokuGrid(
            values,
            Array(HexadokuGrid.SIZE) { row ->
                BooleanArray(HexadokuGrid.SIZE) { col -> values[row][col] != 0 }
            }
        )

        assertEquals(
            Pair(0, 1),
            HexadokuHintRecoveryPolicy.selectCell(grid, setOf(Pair(0, 0)))
        )
    }

    @Test
    fun `does not select an occupied cell whose symbol was unreadable`() {
        val values = generatedSolution()
        values[0][0] = 0
        values[0][1] = 0
        val locked = Array(HexadokuGrid.SIZE) { row ->
            BooleanArray(HexadokuGrid.SIZE) { col -> values[row][col] != 0 }
        }
        locked[0][0] = true
        val grid = HexadokuGrid(values, locked)

        assertEquals(Pair(0, 1), HexadokuHintRecoveryPolicy.selectCell(grid))
    }

    @Test
    fun `returns null for a filled board`() {
        val values = generatedSolution()
        val grid = HexadokuGrid(
            values,
            Array(HexadokuGrid.SIZE) { BooleanArray(HexadokuGrid.SIZE) { true } }
        )

        assertNull(HexadokuHintRecoveryPolicy.selectCell(grid))
    }

    private fun generatedSolution(): Array<IntArray> =
        Array(HexadokuGrid.SIZE) { row ->
            IntArray(HexadokuGrid.SIZE) { col ->
                ((row * HexadokuGrid.BOX_SIZE + row / HexadokuGrid.BOX_SIZE + col) %
                    HexadokuGrid.SIZE) + 1
            }
        }
}
