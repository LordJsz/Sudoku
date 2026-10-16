package com.sudoku.solver.processing

import android.util.Log
import com.sudoku.solver.model.Contradiction
import com.sudoku.solver.model.SolverResult
import com.sudoku.solver.model.SudokuGrid

/**
 * Solves Sudoku puzzles using backtracking with constraint checking
 */
class SudokuSolver {
    @Volatile
    private var stopRequested = false

    fun requestStop() {
        stopRequested = true
    }

    fun clearStopRequest() {
        stopRequested = false
    }

    private fun shouldStop(): Boolean = stopRequested

    fun findContradictionCells(board: Array<IntArray>): List<Pair<Int, Int>> {
        return findContradictions(board).flatMap { it.positions }.distinct()
    }

    /**
     * Main entry point: Validate and solve the puzzle
     * Returns detailed result about what's wrong or the solution
     */
    fun validateAndSolve(grid: SudokuGrid): ValidationResult {
        if (shouldStop()) {
            return ValidationResult(
                status = ValidationStatus.INTERNAL_ERROR,
                solution = null,
                message = "Solving cancelled.",
                problemCells = emptyList()
            )
        }

        val clueCount = grid.values.sumOf { row -> row.count { it != 0 } }
        Log.i(TAG, "=== Validating puzzle with $clueCount clues ===")
        logGrid(grid.values)

        // Step 1: Check for contradictions (duplicates in row/column/box)
        val contradictions = findContradictions(grid.values)
        if (contradictions.isNotEmpty()) {
            Log.e(TAG, "Found ${contradictions.size} contradictions")

            // Fast path for OCR noise: greedily drop highly conflicting clues, then solve.
            val fastResolved = tryFastResolveByDroppingConflicts(grid.values, contradictions)
            if (fastResolved != null) {
                Log.i(TAG, "Fast conflict-drop resolution successful!")
                logGrid(fastResolved.correctedGrid)
                return ValidationResult(
                    status = ValidationStatus.SUCCESS,
                    solution = fastResolved.solution,
                    message = "✓ Fixed OCR conflicts by removing ${fastResolved.corrections.size} noisy clue(s) and solved!",
                    problemCells = emptyList(),
                    correctedGrid = fastResolved.correctedGrid,
                    corrections = fastResolved.corrections
                )
            }

            // Check if any are confused digits (6/9, 1/7) - try auto-resolution
            val confusedConflicts = contradictions.filter {
                it.value == 6 || it.value == 9 || it.value == 1 || it.value == 7
            }

            if (confusedConflicts.isNotEmpty()) {
                Log.i(TAG, "Attempting auto-resolution for ${confusedConflicts.size} confused digit conflicts")
                val autoResolved = tryAutoResolveConflicts(grid.values, contradictions)
                if (autoResolved != null) {
                    Log.i(TAG, "Auto-resolution successful!")
                    logGrid(autoResolved.correctedGrid)

                    // Continue with the corrected grid
                    return ValidationResult(
                        status = ValidationStatus.SUCCESS,
                        solution = autoResolved.solution,
                        message = "✓ Auto-corrected ${autoResolved.corrections.size} OCR error(s) and solved!\n${autoResolved.correctionDescription}",
                        problemCells = emptyList(),
                        correctedGrid = autoResolved.correctedGrid,
                        corrections = autoResolved.corrections,
                        solutionIsTimeout = autoResolved.isTimeoutResult
                    )
                }
                Log.i(TAG, "Auto-resolution failed - asking user")
            }

            // Collect all cells involved in contradictions
            val problemCells = contradictions.flatMap { it.positions }.distinct()

            val message = if (confusedConflicts.isNotEmpty()) {
                "Found duplicate ${confusedConflicts.first().value}s - likely OCR confusion (6↔9 or 1↔7).\nCouldn't auto-fix. Please check RED cells."
            } else {
                "Found ${contradictions.size} duplicate values.\nPlease check the RED cells."
            }

            return ValidationResult(
                status = ValidationStatus.HAS_CONTRADICTIONS,
                solution = null,
                message = message,
                problemCells = problemCells
            )
        }

        // Step 2: Check minimum clues
        // Keep a hard floor only for extremely sparse OCR output.
        if (clueCount < 10) {
            return ValidationResult(
                status = ValidationStatus.NEEDS_MORE_CLUES,
                solution = null,
                message = "Need at least 10 clues to attempt solving.\nCurrently: $clueCount clues.",
                problemCells = emptyList()
            )
        }

        if (clueCount < 17) {
            Log.w(TAG, "Low clue count ($clueCount). Attempting solve anyway.")
        }

        // Step 3: Try to solve
        val board = Array(9) { r -> IntArray(9) { c -> grid.values[r][c] } }
        if (!solveBacktrack(board)) {
            // Find most constrained cells to suggest for hints
            val suggestions = findMostConstrainedCells(grid.values)
            Log.i(TAG, "Primary solve failed on contradiction-free grid. Attempting unsolvable auto-resolution")
            val autoResolved = tryAutoResolveUnsolvable(grid.values, suggestions)
            if (autoResolved != null) {
                Log.i(TAG, "Unsolvable auto-resolution successful!")
                logGrid(autoResolved.correctedGrid)
                return ValidationResult(
                    status = ValidationStatus.SUCCESS,
                    solution = autoResolved.solution,
                    message = "✓ Auto-corrected ${autoResolved.corrections.size} OCR error(s) and solved!\n${autoResolved.correctionDescription}",
                    problemCells = emptyList(),
                    correctedGrid = autoResolved.correctedGrid,
                    corrections = autoResolved.corrections,
                    solutionIsTimeout = autoResolved.isTimeoutResult
                )
            }
            return ValidationResult(
                status = ValidationStatus.UNSOLVABLE,
                solution = null,
                message = "Current OCR clues are unsatisfiable.\nLikely OCR error. Check RED cells or re-scan.",
                problemCells = suggestions
            )
        }

        // Step 4: Verify solution is valid
        val solutionCheck = findContradictions(board)
        if (solutionCheck.isNotEmpty()) {
            Log.e(TAG, "BUG: Solver produced invalid solution!")
            return ValidationResult(
                status = ValidationStatus.INTERNAL_ERROR,
                solution = null,
                message = "Internal error. Please re-scan.",
                problemCells = emptyList()
            )
        }

        // Step 5: Check if solution is unique
        val uniqueness = checkUniqueness(grid.values)
        if (!uniqueness.isUnique) {
            // A sparse OCR grid can legitimately have several solutions. Swapping
            // otherwise valid 1/7 or 6/9 clues merely to manufacture uniqueness is
            // not evidence of an OCR error and can corrupt correct puzzle givens.
            // Keep the observed clues unchanged and ask for a key cell instead.
            Log.i(TAG, "Multiple solutions found - requesting key-cell confirmation")

            val keyCell = uniqueness.keyCellRecommendation
            if (keyCell != null) {
                Log.i(
                    TAG,
                    "Key disambiguation cell: r${keyCell.row + 1}c${keyCell.col + 1} " +
                        "(options ${keyCell.optionA}/${keyCell.optionB})"
                )
            }

            val recommendationLine = if (keyCell != null) {
                val remaining = (uniqueness.keyCellRecommendations.size - 1).coerceAtLeast(0)
                if (remaining > 0) {
                    "Key cell to verify first: row ${keyCell.row + 1}, col ${keyCell.col + 1} " +
                        "(${keyCell.optionA} or ${keyCell.optionB}). Then $remaining more key cell(s)."
                } else {
                    "Key cell to verify: row ${keyCell.row + 1}, col ${keyCell.col + 1} (${keyCell.optionA} or ${keyCell.optionB})."
                }
            } else {
                "Get hints for RED cells."
            }

            return ValidationResult(
                status = ValidationStatus.MULTIPLE_SOLUTIONS,
                solution = board,
                message = "Puzzle has multiple solutions!\n${uniqueness.ambiguousCells.size} cells are ambiguous.\n$recommendationLine",
                problemCells = uniqueness.ambiguousCells,
                keyCellRecommendation = keyCell,
                keyCellRecommendations = uniqueness.keyCellRecommendations
            )
        }

        // Success!
        Log.i(TAG, "Puzzle solved successfully with unique solution")
        logGrid(board)
        return ValidationResult(
            status = ValidationStatus.SUCCESS,
            solution = board,
            message = "✓ Unique solution found!",
            problemCells = emptyList()
        )
    }

