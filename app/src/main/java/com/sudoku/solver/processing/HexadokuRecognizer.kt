package com.sudoku.solver.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sudoku.solver.model.HexadokuGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.min

data class HexadokuRecognitionResult(
    val grid: HexadokuGrid,
    val occupiedCells: Set<Pair<Int, Int>>,
    val unrecognizedOccupiedCells: Set<Pair<Int, Int>>,
    val lowConfidenceCells: Set<Pair<Int, Int>>,
    val cellReads: Map<Pair<Int, Int>, HexadokuCellRead>
)

data class HexadokuCellRead(
    val tesseractText: String,
    val tesseractConfidence: Int,
    val mlKitText: String,
    val value: Int,
    val source: String
)

/**
 * Tesseract recognizer dedicated to 16x16 boards using 1-9 and A-G.
 * Its whitelist and parsing are intentionally separate from the numeric-only
 * recognizer used by the normal 9x9 SOLVE path.
 */
class HexadokuRecognizer(private val context: Context) {
    private var tessApi: TessBaseAPI? = null
    private var isInitialized = false
    private val mlKitRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    init {
        initialize()
    }

    suspend fun extractGrid(warpedBitmap: Bitmap): HexadokuRecognitionResult =
        withContext(Dispatchers.Default) {
            check(isInitialized) { "16x16 OCR is not initialized" }
            val size = HexadokuGrid.SIZE
            val values = Array(size) { IntArray(size) }
            val locked = Array(size) { BooleanArray(size) }
            val occupied = linkedSetOf<Pair<Int, Int>>()
            val unrecognized = linkedSetOf<Pair<Int, Int>>()
            val lowConfidence = linkedSetOf<Pair<Int, Int>>()
            val cellReads = linkedMapOf<Pair<Int, Int>, HexadokuCellRead>()
            val cellShapes = linkedMapOf<Pair<Int, Int>, ShapeDescriptor>()
            val diagnosticsDirectory = context.getExternalFilesDir(DIAGNOSTICS_DIRECTORY)?.apply {
                mkdirs()
                listFiles()?.forEach { file -> file.delete() }
            }

            val xBounds = IntArray(size + 1) { index -> warpedBitmap.width * index / size }
            val yBounds = IntArray(size + 1) { index -> warpedBitmap.height * index / size }

            for (row in 0 until size) {
                for (col in 0 until size) {
                    coroutineContext.ensureActive()
                    val fullLeft = xBounds[col]
                    val fullTop = yBounds[row]
                    val fullWidth = (xBounds[col + 1] - fullLeft).coerceAtLeast(1)
                    val fullHeight = (yBounds[row + 1] - fullTop).coerceAtLeast(1)
                    // Keep grid lines out while leaving room for larger fonts that nearly
                    // touch the cell edges. The margin is proportional to the detected cell.
                    val marginX = (fullWidth * CELL_MARGIN_RATIO).toInt().coerceAtLeast(1)
                    val marginY = (fullHeight * CELL_MARGIN_RATIO).toInt().coerceAtLeast(1)
                    val cropWidth = (fullWidth - marginX * 2).coerceAtLeast(1)
                    val cropHeight = (fullHeight - marginY * 2).coerceAtLeast(1)
                    val cell = Bitmap.createBitmap(
                        warpedBitmap,
                        fullLeft + marginX,
                        fullTop + marginY,
                        cropWidth,
                        cropHeight
                    )

                    try {
                        if (!cellHasInk(cell)) continue
                        val coordinate = row to col
                        occupied.add(coordinate)
                        // Occupancy and recognition are separate facts. Even if OCR cannot
                        // name this clue, auto-input must never overwrite a visibly filled cell.
                        locked[row][col] = true
                        val prepared = prepareForOcr(cell)
                        try {
                            cellShapes[coordinate] = buildShapeDescriptor(prepared)
                            val api = tessApi ?: error("16x16 OCR is not initialized")
                            api.setImage(prepared)
                            val rawText = api.utF8Text.orEmpty().trim()
                            val confidence = api.meanConfidence()
                            api.clear()
                            val tesseractValue = parseSymbol(rawText)
                            // Tesseract is fast for clear cells. ML Kit adds useful signal
                            // for faint or ambiguous glyphs, but running it on every clue
                            // made a 16x16 scan wait on more than a hundred model calls.
                            val mlKitText = if (
                                tesseractValue == 0 || confidence < STRONG_TESSERACT_CONFIDENCE
                            ) {
                                val mlKitBitmap = prepareForMlKit(cell)
                                try {
                                    mlKitRecognizer.process(InputImage.fromBitmap(mlKitBitmap, 0))
                                        .await()
                                        .text
                                        .trim()
                                } catch (error: Exception) {
                                    Log.w(TAG, "ML Kit failed for 16x16 cell ($row,$col)", error)
                                    ""
                                } finally {
                                    mlKitBitmap.recycle()
                                }
                            } else {
                                ""
                            }
                            val mlKitValue = parseMlKitSymbol(mlKitText)
                            val topologicalOne = (tesseractValue in listOf(1, 7) || mlKitValue in listOf(1, 7)) &&
                                looksLikeNarrowOne(prepared)
                            // Tesseract occasionally closes this game's open C into a 6.
                            // The two right-facing tips with a clear middle gap distinguish
                            // C from a 6's closed lower loop.
                            val topologicalC = (tesseractValue == 6 || mlKitValue == 6) &&
                                looksLikeTopologicalC(prepared)
                            val topologicalFour = looksLikeTopologicalFour(prepared)
                            val topologicalB = looksLikeTopologicalB(prepared)
                            val topologicalEight = looksLikeTopologicalEight(prepared)
                            val topologicalBarLetter = classifyTopologicalBarLetter(prepared)
                            val topologicalNine = looksLikeTopologicalNine(prepared)
                            val topologicalFive = tesseractValue in listOf(0, 5, 9) &&
                                confidence < STRONG_TESSERACT_CONFIDENCE &&
                                mlKitValue in listOf(0, 5, 9) &&
                                looksLikeTopologicalFive(prepared)
                            val value = when {
                                topologicalOne -> 1
                                topologicalC -> HexadokuGrid.valueFor('C')
                                topologicalFour -> 4
                                topologicalB -> HexadokuGrid.valueFor('B')
                                topologicalEight -> 8
                                topologicalBarLetter == HexadokuBarTopologyPolicy.Symbol.E ->
                                    HexadokuGrid.valueFor('E')
                                topologicalBarLetter == HexadokuBarTopologyPolicy.Symbol.F ->
                                    HexadokuGrid.valueFor('F')
                                topologicalNine -> HexadokuGrid.valueFor('9')
                                topologicalFive -> 5
                                mlKitValue != 0 -> mlKitValue
                                else -> tesseractValue
                            }
                            val source = when {
                                topologicalOne -> "TOPOLOGY_1"
                                topologicalC -> "TOPOLOGY_C"
                                topologicalFour -> "TOPOLOGY_4"
                                topologicalB -> "TOPOLOGY_B"
                                topologicalEight -> "TOPOLOGY_8"
                                topologicalBarLetter == HexadokuBarTopologyPolicy.Symbol.E -> "TOPOLOGY_E"
                                topologicalBarLetter == HexadokuBarTopologyPolicy.Symbol.F -> "TOPOLOGY_F"
                                topologicalNine -> "TOPOLOGY_9"
                                topologicalFive -> "TOPOLOGY_5"
                                mlKitValue != 0 -> "ML_KIT"
                                tesseractValue != 0 -> "TESSERACT"
                                else -> "NONE"
                            }
                            cellReads[coordinate] = HexadokuCellRead(
                                tesseractText = rawText,
                                tesseractConfidence = confidence,
                                mlKitText = mlKitText,
                                value = value,
                                source = source
                            )
                            if (value == 0) {
                                unrecognized.add(coordinate)
                                Log.w(TAG, "16x16 OCR missed occupied cell ($row,$col): '$rawText'")
                                diagnosticsDirectory?.let { directory ->
                                    File(directory, "cell_${row}_${col}_original.png").outputStream().use { output ->
                                        cell.compress(Bitmap.CompressFormat.PNG, 100, output)
                                    }
                                    File(directory, "cell_${row}_${col}_prepared.png").outputStream().use { output ->
                                        prepared.compress(Bitmap.CompressFormat.PNG, 100, output)
                                    }
                                }
                            } else {
                                values[row][col] = value
                                locked[row][col] = true
                                if (source == "TESSERACT" && confidence < LOW_CONFIDENCE_THRESHOLD) {
                                    lowConfidence.add(coordinate)
                                }
                                Log.d(
                                    TAG,
                                    "16x16 cell ($row,$col)=${HexadokuGrid.symbolFor(value)} conf=$confidence"
                                )
                            }
                        } finally {
                            prepared.recycle()
                        }
                    } finally {
                        cell.recycle()
                    }
                }
            }

            refineWithShapeTemplates(
                values = values,
                locked = locked,
                occupied = occupied,
                unrecognized = unrecognized,
                lowConfidence = lowConfidence,
                cellReads = cellReads,
                cellShapes = cellShapes
            )

            HexadokuRecognitionResult(
                grid = HexadokuGrid(values, locked),
                occupiedCells = occupied,
                unrecognizedOccupiedCells = unrecognized,
                lowConfidenceCells = lowConfidence,
                cellReads = cellReads
            )
        }

