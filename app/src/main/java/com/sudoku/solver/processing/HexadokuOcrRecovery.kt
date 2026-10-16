package com.sudoku.solver.processing

import com.sudoku.solver.model.HexadokuGrid

data class HexadokuOcrRecoveryResult(
    val grid: HexadokuGrid,
    val solveResult: HexadokuSolveResult,
    val clearedCells: Set<Pair<Int, Int>>
)

/**
 * Recovers from contradictory 16x16 OCR without guessing a replacement symbol.
 *
 * Only clues that participate in a duplicate row, column, or box are considered.
 * Candidate clues are cleared from the solver grid but remain locked so auto-input
 * never taps a clue that is already present in the game. Recovery is accepted only
 * when every viable minimum-edit candidate produces the same unique solution.
 */
object HexadokuOcrRecovery {
    fun recover(
        grid: HexadokuGrid,
        solver: HexadokuSolver,
        maxClears: Int = DEFAULT_MAX_CLEARS,
        timeoutMs: Long = DEFAULT_CANDIDATE_TIMEOUT_MS,
        forbiddenValues: Map<Pair<Int, Int>, Set<Int>> = emptyMap()
    ): HexadokuOcrRecoveryResult? {
        val conflictCells = duplicateGroups(grid.values)
            .flatten()
            .distinct()
            .sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        if (conflictCells.isEmpty() || conflictCells.size > MAX_CONFLICT_CANDIDATES) return null

        val clearLimit = minOf(maxClears.coerceAtLeast(1), conflictCells.size)
        for (clearCount in 1..clearLimit) {
            val successful = mutableListOf<HexadokuOcrRecoveryResult>()
            var sawAmbiguousCandidate = false
            var sawIndeterminateCandidate = false

            forEachCombination(conflictCells, clearCount) { cleared ->
                val candidateGrid = clearValuesButKeepLocked(grid, cleared)
                // Avoid invoking the backtracker until this edit covers every
                // duplicate group in the OCR grid.
                if (duplicateGroups(candidateGrid.values).isNotEmpty()) return@forEachCombination

                val candidateResult = solver.solve(candidateGrid, timeoutMs, forbiddenValues)
                when (candidateResult.status) {
                    HexadokuSolveStatus.SUCCESS -> {
                        if (candidateResult.solution != null) {
                            successful += HexadokuOcrRecoveryResult(
                                grid = candidateGrid,
                                solveResult = candidateResult,
                                clearedCells = cleared.toSet()
                            )
                        }
                    }
                    HexadokuSolveStatus.MULTIPLE_SOLUTIONS -> sawAmbiguousCandidate = true
                    HexadokuSolveStatus.TIMEOUT,
                    HexadokuSolveStatus.CANCELLED -> sawIndeterminateCandidate = true
                    HexadokuSolveStatus.INVALID,
                    HexadokuSolveStatus.UNSOLVABLE -> Unit
                }
            }

            if (successful.isNotEmpty()) {
                if (sawAmbiguousCandidate || sawIndeterminateCandidate) return null
                val expected = successful.first().solveResult.solution ?: return null
                if (successful.any { !expected.contentDeepEquals(it.solveResult.solution) }) return null
                return successful.first()
            }

            // Removing more clues cannot make an already ambiguous candidate
            // safer, and a timed-out branch means recovery was not exhaustive.
            if (sawAmbiguousCandidate || sawIndeterminateCandidate) return null
        }
        return null
    }

    internal fun duplicateConflictCells(values: Array<IntArray>): Set<Pair<Int, Int>> =
        duplicateGroups(values).flatten().toSet()

    private fun duplicateGroups(values: Array<IntArray>): List<List<Pair<Int, Int>>> {
        if (values.size != HexadokuGrid.SIZE || values.any { it.size != HexadokuGrid.SIZE }) {
            return emptyList()
        }
        val groups = mutableListOf<List<Pair<Int, Int>>>()

        fun addDuplicates(unit: List<Pair<Int, Int>>) {
            unit.groupBy { (row, col) -> values[row][col] }
                .filterKeys { it != 0 }
                .values
                .filterTo(groups) { it.size > 1 }
        }

        for (row in 0 until HexadokuGrid.SIZE) {
            addDuplicates((0 until HexadokuGrid.SIZE).map { col -> row to col })
        }
        for (col in 0 until HexadokuGrid.SIZE) {
            addDuplicates((0 until HexadokuGrid.SIZE).map { row -> row to col })
        }
        for (boxRow in 0 until HexadokuGrid.SIZE step HexadokuGrid.BOX_SIZE) {
            for (boxCol in 0 until HexadokuGrid.SIZE step HexadokuGrid.BOX_SIZE) {
                addDuplicates(
                    buildList {
                        for (row in boxRow until boxRow + HexadokuGrid.BOX_SIZE) {
                            for (col in boxCol until boxCol + HexadokuGrid.BOX_SIZE) {
                                add(row to col)
                            }
                        }
                    }
                )
            }
        }
        return groups
    }

    private fun clearValuesButKeepLocked(
        grid: HexadokuGrid,
        cleared: List<Pair<Int, Int>>
    ): HexadokuGrid {
        val values = grid.values.map(IntArray::clone).toTypedArray()
        val locked = grid.locked.map(BooleanArray::clone).toTypedArray()
        cleared.forEach { (row, col) ->
            values[row][col] = 0
            // The OCR value is discarded, but the target cell is visibly occupied.
            // Keeping it locked prevents auto-input from trying to edit a given clue.
            locked[row][col] = true
        }
        return HexadokuGrid(values, locked)
    }

    private fun <T> forEachCombination(
        items: List<T>,
        count: Int,
        action: (List<T>) -> Unit
    ) {
        val selected = ArrayList<T>(count)
        fun visit(start: Int) {
            if (selected.size == count) {
                action(selected.toList())
                return
            }
            val remaining = count - selected.size
            val lastStart = items.size - remaining
            for (index in start..lastStart) {
                selected += items[index]
                visit(index + 1)
                selected.removeAt(selected.lastIndex)
            }
        }
        visit(0)
    }

    private const val DEFAULT_MAX_CLEARS = 4
    private const val DEFAULT_CANDIDATE_TIMEOUT_MS = 3_000L
    private const val MAX_CONFLICT_CANDIDATES = 12
}