    /**
     * OCR confusion swap maps - progressive rounds from most common to least common.
     * Round 1: 6↔9, 1↔7 (most frequent OCR errors)
     * Round 2: adds 3↔8, 5↔6, 4↔9, 2↔7
     * Round 3: expanded - each digit tries all plausible OCR misreads
     */
    private val swapRounds: List<Map<Int, List<Int>>> = listOf(
        // Round 1: primary confusions only
        mapOf(6 to listOf(9), 9 to listOf(6), 1 to listOf(7), 7 to listOf(1)),
        // Round 2: common confusions
        mapOf(
            6 to listOf(9), 9 to listOf(6), 1 to listOf(7), 7 to listOf(1),
            3 to listOf(8), 8 to listOf(3), 5 to listOf(6), 4 to listOf(9, 2), 2 to listOf(7, 4)
        ),
        // Round 3: all plausible OCR misreads per digit
        mapOf(
            1 to listOf(7, 4),
            2 to listOf(7, 4, 3),
            3 to listOf(8, 5, 9),
            4 to listOf(2, 9, 1),
            5 to listOf(6, 3, 8),
            6 to listOf(9, 8, 5),
            7 to listOf(1, 2),
            8 to listOf(3, 6, 9),
            9 to listOf(6, 4)
        )
    )

