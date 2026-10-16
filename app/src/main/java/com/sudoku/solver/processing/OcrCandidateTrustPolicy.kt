package com.sudoku.solver.processing

/** Safety rule for accepting a uniquely solved OCR candidate with one repaired clue. */
internal object OcrCandidateTrustPolicy {
    private const val MIN_CLUES = 17
    private const val MAX_CORRECTIONS = 1
    private const val MAX_INITIAL_CONTRADICTIONS = 2

    fun isSafeCorrectedUnique(
        clueCount: Int,
        contradictionCount: Int,
        correctionCells: List<Pair<Int, Int>>,
        uncertainCells: Set<Pair<Int, Int>>
    ): Boolean {
        if (clueCount < MIN_CLUES) return false
        if (correctionCells.isEmpty() || correctionCells.size > MAX_CORRECTIONS) return false
        if (contradictionCount > MAX_INITIAL_CONTRADICTIONS) return false
        return correctionCells.all { it in uncertainCells }
    }
}