    fun close() {
        tessApi?.recycle()
        tessApi = null
        mlKitRecognizer.close()
        isInitialized = false
    }

    internal fun parseSymbol(rawText: String): Int {
        val normalized = normalizeOcrSymbols(rawText)
        for (character in normalized) {
            val value = HexadokuGrid.valueFor(character)
            if (value != 0) return value
        }
        return 0
    }

    internal fun parseMlKitSymbol(rawText: String): Int {
        val alphaNumeric = normalizeOcrSymbols(rawText)
        if (alphaNumeric.isEmpty()) return 0
        if (alphaNumeric.any { HexadokuGrid.valueFor(it) == 0 }) return 0
        val distinctValues = alphaNumeric.map(HexadokuGrid::valueFor).toSet()
        if (distinctValues == setOf(4, 10)) return 4 // ML Kit reports this font's 4 as "A 4".
        return distinctValues.singleOrNull() ?: 0
    }

    private fun normalizeOcrSymbols(rawText: String): String = rawText.uppercase()
        .map { character ->
            // I and L are never valid Hexadoku letters; S is also outside A-G.
            // Treat these common OCR confusions as their unambiguous digit forms.
            when (character) {
                'I', 'L', '|' -> '1'
                'S' -> '5'
                else -> character
            }
        }
        .filter { it.isLetterOrDigit() }
        .joinToString(separator = "")

