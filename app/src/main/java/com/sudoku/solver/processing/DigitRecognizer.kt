package com.sudoku.solver.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sudoku.solver.model.SudokuGrid
import com.sudoku.solver.model.OcrEngine
import com.sudoku.solver.model.OcrResult
import com.sudoku.solver.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Recognizes digits in Sudoku cells using ML Kit OCR or Tesseract
 * Ported from Python implementation in a.py
 *
 * OCR engine can be switched in Constants.kt (OCR_ENGINE)
 */
class DigitRecognizer(
    private val context: Context,
    private val useScreenDigitTemplates: Boolean = true
) : AutoCloseable {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val boardDetector = BoardDetector()
    private val mlPassCache = WeakHashMap<Bitmap, HybridMlPassCache>()
    private val screenDigitMatcher by lazy { ScreenDigitMatcher(context) }

    // Tesseract recognizer - only initialized when needed
    private val tesseractRecognizerDelegate = lazy {
        TesseractDigitRecognizer(context)
    }
    private val tesseractRecognizer: TesseractDigitRecognizer by tesseractRecognizerDelegate

    @Volatile
    private var closed = false

    /**
     * Extract the 9x9 grid from a warped board image
     * Uses preprocessed binary image for digit detection, original image for OCR accuracy
     */
    suspend fun extractGrid(warpedBitmap: Bitmap): SudokuGrid = withContext(Dispatchers.Default) {
        extractGridWithUncertainty(warpedBitmap).grid
    }

    /**
     * Extract grid with uncertainty information - marks cells detected as 6 or 9
     * so user can verify them (common OCR confusion)
     */
    suspend fun extractGridWithUncertainty(
        warpedBitmap: Bitmap,
        mlKitOnly: Boolean = false
    ): OcrResult = withContext(Dispatchers.Default) {
        // Delegate to Tesseract if that engine is selected
        if (Constants.OCR_ENGINE == OcrEngine.TESSERACT) {
            Log.i(TAG, "Using Tesseract OCR engine")
            return@withContext tesseractRecognizer.extractGridWithUncertainty(warpedBitmap)
        }

        // Use hybrid mode - run both OCR engines and compare
        if (Constants.OCR_ENGINE == OcrEngine.HYBRID) {
            if (mlKitOnly) {
                Log.i(TAG, "Using printed-font matching + ML Kit fast path (no Tesseract)")
            } else {
                Log.i(TAG, "Using HYBRID OCR mode (ML Kit + Tesseract)")
            }
            return@withContext extractGridHybrid(warpedBitmap, mlKitOnly)
        }

        Log.i(TAG, "Using ML Kit OCR engine")
        // Binary preprocessed image (grid lines removed) - used for detecting if cell has a digit
        val preprocessed = boardDetector.preprocessForCells(warpedBitmap)
        val geometry = buildCellGeometry(warpedBitmap.width, warpedBitmap.height)
        Log.i(
            TAG,
            "Cell extraction geometry: inset=${geometry.inset}px, " +
                "board=${warpedBitmap.width}x${warpedBitmap.height}"
        )

        val values = Array(9) { IntArray(9) }
        val locked = Array(9) { BooleanArray(9) }
        val uncertainCells = mutableListOf<Pair<Int, Int>>()
        val occupiedCells = linkedSetOf<Pair<Int, Int>>()
        val observedDigits = linkedMapOf<Pair<Int, Int>, Int>()

        for (row in 0..8) {
            for (col in 0..8) {
                val x1 = geometry.xBounds[col]
                val y1 = geometry.yBounds[row]
                val cellWidth = (geometry.xBounds[col + 1] - x1).coerceAtLeast(1)
                val cellHeight = (geometry.yBounds[row + 1] - y1).coerceAtLeast(1)

                // Extract cell from preprocessed image for digit detection
                val binaryCellBitmap = Bitmap.createBitmap(
                    preprocessed, x1, y1, cellWidth, cellHeight
                )

                // Use binary image only for digit-presence checks.
                // Original-image fallback caused clue flooding on highlighted cells.
                val hasDigit = cellHasDigit(binaryCellBitmap, row, col)

                if (hasDigit) {
                    val cell = Pair(row, col)
                    occupiedCells.add(cell)
                    val cellBitmap = Bitmap.createBitmap(
                        warpedBitmap, x1, y1, cellWidth, cellHeight
                    )
                    val mlRead = ocrCellWithUncertainty(cellBitmap)
                    val digit = mlRead.digit
                    val isUncertain = mlRead.isUncertain
                    if (digit in 1..9) observedDigits[cell] = digit
                    values[row][col] = digit
                    locked[row][col] = digit > 0
                    if (isUncertain && digit > 0) {
                        uncertainCells.add(Pair(row, col))
                        Log.i(TAG, "Cell ($row,$col): $digit (UNCERTAIN - check 6/9 or 1/7)")
                    } else {
                        Log.i(TAG, "Cell ($row,$col): $digit")
                    }
                    cellBitmap.recycle()
                } else {
                    values[row][col] = 0
                    locked[row][col] = false
                }

                binaryCellBitmap.recycle()
            }
        }

        preprocessed.recycle()
        Log.i(TAG, "Extracted grid:\n${gridToString(values)}")
        Log.i(TAG, "Uncertain 6/9 cells: ${uncertainCells.size} - ${uncertainCells}")
        recoverCellsFromBoardContext(warpedBitmap, OcrResult(
            grid = SudokuGrid(values, locked),
            uncertainCells = uncertainCells,
            occupiedCells = occupiedCells,
            observedDigits = observedDigits
        ))
    }

    /**
     * Hybrid mode: Run both ML Kit and Tesseract, use the best result
     * - When both agree: high confidence
     * - When they disagree on 6/9 or 1/7: prefer Tesseract
     * - When one finds digit and other doesn't: use the one that found it
     */
    private suspend fun extractGridHybrid(
        warpedBitmap: Bitmap,
        mlKitOnly: Boolean = false
    ): OcrResult = withContext(Dispatchers.Default) {
        val hybridStartMs = System.currentTimeMillis()
        val cachedMlPass = synchronized(mlPassCache) {
            mlPassCache[warpedBitmap]?.takeIf { cache ->
                cache.width == warpedBitmap.width && cache.height == warpedBitmap.height
            }
        }
        val preprocessed = if (cachedMlPass == null) {
            boardDetector.preprocessForCells(warpedBitmap)
        } else {
            Log.i(TAG, "Reusing cached ML Kit cell reads for full hybrid OCR")
            null
        }
        val geometry = buildCellGeometry(warpedBitmap.width, warpedBitmap.height)
        Log.i(
            TAG,
            "Hybrid cell geometry: inset=${geometry.inset}px, " +
                "board=${warpedBitmap.width}x${warpedBitmap.height}"
        )

        val values = Array(9) { IntArray(9) }
        val locked = Array(9) { BooleanArray(9) }
        val uncertainCells = mutableListOf<Pair<Int, Int>>()
        val occupiedCells = linkedSetOf<Pair<Int, Int>>()
        val observedDigits = linkedMapOf<Pair<Int, Int>, Int>()
        val droppedCandidates = mutableListOf<HybridDroppedCandidate>()
        val workItems = mutableListOf<HybridCellWorkItem>()
        val detectedCells = Array(9) { BooleanArray(9) }
        val cachedReads = Array(9) { arrayOfNulls<MlKitCellRead>(9) }
        val cachedStyles = Array(9) { Array(9) { UserStyledDigitKind.NONE } }
        var digitCells = 0
        var tesseractRuns = 0
        var tesseractSkippedByMl = 0
        var tesseractSkippedByBudget: Int

        for (row in 0..8) {
            for (col in 0..8) {
                val x1 = geometry.xBounds[col]
                val y1 = geometry.yBounds[row]
                val cellWidth = (geometry.xBounds[col + 1] - x1).coerceAtLeast(1)
                val cellHeight = (geometry.yBounds[row + 1] - y1).coerceAtLeast(1)

                val hasDigit = cachedMlPass?.detectedCells?.get(row)?.get(col) ?: run {
                    val binaryCellBitmap = Bitmap.createBitmap(
                        requireNotNull(preprocessed), x1, y1, cellWidth, cellHeight
                    )
                    try {
                        cellHasDigit(binaryCellBitmap, row, col)
                    } finally {
                        binaryCellBitmap.recycle()
                    }
                }
                detectedCells[row][col] = hasDigit

                if (hasDigit) {
                    occupiedCells.add(Pair(row, col))
                    digitCells++
                    val cellBitmap = Bitmap.createBitmap(
                        warpedBitmap, x1, y1, cellWidth, cellHeight
                    )
                    val mlRead = cachedMlPass?.reads?.get(row)?.get(col)
                        ?: ocrCellWithUncertainty(cellBitmap)
                    val userStyledDigitKind =
                        cachedMlPass?.styles?.get(row)?.get(col)
                            ?: if (mlRead.digit in 1..9) {
                            detectLikelyUserStyledDigit(cellBitmap)
                        } else {
                            UserStyledDigitKind.NONE
                        }
                    cachedReads[row][col] = mlRead
                    cachedStyles[row][col] = userStyledDigitKind
                    val likelyBlueStyled =
                        userStyledDigitKind == UserStyledDigitKind.BLUE &&
                            mlRead.digit in 1..9 &&
                            isConfusedDigit(mlRead.digit)
                    val wantsTesseract = !mlKitOnly && shouldRunTesseractForHybrid(mlRead)
                    if (!wantsTesseract) {
                        tesseractSkippedByMl++
                    }
                    workItems.add(
                        HybridCellWorkItem(
                            row = row,
                            col = col,
                            cellBitmap = cellBitmap,
                            mlRead = mlRead,
                            userStyledDigitKind = userStyledDigitKind,
                            likelyBlueStyled = likelyBlueStyled,
                            wantsTesseract = wantsTesseract,
                            tesseractPriority = calculateHybridTesseractPriority(
                                mlRead = mlRead,
                                likelyBlueStyled = likelyBlueStyled
                            )
                        )
                    )
                } else {
                    values[row][col] = 0
                    locked[row][col] = false
                }
            }
        }

        if (mlKitOnly && cachedMlPass == null) {
            synchronized(mlPassCache) {
                mlPassCache[warpedBitmap] = HybridMlPassCache(
                    width = warpedBitmap.width,
                    height = warpedBitmap.height,
                    detectedCells = detectedCells,
                    reads = cachedReads,
                    styles = cachedStyles
                )
            }
        }

        val styledDigitCount = workItems.count { it.userStyledDigitKind != UserStyledDigitKind.NONE }
        val treatStyledDigitsAsUserEntries =
            OcrStylePolicy.shouldTreatStyledDigitsAsUserEntries(
                digitCells = digitCells,
                styledDigitCount = styledDigitCount
            )
        if (treatStyledDigitsAsUserEntries) {
            Log.i(
                TAG,
                "Treating $styledDigitCount styled digit(s) as editable user entries " +
                    "(digitCells=$digitCells)"
            )
        }

        val tesseractQueue = workItems
            .asSequence()
            .filter { item ->
                item.wantsTesseract &&
                    !(treatStyledDigitsAsUserEntries && item.userStyledDigitKind != UserStyledDigitKind.NONE)
            }
            .sortedByDescending { it.tesseractPriority }
            .toList()

        var tesseractQueueIndex = 0
        while (tesseractQueueIndex < tesseractQueue.size) {
            val elapsedMsBeforeTesseract = System.currentTimeMillis() - hybridStartMs
            val withinTesseractBudget =
                tesseractRuns < HYBRID_TESSERACT_MAX_RUNS &&
                    elapsedMsBeforeTesseract < HYBRID_TESSERACT_TIME_BUDGET_MS
            if (!withinTesseractBudget) {
                break
            }

            val item = tesseractQueue[tesseractQueueIndex]
            item.tesseractRead = tesseractRecognizer.ocrSingleCellDetailed(item.cellBitmap)
            tesseractRuns++
            tesseractQueueIndex++
        }
        tesseractSkippedByBudget = (tesseractQueue.size - tesseractQueueIndex).coerceAtLeast(0)

        for (item in workItems) {
            val row = item.row
            val col = item.col
            val mlRead = item.mlRead
            val mlKitResult = mlRead.digit
            val mlKitUncertain = mlRead.isUncertain
            val mlKitStrongConsensus = mlRead.strongConsensus
            val tesseractRead = item.tesseractRead
            val tesseractResult = tesseractRead?.digit ?: 0
            val tesseractConfidence = tesseractRead?.confidence ?: 0
            val tesseractUncertain = tesseractRead?.isUncertain ?: false
            val tesseractSummary = if (tesseractRead != null) {
                "$tesseractResult (conf=$tesseractConfidence, uncertain=$tesseractUncertain)"
            } else if (!item.wantsTesseract) {
                "SKIPPED (ML consensus)"
            } else {
                "SKIPPED (budget cap)"
            }

            Log.i(
                TAG,
                "Cell ($row,$col): ${if (mlRead.templateMatched) "verified font" else "ML Kit"}=$mlKitResult (uncertain=$mlKitUncertain), " +
                    "Tesseract=$tesseractSummary, " +
                    "mlConsensus=$mlKitStrongConsensus, mlVotes=${mlRead.voteCount}"
            )

            val decision = decideHybridCellValue(
                cellBitmap = item.cellBitmap,
                mlRead = mlRead,
                tesseractRead = tesseractRead,
                likelyBlueStyled = item.likelyBlueStyled
            )
            val decidedValue = decision.value
            if (decidedValue in 1..9) {
                observedDigits[Pair(row, col)] = decidedValue
            }
            var finalValue = decidedValue
            var isUncertain = decision.isUncertain
            var isLocked = finalValue > 0

            if (finalValue > 0 && item.userStyledDigitKind != UserStyledDigitKind.NONE) {
                val styleLabel = when (item.userStyledDigitKind) {
                    UserStyledDigitKind.BLUE -> "blue"
                    UserStyledDigitKind.RED -> "red"
                    UserStyledDigitKind.NONE -> "neutral"
                }
                if (treatStyledDigitsAsUserEntries) {
                    Log.i(
                        TAG,
                        "  -> $styleLabel-styled digit $finalValue treated as editable user entry and dropped"
                    )
                    finalValue = 0
                    isUncertain = false
                    isLocked = false
                } else {
                    Log.i(
                        TAG,
                        "  -> $styleLabel-styled digit $finalValue kept as editable/uncertain"
                    )
                    isUncertain = true
                    isLocked = false
                }
            }

            if (finalValue == 0 && decidedValue == 0) {
                droppedCandidates.add(
                    HybridDroppedCandidate(
                        row = row,
                        col = col,
                        mlDigit = mlKitResult,
                        mlUncertain = mlKitUncertain,
                        mlStrongConsensus = mlKitStrongConsensus,
                        tesseractDigit = tesseractResult,
                        tesseractConfidence = tesseractConfidence,
                        tesseractUncertain = tesseractUncertain
                    )
                )
            }

            values[row][col] = finalValue
            locked[row][col] = finalValue > 0 && isLocked

            if (isUncertain && finalValue > 0) {
                uncertainCells.add(Pair(row, col))
            }

            item.cellBitmap.recycle()
        }

        val recovered = recoverDroppedHybridClues(values, locked, uncertainCells, droppedCandidates)
        if (recovered > 0) {
            Log.i(
                TAG,
                "Hybrid recovery restored $recovered clue(s); total clues now " +
                    values.sumOf { row -> row.count { it != 0 } }
            )
        }

        for ((row, col) in occupiedCells) {
            val recoveredValue = values[row][col]
            if (recoveredValue in 1..9) {
                observedDigits.putIfAbsent(Pair(row, col), recoveredValue)
            }
        }

        preprocessed?.recycle()
        val elapsedMs = System.currentTimeMillis() - hybridStartMs
        val tesseractPct = if (digitCells == 0) 0 else (tesseractRuns * 100 / digitCells)
        Log.i(
            TAG,
            "Hybrid OCR timing: ${elapsedMs}ms (digitCells=$digitCells, " +
                "tesseractRuns=$tesseractRuns/$digitCells, $tesseractPct%, " +
                "skippedByMl=$tesseractSkippedByMl, skippedByBudget=$tesseractSkippedByBudget)"
        )
        Log.i(TAG, "Hybrid OCR result:\n${gridToString(values)}")
        Log.i(TAG, "Uncertain cells: ${uncertainCells.size}")
        recoverCellsFromBoardContext(warpedBitmap, OcrResult(
            grid = SudokuGrid(values, locked),
            uncertainCells = uncertainCells,
            occupiedCells = occupiedCells,
            observedDigits = observedDigits
        ), treatStyledDigitsAsUserEntries)
    }

    /** Board context checks isolated glyph misses and uncertain 1/7 or 6/9 reads across scales. */
    private suspend fun recoverCellsFromBoardContext(
        bitmap: Bitmap, scan: OcrResult, treatStyledDigitsAsUserEntries: Boolean = false
    ): OcrResult {
        val unread = scan.occupiedCells.filterTo(linkedSetOf()) {
            scan.grid.values[it.first][it.second] == 0 && scan.observedDigits[it] !in 1..9
        }
        val ambiguous = scan.uncertainCells.filterTo(linkedSetOf()) {
            scan.observedDigits[it] in setOf(1, 7, 6, 9)
        }
        if (unread.isEmpty() && ambiguous.isEmpty()) return scan
        return try {
            val geometry = buildCellGeometry(bitmap.width, bitmap.height)
            suspend fun readBoard(source: Bitmap): Map<Pair<Int, Int>, Set<Int>> {
                val text = textRecognizer.process(InputImage.fromBitmap(source, 0)).await()
                val reads = linkedMapOf<Pair<Int, Int>, MutableSet<Int>>()
                val scaleX = bitmap.width.toDouble() / source.width
                val scaleY = bitmap.height.toDouble() / source.height
                // Nearby clues may be grouped into one element such as "815". Use
                // character boxes rather than assigning that entire token to one cell.
                val glyphs = text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.flatMap { element ->
                    if (element.symbols.isEmpty()) listOf(element.text to element.boundingBox)
                    else element.symbols.map { it.text to it.boundingBox }
                }
                for ((glyph, box) in glyphs) {
                    val digit = parseSingleDigitCandidate(normalizeOcrText(glyph))
                    if (digit !in 1..9) continue
                    val sourceBounds = box ?: continue
                    val bounds = android.graphics.Rect(
                        (sourceBounds.left * scaleX).roundToInt(), (sourceBounds.top * scaleY).roundToInt(),
                        (sourceBounds.right * scaleX).roundToInt(), (sourceBounds.bottom * scaleY).roundToInt())
                    val col = (0..8).firstOrNull {
                        bounds.centerX() >= geometry.xBounds[it] && bounds.centerX() < geometry.xBounds[it + 1]
                    } ?: continue
                    val row = (0..8).firstOrNull {
                        bounds.centerY() >= geometry.yBounds[it] && bounds.centerY() < geometry.yBounds[it + 1]
                    } ?: continue
                    val cell = row to col
                    if (cell !in unread && cell !in ambiguous) continue
                    val cellHeight = geometry.yBounds[row + 1] - geometry.yBounds[row]
                    // Reject notes, grid-line fragments, and elements spanning multiple cells.
                    if (bounds.height() < cellHeight * 0.35f ||
                        bounds.left < geometry.xBounds[col] || bounds.right > geometry.xBounds[col + 1] ||
                        bounds.top < geometry.yBounds[row] || bounds.bottom > geometry.yBounds[row + 1]) continue
                    reads.getOrPut(cell) { linkedSetOf() }.add(digit)
                }
                return reads
            }
            val reads = readBoard(bitmap)
            val scaledReads = if (unread.isNotEmpty() || ambiguous.isNotEmpty()) {
                val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
                try { readBoard(scaled) } finally { scaled.recycle() }
            } else emptyMap()
            Log.i(TAG, "Board-context OCR: original=$reads scaled=$scaledReads")
            // A glyph may be visible to OCR only at the smaller scale. The old
            // code iterated just the original reads and silently lost that evidence.
            val combinedReads = (reads.keys + scaledReads.keys).associateWith { cell ->
                reads[cell].orEmpty() + scaledReads[cell].orEmpty()
            }
            val recovered = combinedReads.filter { (cell, digits) ->
                digits.size == 1 && (cell in unread ||
                    (digits.single() in setOf(1, 7, 6, 9) && reads[cell] == digits && scaledReads[cell] == digits))
            }.mapValues { it.value.single() }
            if (recovered.isEmpty()) return scan
            val values = Array(9) { scan.grid.values[it].clone() }
            val locked = Array(9) { scan.grid.locked[it].clone() }
            recovered.forEach { (cell, digit) ->
                val (row, col) = cell
                val cellBitmap = Bitmap.createBitmap(bitmap, geometry.xBounds[col], geometry.yBounds[row],
                    geometry.xBounds[col + 1] - geometry.xBounds[col],
                    geometry.yBounds[row + 1] - geometry.yBounds[row])
                val styled = try { detectLikelyUserStyledDigit(cellBitmap) != UserStyledDigitKind.NONE }
                    finally { cellBitmap.recycle() }
                values[row][col] = if (styled && treatStyledDigitsAsUserEntries) 0 else digit
                locked[row][col] = !styled
            }
            Log.i(TAG, "Full-board OCR confirmed cells: $recovered")
            scan.copy(grid = SudokuGrid(values, locked),
                uncertainCells = (scan.uncertainCells + recovered.keys).distinct(),
                observedDigits = scan.observedDigits + recovered)
        } catch (error: Exception) {
            Log.w(TAG, "Full-board OCR fallback failed", error)
            scan
        }
    }

    private fun decideHybridCellValue(
        cellBitmap: Bitmap,
        mlRead: MlKitCellRead,
        tesseractRead: TesseractDigitRecognizer.TesseractCellResult?,
        likelyBlueStyled: Boolean
    ): HybridCellDecision {
        if (mlRead.templateMatched) return HybridCellDecision(mlRead.digit, false)
        val mlKitResult = mlRead.digit
        val mlKitUncertain = mlRead.isUncertain
        val mlKitStrongConsensus = mlRead.strongConsensus
        val tesseractResult = tesseractRead?.digit ?: 0
        val tesseractConfidence = tesseractRead?.confidence ?: 0
        val tesseractUncertain = tesseractRead?.isUncertain ?: false

        var finalValue: Int
        var isUncertain: Boolean
        when {
            mlKitResult == tesseractResult && mlKitResult > 0 -> {
                finalValue = mlKitResult
                val is69 = finalValue == 6 || finalValue == 9
                if (is69 &&
                    tesseractConfidence < HYBRID_CONFUSED_DIGIT_AGREE_MIN_CONF &&
                    !mlKitStrongConsensus
                ) {
                    isUncertain = true
                    Log.i(
                        TAG,
                        "  -> AGREE on 6/9 but weak confidence " +
                            "(conf=$tesseractConfidence): keeping $finalValue (uncertain)"
                    )
                } else {
                    isUncertain = isConfusedDigit(finalValue) || (mlKitUncertain && tesseractUncertain)
                    if (is69 && tesseractConfidence < HYBRID_CONFUSED_DIGIT_AGREE_MIN_CONF) {
                        Log.i(
                            TAG,
                            "  -> AGREE on 6/9 with weak Tesseract confidence " +
                                "(conf=$tesseractConfidence) but strong ML consensus: keeping as uncertain"
                        )
                    }
                    Log.i(TAG, "  -> AGREE: $finalValue")
                }
            }
            mlKitResult > 0 && tesseractResult > 0 && mlKitResult != tesseractResult -> {
                val is69 = (mlKitResult == 6 || mlKitResult == 9) && (tesseractResult == 6 || tesseractResult == 9)
                val is17 = (mlKitResult == 1 || mlKitResult == 7) && (tesseractResult == 1 || tesseractResult == 7)
                if (is69) {
                    when {
                        tesseractConfidence >= HYBRID_CONFUSED_DIGIT_STRONG_TESSERACT_CONF -> {
                            finalValue = tesseractResult
                            isUncertain = true
                            Log.i(TAG, "  -> DISAGREE (6/9): using strong Tesseract $finalValue (uncertain)")
                        }
                        tesseractConfidence >= HYBRID_CONFUSED_DIGIT_MIN_CONF -> {
                            finalValue = tesseractResult
                            isUncertain = true
                            Log.i(TAG, "  -> DISAGREE (6/9): using medium-confidence Tesseract $finalValue (uncertain)")
                        }
                        mlKitStrongConsensus -> {
                            finalValue = mlKitResult
                            isUncertain = true
                            Log.i(TAG, "  -> DISAGREE (6/9): using strong ML consensus $finalValue (uncertain)")
                        }
                        else -> {
                            finalValue = mlKitResult
                            isUncertain = true
                            Log.i(TAG, "  -> DISAGREE (6/9): keeping ML Kit $finalValue uncertain (Tesseract=$tesseractResult conf=$tesseractConfidence)")
                        }
                    }
                } else if (is17) {
                    finalValue = when {
                        mlKitStrongConsensus -> mlKitResult
                        tesseractConfidence >= HYBRID_CONFUSED_17_TESSERACT_CONF -> tesseractResult
                        mlRead.voteCount >= 2 -> mlKitResult
                        else -> 0
                    }
                    isUncertain = finalValue != 0
                    if (finalValue == mlKitResult && mlKitStrongConsensus) {
                        Log.i(TAG, "  -> DISAGREE (1/7): using strong ML consensus $finalValue (uncertain)")
                    } else if (finalValue == tesseractResult && tesseractConfidence >= HYBRID_CONFUSED_17_TESSERACT_CONF) {
                        Log.i(TAG, "  -> DISAGREE (1/7): using trusted Tesseract $finalValue (uncertain)")
                    } else if (finalValue != 0) {
                        Log.i(TAG, "  -> DISAGREE (1/7): keeping ML Kit $finalValue uncertain (votes=${mlRead.voteCount})")
                    } else {
                        Log.i(TAG, "  -> DISAGREE (1/7): dropped as empty")
                    }
                } else if (tesseractConfidence >= HYBRID_STRONG_TESSERACT_CONF && !tesseractUncertain) {
                    finalValue = tesseractResult
                    isUncertain = true
                    Log.i(TAG, "  -> DISAGREE: using strong Tesseract $finalValue (uncertain)")
                } else if (mlKitStrongConsensus && mlRead.voteCount >= HYBRID_STRONG_ML_VOTE_MIN) {
                    finalValue = mlKitResult
                    isUncertain = true
                    Log.i(
                        TAG,
                        "  -> DISAGREE: using strong ML consensus $finalValue " +
                            "(votes=${mlRead.voteCount}, uncertain)"
                    )
                } else if (!mlKitUncertain) {
                    finalValue = mlKitResult
                    isUncertain = true
                    Log.i(TAG, "  -> DISAGREE: falling back to ML Kit $finalValue (uncertain)")
                } else {
                    finalValue = 0
                    isUncertain = false
                    Log.i(TAG, "  -> DISAGREE: both uncertain, dropped as empty")
                }
            }
            mlKitResult > 0 -> {
                val mlConfused = isConfusedDigit(mlKitResult)
                val mlConfused17 = mlKitResult == 1 || mlKitResult == 7
                if (mlKitResult == 6 || mlKitResult == 9) {
                    when {
                        mlKitStrongConsensus -> {
                            finalValue = mlKitResult
                            isUncertain = true
                            Log.i(TAG, "  -> ML Kit only 6/9: using strong consensus $finalValue (uncertain)")
                        }
                        mlRead.voteCount >= HYBRID_KEEP_CONFUSED_69_ML_VOTES -> {
                            finalValue = mlKitResult
                            isUncertain = true
                            Log.i(TAG, "  -> ML Kit only 6/9: keeping $finalValue uncertain (voteCount=${mlRead.voteCount})")
                        }
                        else -> {
                            finalValue = 0
                            isUncertain = false
                            Log.i(TAG, "  -> ML Kit only 6/9: dropped as empty (voteCount=${mlRead.voteCount})")
                        }
                    }
                } else if (mlConfused17 && mlRead.voteCount >= HYBRID_KEEP_CONFUSED_17_ML_VOTES) {
                    finalValue = mlKitResult
                    isUncertain = true
                    Log.i(
                        TAG,
                        "  -> ML Kit only 1/7: keeping $finalValue " +
                            "(votes=${mlRead.voteCount}, uncertain)"
                    )
                } else if (mlConfused && !mlKitStrongConsensus) {
                    finalValue = mlKitResult
                    isUncertain = true
                    Log.i(TAG, "  -> ML Kit only ambiguous confused digit: keeping $finalValue (uncertain)")
                } else {
                    finalValue = mlKitResult
                    isUncertain = mlKitUncertain || mlConfused
                    Log.i(TAG, "  -> ML Kit only: using $finalValue (uncertain=$isUncertain)")
                }
            }
            tesseractResult > 0 -> {
                val minRequiredConf = if (tesseractResult == 6 || tesseractResult == 9) {
                    HYBRID_CONFUSED_DIGIT_STRONG_TESSERACT_CONF
                } else if (tesseractResult == 1 || tesseractResult == 7) {
                    HYBRID_SINGLE_ENGINE_CONFUSED_17_TESSERACT_CONF
                } else {
                    HYBRID_SINGLE_ENGINE_TESSERACT_CONF
                }
                if (tesseractConfidence >= minRequiredConf) {
                    finalValue = tesseractResult
                    isUncertain = tesseractUncertain || isConfusedDigit(finalValue)
                    Log.i(
                        TAG,
                        "  -> Tesseract only: using $finalValue " +
                            "(conf=$tesseractConfidence, uncertain=$isUncertain)"
                    )
                } else {
                    finalValue = 0
                    isUncertain = false
                    Log.i(
                        TAG,
                        "  -> Tesseract only low confidence (conf=$tesseractConfidence, min=$minRequiredConf): dropped as empty"
                    )
                }
            }
            else -> {
                finalValue = 0
                isUncertain = false
            }
        }

        if (finalValue == 6 || finalValue == 9) {
            val hasTrustedTesseract69 =
                (tesseractResult == 6 || tesseractResult == 9) &&
                    tesseractConfidence >= HYBRID_CONFUSED_DIGIT_MIN_CONF
            if (hasTrustedTesseract69) {
                finalValue = tesseractResult
            } else {
                val mlAlreadyDisambiguated69 =
                    (mlKitResult == 6 || mlKitResult == 9) && finalValue == mlKitResult
                if (!mlAlreadyDisambiguated69) {
                    finalValue = disambiguate6And9(cellBitmap, finalValue)
                }
            }
        }

        if (finalValue in 1..9 && isConfusedDigit(finalValue) && likelyBlueStyled) {
            Log.i(
                TAG,
                "  -> Confused digit $finalValue appears blue/highlighted: keeping as uncertain"
            )
            isUncertain = true
        }

        return HybridCellDecision(finalValue, isUncertain)
    }

    private fun calculateHybridTesseractPriority(
        mlRead: MlKitCellRead,
        likelyBlueStyled: Boolean
    ): Int {
        var score = 0
        val digit = mlRead.digit

        when (digit) {
            0 -> score += 160
            6, 9 -> score += 120
            1, 7 -> score += 92
            in 1..9 -> score += 24
        }

        if (!mlRead.strongConsensus) {
            score += 36
        }
        if (mlRead.isUncertain) {
            score += 28
        }
        when (mlRead.voteCount) {
            0, 1 -> score += 32
            2 -> score += 16
        }
        if (likelyBlueStyled) {
            score += 18
        }

        return score
    }

    private fun shouldRunTesseractForHybrid(mlRead: MlKitCellRead): Boolean {
        if (mlRead.templateMatched) return false
        if (!HYBRID_SELECTIVE_TESSERACT_ENABLED) return true
        val digit = mlRead.digit
        if (digit !in 1..9) return true

        if (digit == 6 || digit == 9) {
            val strong69MlRead =
                mlRead.strongConsensus && mlRead.voteCount >= HYBRID_SKIP_TESSERACT_69_MIN_ML_VOTES
            return !strong69MlRead
        }

        if (digit == 1 || digit == 7) {
            val strong17MlRead =
                mlRead.strongConsensus && mlRead.voteCount >= HYBRID_SKIP_TESSERACT_17_MIN_ML_VOTES
            return !strong17MlRead
        }

        val strongStableMlRead =
            mlRead.strongConsensus && mlRead.voteCount >= HYBRID_SKIP_TESSERACT_MIN_ML_VOTES
        return !strongStableMlRead
    }

    private fun recoverDroppedHybridClues(
        values: Array<IntArray>,
        locked: Array<BooleanArray>,
        uncertainCells: MutableList<Pair<Int, Int>>,
        droppedCandidates: List<HybridDroppedCandidate>
    ): Int {
        var clueCount = values.sumOf { row -> row.count { it != 0 } }
        if (clueCount >= HYBRID_RECOVERY_MIN_CLUES || droppedCandidates.isEmpty()) {
            return 0
        }
        val targetClues = HYBRID_RECOVERY_MIN_CLUES

        val picks = droppedCandidates.mapNotNull { candidate ->
            chooseHybridRecoveryPick(candidate)
        }.sortedByDescending { it.score }

        var inserted = 0
        for (pick in picks) {
            if (clueCount >= targetClues || inserted >= HYBRID_RECOVERY_MAX_INSERTS) {
                break
            }
            if (values[pick.row][pick.col] != 0) continue
            if (isPlacementConflict(values, pick.row, pick.col, pick.digit)) continue

            values[pick.row][pick.col] = pick.digit
            locked[pick.row][pick.col] = true
            val key = Pair(pick.row, pick.col)
            if (!uncertainCells.contains(key)) {
                uncertainCells.add(key)
            }
            inserted++
            clueCount++
            Log.i(
                TAG,
                "Hybrid recovery: restored (${pick.row},${pick.col})=${pick.digit} " +
                    "(score=${pick.score}, clue $clueCount)"
            )
        }

        return inserted
    }

    private fun chooseHybridRecoveryPick(candidate: HybridDroppedCandidate): HybridRecoveryPick? {
        var bestDigit = 0
        var bestScore = Int.MIN_VALUE

        fun consider(digit: Int, score: Int) {
            if (digit !in 1..9) return
            if (score <= bestScore) return
            bestDigit = digit
            bestScore = score
        }

        if (candidate.tesseractDigit in 1..9) {
            val minRequired = when (candidate.tesseractDigit) {
                6, 9 -> HYBRID_RECOVERY_MIN_CONFUSED_69_TESSERACT_CONF
                1, 7 -> HYBRID_RECOVERY_MIN_CONFUSED_17_TESSERACT_CONF
                else -> HYBRID_RECOVERY_MIN_TESSERACT_CONF
            }
            if (candidate.tesseractConfidence >= minRequired) {
                var score = candidate.tesseractConfidence
                if (!candidate.tesseractUncertain) score += 12
                if (candidate.mlDigit == candidate.tesseractDigit && candidate.mlDigit > 0) score += 8
                consider(candidate.tesseractDigit, score)
            }
        }

        if (candidate.mlStrongConsensus && candidate.mlDigit in 1..9) {
            // ML-only rescue path: lower priority than trusted Tesseract.
            var score = HYBRID_RECOVERY_ML_BASE_SCORE
            if (!candidate.mlUncertain) score += 8
            if (candidate.mlDigit == candidate.tesseractDigit && candidate.tesseractDigit > 0) score += 8
            if (candidate.mlDigit == 6 || candidate.mlDigit == 9) score -= 6
            consider(candidate.mlDigit, score)
        }

        if (bestDigit !in 1..9) return null
        return HybridRecoveryPick(
            row = candidate.row,
            col = candidate.col,
            digit = bestDigit,
            score = bestScore
        )
    }

    private fun isPlacementConflict(values: Array<IntArray>, row: Int, col: Int, digit: Int): Boolean {
        if (digit !in 1..9) return true

        for (c in 0..8) {
            if (c != col && values[row][c] == digit) return true
        }
        for (r in 0..8) {
            if (r != row && values[r][col] == digit) return true
        }

        val boxRow = (row / 3) * 3
        val boxCol = (col / 3) * 3
        for (r in boxRow until boxRow + 3) {
            for (c in boxCol until boxCol + 3) {
                if ((r != row || c != col) && values[r][c] == digit) return true
            }
        }
        return false
    }

    private fun gridToString(values: Array<IntArray>): String = buildString {
        for (row in 0..8) {
            if (row % 3 == 0 && row != 0) appendLine("------+-------+------")
            for (col in 0..8) {
                if (col % 3 == 0 && col != 0) append("| ")
                append(if (values[row][col] == 0) "." else values[row][col])
                append(" ")
            }
            appendLine()
        }
    }

    private data class CellGeometry(
        val xBounds: IntArray,
        val yBounds: IntArray,
        val inset: Int
    )

    private data class MlKitCellRead(
        val digit: Int,
        val isUncertain: Boolean,
        val strongConsensus: Boolean,
        val voteCount: Int,
        val templateMatched: Boolean = false
    )

    private data class HybridDroppedCandidate(
        val row: Int,
        val col: Int,
        val mlDigit: Int,
        val mlUncertain: Boolean,
        val mlStrongConsensus: Boolean,
        val tesseractDigit: Int,
        val tesseractConfidence: Int,
        val tesseractUncertain: Boolean
    )

    private data class HybridRecoveryPick(
        val row: Int,
        val col: Int,
        val digit: Int,
        val score: Int
    )

    private enum class UserStyledDigitKind {
        NONE,
        BLUE,
        RED
    }

    private data class HybridCellWorkItem(
        val row: Int,
        val col: Int,
        val cellBitmap: Bitmap,
        val mlRead: MlKitCellRead,
        val userStyledDigitKind: UserStyledDigitKind,
        val likelyBlueStyled: Boolean,
        val wantsTesseract: Boolean,
        val tesseractPriority: Int,
        var tesseractRead: TesseractDigitRecognizer.TesseractCellResult? = null
    )

    private data class HybridCellDecision(
        val value: Int,
        val isUncertain: Boolean
    )

    private data class HybridMlPassCache(
        val width: Int,
        val height: Int,
        val detectedCells: Array<BooleanArray>,
        val reads: Array<Array<MlKitCellRead?>>,
        val styles: Array<Array<UserStyledDigitKind>>
    )

    private fun buildCellGeometry(width: Int, height: Int): CellGeometry {
        val gridSize = Constants.GRID_SIZE
        val boardSize = minOf(width, height).coerceAtLeast(gridSize)
        val offsetX = (width - boardSize) / 2
        val offsetY = (height - boardSize) / 2
        val maxInset = ((boardSize / gridSize) / 3).coerceAtLeast(0)
        val inset = Constants.BOARD_INNER_CROP_PX.coerceIn(0, maxInset)
        val usableSize = (boardSize - 2 * inset).coerceAtLeast(gridSize)
        val step = usableSize.toDouble() / gridSize.toDouble()

        val xBounds = IntArray(gridSize + 1)
        val yBounds = IntArray(gridSize + 1)

        for (i in 0..gridSize) {
            val x = (offsetX + inset + i * step).roundToInt().coerceIn(0, width)
            val y = (offsetY + inset + i * step).roundToInt().coerceIn(0, height)
            xBounds[i] = x
            yBounds[i] = y
        }

        xBounds[0] = (offsetX + inset).coerceIn(0, (width - 1).coerceAtLeast(0))
        yBounds[0] = (offsetY + inset).coerceIn(0, (height - 1).coerceAtLeast(0))
        xBounds[gridSize] = (offsetX + boardSize - inset).coerceIn((xBounds[0] + 1).coerceAtMost(width), width)
        yBounds[gridSize] = (offsetY + boardSize - inset).coerceIn((yBounds[0] + 1).coerceAtMost(height), height)

        for (i in 0 until gridSize) {
            if (xBounds[i + 1] <= xBounds[i]) {
                xBounds[i + 1] = (xBounds[i] + 1).coerceAtMost(width)
            }
            if (yBounds[i + 1] <= yBounds[i]) {
                yBounds[i + 1] = (yBounds[i] + 1).coerceAtMost(height)
            }
        }

        return CellGeometry(xBounds, yBounds, inset)
    }

    /**
     * Check if a cell contains a digit
     * Ported from cell_has_digit() in a.py (lines 133-135)
     */
    private fun cellHasDigit(cellBitmap: Bitmap, row: Int = -1, col: Int = -1): Boolean {
        val cropped = cropCell(cellBitmap)
        val width = cropped.width
        val height = cropped.height
        val totalPixels = (width * height).coerceAtLeast(1)

        var whitePixels = 0
        var centerWhitePixels = 0
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1

        val centerLeft = (width * DIGIT_CENTER_ZONE_LEFT).toInt().coerceIn(0, width - 1)
        val centerRight = (width * DIGIT_CENTER_ZONE_RIGHT).toInt().coerceIn(centerLeft + 1, width)
        val centerTop = (height * DIGIT_CENTER_ZONE_TOP).toInt().coerceIn(0, height - 1)
        val centerBottom = (height * DIGIT_CENTER_ZONE_BOTTOM).toInt().coerceIn(centerTop + 1, height)

        for (x in 0 until cropped.width) {
            for (y in 0 until cropped.height) {
                val pixel = cropped.getPixel(x, y)
                // Check if pixel is white (threshold > 200)
                if (Color.red(pixel) > 200) {
                    whitePixels++
                    if (x >= centerLeft && x < centerRight && y >= centerTop && y < centerBottom) {
                        centerWhitePixels++
                    }
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val cellTag = if (row >= 0) "($row,$col)" else "?"

        if (whitePixels < Constants.MIN_WHITE_PIXELS || maxX < minX || maxY < minY) {
            Log.d(TAG, "cellHasDigit $cellTag: REJECT whitePx=$whitePixels (min=${Constants.MIN_WHITE_PIXELS}), size=${width}x${height}")
            cropped.recycle()
            return false
        }

        val whiteRatio = whitePixels.toDouble() / totalPixels.toDouble()
        if (whiteRatio < DIGIT_MIN_WHITE_RATIO || whiteRatio > DIGIT_MAX_WHITE_RATIO) {
            Log.d(TAG, "cellHasDigit $cellTag: REJECT whiteRatio=${"%.4f".format(whiteRatio)} (range=$DIGIT_MIN_WHITE_RATIO..$DIGIT_MAX_WHITE_RATIO), whitePx=$whitePixels")
            cropped.recycle()
            return false
        }

        val boxWidth = maxX - minX + 1
        val boxHeight = maxY - minY + 1
        val minBoxW = (width * DIGIT_MIN_BOX_WIDTH_RATIO).toInt().coerceAtLeast(3)
        val minBoxH = (height * DIGIT_MIN_BOX_HEIGHT_RATIO).toInt().coerceAtLeast(8)
        if (boxWidth < minBoxW) {
            Log.d(TAG, "cellHasDigit $cellTag: REJECT boxWidth=$boxWidth (min=$minBoxW), whitePx=$whitePixels")
            cropped.recycle()
            return false
        }
        if (boxHeight < minBoxH) {
            Log.d(TAG, "cellHasDigit $cellTag: REJECT boxHeight=$boxHeight (min=$minBoxH), whitePx=$whitePixels")
            cropped.recycle()
            return false
        }

        val centerX = (minX + maxX) / 2.0
        val centerY = (minY + maxY) / 2.0
        val centerOffsetX = kotlin.math.abs(centerX - (width / 2.0)) / width.toDouble()
        val centerOffsetY = kotlin.math.abs(centerY - (height / 2.0)) / height.toDouble()
        if (centerOffsetX > DIGIT_MAX_CENTER_OFFSET || centerOffsetY > DIGIT_MAX_CENTER_OFFSET) {
            Log.d(TAG, "cellHasDigit $cellTag: REJECT centerOffset=(${
                "%.3f".format(centerOffsetX)},${
                "%.3f".format(centerOffsetY)}) max=$DIGIT_MAX_CENTER_OFFSET, box=${boxWidth}x${boxHeight}, whitePx=$whitePixels")
            cropped.recycle()
            return false
        }

        val centerArea = (centerRight - centerLeft) * (centerBottom - centerTop)
        val centerRatio = if (centerArea > 0) {
            centerWhitePixels.toDouble() / centerArea.toDouble()
        } else {
            0.0
        }
        if (centerRatio < DIGIT_MIN_CENTER_WHITE_RATIO && whiteRatio < DIGIT_MIN_WHITE_RATIO_FOR_EDGE_ONLY) {
            Log.d(TAG, "cellHasDigit $cellTag: REJECT centerRatio=${"%.4f".format(centerRatio)} whiteRatio=${"%.4f".format(whiteRatio)}, whitePx=$whitePixels")
            cropped.recycle()
            return false
        }

        cropped.recycle()
        return true
    }

    /**
     * Crop cell with margin
     * Ported from crop_cell() in a.py (lines 128-131)
     */
    private fun cropCell(cellBitmap: Bitmap): Bitmap {
        val size = cellBitmap.width.coerceAtMost(cellBitmap.height)
        val margin = (size * Constants.CELL_CROP_MARGIN).toInt()

        val width = cellBitmap.width - 2 * margin
        val height = cellBitmap.height - 2 * margin

        if (width <= 0 || height <= 0) {
            // If margin too large, return original
            return cellBitmap.copy(cellBitmap.config, false)
        }

        return Bitmap.createBitmap(
            cellBitmap,
            margin, margin,
            width, height
        )
    }

    /**
     * Heuristic for apps that render player-entered or invalid digits in blue/red.
     * These cells are usually editable user input instead of original puzzle givens.
     */
    private fun detectLikelyUserStyledDigit(cellBitmap: Bitmap): UserStyledDigitKind {
        val cropped = cropCell(cellBitmap)
        val width = cropped.width
        val height = cropped.height
        val pixels = IntArray((width * height).coerceAtLeast(0))
        cropped.getPixels(pixels, 0, width, 0, 0, width, height)
        cropped.recycle()

        return when (OcrDigitStylePolicy.classifyArgbPixels(pixels)) {
            OcrDigitStyle.BLUE -> UserStyledDigitKind.BLUE
            OcrDigitStyle.RED -> UserStyledDigitKind.RED
            OcrDigitStyle.NONE -> UserStyledDigitKind.NONE
        }
    }

    /**
     * Simplified preprocessing optimized for light mode (dark digits on light background).
     * Uses minimal processing to preserve digit shape for better OCR accuracy.
     */
    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        // Convert to OpenCV Mat
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Convert to grayscale
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

        // Simple Gaussian blur to reduce noise
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)

        // Use Otsu threshold - works well for light mode with good contrast
        val binary = Mat()
        Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

        // Denoise: morphological opening to remove isolated noise pixels
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        val denoised = Mat()
        Imgproc.morphologyEx(binary, denoised, Imgproc.MORPH_OPEN, kernel)
        binary.release()
        kernel.release()

        // Check if we need to invert - for ML Kit, we want dark text on light background
        val nonZeroCount = Core.countNonZero(denoised)
        val totalPixels = denoised.rows() * denoised.cols()

        // If more black than white, the digit is white - need to invert for standard OCR
        val normalized = if (nonZeroCount < totalPixels / 2) {
            // More black pixels = digit is probably white on black, invert it
            val inverted = Mat()
            Core.bitwise_not(denoised, inverted)
            denoised.release()
            inverted
        } else {
            // More white pixels = background is white, digit is black - this is correct
            denoised
        }

        // Add white padding around the cell for better ML Kit accuracy
        val padding = 20
        val padded = Mat()
        Core.copyMakeBorder(
            normalized, padded, padding, padding, padding, padding,
            Core.BORDER_CONSTANT, Scalar(255.0)
        )

        // Convert back to Bitmap
        val result = Bitmap.createBitmap(padded.cols(), padded.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(padded, result)

        // Cleanup
        mat.release()
        gray.release()
        blurred.release()
        normalized.release()
        padded.release()

        return result
    }

    /**
     * Alternate preprocessing for difficult cells (uneven contrast/background).
     */
    private fun enhanceContrastAdaptive(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

        val equalized = Mat()
        Imgproc.equalizeHist(gray, equalized)

        val adaptive = Mat()
        Imgproc.adaptiveThreshold(
            equalized,
            adaptive,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            31,
            4.0
        )

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        val cleaned = Mat()
        Imgproc.morphologyEx(adaptive, cleaned, Imgproc.MORPH_OPEN, kernel)
        adaptive.release()
        kernel.release()

        val nonZeroCount = Core.countNonZero(cleaned)
        val totalPixels = cleaned.rows() * cleaned.cols()
        val normalized = if (nonZeroCount < totalPixels / 2) {
            val inverted = Mat()
            Core.bitwise_not(cleaned, inverted)
            cleaned.release()
            inverted
        } else {
            cleaned
        }

        val padded = Mat()
        Core.copyMakeBorder(
            normalized, padded, 20, 20, 20, 20,
            Core.BORDER_CONSTANT, Scalar(255.0)
        )

        val result = Bitmap.createBitmap(padded.cols(), padded.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(padded, result)

        mat.release()
        gray.release()
        equalized.release()
        normalized.release()
        padded.release()

        return result
    }

    /**
     * Convert common OCR misreads to actual digits
     * Only handles very safe replacements to avoid introducing errors
     */
    private fun normalizeOcrText(text: String): String {
        return text
            .replace('O', '0').replace('o', '0')  // O looks like 0
    }

    private fun parseSingleDigitCandidate(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        val digits = trimmed.filter { char -> char.isDigit() }
        if (digits.isEmpty()) return 0
        val uniqueDigits = digits.toSet()
        if (uniqueDigits.size != 1) return 0

        // Allow common OCR border/markup noise around a single digit (e.g. "|4", "/7", "(3)").
        // Reject letter-containing tokens to avoid promoting random text fragments.
        val nonDigitNoise = trimmed.filter { !it.isDigit() && !it.isWhitespace() }
        if (nonDigitNoise.any { it.isLetter() }) return 0
        if (nonDigitNoise.any { it !in ALLOWED_OCR_NOISE_CHARS }) return 0

        val digit = digits.first().digitToInt()
        return if (digit in 1..9) digit else 0
    }

    /**
     * Perform OCR on a single cell to extract the digit
     * Uses ML Kit Text Recognition (replaces Tesseract from Python)
     */
    private suspend fun ocrCell(cellBitmap: Bitmap): Int = withContext(Dispatchers.IO) {
        ocrCellWithUncertainty(cellBitmap).digit
    }

    /**
     * Perform OCR and return (digit, isUncertain) where isUncertain=true for 6 or 9
     */
    private suspend fun ocrCellWithUncertainty(cellBitmap: Bitmap): MlKitCellRead = withContext(Dispatchers.IO) {
        try {
            val template = if (useScreenDigitTemplates) screenDigitMatcher.match(cellBitmap) else null
            if (template != null) {
                Log.i(TAG, "Verified-font match: digit=${template.digit}, distance=${template.distance}, margin=${template.margin}")
                return@withContext MlKitCellRead(template.digit, false, false, 0, templateMatched = true)
            }
            val cropped = cropCell(cellBitmap)
            val enhanced = enhanceContrast(cropped)
            val reads = mutableListOf<Pair<Int, Boolean>>()

            suspend fun runMlKitPass(prepared: Bitmap, label: String) {
                val scaledWidth = (prepared.width * ML_KIT_SCALE_FACTOR).toInt()
                val scaledHeight = (prepared.height * ML_KIT_SCALE_FACTOR).toInt()
                val scaled = Bitmap.createScaledBitmap(prepared, scaledWidth, scaledHeight, true)

                val normal = performOcrOnBitmapWithUncertainty(scaled)
                reads.add(normal)
                Log.i(TAG, "ML Kit $label/raw -> ${normal.first} (uncertain=${normal.second})")

                val shouldRunInverted =
                    normal.first !in 1..9 || normal.second || isConfusedDigit(normal.first)
                if (shouldRunInverted) {
                    val inverted = invertBitmap(scaled)
                    val invertedRead = performOcrOnBitmapWithUncertainty(inverted)
                    reads.add(invertedRead)
                    Log.i(TAG, "ML Kit $label/inverted -> ${invertedRead.first} (uncertain=${invertedRead.second})")
                    inverted.recycle()
                } else {
                    Log.i(TAG, "ML Kit $label/inverted skipped (confident raw ${normal.first})")
                }
                scaled.recycle()
            }

            runMlKitPass(enhanced, "base")

            val baseCounts = reads
                .map { it.first }
                .filter { it in 1..9 }
                .groupingBy { it }
                .eachCount()
            val baseWinning = baseCounts.maxByOrNull { it.value }
            val baseWinningDigit = baseWinning?.key ?: 0
            val baseWinningVotes = baseWinning?.value ?: 0

            // Run adaptive only when base pass is unstable.
            // Stable non-confused single-vote base reads can also skip adaptive.
            val needsFallback = when {
                baseCounts.isEmpty() -> true
                baseCounts.size > 1 -> true
                baseWinningDigit in 1..9 && !isConfusedDigit(baseWinningDigit) && baseWinningVotes >= 1 -> false
                else -> baseWinningVotes < 2
            }
            if (needsFallback) {
                val adaptive = enhanceContrastAdaptive(cropped)
                runMlKitPass(adaptive, "adaptive")
                adaptive.recycle()
            }

            // Use the margin-stripped cell for disambiguation so grid-line edges
            // don't skew the 6/9 mass or hole analysis.
            val result = chooseBestMlKitVote(reads, cropped)

            cropped.recycle()
            enhanced.recycle()

            result
        } catch (e: Exception) {
            Log.e(TAG, "OCR error", e)
            MlKitCellRead(0, false, false, 0)
        }
    }

    private fun chooseBestMlKitVote(
        reads: List<Pair<Int, Boolean>>,
        originalBitmap: Bitmap
    ): MlKitCellRead {
        val valid = reads.filter { it.first in 1..9 }
        if (valid.isEmpty()) return MlKitCellRead(0, false, false, 0)

        val counts = valid.groupingBy { it.first }.eachCount()
        val bestCount = counts.values.maxOrNull() ?: return MlKitCellRead(0, false, false, 0)
        val topDigits = counts.filterValues { it == bestCount }.keys.toList()
        var structurallyResolvedTie = false

        val originalChosen = if (topDigits.size == 1) {
            topDigits.first()
        } else if (topDigits.toSet() == setOf(6, 9)) {
            // ML Kit frequently splits its four passes evenly between 6 and 9.
            // The old behavior discarded a clearly occupied given as empty before
            // the existing hole/mass analysis could inspect it. Resolve only when
            // that image structure has an opinion; otherwise retain the safe drop.
            val structuralDigit = disambiguate6And9(originalBitmap, 0)
            if (structuralDigit == 6 || structuralDigit == 9) {
                structurallyResolvedTie = true
                Log.i(TAG, "ML Kit 6/9 vote tie $counts -> structurally resolved as $structuralDigit")
                structuralDigit
            } else {
                Log.i(TAG, "ML Kit 6/9 vote tie $counts -> structurally unresolved; dropped as empty")
                return MlKitCellRead(0, false, false, 0)
            }
        } else {
            Log.i(TAG, "ML Kit vote tie $counts -> dropped as empty")
            return MlKitCellRead(0, false, false, 0)
        }

        var chosen = originalChosen
        if ((chosen == 6 || chosen == 9) && !structurallyResolvedTie) {
            chosen = disambiguate6And9(originalBitmap, chosen)
        }

        // Count votes against the ORIGINAL digit (before disambiguation flip).
        // If disambiguation changed 6→9, all votes were for 6 (voteCount would be 0 for 9),
        // causing the cell to be incorrectly dropped in the hybrid merge.
        val disagreement = counts.size > 1
        val agreeingVotes = if (structurallyResolvedTie) {
            valid.size
        } else {
            valid.count { it.first == originalChosen }
        }
        val uncertainSignals = valid.any { it.first == originalChosen && it.second }
        // Strong consensus only applies when disambiguation did NOT flip the digit.
        // A flipped cell is uncertain by definition and must not skip Tesseract.
        val strongConsensus = counts.size == 1 && agreeingVotes >= 2 && chosen == originalChosen
        val isUncertain = when {
            !isConfusedDigit(chosen) && counts.size == 1 -> false
            strongConsensus && !isConfusedDigit(chosen) -> false
            else -> disagreement || agreeingVotes < 2 || uncertainSignals || isConfusedDigit(chosen)
        }

        return MlKitCellRead(chosen, isUncertain, strongConsensus, agreeingVotes)
    }

    private fun isConfusedDigit(digit: Int): Boolean {
        return digit == 6 || digit == 9 || digit == 1 || digit == 7
    }

    /**
     * Invert bitmap colors for alternate OCR attempt
     */
    private fun invertBitmap(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        Core.bitwise_not(mat, mat)
        val result = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, result)
        mat.release()
        return result
    }

    /**
     * Perform OCR on a prepared bitmap and return detected digit
     */
    private suspend fun performOcrOnBitmap(bitmap: Bitmap): Int {
        return performOcrOnBitmapWithUncertainty(bitmap).first
    }

    /**
     * Perform OCR and return (digit, isUncertain)
     * isUncertain is true for commonly confused digits: 6/9 and 1/7
     */
    private suspend fun performOcrOnBitmapWithUncertainty(bitmap: Bitmap): Pair<Int, Boolean> {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val result = textRecognizer.process(inputImage).await()

        val recognizedText = result.text.trim()
        Log.i(TAG, "OCR raw: '$recognizedText'")

        // Helper to check if digit is commonly confused
        fun isConfused(d: Int) = d == 6 || d == 9 || d == 1 || d == 7

        // Parse full-text candidate; ignore mixed strings like "13" or "|7".
        val fullTextDigit = parseSingleDigitCandidate(normalizeOcrText(recognizedText))
        if (fullTextDigit in 1..9) {
            return Pair(fullTextDigit, isConfused(fullTextDigit))
        }

        // Also check individual text blocks for better accuracy
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val candidate = parseSingleDigitCandidate(normalizeOcrText(line.text))
                if (candidate in 1..9) {
                    return Pair(candidate, isConfused(candidate))
                }
            }
        }

        return Pair(0, false) // No digit found
    }

    /**
     * Disambiguate between 6 and 9 using hole detection + pixel mass analysis.
     * 1. Hole position: if hole center is clearly in top or bottom zone, decide directly.
     * 2. Pixel mass: compare ink density in top half vs bottom half of the digit.
     *    A "6" has more mass at the bottom (circle), a "9" has more at the top.
     * 3. Only trust raw OCR if both methods are inconclusive.
     */
    private fun disambiguate6And9(bitmap: Bitmap, ocrResult: Int): Int {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Convert to grayscale
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

        // Threshold
        val binary = Mat()
        Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

        // Check if we need to invert (digit should be white for contour detection)
        val nonZero = Core.countNonZero(binary)
        val totalPixels = binary.rows() * binary.cols()
        val minHoleArea = totalPixels * MIN_HOLE_AREA_RATIO
        if (nonZero > totalPixels / 2) {
            Core.bitwise_not(binary, binary)
        }

        val height = binary.rows()

        // --- Phase 1: Hole detection + digit-body bounding rect ---
        val contours = mutableListOf<org.opencv.core.MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary.clone(), contours, hierarchy, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE)

        var holeY = -1
        var holeArea = 0.0
        var digitBodyRect: org.opencv.core.Rect? = null
        var digitBodyArea = 0.0

        if (hierarchy.cols() > 0) {
            for (i in contours.indices) {
                val h = hierarchy.get(0, i)
                if (h != null && h.size >= 4) {
                    val parent = h[3].toInt()
                    if (parent >= 0) {
                        // Inner contour = hole (circle of 6 or 9)
                        val rect = Imgproc.boundingRect(contours[i])
                        if (rect.area() > holeArea && rect.area() > minHoleArea) {
                            holeY = rect.y + rect.height / 2
                            holeArea = rect.area()
                        }
                    } else {
                        // Outer contour = digit body; track the largest one
                        val area = Imgproc.contourArea(contours[i])
                        if (area > digitBodyArea) {
                            digitBodyArea = area
                            digitBodyRect = Imgproc.boundingRect(contours[i])
                        }
                    }
                }
            }
        }

        hierarchy.release()
        contours.forEach { it.release() }

        // Wider hole zones - decide directly when hole is clearly top or bottom
        if (holeY >= 0 && holeArea >= minHoleArea) {
            val topCutoff = (height * HOLE_TOP_ZONE_RATIO).toInt()
            val bottomCutoff = (height * HOLE_BOTTOM_ZONE_RATIO).toInt()
            val marginPx = (height * HOLE_ZONE_MARGIN_RATIO).roundToInt().coerceAtLeast(2)
            if (holeY <= topCutoff - marginPx) {
                Log.i(TAG, "6/9: hole at Y=$holeY (top zone ≤$topCutoff) -> 9 (OCR=$ocrResult)")
                mat.release(); gray.release(); binary.release()
                return 9
            } else if (holeY >= bottomCutoff + marginPx) {
                Log.i(TAG, "6/9: hole at Y=$holeY (bottom zone ≥$bottomCutoff) -> 6 (OCR=$ocrResult)")
                mat.release(); gray.release(); binary.release()
                return 6
            }
        }

        // --- Phase 2: Pixel mass analysis ---
        // Compare white pixel density in top half vs bottom half.
        // Focus on the digit body bounding rect when available so that surrounding
        // cell noise and grid-line remnants don't skew the mass distribution.
        // Using halves (not thirds) because the loop of a 6/9 often straddles
        // the top-third/middle-third boundary, making thirds unreliable.
        // For "9": circle at top → more mass in top half
        // For "6": circle at bottom → more mass in bottom half
        val focusedMat: Mat? = if (digitBodyRect != null &&
            digitBodyRect.width > 2 && digitBodyRect.height > 4
        ) {
            Mat(binary, digitBodyRect).clone()
        } else {
            null
        }
        val analysisMat = focusedMat ?: binary
        val analysisHeight = analysisMat.rows().coerceAtLeast(1)
        val analysisWidth = analysisMat.cols()
        val halfH = analysisHeight / 2
        val topHalf = analysisMat.submat(0, halfH, 0, analysisWidth)
        val bottomHalf = analysisMat.submat(halfH, analysisHeight, 0, analysisWidth)

        val topMass = Core.countNonZero(topHalf).toDouble()
        val bottomMass = Core.countNonZero(bottomHalf).toDouble()
        topHalf.release()
        bottomHalf.release()
        focusedMat?.release()

        val totalMass = topMass + bottomMass
        var massResult = 0
        if (totalMass > 0) {
            val topRatio = topMass / totalMass
            // topRatio > 0.5 means more mass at top → 9
            // topRatio < 0.5 means more mass at bottom → 6
            massResult = SixNineMassPolicy.classify(topRatio)
            Log.i(TAG, "6/9: mass analysis topRatio=%.3f (top=%.0f, bottom=%.0f) -> %s".format(
                topRatio, topMass, bottomMass,
                if (massResult != 0) massResult.toString() else "inconclusive"
            ))
        }

        // Cleanup
        mat.release()
        gray.release()
        binary.release()

        // --- Decision ---
        // If hole detection was in the middle zone but mass analysis has an opinion, use mass
        if (massResult != 0) {
            if (holeY >= 0 && holeArea >= minHoleArea) {
                // Both hole (mid-zone) and mass agree? High confidence.
                val holeHint = if (holeY < height / 2) 9 else 6
                if (holeHint == massResult) {
                    Log.i(TAG, "6/9: hole hint ($holeHint) + mass agree -> $massResult (OCR=$ocrResult)")
                    return massResult
                }
            }
            // Mass analysis alone
            Log.i(TAG, "6/9: mass analysis -> $massResult (OCR=$ocrResult)")
            return massResult
        }

        // Mid-zone holes can be unstable on highlighted/stylized boards.
        // If mass is inconclusive, keep OCR result instead of forcing a flip.
        if (holeY >= 0 && holeArea >= minHoleArea) {
            Log.i(TAG, "6/9: mid-zone hole at Y=$holeY with inconclusive mass -> keeping OCR = $ocrResult")
            return ocrResult
        }

        // Neither method conclusive - trust OCR
        Log.i(TAG, "6/9: no hole, mass inconclusive -> trusting OCR = $ocrResult")
        return ocrResult
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        mlPassCache.clear()
        try {
            textRecognizer.close()
        } finally {
            if (tesseractRecognizerDelegate.isInitialized()) {
                tesseractRecognizerDelegate.value.release()
            }
        }
    }

    companion object {
        private const val TAG = "DigitRecognizer"
        private const val HYBRID_CONFUSED_DIGIT_MIN_CONF = 70
        private const val HYBRID_CONFUSED_DIGIT_AGREE_MIN_CONF = 60
        private const val HYBRID_CONFUSED_DIGIT_STRONG_TESSERACT_CONF = 72
        private const val HYBRID_CONFUSED_17_TESSERACT_CONF = 76
        private const val HYBRID_SINGLE_ENGINE_CONFUSED_17_TESSERACT_CONF = 64
        private const val HYBRID_TRUSTED_TESSERACT_CONF = 70
        private const val HYBRID_STRONG_TESSERACT_CONF = 85
        private const val HYBRID_SINGLE_ENGINE_TESSERACT_CONF = 60
        private const val HYBRID_SELECTIVE_TESSERACT_ENABLED = true
        private const val HYBRID_SKIP_TESSERACT_MIN_ML_VOTES = 2
        private const val HYBRID_SKIP_TESSERACT_17_MIN_ML_VOTES = 3
        private const val HYBRID_SKIP_TESSERACT_69_MIN_ML_VOTES = 4
        // Preserve the resolution at which ML Kit was reliable on the target device.
        // The 3.5x experiment produced empty/conflicting reads without reducing latency.
        private const val ML_KIT_SCALE_FACTOR = 4.5
        // Keep the fallback bounded, but allow enough cells for sparse/extreme fonts.
        private const val HYBRID_TESSERACT_MAX_RUNS = 24
        private const val HYBRID_TESSERACT_TIME_BUDGET_MS = 10_000L
        private const val HYBRID_RECOVERY_MIN_CLUES = 17
        private const val HYBRID_RECOVERY_MAX_INSERTS = 14
        private const val HYBRID_RECOVERY_MIN_TESSERACT_CONF = 68
        private const val HYBRID_RECOVERY_MIN_CONFUSED_17_TESSERACT_CONF = 78
        private const val HYBRID_RECOVERY_MIN_CONFUSED_69_TESSERACT_CONF = 70
        private const val HYBRID_RECOVERY_ML_BASE_SCORE = 62
        private const val DIGIT_MIN_WHITE_RATIO = 0.003
        private const val DIGIT_MAX_WHITE_RATIO = 0.33
        private const val DIGIT_MIN_BOX_WIDTH_RATIO = 0.07
        private const val DIGIT_MIN_BOX_HEIGHT_RATIO = 0.20
        private const val DIGIT_MAX_CENTER_OFFSET = 0.38
        private const val DIGIT_CENTER_ZONE_LEFT = 0.18
        private const val DIGIT_CENTER_ZONE_RIGHT = 0.82
        private const val DIGIT_CENTER_ZONE_TOP = 0.18
        private const val DIGIT_CENTER_ZONE_BOTTOM = 0.82
        private const val DIGIT_MIN_CENTER_WHITE_RATIO = 0.002
        private const val DIGIT_MIN_WHITE_RATIO_FOR_EDGE_ONLY = 0.015
        private const val HOLE_TOP_ZONE_RATIO = 0.45
        private const val HOLE_BOTTOM_ZONE_RATIO = 0.62
        private const val HOLE_ZONE_MARGIN_RATIO = 0.02
        private const val MIN_HOLE_AREA_RATIO = 0.008
        private const val HYBRID_STRONG_ML_VOTE_MIN = 3
        private const val HYBRID_KEEP_CONFUSED_17_ML_VOTES = 2
        private const val HYBRID_KEEP_CONFUSED_69_ML_VOTES = 2
        private val ALLOWED_OCR_NOISE_CHARS = setOf(
            '|', '/', '\\', '_', '-', '.', ',', ':', ';', '\'', '"', '`',
            '(', ')', '[', ']', '{', '}', '!'
        )
    }
}
