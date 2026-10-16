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
import com.sudoku.solver.model.HexadokuGrid
import com.sudoku.solver.service.ScreenCaptureService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class HexadokuInputResult(
    val success: Boolean,
    val inputCount: Int,
    val message: String,
    val errorCell: Triple<Int, Int, Int>? = null,
    val enteredCells: Set<Pair<Int, Int>> = emptySet(),
    val recoveryCell: Pair<Int, Int>? = null,
    val restartRequested: Boolean = false
)

/** Accessibility input kept separate from the tuned 9x9 input controller. */
class HexadokuAutoInputController(
    private val accessibilityService: AccessibilityService,
    private val beforeInput: () -> Unit = {}
) {
    // Live-benchmarked on the Galaxy A07; other devices retain their verified timings.
    private val samsungA07 = Build.MODEL.startsWith("SM-A075", ignoreCase = true)
    private val huaweiJadLx9 = Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) &&
        Build.MODEL.equals("JAD-LX9", ignoreCase = true)
    @Volatile
    private var stopRequested = false
    private val symbolButtonCache = mutableMapOf<Int, Rect>()
    private var activeBoardBounds: Rect? = null
    private var keypadShowsLetters = false
    private var adaptivePacing: AdaptiveInputPacing? = null
    private var lastCompletionCheckAtMs = 0L
    private data class PendingRedCheck(val row: Int, val col: Int, val value: Int, val bounds: Rect)
    private data class BatchFailure(val entry: PendingRedCheck, val missing: Boolean)
    private data class BatchVerification(val failure: BatchFailure?, val visibleSymbols: Int)

    fun requestStop() {
        stopRequested = true
    }

    fun clearStopRequest() {
        stopRequested = false
    }

    fun clearCache() {
        symbolButtonCache.clear()
    }

    suspend fun inputSolution(
        original: HexadokuGrid,
        solution: Array<IntArray>,
        boardBounds: Rect,
        skipCells: Set<Pair<Int, Int>> = emptySet()
    ): HexadokuInputResult {
        require(solution.size == HexadokuGrid.SIZE && solution.all { it.size == HexadokuGrid.SIZE }) {
            "Solution must be 16x16"
        }
        clearCache()
        val inputStartedAt = SystemClock.elapsedRealtime()
        lastCompletionCheckAtMs = 0L
        adaptivePacing = AdaptiveInputPacing(
            accessibilityService, InputPacingMode.HEX_16,
            when {
                samsungA07 -> InputPace(
                    SAMSUNG_A07_CELL_SETTLE_DELAY_MS, SAMSUNG_A07_SYMBOL_SETTLE_DELAY_MS,
                    SAMSUNG_A07_TAP_DURATION_MS
                )
                huaweiJadLx9 -> InputPace(
                    HUAWEI_JAD_CELL_SETTLE_DELAY_MS, HUAWEI_JAD_SYMBOL_SETTLE_DELAY_MS,
                    HUAWEI_JAD_TAP_DURATION_MS
                )
                else -> InputPace(CELL_SETTLE_DELAY_MS, SYMBOL_SETTLE_DELAY_MS, TAP_DURATION_MS)
            },
            maximumSpeed = huaweiJadLx9 || samsungA07,
            maximumSpeedPace = if (samsungA07) InputPace(0, 0, 1) else InputPace.HUAWEI_FAST,
            // Separate the verified A07 profile from legacy OCR-related slowdowns.
            // Missing entries can still back off and retain that fallback on retries.
            preferenceProfile = if (samsungA07) "a07_fast_v1" else null,
            fixedMaximumSpeed = huaweiJadLx9
        )
        activeBoardBounds = Rect(boardBounds)
        val cellWidth = boardBounds.width() / HexadokuGrid.SIZE.toFloat()
        val cellHeight = boardBounds.height() / HexadokuGrid.SIZE.toFloat()
        var inputCount = 0
        val enteredCells = mutableSetOf<Pair<Int, Int>>()
        val pendingRedChecks = mutableListOf<PendingRedCheck>()
        val initialVisibleErrorCount = readVisibleErrorCount()
        Log.i(TAG, "Visible error counter at 16x16 input start: $initialVisibleErrorCount")
        Log.i(TAG, "16x16 input pace: ${requireNotNull(adaptivePacing).pace}")

        // Start from a known page. A right swipe is harmless if 1-9 is already
        // visible and prevents the keypad's reused A/1 IDs from being confused.
        switchKeypadPage(showLetters = false)
        delay(KEYPAD_PAGE_SETTLE_DELAY_MS)
        keypadShowsLetters = false

        // Group input by symbol. The target app places 1-9 and A-G on two
        // keypad pages, so this limits navigation to one page transition.
        for (value in 1..HexadokuGrid.SIZE) {
            if (value == 10) {
                switchKeypadPage(showLetters = true)
                delay(KEYPAD_PAGE_SETTLE_DELAY_MS)
                keypadShowsLetters = true
                clearCache()
            }
            for (row in 0 until HexadokuGrid.SIZE) {
                for (col in 0 until HexadokuGrid.SIZE) {
                    ensureNotStopped()
                    if (original.values[row][col] != 0 || original.locked[row][col]) continue
                    if (solution[row][col] != value) continue
                    if (Pair(row, col) in skipCells) continue

                    val cellX = (boardBounds.left + (col + 0.5f) * cellWidth).toInt()
                    val cellY = (boardBounds.top + (row + 0.5f) * cellHeight).toInt()
                    val cellBounds = cellBounds(row, col, boardBounds)
                    Log.i(TAG, "Entering ${HexadokuGrid.symbolFor(value)} at r${row + 1}c${col + 1} ($cellX,$cellY)")
                    if (!performTap(cellX, cellY)) {
                        adaptivePacing?.rejected("cell tap rejected")
                        return HexadokuInputResult(
                            success = false,
                            inputCount = inputCount,
                            message = "Cell tap was rejected at row ${row + 1}, column ${col + 1}",
                            enteredCells = enteredCells,
                            recoveryCell = Pair(row, col)
                        )
                    }
                    delay(requireNotNull(adaptivePacing).pace.cellSettleMs)
                    ensureNotStopped()

                    if (!selectSymbol(value)) {
                        adaptivePacing?.rejected("symbol tap rejected")
                        return HexadokuInputResult(
                            success = false,
                            inputCount = inputCount,
                            message = "Could not find the ${HexadokuGrid.symbolFor(value)} keypad button",
                            enteredCells = enteredCells,
                            recoveryCell = Pair(row, col)
                        )
                    }
                    inputCount++
                    enteredCells.add(Pair(row, col))
                    delay(requireNotNull(adaptivePacing).pace.digitSettleMs)

                    // On the JAD-LX9, one screen/tree read per symbol roughly doubles
                    // the run time. Its grouped verification below checks sixteen entries
                    // from one frame and still identifies the exact red or missing cell.
                    val currentVisibleErrorCount = if (huaweiJadLx9) null else readVisibleErrorCount()
                    val visibleCounterIncreased =
                        !huaweiJadLx9 && initialVisibleErrorCount != null &&
                            currentVisibleErrorCount != null &&
                            currentVisibleErrorCount > initialVisibleErrorCount
                    val pixelErrorDetected =
                        !huaweiJadLx9 && initialVisibleErrorCount == null && detectErrorInCell(cellBounds)
                    if (visibleCounterIncreased || pixelErrorDetected) {
                        adaptivePacing?.wrongValueDetected("red symbol")
                        Log.e(
                            TAG,
                            "Red error detected at ($row,$col) for ${HexadokuGrid.symbolFor(value)} - stopping"
                        )
                        return HexadokuInputResult(
                            success = false,
                            inputCount = inputCount,
                            message = "Wrong symbol detected",
                            errorCell = Triple(row, col, value),
                            enteredCells = enteredCells
                        )
                    }
                    pendingRedChecks += PendingRedCheck(row, col, value, cellBounds)
                    if (pendingRedChecks.size >= RED_VERIFY_BATCH_SIZE) {
                        val verification = verifySymbolBatch(pendingRedChecks)
                        val failure = verification?.failure
                        if (failure != null) {
                            if (failure.missing) adaptivePacing?.rejected("missing symbol")
                            else adaptivePacing?.wrongValueDetected("red symbol")
                            return HexadokuInputResult(
                                success = false,
                                inputCount = inputCount,
                                message = if (failure.missing) "Missing symbol detected" else "Wrong symbol detected",
                                errorCell = if (failure.missing) null else Triple(
                                    failure.entry.row, failure.entry.col, failure.entry.value
                                ),
                                enteredCells = enteredCells,
                                recoveryCell = if (failure.missing) failure.entry.row to failure.entry.col else null,
                                restartRequested = failure.missing
                            )
                        }
                        if (verification?.visibleSymbols == pendingRedChecks.size) {
                            adaptivePacing?.verified(pendingRedChecks.size)
                        }
                        pendingRedChecks.clear()
                    }
                }
            }
        }

        if (pendingRedChecks.isNotEmpty()) {
            val verification = verifySymbolBatch(pendingRedChecks)
            val failure = verification?.failure
            if (failure != null) {
                if (failure.missing) adaptivePacing?.rejected("missing symbol")
                else adaptivePacing?.wrongValueDetected("red symbol")
                return HexadokuInputResult(
                    success = false,
                    inputCount = inputCount,
                    message = if (failure.missing) "Missing symbol detected" else "Wrong symbol detected",
                    errorCell = if (failure.missing) null else Triple(
                        failure.entry.row, failure.entry.col, failure.entry.value
                    ),
                    enteredCells = enteredCells,
                    recoveryCell = if (failure.missing) failure.entry.row to failure.entry.col else null,
                    restartRequested = failure.missing
                )
            }
            if (verification?.visibleSymbols == pendingRedChecks.size) {
                adaptivePacing?.verified(pendingRedChecks.size)
            }
        }

        Log.i(TAG, "Auto-input complete: $inputCount cells filled in ${SystemClock.elapsedRealtime() - inputStartedAt}ms")
        return HexadokuInputResult(
            success = true,
            inputCount = inputCount,
            message = "Entered $inputCount cells",
            enteredCells = enteredCells
        )
    }

    /** Returns true while a 16x16 cell still contains the red wrong symbol. */
    suspend fun isCellMarkedWrong(row: Int, col: Int, boardBounds: Rect): Boolean {
        require(row in 0 until HexadokuGrid.SIZE && col in 0 until HexadokuGrid.SIZE) {
            "Cell must be inside a 16x16 board"
        }
        return detectErrorInCell(cellBounds(row, col, boardBounds))
    }

    /** Finds a red symbol anywhere on the board, including one left from an earlier input pass. */
    suspend fun findMarkedWrongCell(boardBounds: Rect): Pair<Int, Int>? {
        if (isResultVisible()) {
            Log.i(TAG, "Result screen is visible; skipping stale red-cell scan")
            return null
        }
        val captureService = ScreenCaptureService.instance ?: return null
        return withContext(Dispatchers.IO) {
            val screenshot = captureService.captureScreen() ?: return@withContext null
            try {
                for (row in 0 until HexadokuGrid.SIZE) {
                    for (col in 0 until HexadokuGrid.SIZE) {
                        ensureNotStopped()
                        val bounds = cellBounds(row, col, boardBounds)
                        val left = bounds.left.coerceIn(0, screenshot.width - 1)
                        val top = bounds.top.coerceIn(0, screenshot.height - 1)
                        val width = bounds.width().coerceAtMost(screenshot.width - left)
                        val height = bounds.height().coerceAtMost(screenshot.height - top)
                        if (width <= 0 || height <= 0) continue
                        val cell = Bitmap.createBitmap(screenshot, left, top, width, height)
                        try {
                            if (checkForRedColor(cell)) return@withContext row to col
                        } finally { cell.recycle() }
                    }
                }
                null
            } finally { screenshot.recycle() }
        }
    }

    /** Reliably focuses a cell before the service selects PISTA. */
    suspend fun focusCell(row: Int, col: Int, boardBounds: Rect): Boolean {
        require(row in 0 until HexadokuGrid.SIZE && col in 0 until HexadokuGrid.SIZE) {
            "Cell must be inside a 16x16 board"
        }
        val cellWidth = boardBounds.width() / HexadokuGrid.SIZE.toFloat()
        val cellHeight = boardBounds.height() / HexadokuGrid.SIZE.toFloat()
        val x = (boardBounds.left + (col + 0.5f) * cellWidth).toInt()
        val y = (boardBounds.top + (row + 0.5f) * cellHeight).toInt()
        return performTap(x, y)
    }

    private fun cellBounds(row: Int, col: Int, boardBounds: Rect): Rect {
        val cellWidth = boardBounds.width() / HexadokuGrid.SIZE.toFloat()
        val cellHeight = boardBounds.height() / HexadokuGrid.SIZE.toFloat()
        return Rect(
            (boardBounds.left + col * cellWidth).toInt(),
            (boardBounds.top + row * cellHeight).toInt(),
            (boardBounds.left + (col + 1) * cellWidth).toInt(),
            (boardBounds.top + (row + 1) * cellHeight).toInt()
        )
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

    private suspend fun detectErrorInCell(cellBounds: Rect): Boolean {
        ensureNotStopped()
        val first = detectErrorInCellOnce(cellBounds)
        if (!first) return false
        delay(ERROR_CONFIRM_DELAY_MS)
        ensureNotStopped()
        return detectErrorInCellOnce(cellBounds)
    }

    private suspend fun detectErrorInCellOnce(cellBounds: Rect): Boolean {
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
                    checkForRedColor(cellBitmap)
                } finally {
                    cellBitmap.recycle()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed detecting red 16x16 symbol", error)
                false
            } finally {
                screenshot.recycle()
            }
        }
    }

    private suspend fun verifySymbolBatch(entries: List<PendingRedCheck>): BatchVerification? {
        if (isResultVisible()) {
            Log.i(TAG, "Result screen appeared during grouped 16x16 verification")
            return BatchVerification(failure = null, visibleSymbols = 0)
        }
        val captureService = ScreenCaptureService.instance ?: return null
        return withContext(Dispatchers.IO) {
            val captureBounds = if (huaweiJadLx9) activeBoardBounds else null
            val screenshot = (if (captureBounds != null)
                captureService.captureRegion(captureBounds, freshFrameWaitMs = 32L)
            else captureService.captureScreen()) ?: return@withContext null
            try {
                var firstMissing: PendingRedCheck? = null
                var visibleSymbols = 0
                for (entry in entries) {
                    ensureNotStopped()
                    val bounds = entry.bounds
                    val left = (bounds.left - (captureBounds?.left ?: 0)).coerceIn(0, screenshot.width - 1)
                    val top = (bounds.top - (captureBounds?.top ?: 0)).coerceIn(0, screenshot.height - 1)
                    val width = bounds.width().coerceAtMost(screenshot.width - left)
                    val height = bounds.height().coerceAtMost(screenshot.height - top)
                    if (width <= 0 || height <= 0) continue
                    val cell = Bitmap.createBitmap(screenshot, left, top, width, height)
                    try {
                        if (checkForRedColor(cell)) {
                            Log.e(TAG, "Grouped red check found wrong symbol at (${entry.row},${entry.col})")
                            return@withContext BatchVerification(
                                BatchFailure(entry, missing = false), visibleSymbols
                            )
                        }
                        if (checkForSymbolInk(cell)) visibleSymbols++
                        else if (firstMissing == null) firstMissing = entry
                    } finally { cell.recycle() }
                }
                // All cells disappearing together usually means the win dialog replaced the board.
                val failure = if (firstMissing != null && (visibleSymbols > 0 || !isResultVisible())) {
                    Log.e(TAG, "Grouped check found missing symbol at (${firstMissing.row},${firstMissing.col})")
                    BatchFailure(firstMissing, missing = true)
                } else null
                BatchVerification(failure, visibleSymbols)
            } finally { screenshot.recycle() }
        }
    }

    private fun checkForSymbolInk(bitmap: Bitmap): Boolean {
        val startX = (bitmap.width * 0.2f).toInt().coerceAtLeast(0)
        val endX = (bitmap.width * 0.8f).toInt().coerceAtMost(bitmap.width)
        val startY = (bitmap.height * 0.2f).toInt().coerceAtLeast(0)
        val endY = (bitmap.height * 0.8f).toInt().coerceAtMost(bitmap.height)
        var inkPixels = 0
        var sampleCount = 0
        var activeRows = 0
        var sampledRows = 0
        for (y in startY until endY step 2) {
            var rowInk = 0
            sampledRows++
            for (x in startX until endX step 2) {
                sampleCount++
                val color = bitmap.getPixel(x, y)
                val luminance = 0.299f * Color.red(color) +
                    0.587f * Color.green(color) + 0.114f * Color.blue(color)
                if (luminance < 160f) {
                    inkPixels++
                    rowInk++
                }
            }
            if (rowInk > 0) activeRows++
        }
        return DigitInkPolicy.isDigitLike(inkPixels, sampleCount, activeRows, sampledRows)
    }

    private fun isResultVisible(): Boolean {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        accessibilityService.rootInActiveWindow?.let(roots::add)
        accessibilityService.windows.forEach { window -> window.root?.let(roots::add) }
        return try {
            roots.any { root ->
                root.packageName?.toString() == GameInputPolicy.GAME_PACKAGE &&
                    GameInputPolicy.resultViewIds.any { id ->
                        val nodes = root.findAccessibilityNodeInfosByViewId(id)
                        try {
                            nodes.any { it.isVisibleToUser }
                        } finally {
                            nodes.forEach(AccessibilityNodeInfo::recycle)
                        }
                    }
            }
        } finally {
            roots.forEach(AccessibilityNodeInfo::recycle)
        }
    }

    private fun checkForRedColor(bitmap: Bitmap): Boolean {
        val marginX = (bitmap.width * ERROR_SAMPLE_MARGIN).toInt()
        val marginY = (bitmap.height * ERROR_SAMPLE_MARGIN).toInt()
        val startX = marginX.coerceIn(0, bitmap.width - 1)
        val endX = (bitmap.width - marginX).coerceIn(startX + 1, bitmap.width)
        val startY = marginY.coerceIn(0, bitmap.height - 1)
        val endY = (bitmap.height - marginY).coerceIn(startY + 1, bitmap.height)
        var redPixels = 0
        var sampleCount = 0

        for (x in startX until endX step ERROR_SAMPLE_STEP) {
            for (y in startY until endY step ERROR_SAMPLE_STEP) {
                sampleCount++
                val pixel = bitmap.getPixel(x, y)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                if (
                    red >= MIN_ERROR_RED &&
                    red >= green + MIN_ERROR_CHANNEL_GAP &&
                    red >= blue + MIN_ERROR_CHANNEL_GAP
                ) {
                    redPixels++
                }
            }
        }

        if (sampleCount == 0) return false
        return redPixels >= MIN_ERROR_RED_PIXELS &&
            redPixels.toFloat() / sampleCount >= MIN_ERROR_RED_RATIO
    }

    private suspend fun selectSymbol(value: Int): Boolean {
        ensureNotStopped()
        val cached = symbolButtonCache[value]
        if (cached != null && isOutsideBoard(cached)) {
            return performTap(cached.centerX(), cached.centerY())
        }

        val symbol = HexadokuGrid.symbolFor(value)
        var bounds = awaitVisibleSymbolBounds(value, SYMBOL_LOOKUP_ATTEMPTS)
        if (bounds == null) {
            switchKeypadPage(showLetters = value >= 10)
            delay(KEYPAD_PAGE_SETTLE_DELAY_MS)
            ensureNotStopped()
            clearCache()
            bounds = awaitVisibleSymbolBounds(value, SYMBOL_LOOKUP_AFTER_SWIPE_ATTEMPTS)
        }
        if (bounds == null) {
            val displayMetrics = accessibilityService.resources.displayMetrics
            // Accessibility resources report the app-content height on the A07
            // (1440px), while gesture coordinates use the physical 1600px screen.
            // MediaProjection is the authoritative coordinate space here.
            val captureDimensions = ScreenCaptureService.instance?.captureDimensions()
            val boardBottom = activeBoardBounds?.bottom ?: 0
            val keypadBounds = findVisibleKeypadBounds()
            val fallback = HexadokuKeypadGeometry.fallbackTapPoint(
                value = value,
                screenWidth = captureDimensions?.first ?: displayMetrics.widthPixels,
                screenHeight = captureDimensions?.second ?: displayMetrics.heightPixels,
                boardBottom = boardBottom,
                visibleKeypadCenterY = keypadBounds?.centerY(),
                keypadLeft = keypadBounds?.left ?: 0,
                keypadRight = keypadBounds?.right ?: (captureDimensions?.first ?: displayMetrics.widthPixels)
            )
            if (fallback == null) {
                Log.w(TAG, "Could not find visible 16x16 keypad symbol $symbol")
                return false
            }
            Log.w(
                TAG,
                "Accessibility tree missed 16x16 keypad symbol $symbol; " +
                    "using geometry fallback at (${fallback.first},${fallback.second})"
            )
            return performTap(fallback.first, fallback.second)
        }
        symbolButtonCache[value] = bounds
        Log.i(TAG, "Keypad symbol $symbol at (${bounds.centerX()},${bounds.centerY()})")
        return performTap(bounds.centerX(), bounds.centerY())
    }

    private suspend fun awaitVisibleSymbolBounds(value: Int, attempts: Int): Rect? {
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            ensureNotStopped()
            findVisibleSymbolBounds(value)?.let { return it }
            if (attempt + 1 < attempts) delay(SYMBOL_LOOKUP_RETRY_DELAY_MS)
        }
        return null
    }

    private fun findVisibleSymbolBounds(value: Int): Rect? {
        val root = accessibilityService.rootInActiveWindow ?: return null
        return try {
            // Toolbar badges such as quick-pencil '+1' also match a digit label.
            // Only accept controls inside the actual keypad, never the toolbar.
            val keypadBounds = findKeypadBounds(root) ?: return null
            val reusableDrawableIdsMatchPage = keypadShowsLetters == (value >= 10)
            findSymbolBounds(
                node = root,
                value = value,
                symbol = HexadokuGrid.symbolFor(value),
                keypadBounds = keypadBounds,
                reusableDrawableIdsMatchPage = reusableDrawableIdsMatchPage
            )
        } finally {
            root.recycle()
        }
    }

    private fun findSymbolBounds(
        node: AccessibilityNodeInfo,
        value: Int,
        symbol: String,
        keypadBounds: Rect,
        reusableDrawableIdsMatchPage: Boolean
    ): Rect? {
        val textMatches = matchesSymbolLabel(node.text?.toString(), symbol) ||
            matchesSymbolLabel(node.contentDescription?.toString(), symbol)
        val idMatches = node.viewIdResourceName
            ?.substringAfter(":id/", "")
            ?.let { matchesResourceId(it, value, symbol, reusableDrawableIdsMatchPage) } == true

        if (node.isVisibleToUser && (textMatches || idMatches)) {
            val bounds = resolveTapBounds(node)
            if (bounds.width() > 2 && bounds.height() > 2 &&
                keypadBounds.contains(bounds.centerX(), bounds.centerY()) && isOutsideBoard(bounds)) return bounds
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findSymbolBounds(child, value, symbol, keypadBounds, reusableDrawableIdsMatchPage)
            child.recycle()
            if (match != null) return match
        }
        return null
    }

    private fun matchesSymbolLabel(raw: String?, symbol: String): Boolean {
        if (raw.isNullOrBlank()) return false
        val normalized = raw.trim().uppercase()
        if (normalized == symbol) return true
        val pattern = Regex("(?<![0-9A-Z])${Regex.escape(symbol)}(?![0-9A-Z])")
        return pattern.containsMatchIn(normalized)
    }

    private fun matchesResourceId(
        raw: String,
        value: Int,
        symbol: String,
        reusableDrawableIdsMatchPage: Boolean
    ): Boolean {
        val normalized = raw.uppercase()
        val pageIndex = if (value <= 9) value else value - 9
        val expectedDrawableId = if (pageIndex == 1) "DRAWABLEVIEW" else "DRAWABLEVIEW$pageIndex"
        return normalized == symbol ||
            normalized.endsWith("_$symbol") ||
            normalized.endsWith("BUTTON$symbol") ||
            normalized.endsWith("VIEW$symbol") ||
            (reusableDrawableIdsMatchPage && normalized == expectedDrawableId)
    }

    private fun resolveTapBounds(node: AccessibilityNodeInfo): Rect {
        var cursor: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var selected: AccessibilityNodeInfo? = null
        try {
            while (cursor != null) {
                if (cursor.isClickable) {
                    selected = AccessibilityNodeInfo.obtain(cursor)
                    break
                }
                val parent = cursor.parent
                cursor.recycle()
                cursor = parent
            }
            val target = selected ?: node
            return Rect().also(target::getBoundsInScreen)
        } finally {
            cursor?.recycle()
            selected?.recycle()
        }
    }

    private fun isOutsideBoard(bounds: Rect): Boolean {
        val board = activeBoardBounds ?: return true
        return !board.contains(bounds.centerX(), bounds.centerY())
    }

    private suspend fun performTap(x: Int, y: Int): Boolean {
        ensureNotStopped()
        repeat(GESTURE_MAX_ATTEMPTS) { attempt ->
            if (dispatchTapOnce(x, y)) return true
            if (attempt + 1 < GESTURE_MAX_ATTEMPTS) {
                delay(GESTURE_RETRY_DELAY_MS)
                ensureNotStopped()
            }
        }
        Log.w(TAG, "Tap gesture failed after $GESTURE_MAX_ATTEMPTS attempts at ($x,$y)")
        return false
    }

    private suspend fun dispatchTapOnce(x: Int, y: Int): Boolean {
        return withTimeoutOrNull(GESTURE_CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0,
                        adaptivePacing?.pace?.gestureMs ?: TAP_DURATION_MS))
                    .build()
                val accepted = accessibilityService.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                    null
                )
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
        } ?: false
    }

    private suspend fun dispatchSwipeOnce(gesture: GestureDescription): Boolean {
        return withTimeoutOrNull(GESTURE_CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val accepted = accessibilityService.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                    null
                )
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
        }
            ?: false
    }

    private suspend fun switchKeypadPage(showLetters: Boolean) {
        ensureNotStopped()
        val board = activeBoardBounds ?: return
        val displayMetrics = accessibilityService.resources.displayMetrics
        val captureDimensions = ScreenCaptureService.instance?.captureDimensions()
        val screenWidth = maxOf(captureDimensions?.first ?: displayMetrics.widthPixels, board.right)
        val screenHeight = maxOf(captureDimensions?.second ?: displayMetrics.heightPixels, board.bottom + 1)
        val keypadBounds = findVisibleKeypadBounds()
        val keypadLeft = keypadBounds?.left ?: 0
        val keypadWidth = (keypadBounds?.width() ?: screenWidth).coerceAtLeast(1)
        val startX = keypadLeft + (keypadWidth * if (showLetters) 0.78f else 0.22f).toInt()
        val endX = keypadLeft + (keypadWidth * if (showLetters) 0.22f else 0.78f).toInt()
        val keypadY = keypadBounds?.centerY()?.toFloat()
            ?: (board.bottom + ((screenHeight - board.bottom) * KEYPAD_VERTICAL_FRACTION))
        val path = Path().apply {
            moveTo(startX.toFloat(), keypadY)
            lineTo(endX.toFloat(), keypadY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, KEYPAD_SWIPE_DURATION_MS))
            .build()
        repeat(GESTURE_MAX_ATTEMPTS) { attempt ->
            if (dispatchSwipeOnce(gesture)) return
            if (attempt + 1 < GESTURE_MAX_ATTEMPTS) delay(GESTURE_RETRY_DELAY_MS)
        }
        Log.w(TAG, "Keypad swipe was rejected after $GESTURE_MAX_ATTEMPTS attempts")
    }

    private fun findVisibleKeypadBounds(): Rect? {
        val root = accessibilityService.rootInActiveWindow ?: return null
        return try {
            findKeypadBounds(root)
        } finally {
            root.recycle()
        }
    }

    private fun findKeypadBounds(node: AccessibilityNodeInfo): Rect? {
        val id = node.viewIdResourceName?.substringAfter(":id/", "")
        if (node.isVisibleToUser && id.equals("inputLayoutSv", ignoreCase = true)) {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.width() > 2 && bounds.height() > 2 && isOutsideBoard(bounds)) return bounds
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val match = findKeypadBounds(child)
            child.recycle()
            if (match != null) return match
        }
        return null
    }

    private suspend fun ensureNotStopped() {
        // Walking the full accessibility tree before both taps cost roughly
        // 180 ms per symbol on the JAD-LX9. Grouped result/error verification
        // remains authoritative, so poll completion at a bounded interval.
        val nowMs = SystemClock.elapsedRealtime()
        if (!huaweiJadLx9 || nowMs - lastCompletionCheckAtMs >= HUAWEI_COMPLETION_CHECK_INTERVAL_MS) {
            lastCompletionCheckAtMs = nowMs
            beforeInput()
        }
        currentCoroutineContext().ensureActive()
        if (stopRequested) throw CancellationException("16x16 auto-input stopped")
    }

    companion object {
        private const val RED_VERIFY_BATCH_SIZE = 16
        private const val TAG = "HexadokuInput"
        private const val CELL_SETTLE_DELAY_MS = 60L
        private const val SYMBOL_SETTLE_DELAY_MS = 30L
        private const val TAP_DURATION_MS = 50L
        private const val SAMSUNG_A07_CELL_SETTLE_DELAY_MS = 0L
        private const val SAMSUNG_A07_SYMBOL_SETTLE_DELAY_MS = 0L
        private const val SAMSUNG_A07_TAP_DURATION_MS = 1L
        private const val HUAWEI_JAD_CELL_SETTLE_DELAY_MS = 10L
        private const val HUAWEI_JAD_SYMBOL_SETTLE_DELAY_MS = 5L
        private const val HUAWEI_JAD_TAP_DURATION_MS = 1L
        private const val HUAWEI_COMPLETION_CHECK_INTERVAL_MS = 500L
        private const val KEYPAD_PAGE_SETTLE_DELAY_MS = 500L
        private const val KEYPAD_SWIPE_DURATION_MS = 240L
        private const val KEYPAD_VERTICAL_FRACTION = 0.55f
        private const val SYMBOL_LOOKUP_ATTEMPTS = 4
        private const val SYMBOL_LOOKUP_AFTER_SWIPE_ATTEMPTS = 6
        private const val SYMBOL_LOOKUP_RETRY_DELAY_MS = 180L
        private const val ERROR_CONFIRM_DELAY_MS = 120L
        private const val ERROR_SAMPLE_MARGIN = 0.15f
        private const val ERROR_SAMPLE_STEP = 2
        private const val MIN_ERROR_RED = 165
        private const val MIN_ERROR_CHANNEL_GAP = 45
        private const val MIN_ERROR_RED_RATIO = 0.025f
        private const val MIN_ERROR_RED_PIXELS = 8
        private const val GESTURE_MAX_ATTEMPTS = 3
        private const val GESTURE_RETRY_DELAY_MS = 120L
        private const val GESTURE_CALLBACK_TIMEOUT_MS = 1_500L
    }
}
