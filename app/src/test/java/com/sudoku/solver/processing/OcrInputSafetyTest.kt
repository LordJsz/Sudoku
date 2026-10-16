package com.sudoku.solver.processing

import com.sudoku.solver.model.OcrResult
import com.sudoku.solver.model.SudokuGrid
import org.junit.Assert.*
import org.junit.Test

class OcrInputSafetyTest {
    private val rows = listOf("073000002", "869500470", "000007500", "000750800",
        "026814000", "700236000", "297680004", "380002709", "010073080")
    private fun scan(): OcrResult {
        val values = rows.map { row -> row.map { it.digitToInt() }.toIntArray() }.toTypedArray()
        return OcrResult(SudokuGrid(values, Array(9) { r -> BooleanArray(9) { c -> values[r][c] != 0 } }))
    }
    @Test fun acceptsUniqueSolutionPreservingPhoneClues() {
        val scan = scan()
        assertTrue(OcrInputSafety.canInput(scan, SudokuSolver().validateAndSolve(scan.grid)))
    }
    @Test fun rejectsClueRepairEvenWhenSolverReportsSuccess() {
        val scan = scan()
        val result = SudokuSolver().validateAndSolve(scan.grid).copy(
            corrections = listOf(CellCorrection(1, 3, 5, 0)))
        assertFalse(OcrInputSafety.canInput(scan, result))
    }
    @Test fun rejectsChangedClueEvenWithoutCorrectionMetadata() {
        val scan = scan()
        val result = SudokuSolver().validateAndSolve(scan.grid)
        result.solution!![1][3] = 9
        assertFalse(OcrInputSafety.canInput(scan, result))
    }
    @Test fun rejectsUnprovenUniqueness() {
        val scan = scan()
        assertFalse(OcrInputSafety.canInput(scan,
            SudokuSolver().validateAndSolve(scan.grid).copy(solutionIsTimeout = true)))
    }
    @Test fun rejectsUnreadOccupiedCell() {
        val scan = scan().copy(occupiedCells = setOf(0 to 1))
        assertFalse(OcrInputSafety.canInput(scan, SudokuSolver().validateAndSolve(scan.grid)))
    }
    @Test fun explainsUnreadCellsSeparatelyFromSolverTimeouts() {
        val scan = scan().copy(occupiedCells = setOf(3 to 2, 6 to 7))
        val solved = SudokuSolver().validateAndSolve(scan.grid)
        assertEquals("Could not read r4c3, r7c8. Scan again.", OcrInputSafety.rejectionReason(scan, solved))
        assertTrue(OcrInputSafety.rejectionReason(scan, solved.copy(solutionIsTimeout = true))!!
            .contains("time limit"))
    }
}
