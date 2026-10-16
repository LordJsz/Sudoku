package com.sudoku.solver.processing

import com.sudoku.solver.model.OcrResult
import com.sudoku.solver.model.SudokuGrid

/**
 * Combines several OCR passes cell-by-cell.
 *
 * A strict majority wins, including a majority that considers a cell empty. If
 * there is no majority, the strongest complete OCR candidate is retained and
 * the cell is flagged as uncertain so recovery can revisit it.
 */
object OcrConsensus {
    fun combine(primary: OcrResult, candidates: List<OcrResult>): OcrResult {
        val sources = candidates.ifEmpty { listOf(primary) }
        val values = Array(9) { IntArray(9) }
        val locked = Array(9) { BooleanArray(9) }
        val uncertain = linkedSetOf<Pair<Int, Int>>()
        val occupied = sources.flatMapTo(linkedSetOf()) { it.occupiedCells }
        val observed = linkedMapOf<Pair<Int, Int>, Int>()
        val requiredVotes = sources.size / 2 + 1

        for (row in 0..8) {
            for (col in 0..8) {
                val votes = IntArray(10)
                sources.forEach { result ->
                    val value = result.grid.values[row][col]
                    if (value in 0..9) votes[value]++
                }

                val highestVotes = votes.maxOrNull() ?: 0
                val winners = (0..9).filter { votes[it] == highestVotes }
                val hasMajority = highestVotes >= requiredVotes && winners.size == 1
                val chosen = if (hasMajority) winners.first() else primary.grid.values[row][col]

                values[row][col] = chosen
                locked[row][col] = chosen != 0

                val sourceValues = sources.map { it.grid.values[row][col] }.toSet()
                val primaryWasUncertain = Pair(row, col) in primary.uncertainCells
                if (!hasMajority || sourceValues.size > 1 || primaryWasUncertain) {
                    uncertain.add(Pair(row, col))
                }

                val cell = Pair(row, col)
                val observedValues = sources.mapNotNull { it.observedDigits[cell] }
                val observedWinner = observedValues
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                val bestObserved = primary.observedDigits[cell]
                    ?: observedWinner
                    ?: chosen.takeIf { it in 1..9 && cell in occupied }
                if (bestObserved != null) observed[cell] = bestObserved
            }
        }

        return OcrResult(
            grid = SudokuGrid(values, locked),
            uncertainCells = uncertain.toList(),
            occupiedCells = occupied,
            observedDigits = observed
        )
    }
}
