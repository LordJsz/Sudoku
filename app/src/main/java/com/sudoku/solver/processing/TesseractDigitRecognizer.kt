package com.sudoku.solver.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import com.sudoku.solver.model.OcrResult
import com.sudoku.solver.model.SudokuGrid
import com.sudoku.solver.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Recognizes digits in Sudoku cells using Tesseract OCR
 * Alternative to ML Kit for better 6/9 disambiguation
 */
class TesseractDigitRecognizer(private val context: Context) {

    private var tessApi: TessBaseAPI? = null
    private val boardDetector = BoardDetector()
    private var isInitialized = false
    private var debugDumpCounter = 0

    data class TesseractCellResult(
        val digit: Int,
        val confidence: Int,
        val isUncertain: Boolean
    )

    init {
        initTesseract()
    }

    private fun initTesseract() {
        try {
            // Copy trained data from assets to app files directory
            val tessDataPath = File(context.filesDir, "tessdata")
            if (!tessDataPath.exists()) {
                tessDataPath.mkdirs()
            }

            val trainedDataFile = File(tessDataPath, "eng.traineddata")
            if (!trainedDataFile.exists()) {
                Log.i(TAG, "Copying Tesseract trained data...")
                context.assets.open("tessdata/eng.traineddata").use { input ->
                    FileOutputStream(trainedDataFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Tesseract trained data copied successfully")
            }

            // Initialize Tesseract
            tessApi = TessBaseAPI()
            val success = tessApi?.init(context.filesDir.absolutePath, "eng") ?: false

            if (success) {
                // Configure for single digit recognition (1-9 only, no zero in Sudoku)
                tessApi?.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "123456789")
                val numericModeEnabled = tessApi?.setVariable("classify_bln_numeric_mode", "1") ?: false
                tessApi?.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_CHAR
                isInitialized = true
                Log.i(TAG, "Tesseract initialized successfully (numericMode=$numericModeEnabled)")
            } else {
                Log.e(TAG, "Failed to initialize Tesseract")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Tesseract", e)
        }
    }

    /**
     * Extract the 9x9 grid from a warped board image
     */
    suspend fun extractGrid(warpedBitmap: Bitmap): SudokuGrid = withContext(Dispatchers.Default) {
        extractGridWithUncertainty(warpedBitmap).grid
    }

    /**
     * Extract grid with uncertainty information
     */
    suspend fun extractGridWithUncertainty(warpedBitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        val preprocessed = boardDetector.preprocessForCells(warpedBitmap)
        val geometry = buildCellGeometry(warpedBitmap.width, warpedBitmap.height)
        Log.i(
            TAG,
            "Tesseract cell geometry: inset=${geometry.inset}px, " +
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

                val binaryCellBitmap = Bitmap.createBitmap(
                    preprocessed, x1, y1, cellWidth, cellHeight
                )

                val hasDigit = cellHasDigit(binaryCellBitmap)

                if (hasDigit) {
                    val cell = Pair(row, col)
                    occupiedCells.add(cell)
                    val cellBitmap = Bitmap.createBitmap(
                        warpedBitmap, x1, y1, cellWidth, cellHeight
                    )
                    val tesseractResult = ocrCellDetailed(cellBitmap)
                    val digit = tesseractResult.digit
                    val isUncertain = tesseractResult.isUncertain
                    if (digit in 1..9) observedDigits[cell] = digit
                    values[row][col] = digit
                    locked[row][col] = digit > 0
                    if (isUncertain && digit > 0) {
                        uncertainCells.add(Pair(row, col))
                        Log.i(
                            TAG,
                            "Cell ($row,$col): $digit (UNCERTAIN, conf=${tesseractResult.confidence})"
                        )
                    } else {
                        Log.i(TAG, "Cell ($row,$col): $digit (conf=${tesseractResult.confidence})")
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
        Log.i(TAG, "Extracted grid (Tesseract):\n${gridToString(values)}")
        OcrResult(
            grid = SudokuGrid(values, locked),
            uncertainCells = uncertainCells,
            occupiedCells = occupiedCells,
            observedDigits = observedDigits
        )
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

    private fun cellHasDigit(cellBitmap: Bitmap): Boolean {
        val cropped = cropCell(cellBitmap)
        try {
            var whitePixels = 0
            var minX = cropped.width; var maxX = -1
            var minY = cropped.height; var maxY = -1
            for (x in 0 until cropped.width) {
                for (y in 0 until cropped.height) {
                    val pixel = cropped.getPixel(x, y)
                    if (Color.red(pixel) > 200) {
                        whitePixels++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }

            val totalPixels = (cropped.width * cropped.height).coerceAtLeast(1)
            val whiteRatio = whitePixels.toDouble() / totalPixels.toDouble()
            val lowGate =
                (Constants.MIN_WHITE_PIXELS * TESSERACT_DIGIT_GATE_LOW_MULTIPLIER)
                    .roundToInt()
                    .coerceAtLeast(1)
            val strongGate =
                (Constants.MIN_WHITE_PIXELS * TESSERACT_DIGIT_GATE_STRONG_MULTIPLIER)
                    .roundToInt()
                    .coerceAtLeast(lowGate + 1)

            if (whitePixels < lowGate || whiteRatio < TESSERACT_DIGIT_GATE_MIN_WHITE_RATIO) {
                return false
            }
            if (whiteRatio > TESSERACT_DIGIT_GATE_MAX_WHITE_RATIO) {
                return false
            }

            // Bounding-box and center-offset checks reject narrow grid-line phantoms
            // and off-center artifacts that a pixel-count gate alone would pass.
            if (maxX > minX && maxY > minY) {
                val boxWidth = maxX - minX + 1
                val boxHeight = maxY - minY + 1
                val minBoxW = (cropped.width * TESSERACT_DIGIT_GATE_MIN_BOX_WIDTH_RATIO)
                    .toInt().coerceAtLeast(2)
                val minBoxH = (cropped.height * TESSERACT_DIGIT_GATE_MIN_BOX_HEIGHT_RATIO)
                    .toInt().coerceAtLeast(4)
                if (boxWidth < minBoxW || boxHeight < minBoxH) {
                    return false
                }
                val centerX = (minX + maxX) / 2.0
                val centerY = (minY + maxY) / 2.0
                val centerOffsetX = abs(centerX - (cropped.width / 2.0)) / cropped.width.toDouble()
                val centerOffsetY = abs(centerY - (cropped.height / 2.0)) / cropped.height.toDouble()
                if (centerOffsetX > TESSERACT_DIGIT_GATE_MAX_CENTER_OFFSET ||
                    centerOffsetY > TESSERACT_DIGIT_GATE_MAX_CENTER_OFFSET
                ) {
                    return false
                }
                if (whitePixels >= strongGate && whiteRatio >= TESSERACT_DIGIT_GATE_STRONG_MIN_WHITE_RATIO) {
                    return true
                }
            } else if (whitePixels >= strongGate && whiteRatio >= TESSERACT_DIGIT_GATE_STRONG_MIN_WHITE_RATIO) {
                return true
            }

            return contourHasDigitLikeShape(cropped)
        } finally {
            cropped.recycle()
        }
    }

    private fun contourHasDigitLikeShape(cellBitmap: Bitmap): Boolean {
        val mat = Mat()
        val gray = Mat()
        val binary = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()

        try {
            Utils.bitmapToMat(cellBitmap, mat)
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

            val totalPixels = (binary.rows() * binary.cols()).coerceAtLeast(1)
            if (Core.countNonZero(binary) > totalPixels / 2) {
                Core.bitwise_not(binary, binary)
            }

            val contourInput = binary.clone()
            Imgproc.findContours(contourInput, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            contourInput.release()

            val width = binary.cols().toDouble().coerceAtLeast(1.0)
            val height = binary.rows().toDouble().coerceAtLeast(1.0)
            val minArea = totalPixels * TESSERACT_CELL_CONTOUR_MIN_AREA_RATIO
            val maxArea = totalPixels * TESSERACT_CELL_CONTOUR_MAX_AREA_RATIO

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < minArea || area > maxArea) continue

                val rect = Imgproc.boundingRect(contour)
                if (rect.width <= 0 || rect.height <= 0) continue

                val widthRatio = rect.width.toDouble() / width
                val heightRatio = rect.height.toDouble() / height
                if (widthRatio < TESSERACT_CELL_CONTOUR_MIN_WIDTH_RATIO ||
                    heightRatio < TESSERACT_CELL_CONTOUR_MIN_HEIGHT_RATIO
                ) {
                    continue
                }

                val aspect = rect.width.toDouble() / rect.height.toDouble()
                if (aspect < TESSERACT_CELL_CONTOUR_MIN_ASPECT || aspect > TESSERACT_CELL_CONTOUR_MAX_ASPECT) {
                    continue
                }

                val centerX = (rect.x + rect.width / 2.0) / width
                val centerY = (rect.y + rect.height / 2.0) / height
                if (centerX < TESSERACT_CELL_CENTER_ZONE_LEFT || centerX > TESSERACT_CELL_CENTER_ZONE_RIGHT) {
                    continue
                }
                if (centerY < TESSERACT_CELL_CENTER_ZONE_TOP || centerY > TESSERACT_CELL_CENTER_ZONE_BOTTOM) {
                    continue
                }

                return true
            }
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Contour fallback for cell gate failed; defaulting to true", e)
            return true
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
            binary.release()
            gray.release()
            mat.release()
        }
    }

    private fun cropCell(cellBitmap: Bitmap): Bitmap {
        val size = cellBitmap.width.coerceAtMost(cellBitmap.height)
        val margin = (size * Constants.CELL_CROP_MARGIN).toInt()

        val width = cellBitmap.width - 2 * margin
        val height = cellBitmap.height - 2 * margin

        if (width <= 0 || height <= 0) {
            return cellBitmap.copy(cellBitmap.config, false)
        }

        return Bitmap.createBitmap(
            cellBitmap,
            margin, margin,
            width, height
        )
    }

    private fun maybeDilateThinStrokes(binaryBlackOnWhite: Mat): Mat {
        val inkMask = Mat()
        Imgproc.threshold(binaryBlackOnWhite, inkMask, 127.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val totalPixels = (inkMask.rows() * inkMask.cols()).coerceAtLeast(1)
        val inkRatio = Core.countNonZero(inkMask).toDouble() / totalPixels.toDouble()

        if (inkRatio >= TESSERACT_DILATION_INK_RATIO_THRESHOLD) {
            inkMask.release()
            return binaryBlackOnWhite.clone()
        }

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        val dilatedMask = Mat()
        Imgproc.dilate(inkMask, dilatedMask, kernel)
        val result = Mat()
        Core.bitwise_not(dilatedMask, result)

        Log.i(TAG, "Applied thin-stroke dilation (inkRatio=$inkRatio)")

        inkMask.release()
        kernel.release()
        dilatedMask.release()
        return result
    }

    private fun clearMaskBorder(mask: Mat, borderPx: Int) {
        val maxBorder = minOf(mask.rows(), mask.cols()) / 2
        val b = borderPx.coerceAtMost(maxBorder)
        if (b <= 0) return

        mask.rowRange(0, b).setTo(Scalar(0.0))
        mask.rowRange(mask.rows() - b, mask.rows()).setTo(Scalar(0.0))
        mask.colRange(0, b).setTo(Scalar(0.0))
        mask.colRange(mask.cols() - b, mask.cols()).setTo(Scalar(0.0))
    }

    private fun selectDigitComponentRect(inkMask: Mat): Rect? {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        val contourInput = inkMask.clone()
        Imgproc.findContours(contourInput, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        contourInput.release()

        val width = inkMask.cols().coerceAtLeast(1)
        val height = inkMask.rows().coerceAtLeast(1)
        val canvasArea = (width * height).toDouble()
        val minArea = canvasArea * TESSERACT_COMPONENT_MIN_AREA_RATIO
        val minHeight = (height * TESSERACT_MIN_COMPONENT_HEIGHT_RATIO).roundToInt().coerceAtLeast(1)

        var bestRect: Rect? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var fallbackRect: Rect? = null
        var fallbackArea = 0.0

        val centerX = (width - 1) / 2.0
        val centerY = (height - 1) / 2.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            val rect = Imgproc.boundingRect(contour)
            val isFallbackCandidate =
                area > fallbackArea &&
                    rect.height >= (height * TESSERACT_FALLBACK_MIN_HEIGHT_RATIO).roundToInt().coerceAtLeast(1)
            if (isFallbackCandidate) {
                fallbackArea = area
                fallbackRect = rect
            }

            if (area < minArea || rect.height < minHeight) continue

            val touchesBorder =
                rect.x <= TESSERACT_COMPONENT_BORDER_MARGIN_PX ||
                    rect.y <= TESSERACT_COMPONENT_BORDER_MARGIN_PX ||
                    rect.x + rect.width >= width - TESSERACT_COMPONENT_BORDER_MARGIN_PX ||
                    rect.y + rect.height >= height - TESSERACT_COMPONENT_BORDER_MARGIN_PX

            val rectCenterX = rect.x + rect.width / 2.0
            val rectCenterY = rect.y + rect.height / 2.0
            val dx = abs(rectCenterX - centerX) / width.toDouble()
            val dy = abs(rectCenterY - centerY) / height.toDouble()
            val centerDistance = sqrt(dx * dx + dy * dy).coerceIn(0.0, 1.0)
            val centerScore = 1.0 - centerDistance
            val sizeScore = (area / canvasArea).coerceIn(0.0, 1.0)
            val heightScore = (rect.height.toDouble() / height.toDouble()).coerceIn(0.0, 1.0)
            val borderPenalty = if (touchesBorder) TESSERACT_COMPONENT_BORDER_PENALTY else 0.0

            val score = sizeScore * 0.75 + heightScore * 0.2 + centerScore * 0.2 - borderPenalty
            if (score > bestScore) {
                bestScore = score
                bestRect = rect
            }
        }

        contours.forEach { it.release() }
        hierarchy.release()

        return bestRect ?: fallbackRect
    }

    private fun isolateAndCenterDigit(binaryBlackOnWhite: Mat, passLabel: String): Mat {
        val inkMask = Mat()
        Imgproc.threshold(binaryBlackOnWhite, inkMask, 127.0, 255.0, Imgproc.THRESH_BINARY_INV)
        if (ENABLE_TESSERACT_CLEAR_BORDER) {
            clearMaskBorder(inkMask, TESSERACT_CLEAR_BORDER_PX)
        }

        val componentRect = selectDigitComponentRect(inkMask)
        inkMask.release()

        if (componentRect == null) {
            return binaryBlackOnWhite.clone()
        }

        if (componentRect.width <= 0 || componentRect.height <= 0) {
            return binaryBlackOnWhite.clone()
        }

        val rows = binaryBlackOnWhite.rows().coerceAtLeast(1)
        val cols = binaryBlackOnWhite.cols().coerceAtLeast(1)
        val targetHeight = (rows * TESSERACT_TARGET_DIGIT_FILL_RATIO).roundToInt().coerceAtLeast(1)
        val targetWidth = (cols * TESSERACT_TARGET_DIGIT_FILL_RATIO).roundToInt().coerceAtLeast(1)
        val baseScale = minOf(
            targetWidth.toDouble() / componentRect.width.toDouble(),
            targetHeight.toDouble() / componentRect.height.toDouble()
        )
        val scale = baseScale.coerceIn(TESSERACT_MIN_NORMALIZE_SCALE, TESSERACT_MAX_NORMALIZE_SCALE)

        val resizedWidth = (componentRect.width * scale).roundToInt().coerceIn(1, cols)
        val resizedHeight = (componentRect.height * scale).roundToInt().coerceIn(1, rows)

        val digitRoi = Mat(binaryBlackOnWhite, componentRect)
        val resized = Mat()
        Imgproc.resize(
            digitRoi,
            resized,
            Size(resizedWidth.toDouble(), resizedHeight.toDouble()),
            0.0,
            0.0,
            Imgproc.INTER_LINEAR
        )

        val centered = Mat(binaryBlackOnWhite.size(), binaryBlackOnWhite.type(), Scalar(255.0))
        val destX = ((cols - resizedWidth) / 2).coerceAtLeast(0)
        val destY = ((rows - resizedHeight) / 2).coerceAtLeast(0)
        val targetRect = Rect(destX, destY, resizedWidth, resizedHeight)
        val centeredRoi = centered.submat(targetRect)
        resized.copyTo(centeredRoi)

        centeredRoi.release()
        digitRoi.release()
        resized.release()

        Log.i(
            TAG,
            "Centered digit ($passLabel): rect=${componentRect.width}x${componentRect.height}, " +
                "scaled=${resizedWidth}x$resizedHeight"
        )

        return centered
    }

    private fun maybeApplySixNineFallback(
        sourceBitmap: Bitmap,
        digit: Int,
        confidence: Int,
        voteCount: Int,
        stage: String
    ): Int {
        if (!ENABLE_TESSERACT_69_CENTROID_FALLBACK || (digit != 6 && digit != 9)) {
            return digit
        }
        val shouldRunFallback =
            confidence <= TESSERACT_69_FALLBACK_MAX_CONFIDENCE || voteCount <= TESSERACT_69_FALLBACK_MAX_VOTES
        if (!shouldRunFallback) {
            return digit
        }

        val resolved = disambiguate6And9ByCentroid(sourceBitmap, digit)
        if (resolved != digit) {
            Log.i(
                TAG,
                "6/9 centroid fallback ($stage): $digit -> $resolved (conf=$confidence, votes=$voteCount)"
            )
        }
        return resolved
    }

    private fun disambiguate6And9ByCentroid(sourceBitmap: Bitmap, ocrDigit: Int): Int {
        val mat = Mat()
        val gray = Mat()
        val binary = Mat()
        val inkMask = Mat()

        try {
            Utils.bitmapToMat(sourceBitmap, mat)
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

            val totalPixels = (binary.rows() * binary.cols()).coerceAtLeast(1)
            val nonZero = Core.countNonZero(binary)
            if (nonZero < totalPixels / 2) {
                Core.bitwise_not(binary, binary)
            }

            Imgproc.threshold(binary, inkMask, 127.0, 255.0, Imgproc.THRESH_BINARY_INV)
            if (ENABLE_TESSERACT_CLEAR_BORDER) {
                clearMaskBorder(inkMask, TESSERACT_CLEAR_BORDER_PX)
            }

            val componentRect = selectDigitComponentRect(inkMask)
            val focusedMask =
                if (componentRect != null && componentRect.width > 0 && componentRect.height > 0) {
                    Mat(inkMask, componentRect).clone()
                } else {
                    inkMask.clone()
                }

            val moments = Imgproc.moments(focusedMask, true)
            val mass = moments.m00
            if (mass <= 0.0) {
                focusedMask.release()
                return ocrDigit
            }

            val height = focusedMask.rows().coerceAtLeast(1).toDouble()
            val centroidYRatio = (moments.m01 / mass) / height
            focusedMask.release()

            return when {
                centroidYRatio <= TESSERACT_69_TOP_CENTROID_RATIO -> 9
                centroidYRatio >= TESSERACT_69_BOTTOM_CENTROID_RATIO -> 6
                else -> ocrDigit
            }
        } catch (e: Exception) {
            Log.w(TAG, "6/9 centroid fallback failed; keeping OCR digit $ocrDigit", e)
            return ocrDigit
        } finally {
            mat.release()
            gray.release()
            binary.release()
            inkMask.release()
        }
    }

    /**
     * Enhanced preprocessing for Tesseract - optimized for digit recognition
     */
    private fun enhanceForTesseract(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // Convert to grayscale
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

        // Apply Gaussian blur
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)

        // Otsu threshold
        val binary = Mat()
        Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

        // Denoise: morphological opening to remove isolated noise pixels
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        val denoised = Mat()
        Imgproc.morphologyEx(binary, denoised, Imgproc.MORPH_OPEN, kernel)
        binary.release()
        kernel.release()

        // Ensure white background, black text (Tesseract works best with this)
        val nonZeroCount = Core.countNonZero(denoised)
        val totalPixels = denoised.rows() * denoised.cols()

        val normalized = if (nonZeroCount < totalPixels / 2) {
            // More black = digit is white, invert so digit is black
            val inverted = Mat()
            Core.bitwise_not(denoised, inverted)
            denoised.release()
            inverted
        } else {
            denoised
        }

        val thickened = if (ENABLE_TESSERACT_THIN_STROKE_DILATION) {
            val dilated = maybeDilateThinStrokes(normalized)
            normalized.release()
            dilated
        } else {
            normalized
        }

        val focused = if (ENABLE_TESSERACT_ISOLATE_AND_CENTER) {
            val isolated = isolateAndCenterDigit(thickened, "base")
            thickened.release()
            isolated
        } else {
            thickened
        }

        // Add white padding
        val padding = TESSERACT_BASE_PADDING_PX
        val padded = Mat()
        Core.copyMakeBorder(
            focused, padded, padding, padding, padding, padding,
            Core.BORDER_CONSTANT, Scalar(255.0)
        )

        val result = Bitmap.createBitmap(padded.cols(), padded.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(padded, result)

        // Cleanup
        mat.release()
        gray.release()
        blurred.release()
        focused.release()
        padded.release()

        return result
    }

    /**
     * Alternate preprocessing path: adaptive thresholding performs better on uneven lighting.
     */
    private fun enhanceForTesseractAdaptive(bitmap: Bitmap): Bitmap {
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

        val thickened = if (ENABLE_TESSERACT_THIN_STROKE_DILATION) {
            val dilated = maybeDilateThinStrokes(normalized)
            normalized.release()
            dilated
        } else {
            normalized
        }

        val focused = if (ENABLE_TESSERACT_ISOLATE_AND_CENTER) {
            val isolated = isolateAndCenterDigit(thickened, "adaptive")
            thickened.release()
            isolated
        } else {
            thickened
        }

        val padded = Mat()
        Core.copyMakeBorder(
            focused,
            padded,
            TESSERACT_ADAPTIVE_PADDING_PX,
            TESSERACT_ADAPTIVE_PADDING_PX,
            TESSERACT_ADAPTIVE_PADDING_PX,
            TESSERACT_ADAPTIVE_PADDING_PX,
            Core.BORDER_CONSTANT, Scalar(255.0)
        )

        val result = Bitmap.createBitmap(padded.cols(), padded.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(padded, result)

        mat.release()
        gray.release()
        equalized.release()
        focused.release()
        padded.release()

        return result
    }

    private fun enhanceForTesseractClahe(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

        val claheOutput = Mat()
        val clahe = Imgproc.createCLAHE(
            TESSERACT_CLAHE_CLIP_LIMIT,
            Size(TESSERACT_CLAHE_TILE_SIZE.toDouble(), TESSERACT_CLAHE_TILE_SIZE.toDouble())
        )
        clahe.apply(gray, claheOutput)
        clahe.collectGarbage()

        val adaptive = Mat()
        Imgproc.adaptiveThreshold(
            claheOutput,
            adaptive,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            TESSERACT_CLAHE_BLOCK_SIZE,
            TESSERACT_CLAHE_C
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

        val thickened = if (ENABLE_TESSERACT_THIN_STROKE_DILATION) {
            val dilated = maybeDilateThinStrokes(normalized)
            normalized.release()
            dilated
        } else {
            normalized
        }

        val focused = if (ENABLE_TESSERACT_ISOLATE_AND_CENTER) {
            val isolated = isolateAndCenterDigit(thickened, "clahe")
            thickened.release()
            isolated
        } else {
            thickened
        }

        val padded = Mat()
        Core.copyMakeBorder(
            focused,
            padded,
            TESSERACT_CLAHE_PADDING_PX,
            TESSERACT_CLAHE_PADDING_PX,
            TESSERACT_CLAHE_PADDING_PX,
            TESSERACT_CLAHE_PADDING_PX,
            Core.BORDER_CONSTANT, Scalar(255.0)
        )

        val result = Bitmap.createBitmap(padded.cols(), padded.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(padded, result)

        mat.release()
        gray.release()
        claheOutput.release()
        focused.release()
        padded.release()

        return result
    }

    private fun shouldRunClaheFallback(
        baseRaw: TesseractCellResult,
        baseInverted: TesseractCellResult,
        pickedBeforeClahe: Pair<TesseractCellResult, Int>?,
        candidates: List<TesseractCellResult>
    ): Boolean {
        if (!ENABLE_TESSERACT_CLAHE_FALLBACK) return false

        val baseRawValid = baseRaw.digit in 1..9
        val baseInvertedValid = baseInverted.digit in 1..9
        val baseDisagree = baseRawValid && baseInvertedValid && baseRaw.digit != baseInverted.digit
        val baseBestConfidence = maxOf(baseRaw.confidence, baseInverted.confidence)
        val baseWeak = baseBestConfidence < TESSERACT_CLAHE_TRIGGER_CONFIDENCE
        val candidateDisagreement =
            candidates.asSequence().map { it.digit }.filter { it in 1..9 }.distinct().count() > 1

        if (pickedBeforeClahe == null) return true

        val (best, voteCount) = pickedBeforeClahe
        val currentWeak =
            voteCount <= 1 ||
                best.confidence < TESSERACT_CLAHE_TRIGGER_CONFIDENCE ||
                candidateDisagreement
        return baseDisagree || baseWeak || currentWeak
    }

    @Synchronized
    private fun nextDebugDumpId(): Int {
        debugDumpCounter += 1
        return debugDumpCounter
    }

    private fun persistBitmapToPng(file: File, bitmap: Bitmap) {
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.flush()
        }
    }

    private fun maybeDumpOcrDebugArtifacts(
        sourceBitmap: Bitmap,
        result: TesseractCellResult,
        voteCount: Int,
        stage: String,
        reason: String,
        candidates: List<TesseractCellResult>
    ) {
        if (!ENABLE_TESSERACT_DEBUG_DUMPS) return

        val shouldDump =
            result.isUncertain ||
                (result.confidence in 1..TESSERACT_DEBUG_LOW_CONFIDENCE) ||
                reason.contains("reject")
        if (!shouldDump) return

        val dumpId = nextDebugDumpId()
        if (dumpId > TESSERACT_DEBUG_MAX_DUMPS) return

        try {
            val debugDir = File(context.filesDir, TESSERACT_DEBUG_DIR_NAME)
            if (!debugDir.exists() && !debugDir.mkdirs()) return

            val sanitizedStage = stage.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val baseName = "ocr_${System.currentTimeMillis()}_${dumpId}_$sanitizedStage"

            persistBitmapToPng(File(debugDir, "${baseName}_source.png"), sourceBitmap)

            if (ENABLE_TESSERACT_DEBUG_DUMP_PREPROCESSED) {
                val basePreview = enhanceForTesseract(sourceBitmap)
                val adaptivePreview = enhanceForTesseractAdaptive(sourceBitmap)
                val clahePreview = enhanceForTesseractClahe(sourceBitmap)
                try {
                    persistBitmapToPng(File(debugDir, "${baseName}_base.png"), basePreview)
                    persistBitmapToPng(File(debugDir, "${baseName}_adaptive.png"), adaptivePreview)
                    persistBitmapToPng(File(debugDir, "${baseName}_clahe.png"), clahePreview)
                } finally {
                    basePreview.recycle()
                    adaptivePreview.recycle()
                    clahePreview.recycle()
                }
            }

            val metadata = buildString {
                appendLine("stage=$stage")
                appendLine("reason=$reason")
                appendLine("digit=${result.digit}")
                appendLine("confidence=${result.confidence}")
                appendLine("isUncertain=${result.isUncertain}")
                appendLine("voteCount=$voteCount")
                appendLine("candidateCount=${candidates.size}")
                appendLine(
                    "candidates=" + candidates.joinToString(",") {
                        "${it.digit}:${it.confidence}:${it.isUncertain}"
                    }
                )
            }
            File(debugDir, "${baseName}.txt").writeText(metadata)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write OCR debug dump", e)
        }
    }

    private fun runTesseractCandidate(bitmap: Bitmap, label: String): TesseractCellResult {
        tessApi?.setImage(bitmap)
        val recognizedText = tessApi?.utF8Text?.trim() ?: ""
        val confidence = tessApi?.meanConfidence() ?: 0
        Log.i(TAG, "Tesseract $label: '$recognizedText' (confidence: $confidence)")
        return parseDigitCandidate(recognizedText, confidence)
    }

    private fun pickBestCandidate(candidates: List<TesseractCellResult>): Pair<TesseractCellResult, Int>? {
        if (candidates.isEmpty()) return null

        val grouped = candidates.groupBy { it.digit }
        var bestDigit = 0
        var bestConf = -1
        var bestVotes = 0
        var bestScore = Int.MIN_VALUE

        for ((digit, votes) in grouped) {
            val maxConf = votes.maxOf { it.confidence }
            val voteCount = votes.size
            val avgConf = votes.sumOf { it.confidence } / voteCount
            val score = maxConf + (voteCount - 1) * TESSERACT_VOTE_BONUS + avgConf / 10

            if (score > bestScore) {
                bestScore = score
                bestDigit = digit
                bestConf = maxConf
                bestVotes = voteCount
            }
        }

        if (bestDigit !in 1..9) return null
        return Pair(TesseractCellResult(bestDigit, bestConf, false), bestVotes)
    }

    private fun ocrCellDetailed(cellBitmap: Bitmap): TesseractCellResult {
        if (!isInitialized || tessApi == null) {
            Log.e(TAG, "Tesseract not initialized")
            return TesseractCellResult(0, 0, false)
        }

        try {
            val cropped = cropCell(cellBitmap)
            try {
                val candidates = mutableListOf<TesseractCellResult>()

                fun collectCandidates(name: String, prepared: Bitmap): Pair<TesseractCellResult, TesseractCellResult> {
                    val scaledWidth = (prepared.width * TESSERACT_SCALE_FACTOR).roundToInt().coerceAtLeast(1)
                    val scaledHeight = (prepared.height * TESSERACT_SCALE_FACTOR).roundToInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(prepared, scaledWidth, scaledHeight, true)

                    val normal = runTesseractCandidate(scaled, "$name/raw")
                    if (normal.digit in 1..9) candidates.add(normal)

                    val mat = Mat()
                    Utils.bitmapToMat(scaled, mat)
                    Core.bitwise_not(mat, mat)
                    val inverted = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(mat, inverted)
                    mat.release()

                    val invertedCandidate = runTesseractCandidate(inverted, "$name/inverted")
                    if (invertedCandidate.digit in 1..9) candidates.add(invertedCandidate)

                    prepared.recycle()
                    scaled.recycle()
                    inverted.recycle()
                    return Pair(normal, invertedCandidate)
                }

                val basePrepared = enhanceForTesseract(cropped)
                val (baseRaw, baseInverted) = collectCandidates("base", basePrepared)

                val strongBaseRaw =
                    baseRaw.digit in 1..9 &&
                        baseRaw.confidence >= TESSERACT_EARLY_ACCEPT_CONFIDENCE
                val baseAgreementConfidence = maxOf(baseRaw.confidence, baseInverted.confidence)
                val agreeingBase =
                    baseRaw.digit in 1..9 &&
                        baseRaw.digit == baseInverted.digit &&
                        baseAgreementConfidence >= TESSERACT_EARLY_AGREE_CONFIDENCE

                if (strongBaseRaw || agreeingBase) {
                    val early = if (agreeingBase && baseInverted.confidence > baseRaw.confidence) {
                        baseInverted
                    } else {
                        baseRaw
                    }
                    val earlyVotes = if (agreeingBase) 2 else 1
                    val mode = if (agreeingBase) "base_agree" else "base_raw"
                    val resolvedEarlyDigit = maybeApplySixNineFallback(
                        cropped,
                        early.digit,
                        early.confidence,
                        earlyVotes,
                        mode
                    )
                    val isUncertain =
                        early.confidence < TESSERACT_HIGH_CONFIDENCE ||
                            isConfusedDigit(resolvedEarlyDigit)
                    val earlyResult = TesseractCellResult(resolvedEarlyDigit, early.confidence, isUncertain)
                    maybeDumpOcrDebugArtifacts(
                        sourceBitmap = cropped,
                        result = earlyResult,
                        voteCount = earlyVotes,
                        stage = mode,
                        reason = "early_accept",
                        candidates = candidates
                    )
                    Log.i(
                        TAG,
                        "Tesseract early accept: digit=$resolvedEarlyDigit, conf=${early.confidence}, mode=$mode"
                    )
                    return earlyResult
                }

                val adaptivePrepared = enhanceForTesseractAdaptive(cropped)
                collectCandidates("adaptive", adaptivePrepared)

                var picked = pickBestCandidate(candidates)
                if (shouldRunClaheFallback(baseRaw, baseInverted, picked, candidates)) {
                    Log.i(TAG, "Running CLAHE fallback pass (base/adaptive weak or disagreement detected)")
                    val clahePrepared = enhanceForTesseractClahe(cropped)
                    collectCandidates("clahe", clahePrepared)
                    picked = pickBestCandidate(candidates)
                }
                if (picked == null) {
                    return TesseractCellResult(0, 0, false)
                }
                val (best, voteCount) = picked

                // Reject low-confidence single-digit hallucinations (common on empty/noisy cells).
                val acceptedByConsensus =
                    voteCount >= 2 && best.confidence >= MIN_TESSERACT_CONSENSUS_CONFIDENCE
                if (best.confidence < MIN_TESSERACT_ACCEPT_CONFIDENCE && !acceptedByConsensus) {
                    Log.i(
                        TAG,
                        "Rejecting low-confidence Tesseract digit ${best.digit} " +
                            "(conf=${best.confidence}, votes=$voteCount)"
                    )
                    val rejected = TesseractCellResult(0, best.confidence, false)
                    maybeDumpOcrDebugArtifacts(
                        sourceBitmap = cropped,
                        result = rejected,
                        voteCount = voteCount,
                        stage = "vote",
                        reason = "reject_low_confidence",
                        candidates = candidates
                    )
                    return rejected
                }

                val resolvedDigit = maybeApplySixNineFallback(
                    cropped,
                    best.digit,
                    best.confidence,
                    voteCount,
                    "vote"
                )
                val isUncertain =
                    best.confidence < TESSERACT_HIGH_CONFIDENCE ||
                        isConfusedDigit(resolvedDigit) ||
                        voteCount <= 1
                val finalResult = TesseractCellResult(resolvedDigit, best.confidence, isUncertain)
                maybeDumpOcrDebugArtifacts(
                    sourceBitmap = cropped,
                    result = finalResult,
                    voteCount = voteCount,
                    stage = "vote",
                    reason = "final",
                    candidates = candidates
                )
                Log.i(TAG, "Tesseract voted: digit=$resolvedDigit, conf=${best.confidence}, votes=$voteCount")
                return finalResult
            } finally {
                cropped.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tesseract OCR error", e)
            return TesseractCellResult(0, 0, false)
        }
    }

    private fun ocrCellWithUncertainty(cellBitmap: Bitmap): Pair<Int, Boolean> {
        val result = ocrCellDetailed(cellBitmap)
        return Pair(result.digit, result.isUncertain)
    }

    private fun parseDigitCandidate(text: String, confidence: Int): TesseractCellResult {
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) return TesseractCellResult(0, 0, false)

        val uniqueDigits = digits.toSet()
        if (uniqueDigits.size != 1) return TesseractCellResult(0, 0, false)

        val digit = digits.first().digitToInt()
        if (digit !in 1..9) return TesseractCellResult(0, 0, false)

        val safeConfidence = confidence.coerceAtLeast(0)
        return TesseractCellResult(digit, safeConfidence, false)
    }

    private fun isConfusedDigit(digit: Int): Boolean {
        return digit == 6 || digit == 9 || digit == 1 || digit == 7
    }

    /**
     * OCR a single cell - public method for hybrid mode
     * Returns just the digit (0 if not recognized)
     */
    fun ocrSingleCell(cellBitmap: Bitmap): Int {
        return ocrCellDetailed(cellBitmap).digit
    }

    fun ocrSingleCellDetailed(cellBitmap: Bitmap): TesseractCellResult {
        return ocrCellDetailed(cellBitmap)
    }

    fun release() {
        tessApi?.recycle()
        tessApi = null
        isInitialized = false
    }

    companion object {
        private const val TAG = "TesseractDigitRecognizer"
        private const val ENABLE_TESSERACT_ISOLATE_AND_CENTER = true
        private const val ENABLE_TESSERACT_CLEAR_BORDER = true
        private const val ENABLE_TESSERACT_THIN_STROKE_DILATION = true
        private const val ENABLE_TESSERACT_69_CENTROID_FALLBACK = true
        private const val ENABLE_TESSERACT_CLAHE_FALLBACK = true
        private const val ENABLE_TESSERACT_DEBUG_DUMPS = false
        private const val ENABLE_TESSERACT_DEBUG_DUMP_PREPROCESSED = true
        private const val TESSERACT_BASE_PADDING_PX = 22
        private const val TESSERACT_ADAPTIVE_PADDING_PX = 24
        private const val TESSERACT_CLAHE_PADDING_PX = 20
        private const val TESSERACT_SCALE_FACTOR = 3.2
        private const val TESSERACT_CLEAR_BORDER_PX = 2
        private const val TESSERACT_COMPONENT_MIN_AREA_RATIO = 0.007
        private const val TESSERACT_MIN_COMPONENT_HEIGHT_RATIO = 0.20
        private const val TESSERACT_FALLBACK_MIN_HEIGHT_RATIO = 0.14
        private const val TESSERACT_COMPONENT_BORDER_MARGIN_PX = 1
        private const val TESSERACT_COMPONENT_BORDER_PENALTY = 0.22
        private const val TESSERACT_TARGET_DIGIT_FILL_RATIO = 0.78
        private const val TESSERACT_MIN_NORMALIZE_SCALE = 0.65
        private const val TESSERACT_MAX_NORMALIZE_SCALE = 3.0
        private const val TESSERACT_DILATION_INK_RATIO_THRESHOLD = 0.070
        private const val TESSERACT_CLAHE_TRIGGER_CONFIDENCE = 72
        private const val TESSERACT_CLAHE_CLIP_LIMIT = 2.5
        private const val TESSERACT_CLAHE_TILE_SIZE = 8
        private const val TESSERACT_CLAHE_BLOCK_SIZE = 35
        private const val TESSERACT_CLAHE_C = 4.0
        private const val TESSERACT_DIGIT_GATE_LOW_MULTIPLIER = 0.7
        private const val TESSERACT_DIGIT_GATE_STRONG_MULTIPLIER = 2.2
        private const val TESSERACT_DIGIT_GATE_MIN_WHITE_RATIO = 0.0015
        private const val TESSERACT_DIGIT_GATE_STRONG_MIN_WHITE_RATIO = 0.004
        private const val TESSERACT_DIGIT_GATE_MAX_WHITE_RATIO = 0.38
        private const val TESSERACT_DIGIT_GATE_MIN_BOX_WIDTH_RATIO = 0.07
        private const val TESSERACT_DIGIT_GATE_MIN_BOX_HEIGHT_RATIO = 0.20
        private const val TESSERACT_DIGIT_GATE_MAX_CENTER_OFFSET = 0.38
        private const val TESSERACT_CELL_CONTOUR_MIN_AREA_RATIO = 0.003
        private const val TESSERACT_CELL_CONTOUR_MAX_AREA_RATIO = 0.42
        private const val TESSERACT_CELL_CONTOUR_MIN_WIDTH_RATIO = 0.05
        private const val TESSERACT_CELL_CONTOUR_MIN_HEIGHT_RATIO = 0.18
        private const val TESSERACT_CELL_CONTOUR_MIN_ASPECT = 0.12
        private const val TESSERACT_CELL_CONTOUR_MAX_ASPECT = 1.85
        private const val TESSERACT_CELL_CENTER_ZONE_LEFT = 0.08
        private const val TESSERACT_CELL_CENTER_ZONE_RIGHT = 0.92
        private const val TESSERACT_CELL_CENTER_ZONE_TOP = 0.08
        private const val TESSERACT_CELL_CENTER_ZONE_BOTTOM = 0.92
        private const val TESSERACT_69_FALLBACK_MAX_CONFIDENCE = 86
        private const val TESSERACT_69_FALLBACK_MAX_VOTES = 1
        private const val TESSERACT_69_TOP_CENTROID_RATIO = 0.46
        private const val TESSERACT_69_BOTTOM_CENTROID_RATIO = 0.54
        private const val TESSERACT_DEBUG_LOW_CONFIDENCE = 72
        private const val TESSERACT_DEBUG_MAX_DUMPS = 160
        private const val TESSERACT_DEBUG_DIR_NAME = "ocr_debug"
        private const val MIN_TESSERACT_ACCEPT_CONFIDENCE = 45
        private const val MIN_TESSERACT_CONSENSUS_CONFIDENCE = 35
        private const val TESSERACT_HIGH_CONFIDENCE = 80
        private const val TESSERACT_VOTE_BONUS = 8
        private const val TESSERACT_EARLY_ACCEPT_CONFIDENCE = 92
        private const val TESSERACT_EARLY_AGREE_CONFIDENCE = 78
    }
}
