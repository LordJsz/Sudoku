package com.sudoku.solver.processing

import com.sudoku.solver.model.OcrResult
import com.sudoku.solver.model.SudokuGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyCellGuidancePolicyTest {
    @Test
    fun `guidance skips occupied recommendations and returns only one empty cell`() {
        val recommendations = listOf(
            KeyCellRecommendation(1, 2, 3, 8),
            KeyCellRecommendation(4, 5, 2, 7),
            KeyCellRecommendation(7, 8, 1, 9)
        )

        val selected = KeyCellGuidancePolicy.selectNextEmptyCell(
            recommendations = recommendations,
            occupiedCells = setOf(Pair(1, 2))
        )

        assertEquals(listOf(recommendations[1]), selected)
    }

    @Test
    fun `confirmed styled entry is restored from the observed digit`() {
        val cell = Pair(4, 5)
        val result = OcrResult(
            grid = SudokuGrid(),
            occupiedCells = setOf(cell),
            observedDigits = mapOf(cell to 7)
        )

        val restored = KeyCellGuidancePolicy.applyConfirmedEntries(
            ocrResult = result,
            confirmedOptions = mapOf(cell to setOf(2, 7))
        )

        assertEquals(7, restored.grid.values[4][5])
        assertTrue(restored.grid.locked[4][5])
        assertFalse(cell in restored.uncertainCells)
    }

    @Test
    fun `unexpected observed value is not accepted as a confirmed clue`() {
        val cell = Pair(4, 5)
        val result = OcrResult(
            grid = SudokuGrid(),
            occupiedCells = setOf(cell),
            observedDigits = mapOf(cell to 4)
        )

        val restored = KeyCellGuidancePolicy.applyConfirmedEntries(
            ocrResult = result,
            confirmedOptions = mapOf(cell to setOf(2, 7))
        )

        assertEquals(0, restored.grid.values[4][5])
        assertFalse(restored.grid.locked[4][5])
    }

    @Test
    fun `matching observed value resolves an occupied key cell`() {
        val cell = Pair(2, 6)
        val result = OcrResult(
            grid = SudokuGrid(),
            occupiedCells = setOf(cell),
            observedDigits = mapOf(cell to 6)
        )

        val recovery = KeyCellGuidancePolicy.restoreFirstMatchingObservedKeyCell(
            ocrResult = result,
            recommendations = listOf(KeyCellRecommendation(2, 6, 4, 6))
        )

        requireNotNull(recovery)
        assertEquals(cell, recovery.cell)
        assertEquals(6, recovery.value)
        assertEquals(6, recovery.ocrResult.grid.values[2][6])
        assertTrue(recovery.ocrResult.grid.locked[2][6])
    }

    @Test
    fun `observed value outside key cell options is rejected`() {
        val cell = Pair(2, 6)
        val result = OcrResult(
            grid = SudokuGrid(),
            occupiedCells = setOf(cell),
            observedDigits = mapOf(cell to 8)
        )

        val recovery = KeyCellGuidancePolicy.restoreFirstMatchingObservedKeyCell(
            ocrResult = result,
            recommendations = listOf(KeyCellRecommendation(2, 6, 4, 6))
        )

        assertEquals(null, recovery)
    }

    @Test
    fun `false occupied detection without observed digit is rejected`() {
        val cell = Pair(2, 6)
        val result = OcrResult(
            grid = SudokuGrid(),
            occupiedCells = setOf(cell)
        )

        val recovery = KeyCellGuidancePolicy.restoreFirstMatchingObservedKeyCell(
            ocrResult = result,
            recommendations = listOf(KeyCellRecommendation(2, 6, 4, 6))
        )

        assertEquals(null, recovery)
    }
}
