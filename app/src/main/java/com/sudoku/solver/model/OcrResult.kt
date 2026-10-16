package com.sudoku.solver.model

/**
 * Result of OCR scanning
 */
data class OcrResult(
    val grid: SudokuGrid,
    val uncertainCells: List<Pair<Int, Int>> = emptyList(),  // Cells that may have OCR errors (6/9, 1/7)
    // Cells where the board image visibly contains a digit, including styled user entries
    // that are intentionally excluded from grid.
    val occupiedCells: Set<Pair<Int, Int>> = emptySet(),
    // Best recognized value before style filtering. This lets a user-confirmed key cell
    // survive the next OCR pass without treating every editable entry as a puzzle given.
    val observedDigits: Map<Pair<Int, Int>, Int> = emptyMap()
)
