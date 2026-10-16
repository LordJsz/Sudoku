package com.sudoku.solver.processing

import com.sudoku.solver.model.OcrResult
import com.sudoku.solver.model.SudokuGrid

/** Pure policy helpers for safe, incremental key-cell guidance. */
internal object KeyCellGuidancePolicy {
    fun selectNextEmptyCell(
        recommendations: List<KeyCellRecommendation>,
        occupiedCells: Set<Pair<Int, Int>>
    ): List<KeyCellRecommendation> {
        return recommendations
            .distinctBy { Pair(it.row, it.col) }
            .filterNot { Pair(it.row, it.col) in occupiedCells }
            .take(1)
    }

    fun applyConfirmedEntries(
        ocrResult: OcrResult,
        confirmedOptions: Map<Pair<Int, Int>, Set<Int>>
    ): OcrResult {
        if (confirmedOptions.isEmpty()) return ocrResult

        val values = ocrResult.grid.values.map { it.clone() }.toTypedArray()
        val locked = ocrResult.grid.locked.map { it.clone() }.toTypedArray()
        val uncertain = ocrResult.uncertainCells.toMutableSet()
        var changed = false

        for ((cell, allowedValues) in confirmedOptions) {
            val (row, col) = cell
            if (row !in 0..8 || col !in 0..8) continue

            val observed = ocrResult.observedDigits[cell]
                ?: values[row][col].takeIf { it in 1..9 }
                ?: continue
            if (observed !in allowedValues) continue

            values[row][col] = observed
            locked[row][col] = true
            uncertain.remove(cell)
            changed = true
        }

        if (!changed) return ocrResult
        return ocrResult.copy(
            grid = SudokuGrid(values, locked),
            uncertainCells = uncertain.toList()
        )
    }

    /**
     * Restores one visible value that OCR intentionally removed as a styled entry when
     * that value exactly matches one of the solver's two choices for an occupied key cell.
     * Blank cells falsely marked occupied are harmless because they have no observed value.
     */
    fun restoreFirstMatchingObservedKeyCell(
        ocrResult: OcrResult,
        recommendations: List<KeyCellRecommendation>
    ): ObservedKeyCellRecovery? {
        val recommendation = recommendations
            .distinctBy { Pair(it.row, it.col) }
            .firstOrNull { item ->
                val cell = Pair(item.row, item.col)
                val observed = ocrResult.observedDigits[cell]
                item.row in 0..8 &&
                    item.col in 0..8 &&
                    cell in ocrResult.occupiedCells &&
                    ocrResult.grid.values[item.row][item.col] == 0 &&
                    observed != null &&
                    (observed == item.optionA || observed == item.optionB)
            } ?: return null

        val cell = Pair(recommendation.row, recommendation.col)
        val observed = requireNotNull(ocrResult.observedDigits[cell])
        val values = ocrResult.grid.values.map { it.clone() }.toTypedArray()
        val locked = ocrResult.grid.locked.map { it.clone() }.toTypedArray()
        values[recommendation.row][recommendation.col] = observed
        locked[recommendation.row][recommendation.col] = true

        return ObservedKeyCellRecovery(
            ocrResult = ocrResult.copy(
                grid = SudokuGrid(values, locked),
                uncertainCells = ocrResult.uncertainCells.filterNot { it == cell }
            ),
            cell = cell,
            value = observed
        )
    }
}

internal data class ObservedKeyCellRecovery(
    val ocrResult: OcrResult,
    val cell: Pair<Int, Int>,
    val value: Int
)
