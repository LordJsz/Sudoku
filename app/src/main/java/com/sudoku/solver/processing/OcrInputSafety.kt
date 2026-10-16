package com.sudoku.solver.processing

import com.sudoku.solver.model.OcrResult

/** Final check against the original scan, after any solver recovery or clue cleanup. */
internal object OcrInputSafety {
    fun canInput(scan: OcrResult, result: ValidationResult): Boolean {
        return rejectionReason(scan, result) == null
    }

    /** Share the actual blocking condition with the UI and durable scan report. */
    fun rejectionReason(scan: OcrResult, result: ValidationResult): String? {
        val solution = result.solution ?: return "No confirmed solution is available."
        if (result.status != ValidationStatus.SUCCESS) return result.message
        if (result.solutionIsTimeout) return "Solution uniqueness could not be confirmed before the time limit."
        if (result.corrections.isNotEmpty()) return "OCR needed to change scanned clues. Scan again."
        val unread = scan.occupiedCells.filter { scan.observedDigits[it] !in 1..9 }
        if (unread.isNotEmpty()) return "Could not read ${formatCells(unread)}. Scan again."
        val changed = (0..8).flatMap { row ->
            (0..8).mapNotNull { col ->
                val clue = scan.grid.values[row][col]
                if (clue != 0 && solution[row][col] != clue) row to col else null
            }
        }
        if (changed.isNotEmpty()) return "Solution disagrees with scanned clues at ${formatCells(changed)}. Scan again."
        return null
    }

    private fun formatCells(cells: List<Pair<Int, Int>>): String =
        cells.take(3).joinToString(", ") { (row, col) -> "r${row + 1}c${col + 1}" } +
            if (cells.size > 3) " and ${cells.size - 3} more cells" else ""
}
