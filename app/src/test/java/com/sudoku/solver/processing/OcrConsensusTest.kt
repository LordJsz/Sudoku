package com.sudoku.solver.processing

import com.sudoku.solver.model.OcrResult
import com.sudoku.solver.model.SudokuGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrConsensusTest {
    @Test
    fun `majority digit wins over one conflicting pass`() {
        val primary = resultWith(0, 0, 6)
        val combined = OcrConsensus.combine(
            primary,
            listOf(primary, resultWith(0, 0, 9), resultWith(0, 0, 9))
        )

        assertEquals(9, combined.grid.values[0][0])
        assertTrue(Pair(0, 0) in combined.uncertainCells)
    }

    @Test
    fun `majority empty rejects a one-pass hallucinated clue`() {
        val primary = resultWith(2, 4, 7)
        val combined = OcrConsensus.combine(
            primary,
            listOf(primary, emptyResult(), emptyResult())
        )

        assertEquals(0, combined.grid.values[2][4])
        assertFalse(combined.grid.locked[2][4])
    }

    @Test
    fun `tie keeps primary and marks cell uncertain`() {
        val primary = resultWith(3, 3, 1)
        val combined = OcrConsensus.combine(primary, listOf(primary, resultWith(3, 3, 7)))

        assertEquals(1, combined.grid.values[3][3])
        assertTrue(Pair(3, 3) in combined.uncertainCells)
    }

    @Test
    fun `unanimous clean clue stays certain`() {
        val primary = resultWith(8, 8, 4)
        val combined = OcrConsensus.combine(
            primary,
            listOf(primary, resultWith(8, 8, 4), resultWith(8, 8, 4))
        )

        assertEquals(4, combined.grid.values[8][8])
        assertFalse(Pair(8, 8) in combined.uncertainCells)
    }

    @Test
    fun `consensus preserves visual occupancy and pre-filter digit`() {
        val cell = Pair(5, 6)
        val styledEntry = OcrResult(
            grid = SudokuGrid(),
            occupiedCells = setOf(cell),
            observedDigits = mapOf(cell to 4)
        )

        val combined = OcrConsensus.combine(styledEntry, listOf(styledEntry, emptyResult()))

        assertTrue(cell in combined.occupiedCells)
        assertEquals(4, combined.observedDigits[cell])
    }

    private fun emptyResult(): OcrResult = OcrResult(SudokuGrid())

    private fun resultWith(row: Int, col: Int, value: Int): OcrResult {
        val grid = SudokuGrid()
        grid.values[row][col] = value
        grid.locked[row][col] = value != 0
        return OcrResult(grid)
    }
}