    /**
     * Try to auto-resolve conflicts by swapping confused digits.
     * Uses iterative deepening on maxSwaps (1→2→3→4) to guarantee minimum-swap solution.
     * For each swap budget:
     *   Phase 1: Only contradiction cells with progressive swap rounds.
     *   Phase 2: All confused cells (contradiction cells first) with progressive swap rounds.
     * Fewer swaps = more likely to be the correct original puzzle.
     */
    private fun tryAutoResolveConflicts(
        original: Array<IntArray>,
        contradictions: List<Contradiction>
    ): AutoResolutionResult? {
        if (shouldStop()) return null
        val contradictionCells = contradictions.flatMap { it.positions }.distinct().toSet()
        val startMs = System.currentTimeMillis()
        val budgetMs = 12_000L

        // Iterative deepening: prefer solutions with fewer swaps
        for (maxSwaps in 1..4) {
            if (shouldStop()) return null
            if (System.currentTimeMillis() - startMs > budgetMs) {
                Log.i(TAG, "Auto-resolution timed out at ${budgetMs}ms")
                return null
            }
            Log.i(TAG, "--- Trying maxSwaps=$maxSwaps ---")

            // Phase 1: Only contradiction cells
            for ((roundIdx, swapMap) in swapRounds.withIndex()) {
                if (shouldStop()) return null
                if (System.currentTimeMillis() - startMs > budgetMs) {
                    Log.i(TAG, "Auto-resolution timed out at ${budgetMs}ms")
                    return null
                }
                val cells = mutableListOf<Pair<Int, Int>>()
                val cellOptions = mutableListOf<List<Int>>()

                for ((row, col) in contradictionCells) {
                    if (shouldStop()) return null
                    val v = original[row][col]
                    val swaps = swapMap[v]
                    if (swaps != null) {
                        cells.add(Pair(row, col))
                        // Include 0 (remove) as option for contradiction cells -
                        // phantom OCR digits may need to be cleared, not just swapped.
                        cellOptions.add((listOf(v) + swaps + 0).distinct())
                    }
                }

                if (cells.isEmpty()) continue

                Log.i(TAG, "Phase 1 round ${roundIdx + 1}: ${cells.size} contradiction cells (maxSwaps=$maxSwaps)")

                val result = backtrackConfusedDigits(original, cells, cellOptions, maxSwaps = maxSwaps)
                if (result != null) {
                    Log.i(TAG, "Resolved in phase 1 round ${roundIdx + 1} with maxSwaps=$maxSwaps")
                    return result
                }
            }

            // Phase 2: Contradiction cells first, then ALL confused cells
            for ((roundIdx, swapMap) in swapRounds.withIndex()) {
                if (shouldStop()) return null
                if (System.currentTimeMillis() - startMs > budgetMs) {
                    Log.i(TAG, "Auto-resolution timed out at ${budgetMs}ms")
                    return null
                }
                val priorityCells = mutableListOf<Pair<Int, Int>>()
                val priorityOptions = mutableListOf<List<Int>>()
                val normalCells = mutableListOf<Pair<Int, Int>>()
                val normalOptions = mutableListOf<List<Int>>()

                for (row in 0..8) {
                    for (col in 0..8) {
                        if (shouldStop()) return null
                        val v = original[row][col]
                        val swaps = swapMap[v]
                        if (swaps != null) {
                            val cell = Pair(row, col)
                            if (cell in contradictionCells) {
                                priorityCells.add(cell)
                                // Include 0 (remove) for contradiction cells - may be phantom OCR digits
                                priorityOptions.add((listOf(v) + swaps + 0).distinct())
                            } else {
                                normalCells.add(cell)
                                normalOptions.add(listOf(v) + swaps)
                            }
                        }
                    }
                }

                val cells = priorityCells + normalCells
                val cellOptions = priorityOptions + normalOptions

                if (cells.isEmpty()) continue

                Log.i(TAG, "Phase 2 round ${roundIdx + 1}: ${cells.size} cells (${priorityCells.size} priority, maxSwaps=$maxSwaps)")

                val result = backtrackConfusedDigits(original, cells, cellOptions, maxSwaps = maxSwaps)
                if (result != null) {
                    Log.i(TAG, "Resolved in phase 2 round ${roundIdx + 1} with maxSwaps=$maxSwaps")
                    return result
                }
            }
        }

        // Fallback: contradiction cells may include false-positive OCR clues.
        // Allow turning a few contradiction clues into empty cells (value -> 0).
        val clearResult = tryResolveByClearingContradictions(original, contradictionCells)
        if (clearResult != null) {
            Log.i(TAG, "Resolved by clearing contradiction clue(s)")
            return clearResult
        }

        return null
    }

    /**
     * Fallback conflict resolver that can clear a small number of contradiction clues (set to 0).
     * This helps when OCR hallucinated a digit in an empty cell.
     */
    private fun tryResolveByClearingContradictions(
        original: Array<IntArray>,
        contradictionCells: Set<Pair<Int, Int>>
    ): AutoResolutionResult? {
        val cells = mutableListOf<Pair<Int, Int>>()
        val cellOptions = mutableListOf<List<Int>>()

        for ((row, col) in contradictionCells) {
            if (shouldStop()) return null
            val value = original[row][col]
            if (value == 0) continue

            val options = mutableListOf(value)
            options.addAll(getSwapOptions(value))
            options.add(0) // allow removing a likely false OCR clue

            val distinctOptions = options.distinct()
            if (distinctOptions.size > 1) {
                cells.add(Pair(row, col))
                cellOptions.add(distinctOptions)
            }
        }

        if (cells.isEmpty()) {
            return null
        }

        for (maxSwaps in 1..3) {
            if (shouldStop()) return null
            Log.i(TAG, "Fallback clear round: ${cells.size} contradiction cells (maxSwaps=$maxSwaps)")
            val result = backtrackConfusedDigits(original, cells, cellOptions, maxSwaps = maxSwaps)
            if (result != null) {
                return result
            }
        }

        return null
    }

