package com.sudoku.solver.processing

import org.junit.Assert.*
import org.junit.Test

class GameInputPolicyTest {
    @Test fun detectsCompletionInProgressWithoutConfusingSettingsOrResults() {
        for (text in listOf("Autocompletando", "AUTOCOMPLETANDO…", "Autocompletando el sudoku", "Auto completing…", "Auto–Completing…", "Auto-completing puzzle")) {
            assertTrue(text, GameInputPolicy.isAutoCompleting(text))
        }
        for (text in listOf("Autocompletar", "Autocompletado", "Auto-complete", "Puzzle completed", "3 digits left", "Autocomplete is disabled")) {
            assertFalse(text, GameInputPolicy.isAutoCompleting(text))
        }
    }

    @Test fun recognizesActiveCompletionControlsButNotTheOfferOrSettings() {
        assertTrue(GameInputPolicy.isAutoCompletingViewId("${GameInputPolicy.GAME_PACKAGE}:id/quickAutoCompleteDesc"))
        assertTrue(GameInputPolicy.isAutoCompletingViewId("${GameInputPolicy.GAME_PACKAGE}:id/cancelAutoComplete"))
        assertFalse(GameInputPolicy.isAutoCompletingViewId("${GameInputPolicy.GAME_PACKAGE}:id/autoCompleteLayout"))
        assertFalse(GameInputPolicy.isAutoCompletingViewId("${GameInputPolicy.GAME_PACKAGE}:id/settingAutoComplete"))
        assertFalse(GameInputPolicy.isAutoCompletingViewId("other.app:id/quickAutoCompleteDesc"))
        assertTrue(GameInputPolicy.isResultViewId("${GameInputPolicy.GAME_PACKAGE}:id/nextGameBtnGroup"))
        assertFalse(GameInputPolicy.isResultViewId("${GameInputPolicy.GAME_PACKAGE}:id/battleGameStatusInfoView"))
    }

    @Test fun onlyUsesFasterPacingForActualBattleControls() {
        assertTrue(GameInputPolicy.isVersusViewId("${GameInputPolicy.GAME_PACKAGE}:id/battleGameStatusInfoView"))
        assertTrue(GameInputPolicy.isVersusViewId("${GameInputPolicy.GAME_PACKAGE}:id/vsImage"))
        assertFalse(GameInputPolicy.isVersusViewId("other.app:id/vsImage"))
        assertFalse(GameInputPolicy.isVersusViewId("${GameInputPolicy.GAME_PACKAGE}:id/sudokuView"))
    }

    @Test fun completionStaysPausedThroughToastDisappearanceAndBriefMissingBoard() {
        val gate = GameCompletionGate()
        assertTrue(gate.notice(GameInputPolicy.GAME_PACKAGE, listOf("Autocompletando")))
        assertFalse(gate.notice(GameInputPolicy.GAME_PACKAGE, listOf("Autocompletando")))
        assertTrue(gate.waiting)
        gate.observeBoard(GameInputPolicy.GAME_PACKAGE, false, 100L)
        gate.observeBoard(GameInputPolicy.GAME_PACKAGE, true, 600L)
        gate.observeBoard(GameInputPolicy.GAME_PACKAGE, false, 800L)
        gate.observeBoard("com.sudoku.solver", false, 3_000L)
        assertTrue(gate.waiting)
        gate.observeBoard(GameInputPolicy.GAME_PACKAGE, false, 1_500L)
        assertTrue(gate.waiting)
        gate.observeBoard(GameInputPolicy.GAME_PACKAGE, false, 2_001L)
        assertFalse(gate.waiting)
        gate.ensureInputAllowed()
    }

    @Test fun unrelatedNotificationCannotPauseSolver() {
        val gate = GameCompletionGate()
        assertFalse(gate.notice("other.app", listOf("Autocompletando")))
        assertFalse(gate.waiting)
    }

    @Test(expected = GameAutoCompletionException::class)
    fun completionImmediatelyBlocksFurtherInput() {
        val gate = GameCompletionGate()
        gate.notice(GameInputPolicy.GAME_PACKAGE, listOf("Autocompletando"))
        gate.ensureInputAllowed()
    }
}
