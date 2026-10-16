package com.sudoku.solver.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAutomationPolicyTest {
    @Test
    fun `localized multiplied reward is accepted`() {
        assertTrue(ScreenAutomationPolicy.isMultipliedRewardText("Acumular × 4"))
        assertTrue(ScreenAutomationPolicy.isMultipliedRewardText("Accumulate x10"))
        assertTrue(ScreenAutomationPolicy.isMultipliedRewardLabel("Acumular ×"))
    }

    @Test
    fun `plain reward button is not auto clicked`() {
        assertFalse(ScreenAutomationPolicy.isMultipliedRewardText("Acumular"))
        assertFalse(ScreenAutomationPolicy.isMultipliedRewardText("Continue"))
        assertFalse(ScreenAutomationPolicy.isMultipliedRewardLabel("Acumular"))
    }

    @Test
    fun `multiplied reward button is recognized only in the target app`() {
        assertTrue(
            ScreenAutomationPolicy.isMultipliedRewardButtonViewId(
                "easy.sudoku.puzzle.solver.free:id/watchAdBtn"
            )
        )
        assertFalse(
            ScreenAutomationPolicy.isMultipliedRewardButtonViewId(
                "easy.sudoku.puzzle.solver.free:id/collectBtn"
            )
        )
        assertFalse(
            ScreenAutomationPolicy.isMultipliedRewardButtonViewId(
                "another.app:id/watchAdBtn"
            )
        )
    }

    @Test
    fun `hint action is recognized only in the target app`() {
        assertTrue(
            ScreenAutomationPolicy.isHintActionViewId(
                "easy.sudoku.puzzle.solver.free:id/hintView"
            )
        )
        assertTrue(
            ScreenAutomationPolicy.isHintActionViewId(
                "easy.sudoku.puzzle.solver.free:id/hintBtn"
            )
        )
        assertFalse(
            ScreenAutomationPolicy.isHintActionViewId(
                "another.app:id/hintView"
            )
        )
    }

    @Test
    fun `prize claim controls are recognized only in the target app`() {
        assertTrue(
            ScreenAutomationPolicy.isPrizeClaimViewId(
                "easy.sudoku.puzzle.solver.free:id/collectBtn"
            )
        )
        assertTrue(
            ScreenAutomationPolicy.isPrizeClaimViewId(
                "easy.sudoku.puzzle.solver.free:id/claimBtn"
            )
        )
        assertTrue(
            ScreenAutomationPolicy.isPrizeClaimViewId(
                "easy.sudoku.puzzle.solver.free:id/tapToClaim"
            )
        )
        assertFalse(
            ScreenAutomationPolicy.isPrizeClaimViewId(
                "another.app:id/collectBtn"
            )
        )
    }

    @Test
    fun `known board view identifiers are recognized narrowly`() {
        assertTrue(
            ScreenAutomationPolicy.isSudokuBoardViewId(
                "easy.sudoku.puzzle.solver.free:id/sudokuView"
            )
        )
        assertFalse(
            ScreenAutomationPolicy.isSudokuBoardViewId(
                "easy.sudoku.puzzle.solver.free:id/sudokuFunctionMenu"
            )
        )
    }

    @Test
    fun `result continue control is recognized only in the target app`() {
        assertTrue(
            ScreenAutomationPolicy.isResultContinueViewId(
                "easy.sudoku.puzzle.solver.free:id/resultContinueBtn"
            )
        )
        assertFalse(
            ScreenAutomationPolicy.isResultContinueViewId(
                "another.app:id/resultContinueBtn"
            )
        )
        assertFalse(
            ScreenAutomationPolicy.isResultContinueViewId(
                "easy.sudoku.puzzle.solver.free:id/shareArea"
            )
        )
    }

    @Test
    fun `next puzzle play control is recognized only in the target app`() {
        assertTrue(
            ScreenAutomationPolicy.isNextPuzzlePlayViewId(
                "easy.sudoku.puzzle.solver.free:id/startBtn"
            )
        )
        assertFalse(
            ScreenAutomationPolicy.isNextPuzzlePlayViewId(
                "another.app:id/startBtn"
            )
        )
        assertFalse(
            ScreenAutomationPolicy.isNextPuzzlePlayViewId(
                "easy.sudoku.puzzle.solver.free:id/tournamentScoreDiffGroup"
            )
        )
    }

    @Test
    fun `16x16 labels are recognized without numeric substring false positives`() {
        assertTrue(ScreenAutomationPolicy.isHexadokuLabel("16×16"))
        assertTrue(ScreenAutomationPolicy.isHexadokuLabel("Continue 06:23 - 16x16"))
        assertFalse(ScreenAutomationPolicy.isHexadokuLabel("9×9"))
        assertFalse(ScreenAutomationPolicy.isHexadokuLabel("116x160"))
    }

    @Test
    fun `visible 16x16 board selects the hexadoku solver`() {
        assertTrue(
            ScreenAutomationPolicy.automaticBoardMode(
                boardVisible = true,
                hasHexadokuMarker = true
            ) == ScreenAutomationPolicy.AutomaticBoardMode.SIXTEEN_BY_SIXTEEN
        )
    }

    @Test
    fun `visible board without 16x16 marker keeps the normal solver`() {
        assertTrue(
            ScreenAutomationPolicy.automaticBoardMode(
                boardVisible = true,
                hasHexadokuMarker = false
            ) == ScreenAutomationPolicy.AutomaticBoardMode.NINE_BY_NINE
        )
    }

    @Test
    fun `hexadoku label outside a board does not start a solver`() {
        assertTrue(
            ScreenAutomationPolicy.automaticBoardMode(
                boardVisible = false,
                hasHexadokuMarker = true
            ) == ScreenAutomationPolicy.AutomaticBoardMode.NONE
        )
    }

    @Test
    fun `board screen fallback requires two target app markers`() {
        assertTrue(
            ScreenAutomationPolicy.hasSudokuBoardScreenMarkers(
                listOf(
                    "easy.sudoku.puzzle.solver.free:id/inputLayoutSv",
                    "easy.sudoku.puzzle.solver.free:id/newGameBtn"
                )
            )
        )
        assertFalse(
            ScreenAutomationPolicy.hasSudokuBoardScreenMarkers(
                listOf("easy.sudoku.puzzle.solver.free:id/inputLayoutSv")
            )
        )
        assertFalse(
            ScreenAutomationPolicy.hasSudokuBoardScreenMarkers(
                listOf(
                    "another.app:id/inputLayoutSv",
                    "another.app:id/newGameBtn"
                )
            )
        )
    }
}