    /**
     * Attempt OCR auto-recovery when the grid is contradiction-free but unsolvable.
     * Strategy:
     * 1) Try single clue edits near the most constrained empty cells.
     * 2) If needed, run a bounded multi-cell swap/clear search.
     */
    private fun tryAutoResolveUnsolvable(
        original: Array<IntArray>,
        constrainedCells: List<Pair<Int, Int>>
    ): AutoResolutionResult? {
        if (shouldStop()) return null
        val startMs = System.currentTimeMillis()
        val budgetMs = 4_500L
        val candidateScores = mutableMapOf<Pair<Int, Int>, Int>()

        fun bump(cell: Pair<Int, Int>, score: Int) {
            candidateScores[cell] = (candidateScores[cell] ?: 0) + score
        }

        for ((row, col) in constrainedCells) {
            if (shouldStop()) return null
            for (c in 0..8) {
                if (original[row][c] != 0) bump(Pair(row, c), 4)
            }
            for (r in 0..8) {
                if (original[r][col] != 0) bump(Pair(r, col), 4)
            }
            val boxRow = (row / 3) * 3
            val boxCol = (col / 3) * 3
            for (r in boxRow until boxRow + 3) {
                for (c in boxCol until boxCol + 3) {
                    if (original[r][c] != 0) bump(Pair(r, c), 3)
                }
            }
        }

        for (row in 0..8) {
            for (col in 0..8) {
                val value = original[row][col]
                if (value == 0) continue
                val fallbackScore = if (value == 1 || value == 2 || value == 4 || value == 6 || value == 7 || value == 9) 2 else 1
                val cell = Pair(row, col)
                candidateScores[cell] = maxOf(candidateScores[cell] ?: 0, fallbackScore)
            }
        }

        val candidates = candidateScores.entries
            .sortedWith(
                compareByDescending<Map.Entry<Pair<Int, Int>, Int>> { it.value }
                    .thenByDescending { confusionPriority(original[it.key.first][it.key.second]) }
                    .thenBy { it.key.first }
                    .thenBy { it.key.second }
            )
            .map { it.key }

        if (candidates.isEmpty()) return null

        // Stage 1: single-clue edits first (fast and safer).
        for ((row, col) in candidates) {
            if (shouldStop()) return null
            if (System.currentTimeMillis() - startMs > budgetMs) {
                Log.i(TAG, "Unsolvable auto-resolution timed out in single-edit stage")
                return null
            }
            val oldValue = original[row][col]
            val options = (getSwapOptions(oldValue) + 0).distinct()
            for (newValue in options) {
                if (shouldStop()) return null
                if (newValue == oldValue) continue

                val testGrid = Array(9) { r -> IntArray(9) { c -> original[r][c] } }
                testGrid[row][col] = newValue
                if (findContradictions(testGrid).isNotEmpty()) continue

                val solution = Array(9) { r -> IntArray(9) { c -> testGrid[r][c] } }
                if (!solveBacktrack(solution)) continue

                val unique = checkUniquenessQuick(testGrid, 1_500)
                if (unique == false) continue

                val correction = CellCorrection(row, col, oldValue, newValue)
                val uniquenessLabel = if (unique == true) "unique" else "likely unique (timeout)"
                Log.i(
                    TAG,
                    "Unsolvable auto-resolution: single clue fix ($row,$col) " +
                        "$oldValue->$newValue ($uniquenessLabel)"
                )
                return AutoResolutionResult(
                    correctedGrid = testGrid,
                    solution = solution,
                    corrections = listOf(correction),
                    correctionDescription = "($row,$col): $oldValue→$newValue"
                )
            }
        }

        if (System.currentTimeMillis() - startMs > budgetMs) {
            Log.i(TAG, "Unsolvable auto-resolution timed out before multi-edit stage")
            return null
        }

        // Stage 2: bounded multi-cell search.
        val candidateCells = mutableListOf<Pair<Int, Int>>()
        val cellOptions = mutableListOf<List<Int>>()
        for ((row, col) in candidates.take(12)) {
            val value = original[row][col]
            val options = (listOf(value) + getSwapOptions(value) + 0).distinct()
            if (options.size > 1) {
                candidateCells.add(Pair(row, col))
                cellOptions.add(options)
            }
        }

        if (candidateCells.isEmpty()) return null

        Log.i(TAG, "Unsolvable auto-resolution stage 2: ${candidateCells.size} candidate clues (maxSwaps=2)")
        val stage2 = backtrackConfusedDigits(original, candidateCells, cellOptions, maxSwaps = 2)
        if (stage2 != null) return stage2

        if (System.currentTimeMillis() - startMs > budgetMs) {
            Log.i(TAG, "Unsolvable auto-resolution timed out before final stage")
            return null
        }

        val reducedCells = candidateCells.take(8)
        val reducedOptions = cellOptions.take(8)
        Log.i(TAG, "Unsolvable auto-resolution stage 3: ${reducedCells.size} candidate clues (maxSwaps=3)")
        return backtrackConfusedDigits(original, reducedCells, reducedOptions, maxSwaps = 3)
    }

    /**
     * Backtracking solver for confused digit resolution.
     * For each confused cell, tries all its possible values (original + swaps),
     * pruning branches that create duplicates.
     * Returns the first valid solution found (original values tried first = fewest swaps).
     * @param maxSwaps Maximum number of cell changes allowed (prevents constructing a different puzzle)
     */
    private fun backtrackConfusedDigits(
        original: Array<IntArray>,
        confusedCells: List<Pair<Int, Int>>,
        cellOptions: List<List<Int>>,
        maxSwaps: Int = 6,
        timeoutMs: Long = 3_000L
    ): AutoResolutionResult? {
        if (shouldStop()) return null
        val testGrid = Array(9) { r -> IntArray(9) { c -> original[r][c] } }
        val chosenValue = IntArray(confusedCells.size)
        val startTime = System.currentTimeMillis()
        val deadlineMs = startTime + timeoutMs.coerceAtLeast(200L)
        var found: AutoResolutionResult? = null

        // Set all confused cells to 0 so they don't interfere during backtracking
        for ((row, col) in confusedCells) {
            if (shouldStop()) return null
            testGrid[row][col] = 0
        }

        // Check if a cell has no duplicates with already-assigned cells
        fun cellIsValid(row: Int, col: Int): Boolean {
            val value = testGrid[row][col]
            if (value == 0) return true
            for (c in 0..8) {
                if (c != col && testGrid[row][c] == value) return false
            }
            for (r in 0..8) {
                if (r != row && testGrid[r][col] == value) return false
            }
            val br = (row / 3) * 3
            val bc = (col / 3) * 3
            for (r in br until br + 3) {
                for (c in bc until bc + 3) {
                    if ((r != row || c != col) && testGrid[r][c] == value) return false
                }
            }
            return true
        }

        fun backtrack(idx: Int, swapCount: Int): Boolean {
            if (shouldStop()) return false
            if (found != null) return true
            if (System.currentTimeMillis() >= deadlineMs) return false

            if (idx >= confusedCells.size) {
                if (shouldStop()) return false
                // All cells assigned - check no contradictions remain
                if (findContradictions(testGrid).isNotEmpty()) return false

                // Try to solve
                val solution = Array(9) { r -> IntArray(9) { c -> testGrid[r][c] } }
                if (solveBacktrack(solution, deadlineMs)) {
                    if (shouldStop()) return false
                    val remainingMs = (deadlineMs - System.currentTimeMillis()).coerceAtMost(1_000L)
                    if (remainingMs <= 0L) return false
                    // Quick uniqueness check bounded by remaining budget.
                    val uniqueResult = checkUniquenessQuick(testGrid, remainingMs)
                    if (uniqueResult == false) {
                        // Confirmed multiple solutions - wrong interpretation, skip
                        return false
                    }
                    // uniqueResult == true (confirmed unique) or null (timeout = probably unique)

                    val corrections = mutableListOf<CellCorrection>()
                    for (i in confusedCells.indices) {
                        val (r, c) = confusedCells[i]
                        if (chosenValue[i] != original[r][c]) {
                            corrections.add(CellCorrection(r, c, original[r][c], chosenValue[i]))
                        }
                    }
                    found = AutoResolutionResult(
                        correctedGrid = Array(9) { r -> IntArray(9) { c -> testGrid[r][c] } },
                        solution = solution,
                        corrections = corrections,
                        correctionDescription = corrections.joinToString(", ") {
                            "(${it.row},${it.col}): ${it.oldValue}→${it.newValue}"
                        },
                        isTimeoutResult = uniqueResult == null
                    )
                    val status = if (uniqueResult == true) "unique" else "likely unique (timeout)"
                    Log.i(TAG, "Backtrack found $status solution with $swapCount swap(s) (max=$maxSwaps)")
                    return true
                }
                return false
            }

            val (row, col) = confusedCells[idx]
            val options = cellOptions[idx]

            for ((_, value) in options.withIndex()) {
                if (shouldStop()) return false
                if (System.currentTimeMillis() >= deadlineMs) return false
                val isSwap = value != original[row][col]
                val newSwapCount = swapCount + if (isSwap) 1 else 0

                // Prune: too many swaps means we're constructing a different puzzle
                if (newSwapCount > maxSwaps) continue

                testGrid[row][col] = value
                chosenValue[idx] = value
                if (cellIsValid(row, col)) {
                    if (backtrack(idx + 1, newSwapCount)) return true
                }
            }

            testGrid[row][col] = 0
            return false
        }

        backtrack(0, 0)

        if (found != null) {
            Log.i(TAG, "Backtracking resolved with ${found!!.corrections.size} swap(s) (max=$maxSwaps): ${found!!.correctionDescription}")
        } else {
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Backtracking found no valid solution (${elapsed}ms, maxSwaps=$maxSwaps)")
        }

        return found
    }

