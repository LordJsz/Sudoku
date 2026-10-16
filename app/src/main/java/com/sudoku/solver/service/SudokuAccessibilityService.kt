package com.sudoku.solver.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import com.sudoku.solver.model.DetectedBoard
import com.sudoku.solver.model.HexadokuGrid
import com.sudoku.solver.model.OcrResult
import com.sudoku.solver.model.SudokuGrid
import com.sudoku.solver.processing.AutoInputController
import com.sudoku.solver.processing.BoardDetector
import com.sudoku.solver.processing.DigitInkPolicy
import com.sudoku.solver.processing.DigitRecognizer
import com.sudoku.solver.processing.HexadokuAutoInputController
import com.sudoku.solver.processing.HexadokuOcrRecovery
import com.sudoku.solver.processing.HexadokuRecognizer
import com.sudoku.solver.processing.HexadokuScanQualityPolicy
import com.sudoku.solver.processing.HexadokuSolveStatus
import com.sudoku.solver.processing.HexadokuSolver
import com.sudoku.solver.processing.HintSolutionPolicy
import com.sudoku.solver.processing.InputResult
import com.sudoku.solver.processing.GameInputPolicy
import com.sudoku.solver.processing.GameCompletionGate
import com.sudoku.solver.processing.GameAutoCompletionException
import com.sudoku.solver.processing.KeyCellGuidancePolicy
import com.sudoku.solver.processing.OcrConsensus
import com.sudoku.solver.processing.ScreenAutomationPolicy
import com.sudoku.solver.processing.SudokuSolver
import com.sudoku.solver.processing.ValidationResult
import com.sudoku.solver.processing.ValidationStatus
import com.sudoku.solver.util.Constants
import com.sudoku.solver.util.OcrScanAreaPrefs
import kotlinx.coroutines.*
import java.io.File

/**
 * Accessibility service that orchestrates the Sudoku solving process
 */
class SudokuAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isSolving = false
    private val gameCompletionGate = GameCompletionGate()
    @Volatile private var lastGameCompletionPollAtMs = 0L
    @Volatile
    private var isHexadokuMode = false
    private var solvingJob: Job? = null
    private var autoResumeJob: Job? = null
    private var keyGuidanceMonitorJob: Job? = null
    private var automaticKeyHintJob: Job? = null
    private var screenAutomationJob: Job? = null
    private var screenAutomationFollowUpJob: Job? = null
    private var autoBoardStartJob: Job? = null
    private var lastAutoResumeAtMs: Long = 0L
    private var lastRewardClickAtMs: Long = 0L
    private var lastPuzzleFlowClickAtMs: Long = 0L
    private var lastPuzzleFlowTargetId: String? = null
    private var autoBoardWasVisible = false
    private var autoBoardMode = ScreenAutomationPolicy.AutomaticBoardMode.NONE
    @Volatile
    private var suppressAutoHexadokuUntilBoardLeaves = false
    @Volatile
    private var stopRequested = false
    private val keyGuidanceLock = Any()
    private var keyGuidanceBoardBounds: Rect? = null
    private val pendingKeyCells = ArrayDeque<KeyCellGuidanceItem>()
    private var lastGuidanceTap: Pair<Int, Int>? = null
    private var lastGuidanceTapAtMs: Long = 0L
    private var hasLoggedMissingScanArea = false
    private var keyGuidanceOverlayView: TextView? = null
    private var keyGuidanceWindowManager: WindowManager? = null
    private var keyGuidanceTotal: Int = 0
    private val keyGuidancePendingChecks = mutableSetOf<Pair<Int, Int>>()
    private val confirmedKeyCellOptions = mutableMapOf<Pair<Int, Int>, Set<Int>>()

    // Processing components
    private lateinit var boardDetector: BoardDetector
    private lateinit var digitRecognizer: DigitRecognizer
    private lateinit var sudokuSolver: SudokuSolver
    private lateinit var autoInputController: AutoInputController
    // These remain null throughout normal 9x9 use and are created only when the
    // dedicated 16x16 button is pressed.
    private var hexadokuRecognizer: HexadokuRecognizer? = null
    private var hexadokuSolver: HexadokuSolver? = null
    private var hexadokuAutoInputController: HexadokuAutoInputController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.w(TAG, "Accessibility service connected")
        instance = this

        // Initialize processing components
        boardDetector = BoardDetector()
        digitRecognizer = DigitRecognizer(applicationContext)
        sudokuSolver = SudokuSolver()
        autoInputController = AutoInputController(this, ::checkGameAutoCompletion)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val completionTextMatched = event.text.any(GameInputPolicy::isAutoCompleting) ||
            GameInputPolicy.isAutoCompleting(event.contentDescription)
        val completionSourceVisible = if (!completionTextMatched &&
            event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            event.packageName?.toString() == GameInputPolicy.GAME_PACKAGE) {
            event.source?.let { source ->
                try { source.isVisibleToUser && GameInputPolicy.isAutoCompletingViewId(source.viewIdResourceName) }
                finally { source.recycle() }
            } ?: false
        } else false
        val completionTexts = event.text + listOf(event.contentDescription) +
            if (completionSourceVisible) listOf("Autocompletando") else emptyList()
        if (gameCompletionGate.notice(event.packageName?.toString(), completionTexts)) {
            pauseForGameAutoCompletion()
        }
        if (gameCompletionGate.waiting) {
            scheduleScreenAutomation(event)
            return
        }
        maybeHandleKeyCellTap(event)
        scheduleScreenAutomation(event)
    }

    override fun onInterrupt() {
        Log.i(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        solvingJob?.cancel()
        autoResumeJob?.cancel()
        autoResumeJob = null
        keyGuidanceMonitorJob?.cancel()
        keyGuidanceMonitorJob = null
        automaticKeyHintJob?.cancel()
        automaticKeyHintJob = null
        screenAutomationJob?.cancel()
        screenAutomationJob = null
        screenAutomationFollowUpJob?.cancel()
        screenAutomationFollowUpJob = null
        autoBoardStartJob?.cancel()
        autoBoardStartJob = null
        hideKeyGuidanceOverlay()
        serviceScope.cancel()
        if (::digitRecognizer.isInitialized) {
            digitRecognizer.close()
        }
        hexadokuRecognizer?.close()
        hexadokuRecognizer = null
        hexadokuSolver = null
        hexadokuAutoInputController = null
        // Samsung may bind a replacement service instance before destroying the
        // previous one. Do not let the stale instance clear the active connection.
        if (instance === this) {
            instance = null
        }
    }

    /**
     * Start the Sudoku solving process
     */
    fun startSolving(preserveKeyCellConfirmations: Boolean = false) {
        if (gameCompletionGate.waiting) {
            showToast("Autocompletando: letting the game finish.")
            return
        }
        if (solvingJob?.isActive == true || isSolving) {
            showToast("Already solving...")
            return
        }

        isHexadokuMode = false
        autoResumeJob?.cancel()
        autoResumeJob = null
        stopRequested = false
        isSolving = true
        if (!preserveKeyCellConfirmations) {
            clearConfirmedKeyCells()
        }
        clearKeyCellGuidance()
        autoInputController.clearStopRequest()
        sudokuSolver.clearStopRequest()

        solvingJob = serviceScope.launch {
            try {
                solveSudoku()
            } catch (e: CancellationException) {
                Log.i(TAG, "Solving cancelled")
                showToast(if (gameCompletionGate.waiting) "Autocompletando: letting the game finish." else "Solving stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error during solving", e)
                showToast("Error: ${e.message}")
            } finally {
                isSolving = false
                solvingJob = null
                stopRequested = false
                autoInputController.clearStopRequest()
                sudokuSolver.clearStopRequest()
            }
        }
    }

    /**
     * Starts the isolated 16x16 (1-9, A-G) pipeline used by automatic board
     * detection and by the second overlay button.
     * The normal 9x9 startSolving() path above is intentionally unchanged.
     */
    fun startSolving16x16() {
        if (gameCompletionGate.waiting) {
            showToast("Autocompletando: letting the game finish.")
            return
        }
        if (solvingJob?.isActive == true || isSolving) {
            showToast("Already solving...")
            return
        }

        // Publish the running state before first-use OCR initialization so the
        // orange overlay button changes to STOP immediately after the tap.
        isHexadokuMode = true
        autoResumeJob?.cancel()
        autoResumeJob = null
        stopRequested = false
        isSolving = true
        clearConfirmedKeyCells()
        clearKeyCellGuidance()
        hexadokuSolver?.clearStopRequest()
        hexadokuAutoInputController?.clearStopRequest()

        solvingJob = serviceScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    if (hexadokuSolver == null) {
                        hexadokuSolver = HexadokuSolver()
                    }
                    if (hexadokuAutoInputController == null) {
                        hexadokuAutoInputController = HexadokuAutoInputController(this@SudokuAccessibilityService, ::checkGameAutoCompletion)
                    }
                    if (hexadokuRecognizer == null) {
                        hexadokuRecognizer = HexadokuRecognizer(applicationContext)
                    }
                    hexadokuSolver?.clearStopRequest()
                    hexadokuAutoInputController?.clearStopRequest()
                }
                solveHexadoku()
            } catch (error: CancellationException) {
                Log.i(TAG, "16x16 solving cancelled")
                showToast(if (gameCompletionGate.waiting) "Autocompletando: letting the game finish." else "16x16 solving stopped")
            } catch (error: Exception) {
                Log.e(TAG, "Error during 16x16 solve", error)
                writeHexadokuRunStatus(
                    "EXCEPTION type=${error::class.java.simpleName} message=${error.message.orEmpty()}"
                )
                showToast("16x16 error: ${error.message}")
            } finally {
                // A transient accessibility-tree refresh can briefly hide the board.
                // Never auto-run again on a board this pipeline already touched.
                suppressAutoHexadokuUntilBoardLeaves = true
                isSolving = false
                isHexadokuMode = false
                solvingJob = null
                stopRequested = false
                hexadokuSolver?.clearStopRequest()
                hexadokuAutoInputController?.clearStopRequest()
            }
        }
    }

    fun stopSolving() {
        val hasPendingGuidance = synchronized(keyGuidanceLock) { pendingKeyCells.isNotEmpty() }
        if (solvingJob?.isActive != true && !isSolving && !hasPendingGuidance) {
            showToast("No solve in progress")
            return
        }
        Log.i(TAG, "Stop requested by user")
        autoResumeJob?.cancel()
        autoResumeJob = null
        lastAutoResumeAtMs = 0L
        stopRequested = true
        isSolving = false // Update button state immediately
        clearConfirmedKeyCells()
        clearKeyCellGuidance()
        autoInputController.requestStop()
        sudokuSolver.requestStop()
        autoInputController.clearCache()
        hexadokuSolver?.requestStop()
        hexadokuAutoInputController?.requestStop()
        hexadokuAutoInputController?.clearCache()
        solvingJob?.cancel(CancellationException("Stopped by user"))
    }

    fun isCurrentlySolving(): Boolean {
        return !stopRequested && (isSolving || solvingJob?.isActive == true)
    }

    fun isCurrentlySolving16x16(): Boolean {
        return isHexadokuMode && isCurrentlySolving()
    }

    private suspend fun ensureNotStopped() {
        checkGameAutoCompletion()
        currentCoroutineContext().ensureActive()
        if (stopRequested) {
            throw CancellationException("Stopped by user")
        }
    }

    fun isWaitingForGameCompletion(): Boolean = gameCompletionGate.waiting

    /** Called immediately before input as well as from accessibility notifications. */
    private fun checkGameAutoCompletion() {
        gameCompletionGate.ensureInputAllowed()
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - lastGameCompletionPollAtMs < 50L) return
        lastGameCompletionPollAtMs = nowMs
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != GameInputPolicy.GAME_PACKAGE) return
            val matches = root.findAccessibilityNodeInfosByText("complet") +
                GameInputPolicy.autoCompletingViewIds.flatMap(root::findAccessibilityNodeInfosByViewId)
            val found = try { matches.any { it.isVisibleToUser &&
                (GameInputPolicy.isAutoCompletingViewId(it.viewIdResourceName) ||
                    GameInputPolicy.isAutoCompleting(it.text) || GameInputPolicy.isAutoCompleting(it.contentDescription)) }
            } finally { matches.forEach { it.recycle() } }
            if (found && gameCompletionGate.notice(root.packageName?.toString(), listOf("Autocompletando"))) {
                pauseForGameAutoCompletion()
            }
        } finally { root.recycle() }
        gameCompletionGate.ensureInputAllowed()
    }

    private fun pauseForGameAutoCompletion() {
        Log.i(TAG, "Autocompletando detected; stopping input and automatic retries")
        isSolving = false
        autoResumeJob?.cancel()
        autoBoardStartJob?.cancel()
        automaticKeyHintJob?.cancel()
        screenAutomationFollowUpJob?.cancel()
        if (::autoInputController.isInitialized) autoInputController.requestStop()
        hexadokuAutoInputController?.requestStop()
        if (::sudokuSolver.isInitialized) sudokuSolver.requestStop()
        hexadokuSolver?.requestStop()
        solvingJob?.cancel(GameAutoCompletionException())
        serviceScope.launch {
            clearKeyCellGuidance()
            showToast("Autocompletando: letting the game finish.")
        }
    }

    private suspend fun solveSudoku() = withContext(Dispatchers.Default) {
        ensureNotStopped()

        // Step 1: Capture screen
        showToast("Capturing screen...")
        val screenshot = captureScreen()
        if (screenshot == null) {
            withContext(Dispatchers.Main) {
                showToast("Failed to capture screen")
            }
            return@withContext
        }

        // Step 2: Detect board
        ensureNotStopped()
        showToast("Detecting board...")
        var detectedBoard = detectBoardUsingConfiguredArea(screenshot)
        screenshot.recycle()

        if (detectedBoard == null) {
            clearKeyCellGuidance()
            withContext(Dispatchers.Main) {
                showToast("No Sudoku board detected")
            }
            return@withContext
        }

        // Step 3: Recognize digits
        ensureNotStopped()
        showToast("Recognizing digits...")
        var bestOcrCandidate = selectBestOcrCandidate(
            warpedBitmap = detectedBoard.warpedBitmap,
            passLabel = "scan-1"
        )

        if (shouldAutoRescanForOcr(bestOcrCandidate)) {
            Log.w(
                TAG,
                "Primary OCR candidate not reliable enough " +
                    "(status=${bestOcrCandidate.validation.status}, " +
                    "contradictions=${bestOcrCandidate.contradictionCount}, " +
                    "uncertain=${bestOcrCandidate.uncertainCount}). Trying one OCR re-scan."
            )
            withContext(Dispatchers.Main) {
                showToast("Re-scanning for better OCR...")
            }
            ensureNotStopped()
            val rescanScreenshot = captureScreen()
            if (rescanScreenshot != null) {
                val rescanBoard = detectBoardUsingConfiguredArea(rescanScreenshot)
                rescanScreenshot.recycle()
                if (rescanBoard != null) {
                    ensureNotStopped()
                    val rescanCandidate = selectBestOcrCandidate(
                        warpedBitmap = rescanBoard.warpedBitmap,
                        passLabel = "scan-2"
                    )
                    if (isCandidateBetter(rescanCandidate, bestOcrCandidate)) {
                        Log.w(
                            TAG,
                            "Using OCR candidate from re-scan (${rescanCandidate.label}) over initial " +
                                "(${bestOcrCandidate.label})"
                        )
                        detectedBoard.recycle()
                        detectedBoard = rescanBoard
                        bestOcrCandidate = rescanCandidate
                    } else {
                        Log.w(
                            TAG,
                            "Keeping initial OCR candidate (${bestOcrCandidate.label}); " +
                                "re-scan candidate (${rescanCandidate.label}) was not better"
                        )
                        rescanBoard.recycle()
                    }
                } else {
                    Log.w(TAG, "OCR re-scan failed to detect board; keeping first scan")
                }
            } else {
                Log.w(TAG, "OCR re-scan screenshot failed; keeping first scan")
            }
        }

        var ocrResult = bestOcrCandidate.ocrResult
        var grid = ocrResult.grid
        Log.w(TAG, "Chosen OCR candidate: ${bestOcrCandidate.label}")
        Log.w(TAG, "Extracted grid:\n$grid")
        Log.w(TAG, "OCR uncertain cells: ${ocrResult.uncertainCells.size} ${ocrResult.uncertainCells}")

        // Check clue count
        var clueCount = grid.values.sumOf { row -> row.count { it != 0 } }
        Log.w(TAG, "Puzzle has $clueCount clues")

        if (clueCount < 10) {
            clearKeyCellGuidance()
            withContext(Dispatchers.Main) {
                showToast("Only $clueCount digits detected - OCR may have failed. Try again.")
            }
            detectedBoard.recycle()
            return@withContext
        }

        // Step 4: Solve puzzle
        ensureNotStopped()
        showToast("Solving ($clueCount clues)...")
        var result = bestOcrCandidate.validation
        ensureNotStopped()

        // Only clear uncertain OCR clues when solving fails due contradictions/unsolvable.
        // Do not aggressively clear upfront, otherwise we can drop below minimum clue count.
        if (
            (result.status == ValidationStatus.HAS_CONTRADICTIONS || result.status == ValidationStatus.UNSOLVABLE) &&
            ocrResult.uncertainCells.isNotEmpty()
        ) {
            val targetedRefinedGrid = clearUncertainClues(
                grid = grid,
                uncertainCells = ocrResult.uncertainCells,
                preferredCells = result.problemCells,
                minCluesToKeep = 17,
                onlyPreferred = true,
                maxClears = 2
            )
            val refinedGrid = if (targetedRefinedGrid !== grid) {
                targetedRefinedGrid
            } else {
                clearUncertainClues(
                    grid = grid,
                    uncertainCells = ocrResult.uncertainCells,
                    preferredCells = result.problemCells,
                    minCluesToKeep = 17,
                    onlyPreferred = false,
                    maxClears = 3
                )
            }
            if (refinedGrid !== grid) {
                val refinedClueCount = refinedGrid.values.sumOf { row -> row.count { it != 0 } }
                Log.w(
                    TAG,
                    "Retrying solve after selective uncertain-clue cleanup. " +
                        "Clues: $clueCount -> $refinedClueCount"
                )
                Log.w(TAG, "Grid after selective uncertain OCR cleanup:\n$refinedGrid")
                withContext(Dispatchers.Main) {
                    showToast("Retrying solve with OCR cleanup...")
                }
                grid = refinedGrid
                ensureNotStopped()
                result = sudokuSolver.validateAndSolve(grid)
                ensureNotStopped()
            }
        }

        // Safety guard: if a "success" required editing non-uncertain or non-confused clues,
        // OCR is likely unstable for this board. Retry with broader uncertain-clue cleanup.
        if (result.status == ValidationStatus.SUCCESS && result.corrections.isNotEmpty()) {
            val uncertainSet = ocrResult.uncertainCells.toSet()
            val unsafeCorrections = result.corrections.filter { correction ->
                val position = Pair(correction.row, correction.col)
                // Unsafe = correcting a cell OCR was confident about.
                // Uncertain cells are fair game - solver only tries plausible OCR swaps.
                position !in uncertainSet
            }

            if (unsafeCorrections.isNotEmpty()) {
                Log.w(
                    TAG,
                    "Non-uncertain corrections (${unsafeCorrections.size}): " +
                        unsafeCorrections.joinToString(", ") {
                            "(${it.row},${it.col}) ${it.oldValue}->${it.newValue}"
                        }
                )
            }

            // Allow 1 confident-cell correction backed by uniqueness check.
            // 2+ confident corrections suggests widespread OCR errors - retry.
            if (unsafeCorrections.size > 1) {
                val saferRefinedGrid = clearUncertainClues(
                    grid = grid,
                    uncertainCells = ocrResult.uncertainCells,
                    preferredCells = unsafeCorrections.map { Pair(it.row, it.col) },
                    minCluesToKeep = 22,
                    onlyPreferred = false,
                    maxClears = 8
                )

                if (saferRefinedGrid !== grid) {
                    Log.w(
                        TAG,
                        "Retrying after unsafe correction guard. " +
                            "Clues: ${grid.values.sumOf { row -> row.count { it != 0 } }} -> " +
                            saferRefinedGrid.values.sumOf { row -> row.count { it != 0 } }
                    )
                    grid = saferRefinedGrid
                    ensureNotStopped()
                    result = sudokuSolver.validateAndSolve(grid)
                    ensureNotStopped()
                }

                val retryUnsafe = result.corrections.filter { correction ->
                    val position = Pair(correction.row, correction.col)
                    position !in uncertainSet
                }
                if (result.status != ValidationStatus.SUCCESS || retryUnsafe.size > 1) {
                    if (retryUnsafe.isNotEmpty()) {
                        Log.w(
                            TAG,
                            "Retry still has unsafe corrections: " +
                                retryUnsafe.joinToString(", ") {
                                    "(${it.row},${it.col}) ${it.oldValue}->${it.newValue}"
                                }
                        )
                    }
                    Log.w(TAG, "Unsafe correction guard still failing; continuing with deep OCR recovery path")
                }
            }
        }

        // Guardrail: if OCR needed many auto-corrections on a highly uncertain board,
        // re-check with broader uncertain-clue cleanup before any auto-input.
        if (
            result.status == ValidationStatus.SUCCESS &&
            result.corrections.size >= 5 &&
            ocrResult.uncertainCells.size >= 14
        ) {
            val guardrailClueCount = grid.values.sumOf { row -> row.count { it != 0 } }
            val preferredFromCorrections = result.corrections.map { Pair(it.row, it.col) }
            val aggressiveRefinedGrid = clearUncertainClues(
                grid = grid,
                uncertainCells = ocrResult.uncertainCells,
                preferredCells = preferredFromCorrections,
                minCluesToKeep = 24,
                onlyPreferred = false,
                maxClears = 4
            )
            if (aggressiveRefinedGrid !== grid) {
                Log.w(
                    TAG,
                    "High OCR correction count (${result.corrections.size}) with ${ocrResult.uncertainCells.size} uncertain cells. " +
                        "Rechecking solution after broader uncertain-clue cleanup. " +
                        "Clues: $guardrailClueCount -> ${aggressiveRefinedGrid.values.sumOf { row -> row.count { it != 0 } }}"
                )
                grid = aggressiveRefinedGrid
                ensureNotStopped()
                result = sudokuSolver.validateAndSolve(grid)
                ensureNotStopped()
            }

            if (result.status != ValidationStatus.SUCCESS) {
                Log.w(TAG, "Guardrail retry still not solved; continuing with deep OCR recovery path")
            }
        }

        // Last automatic recovery before asking for manual intervention:
        // 1) Progressive uncertain-clue cleanup sweeps on the current OCR.
        // 2) One extra OCR re-scan pass, then cleanup sweeps again.
        if (result.status != ValidationStatus.SUCCESS) {
            val recovered = tryProgressiveUncertainRecovery(
                baseGrid = grid,
                baseResult = result,
                uncertainCells = ocrResult.uncertainCells
            )
            if (recovered != null) {
                val (recoveredGrid, recoveredResult) = recovered
                grid = recoveredGrid
                result = recoveredResult
                clueCount = grid.values.sumOf { row -> row.count { it != 0 } }
                Log.w(
                    TAG,
                    "Applied progressive uncertain-clue recovery. " +
                        "status=${result.status}, clues=$clueCount, corrections=${result.corrections.size}"
                )
            }
        }

        if (result.status != ValidationStatus.SUCCESS && shouldAttemptDeepRecovery(result, ocrResult.uncertainCells)) {
            withContext(Dispatchers.Main) {
                showToast("OCR still unstable. Trying one deep recovery scan...")
            }
            ensureNotStopped()
            val deepRecoveryScan = captureAndEvaluateOcrCandidate("scan-3")
            if (deepRecoveryScan != null) {
                val (rescanBoard, rescanCandidate) = deepRecoveryScan
                val preferRescan =
                    rescanCandidate.validation.status == ValidationStatus.SUCCESS ||
                        isCandidateBetter(rescanCandidate, bestOcrCandidate) ||
                        compareValidationResultForRecovery(rescanCandidate.validation, result) > 0

                if (preferRescan) {
                    Log.w(
                        TAG,
                        "Using deep recovery OCR candidate ${rescanCandidate.label} over ${bestOcrCandidate.label}"
                    )
                    detectedBoard.recycle()
                    detectedBoard = rescanBoard
                    bestOcrCandidate = rescanCandidate
                    ocrResult = rescanCandidate.ocrResult
                    grid = ocrResult.grid
                    result = rescanCandidate.validation
                    clueCount = grid.values.sumOf { row -> row.count { it != 0 } }

                    val recovered = tryProgressiveUncertainRecovery(
                        baseGrid = grid,
                        baseResult = result,
                        uncertainCells = ocrResult.uncertainCells
                    )
                    if (recovered != null) {
                        val (recoveredGrid, recoveredResult) = recovered
                        grid = recoveredGrid
                        result = recoveredResult
                        clueCount = grid.values.sumOf { row -> row.count { it != 0 } }
                    }
                } else {
                    Log.w(
                        TAG,
                        "Deep recovery scan ${rescanCandidate.label} was not better than current candidate ${bestOcrCandidate.label}"
                    )
                    rescanBoard.recycle()
                }
            } else {
                Log.w(TAG, "Deep recovery scan failed; continuing with current OCR candidate")
            }
        }

        writeSudokuScanDiagnostics(ocrResult, result, detectedBoard.screenBounds, bestOcrCandidate.label)

        if (result.status != ValidationStatus.SUCCESS) {
            ensureNotStopped()
            val guidanceShown = if (
                result.status == ValidationStatus.MULTIPLE_SOLUTIONS &&
                result.keyCellRecommendations.isNotEmpty()
            ) {
                setKeyCellGuidance(
                    boardBounds = detectedBoard.screenBounds,
                    recommendations = result.keyCellRecommendations,
                    occupiedCells = ocrResult.occupiedCells
                )
            } else {
                clearKeyCellGuidance()
                false
            }
            withContext(Dispatchers.Main) {
                if (!guidanceShown) {
                    val message = if (result.status == ValidationStatus.MULTIPLE_SOLUTIONS) {
                        "OCR still sees multiple solutions, but every suggested key cell is already occupied. " +
                            "No cell was requested; try a fresh scan or manual entry."
                    } else {
                        result.message
                    }
                    showToast(message)
                }
            }
            detectedBoard.recycle()
            return@withContext
        }

        // Constraint repair can solve a different puzzle. Never type such a result
        // into the photographed board, or use a search timeout as proof of uniqueness.
        val inputBlockReason = com.sudoku.solver.processing.OcrInputSafety.rejectionReason(ocrResult, result)
        if (inputBlockReason != null) {
            Log.w(TAG, "Blocked unsafe OCR input: corrections=${result.corrections.size}, " +
                "timeout=${result.solutionIsTimeout}; solution must preserve all scanned clues")
            showToast(inputBlockReason)
            detectedBoard.recycle()
            return@withContext
        }

        // Use corrected grid if auto-corrections were made
        val correctedGridValues = result.correctedGrid
        val gridToUse = if (correctedGridValues != null) {
            val correctedLocked = Array(9) { row ->
                BooleanArray(9) { col -> correctedGridValues[row][col] != 0 }
            }
            SudokuGrid(correctedGridValues, correctedLocked)
        } else {
            grid
        }

        // Log auto-corrections if any
        if (result.corrections.isNotEmpty()) {
            Log.i(TAG, "Auto-corrected OCR: ${result.corrections.map { "(${it.row},${it.col}): ${it.oldValue}→${it.newValue}" }}")
        }

        // Step 5: Auto-input solution with error detection
        val solvedGrid = result.solution
        if (solvedGrid == null) {
            ensureNotStopped()
            withContext(Dispatchers.Main) {
                showToast("No solved grid returned. Please re-scan.")
            }
            detectedBoard.recycle()
            return@withContext
        }

        showToast("Auto-inputting solution...")
        ensureNotStopped()
        val inputResult = inputSolutionWithAutomaticHintRecovery(
            gridToUse,
            solvedGrid,
            detectedBoard.screenBounds
        )

        if (inputResult.gameEnded) {
            detectedBoard.recycle()
            return@withContext
        }

        if (inputResult.success) {
            clearConfirmedKeyCells()
            clearKeyCellGuidance()
            withContext(Dispatchers.Main) {
                showToast("Puzzle solved!")
            }
            detectedBoard.recycle()
            return@withContext
        }

        // A rejected digit must never leave the overlay waiting for another
        // manual press. Recovery above gets the first chance to correct/resume;
        // if it gives up, clear the red entry when possible and start a fresh
        // OCR/solve pass as soon as this job has released the running state.
        if (inputResult.errorCell != null && !inputResult.entryMissing) {
            val (row, col, digit) = inputResult.errorCell
            Log.w(TAG, "Wrong digit $digit remained at r${row + 1}c${col + 1}; scheduling full solver restart")
            if (autoInputController.isCellMarkedWrong(row, col, detectedBoard.screenBounds)) {
                clearWrongEntryWithoutHint(row, col, detectedBoard.screenBounds, 9)
            }
            clearKeyCellGuidance()
            showToast("Wrong digit detected. Restarting solver automatically...")
            scheduleAutoResumeSolve("wrong digit at r${row + 1}c${col + 1}")
            detectedBoard.recycle()
            return@withContext
        }

        if (inputResult.selectionFailed) {
            Log.e(TAG, "Auto-input failed: ${inputResult.failureMessage}")
            clearKeyCellGuidance()
            withContext(Dispatchers.Main) {
                showToast("Could not find digit keypad. Re-open puzzle and try again.")
            }
            detectedBoard.recycle()
            return@withContext
        }

        if (inputResult.failureMessage != null) {
            Log.e(TAG, "Automatic PISTA recovery failed: ${inputResult.failureMessage}")
            clearKeyCellGuidance()
            withContext(Dispatchers.Main) {
                showToast(inputResult.failureMessage)
            }
            detectedBoard.recycle()
            return@withContext
        }

        // Error detected - stop and try alternative solution
        val errorCell = inputResult.errorCell ?: run {
            withContext(Dispatchers.Main) {
                showToast("Input failed unexpectedly. Please retry.")
            }
            detectedBoard.recycle()
            return@withContext
        }
        Log.e(TAG, "Error detected at (${errorCell.first},${errorCell.second}) for digit ${errorCell.third}")
        withContext(Dispatchers.Main) {
            showToast("Wrong digit detected. Undo it, then tap SOLVE to re-scan.")
        }

        // Track all wrong digits we've discovered
        val wrongDigits = mutableListOf(errorCell)
        var attempts = 0
        // Never keep guessing after the game confirms a wrong digit. Previous
        // retries could accumulate several visible mistakes on the user's board.
        val maxAttempts = 0

        while (attempts < maxAttempts) {
            ensureNotStopped()
            attempts++
            Log.i(TAG, "Retry attempt $attempts with ${wrongDigits.size} known wrong digits")

            // Find alternative solution avoiding all known wrong digits
            ensureNotStopped()
            val alternativeSolution = sudokuSolver.solveWithWrongDigits(
                gridToUse.values,
                wrongDigits
            )

            if (alternativeSolution == null) {
                Log.e(TAG, "No alternative solution found")
                clearKeyCellGuidance()
                withContext(Dispatchers.Main) {
                    showToast("No alternative solution. Check OCR manually.")
                }
                break
            }

            Log.i(TAG, "Found alternative solution!")
            ensureNotStopped()
            delay(500)

            // Re-capture screen to get current board state
            ensureNotStopped()
            val newScreenshot = captureScreen()
            if (newScreenshot == null) {
                clearKeyCellGuidance()
                withContext(Dispatchers.Main) {
                    showToast("Failed to capture screen for retry")
                }
                break
            }

            val newBoard = detectBoardUsingConfiguredArea(newScreenshot)
            newScreenshot.recycle()

            if (newBoard == null) {
                clearKeyCellGuidance()
                withContext(Dispatchers.Main) {
                    showToast("Board not detected for retry")
                }
                break
            }

            // Clear button cache since UI might have changed
            autoInputController.clearCache()

            // Re-input with the alternative solution
            ensureNotStopped()
            withContext(Dispatchers.Main) {
                showToast("Trying alternative solution...")
            }

            ensureNotStopped()
            val retryResult = autoInputController.inputSolution(
                gridToUse,
                alternativeSolution,
                newBoard.screenBounds,
                checkForErrors = true
            )
            newBoard.recycle()

            if (retryResult.success) {
                clearKeyCellGuidance()
                withContext(Dispatchers.Main) {
                    showToast("Puzzle solved with alternative!")
                }
                break
            } else {
                if (retryResult.selectionFailed) {
                    Log.e(TAG, "Retry failed due to digit keypad lookup: ${retryResult.failureMessage}")
                    clearKeyCellGuidance()
                    withContext(Dispatchers.Main) {
                        showToast("Digit keypad not found during retry. Please re-open puzzle.")
                    }
                    break
                }

                // Another error - add to our list and try again
                val newError = retryResult.errorCell
                if (newError == null) {
                    clearKeyCellGuidance()
                    withContext(Dispatchers.Main) {
                        showToast("Retry failed unexpectedly. Please re-scan.")
                    }
                    break
                }
                val repeatedSameCell = wrongDigits.any { it.first == newError.first && it.second == newError.second }
                if (repeatedSameCell) {
                    Log.e(
                        TAG,
                        "Same cell (${newError.first},${newError.second}) failed again with a different digit; stopping retry loop"
                    )
                    clearKeyCellGuidance()
                    withContext(Dispatchers.Main) {
                        showToast("Same cell keeps failing. OCR is unstable, please re-scan.")
                    }
                    break
                }
                Log.e(TAG, "New error at (${newError.first},${newError.second}) for digit ${newError.third}")
                if (wrongDigits.contains(newError)) {
                    Log.e(TAG, "Repeated same wrong digit after retry; stopping to avoid infinite loop")
                    clearKeyCellGuidance()
                    withContext(Dispatchers.Main) {
                        showToast("Same wrong digit repeated. Please re-scan for better OCR.")
                    }
                    break
                }
                wrongDigits.add(newError)
                withContext(Dispatchers.Main) {
                    showToast("Another error. Trying again... ($attempts/$maxAttempts)")
                }
            }
        }

        if (attempts >= maxAttempts) {
            clearKeyCellGuidance()
            withContext(Dispatchers.Main) {
                showToast("Stopped after the first confirmed wrong digit. Undo it, then tap SOLVE.")
            }
        }

        detectedBoard.recycle()
    }

    private suspend fun solveHexadoku() {
        writeHexadokuRunStatus("START", append = false)
        var attempt = 1
        var previousRestartReason: String? = null
        val rejectedSymbols = mutableMapOf<Pair<Int, Int>, Set<Int>>()

        while (true) {
            ensureNotStopped()
            writeHexadokuRunStatus("ATTEMPT $attempt")
            val outcome = solveHexadokuAttempt(rejectedSymbols)
            if (!outcome.restartRequested) return

            // Re-reading an unchanged board cannot fix a deterministic OCR gap.
            // The old flow repeated the same non-unique scan six times, adding
            // roughly a minute without changing a single recognized clue.
            if (outcome.reason.startsWith("solver MULTIPLE_SOLUTIONS") &&
                outcome.reason == previousRestartReason
            ) {
                writeHexadokuRunStatus(
                    "REPEATED_SCAN_LIMIT attempts=$attempt reason=${outcome.reason}"
                )
                showToast(
                    "The 16x16 scan stayed ambiguous. Adjust the board zoom or check the unread symbols, then retry."
                )
                return
            }
            previousRestartReason = outcome.reason

            if (attempt >= MAX_HEXADOKU_SOLVE_ATTEMPTS) {
                writeHexadokuRunStatus(
                    "RECOVERY_LIMIT attempts=$attempt reason=${outcome.reason}"
                )
                val message = if (outcome.reason.startsWith("OCR insufficient clues:")) {
                    "Could not read enough 16x16 symbols after $attempt scans. " +
                        "Check that the full board is visible, then try again."
                } else {
                    "Could not read a valid 16x16 board after $attempt attempts. " +
                        "No PISTAS were used; start a fresh puzzle and try again."
                }
                showToast(message)
                return
            }

            writeHexadokuRunStatus(
                "RESTART nextAttempt=${attempt + 1} reason=${outcome.reason}"
            )
            showToast(outcome.restartMessage)
            delay(HEXADOKU_RESTART_DELAY_MS)
            attempt++
        }
    }

    private suspend fun solveHexadokuAttempt(
        rejectedSymbols: MutableMap<Pair<Int, Int>, Set<Int>>
    ): HexadokuAttemptOutcome = withContext(Dispatchers.Default) {
        ensureNotStopped()
        showToast("Capturing 16x16 board...")
        val screenshot = captureScreen()
        if (screenshot == null) {
            writeHexadokuRunStatus("CAPTURE_FAILED")
            showToast("Failed to capture screen")
            return@withContext HexadokuAttemptOutcome.finished()
        }

        ensureNotStopped()
        showToast("Detecting 16x16 board...")
        val detectedBoard = detectHexadokuBoardUsingConfiguredArea(screenshot)
        screenshot.recycle()
        if (detectedBoard == null) {
            writeHexadokuRunStatus("BOARD_DETECTION_FAILED")
            showToast("No 16x16 Sudoku board detected")
            return@withContext HexadokuAttemptOutcome.finished()
        }

        try {
            ensureNotStopped()
            requireNotNull(hexadokuAutoInputController)
                .findMarkedWrongCell(detectedBoard.screenBounds)?.let { (row, col) ->
                    return@withContext recoverUnexpectedHexadokuRedCell(
                        row, col, detectedBoard.screenBounds
                    )
                }
            showToast("Reading 1-9 and A-G...")
            val recognition = requireNotNull(hexadokuRecognizer).extractGrid(detectedBoard.warpedBitmap)
            writeHexadokuDiagnostics(recognition, detectedBoard.screenBounds)
            writeHexadokuRunStatus(
                "OCR occupied=${recognition.occupiedCells.size} recognized=${recognition.grid.filledCount()} " +
                    "unrecognized=${recognition.unrecognizedOccupiedCells.size}"
            )
            val missedCells = recognition.unrecognizedOccupiedCells.size
            if (missedCells > 0) {
                Log.w(TAG, "16x16 OCR left $missedCells occupied cells unread: ${recognition.unrecognizedOccupiedCells}")
                showToast("$missedCells filled symbol(s) unreadable; solving safely from the remaining clues...")
            }
            if (HexadokuScanQualityPolicy.isLikelyObstructed(missedCells)) {
                val reason = "OCR obstruction suspected: $missedCells occupied cells unread"
                Log.w(TAG, "$reason; waiting for a clean board before retry")
                return@withContext HexadokuAttemptOutcome.restart(
                    reason = reason,
                    restartMessage = "Board is still covered or animating. Waiting for a clean re-scan..."
                )
            }

            var grid = recognition.grid
            val clueCount = grid.filledCount()
            Log.w(
                TAG,
                "16x16 OCR: clues=$clueCount, lowConfidence=${recognition.lowConfidenceCells.size}\n$grid"
            )
            if (clueCount < MIN_HEXADOKU_CLUES) {
                val reason = "OCR insufficient clues: $clueCount of $MIN_HEXADOKU_CLUES required"
                writeHexadokuRunStatus(reason)
                return@withContext HexadokuAttemptOutcome.restart(
                    reason = reason,
                    restartMessage = "Only $clueCount symbols recognized; waiting for a clear 16x16 board..."
                )
            }

            ensureNotStopped()
            showToast("Solving 16x16 puzzle...")
            var result = requireNotNull(hexadokuSolver).solve(grid, forbiddenValues = rejectedSymbols)
            writeHexadokuRunStatus("SOLVER status=${result.status} message=${result.message}")
            if (result.status != HexadokuSolveStatus.SUCCESS) {
                ensureNotStopped()
                val recovery = HexadokuOcrRecovery.recover(
                    grid = grid,
                    solver = requireNotNull(hexadokuSolver),
                    forbiddenValues = rejectedSymbols
                )
                ensureNotStopped()
                if (recovery != null) {
                    grid = recovery.grid
                    result = recovery.solveResult
                    val cleared = recovery.clearedCells
                        .sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
                        .joinToString(",") { (row, col) -> "r${row + 1}c${col + 1}" }
                    Log.w(TAG, "16x16 OCR recovery cleared contradictory reads: $cleared")
                    writeHexadokuRunStatus("OCR_RECOVERY cleared=$cleared status=${result.status}")
                    showToast("Recovered ${recovery.clearedCells.size} conflicting OCR reads. Solving safely...")
                }
            }
            val solvedHexadoku = result.solution
            if (result.status != HexadokuSolveStatus.SUCCESS || solvedHexadoku == null) {
                Log.w(TAG, "16x16 solve failed: ${result.status}: ${result.message}")
                if (result.status == HexadokuSolveStatus.CANCELLED) {
                    showToast(result.message)
                    return@withContext HexadokuAttemptOutcome.finished()
                }
                val reason = "solver ${result.status}: ${result.message}"
                return@withContext HexadokuAttemptOutcome.restart(
                    reason = reason,
                    restartMessage = "Invalid 16x16 scan. Retrying without using PISTA..."
                )
            }

            ensureNotStopped()
            showToast("Entering 16x16 solution...")
            val input = requireNotNull(hexadokuAutoInputController).inputSolution(
                original = grid,
                solution = solvedHexadoku,
                boardBounds = detectedBoard.screenBounds
            )
            writeHexadokuRunStatus(
                "INPUT success=${input.success} count=${input.inputCount} message=${input.message}"
            )
            if (input.success) {
                requireNotNull(hexadokuAutoInputController)
                    .findMarkedWrongCell(detectedBoard.screenBounds)?.let { (row, col) ->
                        return@withContext recoverUnexpectedHexadokuRedCell(
                            row, col, detectedBoard.screenBounds
                        )
                    }
                showToast("16x16 puzzle solved! Entered ${input.inputCount} cells.")
            } else if (input.restartRequested) {
                Log.w(TAG, "16x16 input requested a full restart: ${input.message}")
                return@withContext HexadokuAttemptOutcome.restart(input.message)
            } else if (input.errorCell != null) {
                val (row, col, value) = input.errorCell
                val hinted = correctWrongEntryWithHint(
                    row = row,
                    col = col,
                    boardBounds = detectedBoard.screenBounds,
                    gridSize = HexadokuGrid.SIZE,
                    isStillMarkedWrong = {
                        requireNotNull(hexadokuAutoInputController).isCellMarkedWrong(
                            row, col, detectedBoard.screenBounds
                        )
                    }
                )
                if (hinted) {
                    Log.i(TAG, "PISTA corrected 16x16 r${row + 1}c${col + 1}; rescanning")
                    return@withContext HexadokuAttemptOutcome.restart(
                        reason = "PISTA corrected r${row + 1}c${col + 1}",
                        restartMessage = "PISTA corrected the symbol. Resuming 16x16..."
                    )
                }
                if (requireNotNull(hexadokuAutoInputController).isCellMarkedWrong(
                        row, col, detectedBoard.screenBounds
                    ) && !clearWrongEntryWithoutHint(
                        row, col, detectedBoard.screenBounds, HexadokuGrid.SIZE
                    )) {
                    showToast("Could not clear the wrong 16x16 symbol")
                    return@withContext HexadokuAttemptOutcome.finished()
                }
                val cell = row to col
                rejectedSymbols[cell] = rejectedSymbols[cell].orEmpty() + value
                if (rejectedSymbols.values.sumOf(Set<Int>::size) > MAX_NO_HINT_RECOVERIES) {
                    showToast("Too many 16x16 symbols were rejected to recover safely")
                    return@withContext HexadokuAttemptOutcome.finished()
                }
                return@withContext HexadokuAttemptOutcome.restart(
                    reason = "Rejected ${HexadokuGrid.symbolFor(value)} at r${row + 1}c${col + 1}",
                    restartMessage = "Pista unavailable. Excluding the rejected symbol and rescanning..."
                )
            } else {
                Log.w(TAG, "16x16 input stopped after ${input.inputCount} cells: ${input.message}")
                showToast(input.message)
            }
            HexadokuAttemptOutcome.finished()
        } finally {
            detectedBoard.recycle()
        }
    }

    private suspend fun recoverUnexpectedHexadokuRedCell(
        row: Int, col: Int, boardBounds: Rect
    ): HexadokuAttemptOutcome {
        Log.w(TAG, "16x16 board scan found red symbol at r${row + 1}c${col + 1}")
        val hinted = correctWrongEntryWithHint(
            row = row,
            col = col,
            boardBounds = boardBounds,
            gridSize = HexadokuGrid.SIZE,
            isStillMarkedWrong = {
                requireNotNull(hexadokuAutoInputController)
                    .isCellMarkedWrong(row, col, boardBounds)
            }
        )
        if (!hinted && !clearWrongEntryWithoutHint(row, col, boardBounds, HexadokuGrid.SIZE)) {
            showToast("Could not clear wrong symbol at r${row + 1}c${col + 1}")
            return HexadokuAttemptOutcome.finished()
        }
        return HexadokuAttemptOutcome.restart(
            reason = "Cleared unexpected red symbol at r${row + 1}c${col + 1}",
            restartMessage = "Corrected a red symbol. Resuming 16x16..."
        )
    }

    private fun writeSudokuScanDiagnostics(
        scan: OcrResult, result: ValidationResult, boardBounds: Rect, candidate: String
    ) {
        try {
            val directory = getExternalFilesDir(null) ?: return
            File(directory, "sudoku_last_scan.txt").writeText(buildString {
                appendLine("timestamp=${System.currentTimeMillis()}")
                appendLine("candidate=$candidate boardBounds=$boardBounds")
                appendLine("status=${result.status} timeout=${result.solutionIsTimeout}")
                appendLine("inputBlockReason=${com.sudoku.solver.processing.OcrInputSafety.rejectionReason(scan, result)}")
                appendLine("corrections=${result.corrections}")
                appendLine("unread=${scan.occupiedCells.filter { scan.observedDigits[it] !in 1..9 }}")
                appendLine("uncertain=${scan.uncertainCells}")
                appendLine("observedDigits=${scan.observedDigits}")
                appendLine(scan.grid.toString())
            })
        } catch (error: Exception) {
            Log.w(TAG, "Could not write 9x9 OCR diagnostics", error)
        }
    }

    private fun writeHexadokuRunStatus(message: String, append: Boolean = true) {
        try {
            val directory = getExternalFilesDir(null) ?: return
            val report = File(directory, "hexadoku_last_run.txt")
            val line = "${System.currentTimeMillis()} $message\n"
            if (append) report.appendText(line) else report.writeText(line)
        } catch (error: Exception) {
            Log.w(TAG, "Could not write 16x16 run status", error)
        }
    }

    private fun writeHexadokuDiagnostics(
        recognition: com.sudoku.solver.processing.HexadokuRecognitionResult,
        boardBounds: Rect
    ) {
        try {
            val report = buildString {
                appendLine("boardBounds=$boardBounds")
                appendLine("occupied=${recognition.occupiedCells.size}")
                appendLine("recognized=${recognition.grid.filledCount()}")
                appendLine("unrecognized=${recognition.unrecognizedOccupiedCells}")
                appendLine("lowConfidence=${recognition.lowConfidenceCells}")
                appendLine(recognition.grid.toString())
                appendLine("Cell reads:")
                recognition.cellReads.forEach { (cell, read) ->
                    appendLine(
                        "${cell.first},${cell.second}: tess='${read.tesseractText}' " +
                            "tessConfidence=${read.tesseractConfidence} ml='${read.mlKitText}' " +
                            "value=${read.value} source=${read.source}"
                    )
                }
            }
            val directory = getExternalFilesDir(null) ?: return
            File(directory, "hexadoku_last_scan.txt").writeText(report)
        } catch (error: Exception) {
            Log.w(TAG, "Could not write 16x16 OCR diagnostics", error)
        }
    }

    private suspend fun captureScreen(): Bitmap? {
        // Get the screen capture service instance
        val captureService = ScreenCaptureService.instance
        if (captureService == null) {
            Log.e(TAG, "ScreenCaptureService not running")
            return null
        }

        return withContext(Dispatchers.IO) {
            captureService.captureScreen()
        }
    }

    private fun detectBoardUsingConfiguredArea(screenshot: Bitmap): DetectedBoard? {
        val configuredArea = OcrScanAreaPrefs.toAbsoluteRect(this, screenshot.width, screenshot.height)
        if (configuredArea == null) {
            if (!hasLoggedMissingScanArea) {
                Log.i(TAG, "No saved OCR scan area configured. Using full-screen detection.")
                hasLoggedMissingScanArea = true
            }
            return boardDetector.detectBoard(screenshot)
        }

        if (configuredArea.width() < 120 || configuredArea.height() < 120) {
            Log.w(TAG, "Saved OCR scan area is too small. Falling back to full-screen detection.")
            return boardDetector.detectBoard(screenshot)
        }

        Log.w(
            TAG,
            "Using saved OCR scan area: " +
                "left=${configuredArea.left}, top=${configuredArea.top}, " +
                "right=${configuredArea.right}, bottom=${configuredArea.bottom}"
        )

        val cropped = Bitmap.createBitmap(
            screenshot,
            configuredArea.left,
            configuredArea.top,
            configuredArea.width(),
            configuredArea.height()
        )

        return try {
            val detectedInCrop = boardDetector.detectBoard(cropped)
            if (detectedInCrop == null) {
                Log.w(TAG, "Board not detected inside saved OCR area. Falling back to full-screen detection.")
                return boardDetector.detectBoard(screenshot)
            }

            val adjustedBounds = android.graphics.Rect(
                detectedInCrop.screenBounds.left + configuredArea.left,
                detectedInCrop.screenBounds.top + configuredArea.top,
                detectedInCrop.screenBounds.right + configuredArea.left,
                detectedInCrop.screenBounds.bottom + configuredArea.top
            )
            val adjustedCorners = detectedInCrop.cornerPoints.map { p ->
                Point(p.x + configuredArea.left, p.y + configuredArea.top)
            }

            DetectedBoard(
                warpedBitmap = detectedInCrop.warpedBitmap,
                cornerPoints = adjustedCorners,
                screenBounds = adjustedBounds
            )
        } finally {
            cropped.recycle()
        }
    }

    /** Board detection entry point used only by the 16x16 button. */
    private fun detectHexadokuBoardUsingConfiguredArea(screenshot: Bitmap): DetectedBoard? {
        val hexadokuBoardDetector = BoardDetector()
        val configuredArea = OcrScanAreaPrefs.toAbsoluteRect(this, screenshot.width, screenshot.height)
        if (configuredArea == null || configuredArea.width() < 120 || configuredArea.height() < 120) {
            return hexadokuBoardDetector.detectBoard(screenshot)
        }

        val cropped = Bitmap.createBitmap(
            screenshot,
            configuredArea.left,
            configuredArea.top,
            configuredArea.width(),
            configuredArea.height()
        )

        return try {
            val detectedInCrop = hexadokuBoardDetector.detectBoard(cropped)
            if (detectedInCrop == null) {
                return hexadokuBoardDetector.detectBoard(screenshot)
            }
            DetectedBoard(
                warpedBitmap = detectedInCrop.warpedBitmap,
                cornerPoints = detectedInCrop.cornerPoints.map { point ->
                    Point(point.x + configuredArea.left, point.y + configuredArea.top)
                },
                screenBounds = Rect(
                    detectedInCrop.screenBounds.left + configuredArea.left,
                    detectedInCrop.screenBounds.top + configuredArea.top,
                    detectedInCrop.screenBounds.right + configuredArea.left,
                    detectedInCrop.screenBounds.bottom + configuredArea.top
                )
            )
        } finally {
            cropped.recycle()
        }
    }

    /**
     * Perform a tap gesture at the specified coordinates
     */
    fun performTap(x: Int, y: Int, callback: (() -> Unit)? = null) {
        if (gameCompletionGate.waiting) return
        dispatchTap(x, y, callback)
    }

    /** The user-controlled timer remains independent of the solver's completion gate. */
    fun performTimerTap(x: Int, y: Int) {
        dispatchTap(x, y, null)
    }

    private fun dispatchTap(x: Int, y: Int, callback: (() -> Unit)?) {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, Constants.GESTURE_DURATION_MS))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesture cancelled")
            }
        }, null)
    }

    /**
     * Start auto-input from manual puzzle entry (no OCR needed)
     * Uses screen capture to detect board bounds, then inputs solution
     */
    fun startManualAutoInput(original: SudokuGrid, solution: Array<IntArray>) {
        if (gameCompletionGate.waiting) {
            showToast("Autocompletando: letting the game finish.")
            return
        }
        if (solvingJob?.isActive == true || isSolving) {
            showToast("Already solving...")
            return
        }

        isHexadokuMode = false
        autoResumeJob?.cancel()
        autoResumeJob = null
        clearConfirmedKeyCells()
        clearKeyCellGuidance()
        stopRequested = false
        isSolving = true
        autoInputController.clearStopRequest()
        sudokuSolver.clearStopRequest()

        solvingJob = serviceScope.launch {
            try {
                manualAutoInput(original, solution)
            } catch (e: CancellationException) {
                Log.i(TAG, "Manual auto-input cancelled")
                showToast(if (gameCompletionGate.waiting) "Autocompletando: letting the game finish." else "Solving stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error during manual auto-input", e)
                showToast("Error: ${e.message}")
            } finally {
                isSolving = false
                solvingJob = null
                stopRequested = false
                autoInputController.clearStopRequest()
                sudokuSolver.clearStopRequest()
            }
        }
    }

    private suspend fun manualAutoInput(original: SudokuGrid, solution: Array<IntArray>) = withContext(Dispatchers.Default) {
        ensureNotStopped()
        showToast("Detecting board...")

        // Capture screen to detect board bounds
        ensureNotStopped()
        val screenshot = captureScreen()
        if (screenshot == null) {
            withContext(Dispatchers.Main) {
                showToast("Failed to capture screen")
            }
            return@withContext
        }

        ensureNotStopped()
        val detectedBoard = detectBoardUsingConfiguredArea(screenshot)
        screenshot.recycle()

        if (detectedBoard == null) {
            withContext(Dispatchers.Main) {
                showToast("No Sudoku board detected on screen")
            }
            return@withContext
        }

        // Auto-input the solution with error detection
        ensureNotStopped()
        showToast("Auto-inputting solution...")
        val inputResult = inputSolutionWithAutomaticHintRecovery(
            original,
            solution,
            detectedBoard.screenBounds
        )

        if (inputResult.gameEnded) {
            detectedBoard.recycle()
            return@withContext
        }

        if (inputResult.success) {
            withContext(Dispatchers.Main) {
                showToast("Puzzle solved!")
            }
            detectedBoard.recycle()
            return@withContext
        }

        if (inputResult.selectionFailed) {
            withContext(Dispatchers.Main) {
                showToast("Could not find digit keypad. Re-open puzzle and try again.")
            }
            detectedBoard.recycle()
            return@withContext
        }


        if (inputResult.failureMessage != null) {
            Log.e(TAG, "Manual automatic PISTA recovery failed: ${inputResult.failureMessage}")
            withContext(Dispatchers.Main) {
                showToast(inputResult.failureMessage)
            }
            detectedBoard.recycle()
            return@withContext
        }

        // Error detected - try alternatives
        val errorCell = inputResult.errorCell ?: run {
            withContext(Dispatchers.Main) {
                showToast("Input failed unexpectedly. Please retry.")
            }
            detectedBoard.recycle()
            return@withContext
        }
        Log.e(TAG, "Error detected at (${errorCell.first},${errorCell.second}) for digit ${errorCell.third}")
        withContext(Dispatchers.Main) {
            showToast("Wrong digit detected. Stopping to protect the puzzle.")
        }

        val wrongDigits = mutableListOf(errorCell)
        var attempts = 0
        val maxAttempts = 0

        while (attempts < maxAttempts) {
            ensureNotStopped()
            attempts++
            Log.i(TAG, "Manual retry attempt $attempts with ${wrongDigits.size} known wrong digits")

            ensureNotStopped()
            val alternativeSolution = sudokuSolver.solveWithWrongDigits(
                original.values,
                wrongDigits
            )

            if (alternativeSolution == null) {
                withContext(Dispatchers.Main) {
                    showToast("No alternative solution found.")
                }
                break
            }

            ensureNotStopped()
            delay(500)

            ensureNotStopped()
            val newScreenshot = captureScreen()
            if (newScreenshot == null) break

            ensureNotStopped()
            val newBoard = detectBoardUsingConfiguredArea(newScreenshot)
            newScreenshot.recycle()
            if (newBoard == null) break

            autoInputController.clearCache()

            ensureNotStopped()
            withContext(Dispatchers.Main) {
                showToast("Trying alternative...")
            }

            ensureNotStopped()
            val retryResult = autoInputController.inputSolution(
                original, alternativeSolution, newBoard.screenBounds,
                checkForErrors = true
            )
            newBoard.recycle()

            if (retryResult.success) {
                withContext(Dispatchers.Main) {
                    showToast("Puzzle solved with alternative!")
                }
                break
            } else {
                if (retryResult.selectionFailed) {
                    withContext(Dispatchers.Main) {
                        showToast("Digit keypad not found during retry. Re-open puzzle.")
                    }
                    break
                }
                val newError = retryResult.errorCell
                if (newError == null) {
                    withContext(Dispatchers.Main) {
                        showToast("Retry failed unexpectedly. Please re-scan.")
                    }
                    break
                }
                val repeatedSameCell = wrongDigits.any { it.first == newError.first && it.second == newError.second }
                if (repeatedSameCell) {
                    withContext(Dispatchers.Main) {
                        showToast("Same cell keeps failing. OCR unstable, please re-scan.")
                    }
                    break
                }
                if (wrongDigits.contains(newError)) {
                    withContext(Dispatchers.Main) {
                        showToast("Same wrong digit repeated. Please re-scan.")
                    }
                    break
                }
                wrongDigits.add(newError)
                withContext(Dispatchers.Main) {
                    showToast("Another error. Retrying... ($attempts/$maxAttempts)")
                }
            }
        }

        detectedBoard.recycle()
    }

    /**
     * Runs auto-input until completion. If the game marks an entered digit red,
     * PISTA is selected for that cell and the remaining empty cells are resumed.
     * The one-pass filled-cell guard in [AutoInputController] preserves every
     * digit already entered, including the value revealed by PISTA.
     */
    private suspend fun inputSolutionWithAutomaticHintRecovery(
        original: SudokuGrid,
        solution: Array<IntArray>,
        boardBounds: Rect
    ): InputResult {
        val recoveredCells = mutableSetOf<Pair<Int, Int>>()
        val rejectedDigits = mutableListOf<Triple<Int, Int, Int>>()
        var noHintRecoveries = 0
        var currentGrid = original
        var currentSolution = solution
        var currentBoardBounds = Rect(boardBounds)
        var result = autoInputController.inputSolution(
            currentGrid,
            currentSolution,
            currentBoardBounds,
            checkForErrors = true
        )

        while (!result.success && !result.selectionFailed) {
            ensureNotStopped()
            val errorCell = result.errorCell ?: return result
            val cell = Pair(errorCell.first, errorCell.second)

            if (result.entryMissing) {
                if (++noHintRecoveries > MAX_NO_HINT_RECOVERIES) {
                    return result.copy(failureMessage = "An entry stayed empty after retries")
                }
                Log.i(TAG, "Retrying missing entry at r${errorCell.first + 1}c${errorCell.second + 1}")
                result = autoInputController.inputSolution(
                    currentGrid, currentSolution, currentBoardBounds, checkForErrors = true
                )
                continue
            }

            if (result.battleMode) {
                Log.w(TAG, "Batalla rejected or missed ${errorCell.third} at r${errorCell.first + 1}c${errorCell.second + 1}; rescanning")
                if (!result.entryMissing && errorCell !in rejectedDigits) {
                    rejectedDigits.add(errorCell)
                }
                if (++noHintRecoveries > MAX_NO_HINT_RECOVERIES) {
                    return result.copy(failureMessage = "Batalla rejected too many digits to recover safely")
                }
                if (autoInputController.isCellMarkedWrong(
                        errorCell.first, errorCell.second, currentBoardBounds
                    ) && !clearWrongEntryWithoutHint(
                        errorCell.first, errorCell.second, currentBoardBounds, 9
                    )) {
                    return result.copy(failureMessage = "Could not clear the rejected Batalla digit")
                }
                val refreshed = refreshSolutionWithoutHint(rejectedDigits, original)
                    ?: return result.copy(failureMessage = "Batalla board could not be solved after the rejected digit")
                currentGrid = refreshed.grid
                currentSolution = refreshed.solution
                currentBoardBounds = refreshed.boardBounds
                showToast("Batalla board updated. Resuming solver...")
                result = autoInputController.inputSolution(
                    currentGrid, currentSolution, currentBoardBounds, checkForErrors = true
                )
                continue
            }

            if (!recoveredCells.add(cell)) {
                return result.copy(
                    failureMessage = "PISTA could not correct the red entry. Please finish this cell manually."
                )
            }
            if (recoveredCells.size > MAX_AUTOMATIC_HINT_RECOVERIES) {
                return result.copy(
                    failureMessage = "Too many wrong entries were detected. Please re-scan the puzzle."
                )
            }

            Log.w(
                TAG,
                "Wrong digit ${errorCell.third} at r${errorCell.first + 1}c${errorCell.second + 1}; " +
                    "selecting PISTA before resuming"
            )
            showToast("Wrong digit detected. Using PISTA for the correct value...")

            val hintCorrected = correctWrongEntryWithHint(
                    row = errorCell.first,
                    col = errorCell.second,
                    boardBounds = currentBoardBounds,
                    gridSize = 9,
                    isStillMarkedWrong = {
                        autoInputController.isCellMarkedWrong(
                            errorCell.first,
                            errorCell.second,
                            currentBoardBounds
                        )
                    }
                )
            if (!hintCorrected) {
                if (errorCell !in rejectedDigits) rejectedDigits.add(errorCell)
                if (++noHintRecoveries > MAX_NO_HINT_RECOVERIES) {
                    return result.copy(failureMessage = "Too many rejected digits to recover safely")
                }
                if (autoInputController.isCellMarkedWrong(
                        errorCell.first, errorCell.second, currentBoardBounds
                    ) && !clearWrongEntryWithoutHint(
                        errorCell.first, errorCell.second, currentBoardBounds, 9
                    )) {
                    return result.copy(failureMessage = "Could not clear the rejected digit")
                }
                val refreshed = refreshSolutionWithoutHint(rejectedDigits)
                    ?: return result.copy(failureMessage = "Board could not be solved without PISTA")
                currentGrid = refreshed.grid
                currentSolution = refreshed.solution
                currentBoardBounds = refreshed.boardBounds
                showToast("Pista unavailable. Resuming from the board...")
                result = autoInputController.inputSolution(
                    currentGrid, currentSolution, currentBoardBounds, checkForErrors = true
                )
                continue
            }

            ensureNotStopped()
            showToast("PISTA corrected the cell. Updating solution...")
            delay(AUTOMATIC_HINT_RESUME_DELAY_MS)
            val refreshed = refreshSolutionAfterHint(
                baseGrid = currentGrid,
                row = errorCell.first,
                col = errorCell.second,
                rejectedDigit = errorCell.third
            ) ?: return result.copy(
                failureMessage =
                    "PISTA filled the cell, but the solver could not read the revealed value. Please re-scan."
            )

            currentGrid = refreshed.grid
            currentSolution = refreshed.solution
            currentBoardBounds = refreshed.boardBounds
            Log.i(
                TAG,
                "Updated solution from PISTA at r${errorCell.first + 1}c${errorCell.second + 1}; " +
                    "revealed=${currentSolution[errorCell.first][errorCell.second]}"
            )
            showToast("Solution updated. Resuming solver...")
            result = autoInputController.inputSolution(
                currentGrid,
                currentSolution,
                currentBoardBounds,
                checkForErrors = true
            )
        }

        return result
    }

    private suspend fun refreshSolutionAfterHint(
        baseGrid: SudokuGrid,
        row: Int,
        col: Int,
        rejectedDigit: Int
    ): HintSolutionRefresh? {
        ensureNotStopped()
        val screenshot = captureScreen() ?: run {
            Log.w(TAG, "PISTA solution refresh failed: screen capture unavailable")
            return null
        }
        val board = try {
            detectBoardUsingConfiguredArea(screenshot)
        } finally {
            screenshot.recycle()
        } ?: run {
            Log.w(TAG, "PISTA solution refresh failed: board not detected")
            return null
        }

        return try {
            ensureNotStopped()
            var refreshedOcr = digitRecognizer.extractGridWithUncertainty(
                board.warpedBitmap,
                mlKitOnly = true
            )
            ensureNotStopped()
            val cell = Pair(row, col)
            fun readRevealedValue(): Int? = refreshedOcr.observedDigits[cell]
                ?: refreshedOcr.grid.values[row][col].takeIf { it in 1..9 }

            if (readRevealedValue() == null || readRevealedValue() == rejectedDigit) {
                Log.i(
                    TAG,
                    "PISTA fast refresh was inconclusive at r${row + 1}c${col + 1}; " +
                        "retrying with full OCR"
                )
                refreshedOcr = digitRecognizer.extractGridWithUncertainty(
                    board.warpedBitmap,
                    mlKitOnly = false
                )
                ensureNotStopped()
            }
            val revealedValue = readRevealedValue() ?: run {
                Log.w(
                    TAG,
                    "PISTA solution refresh could not recognize r${row + 1}c${col + 1}"
                )
                return null
            }
            if (revealedValue == rejectedDigit) {
                Log.w(
                    TAG,
                    "PISTA solution refresh still read rejected digit $rejectedDigit at " +
                        "r${row + 1}c${col + 1}"
                )
                return null
            }

            val candidates = listOf(baseGrid, refreshedOcr.grid)
                .mapNotNull { candidate ->
                    HintSolutionPolicy.withRevealedValue(candidate, row, col, revealedValue)
                }
                .distinct()

            for (candidate in candidates) {
                ensureNotStopped()
                val validation = sudokuSolver.validateAndSolve(candidate)
                val updatedSolution = validation.solution
                if (
                    validation.status != ValidationStatus.SUCCESS ||
                    !HintSolutionPolicy.solutionHonorsReveal(
                        updatedSolution,
                        row,
                        col,
                        revealedValue
                    )
                ) {
                    continue
                }

                val correctedValues = validation.correctedGrid
                val solvedFromGrid = if (correctedValues != null) {
                    SudokuGrid(
                        values = correctedValues.map { it.clone() }.toTypedArray(),
                        locked = Array(9) { r ->
                            BooleanArray(9) { c -> correctedValues[r][c] != 0 }
                        }
                    )
                } else {
                    candidate
                }
                val anchoredGrid = HintSolutionPolicy.withRevealedValue(
                    solvedFromGrid,
                    row,
                    col,
                    revealedValue
                ) ?: continue

                Log.i(
                    TAG,
                    "PISTA solution refresh accepted revealed value $revealedValue at " +
                        "r${row + 1}c${col + 1}"
                )
                return HintSolutionRefresh(
                    grid = anchoredGrid,
                    solution = requireNotNull(updatedSolution),
                    boardBounds = Rect(board.screenBounds)
                )
            }

            Log.w(
                TAG,
                "PISTA solution refresh found value $revealedValue at " +
                    "r${row + 1}c${col + 1}, but no valid updated solution"
            )
            null
        } finally {
            board.recycle()
        }
    }

    private suspend fun refreshSolutionWithoutHint(
        rejectedDigits: List<Triple<Int, Int, Int>>,
        baseGrid: SudokuGrid? = null
    ): HintSolutionRefresh? {
        ensureNotStopped()
        val screenshot = captureScreen() ?: return null
        val board = try { detectBoardUsingConfiguredArea(screenshot) }
            finally { screenshot.recycle() } ?: return null
        return try {
            val scan = digitRecognizer.extractGridWithUncertainty(
                board.warpedBitmap, mlKitOnly = false
            )
            val rejectedCells = rejectedDigits.map { Pair(it.first, it.second) }.toSet()
            val values = scan.grid.values.map(IntArray::clone).toTypedArray()
            val locked = scan.grid.locked.map(BooleanArray::clone).toTypedArray()
            rejectedCells.forEach { (row, col) ->
                values[row][col] = 0
                locked[row][col] = false
            }
            val refreshedGrid = SudokuGrid(values, locked)
            val validated = sudokuSolver.validateAndSolve(refreshedGrid)
            val options = sequence {
                validated.solution?.let { yield(it) }
                sudokuSolver.solveWithWrongDigits(values, rejectedDigits)?.let { yield(it) }
                baseGrid?.let { base ->
                    sudokuSolver.solveWithWrongDigits(base.values, rejectedDigits)?.let { yield(it) }
                }
            }
            for (solution in options) {
                if (rejectedDigits.any { (row, col, digit) -> solution[row][col] == digit }) continue
                if ((0..8).any { row -> (0..8).any { col ->
                        values[row][col] != 0 && !locked[row][col] &&
                            values[row][col] != solution[row][col]
                    } }) continue
                val inputValues = values.map(IntArray::clone).toTypedArray()
                val inputLocked = locked.map(BooleanArray::clone).toTypedArray()
                for (row in 0..8) for (col in 0..8) {
                    if (inputValues[row][col] != 0 && inputValues[row][col] != solution[row][col]) {
                        inputValues[row][col] = 0
                        inputLocked[row][col] = false
                    }
                }
                Log.i(TAG, "Recovered Batalla solution after ${rejectedDigits.size} rejected digit(s)")
                return HintSolutionRefresh(
                    SudokuGrid(inputValues, inputLocked), solution, Rect(board.screenBounds)
                )
            }
            null
        } finally { board.recycle() }
    }

    private suspend fun clearWrongEntryWithoutHint(
        row: Int, col: Int, boardBounds: Rect, gridSize: Int
    ): Boolean {
        val cellWidth = boardBounds.width() / gridSize.toFloat()
        val cellHeight = boardBounds.height() / gridSize.toFloat()
        performTap(
            (boardBounds.left + (col + 0.5f) * cellWidth).toInt(),
            (boardBounds.top + (row + 0.5f) * cellHeight).toInt()
        )
        delay(150L)
        val eraseTarget = awaitVisibleAutomationTarget(
            ScreenAutomationPolicy::isEraseActionViewId
        ) ?: return false
        val clicked = eraseTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            val bounds = Rect().also(eraseTarget::getBoundsInScreen)
            if (bounds.isEmpty) return false
            performTap(bounds.centerX(), bounds.centerY())
        }
        delay(180L)
        return if (gridSize == 9) !autoInputController.isCellMarkedWrong(row, col, boardBounds)
        else !requireNotNull(hexadokuAutoInputController).isCellMarkedWrong(row, col, boardBounds)
    }

    private suspend fun correctWrongEntryWithHint(
        row: Int,
        col: Int,
        boardBounds: Rect,
        gridSize: Int,
        isStillMarkedWrong: suspend () -> Boolean
    ): Boolean {
        ensureNotStopped()
        if (boardBounds.width() <= 0 || boardBounds.height() <= 0 || gridSize <= 0) return false

        val focused = if (gridSize == HexadokuGrid.SIZE) {
            requireNotNull(hexadokuAutoInputController).focusCell(row, col, boardBounds)
        } else {
            val cellWidth = boardBounds.width() / gridSize.toFloat()
            val cellHeight = boardBounds.height() / gridSize.toFloat()
            val cellX = (boardBounds.left + (col + 0.5f) * cellWidth).toInt()
            val cellY = (boardBounds.top + (row + 0.5f) * cellHeight).toInt()
            performTap(cellX, cellY)
            true
        }
        if (!focused) {
            Log.w(TAG, "PISTA recovery failed: could not focus r${row + 1}c${col + 1}")
            return false
        }
        delay(AUTOMATIC_HINT_FOCUS_DELAY_MS)
        ensureNotStopped()

        val hintTarget = awaitVisibleAutomationTarget(
            ScreenAutomationPolicy::isHintActionViewId
        ) ?: run {
            Log.w(TAG, "PISTA recovery failed: hintView unavailable after retries")
            return false
        }

        val clicked = hintTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            val hintBounds = Rect().also(hintTarget::getBoundsInScreen)
            if (hintBounds.isEmpty) {
                Log.w(TAG, "PISTA recovery failed: hintView has empty bounds")
                return false
            }
            performTap(hintBounds.centerX(), hintBounds.centerY())
        }
        Log.i(
            TAG,
            "Selected PISTA for red cell r${row + 1}c${col + 1} " +
                "(accessibilityClick=$clicked)"
        )

        repeat(AUTOMATIC_HINT_VERIFY_ATTEMPTS) { attempt ->
            delay(
                if (attempt == 0) AUTOMATIC_HINT_RESULT_WAIT_MS
                else AUTOMATIC_HINT_VERIFY_RETRY_DELAY_MS
            )
            ensureNotStopped()
            if (!isStillMarkedWrong()) {
                Log.i(TAG, "PISTA cleared red state at r${row + 1}c${col + 1}")
                return true
            }
        }

        Log.w(TAG, "PISTA did not clear red state at r${row + 1}c${col + 1}")
        return false
    }

    private suspend fun selectBestOcrCandidate(
        warpedBitmap: Bitmap,
        passLabel: String,
        allowVariants: Boolean = true
    ): OcrCandidateEvaluation {
        ensureNotStopped()

        // Fast path: try ML Kit only (no Tesseract) - typically completes in ~1s
        val mlFastStart = System.currentTimeMillis()
        val mlOnlyCandidate = evaluateOcrCandidate(
            label = "$passLabel/base/ml-fast",
            warpedBitmap = warpedBitmap,
            mlKitOnly = true
        )
        val mlFastMs = System.currentTimeMillis() - mlFastStart
        if (isStrongOcrCandidate(mlOnlyCandidate)) {
            Log.w(TAG, "ML Kit fast path SUCCESS in ${mlFastMs}ms - skipping Tesseract entirely")
            return mlOnlyCandidate
        }
        if (isTrustedSolvedOcrCandidate(mlOnlyCandidate)) {
            Log.w(TAG, "ML Kit fast path has confirmed unique solution in ${mlFastMs}ms - using it directly")
            return mlOnlyCandidate
        }
        Log.w(
            TAG,
            "ML Kit fast path not sufficient in ${mlFastMs}ms " +
                "(status=${mlOnlyCandidate.validation.status}, " +
                "contradictions=${mlOnlyCandidate.contradictionCount}, " +
                "uncertain=${mlOnlyCandidate.uncertainCount}, " +
                "corrections=${mlOnlyCandidate.validation.corrections.size}). " +
                "Falling back to full hybrid OCR."
        )

        val baseCandidate = evaluateOcrCandidate(
            label = "$passLabel/base",
            warpedBitmap = warpedBitmap
        )
        if (isStrongOcrCandidate(baseCandidate)) {
            Log.w(TAG, "OCR ${baseCandidate.label} is already strong; skipping extra OCR variants")
            return baseCandidate
        }
        if (isTrustedSolvedOcrCandidate(baseCandidate)) {
            Log.w(TAG, "OCR ${baseCandidate.label} has confirmed unique solution; skipping extra OCR variants")
            return baseCandidate
        }
        if (!allowVariants) {
            Log.w(TAG, "OCR ${baseCandidate.label}: skipping crop variants on recovery scan")
            return baseCandidate
        }

        val candidates = mutableListOf(baseCandidate)
        val variants = buildOcrWarpVariants(warpedBitmap, passLabel)
        try {
            var bestVariantIndex = -1
            for ((variantIndex, variant) in variants.withIndex()) {
                ensureNotStopped()
                val candidate = evaluateOcrCandidate(
                    label = variant.label,
                    warpedBitmap = variant.bitmap,
                    mlKitOnly = true
                )
                candidates.add(candidate)
                if (isStrongOcrCandidate(candidate)) {
                    Log.w(TAG, "OCR ${candidate.label} ML fast path is strong; stopping variant scan early")
                    return candidate
                }
                if (isTrustedSolvedOcrCandidate(candidate)) {
                    Log.w(TAG, "OCR ${candidate.label} ML fast path has a confirmed unique solution")
                    return candidate
                }

                if (
                    bestVariantIndex < 0 ||
                    compareOcrCandidates(candidate, candidates[bestVariantIndex]) > 0
                ) {
                    // candidates[0] is the base candidate, hence the +1 offset.
                    bestVariantIndex = variantIndex + 1
                }
            }

            // Refine at most one crop variant. Its ML reads are cached by DigitRecognizer,
            // so this adds only targeted Tesseract work instead of repeating all ML calls.
            if (bestVariantIndex > 0) {
                val fastVariant = candidates[bestVariantIndex]
                val shouldRefineVariant =
                    compareOcrCandidates(fastVariant, baseCandidate) > 0 ||
                        baseCandidate.contradictionCount > 0 ||
                        baseCandidate.validation.solution == null
                if (shouldRefineVariant) {
                    val variant = variants[bestVariantIndex - 1]
                    ensureNotStopped()
                    val refined = evaluateOcrCandidate(
                        label = "${variant.label}/hybrid",
                        warpedBitmap = variant.bitmap
                    )
                    candidates[bestVariantIndex] = refined
                    if (isStrongOcrCandidate(refined) || isTrustedSolvedOcrCandidate(refined)) {
                        Log.w(TAG, "OCR ${refined.label} accepted after targeted refinement")
                        return refined
                    }
                }
            }
        } finally {
            variants.forEach { variant ->
                if (variant.recycleAfterUse && !variant.bitmap.isRecycled) {
                    variant.bitmap.recycle()
                }
            }
        }

        var best = candidates.first()
        for (idx in 1 until candidates.size) {
            val candidate = candidates[idx]
            if (compareOcrCandidates(candidate, best) > 0) {
                best = candidate
            }
        }

        if (candidates.size >= 2) {
            val consensusResult = OcrConsensus.combine(
                primary = best.ocrResult,
                candidates = candidates.map { it.ocrResult }
            )
            val consensus = evaluateOcrResultCandidate(
                label = "$passLabel/consensus-${candidates.size}",
                ocrResult = consensusResult
            )
            candidates.add(consensus)
            if (compareOcrCandidates(consensus, best) > 0) {
                best = consensus
            }
        }

        val ranking = candidates
            .sortedWith { a, b -> compareOcrCandidates(b, a) }
            .joinToString(" | ") { candidate ->
                "${candidate.label}:status=${candidate.validation.status}," +
                    "clues=${candidate.clueCount}," +
                    "uncertain=${candidate.uncertainCount}," +
                    "contr=${candidate.contradictionCount}," +
                    "score=${candidate.confidenceScore}"
            }
        Log.w(TAG, "OCR candidate ranking: $ranking")
        Log.w(TAG, "Selected OCR candidate: ${best.label}")
        return best
    }

    private suspend fun evaluateOcrCandidate(
        label: String,
        warpedBitmap: Bitmap,
        mlKitOnly: Boolean = false
    ): OcrCandidateEvaluation {
        ensureNotStopped()
        val ocrResult = digitRecognizer.extractGridWithUncertainty(warpedBitmap, mlKitOnly)
        ensureNotStopped()
        return evaluateOcrResultCandidate(label, ocrResult)
    }

    private fun evaluateOcrResultCandidate(
        label: String,
        ocrResult: OcrResult
    ): OcrCandidateEvaluation {
        val confirmations = synchronized(keyGuidanceLock) {
            confirmedKeyCellOptions.mapValues { (_, options) -> options.toSet() }
        }
        val effectiveOcrResult = KeyCellGuidancePolicy.applyConfirmedEntries(
            ocrResult = ocrResult,
            confirmedOptions = confirmations
        )
        if (effectiveOcrResult !== ocrResult) {
            Log.w(TAG, "Applied ${confirmations.size} user-confirmed key cell(s) to OCR candidate $label")
        }

        var recoveredOcrResult = effectiveOcrResult
        var validation = sudokuSolver.validateAndSolve(recoveredOcrResult.grid)
        if (validation.status == ValidationStatus.MULTIPLE_SOLUTIONS) {
            val observedRecovery = recoverWithObservedKeyCells(
                initialOcrResult = recoveredOcrResult,
                initialValidation = validation
            )
            if (observedRecovery != null) {
                recoveredOcrResult = observedRecovery.first
                validation = observedRecovery.second
            }
        }

        val grid = recoveredOcrResult.grid
        val clueCount = grid.values.sumOf { row -> row.count { it != 0 } }
        val uncertainCount = recoveredOcrResult.uncertainCells.size
        val contradictionCount = sudokuSolver.findContradictionCells(grid.values).size
        val clueDensityPenalty = calculateClueDensityPenalty(
            clueCount = clueCount,
            uncertainCount = uncertainCount,
            contradictionCount = contradictionCount,
            correctionCount = validation.corrections.size
        )
        val confidenceScore =
            clueCount * 14 -
                uncertainCount * 9 -
                contradictionCount * 40 -
                validation.corrections.size * 11 -
                clueDensityPenalty -
                if (validation.solutionIsTimeout) 24 else 0 -
                when (validation.status) {
                    ValidationStatus.NEEDS_MORE_CLUES -> 240
                    ValidationStatus.HAS_CONTRADICTIONS -> 200
                    ValidationStatus.UNSOLVABLE -> 150
                    ValidationStatus.MULTIPLE_SOLUTIONS -> 35
                    else -> 0
                }
        Log.w(
            TAG,
            "OCR[$label]: clues=$clueCount, uncertain=$uncertainCount, " +
                "contradictions=$contradictionCount, status=${validation.status}, " +
                "corrections=${validation.corrections.size}, score=$confidenceScore"
        )
        return OcrCandidateEvaluation(
            label = label,
            ocrResult = recoveredOcrResult,
            validation = validation,
            clueCount = clueCount,
            uncertainCount = uncertainCount,
            contradictionCount = contradictionCount,
            confidenceScore = confidenceScore
        )
    }

    private fun recoverWithObservedKeyCells(
        initialOcrResult: OcrResult,
        initialValidation: ValidationResult
    ): Pair<OcrResult, ValidationResult>? {
        var candidateOcrResult = initialOcrResult
        var candidateValidation = initialValidation
        val restoredCells = mutableListOf<Pair<Pair<Int, Int>, Int>>()

        for (step in 0 until MAX_OBSERVED_KEY_CELL_RECOVERY_STEPS) {
            if (candidateValidation.status != ValidationStatus.MULTIPLE_SOLUTIONS) {
                break
            }
            val recovery = KeyCellGuidancePolicy.restoreFirstMatchingObservedKeyCell(
                ocrResult = candidateOcrResult,
                recommendations = candidateValidation.keyCellRecommendations
            ) ?: break

            candidateOcrResult = recovery.ocrResult
            restoredCells.add(Pair(recovery.cell, recovery.value))
            candidateValidation = sudokuSolver.validateAndSolve(candidateOcrResult.grid)
            if (candidateValidation.status == ValidationStatus.SUCCESS) {
                Log.w(
                    TAG,
                    "Recovered a unique puzzle using ${restoredCells.size} visible occupied key cell(s): " +
                        restoredCells.joinToString { (cell, value) ->
                            "r${cell.first + 1}c${cell.second + 1}=$value"
                        }
                )
                return Pair(candidateOcrResult, candidateValidation)
            }
            if (candidateValidation.status != ValidationStatus.MULTIPLE_SOLUTIONS) {
                Log.w(
                    TAG,
                    "Discarding occupied key-cell recovery because validation became " +
                        candidateValidation.status
                )
                return null
            }
        }

        return null
    }

    private fun buildOcrWarpVariants(
        base: Bitmap,
        passLabel: String
    ): List<OcrBitmapVariant> {
        val variants = mutableListOf<OcrBitmapVariant>()
        val small = createInnerCropScaledVariant(base, OCR_VARIANT_INNER_CROP_SMALL)
        if (small != null) {
            variants.add(
                OcrBitmapVariant(
                    label = "$passLabel/inner-${(OCR_VARIANT_INNER_CROP_SMALL * 100).toInt()}pct",
                    bitmap = small,
                    recycleAfterUse = true
                )
            )
        }

        return variants
    }

    private fun createInnerCropScaledVariant(
        source: Bitmap,
        cropRatio: Float
    ): Bitmap? {
        if (cropRatio <= 0f) return null
        val minSide = minOf(source.width, source.height)
        val inset = (minSide * cropRatio).toInt().coerceAtLeast(1)
        val cropWidth = source.width - 2 * inset
        val cropHeight = source.height - 2 * inset
        if (cropWidth < 81 || cropHeight < 81) return null

        return try {
            val cropped = Bitmap.createBitmap(source, inset, inset, cropWidth, cropHeight)
            val scaled = Bitmap.createScaledBitmap(cropped, source.width, source.height, true)
            cropped.recycle()
            scaled
        } catch (t: Throwable) {
            Log.w(TAG, "Failed creating OCR warp variant at cropRatio=$cropRatio", t)
            null
        }
    }

    private fun isStrongOcrCandidate(candidate: OcrCandidateEvaluation): Boolean {
        if (!com.sudoku.solver.processing.OcrInputSafety.canInput(candidate.ocrResult, candidate.validation)) return false
        if (candidate.contradictionCount != 0) return false
        if (candidate.clueCount < OCR_EARLY_ACCEPT_MIN_CLUES) return false
        return candidate.uncertainCount <= OCR_EARLY_ACCEPT_MAX_UNCERTAIN ||
            candidate.validation.corrections.isEmpty()
    }

    private fun isTrustedSolvedOcrCandidate(candidate: OcrCandidateEvaluation): Boolean {
        if (!com.sudoku.solver.processing.OcrInputSafety.canInput(candidate.ocrResult, candidate.validation)) return false
        if (candidate.clueCount < OCR_EARLY_ACCEPT_MIN_CLUES) return false
        if (candidate.contradictionCount != 0) return false
        return candidate.uncertainCount <= OCR_TRUSTED_SUCCESS_MAX_UNCERTAIN
    }

    private fun calculateClueDensityPenalty(
        clueCount: Int,
        uncertainCount: Int,
        contradictionCount: Int,
        correctionCount: Int
    ): Int {
        val extraClues = (clueCount - OCR_CLUE_DENSITY_SOFT_MAX).coerceAtLeast(0)
        if (extraClues == 0) return 0

        val noiseWeight =
            (uncertainCount / 2) +
                contradictionCount * 3 +
                correctionCount * 2
        if (noiseWeight <= 0) return 0

        return extraClues * noiseWeight
    }

    private fun shouldAutoRescanForOcr(candidate: OcrCandidateEvaluation): Boolean {
        if (isStrongOcrCandidate(candidate)) return false
        if (isTrustedSolvedOcrCandidate(candidate)) {
            return false
        }
        // Crop variants and targeted Tesseract already handle ordinary OCR errors.
        // Recapturing an unchanged board repeats the same expensive work and was the
        // main cause of minute-long scans. Reserve it for a near-total OCR failure.
        return candidate.clueCount < 10
    }

    private fun isCandidateBetter(
        candidate: OcrCandidateEvaluation,
        currentBest: OcrCandidateEvaluation
    ): Boolean {
        return compareOcrCandidates(candidate, currentBest) > 0
    }

    private fun compareOcrCandidates(
        a: OcrCandidateEvaluation,
        b: OcrCandidateEvaluation
    ): Int {
        val trustedSolvedCmp = compareBoolDesc(
            isTrustedSolvedOcrCandidate(a),
            isTrustedSolvedOcrCandidate(b)
        )
        if (trustedSolvedCmp != 0) return trustedSolvedCmp

        val contradictionFreeCmp = compareBoolDesc(a.contradictionCount == 0, b.contradictionCount == 0)
        if (contradictionFreeCmp != 0) return contradictionFreeCmp

        val solvableCmp = compareBoolDesc(a.validation.solution != null, b.validation.solution != null)
        if (solvableCmp != 0) return solvableCmp

        val timeoutCmp = compareBoolDesc(!a.validation.solutionIsTimeout, !b.validation.solutionIsTimeout)
        if (timeoutCmp != 0) return timeoutCmp

        val correctionCmp = b.validation.corrections.size.compareTo(a.validation.corrections.size)
        if (correctionCmp != 0) return correctionCmp

        val confidenceCmp = a.confidenceScore.compareTo(b.confidenceScore)
        if (confidenceCmp != 0) return confidenceCmp

        val clueCmp = a.clueCount.compareTo(b.clueCount)
        if (clueCmp != 0) return clueCmp

        val uncertainCmp = b.uncertainCount.compareTo(a.uncertainCount)
        if (uncertainCmp != 0) return uncertainCmp

        val contradictionCmp = b.contradictionCount.compareTo(a.contradictionCount)
        if (contradictionCmp != 0) return contradictionCmp

        return compareBoolDesc(
            a.validation.status == ValidationStatus.SUCCESS,
            b.validation.status == ValidationStatus.SUCCESS
        )
    }

    private fun compareBoolDesc(a: Boolean, b: Boolean): Int {
        return when {
            a == b -> 0
            a -> 1
            else -> -1
        }
    }

    private suspend fun captureAndEvaluateOcrCandidate(
        passLabel: String
    ): Pair<DetectedBoard, OcrCandidateEvaluation>? {
        ensureNotStopped()
        val screenshot = captureScreen() ?: return null
        val board = detectBoardUsingConfiguredArea(screenshot)
        screenshot.recycle()
        if (board == null) return null
        ensureNotStopped()
        val candidate = selectBestOcrCandidate(
            warpedBitmap = board.warpedBitmap,
            passLabel = passLabel,
            allowVariants = false
        )
        return Pair(board, candidate)
    }

    private suspend fun tryProgressiveUncertainRecovery(
        baseGrid: SudokuGrid,
        baseResult: ValidationResult,
        uncertainCells: List<Pair<Int, Int>>
    ): Pair<SudokuGrid, ValidationResult>? {
        if (uncertainCells.isEmpty()) return null

        val preferredCells = (
            baseResult.problemCells +
                baseResult.corrections.map { Pair(it.row, it.col) }
            ).distinct()

        var bestGrid = baseGrid
        var bestResult = baseResult
        var improved = false

        val sweeps = listOf(
            Triple(24, 4, true),
            Triple(23, 6, false),
            Triple(21, 9, false)
        )

        for ((minCluesToKeep, maxClears, onlyPreferred) in sweeps) {
            ensureNotStopped()
            val candidateGrid = clearUncertainClues(
                grid = baseGrid,
                uncertainCells = uncertainCells,
                preferredCells = preferredCells,
                minCluesToKeep = minCluesToKeep,
                onlyPreferred = onlyPreferred,
                maxClears = maxClears
            )
            if (candidateGrid === baseGrid) continue

            ensureNotStopped()
            val candidateResult = sudokuSolver.validateAndSolve(candidateGrid)
            Log.w(
                TAG,
                "Progressive uncertain recovery " +
                    "(minClues=$minCluesToKeep,maxClears=$maxClears,onlyPreferred=$onlyPreferred): " +
                    "status=${candidateResult.status}, corrections=${candidateResult.corrections.size}"
            )

            if (compareValidationResultForRecovery(candidateResult, bestResult) > 0) {
                bestGrid = candidateGrid
                bestResult = candidateResult
                improved = true
            }

            if (candidateResult.status == ValidationStatus.SUCCESS) {
                return Pair(candidateGrid, candidateResult)
            }
        }

        return if (improved) Pair(bestGrid, bestResult) else null
    }

    private fun compareValidationResultForRecovery(
        a: ValidationResult,
        b: ValidationResult
    ): Int {
        return recoveryScore(a).compareTo(recoveryScore(b))
    }

    private fun recoveryScore(result: ValidationResult): Int {
        var score = when (result.status) {
            ValidationStatus.SUCCESS -> 10_000
            ValidationStatus.MULTIPLE_SOLUTIONS -> 7_000
            ValidationStatus.UNSOLVABLE -> 4_000
            ValidationStatus.HAS_CONTRADICTIONS -> 3_000
            ValidationStatus.NEEDS_MORE_CLUES -> 2_000
            ValidationStatus.INTERNAL_ERROR -> 0
        }
        if (result.solution != null) score += 400
        score -= result.problemCells.size * 4
        score -= result.corrections.size * 2
        return score
    }

    private fun countActiveUncertainClues(
        grid: SudokuGrid,
        uncertainCells: List<Pair<Int, Int>>
    ): Int {
        return uncertainCells.count { (row, col) ->
            row in 0..8 && col in 0..8 && grid.values[row][col] != 0
        }
    }

    private fun shouldAttemptDeepRecovery(
        result: ValidationResult,
        uncertainCells: List<Pair<Int, Int>>
    ): Boolean {
        if (result.status == ValidationStatus.SUCCESS || result.status == ValidationStatus.INTERNAL_ERROR) {
            return false
        }
        if (result.status == ValidationStatus.HAS_CONTRADICTIONS ||
            result.status == ValidationStatus.UNSOLVABLE ||
            result.status == ValidationStatus.NEEDS_MORE_CLUES
        ) {
            return true
        }
        if (result.status == ValidationStatus.MULTIPLE_SOLUTIONS) {
            return true
        }
        return uncertainCells.size >= OCR_DEEP_RECOVERY_MIN_UNCERTAIN
    }

    private fun showToast(message: String) {
        FloatingButtonService.publishSolverStatus(message)
        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            Log.w(TAG, message)
        }
    }

    private fun setKeyCellGuidance(
        boardBounds: Rect,
        recommendations: List<com.sudoku.solver.processing.KeyCellRecommendation>,
        occupiedCells: Set<Pair<Int, Int>>
    ): Boolean {
        val selected = KeyCellGuidancePolicy.selectNextEmptyCell(
            recommendations = recommendations,
            occupiedCells = occupiedCells
        )
        val items = selected
            .map { rec -> KeyCellGuidanceItem(rec.row, rec.col, rec.optionA, rec.optionB) }
        val skippedOccupied = recommendations.count { Pair(it.row, it.col) in occupiedCells }

        synchronized(keyGuidanceLock) {
            pendingKeyCells.clear()
            items.forEach { pendingKeyCells.addLast(it) }
            keyGuidanceBoardBounds = if (items.isEmpty()) null else Rect(boardBounds)
            lastGuidanceTap = null
            lastGuidanceTapAtMs = 0L
            keyGuidanceTotal = items.size
            keyGuidancePendingChecks.clear()
        }

        if (items.isNotEmpty()) {
            val first = items.first()
            val total = items.size
            val bannerMessage = buildKeyCellBannerMessage(first, 1, total)
            showToast(buildKeyCellToastMessage(first, 1, total))
            showKeyGuidanceOverlay(bannerMessage)
            Log.w(
                TAG,
                "Key disambiguation cell 1/$total: row=${first.row + 1}, col=${first.col + 1}, " +
                "options=${first.optionA}/${first.optionB}, skippedOccupied=$skippedOccupied"
            )
            startKeyGuidanceMonitor()
            scheduleAutomaticKeyCellHint(first)
            return true
        } else {
            hideKeyGuidanceOverlay()
            Log.w(
                TAG,
                "No empty key cell available; skipped $skippedOccupied occupied recommendation(s)"
            )
            return false
        }
    }

    private fun clearConfirmedKeyCells() {
        synchronized(keyGuidanceLock) {
            confirmedKeyCellOptions.clear()
        }
    }

    private fun clearKeyCellGuidance() {
        keyGuidanceMonitorJob?.cancel()
        keyGuidanceMonitorJob = null
        automaticKeyHintJob?.cancel()
        automaticKeyHintJob = null
        synchronized(keyGuidanceLock) {
            pendingKeyCells.clear()
            keyGuidanceBoardBounds = null
            lastGuidanceTap = null
            lastGuidanceTapAtMs = 0L
            keyGuidanceTotal = 0
            keyGuidancePendingChecks.clear()
        }
        hideKeyGuidanceOverlay()
    }

    /**
     * Some Sudoku boards are custom-drawn views and do not emit per-cell accessibility
     * click events. Poll the single guided cell as a fallback so a user-entered value
     * still advances guidance and resumes solving.
     */
    private fun startKeyGuidanceMonitor() {
        keyGuidanceMonitorJob?.cancel()
        keyGuidanceMonitorJob = serviceScope.launch(Dispatchers.Default) {
            while (currentCoroutineContext().isActive) {
                delay(KEY_CELL_MONITOR_INTERVAL_MS)
                val target = synchronized(keyGuidanceLock) {
                    pendingKeyCells.firstOrNull()
                } ?: break
                val cell = Pair(target.row, target.col)
                val shouldCheck = synchronized(keyGuidanceLock) {
                    if (pendingKeyCells.none { it.row == target.row && it.col == target.col }) {
                        false
                    } else {
                        keyGuidancePendingChecks.add(cell)
                    }
                }
                if (!shouldCheck) continue

                try {
                    if (isKeyGuidanceCellFilled(target.row, target.col)) {
                        Log.i(
                            TAG,
                            "Key guidance monitor detected filled cell " +
                                "r${target.row + 1}c${target.col + 1}"
                        )
                        advanceSolvedKeyCell(target.row, target.col)
                    }
                } finally {
                    synchronized(keyGuidanceLock) {
                        keyGuidancePendingChecks.remove(cell)
                    }
                }
            }
        }
    }

    private fun scheduleAutomaticKeyCellHint(item: KeyCellGuidanceItem) {
        automaticKeyHintJob?.cancel()
        automaticKeyHintJob = serviceScope.launch {
            repeat(AUTOMATIC_KEY_HINT_MAX_ATTEMPTS) { attempt ->
                delay(
                    if (attempt == 0) AUTOMATIC_KEY_HINT_INITIAL_DELAY_MS
                    else AUTOMATIC_KEY_HINT_RETRY_DELAY_MS
                )

                val currentItem = synchronized(keyGuidanceLock) {
                    pendingKeyCells.firstOrNull()
                } ?: return@launch
                if (currentItem.row != item.row || currentItem.col != item.col) {
                    return@launch
                }

                val boardBounds = synchronized(keyGuidanceLock) {
                    keyGuidanceBoardBounds?.let { bounds -> Rect(bounds) }
                } ?: return@launch
                val cellWidth = boardBounds.width() / 9f
                val cellHeight = boardBounds.height() / 9f
                val cellX = (boardBounds.left + (item.col + 0.5f) * cellWidth).toInt()
                val cellY = (boardBounds.top + (item.row + 0.5f) * cellHeight).toInt()
                performTap(cellX, cellY)
                delay(AUTOMATIC_KEY_HINT_FOCUS_DELAY_MS)

                val root = rootInActiveWindow
                if (root == null) {
                    Log.w(TAG, "Automatic Pista attempt ${attempt + 1}: active window unavailable")
                    return@repeat
                }
                val nodes = collectAccessibilityNodes(root)
                val hintTarget = findVisibleAutomationTarget(
                    nodes,
                    ScreenAutomationPolicy::isHintActionViewId
                )
                if (hintTarget == null) {
                    Log.w(TAG, "Automatic Pista attempt ${attempt + 1}: hintView unavailable")
                    return@repeat
                }

                val clicked = hintTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked) {
                    val hintBounds = Rect().also(hintTarget::getBoundsInScreen)
                    if (!hintBounds.isEmpty) {
                        performTap(hintBounds.centerX(), hintBounds.centerY())
                    }
                }
                Log.i(
                    TAG,
                    "Key Cell r${item.row + 1}c${item.col + 1}: automatically selected Pista " +
                        "(accessibilityClick=$clicked, attempt=${attempt + 1})"
                )

                delay(AUTOMATIC_KEY_HINT_RESULT_WAIT_MS)
                val stillPending = synchronized(keyGuidanceLock) {
                    pendingKeyCells.any { pending ->
                        pending.row == item.row && pending.col == item.col
                    }
                }
                if (!stillPending) return@launch
            }

            Log.w(
                TAG,
                "Automatic Pista did not fill Key Cell r${item.row + 1}c${item.col + 1}"
            )
        }
    }

    private fun maybeHandleKeyCellTap(event: AccessibilityEvent) {
        if (
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SELECTED
        ) {
            return
        }

        val source = event.source ?: return
        val tappedBounds = Rect()
        source.getBoundsInScreen(tappedBounds)
        source.recycle()

        val boardBounds: Rect
        synchronized(keyGuidanceLock) {
            boardBounds = keyGuidanceBoardBounds?.let { Rect(it) } ?: return
        }

        val centerX = tappedBounds.centerX()
        val centerY = tappedBounds.centerY()
        if (!boardBounds.contains(centerX, centerY)) return
        if (boardBounds.width() <= 0 || boardBounds.height() <= 0) return

        val row = (((centerY - boardBounds.top).toFloat() / boardBounds.height()) * 9f)
            .toInt()
            .coerceIn(0, 8)
        val col = (((centerX - boardBounds.left).toFloat() / boardBounds.width()) * 9f)
            .toInt()
            .coerceIn(0, 8)
        val tappedCell = Pair(row, col)

        val now = System.currentTimeMillis()
        synchronized(keyGuidanceLock) {
            if (lastGuidanceTap == tappedCell && now - lastGuidanceTapAtMs < 350L) {
                return
            }
            lastGuidanceTap = tappedCell
            lastGuidanceTapAtMs = now
        }

        synchronized(keyGuidanceLock) {
            if (pendingKeyCells.isEmpty()) return
            if (pendingKeyCells.none { it.row == row && it.col == col }) return
            if (keyGuidancePendingChecks.contains(tappedCell)) return
            keyGuidancePendingChecks.add(tappedCell)
        }

        serviceScope.launch(Dispatchers.Default) {
            try {
                delay(KEY_CELL_FILL_CHECK_DELAY_MS)
                if (!isKeyGuidanceCellFilled(row, col)) {
                    return@launch
                }
                advanceSolvedKeyCell(row, col)
            } finally {
                synchronized(keyGuidanceLock) {
                    keyGuidancePendingChecks.remove(tappedCell)
                }
            }
        }
    }

    private fun scheduleScreenAutomation(event: AccessibilityEvent) {
        if (event.packageName?.toString() == packageName) return
        if (event.eventType !in SCREEN_AUTOMATION_EVENT_TYPES) return
        if (screenAutomationJob?.isActive == true) return

        screenAutomationJob = serviceScope.launch(Dispatchers.Default) {
            delay(SCREEN_AUTOMATION_DEBOUNCE_MS)
            processVisibleScreenAutomation()
        }
    }

    private suspend fun processVisibleScreenAutomation() {
        val root = rootInActiveWindow ?: return
        val nodes = collectAccessibilityNodes(root)

        if (gameCompletionGate.waiting) {
            val boardStillVisible = nodes.any { it.isVisibleToUser &&
                ScreenAutomationPolicy.isSudokuBoardViewId(it.viewIdResourceName) }
            val resultVisible = nodes.any { it.isVisibleToUser && GameInputPolicy.isResultViewId(it.viewIdResourceName) }
            val completionStillVisible = nodes.any { it.isVisibleToUser &&
                (GameInputPolicy.isAutoCompletingViewId(it.viewIdResourceName) ||
                    GameInputPolicy.isAutoCompleting(it.text) || GameInputPolicy.isAutoCompleting(it.contentDescription)) }
            if (completionStillVisible) gameCompletionGate.notice(root.packageName?.toString(), listOf("Autocompletando"))
            else gameCompletionGate.observeBoard(root.packageName?.toString(), boardStillVisible && !resultVisible, android.os.SystemClock.elapsedRealtime())
            autoBoardStartJob?.cancel()
            if (gameCompletionGate.waiting) {
                screenAutomationFollowUpJob?.cancel()
                screenAutomationFollowUpJob = serviceScope.launch {
                    delay(1_300L)
                    processVisibleScreenAutomation()
                }
                nodes.forEach { it.recycle() }
                return
            }
            autoBoardWasVisible = false
        }

        if ((isSolving || solvingJob?.isActive == true) && nodes.any { node ->
                node.isVisibleToUser && GameInputPolicy.isResultViewId(node.viewIdResourceName)
            }) {
            autoBoardStartJob?.cancel()
            screenAutomationFollowUpJob?.cancel()
            screenAutomationFollowUpJob = serviceScope.launch {
                while (isSolving || solvingJob?.isActive == true) delay(250L)
                delay(150L)
                processVisibleScreenAutomation()
            }
            nodes.forEach { it.recycle() }
            return
        }

        val resultContinueTarget = findVisibleAutomationTarget(
            nodes,
            ScreenAutomationPolicy::isResultContinueViewId
        )
        if (resultContinueTarget != null) {
            autoBoardWasVisible = false
            autoBoardStartJob?.cancel()
            autoBoardStartJob = null
            clickPuzzleFlowTarget(resultContinueTarget, "Continue")
            return
        }

        val prizeClaimTarget = findVisibleAutomationTarget(
            nodes,
            ScreenAutomationPolicy::isPrizeClaimViewId
        )
        if (prizeClaimTarget != null) {
            autoBoardWasVisible = false
            autoBoardStartJob?.cancel()
            autoBoardStartJob = null
            clickPuzzleFlowTarget(prizeClaimTarget, "Claim prize")
            return
        }

        val rewardTarget = findMultipliedRewardTarget(nodes)
        if (rewardTarget != null) {
            autoBoardWasVisible = false
            autoBoardStartJob?.cancel()
            autoBoardStartJob = null
            clickMultipliedReward(rewardTarget)
            scheduleScreenAutomationFollowUp()
            return
        }

        val nextPuzzlePlayTarget = findVisibleAutomationTarget(
            nodes,
            ScreenAutomationPolicy::isNextPuzzlePlayViewId
        )
        if (nextPuzzlePlayTarget != null) {
            autoBoardWasVisible = false
            autoBoardStartJob?.cancel()
            autoBoardStartJob = null
            clickPuzzleFlowTarget(nextPuzzlePlayTarget, "Play next puzzle")
            return
        }

        val hasHexadokuMarker = nodes.any { node ->
            node.isVisibleToUser &&
                (ScreenAutomationPolicy.isHexadokuLabel(node.text) ||
                    ScreenAutomationPolicy.isHexadokuLabel(node.contentDescription))
        }

        val rootBounds = Rect().also(root::getBoundsInScreen)
        val hasLargeBoardNode = nodes.any { node ->
            if (!node.isVisibleToUser || !ScreenAutomationPolicy.isSudokuBoardViewId(node.viewIdResourceName)) {
                false
            } else {
                val bounds = Rect().also(node::getBoundsInScreen)
                bounds.width() >= (rootBounds.width() * MIN_BOARD_WIDTH_RATIO).toInt() &&
                    bounds.height() >= (rootBounds.width() * MIN_BOARD_WIDTH_RATIO).toInt()
            }
        }
        val hasBoardScreenMarkers = ScreenAutomationPolicy.hasSudokuBoardScreenMarkers(
            nodes.asSequence()
                .filter { node -> node.isVisibleToUser }
                .map { node -> node.viewIdResourceName }
                .asIterable()
        )
        val boardVisible = hasLargeBoardNode || hasBoardScreenMarkers
        val detectedMode = ScreenAutomationPolicy.automaticBoardMode(
            boardVisible = boardVisible,
            hasHexadokuMarker = hasHexadokuMarker
        )

        if (detectedMode == ScreenAutomationPolicy.AutomaticBoardMode.NONE) {
            autoBoardWasVisible = false
            autoBoardMode = ScreenAutomationPolicy.AutomaticBoardMode.NONE
            suppressAutoHexadokuUntilBoardLeaves = false
            autoBoardStartJob?.cancel()
            autoBoardStartJob = null
            return
        }
        if (autoBoardWasVisible && autoBoardMode == detectedMode) return
        autoBoardWasVisible = true
        autoBoardMode = detectedMode

        autoBoardStartJob?.cancel()
        autoBoardStartJob = serviceScope.launch {
            delay(AUTO_BOARD_START_DELAY_MS)
            val hasGuidance = synchronized(keyGuidanceLock) { pendingKeyCells.isNotEmpty() }
            if (
                autoBoardWasVisible &&
                autoBoardMode == detectedMode &&
                !isSolving &&
                solvingJob?.isActive != true &&
                !hasGuidance &&
                !(
                    detectedMode == ScreenAutomationPolicy.AutomaticBoardMode.SIXTEEN_BY_SIXTEEN &&
                        suppressAutoHexadokuUntilBoardLeaves
                    ) &&
                ScreenCaptureService.instance != null
            ) {
                when (detectedMode) {
                    ScreenAutomationPolicy.AutomaticBoardMode.SIXTEEN_BY_SIXTEEN -> {
                        Log.i(TAG, "16x16 Sudoku board detected; starting solver automatically")
                        startSolving16x16()
                    }
                    ScreenAutomationPolicy.AutomaticBoardMode.NINE_BY_NINE -> {
                        Log.i(TAG, "9x9 Sudoku board detected; starting solver automatically")
                        startSolving()
                    }
                    ScreenAutomationPolicy.AutomaticBoardMode.NONE -> Unit
                }
            }
        }
    }

    private fun collectAccessibilityNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty() && result.size < MAX_AUTOMATION_TREE_NODES) {
            val node = queue.removeFirst()
            result.add(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return result
    }

    private fun findVisibleAutomationTarget(
        nodes: List<AccessibilityNodeInfo>,
        matchesViewId: (String?) -> Boolean
    ): AccessibilityNodeInfo? {
        return nodes.firstOrNull { node ->
            node.isVisibleToUser &&
                node.isEnabled &&
                matchesViewId(node.viewIdResourceName)
        }
    }

    private suspend fun awaitVisibleAutomationTarget(
        matchesViewId: (String?) -> Boolean
    ): AccessibilityNodeInfo? {
        repeat(AUTOMATION_TARGET_LOOKUP_ATTEMPTS) { attempt ->
            ensureNotStopped()
            val roots = mutableListOf<AccessibilityNodeInfo>()
            rootInActiveWindow?.let(roots::add)
            windows.forEach { window -> window.root?.let(roots::add) }
            for (root in roots) {
                findVisibleAutomationTarget(
                    collectAccessibilityNodes(root),
                    matchesViewId
                )?.let { return it }
            }
            if (attempt + 1 < AUTOMATION_TARGET_LOOKUP_ATTEMPTS) {
                delay(AUTOMATION_TARGET_LOOKUP_RETRY_DELAY_MS)
            }
        }
        return null
    }

    private fun findMultipliedRewardTarget(
        nodes: List<AccessibilityNodeInfo>
    ): AccessibilityNodeInfo? {
        nodes.firstOrNull { node ->
            if (!node.isVisibleToUser ||
                !node.isEnabled ||
                !ScreenAutomationPolicy.isMultipliedRewardButtonViewId(node.viewIdResourceName)
            ) {
                false
            } else {
                val bounds = Rect().also(node::getBoundsInScreen)
                !bounds.isEmpty
            }
        }?.let { return it }

        val clickableNodes = nodes.filter { node ->
            node.isVisibleToUser && node.isEnabled && node.isClickable
        }
        nodes.firstOrNull { node ->
            node.isVisibleToUser &&
                (
                    ScreenAutomationPolicy.isMultipliedRewardLabel(node.text) ||
                        ScreenAutomationPolicy.isMultipliedRewardLabel(node.contentDescription)
                    )
        }?.let { labelNode ->
            val labelBounds = Rect().also(labelNode::getBoundsInScreen)
            clickableNodes
                .asSequence()
                .map { node -> Pair(node, Rect().also(node::getBoundsInScreen)) }
                .filter { (_, bounds) ->
                    bounds.contains(labelBounds.centerX(), labelBounds.centerY())
                }
                .minByOrNull { (_, bounds) -> bounds.width().toLong() * bounds.height() }
                ?.first
                ?.let { return it }
        }

        return nodes
            .asSequence()
            .filter { node ->
                ScreenAutomationPolicy.containsRewardVerb(node.text) ||
                    ScreenAutomationPolicy.containsRewardVerb(node.contentDescription)
            }
            .map { node -> findActionableAncestor(node) }
            .distinctBy { node ->
                Rect().also(node::getBoundsInScreen).let { bounds ->
                    "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
                }
            }
            .filter { node ->
                ScreenAutomationPolicy.isMultipliedRewardText(collectNodeText(node))
            }
            .sortedBy { node -> Rect().also(node::getBoundsInScreen).top }
            .firstOrNull()
    }

    private fun findActionableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        repeat(MAX_REWARD_ANCESTOR_DEPTH) {
            if (current.isClickable) return current
            current = current.parent ?: return current
        }
        return current
    }

    private fun collectNodeText(node: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(Pair(node, 0))
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            current.text?.takeIf { it.isNotBlank() }?.let { parts.add(it.toString()) }
            current.contentDescription?.takeIf { it.isNotBlank() }?.let { parts.add(it.toString()) }
            if (depth >= MAX_REWARD_TEXT_DEPTH) continue
            for (index in 0 until current.childCount) {
                current.getChild(index)?.let { child ->
                    queue.addLast(Pair(child, depth + 1))
                }
            }
        }
        return parts.joinToString(" ")
    }

    private fun clickMultipliedReward(target: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastRewardClickAtMs < REWARD_CLICK_COOLDOWN_MS) return
        lastRewardClickAtMs = now

        val label = collectNodeText(target).ifBlank {
            target.viewIdResourceName ?: "multiplied reward button"
        }
        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            val bounds = Rect().also(target::getBoundsInScreen)
            if (!bounds.isEmpty) {
                serviceScope.launch {
                    performTap(bounds.centerX(), bounds.centerY())
                }
            }
        }
        Log.i(TAG, "Automatically selected multiplied reward: $label")
    }

    private fun clickPuzzleFlowTarget(target: AccessibilityNodeInfo, actionLabel: String) {
        val now = System.currentTimeMillis()
        val targetId = target.viewIdResourceName
        if (
            targetId == lastPuzzleFlowTargetId &&
            now - lastPuzzleFlowClickAtMs < PUZZLE_FLOW_CLICK_COOLDOWN_MS
        ) {
            scheduleScreenAutomationFollowUp()
            return
        }
        lastPuzzleFlowClickAtMs = now
        lastPuzzleFlowTargetId = targetId

        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            val bounds = Rect().also(target::getBoundsInScreen)
            if (!bounds.isEmpty) {
                serviceScope.launch {
                    performTap(bounds.centerX(), bounds.centerY())
                }
            }
        }
        Log.i(TAG, "Continuous puzzle mode: selected $actionLabel")
        scheduleScreenAutomationFollowUp()
    }

    private fun scheduleScreenAutomationFollowUp() {
        if (screenAutomationFollowUpJob?.isActive == true) return
        screenAutomationFollowUpJob = serviceScope.launch {
            repeat(PUZZLE_FLOW_RECHECK_ATTEMPTS) {
                delay(PUZZLE_FLOW_RECHECK_DELAY_MS)
                if (screenAutomationJob?.isActive != true) {
                    processVisibleScreenAutomation()
                }
                if (autoBoardWasVisible || isSolving) return@launch
            }
        }
    }

    private fun advanceSolvedKeyCell(row: Int, col: Int) {
        var completed: KeyCellGuidanceItem? = null
        var next: KeyCellGuidanceItem? = null
        var totalAfter = 0
        synchronized(keyGuidanceLock) {
            if (pendingKeyCells.isEmpty()) return
            val list = pendingKeyCells.toList()
            val matchedIndex = list.indexOfFirst { it.row == row && it.col == col }
            if (matchedIndex < 0) return
            completed = list[matchedIndex]
            val done = completed ?: return
            confirmedKeyCellOptions[Pair(row, col)] = setOf(done.optionA, done.optionB)
            pendingKeyCells.removeIf { it.row == row && it.col == col }
            next = pendingKeyCells.firstOrNull()
            totalAfter = pendingKeyCells.size
            if (totalAfter == 0) {
                keyGuidanceBoardBounds = null
            }
        }

        val done = completed ?: return
        if (next != null) {
            val nextItem = next ?: return
            val totalSnapshot = synchronized(keyGuidanceLock) { keyGuidanceTotal }
            val solvedCount = (totalSnapshot - totalAfter).coerceAtLeast(1)
            val nextIndex = (solvedCount + 1).coerceAtMost(totalSnapshot)
            val bannerMessage = buildKeyCellBannerMessage(nextItem, nextIndex, totalSnapshot)
            showToast(
                "Checked r${done.row + 1} c${done.col + 1}. " +
                    buildKeyCellToastMessage(nextItem, nextIndex, totalSnapshot)
            )
            showKeyGuidanceOverlay(bannerMessage)
            Log.w(
                TAG,
                "Key cell solved r${done.row + 1}c${done.col + 1}; next r${nextItem.row + 1}c${nextItem.col + 1}"
            )
            scheduleAutomaticKeyCellHint(nextItem)
        } else {
            automaticKeyHintJob?.cancel()
            automaticKeyHintJob = null
            showToast("Checked key cell r${done.row + 1} c${done.col + 1}. No more key cells.")
            hideKeyGuidanceOverlay()
            Log.w(TAG, "All key disambiguation cells checked")
        }

        scheduleAutoResumeSolve("key cell r${done.row + 1}c${done.col + 1} confirmed")
    }

    private fun scheduleAutoResumeSolve(reason: String) {
        autoResumeJob?.cancel()
        autoResumeJob = serviceScope.launch {
            delay(AUTO_RESUME_DELAY_MS)

            if (stopRequested) {
                Log.i(TAG, "Auto-resume skipped ($reason): stop requested")
                return@launch
            }
            if (solvingJob?.isActive == true || isSolving) {
                Log.i(TAG, "Auto-resume skipped ($reason): solve already running")
                return@launch
            }

            val now = System.currentTimeMillis()
            if (now - lastAutoResumeAtMs < AUTO_RESUME_MIN_INTERVAL_MS) {
                Log.i(TAG, "Auto-resume skipped ($reason): cooldown active")
                return@launch
            }
            lastAutoResumeAtMs = now

            Log.i(TAG, "Auto-resume triggered ($reason)")
            showToast("Updating puzzle and resuming solve...")
            startSolving(preserveKeyCellConfirmations = true)
        }
    }

    private suspend fun isKeyGuidanceCellFilled(row: Int, col: Int): Boolean {
        val boardBounds = synchronized(keyGuidanceLock) {
            keyGuidanceBoardBounds?.let { Rect(it) }
        } ?: return false

        val captureService = ScreenCaptureService.instance ?: return false
        return withContext(Dispatchers.IO) {
            try {
                if (boardBounds.width() <= 0 || boardBounds.height() <= 0) return@withContext false
                val cellWidth = boardBounds.width() / 9f
                val cellHeight = boardBounds.height() / 9f

                val cellBounds = Rect(
                    (boardBounds.left + col * cellWidth).toInt(),
                    (boardBounds.top + row * cellHeight).toInt(),
                    (boardBounds.left + (col + 1) * cellWidth).toInt(),
                    (boardBounds.top + (row + 1) * cellHeight).toInt()
                )
                val cellBitmap = captureService.captureRegion(cellBounds)
                    ?: return@withContext false
                try {
                    hasKeyGuidanceDigitInk(cellBitmap)
                } finally {
                    cellBitmap.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed checking whether key cell is filled", e)
                false
            }
        }
    }

    private fun hasKeyGuidanceDigitInk(bitmap: Bitmap): Boolean {
        val marginX = (bitmap.width * 0.22f).toInt()
        val marginY = (bitmap.height * 0.22f).toInt()
        val startX = marginX.coerceIn(0, bitmap.width - 1)
        val endX = (bitmap.width - marginX).coerceIn(startX + 1, bitmap.width)
        val startY = marginY.coerceIn(0, bitmap.height - 1)
        val endY = (bitmap.height - marginY).coerceIn(startY + 1, bitmap.height)

        var inkPixels = 0
        var sampleCount = 0
        var activeRows = 0
        var sampledRows = 0
        val sampleStep = 2

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
                // User-entered digits are often saturated blue. The luminance and
                // vertical-spread checks reject the pale selection background and
                // grid borders without discarding those valid digit pixels.
                if (luminance <= KEY_CELL_MAX_INK_LUMA) {
                    inkPixels++
                    rowInkPixels++
                }
            }
            if (rowInkPixels > 0) activeRows++
        }

        return DigitInkPolicy.isDigitLike(
            inkPixels = inkPixels,
            sampleCount = sampleCount,
            activeRows = activeRows,
            sampledRows = sampledRows
        )
    }

    private fun buildKeyCellBannerMessage(
        item: KeyCellGuidanceItem,
        index: Int,
        total: Int
    ): String {
        return "KEY CELL $index/$total: row ${item.row + 1}, col ${item.col + 1} " +
            "(${item.optionA}/${item.optionB})\n" +
            buildKeyCellHighlightTip()
    }

    private fun buildKeyCellToastMessage(
        item: KeyCellGuidanceItem,
        index: Int,
        total: Int
    ): String {
        return "Key cell $index/$total: r${item.row + 1} c${item.col + 1} " +
            "(${item.optionA}/${item.optionB}). " +
            buildKeyCellHighlightTip()
    }

    private fun buildKeyCellHighlightTip(): String {
        return "Pista will be selected automatically to reveal the correct value."
    }

    private fun showKeyGuidanceOverlay(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            try {
                val windowMgr = keyGuidanceWindowManager
                    ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also {
                        keyGuidanceWindowManager = it
                    }

                val overlay = keyGuidanceOverlayView ?: TextView(this@SudokuAccessibilityService).apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(212, 32, 32, 32))
                    setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    elevation = dpToPx(6).toFloat()
                }.also { view ->
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        y = systemTopInsetPx(windowMgr) + dpToPx(12)
                    }
                    windowMgr.addView(view, params)
                    keyGuidanceOverlayView = view
                }

                if (overlay.text?.toString() != message) {
                    overlay.text = message
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to show key guidance overlay", t)
            }
        }
    }

    private fun hideKeyGuidanceOverlay() {
        serviceScope.launch(Dispatchers.Main) {
            try {
                val overlay = keyGuidanceOverlayView
                if (overlay != null) {
                    keyGuidanceWindowManager?.removeView(overlay)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to hide key guidance overlay", t)
            } finally {
                keyGuidanceOverlayView = null
                keyGuidanceWindowManager = null
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun systemTopInsetPx(windowManager: WindowManager): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
                ).top
        }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun clearUncertainClues(
        grid: SudokuGrid,
        uncertainCells: List<Pair<Int, Int>>,
        preferredCells: List<Pair<Int, Int>> = emptyList(),
        minCluesToKeep: Int = 17,
        onlyPreferred: Boolean = false,
        maxClears: Int = Int.MAX_VALUE
    ): SudokuGrid {
        if (uncertainCells.isEmpty()) return grid

        val values = Array(9) { r -> IntArray(9) { c -> grid.values[r][c] } }
        var clueCount = values.sumOf { row -> row.count { it != 0 } }
        if (clueCount <= minCluesToKeep) return grid

        val preferred = preferredCells.toSet()

        val orderedAll = uncertainCells
            .distinct()
            .sortedByDescending { (row, col) ->
                var score = 0
                if (preferred.contains(Pair(row, col))) score += 100
                score += when (values[row][col]) {
                    6, 9, 1, 7 -> 20
                    8, 3, 5, 4, 2 -> 10
                    else -> 5
                }
                score
            }

        val ordered = if (onlyPreferred && preferred.isNotEmpty()) {
            orderedAll.filter { preferred.contains(it) }
        } else {
            orderedAll
        }

        var cleared = 0
        var clearedPreferred = 0
        for ((row, col) in ordered) {
            if (cleared >= maxClears) break
            val digit = values[row][col]
            if (digit == 0) continue
            if (clueCount - 1 < minCluesToKeep) break

            values[row][col] = 0
            clueCount--
            cleared++
            if (preferred.contains(Pair(row, col))) clearedPreferred++
        }

        if (cleared == 0) {
            return grid
        }

        val locked = Array(9) { r ->
            BooleanArray(9) { c -> values[r][c] != 0 }
        }
        Log.w(
            TAG,
            "Cleared $cleared uncertain OCR clue(s) " +
                "(targeted=$clearedPreferred). Remaining clues: $clueCount"
        )
        return SudokuGrid(values, locked)
    }

    private data class OcrBitmapVariant(
        val label: String,
        val bitmap: Bitmap,
        val recycleAfterUse: Boolean
    )

    private data class OcrCandidateEvaluation(
        val label: String,
        val ocrResult: OcrResult,
        val validation: ValidationResult,
        val clueCount: Int,
        val uncertainCount: Int,
        val contradictionCount: Int,
        val confidenceScore: Int
    )

    private data class HintSolutionRefresh(
        val grid: SudokuGrid,
        val solution: Array<IntArray>,
        val boardBounds: Rect
    )

    private data class HexadokuAttemptOutcome(
        val restartRequested: Boolean,
        val reason: String = "",
        val restartMessage: String = ""
    ) {
        companion object {
            fun finished() = HexadokuAttemptOutcome(restartRequested = false)

            fun restart(
                reason: String,
                restartMessage: String = "Re-scanning and restarting 16x16..."
            ) = HexadokuAttemptOutcome(
                restartRequested = true,
                reason = reason,
                restartMessage = restartMessage
            )
        }
    }

    companion object {
        private const val TAG = "SudokuAccessibility"
        private val SCREEN_AUTOMATION_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        )
        private const val SCREEN_AUTOMATION_DEBOUNCE_MS = 250L
        private const val AUTO_BOARD_START_DELAY_MS = 700L
        private const val REWARD_CLICK_COOLDOWN_MS = 30_000L
        private const val PUZZLE_FLOW_CLICK_COOLDOWN_MS = 5_000L
        private const val PUZZLE_FLOW_RECHECK_DELAY_MS = 1_200L
        private const val PUZZLE_FLOW_RECHECK_ATTEMPTS = 8
        private const val MIN_BOARD_WIDTH_RATIO = 0.62f
        private const val MAX_AUTOMATION_TREE_NODES = 500
        private const val MAX_REWARD_ANCESTOR_DEPTH = 4
        private const val MAX_REWARD_TEXT_DEPTH = 3
        private const val KEY_CELL_FILL_CHECK_DELAY_MS = 170L
        private const val KEY_CELL_MONITOR_INTERVAL_MS = 750L
        private const val AUTOMATIC_KEY_HINT_INITIAL_DELAY_MS = 650L
        private const val AUTOMATIC_KEY_HINT_FOCUS_DELAY_MS = 450L
        private const val AUTOMATIC_KEY_HINT_RESULT_WAIT_MS = 1_500L
        private const val AUTOMATIC_KEY_HINT_RETRY_DELAY_MS = 1_500L
        private const val AUTOMATIC_KEY_HINT_MAX_ATTEMPTS = 2
        private const val MAX_AUTOMATIC_HINT_RECOVERIES = 81
        private const val MAX_NO_HINT_RECOVERIES = 2
        private const val AUTOMATIC_HINT_FOCUS_DELAY_MS = 350L
        private const val AUTOMATIC_HINT_RESULT_WAIT_MS = 900L
        private const val AUTOMATIC_HINT_VERIFY_RETRY_DELAY_MS = 450L
        private const val AUTOMATIC_HINT_VERIFY_ATTEMPTS = 4
        private const val AUTOMATIC_HINT_RESUME_DELAY_MS = 250L
        private const val AUTOMATION_TARGET_LOOKUP_ATTEMPTS = 6
        private const val AUTOMATION_TARGET_LOOKUP_RETRY_DELAY_MS = 180L
        private const val AUTO_RESUME_DELAY_MS = 250L
        private const val AUTO_RESUME_MIN_INTERVAL_MS = 500L
        private const val HEXADOKU_RESTART_DELAY_MS = 4_000L
        private const val MAX_HEXADOKU_SOLVE_ATTEMPTS = 6
        private const val KEY_CELL_MAX_INK_LUMA = 142f
        private const val OCR_VARIANT_INNER_CROP_SMALL = 0.012f
        private const val OCR_EARLY_ACCEPT_MIN_CLUES = 17
        private const val OCR_EARLY_ACCEPT_MAX_UNCERTAIN = 12
        private const val OCR_TRUSTED_SUCCESS_MAX_UNCERTAIN = 9
        private const val OCR_CLUE_DENSITY_SOFT_MAX = 45
        private const val OCR_DEEP_RECOVERY_MIN_UNCERTAIN = 6
        private const val MAX_OBSERVED_KEY_CELL_RECOVERY_STEPS = 12
        private const val MIN_HEXADOKU_CLUES = 40
        var instance: SudokuAccessibilityService? = null
            private set
    }
}

private data class KeyCellGuidanceItem(
    val row: Int,
    val col: Int,
    val optionA: Int,
    val optionB: Int
)