    private fun refineWithShapeTemplates(
        values: Array<IntArray>,
        locked: Array<BooleanArray>,
        occupied: Set<Pair<Int, Int>>,
        unrecognized: MutableSet<Pair<Int, Int>>,
        lowConfidence: MutableSet<Pair<Int, Int>>,
        cellReads: MutableMap<Pair<Int, Int>, HexadokuCellRead>,
        cellShapes: Map<Pair<Int, Int>, ShapeDescriptor>
    ) {
        val templates = linkedMapOf<Int, MutableList<ShapeDescriptor>>()

        fun addTemplate(value: Int, coordinate: Pair<Int, Int>) {
            if (value !in 1..HexadokuGrid.SIZE) return
            val shape = cellShapes[coordinate] ?: return
            val examples = templates.getOrPut(value) { mutableListOf() }
            if (examples.size < MAX_TEMPLATES_PER_SYMBOL) examples.add(shape)
        }

        // Agreement and high-confidence Tesseract reads form the initial seeds.
        cellReads.forEach { (coordinate, read) ->
            val tessValue = parseSymbol(read.tesseractText)
            val mlValue = parseMlKitSymbol(read.mlKitText)
            when {
                read.source == "TOPOLOGY_1" -> addTemplate(1, coordinate)
                read.source == "TOPOLOGY_C" -> addTemplate(HexadokuGrid.valueFor('C'), coordinate)
                read.source == "TOPOLOGY_4" -> addTemplate(4, coordinate)
                read.source == "TOPOLOGY_B" -> addTemplate(HexadokuGrid.valueFor('B'), coordinate)
                read.source == "TOPOLOGY_8" -> addTemplate(8, coordinate)
                read.source == "TOPOLOGY_E" -> addTemplate(HexadokuGrid.valueFor('E'), coordinate)
                read.source == "TOPOLOGY_F" -> addTemplate(HexadokuGrid.valueFor('F'), coordinate)
                read.source == "TOPOLOGY_9" -> addTemplate(HexadokuGrid.valueFor('9'), coordinate)
                read.source == "TOPOLOGY_5" -> addTemplate(5, coordinate)
                tessValue != 0 && tessValue == mlValue -> addTemplate(tessValue, coordinate)
                tessValue != 0 && read.tesseractConfidence >= STRONG_TESSERACT_CONFIDENCE ->
                    addTemplate(tessValue, coordinate)
            }
        }

        // Fill symbol classes absent from the strong seeds. This puzzle font is
        // especially difficult for 9 in Tesseract and F in ML Kit, so each engine
        // can supply the missing class for the shape matcher.
        for (value in 1..HexadokuGrid.SIZE) {
            if (!templates[value].isNullOrEmpty()) continue
            val mlCandidate = cellReads.entries.firstOrNull { (_, read) ->
                parseMlKitSymbol(read.mlKitText) == value
            }
            if (mlCandidate != null) {
                addTemplate(value, mlCandidate.key)
                continue
            }
            val tessCandidate = cellReads.entries
                .filter { (_, read) -> parseSymbol(read.tesseractText) == value }
                .maxByOrNull { (_, read) -> read.tesseractConfidence }
            if (tessCandidate != null) addTemplate(value, tessCandidate.key)
        }

        if (templates.isEmpty()) return
        cellShapes.forEach { (coordinate, shape) ->
            val ranked = templates.mapNotNull { (value, examples) ->
                if (examples.isEmpty()) null else value to examples.minOf { template ->
                    shapeDistance(shape, template)
                }
            }.sortedBy { it.second }
            val best = ranked.firstOrNull() ?: return@forEach
            val secondDistance = ranked.getOrNull(1)?.second ?: 1f
            val margin = secondDistance - best.second
            if (best.second <= MAX_SHAPE_DISTANCE && margin >= MIN_SHAPE_MARGIN) {
                val (row, col) = coordinate
                values[row][col] = best.first
                locked[row][col] = true
                unrecognized.remove(coordinate)
                lowConfidence.remove(coordinate)
                val previous = cellReads[coordinate]
                if (previous != null) {
                    cellReads[coordinate] = previous.copy(
                        value = best.first,
                        source = "SHAPE_TEMPLATE(d=${"%.3f".format(best.second)},m=${"%.3f".format(margin)})"
                    )
                }
            }
        }

        unrecognized.clear()
        occupied.forEach { (row, col) ->
            if (values[row][col] == 0) unrecognized.add(row to col)
        }
    }

    private fun buildShapeDescriptor(bitmap: Bitmap): ShapeDescriptor {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (luminance(pixels[y * width + x]) < SHAPE_INK_LUMA) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        if (right < left || bottom < top) return ShapeDescriptor(BooleanArray(SHAPE_SIZE * SHAPE_SIZE))

        val sourceWidth = right - left + 1
        val sourceHeight = bottom - top + 1
        val scale = min(
            SHAPE_CONTENT_SIZE.toFloat() / sourceWidth,
            SHAPE_CONTENT_SIZE.toFloat() / sourceHeight
        )
        val targetWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val offsetX = (SHAPE_SIZE - targetWidth) / 2
        val offsetY = (SHAPE_SIZE - targetHeight) / 2
        val output = BooleanArray(SHAPE_SIZE * SHAPE_SIZE)
        for (targetY in 0 until targetHeight) {
            val sourceY = top + ((targetY + 0.5f) * sourceHeight / targetHeight).toInt().coerceIn(0, sourceHeight - 1)
            for (targetX in 0 until targetWidth) {
                val sourceX = left + ((targetX + 0.5f) * sourceWidth / targetWidth).toInt().coerceIn(0, sourceWidth - 1)
                output[(offsetY + targetY) * SHAPE_SIZE + offsetX + targetX] =
                    luminance(pixels[sourceY * width + sourceX]) < SHAPE_INK_LUMA
            }
        }
        return ShapeDescriptor(output)
    }