    /**
     * Fast OCR-recovery strategy:
     * 1) Repeatedly clear the "most suspicious" clue among conflicting cells.
     * 2) When contradictions disappear, try solving the remaining puzzle.
     *
     * This is intentionally aggressive and much faster than combinational swap search.
     */
    private fun tryFastResolveByDroppingConflicts(
        original: Array<IntArray>,
        initialContradictions: List<Contradiction>
    ): AutoResolutionResult? {
        if (shouldStop()) return null
        val testGrid = Array(9) { r -> IntArray(9) { c -> original[r][c] } }
        val corrections = mutableListOf<CellCorrection>()
        val initialClues = original.sumOf { row -> row.count { it != 0 } }
        // Keep this aggressive for OCR noise recovery, but only accept it when
        // the reduced puzzle remains uniquely solvable.
        val maxDrops = (initialClues - 10).coerceIn(2, 20)

        var contradictions = initialContradictions
        var drops = 0

        while (contradictions.isNotEmpty() && drops < maxDrops) {
            if (shouldStop()) return null
            // Frequency score: cells appearing in multiple contradictions are more likely OCR mistakes.
            val score = mutableMapOf<Pair<Int, Int>, Int>()
            for (conflict in contradictions) {
                if (shouldStop()) return null
                for (cell in conflict.positions) {
                    score[cell] = (score[cell] ?: 0) + 1
                }
            }

            val candidate = score.keys.maxWithOrNull(
                compareBy<Pair<Int, Int>> { score[it] ?: 0 }
                    .thenBy { confusionPriority(testGrid[it.first][it.second]) }
                    .thenBy { -testGrid[it.first][it.second] }
            ) ?: break

            val (row, col) = candidate
            val oldValue = testGrid[row][col]
            if (oldValue == 0) {
                contradictions = findContradictions(testGrid)
                continue
            }

            testGrid[row][col] = 0
            corrections.add(CellCorrection(row, col, oldValue, 0))
            drops++

            Log.i(
                TAG,
                "Fast drop: cleared ($row,$col)=$oldValue " +
                    "(score=${score[candidate] ?: 1}, drop $drops/$maxDrops)"
            )

            contradictions = findContradictions(testGrid)
        }

        if (contradictions.isNotEmpty()) {
            Log.i(TAG, "Fast drop could not remove all contradictions")
            return null
        }

        if (shouldStop()) return null
        val solution = Array(9) { r -> IntArray(9) { c -> testGrid[r][c] } }
        if (!solveBacktrack(solution)) {
            Log.i(TAG, "Fast drop produced contradiction-free grid but still unsolvable")
            return null
        }

        if (shouldStop()) return null
        val unique = checkUniquenessQuick(testGrid, 1500)
        if (unique != true) {
            Log.w(TAG, "Fast drop result is not uniquely solvable; rejecting fast-drop candidate")
            return null
        }

        return AutoResolutionResult(
            correctedGrid = Array(9) { r -> IntArray(9) { c -> testGrid[r][c] } },
            solution = solution,
            corrections = corrections,
            correctionDescription = corrections.joinToString(", ") {
                "(${it.row},${it.col}): ${it.oldValue}→${it.newValue}"
            }
        )
    }

    private fun confusionPriority(value: Int): Int {
        return when (value) {
            6, 9, 1, 7 -> 3
            8, 3, 5, 4, 2 -> 2
            else -> 1
        }
    }

