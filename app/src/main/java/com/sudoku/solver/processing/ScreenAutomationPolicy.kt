package com.sudoku.solver.processing

import java.text.Normalizer
import java.util.Locale

/** Pure matching rules for accessibility-driven screen automation. */
internal object ScreenAutomationPolicy {
    enum class AutomaticBoardMode {
        NONE,
        NINE_BY_NINE,
        SIXTEEN_BY_SIXTEEN
    }

    private const val TARGET_APP_VIEW_ID_PREFIX =
        "easy.sudoku.puzzle.solver.free:id/"
    private const val RESULT_CONTINUE_VIEW_ID =
        "${TARGET_APP_VIEW_ID_PREFIX}resultContinueBtn"
    private const val NEXT_PUZZLE_PLAY_VIEW_ID =
        "${TARGET_APP_VIEW_ID_PREFIX}startBtn"
    private const val MULTIPLIED_REWARD_BUTTON_VIEW_ID =
        "${TARGET_APP_VIEW_ID_PREFIX}watchAdBtn"
    private val hintActionViewIds = setOf(
        "${TARGET_APP_VIEW_ID_PREFIX}hintView",
        "${TARGET_APP_VIEW_ID_PREFIX}hintBtn"
    )
    private const val ERASE_ACTION_VIEW_ID =
        "${TARGET_APP_VIEW_ID_PREFIX}eraseClickArea"
    private val prizeClaimViewIds = setOf(
        "${TARGET_APP_VIEW_ID_PREFIX}collectBtn",
        "${TARGET_APP_VIEW_ID_PREFIX}claimBtn",
        "${TARGET_APP_VIEW_ID_PREFIX}tapToClaim"
    )
    private const val BOARD_INPUT_VIEW_ID =
        "${TARGET_APP_VIEW_ID_PREFIX}inputLayoutSv"
    private const val BOARD_NEW_GAME_VIEW_ID =
        "${TARGET_APP_VIEW_ID_PREFIX}newGameBtn"
    private val rewardVerbs = listOf("acumular", "accumulate", "collect")
    private val boardViewIds = setOf(
        "sudokuview",
        "sudokuboard",
        "sudokugrid",
        "gameboard",
        "gameboardview"
    )

    fun containsRewardVerb(text: CharSequence?): Boolean {
        val normalized = normalize(text)
        return rewardVerbs.any(normalized::contains)
    }

    fun isMultipliedRewardText(text: CharSequence?): Boolean {
        val normalized = normalize(text)
        if (rewardVerbs.none(normalized::contains)) return false
        val hasMultiplierMarker = normalized.contains('x') || text?.contains('×') == true
        return hasMultiplierMarker && normalized.any(Char::isDigit)
    }

    fun isMultipliedRewardLabel(text: CharSequence?): Boolean {
        val normalized = normalize(text)
        return rewardVerbs.any(normalized::contains) && normalized.contains('x')
    }

    fun isMultipliedRewardButtonViewId(viewId: String?): Boolean {
        return viewId == MULTIPLIED_REWARD_BUTTON_VIEW_ID
    }

    fun isHintActionViewId(viewId: String?): Boolean {
        return viewId in hintActionViewIds
    }

    fun isEraseActionViewId(viewId: String?): Boolean {
        return viewId == ERASE_ACTION_VIEW_ID
    }

    fun isPrizeClaimViewId(viewId: String?): Boolean {
        return viewId in prizeClaimViewIds
    }

    fun isSudokuBoardViewId(viewId: String?): Boolean {
        val idName = viewId
            ?.substringAfterLast('/')
            ?.lowercase(Locale.ROOT)
            ?: return false
        return idName in boardViewIds
    }

    fun isResultContinueViewId(viewId: String?): Boolean {
        return viewId == RESULT_CONTINUE_VIEW_ID
    }

    fun isNextPuzzlePlayViewId(viewId: String?): Boolean {
        return viewId == NEXT_PUZZLE_PLAY_VIEW_ID
    }

    fun isHexadokuLabel(text: CharSequence?): Boolean {
        val normalized = normalize(text)
        return Regex("(?<!\\d)16\\s*x\\s*16(?!\\d)").containsMatchIn(normalized)
    }

    fun automaticBoardMode(
        boardVisible: Boolean,
        hasHexadokuMarker: Boolean
    ): AutomaticBoardMode = when {
        !boardVisible -> AutomaticBoardMode.NONE
        hasHexadokuMarker -> AutomaticBoardMode.SIXTEEN_BY_SIXTEEN
        else -> AutomaticBoardMode.NINE_BY_NINE
    }

    fun hasSudokuBoardScreenMarkers(viewIds: Iterable<String?>): Boolean {
        var hasInputControls = false
        var hasNewGameControl = false
        for (viewId in viewIds) {
            when (viewId) {
                BOARD_INPUT_VIEW_ID -> hasInputControls = true
                BOARD_NEW_GAME_VIEW_ID -> hasNewGameControl = true
            }
            if (hasInputControls && hasNewGameControl) return true
        }
        return false
    }

    private fun normalize(text: CharSequence?): String {
        if (text == null) return ""
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace('×', 'x')
            .replace("\\s+".toRegex(), " ")
            .trim()
    }
}