    /** A narrow 1 has a continuous vertical stem; a 7's descending stroke is slanted. */
    private fun looksLikeNarrowOne(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width
        var right = -1
        var top = bitmap.height
        var bottom = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (luminance(pixels[y * bitmap.width + x]) < SHAPE_INK_LUMA) {
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        if (right < left || bottom < top) return false
        val height = bottom - top + 1
        val width = right - left + 1
        val longestStem = (left..right).maxOf { x ->
            (top..bottom).count { y -> luminance(pixels[y * bitmap.width + x]) < SHAPE_INK_LUMA }
        }
        return HexadokuOneTopologyPolicy.isOne(width.toDouble() / height, longestStem.toDouble() / height)
    }

    /** An open C has two tips on the right and no ink across the right middle. */
    private fun looksLikeTopologicalC(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (luminance(pixels[y * width + x]) < SHAPE_INK_LUMA) {
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        if (right < left || bottom < top) return false
        val glyphWidth = right - left + 1
        val glyphHeight = bottom - top + 1
        if (glyphWidth < 12 || glyphHeight < 12 ||
            glyphWidth.toDouble() / glyphHeight !in 0.55..1.15
        ) return false

        val rightStart = left + (glyphWidth * 0.70).toInt()
        val leftEnd = left + (glyphWidth * 0.35).toInt()
        var upperRight = 0
        var middleRight = 0
        var lowerRight = 0
        var middleLeft = 0
        for (y in top..bottom) for (x in left..right) {
            if (luminance(pixels[y * width + x]) >= SHAPE_INK_LUMA) continue
            val relativeY = (y - top).toDouble() / glyphHeight
            if (x >= rightStart) {
                if (relativeY in 0.12..0.35) upperRight++
                if (relativeY in 0.42..0.58) middleRight++
                if (relativeY in 0.65..0.88) lowerRight++
            }
            if (x <= leftEnd && relativeY in 0.42..0.58) middleLeft++
        }
        val minimumTipInk = (glyphWidth * glyphHeight * 0.004).toInt().coerceAtLeast(3)
        return upperRight >= minimumTipInk && lowerRight >= minimumTipInk &&
            middleLeft >= minimumTipInk && middleRight < minimumTipInk
    }

    /**
     * The installed game's thin 5 is often read as 9 or dropped entirely. Its
     * open lower bowl has a full top bar, a left upper stem, a middle bar, and
     * ink in the lower-right quadrant. Those proportions remain stable as the
     * game font and Android display scale change.
     */
    private fun looksLikeTopologicalFive(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val ink = BooleanArray(width * height) { index ->
            luminance(pixels[index]) < SHAPE_INK_LUMA
        }

        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!ink[y * width + x]) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        if (right < left || bottom < top) return false

        val glyphWidth = right - left + 1
        val glyphHeight = bottom - top + 1
        val aspect = glyphWidth.toDouble() / glyphHeight
        if (aspect !in MIN_FIVE_ASPECT..MAX_FIVE_ASPECT || glyphHeight < MIN_FIVE_HEIGHT) return false

        val topEnd = (top + glyphHeight * FIVE_TOP_BAND_RATIO).toInt().coerceAtMost(bottom + 1)
        val middleStart = (top + glyphHeight * FIVE_MIDDLE_START_RATIO).toInt().coerceAtMost(bottom)
        val middleEnd = (top + glyphHeight * FIVE_MIDDLE_END_RATIO).toInt().coerceAtMost(bottom + 1)
        val leftStart = (top + glyphHeight * FIVE_LEFT_STEM_START_RATIO).toInt().coerceAtMost(bottom)
        val leftEnd = (top + glyphHeight * FIVE_LEFT_STEM_END_RATIO).toInt().coerceAtMost(bottom + 1)
        val lowerStart = (top + glyphHeight * FIVE_LOWER_START_RATIO).toInt().coerceAtMost(bottom)
        val leftStemEnd = (left + glyphWidth * FIVE_LEFT_REGION_RATIO).toInt().coerceAtMost(right + 1)
        val lowerRightStart = (left + glyphWidth * FIVE_LOWER_RIGHT_START_RATIO).toInt().coerceAtMost(right + 1)

        var topBar = 0
        for (y in top until topEnd) {
            var rowInk = 0
            for (x in left..right) if (ink[y * width + x]) rowInk++
            topBar = maxOf(topBar, rowInk)
        }

        var middleBar = 0
        for (y in middleStart until middleEnd) {
            var rowInk = 0
            for (x in left..right) if (ink[y * width + x]) rowInk++
            middleBar = maxOf(middleBar, rowInk)
        }

        val leftStemHeight = (left until leftStemEnd).maxOfOrNull { x ->
            (leftStart until leftEnd).count { y -> ink[y * width + x] }
        } ?: 0
        var lowerRightInk = 0
        for (y in lowerStart..bottom) {
            for (x in lowerRightStart..right) if (ink[y * width + x]) lowerRightInk++
        }
        val lowerRightArea =
            (right - lowerRightStart + 1).coerceAtLeast(1) * (bottom - lowerStart + 1).coerceAtLeast(1)

        return topBar.toDouble() / glyphWidth >= MIN_FIVE_TOP_BAR_RATIO &&
            middleBar.toDouble() / glyphWidth >= MIN_FIVE_MIDDLE_BAR_RATIO &&
            leftStemHeight.toDouble() / (leftEnd - leftStart).coerceAtLeast(1) >= MIN_FIVE_LEFT_STEM_RATIO &&
            lowerRightInk.toDouble() / lowerRightArea >= MIN_FIVE_LOWER_RIGHT_INK_RATIO
    }

    private fun shapeDistance(first: ShapeDescriptor, second: ShapeDescriptor): Float {
        var best = 1f
        for (shiftY in -1..1) {
            for (shiftX in -1..1) {
                var intersection = 0
                var firstInk = 0
                var secondInk = 0
                for (y in 0 until SHAPE_SIZE) {
                    for (x in 0 until SHAPE_SIZE) {
                        val firstSet = first.pixels[y * SHAPE_SIZE + x]
                        val secondX = x + shiftX
                        val secondY = y + shiftY
                        val secondSet = secondX in 0 until SHAPE_SIZE &&
                            secondY in 0 until SHAPE_SIZE &&
                            second.pixels[secondY * SHAPE_SIZE + secondX]
                        if (firstSet) firstInk++
                        if (secondSet) secondInk++
                        if (firstSet && secondSet) intersection++
                    }
                }
                val denominator = firstInk + secondInk
                val distance = if (denominator == 0) 1f else 1f - (2f * intersection / denominator)
                if (distance < best) best = distance
            }
        }
        return best
    }

    /**
     * The target game's open-top 4 is commonly returned as A or as no text by
     * both OCR engines. It has one centered triangular counter, a full-width
     * crossbar around 70% height, and a straight right stem that spans almost
     * the entire glyph. Those features distinguish it from A, 9, D, and B.
     */
    private fun looksLikeTopologicalFour(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val ink = BooleanArray(width * height) { index ->
            luminance(pixels[index]) < SHAPE_INK_LUMA
        }

        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!ink[y * width + x]) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        if (right < left || bottom < top) return false
        val glyphWidth = right - left + 1
        val glyphHeight = bottom - top + 1
        val glyphArea = glyphWidth * glyphHeight
        val minimumHoleArea = maxOf(MIN_FOUR_HOLE_PIXELS, (glyphArea * MIN_FOUR_HOLE_RATIO).toInt())

        val visited = BooleanArray(width * height)
        val queue = IntArray(glyphArea)
        var enclosedHoles = 0
        var enclosedHoleCenterYRatio = 1.0
        for (startY in top..bottom) {
            for (startX in left..right) {
                val startIndex = startY * width + startX
                if (ink[startIndex] || visited[startIndex]) continue
                var head = 0
                var tail = 0
                queue[tail++] = startIndex
                visited[startIndex] = true
                var componentArea = 0
                var componentYSum = 0L
                var touchesBoundary = false
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % width
                    val y = index / width
                    componentArea++
                    componentYSum += y
                    if (x == left || x == right || y == top || y == bottom) touchesBoundary = true

                    fun enqueue(nextX: Int, nextY: Int) {
                        if (nextX !in left..right || nextY !in top..bottom) return
                        val nextIndex = nextY * width + nextX
                        if (ink[nextIndex] || visited[nextIndex]) return
                        visited[nextIndex] = true
                        queue[tail++] = nextIndex
                    }
                    enqueue(x - 1, y)
                    enqueue(x + 1, y)
                    enqueue(x, y - 1)
                    enqueue(x, y + 1)
                }
                if (!touchesBoundary && componentArea >= minimumHoleArea) {
                    enclosedHoles++
                    enclosedHoleCenterYRatio =
                        ((componentYSum.toDouble() / componentArea) - top) / glyphHeight
                }
            }
        }