    /**
     * Find an alternative solution when certain digits were wrong.
     * Tries changing each wrong digit to all other possible values.
     * @param original The original grid (clues)
     * @param wrongCells List of (row, col, wrongDigit) that the game rejected
     * @return Alternative solution if found, null otherwise
     */
    fun solveWithWrongDigits(
        original: Array<IntArray>,
        wrongCells: List<Triple<Int, Int, Int>>
    ): Array<IntArray>? {
        if (shouldStop()) return null
        if (wrongCells.isEmpty()) return null

        val wrongConstraints = wrongCells.distinct()
        Log.i(TAG, "Trying to find alternative solution for ${wrongConstraints.size} wrong cells")
        val startMs = System.currentTimeMillis()
        val budgetMs = 8_000L

        // For each wrong cell, the clue might be wrong
        // Try changing each clue cell that could affect the wrong solution cells
        for ((wrongRow, wrongCol, wrongDigit) in wrongConstraints) {
            if (shouldStop()) return null
            if (System.currentTimeMillis() - startMs > budgetMs) {
                Log.i(TAG, "Alternative-solution search timed out at ${budgetMs}ms")
                return null
            }
            Log.i(TAG, "Wrong digit $wrongDigit at ($wrongRow,$wrongCol) - checking related clues")

            // Find all clue cells that constrain this cell (same row, column, or box)
            val relatedClues = mutableListOf<Pair<Int, Int>>()

            // Same row
            for (c in 0..8) {
                if (shouldStop()) return null
                if (original[wrongRow][c] != 0 && c != wrongCol) {
                    relatedClues.add(Pair(wrongRow, c))
                }
            }
            // Same column
            for (r in 0..8) {
                if (shouldStop()) return null
                if (original[r][wrongCol] != 0 && r != wrongRow) {
                    relatedClues.add(Pair(r, wrongCol))
                }
            }
            // Same box
            val boxRow = (wrongRow / 3) * 3
            val boxCol = (wrongCol / 3) * 3
            for (r in boxRow until boxRow + 3) {
                for (c in boxCol until boxCol + 3) {
                    if (shouldStop()) return null
                    if (original[r][c] != 0 && (r != wrongRow || c != wrongCol)) {
                        if (!relatedClues.contains(Pair(r, c))) {
                            relatedClues.add(Pair(r, c))
                        }
                    }
                }
            }

            // Try swapping each related clue that's a confused digit
            for ((clueRow, clueCol) in relatedClues) {
                if (shouldStop()) return null
                if (System.currentTimeMillis() - startMs > budgetMs) {
                    Log.i(TAG, "Alternative-solution search timed out at ${budgetMs}ms")
                    return null
                }
                val clueValue = original[clueRow][clueCol]

                // Try all possible swaps for this clue
                val swapOptions = (getSwapOptions(clueValue) + listOf(0)).distinct()
                for (newValue in swapOptions) {
                    if (shouldStop()) return null
                    val testGrid = Array(9) { r -> IntArray(9) { c -> original[r][c] } }
                    testGrid[clueRow][clueCol] = newValue

                    // Check no contradictions
                    if (findContradictions(testGrid).isNotEmpty()) continue

                    // Try to solve
                    val solution = Array(9) { r -> IntArray(9) { c -> testGrid[r][c] } }
                    if (solveBacktrack(solution)) {
                        // Candidate must avoid all known wrong digit constraints, not only the current one.
                        if (solutionMatchesAnyWrongDigit(solution, wrongConstraints)) {
                            continue
                        }
                        // Keep this check for explicit logging of the current wrong cell.
                        if (solution[wrongRow][wrongCol] != wrongDigit) {
                            Log.i(TAG, "Found alternative! Changed ($clueRow,$clueCol): $clueValue → $newValue")
                            Log.i(TAG, "New value at ($wrongRow,$wrongCol): ${solution[wrongRow][wrongCol]} (was $wrongDigit)")
                            return solution
                        }
                    }
                }
            }
        }

        Log.i(TAG, "No alternative solution found")
        return null
    }

    private fun solutionMatchesAnyWrongDigit(
        solution: Array<IntArray>,
        wrongConstraints: List<Triple<Int, Int, Int>>
    ): Boolean {
        for ((row, col, wrongDigit) in wrongConstraints) {
            if (solution[row][col] == wrongDigit) return true
        }
        return false
    }

    /**
     * Get possible swap values for a digit based on common OCR confusions
     */
    private fun getSwapOptions(digit: Int): List<Int> {
        return when (digit) {
            1 -> listOf(7)
            7 -> listOf(1)
            6 -> listOf(9, 8, 5)  // 6 can look like 9, 8, or 5
            9 -> listOf(6, 4)     // 9 can look like 6 or 4
            8 -> listOf(3, 6)     // 8 can look like 3 or 6
            3 -> listOf(8, 5)     // 3 can look like 8 or 5
            5 -> listOf(6, 3)     // 5 can look like 6 or 3
            2 -> listOf(4, 7, 3)  // 2 can look like 4, 7, or 3
            4 -> listOf(2, 9, 1)  // 4 can look like 2, 9, or 1
            else -> emptyList()
        }
    }

    /**
     * Simple backtracking solver
     */
    private fun solveBacktrack(board: Array<IntArray>, deadlineMs: Long = Long.MAX_VALUE): Boolean {
        if (shouldStop()) return false
        if (System.currentTimeMillis() >= deadlineMs) return false
        for (row in 0..8) {
            for (col in 0..8) {
                if (board[row][col] == 0) {
                    for (digit in 1..9) {
                        if (shouldStop()) return false
                        if (System.currentTimeMillis() >= deadlineMs) return false
                        if (isValidPlacement(board, row, col, digit)) {
                            board[row][col] = digit
                            if (solveBacktrack(board, deadlineMs)) {
                                return true
                            }
                            board[row][col] = 0
                        }
                    }
                    return false
                }
            }
        }
        return true
    }

