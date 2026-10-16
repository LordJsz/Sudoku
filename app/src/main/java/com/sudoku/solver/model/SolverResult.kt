package com.sudoku.solver.model

/**
 * Result from the Sudoku solver
 * @param success Whether the puzzle was solved successfully
 * @param solution The solved 9x9 grid (null if not solved)
 * @param contradictions List of contradictions found (if any)
 */
data class SolverResult(
    val success: Boolean,
    val solution: Array<IntArray>?,
    val contradictions: List<Contradiction>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SolverResult) return false

        if (success != other.success) return false
        if (solution != null) {
            if (other.solution == null) return false
            if (!solution.contentDeepEquals(other.solution)) return false
        } else if (other.solution != null) return false
        if (contradictions != other.contradictions) return false

        return true
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + (solution?.contentDeepHashCode() ?: 0)
        result = 31 * result + contradictions.hashCode()
        return result
    }
}
