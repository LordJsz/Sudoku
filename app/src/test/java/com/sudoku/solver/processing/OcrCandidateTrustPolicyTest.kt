package com.sudoku.solver.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCandidateTrustPolicyTest {
    @Test
    fun `accepts live 42 clue unique result with one flagged 6 or 9 repair`() {
        assertTrue(
            OcrCandidateTrustPolicy.isSafeCorrectedUnique(
                clueCount = 42,
                contradictionCount = 2,
                correctionCells = listOf(Pair(7, 8)),
                uncertainCells = setOf(Pair(7, 8), Pair(0, 3), Pair(1, 1))
            )
        )
    }

    @Test
    fun `rejects correction outside OCR uncertainty set`() {
        assertFalse(
            OcrCandidateTrustPolicy.isSafeCorrectedUnique(
                clueCount = 42,
                contradictionCount = 2,
                correctionCells = listOf(Pair(7, 8)),
                uncertainCells = setOf(Pair(0, 3), Pair(1, 1))
            )
        )
    }

    @Test
    fun `rejects multiple repairs or noisy contradictory candidates`() {
        assertFalse(
            OcrCandidateTrustPolicy.isSafeCorrectedUnique(
                clueCount = 42,
                contradictionCount = 2,
                correctionCells = listOf(Pair(7, 8), Pair(8, 8)),
                uncertainCells = setOf(Pair(7, 8), Pair(8, 8))
            )
        )
        assertFalse(
            OcrCandidateTrustPolicy.isSafeCorrectedUnique(
                clueCount = 42,
                contradictionCount = 3,
                correctionCells = listOf(Pair(7, 8)),
                uncertainCells = setOf(Pair(7, 8))
            )
        )
    }
}
