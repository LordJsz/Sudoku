package com.sudoku.solver.processing

import com.sudoku.solver.model.SudokuGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HintSolutionPolicyTest {
    @Test
    fun `revealed value becomes a locked clue without mutating original grid`() {
        val original = SudokuGrid()

        val updated = requireNotNull(
            HintSolutionPolicy.withRevealedValue(original, row = 3, col = 5, revealedValue = 7)
        )

        assertNotSame(original, updated)
        assertEquals(0, original.values[3][5])
        assertFalse(original.locked[3][5])
        assertEquals(7, updated.values[3][5])
        assertTrue(updated.locked[3][5])
    }

    @Test
    fun `conflicting existing clue is not overwritten by a hint scan`() {
        val values = Array(9) { IntArray(9) }
        val locked = Array(9) { BooleanArray(9) }
        values[2][4] = 6
        locked[2][4] = true

        assertNull(
            HintSolutionPolicy.withRevealedValue(
                SudokuGrid(values, locked),
                row = 2,
                col = 4,
                revealedValue = 9
            )
        )
    }

    @Test
    fun `updated solution must honor the revealed value`() {
        val solution = Array(9) { IntArray(9) { 1 } }
        solution[6][2] = 8

        assertTrue(HintSolutionPolicy.solutionHonorsReveal(solution, 6, 2, 8))
        assertFalse(HintSolutionPolicy.solutionHonorsReveal(solution, 6, 2, 3))
        assertFalse(HintSolutionPolicy.solutionHonorsReveal(null, 6, 2, 8))
    }
}
