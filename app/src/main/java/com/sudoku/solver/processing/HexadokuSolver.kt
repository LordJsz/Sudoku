package com.sudoku.solver.processing

import com.sudoku.solver.model.HexadokuGrid

enum class HexadokuSolveStatus {
    SUCCESS,
    INVALID,
    UNSOLVABLE,
    MULTIPLE_SOLUTIONS,
    TIMEOUT,
    CANCELLED
}

data class HexadokuSolveResult(
    val status: HexadokuSolveStatus,
    val solution: Array<IntArray>? = null,
    val message: String
)

/**
 * Dedicated 16x16 solver. It deliberately does not share any state or constants
 * with the existing 9x9 solver.
 */
class HexadokuSolver {
    @Volatile
    private var stopRequested = false

    private var timedOut = false
    private var solutionCount = 0
    private var firstSolution: Array<IntArray>? = null

    fun requestStop() {
        stopRequested = true
    }

    fun clearStopRequest() {
        stopRequested = false
    }

    fun solve(
        grid: HexadokuGrid,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        forbiddenValues: Map<Pair<Int, Int>, Set<Int>> = emptyMap()
    ): HexadokuSolveResult {
        clearSearchState()
        val board = grid.values.map(IntArray::clone).toTypedArray()
        val rowMasks = IntArray(SIZE)
        val columnMasks = IntArray(SIZE)
        val boxMasks = IntArray(SIZE)

        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) {
                val value = board[row][col]
                if (value == 0) continue
                if (value in forbiddenValues[row to col].orEmpty()) {
                    return failure(HexadokuSolveStatus.UNSOLVABLE, "Previously rejected symbol is still on the board")
                }
                if (value !in 1..SIZE) {
                    return failure(HexadokuSolveStatus.INVALID, "A cell contains a value outside 1-16")
                }

                val bit = bitFor(value)
                val box = boxIndex(row, col)
                if ((rowMasks[row] and bit) != 0 ||
                    (columnMasks[col] and bit) != 0 ||
                    (boxMasks[box] and bit) != 0
                ) {
                    return failure(
                        HexadokuSolveStatus.INVALID,
                        "Duplicate ${HexadokuGrid.symbolFor(value)} at row ${row + 1}, column ${col + 1}"
                    )
                }
                rowMasks[row] = rowMasks[row] or bit
                columnMasks[col] = columnMasks[col] or bit
                boxMasks[box] = boxMasks[box] or bit
            }
        }

        val deadlineNanos = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * 1_000_000L
        search(board, rowMasks, columnMasks, boxMasks, deadlineNanos, forbiddenValues)

        return when {
            stopRequested -> failure(HexadokuSolveStatus.CANCELLED, "16x16 solve cancelled")
            timedOut -> failure(HexadokuSolveStatus.TIMEOUT, "16x16 solve timed out")
            solutionCount == 0 -> failure(HexadokuSolveStatus.UNSOLVABLE, "The 16x16 puzzle has no solution")
            solutionCount > 1 -> failure(
                HexadokuSolveStatus.MULTIPLE_SOLUTIONS,
                "The 16x16 puzzle has multiple solutions; add or correct clues"
            )
            else -> HexadokuSolveResult(
                status = HexadokuSolveStatus.SUCCESS,
                solution = firstSolution,
                message = "16x16 puzzle solved"
            )
        }
    }

    private fun search(
        board: Array<IntArray>,
        rowMasks: IntArray,
        columnMasks: IntArray,
        boxMasks: IntArray,
        deadlineNanos: Long,
        forbiddenValues: Map<Pair<Int, Int>, Set<Int>>
    ) {
        if (solutionCount >= 2 || stopRequested || timedOut) return
        if (System.nanoTime() >= deadlineNanos) {
            timedOut = true
            return
        }

        var bestRow = -1
        var bestCol = -1
        var bestCandidates = 0
        var bestCount = SIZE + 1

        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) {
                if (board[row][col] != 0) continue
                val used = rowMasks[row] or columnMasks[col] or boxMasks[boxIndex(row, col)]
                var candidates = FULL_MASK and used.inv()
                forbiddenValues[row to col].orEmpty().forEach { value ->
                    if (value in 1..SIZE) candidates = candidates and bitFor(value).inv()
                }
                val candidateCount = Integer.bitCount(candidates)
                if (candidateCount == 0) return
                if (candidateCount < bestCount) {
                    bestRow = row
                    bestCol = col
                    bestCandidates = candidates
                    bestCount = candidateCount
                    if (candidateCount == 1) break
                }
            }
            if (bestCount == 1) break
        }

        if (bestRow < 0) {
            solutionCount++
            if (firstSolution == null) {
                firstSolution = board.map(IntArray::clone).toTypedArray()
            }
            return
        }

        val box = boxIndex(bestRow, bestCol)
        var candidates = bestCandidates
        while (candidates != 0 && solutionCount < 2 && !stopRequested && !timedOut) {
            val bit = candidates and -candidates
            candidates = candidates xor bit
            val value = Integer.numberOfTrailingZeros(bit) + 1

            board[bestRow][bestCol] = value
            rowMasks[bestRow] = rowMasks[bestRow] or bit
            columnMasks[bestCol] = columnMasks[bestCol] or bit
            boxMasks[box] = boxMasks[box] or bit

            search(board, rowMasks, columnMasks, boxMasks, deadlineNanos, forbiddenValues)

            board[bestRow][bestCol] = 0
            rowMasks[bestRow] = rowMasks[bestRow] xor bit
            columnMasks[bestCol] = columnMasks[bestCol] xor bit
            boxMasks[box] = boxMasks[box] xor bit
        }
    }

    private fun clearSearchState() {
        timedOut = false
        solutionCount = 0
        firstSolution = null
    }

    private fun failure(status: HexadokuSolveStatus, message: String) =
        HexadokuSolveResult(status = status, message = message)

    private fun bitFor(value: Int): Int = 1 shl (value - 1)

    private fun boxIndex(row: Int, col: Int): Int =
        (row / BOX_SIZE) * BOX_SIZE + col / BOX_SIZE

    companion object {
        private const val SIZE = HexadokuGrid.SIZE
        private const val BOX_SIZE = HexadokuGrid.BOX_SIZE
        private const val FULL_MASK = (1 shl SIZE) - 1
        private const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
