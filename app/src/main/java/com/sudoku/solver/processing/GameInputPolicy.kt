package com.sudoku.solver.processing

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException

internal object GameInputPolicy {
    const val GAME_PACKAGE = "easy.sudoku.puzzle.solver.free"
    const val VS_GESTURE_MS = 70L
    const val VS_CELL_SETTLE_MS = 55L
    const val VS_DIGIT_SETTLE_MS = 45L
    const val VS_ERROR_SETTLE_MS = 35L
    val autoCompletingViewIds = setOf(
        "$GAME_PACKAGE:id/quickAutoCompleteLayout", "$GAME_PACKAGE:id/quickAutoCompleteDesc",
        "$GAME_PACKAGE:id/cancelAutoComplete"
    )

    fun isAutoCompletingViewId(viewId: String?): Boolean = viewId in autoCompletingViewIds

    val resultViewIds = setOf(
        "$GAME_PACKAGE:id/resultContinueBtn", "$GAME_PACKAGE:id/nextGameBtnGroup",
        "$GAME_PACKAGE:id/battleWinrateResultStats", "$GAME_PACKAGE:id/startBtn"
    )
    fun isResultViewId(viewId: String?): Boolean = viewId in resultViewIds

    fun isAutoCompleting(text: CharSequence?): Boolean {
        val normalized = Normalizer.normalize(text?.toString().orEmpty(), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "").replace('–', '-').replace('—', '-').lowercase(Locale.ROOT)
        return Regex("\\bauto\\s*completando\\b|\\bauto[ -]?completing\\b").containsMatchIn(normalized)
    }

    fun isVersusViewId(viewId: String?): Boolean = viewId in setOf(
        "$GAME_PACKAGE:id/battleGameStatusInfoView", "$GAME_PACKAGE:id/battleScoreLayout",
        "$GAME_PACKAGE:id/vsImage"
    )
}

/** Keep the same board paused even after its short completion toast disappears. */
internal class GameCompletionGate {
    @Volatile var waiting = false
        private set
    private var boardLeftAt: Long? = null

    @Synchronized fun notice(packageName: String?, texts: Iterable<CharSequence?>): Boolean {
        if (packageName != GameInputPolicy.GAME_PACKAGE || !texts.any(GameInputPolicy::isAutoCompleting)) return false
        val firstNotice = !waiting
        waiting = true
        boardLeftAt = null
        return firstNotice
    }

    @Synchronized fun observeBoard(packageName: String?, boardVisible: Boolean, nowMs: Long) {
        if (!waiting || packageName != GameInputPolicy.GAME_PACKAGE) return
        if (boardVisible) { boardLeftAt = null; return }
        val leftAt = boardLeftAt
        if (leftAt == null) boardLeftAt = nowMs
        else if (nowMs - leftAt >= 1_200L) { waiting = false; boardLeftAt = null }
    }

    fun ensureInputAllowed() {
        if (waiting) throw GameAutoCompletionException()
    }
}

internal class GameAutoCompletionException : CancellationException("Game is auto-completing the puzzle")