    /**
     * Check if placing a digit at (row, col) is valid
     */
    private fun isValidPlacement(board: Array<IntArray>, row: Int, col: Int, digit: Int): Boolean {
        // Check row
        for (c in 0..8) {
            if (board[row][c] == digit) return false
        }
        // Check column
        for (r in 0..8) {
            if (board[r][col] == digit) return false
        }
        // Check 3x3 box
        val boxRow = (row / 3) * 3
        val boxCol = (col / 3) * 3
        for (r in boxRow until boxRow + 3) {
            for (c in boxCol until boxCol + 3) {
                if (board[r][c] == digit) return false
            }
        }
        return true
    }

    /**
     * Find all contradictions (duplicates) in a board
     */
    private fun findContradictions(board: Array<IntArray>): List<Contradiction> {
        val conflicts = mutableListOf<Contradiction>()

        // Check rows
        for (row in 0..8) {
            val seen = mutableMapOf<Int, MutableList<Int>>()
            for (col in 0..8) {
                val v = board[row][col]
                if (v != 0) {
                    seen.getOrPut(v) { mutableListOf() }.add(col)
                }
            }
            for ((value, cols) in seen) {
                if (cols.size > 1) {
                    conflicts.add(Contradiction("row", row, value, cols.map { Pair(row, it) }))
                }
            }
        }

        // Check columns
        for (col in 0..8) {
            val seen = mutableMapOf<Int, MutableList<Int>>()
            for (row in 0..8) {
                val v = board[row][col]
                if (v != 0) {
                    seen.getOrPut(v) { mutableListOf() }.add(row)
                }
            }
            for ((value, rows) in seen) {
                if (rows.size > 1) {
                    conflicts.add(Contradiction("column", col, value, rows.map { Pair(it, col) }))
                }
            }
        }

        // Check 3x3 boxes
        for (boxRow in 0..2) {
            for (boxCol in 0..2) {
                val seen = mutableMapOf<Int, MutableList<Pair<Int, Int>>>()
                val br = boxRow * 3
                val bc = boxCol * 3
                for (r in br until br + 3) {
                    for (c in bc until bc + 3) {
                        val v = board[r][c]
                        if (v != 0) {
                            seen.getOrPut(v) { mutableListOf() }.add(Pair(r, c))
                        }
                    }
                }
                for ((value, positions) in seen) {
                    if (positions.size > 1) {
                        conflicts.add(Contradiction("box", boxRow * 3 + boxCol, value, positions))
                    }
                }
            }
        }

        return conflicts
    }

    /**
     * Check if puzzle has unique solution
     */
    private fun checkUniqueness(original: Array<IntArray>): UniquenessResult {
        if (shouldStop()) {
            return UniquenessResult(
                isUnique = false,
                ambiguousCells = emptyList(),
                keyCellRecommendation = null,
                keyCellRecommendations = emptyList()
            )
        }
        val solutions = mutableListOf<Array<IntArray>>()
        val board = Array(9) { r -> IntArray(9) { c -> original[r][c] } }

        findSolutions(board, solutions, maxSolutions = 2)

        if (solutions.size <= 1) {
            return UniquenessResult(
                isUnique = true,
                ambiguousCells = emptyList(),
                keyCellRecommendation = null,
                keyCellRecommendations = emptyList()
            )
        }

        // Find cells that differ between solutions
        val ambiguous = mutableListOf<Pair<Int, Int>>()
        for (row in 0..8) {
            for (col in 0..8) {
                if (original[row][col] == 0 && solutions[0][row][col] != solutions[1][row][col]) {
                    ambiguous.add(Pair(row, col))
                }
            }
        }

        val keyCellRecommendations = buildKeyDisambiguationCells(
            original = original,
            firstSolution = solutions[0],
            secondSolution = solutions[1],
            ambiguousCells = ambiguous
        )
        val keyCellRecommendation = keyCellRecommendations.firstOrNull()

        return UniquenessResult(
            isUnique = false,
            ambiguousCells = ambiguous,
            keyCellRecommendation = keyCellRecommendation,
            keyCellRecommendations = keyCellRecommendations
        )
    }

    private fun buildKeyDisambiguationCells(
        original: Array<IntArray>,
        firstSolution: Array<IntArray>,
        secondSolution: Array<IntArray>,
        ambiguousCells: List<Pair<Int, Int>>
    ): List<KeyCellRecommendation> {
        if (ambiguousCells.isEmpty()) return emptyList()

        val scored = mutableListOf<Pair<KeyCellRecommendation, Int>>()

        for ((row, col) in ambiguousCells) {
            val optionA = firstSolution[row][col]
            val optionB = secondSolution[row][col]
            if (optionA == optionB) continue

            val score = countFilledPeers(original, row, col)
            val keyCell = KeyCellRecommendation(
                row = row,
                col = col,
                optionA = optionA,
                optionB = optionB
            )
            scored.add(Pair(keyCell, score))
        }

        return scored
            .sortedWith(
                compareByDescending<Pair<KeyCellRecommendation, Int>> { it.second }
                    .thenBy { it.first.row }
                    .thenBy { it.first.col }
            )
            .map { it.first }
            .take(MAX_KEY_CELL_RECOMMENDATIONS)
    }

    private fun countFilledPeers(board: Array<IntArray>, row: Int, col: Int): Int {
        val peers = mutableSetOf<Pair<Int, Int>>()

        for (c in 0..8) {
            if (c != col) peers.add(Pair(row, c))
        }
        for (r in 0..8) {
            if (r != row) peers.add(Pair(r, col))
        }

        val boxRow = (row / 3) * 3
        val boxCol = (col / 3) * 3
        for (r in boxRow until boxRow + 3) {
            for (c in boxCol until boxCol + 3) {
                if (r != row || c != col) peers.add(Pair(r, c))
            }
        }

        return peers.count { (r, c) -> board[r][c] != 0 }
    }