        var widestInkRow = top
        var widestInkSpan = 0
        for (y in top..bottom) {
            var rowLeft = right + 1
            var rowRight = left - 1
            for (x in left..right) {
                if (!ink[y * width + x]) continue
                rowLeft = minOf(rowLeft, x)
                rowRight = maxOf(rowRight, x)
            }
            val span = if (rowRight >= rowLeft) rowRight - rowLeft + 1 else 0
            if (span > widestInkSpan) {
                widestInkSpan = span
                widestInkRow = y
            }
        }

        val rightStemStart = left + (glyphWidth * FOUR_RIGHT_STEM_START_RATIO).toInt()
        var longestRightVerticalRun = 0
        for (x in rightStemStart.coerceAtMost(right)..right) {
            var currentRun = 0
            for (y in top..bottom) {
                if (ink[y * width + x]) {
                    currentRun++
                    longestRightVerticalRun = maxOf(longestRightVerticalRun, currentRun)
                } else {
                    currentRun = 0
                }
            }
        }

        return HexadokuFourTopologyPolicy.matches(
            enclosedHoleCount = enclosedHoles,
            holeCenterYRatio = enclosedHoleCenterYRatio,
            glyphWidthToHeightRatio = glyphWidth.toDouble() / glyphHeight,
            widestInkRowYRatio = (widestInkRow - top).toDouble() / glyphHeight,
            rightStemRunRatio = longestRightVerticalRun.toDouble() / glyphHeight
        )
    }

    /**
     * The target game's B glyph is consistently missed by both OCR engines.
     * It is safely identifiable in this symbol set by two enclosed counters
     * plus a nearly full-height left stem. Digit 8 also has two counters, but
     * its curved left edge does not form that continuous vertical run; D has
     * the stem but only one counter.
     */
    private fun looksLikeTopologicalB(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val ink = BooleanArray(width * height) { index ->
            luminance(pixels[index]) < SHAPE_INK_LUMA
        }

        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!ink[y * width + x]) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        if (right < left || bottom < top) return false
        val glyphWidth = right - left + 1
        val glyphHeight = bottom - top + 1
        val glyphArea = glyphWidth * glyphHeight
        val minimumHoleArea = maxOf(MIN_B_HOLE_PIXELS, (glyphArea * MIN_B_HOLE_RATIO).toInt())
        val visited = BooleanArray(width * height)
        val queue = IntArray(glyphArea)
        var enclosedHoles = 0

        for (startY in top..bottom) {
            for (startX in left..right) {
                val startIndex = startY * width + startX
                if (ink[startIndex] || visited[startIndex]) continue
                var head = 0
                var tail = 0
                queue[tail++] = startIndex
                visited[startIndex] = true
                var componentArea = 0
                var touchesBoundary = false
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % width
                    val y = index / width
                    componentArea++
                    if (x == left || x == right || y == top || y == bottom) touchesBoundary = true

                    fun enqueue(nextX: Int, nextY: Int) {
                        if (nextX !in left..right || nextY !in top..bottom) return
                        val nextIndex = nextY * width + nextX
                        if (ink[nextIndex] || visited[nextIndex]) return
                        visited[nextIndex] = true
                        queue[tail++] = nextIndex
                    }
                    enqueue(x - 1, y)
                    enqueue(x + 1, y)
                    enqueue(x, y - 1)
                    enqueue(x, y + 1)
                }
                if (!touchesBoundary && componentArea >= minimumHoleArea) enclosedHoles++
            }
        }
        if (enclosedHoles != 2) return false

        val leftSearchWidth = maxOf(1, (glyphWidth * B_LEFT_STEM_SEARCH_RATIO).toInt())
        var longestVerticalRun = 0
        for (x in left until minOf(right + 1, left + leftSearchWidth)) {
            var currentRun = 0
            for (y in top..bottom) {
                if (ink[y * width + x]) {
                    currentRun++
                    longestVerticalRun = maxOf(longestVerticalRun, currentRun)
                } else {
                    currentRun = 0
                }
            }
        }
        return longestVerticalRun >= glyphHeight * MIN_B_STEM_RATIO
    }

    /**
     * Digit 8 shares B's two enclosed counters, but its curved left edge has no
     * continuous full-height stem. This fallback covers blue user-entered 8s,
     * which both OCR engines can miss after a stopped input run.
     */
    private fun looksLikeTopologicalEight(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val ink = BooleanArray(width * height) { index ->
            luminance(pixels[index]) < SHAPE_INK_LUMA
        }

        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!ink[y * width + x]) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        if (right < left || bottom < top) return false
        val glyphWidth = right - left + 1
        val glyphHeight = bottom - top + 1
        val glyphArea = glyphWidth * glyphHeight
        val minimumHoleArea = maxOf(MIN_EIGHT_HOLE_PIXELS, (glyphArea * MIN_EIGHT_HOLE_RATIO).toInt())
        val visited = BooleanArray(width * height)
        val queue = IntArray(glyphArea)
        var enclosedHoles = 0

        for (startY in top..bottom) {
            for (startX in left..right) {
                val startIndex = startY * width + startX
                if (ink[startIndex] || visited[startIndex]) continue
                var head = 0
                var tail = 0
                queue[tail++] = startIndex
                visited[startIndex] = true
                var componentArea = 0
                var touchesBoundary = false
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % width
                    val y = index / width
                    componentArea++
                    if (x == left || x == right || y == top || y == bottom) touchesBoundary = true

                    fun enqueue(nextX: Int, nextY: Int) {
                        if (nextX !in left..right || nextY !in top..bottom) return
                        val nextIndex = nextY * width + nextX
                        if (ink[nextIndex] || visited[nextIndex]) return
                        visited[nextIndex] = true
                        queue[tail++] = nextIndex
                    }
                    enqueue(x - 1, y)
                    enqueue(x + 1, y)
                    enqueue(x, y - 1)
                    enqueue(x, y + 1)
                }
                if (!touchesBoundary && componentArea >= minimumHoleArea) enclosedHoles++
            }
        }

        val leftSearchWidth = maxOf(1, (glyphWidth * EIGHT_LEFT_STEM_SEARCH_RATIO).toInt())
        var longestLeftVerticalRun = 0
        for (x in left until minOf(right + 1, left + leftSearchWidth)) {
            var currentRun = 0
            for (y in top..bottom) {
                if (ink[y * width + x]) {
                    currentRun++
                    longestLeftVerticalRun = maxOf(longestLeftVerticalRun, currentRun)
                } else {
                    currentRun = 0
                }
            }
        }
        return HexadokuEightTopologyPolicy.matches(
            enclosedHoleCount = enclosedHoles,
            leftStemRunRatio = longestLeftVerticalRun.toDouble() / glyphHeight
        )
    }

    /** Distinguishes E from F using their shared left stem and horizontal bars. */
    private fun classifyTopologicalBarLetter(bitmap: Bitmap): HexadokuBarTopologyPolicy.Symbol? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val ink = BooleanArray(width * height) { index ->
            luminance(pixels[index]) < SHAPE_INK_LUMA
        }
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!ink[y * width + x]) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        if (right < left || bottom < top) return null
        val glyphWidth = right - left + 1
        val glyphHeight = bottom - top + 1
        val glyphArea = glyphWidth * glyphHeight
        val minimumHoleArea = maxOf(MIN_BAR_LETTER_HOLE_PIXELS, (glyphArea * MIN_BAR_LETTER_HOLE_RATIO).toInt())
        val visited = BooleanArray(width * height)
        val queue = IntArray(glyphArea)
        var enclosedHoles = 0
        for (startY in top..bottom) {
            for (startX in left..right) {
                val startIndex = startY * width + startX
                if (ink[startIndex] || visited[startIndex]) continue
                var head = 0
                var tail = 0
                queue[tail++] = startIndex
                visited[startIndex] = true
                var componentArea = 0
                var touchesBoundary = false
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % width
                    val y = index / width
                    componentArea++
                    if (x == left || x == right || y == top || y == bottom) touchesBoundary = true

                    fun enqueue(nextX: Int, nextY: Int) {
                        if (nextX !in left..right || nextY !in top..bottom) return
                        val nextIndex = nextY * width + nextX
                        if (ink[nextIndex] || visited[nextIndex]) return
                        visited[nextIndex] = true
                        queue[tail++] = nextIndex
                    }
                    enqueue(x - 1, y)
                    enqueue(x + 1, y)
                    enqueue(x, y - 1)
                    enqueue(x, y + 1)
                }
                if (!touchesBoundary && componentArea >= minimumHoleArea) enclosedHoles++
            }
        }

        val leftSearchWidth = maxOf(1, (glyphWidth * BAR_LETTER_LEFT_STEM_SEARCH_RATIO).toInt())
        var longestVerticalRun = 0
        for (x in left until minOf(right + 1, left + leftSearchWidth)) {
            var currentRun = 0
            for (y in top..bottom) {
                if (ink[y * width + x]) {
                    currentRun++
                    longestVerticalRun = maxOf(longestVerticalRun, currentRun)
                } else {
                    currentRun = 0
                }
            }
        }
        val densityLeft = left + (glyphWidth * BAR_LETTER_DENSITY_LEFT_RATIO).toInt()
        fun density(startY: Int, endY: Int): Float {
            val safeStartY = startY.coerceIn(top, bottom)
            val safeEndY = endY.coerceIn(safeStartY, bottom)
            var inkCount = 0
            var sampleCount = 0
            for (y in safeStartY..safeEndY) {
                for (x in densityLeft..right) {
                    sampleCount++
                    if (ink[y * width + x]) inkCount++
                }
            }
            return if (sampleCount == 0) 0f else inkCount.toFloat() / sampleCount
        }
        val topDensity = density(top, top + (glyphHeight * BAR_LETTER_TOP_BAND_END_RATIO).toInt())
        val middleDensity = density(
            top + (glyphHeight * BAR_LETTER_MIDDLE_BAND_START_RATIO).toInt(),
            top + (glyphHeight * BAR_LETTER_MIDDLE_BAND_END_RATIO).toInt()
        )
        val bottomDensity = density(
            bottom - (glyphHeight * BAR_LETTER_BOTTOM_BAND_RATIO).toInt(),
            bottom
        )
        return HexadokuBarTopologyPolicy.classify(
            enclosedHoleCount = enclosedHoles,
            leftStemRunRatio = longestVerticalRun.toDouble() / glyphHeight,
            topBarDensity = topDensity.toDouble(),
            middleBarDensity = middleDensity.toDouble(),
            bottomBarDensity = bottomDensity.toDouble()
        )
    }

    /**
     * The target game's 9 is usually blank in both OCR engines, and ML Kit can
     * occasionally call it 6. Its single counter sits high in the glyph while
     * the lower stroke is concentrated on the right. The combination excludes
     * 6, A, D, and the enclosed triangle sometimes produced by digit 4.
     */
    private fun looksLikeTopologicalNine(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val ink = BooleanArray(width * height) { index ->
            luminance(pixels[index]) < SHAPE_INK_LUMA
        }

        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!ink[y * width + x]) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        if (right < left || bottom < top) return false
        val glyphWidth = right - left + 1
        val glyphHeight = bottom - top + 1
        val glyphArea = glyphWidth * glyphHeight
        val minimumHoleArea = maxOf(MIN_NINE_HOLE_PIXELS, (glyphArea * MIN_NINE_HOLE_RATIO).toInt())
        val visited = BooleanArray(width * height)
        val queue = IntArray(glyphArea)
        var enclosedHoles = 0
        var enclosedHoleCenterYRatio = 1.0

        for (startY in top..bottom) {
            for (startX in left..right) {
                val startIndex = startY * width + startX
                if (ink[startIndex] || visited[startIndex]) continue
                var head = 0
                var tail = 0
                queue[tail++] = startIndex
                visited[startIndex] = true
                var componentArea = 0
                var componentYSum = 0L
                var touchesBoundary = false
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % width
                    val y = index / width
                    componentArea++
                    componentYSum += y
                    if (x == left || x == right || y == top || y == bottom) touchesBoundary = true

                    fun enqueue(nextX: Int, nextY: Int) {
                        if (nextX !in left..right || nextY !in top..bottom) return
                        val nextIndex = nextY * width + nextX
                        if (ink[nextIndex] || visited[nextIndex]) return
                        visited[nextIndex] = true
                        queue[tail++] = nextIndex
                    }
                    enqueue(x - 1, y)
                    enqueue(x + 1, y)
                    enqueue(x, y - 1)
                    enqueue(x, y + 1)
                }
                if (!touchesBoundary && componentArea >= minimumHoleArea) {
                    enclosedHoles++
                    enclosedHoleCenterYRatio =
                        ((componentYSum.toDouble() / componentArea) - top) / glyphHeight
                }
            }
        }

        val lowerStartY = top + (glyphHeight * NINE_LOWER_BAND_START_RATIO).toInt()
        val leftBandEndExclusive = left + maxOf(1, (glyphWidth * NINE_LEFT_BAND_RATIO).toInt())
        var lowerLeftInk = 0
        var lowerRightInk = 0
        for (y in lowerStartY..bottom) {
            for (x in left..right) {
                if (!ink[y * width + x]) continue
                if (x < leftBandEndExclusive) lowerLeftInk++ else lowerRightInk++
            }
        }
        return HexadokuNineTopologyPolicy.matches(
            enclosedHoleCount = enclosedHoles,
            holeCenterYRatio = enclosedHoleCenterYRatio,
            lowerLeftInk = lowerLeftInk,
            lowerRightInk = lowerRightInk
        )
    }

    private fun initialize() {
        try {
            val tessDataPath = File(context.filesDir, "tessdata")
            if (!tessDataPath.exists()) tessDataPath.mkdirs()
            val trainedDataFile = File(tessDataPath, "eng.traineddata")
            if (!trainedDataFile.exists()) {
                context.assets.open("tessdata/eng.traineddata").use { input ->
                    FileOutputStream(trainedDataFile).use(input::copyTo)
                }
            }

            tessApi = TessBaseAPI()
            isInitialized = tessApi?.init(context.filesDir.absolutePath, "eng") == true
            if (isInitialized) {
                tessApi?.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, HexadokuGrid.SYMBOLS)
                tessApi?.setVariable("classify_bln_numeric_mode", "0")
                tessApi?.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_CHAR
                Log.i(TAG, "16x16 OCR initialized for ${HexadokuGrid.SYMBOLS}")
            } else {
                Log.e(TAG, "Failed to initialize 16x16 OCR")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Error initializing 16x16 OCR", error)
            tessApi?.recycle()
            tessApi = null
            isInitialized = false
        }
    }

    private fun cellHasInk(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val histogram = IntArray(256)
        for (pixel in pixels) histogram[luminance(pixel)]++
        val background = histogram.indices.maxByOrNull { histogram[it] } ?: 255
        var backgroundRed = 0L
        var backgroundGreen = 0L
        var backgroundBlue = 0L
        var backgroundSamples = 0L
        for (pixel in pixels) {
            if (abs(luminance(pixel) - background) <= BACKGROUND_LUMA_TOLERANCE) {
                backgroundRed += Color.red(pixel)
                backgroundGreen += Color.green(pixel)
                backgroundBlue += Color.blue(pixel)
                backgroundSamples++
            }
        }
        val averageBackgroundRed = if (backgroundSamples > 0) (backgroundRed / backgroundSamples).toInt() else background
        val averageBackgroundGreen = if (backgroundSamples > 0) (backgroundGreen / backgroundSamples).toInt() else background
        val averageBackgroundBlue = if (backgroundSamples > 0) (backgroundBlue / backgroundSamples).toInt() else background

        var inkPixels = 0
        var activeRows = 0
        for (y in 0 until height) {
            var rowInk = 0
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val lumaDifference = abs(luminance(pixel) - background)
                val colorDistance =
                    abs(Color.red(pixel) - averageBackgroundRed) +
                        abs(Color.green(pixel) - averageBackgroundGreen) +
                        abs(Color.blue(pixel) - averageBackgroundBlue)
                if (lumaDifference >= MIN_LUMA_DIFFERENCE || colorDistance >= MIN_COLOR_DISTANCE) {
                    inkPixels++
                    rowInk++
                }
            }
            if (rowInk >= MIN_INK_PER_ACTIVE_ROW) activeRows++
        }

        val sampleCount = width * height
        return inkPixels >= maxOf(MIN_INK_PIXELS, (sampleCount * MIN_INK_RATIO).toInt()) &&
            activeRows >= MIN_ACTIVE_ROWS
    }

    private fun prepareForOcr(bitmap: Bitmap): Bitmap {
        val source = Mat()
        Utils.bitmapToMat(bitmap, source)
        val gray = Mat()
        Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
        val enlarged = Mat()
        Imgproc.resize(gray, enlarged, Size(OCR_IMAGE_SIZE.toDouble(), OCR_IMAGE_SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
        Imgproc.GaussianBlur(enlarged, enlarged, Size(3.0, 3.0), 0.0)
        val binary = Mat()
        Imgproc.threshold(enlarged, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        val bordered = Mat()
        Core.copyMakeBorder(binary, bordered, OCR_BORDER, OCR_BORDER, OCR_BORDER, OCR_BORDER, Core.BORDER_CONSTANT, Scalar(255.0))
        val result = Bitmap.createBitmap(bordered.cols(), bordered.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(bordered, result)
        source.release()
        gray.release()
        enlarged.release()
        binary.release()
        bordered.release()
        return result
    }

    private fun prepareForMlKit(bitmap: Bitmap): Bitmap {
        val innerSize = ML_KIT_IMAGE_SIZE - ML_KIT_BORDER * 2
        val scaled = Bitmap.createScaledBitmap(bitmap, innerSize, innerSize, true)
        val result = Bitmap.createBitmap(ML_KIT_IMAGE_SIZE, ML_KIT_IMAGE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(
            scaled,
            ML_KIT_BORDER.toFloat(),
            ML_KIT_BORDER.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        scaled.recycle()
        return result
    }

    private fun luminance(pixel: Int): Int =
        (0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)).toInt()

    companion object {
        private const val TAG = "HexadokuRecognizer"
        private const val CELL_MARGIN_RATIO = 0.12f
        private const val OCR_IMAGE_SIZE = 176
        private const val OCR_BORDER = 14
        private const val ML_KIT_IMAGE_SIZE = 256
        private const val ML_KIT_BORDER = 24
        private const val MIN_LUMA_DIFFERENCE = 38
        private const val BACKGROUND_LUMA_TOLERANCE = 3
        private const val MIN_COLOR_DISTANCE = 105
        private const val MIN_INK_RATIO = 0.012f
        private const val MIN_INK_PIXELS = 18
        private const val MIN_INK_PER_ACTIVE_ROW = 2
        private const val MIN_ACTIVE_ROWS = 5
        private const val LOW_CONFIDENCE_THRESHOLD = 45
        private const val DIAGNOSTICS_DIRECTORY = "hexadoku_ocr"
        private const val STRONG_TESSERACT_CONFIDENCE = 80
        private const val MIN_FIVE_ASPECT = 0.30
        private const val MAX_FIVE_ASPECT = 1.05
        private const val MIN_FIVE_HEIGHT = 12
        private const val FIVE_TOP_BAND_RATIO = 0.24
        private const val FIVE_MIDDLE_START_RATIO = 0.28
        private const val FIVE_MIDDLE_END_RATIO = 0.72
        private const val FIVE_LEFT_STEM_START_RATIO = 0.22
        private const val FIVE_LEFT_STEM_END_RATIO = 0.62
        private const val FIVE_LOWER_START_RATIO = 0.50
        private const val FIVE_LEFT_REGION_RATIO = 0.43
        private const val FIVE_LOWER_RIGHT_START_RATIO = 0.48
        private const val MIN_FIVE_TOP_BAR_RATIO = 0.62
        private const val MIN_FIVE_MIDDLE_BAR_RATIO = 0.48
        private const val MIN_FIVE_LEFT_STEM_RATIO = 0.35
        private const val MIN_FIVE_LOWER_RIGHT_INK_RATIO = 0.06
        private const val SHAPE_SIZE = 32
        private const val SHAPE_CONTENT_SIZE = 28
        private const val SHAPE_INK_LUMA = 128
        private const val MAX_SHAPE_DISTANCE = 0.30f
        private const val MIN_SHAPE_MARGIN = 0.035f
        private const val MAX_TEMPLATES_PER_SYMBOL = 4
        private const val MIN_FOUR_HOLE_PIXELS = 20
        private const val MIN_FOUR_HOLE_RATIO = 0.02f
        private const val FOUR_RIGHT_STEM_START_RATIO = 0.55f
        private const val MIN_B_HOLE_PIXELS = 20
        private const val MIN_B_HOLE_RATIO = 0.02f
        private const val B_LEFT_STEM_SEARCH_RATIO = 0.35f
        private const val MIN_B_STEM_RATIO = 0.85f
        private const val MIN_EIGHT_HOLE_PIXELS = 20
        private const val MIN_EIGHT_HOLE_RATIO = 0.02f
        private const val EIGHT_LEFT_STEM_SEARCH_RATIO = 0.35f
        private const val MIN_BAR_LETTER_HOLE_PIXELS = 20
        private const val MIN_BAR_LETTER_HOLE_RATIO = 0.02f
        private const val BAR_LETTER_LEFT_STEM_SEARCH_RATIO = 0.35f
        private const val BAR_LETTER_DENSITY_LEFT_RATIO = 0.30f
        private const val BAR_LETTER_TOP_BAND_END_RATIO = 0.20f
        private const val BAR_LETTER_MIDDLE_BAND_START_RATIO = 0.35f
        private const val BAR_LETTER_MIDDLE_BAND_END_RATIO = 0.65f
        private const val BAR_LETTER_BOTTOM_BAND_RATIO = 0.20f
        private const val MIN_NINE_HOLE_PIXELS = 20
        private const val MIN_NINE_HOLE_RATIO = 0.02f
        private const val NINE_LOWER_BAND_START_RATIO = 0.58f
        private const val NINE_LEFT_BAND_RATIO = 0.42f
    }

    private data class ShapeDescriptor(val pixels: BooleanArray)
}
