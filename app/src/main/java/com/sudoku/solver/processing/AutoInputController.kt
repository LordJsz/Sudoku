package com.sudoku.solver.processing

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.sudoku.solver.model.SudokuGrid
import com.sudoku.solver.service.ScreenCaptureService
import com.sudoku.solver.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Result of auto-input operation
 */
data class InputResult(
    val success: Boolean,
    val errorCell: Triple<Int, Int, Int>? = null,  // (row, col, wrongDigit) - first error found
    val lastInputRow: Int = 0,
    val lastInputCol: Int = 0,
    val inputCount: Int = 0,
    val selectionFailed: Boolean = false,
    val failureMessage: String? = null,
    val battleMode: Boolean = false,
    val entryMissing: Boolean = false,
    val gameEnded: Boolean = false
)

/**
 * Controls automatic input of Sudoku solution via accessibility gestures
 */
class AutoInputController(
    private val accessibilityService: AccessibilityService,
    private val beforeInput: () -> Unit = {}
) {
    // Live-benchmarked on the Galaxy A07; other devices retain their verified timings.
    private val samsungA07 = Build.MODEL.startsWith("SM-A075", ignoreCase = true)
    private val huaweiJadLx9 = Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) &&
        Build.MODEL.equals("JAD-LX9", ignoreCase = true)
    @Volatile
    private var stopRequested = false
    private var activeBoardBounds: Rect? = null
    private var activeScreenWidth = 0
    private var activeScreenHeight = 0
    private var remainingDigitDemand = IntArray(10)
    private var lastLoggedMissingRequiredDigits: Set<Int> = emptySet()
    private var lastLoggedHiddenCompletedDigits: Set<Int> = emptySet()
    private var versusMode = false
    private var versusExtraSettleMs = 0L
    private var battleScore: Int? = null
    private var adaptivePacing: AdaptiveInputPacing? = null
    private data class PendingBattleEntry(val row: Int, val col: Int, val digit: Int, val bounds: Rect)
    private var gestureDurationMs = Constants.GESTURE_DURATION_MS
    private var lastCompletionCheckAtMs = 0L

    private suspend fun ensureNotStopped() {
        val nowMs = SystemClock.elapsedRealtime()
        val completionCheckInterval = if (versusMode && huaweiJadLx9)
            HUAWEI_BATTLE_COMPLETION_CHECK_INTERVAL_MS else COMPLETION_CHECK_INTERVAL_MS
        if ((versusMode && !huaweiJadLx9) || nowMs - lastCompletionCheckAtMs >= completionCheckInterval) {
            lastCompletionCheckAtMs = nowMs
            beforeInput()
        }
        currentCoroutineContext().ensureActive()
        if (stopRequested) {
            throw CancellationException("Auto-input stopped by user")
        }
    }

    fun requestStop() {
        stopRequested = true
    }

    fun clearStopRequest() {
        stopRequested = false
    }

    private fun createAdaptivePacing(): AdaptiveInputPacing {
        val mode = if (versusMode) InputPacingMode.BATTLE_9 else InputPacingMode.SOLO_9
        val initial = if (versusMode) {
            if (huaweiJadLx9) InputPace(
                HUAWEI_BATTLE_CELL_SETTLE_MS,
                HUAWEI_BATTLE_DIGIT_SETTLE_MS,
                HUAWEI_JAD_GESTURE_DURATION_MS
            ) else InputPace(
                GameInputPolicy.VS_CELL_SETTLE_MS,
                GameInputPolicy.VS_DIGIT_SETTLE_MS,
                GameInputPolicy.VS_GESTURE_MS
            )
        } else when {
            samsungA07 -> InputPace(
                SAMSUNG_A07_TAP_DELAY_MS, SAMSUNG_A07_DIGIT_INPUT_DELAY_MS,
                SAMSUNG_A07_GESTURE_DURATION_MS
            )
            huaweiJadLx9 -> InputPace(
                HUAWEI_JAD_TAP_DELAY_MS, HUAWEI_JAD_DIGIT_INPUT_DELAY_MS,
                HUAWEI_JAD_GESTURE_DURATION_MS
            )
            else -> InputPace(FAST_TAP_DELAY_MS, FAST_DIGIT_INPUT_DELAY_MS,
                FAST_GESTURE_DURATION_MS)
        }
        return AdaptiveInputPacing(accessibilityService, mode, initial, maximumSpeed = true,
            maximumSpeedPace = if (huaweiJadLx9) InputPace.HUAWEI_FAST else InputPace(0, 0, 1),
            fixedMaximumSpeed = huaweiJadLx9)
    }

    /**
     * Checks whether a board cell is still showing the game's red wrong-entry state.
     * This is used after PISTA is selected so auto-input only resumes once the hint
     * has replaced the bad digit.
     */
    suspend fun isCellMarkedWrong(
        row: Int,
        col: Int,
        boardBounds: Rect
    ): Boolean {
        require(row in 0..8 && col in 0..8) { "Cell must be inside a 9x9 board" }
        val cellWidth = boardBounds.width() / 9f
        val cellHeight = boardBounds.height() / 9f
        val cellBounds = Rect(
            (boardBounds.left + col * cellWidth).toInt(),
            (boardBounds.top + row * cellHeight).toInt(),
            (boardBounds.left + (col + 1) * cellWidth).toInt(),
            (boardBounds.top + (row + 1) * cellHeight).toInt()
        )
        return detectErrorInCell(cellBounds)
    }

    /**
     * Input the complete solution to the Sudoku board.
     * Flow: For each empty cell, tap the cell first to select it, then tap the digit.
     * Stops immediately on first error and returns info for retry.
     */
    suspend fun inputSolution(
        original: SudokuGrid,
        solution: Array<IntArray>,
        boardBounds: Rect,
        checkForErrors: Boolean = true
    ): InputResult {
        val cellWidth = boardBounds.width() / 9f
        val cellHeight = boardBounds.height() / 9f
        var inputCount = 0
        val pendingBattleEntries = mutableListOf<PendingBattleEntry>()
        val inputStartedAt = SystemClock.elapsedRealtime()
        versusMode = readIsVersusMode()
        battleScore = if (versusMode) readBattleScore() else null
        versusExtraSettleMs = 0L
        gestureDurationMs = Constants.GESTURE_DURATION_MS
        lastCompletionCheckAtMs = 0L
        Log.i(TAG, "Input pacing: ${if (versusMode) "VS with entry confirmation" else "standard"}; battle score=$battleScore")

        // Cache must be rebuilt for each run because keypad positions can change
        // between retries/puzzles/apps and stale taps can input wrong digits.
        if (digitButtonCache.isNotEmpty()) {
            Log.i(TAG, "Starting new input run: clearing ${digitButtonCache.size} cached digit button(s)")
        }
        clearCache()
        resetDigitDemand(original, solution)
        activeBoardBounds = Rect(boardBounds)
        val captureDimensions = ScreenCaptureService.instance?.captureDimensions()
        activeScreenWidth = captureDimensions?.first
            ?: maxOf(accessibilityService.resources.displayMetrics.widthPixels, boardBounds.right)
        activeScreenHeight = captureDimensions?.second
            ?: maxOf(accessibilityService.resources.displayMetrics.heightPixels, boardBounds.bottom)
        val initialVisibleErrorCount = readVisibleErrorCount()
        // A single preflight capture protects cells that OCR missed. Capturing the
        // entire screen again for every cell added at least 200 ms per input.
        val visuallyFilledAtStart = captureVisuallyFilledCells(original, boardBounds)
        if (huaweiJadLx9 && !versusMode && readIsVersusMode()) {
            versusMode = true
            battleScore = readBattleScore()
            Log.i(TAG, "Battle markers appeared after preflight; score=$battleScore")
        }
        adaptivePacing = createAdaptivePacing()
        Log.i(TAG, "Input pace: ${requireNotNull(adaptivePacing).pace}")
        Log.i(TAG, "Visible error counter at input start: $initialVisibleErrorCount")
        Log.i(TAG, "Preflight found ${visuallyFilledAtStart.count { it }} visually filled OCR target(s)")

        try {
            // Iterate through all cells row by row
            for (row in 0..8) {
                for (col in 0..8) {
                    ensureNotStopped()
                    if (huaweiJadLx9 && !versusMode && inputCount < 3) {
                        versusMode = readIsVersusMode()
                        if (versusMode) {
                            battleScore = readBattleScore()
                            adaptivePacing = createAdaptivePacing()
                            Log.i(TAG, "Battle board detected during input")
                        }
                    }

                    // Never type over any recognized value. Blue/player-entered
                    // digits may intentionally be unlocked for OCR recovery, but
                    // they still occupy the game cell and must not be tapped as empty.
                    if (original.locked[row][col] || original.values[row][col] != 0) continue

                    val digit = solution[row][col]
                    if (digit == 0) continue // Skip if no solution value

                    // Step 1: Tap the cell to select it
                    val cellX = (boardBounds.left + (col + 0.5f) * cellWidth).toInt()
                    val cellY = (boardBounds.top + (row + 0.5f) * cellHeight).toInt()
                    val cellBounds = Rect(
                        (boardBounds.left + col * cellWidth).toInt(),
                        (boardBounds.top + row * cellHeight).toInt(),
                        (boardBounds.left + (col + 1) * cellWidth).toInt(),
                        (boardBounds.top + (row + 1) * cellHeight).toInt()
                    )

                    // Final screen-level guard for clues or previous entries that
                    // OCR dropped. This prevents the keypad receiving a digit when
                    // the game has no empty cell selected.
                    if (visuallyFilledAtStart[row * 9 + col]) {
                        Log.w(TAG, "Skipping visually filled target cell ($row,$col)")
                        markDigitHandled(digit)
                        continue
                    }
                    val isWarmup = inputCount < WARMUP_INPUTS
                    val tapDelayMs = if (isWarmup) {
                        Constants.TAP_DELAY_MS
                    } else {
                        requireNotNull(adaptivePacing).pace.cellSettleMs + versusExtraSettleMs
                    }
                    gestureDurationMs = if (isWarmup) Constants.GESTURE_DURATION_MS
                        else requireNotNull(adaptivePacing).pace.gestureMs

                    Log.w(TAG, "Tapping cell ($row,$col) at screen ($cellX,$cellY)")
                    if (!performTap(cellX, cellY)) {
                        gestureDurationMs = Constants.GESTURE_DURATION_MS
                        delay(RETRY_DIGIT_SETTLE_DELAY_MS)
                        if (!performTap(cellX, cellY)) return InputResult(success = false,
                            lastInputRow = row, lastInputCol = col, inputCount = inputCount,
                            selectionFailed = true, failureMessage = "Cell tap was rejected")
                    }
                    ensureNotStopped()
                    delay(tapDelayMs)
                    ensureNotStopped()
                    if (digitButtonCache.isEmpty()) {
                        // Load every currently needed key in one accessibility-tree
                        // pass. Subsequent cells use direct cached coordinates.
                        preloadDigitButtonCache()
                    } else if (!digitButtonCache.containsKey(digit)) {
                        preloadDigitButtonCache(requiredDigit = digit)
                    }

                    // Step 2: Tap the digit on the number pad
                    ensureNotStopped()
                    Log.w(TAG, "Entering digit $digit for cell ($row,$col)")
                    val hadCachedDigitButton = digitButtonCache.containsKey(digit)
                    val enteredDigit = digit
                    var selected = selectDigit(enteredDigit)
                    if (!selected) {
                        // Cached/button tree can go stale after retries or game transitions.
                        // Clear cache and retry once before giving up.
                        clearCache()
                        ensureNotStopped()
                        delay(RETRY_DIGIT_SETTLE_DELAY_MS)
                        ensureNotStopped()
                        preloadDigitButtonCache(requiredDigit = digit)
                        selected = selectDigit(digit)
                    }
                    if (!selected && digitButtonCache.isEmpty()) {
                        // If no digit buttons are visible at all, keypad is likely collapsed/hidden.
                        // Re-tap the active cell to reopen keypad and try one final time.
                        Log.w(
                            TAG,
                            "Digit keypad appears hidden (cache=0/9); re-tapping cell ($row,$col) to recover."
                        )
                        performTap(cellX, cellY)
                        ensureNotStopped()
                        delay(KEYPAD_RECOVERY_TAP_DELAY_MS)
                        ensureNotStopped()
                        preloadDigitButtonCache(requiredDigit = digit)
                        selected = selectDigit(digit)
                    }
                    if (!selected) {
                        val noDigitButtonsVisible = digitButtonCache.isEmpty()
                        if (noDigitButtonsVisible) {
                            Log.e(
                                TAG,
                                "Failed to select digit $digit for cell ($row,$col): keypad not visible (cache=0/9)"
                            )
                            return InputResult(
                                success = false,
                                errorCell = null,
                                lastInputRow = row,
                                lastInputCol = col,
                                inputCount = inputCount,
                                selectionFailed = true,
                                failureMessage = "Digit keypad not visible"
                            )
                        }

                        if (cellAppearsFilled(cellBounds)) {
                            val hasCellError = if (checkForErrors) {
                                ensureNotStopped()
                                detectErrorInCell(cellBounds)
                            } else {
                                false
                            }
                            if (!hasCellError) {
                                Log.w(
                                    TAG,
                                    "Digit $digit unavailable for cell ($row,$col), but cell appears already filled. " +
                                        "Likely completed-digit keypad hiding; skipping this cell."
                                )
                                markDigitHandled(digit)
                                continue
                            }
                            Log.w(
                                TAG,
                                "Digit $digit unavailable for cell ($row,$col) and cell appears filled, " +
                                    "but it is in error state; refusing to skip."
                            )
                        }
                        Log.e(TAG, "Failed to select digit $digit for cell ($row,$col) - aborting input run")
                            return InputResult(
                                success = false,
                                errorCell = null,
                                lastInputRow = row,
                                lastInputCol = col,
                                inputCount = inputCount,
                                selectionFailed = true,
                                failureMessage = "Could not find digit button $digit (possibly hidden or cell state mismatch)"
                            )
                    }
                    ensureNotStopped()
                    val digitInputDelayMs = if (isWarmup || (!huaweiJadLx9 && !hadCachedDigitButton)) {
                        Constants.DIGIT_INPUT_DELAY_MS
                    } else {
                        requireNotNull(adaptivePacing).pace.digitSettleMs + versusExtraSettleMs
                    }
                    delay(digitInputDelayMs)

                    var confirmedEntry: EntryConfirmation? = null
                    if (versusMode && !huaweiJadLx9) {
                            confirmedEntry = VerifiedDigitEntry.confirm(
                            read = {
                                ensureNotStopped()
                                var state = readCellEntryState(cellBounds)
                                if (state == CellEntryState.WRONG) {
                                    delay(120L)
                                    ensureNotStopped()
                                    state = readCellEntryState(cellBounds)
                                }
                                state.also { ensureNotStopped() }
                            },
                            retry = {
                                // The cell stayed empty across fresh frames. Re-enter it
                                // slowly and keep more settling time for the rest of this run.
                                versusExtraSettleMs = (versusExtraSettleMs + 30L).coerceAtMost(120L)
                                gestureDurationMs = Constants.GESTURE_DURATION_MS
                                ensureNotStopped()
                                if (!performTap(cellX, cellY)) false else {
                                    delay(Constants.TAP_DELAY_MS)
                                    val retried = selectDigit(digit)
                                    delay(Constants.DIGIT_INPUT_DELAY_MS)
                                    retried
                                }
                            }
                            )
                        if (confirmedEntry == EntryConfirmation.UNCONFIRMED) {
                            return InputResult(success = false, lastInputRow = row, lastInputCol = col,
                                inputCount = inputCount,
                                failureMessage = "Digit $digit was not confirmed at row ${row + 1}, column ${col + 1}. Stopped before advancing.")
                        }
                    }
                    inputCount++
                    markDigitHandled(digit)
                    if (checkForErrors && (!versusMode || huaweiJadLx9)) {
                        pendingBattleEntries += PendingBattleEntry(row, col, enteredDigit, cellBounds)
                        val verifyBatchSize = if (versusMode) HUAWEI_BATTLE_VERIFY_BATCH_SIZE
                            else SOLO_VERIFY_BATCH_SIZE
                        if (pendingBattleEntries.size >= verifyBatchSize) {
                            verifyBattleEntries(pendingBattleEntries)?.let { return it }
                            adaptivePacing?.verified(pendingBattleEntries.size)
                            pendingBattleEntries.clear()
                        }
                    } else if (confirmedEntry == EntryConfirmation.CONFIRMED) {
                        adaptivePacing?.verified(1)
                    }

                    // Step 3: Check if game shows error for this cell
                    if (checkForErrors && !huaweiJadLx9) {
                        ensureNotStopped()
                        val errorCheckDelayMs = if (isWarmup || !hadCachedDigitButton) {
                            ERROR_CHECK_DELAY_MS
                        } else if (versusMode) {
                            GameInputPolicy.VS_ERROR_SETTLE_MS
                        } else {
                            when {
                                samsungA07 -> SAMSUNG_A07_ERROR_CHECK_DELAY_MS
                                huaweiJadLx9 -> HUAWEI_JAD_ERROR_CHECK_DELAY_MS
                                else -> FAST_ERROR_CHECK_DELAY_MS
                            }
                        }
                        delay(errorCheckDelayMs) // Wait for game UI to update
                        if (isResultVisible()) {
                            Log.i(TAG, "Game result appeared after entering ($row,$col)")
                            return InputResult(success = false, gameEnded = true, inputCount = inputCount)
                        }
                        val currentVisibleErrorCount = readVisibleErrorCount()
                        val visibleCounterIncreased =
                            initialVisibleErrorCount != null &&
                                currentVisibleErrorCount != null &&
                                currentVisibleErrorCount > initialVisibleErrorCount
                        if (visibleCounterIncreased) {
                            Log.e(
                                TAG,
                                "Visible error counter increased: " +
                                    "$initialVisibleErrorCount -> $currentVisibleErrorCount"
                            )
                        }
                        // When the game exposes its error counter, it is both faster
                        // and more reliable than taking a new screenshot after every
                        // digit. Pixel detection remains the fallback for games that
                        // do not expose a counter at the start of the run.
                        val pixelErrorDetected =
                            if (versusMode) confirmedEntry == EntryConfirmation.WRONG
                            else initialVisibleErrorCount == null && detectErrorInCell(cellBounds)
                        if (visibleCounterIncreased || pixelErrorDetected) {
                            adaptivePacing?.wrongValueDetected("red digit")
                            Log.e(TAG, "ERROR detected in cell ($row,$col) for digit $digit - STOPPING")
                            // Stop immediately on first error
                            return InputResult(
                                success = false,
                                errorCell = Triple(row, col, enteredDigit),
                                lastInputRow = row,
                                lastInputCol = col,
                                inputCount = inputCount,
                                battleMode = versusMode
                            )
                        }
                    }
                }
            }

            if (pendingBattleEntries.isNotEmpty()) {
                verifyBattleEntries(pendingBattleEntries)?.let { return it }
                adaptivePacing?.verified(pendingBattleEntries.size)
            }

            val elapsedMs = SystemClock.elapsedRealtime() - inputStartedAt
            Log.w(
                TAG,
                "Auto-input complete: $inputCount cells filled in ${elapsedMs}ms, no errors detected"
            )
            return InputResult(
                success = true,
                errorCell = null,
                lastInputRow = 8,
                lastInputCol = 8,
                inputCount = inputCount
            )
        } finally {
            adaptivePacing = null
            activeBoardBounds = null
            activeScreenWidth = 0
            activeScreenHeight = 0
            versusMode = false
            battleScore = null
            gestureDurationMs = Constants.GESTURE_DURATION_MS
        }
    }

    private suspend fun verifyBattleEntries(entries: List<PendingBattleEntry>): InputResult? =
        withContext(Dispatchers.IO) {
            if (isResultVisible()) return@withContext InputResult(success = false, gameEnded = true)
            val captureBounds = if (huaweiJadLx9) activeBoardBounds else null
            val screenshot = (if (captureBounds != null)
                ScreenCaptureService.instance?.captureRegion(captureBounds, freshFrameWaitMs = 32L)
            else ScreenCaptureService.instance?.captureScreen())
                ?: return@withContext InputResult(success = false,
                    failureMessage = "Could not verify entered digits")
            try {
                for (entry in entries) {
                    ensureNotStopped()
                    val bounds = entry.bounds
                    val cell = Bitmap.createBitmap(screenshot,
                        bounds.left - (captureBounds?.left ?: 0),
                        bounds.top - (captureBounds?.top ?: 0),
                        bounds.width(), bounds.height())
                    try {
                        if (checkForRedColor(cell)) {
                            adaptivePacing?.wrongValueDetected("red digit")
                            Log.e(TAG, "Batalla error at (${entry.row},${entry.col})")
                            return@withContext InputResult(success = false,
                                errorCell = Triple(entry.row, entry.col, entry.digit),
                                lastInputRow = entry.row, lastInputCol = entry.col,
                                battleMode = versusMode)
                        }
                        if (!checkForDigitInk(cell)) {
                            if (isResultVisible()) {
                                Log.i(TAG, "Game result appeared during input verification")
                                return@withContext InputResult(success = false, gameEnded = true)
                            }
                            adaptivePacing?.rejected("missing digit")
                            Log.e(TAG, "Batalla entry missing at (${entry.row},${entry.col})")
                            return@withContext InputResult(success = false,
                                errorCell = Triple(entry.row, entry.col, entry.digit),
                                lastInputRow = entry.row, lastInputCol = entry.col,
                                battleMode = versusMode, entryMissing = true)
                        }
                    } finally { cell.recycle() }
                }
                Log.i(TAG, "Verified ${entries.size} input entries in one capture")
                null
            } finally { screenshot.recycle() }
        }

    private fun isResultVisible(): Boolean {
        val root = accessibilityService.rootInActiveWindow ?: return false
        return try {
            if (root.packageName?.toString() != GameInputPolicy.GAME_PACKAGE) return false
            GameInputPolicy.resultViewIds.any { id ->
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                try { nodes.any { it.isVisibleToUser } }
                finally { nodes.forEach { it.recycle() } }
            }
        } finally { root.recycle() }
    }

    private fun readBattleScore(): Int? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        accessibilityService.rootInActiveWindow?.let(roots::add)
        accessibilityService.windows.forEach { window -> window.root?.let(roots::add) }
        val rootPackages = roots.map { it.packageName?.toString() }
        var score: Int? = null
        try {
            for (root in roots) {
                if (root.packageName?.toString() != GameInputPolicy.GAME_PACKAGE) continue
                val nodes = root.findAccessibilityNodeInfosByViewId(
                    "${GameInputPolicy.GAME_PACKAGE}:id/scoreText2"
                )
                try {
                    score = nodes.firstNotNullOfOrNull { it.text?.toString()?.trim()?.toIntOrNull() }
                } finally {
                    nodes.forEach(AccessibilityNodeInfo::recycle)
                }
                if (score != null) break
            }
        } finally {
            roots.forEach(AccessibilityNodeInfo::recycle)
        }
        if (score == null) {
            Log.d(TAG, "Battle score unavailable in roots: $rootPackages")
        }
        return score
    }

    private fun readIsVersusMode(): Boolean {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        accessibilityService.rootInActiveWindow?.let(roots::add)
        accessibilityService.windows.forEach { window -> window.root?.let(roots::add) }
        fun hasVersusMarker(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && GameInputPolicy.isVersusViewId(node.viewIdResourceName)) return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val found = try { hasVersusMarker(child) } finally { child.recycle() }
                if (found) return true
            }
            return false
        }
        return try {
            roots.any { root -> root.packageName?.toString() == GameInputPolicy.GAME_PACKAGE &&
                hasVersusMarker(root) }
        } finally { roots.forEach(AccessibilityNodeInfo::recycle) }
    }

    private suspend fun readCellEntryState(bounds: Rect): CellEntryState? = withContext(Dispatchers.IO) {
        val bitmap = ScreenCaptureService.instance?.captureRegion(bounds) ?: return@withContext null
        try {
            when {
                checkForRedColor(bitmap) -> CellEntryState.WRONG
                checkForDigitInk(bitmap) -> CellEntryState.FILLED
                else -> CellEntryState.EMPTY
            }
        } finally { bitmap.recycle() }
    }

    /**
     * Detect if a cell shows an error indicator (red color, etc.)
     */
    private suspend fun detectErrorInCell(cellBounds: Rect): Boolean {
        ensureNotStopped()
        val first = detectErrorInCellOnce(cellBounds)
        if (!first) return false

        // Confirm red state once more to avoid transient false positives from animations/highlights.
        ensureNotStopped()
        delay(120)
        ensureNotStopped()
        return detectErrorInCellOnce(cellBounds)
    }

    private fun readVisibleErrorCount(): Int? {
        val root = accessibilityService.rootInActiveWindow ?: return null
        return try {
            val visibleTexts = mutableListOf<String>()
            collectVisibleTexts(root, visibleTexts)
            VisibleErrorCounter.parse(visibleTexts.joinToString(" "))
        } finally {
            root.recycle()
        }
    }

    private fun collectVisibleTexts(node: AccessibilityNodeInfo, output: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(output::add)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(output::add)

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                collectVisibleTexts(child, output)
            } finally {
                child.recycle()
            }
        }
    }

    private suspend fun detectErrorInCellOnce(cellBounds: Rect): Boolean {
        val captureService = ScreenCaptureService.instance ?: return false
        return withContext(Dispatchers.IO) {
            val screenshot = captureService.captureScreen() ?: return@withContext false
            try {
                // Extract the cell region
                val cellWidth = cellBounds.width().coerceAtMost(screenshot.width - cellBounds.left)
                val cellHeight = cellBounds.height().coerceAtMost(screenshot.height - cellBounds.top)

                if (cellWidth <= 0 || cellHeight <= 0 ||
                    cellBounds.left < 0 || cellBounds.top < 0 ||
                    cellBounds.left >= screenshot.width || cellBounds.top >= screenshot.height) {
                    return@withContext false
                }

                val cellBitmap = Bitmap.createBitmap(
                    screenshot,
                    cellBounds.left.coerceAtLeast(0),
                    cellBounds.top.coerceAtLeast(0),
                    cellWidth,
                    cellHeight
                )
                try {
                    checkForRedColor(cellBitmap)
                } finally {
                    cellBitmap.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error detecting cell error", e)
                false
            } finally {
                screenshot.recycle()
            }
        }
    }

    private suspend fun cellAppearsFilled(cellBounds: Rect): Boolean {
        val captureService = ScreenCaptureService.instance ?: return false
        return withContext(Dispatchers.IO) {
            val screenshot = captureService.captureScreen() ?: return@withContext false
            try {
                val left = cellBounds.left.coerceIn(0, screenshot.width - 1)
                val top = cellBounds.top.coerceIn(0, screenshot.height - 1)
                val width = cellBounds.width().coerceAtMost(screenshot.width - left)
                val height = cellBounds.height().coerceAtMost(screenshot.height - top)

                if (width <= 0 || height <= 0) return@withContext false

                val cellBitmap = Bitmap.createBitmap(screenshot, left, top, width, height)
                try {
                    checkForDigitInk(cellBitmap)
                } finally {
                    cellBitmap.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error checking whether cell is already filled", e)
                false
            } finally {
                screenshot.recycle()
            }
        }
    }

    /**
     * Capture the board once and identify target cells that already contain ink.
     * The returned BooleanArray is indexed row-major (row * 9 + column), matching
     * the input loop so the solver never changes traversal order.
     */
    private suspend fun captureVisuallyFilledCells(
        original: SudokuGrid,
        boardBounds: Rect
    ): BooleanArray {
        val filledCells = BooleanArray(81)
        val captureService = ScreenCaptureService.instance ?: return filledCells
        return withContext(Dispatchers.IO) {
            val screenshot = captureService.captureScreen() ?: return@withContext filledCells
            try {
                val cellWidth = boardBounds.width() / 9f
                val cellHeight = boardBounds.height() / 9f
                for (row in 0..8) {
                    for (col in 0..8) {
                        if (original.locked[row][col] || original.values[row][col] != 0) continue

                        val rawLeft = (boardBounds.left + col * cellWidth).toInt()
                        val rawTop = (boardBounds.top + row * cellHeight).toInt()
                        if (rawLeft !in 0 until screenshot.width || rawTop !in 0 until screenshot.height) {
                            continue
                        }
                        val left = rawLeft.coerceAtLeast(0)
                        val top = rawTop.coerceAtLeast(0)
                        val right = (boardBounds.left + (col + 1) * cellWidth)
                            .toInt()
                            .coerceIn(left + 1, screenshot.width)
                        val bottom = (boardBounds.top + (row + 1) * cellHeight)
                            .toInt()
                            .coerceIn(top + 1, screenshot.height)
                        val cellBitmap = Bitmap.createBitmap(
                            screenshot,
                            left,
                            top,
                            right - left,
                            bottom - top
                        )
                        try {
                            filledCells[row * 9 + col] = checkForDigitInk(cellBitmap)
                        } finally {
                            cellBitmap.recycle()
                        }
                    }
                }
                filledCells
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error during one-pass filled-cell preflight", e)
                filledCells
            } finally {
                screenshot.recycle()
            }
        }
    }

    /**
     * Check if bitmap has significant red coloring (error indicator)
     */
    private fun checkForRedColor(bitmap: Bitmap): Boolean {
        var redPixels = 0
        val sampleStep = 2 // Sample every other pixel for speed
        var sampleCount = 0

        // Ignore border artifacts (selection outlines/grid lines) and focus on the digit region.
        val marginX = (bitmap.width * 0.15f).toInt()
        val marginY = (bitmap.height * 0.15f).toInt()
        val startX = marginX.coerceIn(0, bitmap.width - 1)
        val endX = (bitmap.width - marginX).coerceIn(startX + 1, bitmap.width)
        val startY = marginY.coerceIn(0, bitmap.height - 1)
        val endY = (bitmap.height - marginY).coerceIn(startY + 1, bitmap.height)

        for (x in startX until endX step sampleStep) {
            for (y in startY until endY step sampleStep) {
                sampleCount++
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Check if pixel is "red" - high red, lower green and blue
                if (r >= MIN_ERROR_RED &&
                    r >= g + MIN_ERROR_CHANNEL_GAP &&
                    r >= b + MIN_ERROR_CHANNEL_GAP) {
                    redPixels++
                }
            }
        }

        if (sampleCount <= 0) return false

        // Require both ratio and absolute red pixels to reduce false positives.
        val redRatio = redPixels.toFloat() / sampleCount
        val hasError = redRatio >= MIN_ERROR_RED_RATIO && redPixels >= MIN_ERROR_RED_PIXELS
        Log.d(TAG, "Red ratio(center): $redRatio ($redPixels / $sampleCount), hasError=$hasError")
        return hasError
    }

    private fun checkForDigitInk(bitmap: Bitmap): Boolean {
        var inkPixels = 0
        var sampleCount = 0
        var activeRows = 0
        var sampledRows = 0
        val sampleStep = 2

        // Focus on center where digit glyph lives; ignore borders/highlight edges.
        val marginX = (bitmap.width * 0.22f).toInt()
        val marginY = (bitmap.height * 0.22f).toInt()
        val startX = marginX.coerceIn(0, bitmap.width - 1)
        val endX = (bitmap.width - marginX).coerceIn(startX + 1, bitmap.width)
        val startY = marginY.coerceIn(0, bitmap.height - 1)
        val endY = (bitmap.height - marginY).coerceIn(startY + 1, bitmap.height)

        for (y in startY until endY step sampleStep) {
            var rowInkPixels = 0
            sampledRows++
            for (x in startX until endX step sampleStep) {
                sampleCount++
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val luminance = 0.299f * r + 0.587f * g + 0.114f * b
                // Dark saturated blue player digits are valid ink too. The center
                // crop excludes grid borders, while the luminance threshold keeps
                // pale selection backgrounds from being mistaken for a digit.
                if (luminance <= MAX_DIGIT_INK_LUMA) {
                    inkPixels++
                    rowInkPixels++
                }
            }
            if (rowInkPixels > 0) activeRows++
        }

        if (sampleCount <= 0 || sampledRows <= 0) return false
        val inkRatio = inkPixels.toFloat() / sampleCount
        val activeRowRatio = activeRows.toFloat() / sampledRows
        val filled = DigitInkPolicy.isDigitLike(
            inkPixels = inkPixels,
            sampleCount = sampleCount,
            activeRows = activeRows,
            sampledRows = sampledRows
        )
        Log.d(
            TAG,
            "Digit-ink ratio(center): $inkRatio ($inkPixels / $sampleCount), " +
                "activeRows=$activeRowRatio ($activeRows / $sampledRows), filled=$filled"
        )
        return filled
    }

    private suspend fun performTap(x: Int, y: Int): Boolean {
        ensureNotStopped()
        return suspendCancellableCoroutine<Boolean> { continuation ->
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, gestureDurationMs))
                .build()

            val accepted = accessibilityService.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
    }

    // Cache for digit button positions (speeds up repeated lookups)
    private val digitButtonCache = mutableMapOf<Int, Rect>()

    /**
     * Select a digit on the number pad using multiple strategies.
     * Tries: cached position, resource ID patterns, text search, content description.
     */
    private suspend fun selectDigit(digit: Int): Boolean {
        try {
            ensureNotStopped()
            val settleDelayMs = if (digitButtonCache.containsKey(digit)) {
                FAST_SELECT_SETTLE_DELAY_MS
            } else {
                DEFAULT_SELECT_SETTLE_DELAY_MS
            }
            delay(settleDelayMs)
            ensureNotStopped()

            // Try cached position first
            val cachedBounds = digitButtonCache[digit]
            if (cachedBounds != null) {
                if (!isUsableDigitButtonBounds(cachedBounds, rejectBoardCore = true, rejectBoardArea = true)) {
                    Log.w(TAG, "Discarding suspicious cached position for digit $digit at ${cachedBounds.centerX()},${cachedBounds.centerY()}")
                    digitButtonCache.remove(digit)
                } else {
                    Log.w(TAG, "Using cached position for digit $digit")
                    return performTap(cachedBounds.centerX(), cachedBounds.centerY())
                }
            }

            val rootNode = accessibilityService.rootInActiveWindow ?: run {
                Log.e(TAG, "No active window root node")
                return false
            }

            // Strategy 1: Try multiple resource ID patterns
            val idPatterns = listOf(
                if (digit == 1) "drawableView" else "drawableView$digit",
                "digit_$digit",
                "btn_$digit",
                "button_$digit",
                "num_$digit",
                "number_$digit",
                "key_$digit"
            )

            for (pattern in idPatterns) {
                val node = findNodeByResourceIdSuffix(rootNode, pattern)
                if (node != null) {
                    val bounds = resolveTapBounds(node)
                    node.recycle()
                    if (!isUsableDigitButtonBounds(bounds, rejectBoardCore = true, rejectBoardArea = true)) {
                        continue
                    }
                    Log.w(TAG, "Found digit $digit via resource ID '$pattern' at ${bounds.centerX()},${bounds.centerY()}")
                    digitButtonCache[digit] = bounds
                    rootNode.recycle()
                    return performTap(bounds.centerX(), bounds.centerY())
                }
            }

            // Strategy 2: Try text content
            val textNode = findNodeWithText(rootNode, digit)
            if (textNode != null) {
                val bounds = resolveTapBounds(textNode)
                textNode.recycle()
                if (isUsableDigitButtonBounds(bounds, rejectBoardCore = true, rejectBoardArea = true)) {
                    Log.w(TAG, "Found digit $digit via text at ${bounds.centerX()},${bounds.centerY()}")
                    digitButtonCache[digit] = bounds
                    rootNode.recycle()
                    return performTap(bounds.centerX(), bounds.centerY())
                }
            }

            // Strategy 3: Try content description
            val descNode = findNodeWithContentDescription(rootNode, digit)
            if (descNode != null) {
                val bounds = resolveTapBounds(descNode)
                descNode.recycle()
                if (isUsableDigitButtonBounds(bounds, rejectBoardCore = true, rejectBoardArea = true)) {
                    Log.w(TAG, "Found digit $digit via content description at ${bounds.centerX()},${bounds.centerY()}")
                    digitButtonCache[digit] = bounds
                    rootNode.recycle()
                    return performTap(bounds.centerX(), bounds.centerY())
                }
            }

            // Strategy 4: Find all clickable nodes and look for one matching the digit
            val clickableNodes = findAllClickableNodes(rootNode)
            for (node in clickableNodes) {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                if (matchesDigitLabel(text, digit) || matchesDigitLabel(desc, digit)) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (isUsableDigitButtonBounds(bounds, rejectBoardCore = true, rejectBoardArea = true)) {
                        Log.w(TAG, "Found digit $digit via clickable search at ${bounds.centerX()},${bounds.centerY()}")
                        digitButtonCache[digit] = bounds
                        clickableNodes.forEach { it.recycle() }
                        rootNode.recycle()
                        return performTap(bounds.centerX(), bounds.centerY())
                    }
                }
            }
            clickableNodes.forEach { it.recycle() }

            Log.w(TAG, "Could not find digit button for: $digit (tried all strategies)")
            rootNode.recycle()
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting digit $digit", e)
            return false
        }
    }

    private suspend fun preloadDigitButtonCache(requiredDigit: Int? = null) {
        if (requiredDigit != null && digitButtonCache.containsKey(requiredDigit)) return
        if (requiredDigit == null && digitButtonCache.size >= PRELOAD_TARGET_CACHE_SIZE) return
        ensureNotStopped()

        val rootNode = accessibilityService.rootInActiveWindow ?: return
        var loaded = 0
        try {
            val clickableNodes = findAllClickableNodes(rootNode)
            val probeOrder = if (requiredDigit != null) {
                listOf(requiredDigit) + (1..9).filter { it != requiredDigit }
            } else {
                (1..9).toList()
            }

            for (digit in probeOrder) {
                ensureNotStopped()
                if (digitButtonCache.containsKey(digit)) continue
                if (requiredDigit != null && digit != requiredDigit && remainingDigitDemand[digit] <= 0) continue
                if (requiredDigit == null && remainingDigitDemand[digit] <= 0) continue

                val idPatterns = listOf(
                    if (digit == 1) "drawableView" else "drawableView$digit",
                    "digit_$digit",
                    "btn_$digit",
                    "button_$digit",
                    "num_$digit",
                    "number_$digit",
                    "key_$digit"
                )

                var cachedBounds: Rect? = null

                for (pattern in idPatterns) {
                    val node = findNodeByResourceIdSuffix(rootNode, pattern)
                    if (node != null) {
                        val bounds = resolveTapBounds(node)
                        node.recycle()
                        if (isUsableDigitButtonBounds(bounds, rejectBoardCore = true, rejectBoardArea = true)) {
                            cachedBounds = bounds
                            break
                        }
                    }
                }

                if (cachedBounds == null) {
                    val textNode = findNodeWithText(rootNode, digit)
                    if (textNode != null) {
                        val bounds = resolveTapBounds(textNode)
                        textNode.recycle()
                        if (isUsableDigitButtonBounds(bounds, rejectBoardCore = true, rejectBoardArea = true)) {
                            cachedBounds = bounds
                        }
                    }
                }

                if (cachedBounds == null) {
                    val descNode = findNodeWithContentDescription(rootNode, digit)
                    if (descNode != null) {
                        val bounds = resolveTapBounds(descNode)
                        descNode.recycle()
                        if (isUsableDigitButtonBounds(bounds, rejectBoardCore = true, rejectBoardArea = true)) {
                            cachedBounds = bounds
                        }
                    }
                }

                if (cachedBounds == null) {
                    for (node in clickableNodes) {
                        val text = node.text?.toString() ?: ""
                        val desc = node.contentDescription?.toString() ?: ""
                        if (matchesDigitLabel(text, digit) || matchesDigitLabel(desc, digit)) {
                            val bounds = Rect()
                            node.getBoundsInScreen(bounds)
                            if (isUsableDigitButtonBounds(bounds, rejectBoardCore = true, rejectBoardArea = true)) {
                                cachedBounds = bounds
                                break
                            }
                        }
                    }
                }

                if (cachedBounds != null) {
                    digitButtonCache[digit] = cachedBounds
                    loaded++
                    if (requiredDigit != null && digit == requiredDigit) {
                        break
                    }
                }
            }
            clickableNodes.forEach { it.recycle() }
        } finally {
            rootNode.recycle()
        }

        if (loaded > 0) {
            Log.i(
                TAG,
                "Preloaded $loaded digit button(s); cache size=${digitButtonCache.size}/$PRELOAD_TARGET_CACHE_SIZE"
            )
        }
        val missing = (1..9).filterNot { digitButtonCache.containsKey(it) }
        if (missing.isNotEmpty()) {
            val missingRequired = missing.filter { remainingDigitDemand[it] > 0 }
            if (missingRequired.isNotEmpty()) {
                val missingRequiredSet = missingRequired.toSet()
                if (missingRequiredSet != lastLoggedMissingRequiredDigits) {
                    Log.w(TAG, "Digit keypad missing required digits: $missingRequired")
                    lastLoggedMissingRequiredDigits = missingRequiredSet
                }
            } else {
                val missingSet = missing.toSet()
                if (missingSet != lastLoggedHiddenCompletedDigits) {
                    Log.i(TAG, "Digit keypad hiding completed digits (not needed): $missing")
                    lastLoggedHiddenCompletedDigits = missingSet
                }
            }
        }
    }

    private fun resetDigitDemand(original: SudokuGrid, solution: Array<IntArray>) {
        remainingDigitDemand = IntArray(10)
        lastLoggedMissingRequiredDigits = emptySet()
        lastLoggedHiddenCompletedDigits = emptySet()
        for (row in 0..8) {
            for (col in 0..8) {
                if (original.locked[row][col] || original.values[row][col] != 0) continue
                val digit = solution[row][col]
                if (digit in 1..9) {
                    remainingDigitDemand[digit]++
                }
            }
        }
    }

    private fun markDigitHandled(digit: Int) {
        if (digit !in 1..9) return
        if (remainingDigitDemand[digit] > 0) {
            remainingDigitDemand[digit]--
            if (remainingDigitDemand[digit] == 0) {
                lastLoggedMissingRequiredDigits = lastLoggedMissingRequiredDigits - digit
            }
        }
    }

    private fun isUsableDigitButtonBounds(
        bounds: Rect,
        rejectBoardCore: Boolean,
        rejectBoardArea: Boolean = false
    ): Boolean {
        val board = activeBoardBounds
        val screenWidth = activeScreenWidth.coerceAtLeast(
            maxOf(accessibilityService.resources.displayMetrics.widthPixels, board?.right ?: 0)
        )
        val screenHeight = activeScreenHeight.coerceAtLeast(
            maxOf(accessibilityService.resources.displayMetrics.heightPixels, board?.bottom ?: 0)
        )
        if (!ResponsiveGeometryPolicy.isPlausibleDigitButton(
                buttonWidth = bounds.width(),
                buttonHeight = bounds.height(),
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                boardWidth = board?.width() ?: 0,
                boardHeight = board?.height() ?: 0
            )
        ) {
            return false
        }

        if (board != null && rejectBoardArea) {
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            if (board.contains(centerX, centerY)) return false
        }
        if (!rejectBoardCore || board == null) return true

        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        val coreInsetX = (board.width() * 0.10f).toInt()
        val coreInsetY = (board.height() * 0.10f).toInt()
        val boardCore = Rect(
            board.left + coreInsetX,
            board.top + coreInsetY,
            board.right - coreInsetX,
            board.bottom - coreInsetY
        )

        // Digit buttons should not resolve to the middle of the Sudoku grid.
        return !boardCore.contains(centerX, centerY)
    }

    private fun matchesDigitLabel(raw: String?, digit: Int): Boolean {
        if (raw.isNullOrBlank()) return false
        val normalized = raw.trim()
        val pattern = Regex("(?<!\\d)$digit(?!\\d)")
        return pattern.containsMatchIn(normalized)
    }

    private fun resolveTapBounds(node: AccessibilityNodeInfo): Rect {
        var cursor: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var chosen: AccessibilityNodeInfo? = null
        try {
            while (cursor != null) {
                if (cursor.isClickable) {
                    chosen = AccessibilityNodeInfo.obtain(cursor)
                    break
                }
                val parent = cursor.parent
                cursor.recycle()
                cursor = parent
            }

            if (chosen == null) {
                chosen = AccessibilityNodeInfo.obtain(node)
            }
            val target = chosen ?: AccessibilityNodeInfo.obtain(node)
            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            return bounds
        } finally {
            cursor?.recycle()
            chosen?.recycle()
        }
    }

    /**
     * Clear the digit button cache (useful when restarting)
     */
    fun clearCache() {
        digitButtonCache.clear()
    }

    /**
     * Find a node whose resource ID ends with the given suffix (after :id/).
     */
    private fun findNodeByResourceIdSuffix(
        node: AccessibilityNodeInfo?,
        suffix: String
    ): AccessibilityNodeInfo? {
        if (node == null) return null

        val resId = node.viewIdResourceName
        if (resId != null) {
            val idPart = resId.substringAfter(":id/", "")
            if (idPart.equals(suffix, ignoreCase = true)) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByResourceIdSuffix(child, suffix)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }

        return null
    }

    private fun findNodeWithText(node: AccessibilityNodeInfo?, digit: Int): AccessibilityNodeInfo? {
        if (node == null) return null
        if (matchesDigitLabel(node.text?.toString(), digit)) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeWithText(child, digit)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    private fun findNodeWithContentDescription(node: AccessibilityNodeInfo?, digit: Int): AccessibilityNodeInfo? {
        if (node == null) return null
        if (matchesDigitLabel(node.contentDescription?.toString(), digit)) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeWithContentDescription(child, digit)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    private fun findAllClickableNodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (node == null) return result

        if (node.isClickable) {
            result.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            result.addAll(findAllClickableNodes(child))
            child.recycle()
        }

        return result
    }

    companion object {
        private const val TAG = "AutoInputController"
        private const val MIN_ERROR_RED = 165
        private const val MIN_ERROR_CHANNEL_GAP = 45
        private const val MIN_ERROR_RED_RATIO = 0.03f
        private const val MIN_ERROR_RED_PIXELS = 14
        private const val PRELOAD_TARGET_CACHE_SIZE = 9
        private const val WARMUP_INPUTS = 0
        private const val FAST_GESTURE_DURATION_MS = 50L
        private const val SAMSUNG_A07_GESTURE_DURATION_MS = 20L
        private const val HUAWEI_JAD_GESTURE_DURATION_MS = 1L
        private const val COMPLETION_CHECK_INTERVAL_MS = 350L
        private const val FAST_TAP_DELAY_MS = 40L
        private const val SAMSUNG_A07_TAP_DELAY_MS = 10L
        private const val HUAWEI_JAD_TAP_DELAY_MS = 5L
        private const val FAST_DIGIT_INPUT_DELAY_MS = 30L
        private const val SAMSUNG_A07_DIGIT_INPUT_DELAY_MS = 5L
        private const val HUAWEI_JAD_DIGIT_INPUT_DELAY_MS = 5L
        private const val ERROR_CHECK_DELAY_MS = 100L
        private const val FAST_ERROR_CHECK_DELAY_MS = 30L
        private const val SAMSUNG_A07_ERROR_CHECK_DELAY_MS = 5L
        private const val HUAWEI_JAD_ERROR_CHECK_DELAY_MS = 5L
        private const val HUAWEI_BATTLE_CELL_SETTLE_MS = 30L
        private const val HUAWEI_BATTLE_DIGIT_SETTLE_MS = 25L
        private const val HUAWEI_BATTLE_COMPLETION_CHECK_INTERVAL_MS = 250L
        // Full-screen verification is the dominant cost on the JAD-LX9. Keep
        // wrong-digit detection, but amortize it across twice as many entries
        // to restore the prior input throughput.
        private const val HUAWEI_BATTLE_VERIFY_BATCH_SIZE = 16
        private const val SOLO_VERIFY_BATCH_SIZE = 16
        private const val DEFAULT_SELECT_SETTLE_DELAY_MS = 0L
        private const val FAST_SELECT_SETTLE_DELAY_MS = 0L
        private const val RETRY_DIGIT_SETTLE_DELAY_MS = 120L
        private const val KEYPAD_RECOVERY_TAP_DELAY_MS = 220L
        private const val MAX_DIGIT_INK_LUMA = 142f
    }
}