    private fun findSolutions(board: Array<IntArray>, solutions: MutableList<Array<IntArray>>, maxSolutions: Int) {
        if (shouldStop()) return
        if (solutions.size >= maxSolutions) return

        for (row in 0..8) {
            for (col in 0..8) {
                if (board[row][col] == 0) {
                    for (digit in 1..9) {
                        if (shouldStop()) return
                        if (isValidPlacement(board, row, col, digit)) {
                            board[row][col] = digit
                            findSolutions(board, solutions, maxSolutions)
                            if (solutions.size >= maxSolutions) {
                                board[row][col] = 0
                                return
                            }
                            board[row][col] = 0
                        }
                    }
                    return
                }
            }
        }

        // Found a solution
        solutions.add(Array(9) { r -> IntArray(9) { c -> board[r][c] } })
    }

    /**
     * Timed uniqueness check. Returns:
     *  true  = confirmed unique (no second solution found within full search)
     *  false = confirmed multiple solutions
     *  null  = timeout (couldn't determine)
     */
    private fun checkUniquenessQuick(original: Array<IntArray>, timeoutMs: Long): Boolean? {
        if (shouldStop()) return null
        val solutions = mutableListOf<Array<IntArray>>()
        val board = Array(9) { r -> IntArray(9) { c -> original[r][c] } }
        val deadline = System.currentTimeMillis() + timeoutMs
        val timedOut = booleanArrayOf(false)

        fun findWithDeadline(bd: Array<IntArray>) {
            if (shouldStop()) {
                timedOut[0] = true
                return
            }
            if (solutions.size >= 2 || timedOut[0]) return
            if (System.currentTimeMillis() > deadline) {
                timedOut[0] = true
                return
            }

            for (row in 0..8) {
                for (col in 0..8) {
                    if (bd[row][col] == 0) {
                        for (digit in 1..9) {
                            if (shouldStop()) {
                                timedOut[0] = true
                                return
                            }
                            if (isValidPlacement(bd, row, col, digit)) {
                                bd[row][col] = digit
                                findWithDeadline(bd)
                                if (solutions.size >= 2 || timedOut[0]) {
                                    bd[row][col] = 0
                                    return
                                }
                                bd[row][col] = 0
                            }
                        }
                        return
                    }
                }
            }
            solutions.add(Array(9) { r -> IntArray(9) { c -> bd[r][c] } })
        }

        findWithDeadline(board)

        return when {
            solutions.size >= 2 -> false          // confirmed multiple
            timedOut[0] -> null                    // timeout
            solutions.size == 1 -> true            // confirmed unique
            else -> null                           // no solution found?
        }
    }

    /**
     * Find cells with fewest valid options (most constrained)
     */
    private fun findMostConstrainedCells(board: Array<IntArray>): List<Pair<Int, Int>> {
        val cells = mutableListOf<Pair<Pair<Int, Int>, Int>>()

        for (row in 0..8) {
            for (col in 0..8) {
                if (board[row][col] == 0) {
                    var count = 0
                    for (d in 1..9) {
                        if (isValidPlacement(board, row, col, d)) count++
                    }
                    cells.add(Pair(Pair(row, col), count))
                }
            }
        }

        // Return cells with 0-2 options (most problematic)
        return cells.filter { it.second <= 2 }.map { it.first }.take(5)
    }

    private fun logGrid(board: Array<IntArray>) {
        val sb = StringBuilder("\n")
        for (r in 0..8) {
            if (r % 3 == 0 && r != 0) sb.append("------+-------+------\n")
            for (c in 0..8) {
                if (c % 3 == 0 && c != 0) sb.append("| ")
                sb.append(if (board[r][c] == 0) "." else board[r][c])
                sb.append(" ")
            }
            sb.append("\n")
        }
        Log.i(TAG, sb.toString())
    }

    companion object {
        private const val TAG = "SudokuSolver"
        private const val MAX_KEY_CELL_RECOMMENDATIONS = 6
    }
}

enum class ValidationStatus {
    SUCCESS,              // Unique solution found
    HAS_CONTRADICTIONS,   // Duplicates in row/column/box
    NEEDS_MORE_CLUES,     // Less than 17 clues
    MULTIPLE_SOLUTIONS,   // Puzzle has multiple valid solutions
    UNSOLVABLE,           // No valid solution exists
    INTERNAL_ERROR        // Something went wrong
}

data class ValidationResult(
    val status: ValidationStatus,
    val solution: Array<IntArray>?,
    val message: String,
    val problemCells: List<Pair<Int, Int>>,  // Cells to highlight in RED
    val correctedGrid: Array<IntArray>? = null,  // Grid after auto-correction (if any)
    val corrections: List<CellCorrection> = emptyList(),  // What was auto-corrected
    val keyCellRecommendation: KeyCellRecommendation? = null,
    val keyCellRecommendations: List<KeyCellRecommendation> = emptyList(),
    val solutionIsTimeout: Boolean = false  // true = uniqueness unconfirmed (timed out)
)

data class UniquenessResult(
    val isUnique: Boolean,
    val ambiguousCells: List<Pair<Int, Int>>,
    val keyCellRecommendation: KeyCellRecommendation?,
    val keyCellRecommendations: List<KeyCellRecommendation>
)

data class KeyCellRecommendation(
    val row: Int,
    val col: Int,
    val optionA: Int,
    val optionB: Int
)

data class AutoResolutionResult(
    val correctedGrid: Array<IntArray>,
    val solution: Array<IntArray>,
    val corrections: List<CellCorrection>,
    val correctionDescription: String,
    val isTimeoutResult: Boolean = false
)

data class CellCorrection(
    val row: Int,
    val col: Int,
    val oldValue: Int,
    val newValue: Int
)
