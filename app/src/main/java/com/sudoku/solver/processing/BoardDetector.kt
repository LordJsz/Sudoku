package com.sudoku.solver.processing

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import com.sudoku.solver.model.DetectedBoard
import com.sudoku.solver.util.Constants
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Detects Sudoku board in images using OpenCV
 * Ported from Python implementation in a.py
 */
class BoardDetector {

    /**
     * Detect Sudoku board from a screenshot
     * @param screenshot The screen capture bitmap
     * @return DetectedBoard or null if no board found
     */
    fun detectBoard(screenshot: Bitmap): DetectedBoard? {
        // Convert bitmap to OpenCV Mat
        val mat = Mat()
        Utils.bitmapToMat(screenshot, mat)

        // Find the Sudoku quadrilateral
        val lineQuad = findScreenGrid(mat)
        val quad = lineQuad ?: findSudokuQuad(mat)
        if (quad == null) {
            Log.w(TAG, "No Sudoku board detected")
            mat.release()
            return null
        }

        // Calculate screen bounds from quad points
        val screenBounds = calculateBounds(quad, screenshot.width, screenshot.height)
        Log.i(
            TAG,
            "Detected board bounds: " +
                "left=${screenBounds.left}, top=${screenBounds.top}, " +
                "right=${screenBounds.right}, bottom=${screenBounds.bottom}, " +
                "size=${screenBounds.width()}x${screenBounds.height()}"
        )

        // Perform perspective correction, then tighten to the inner 9x9 grid
        val warpedMatRaw = fourPointWarp(mat, quad)
        // A line-validated screen grid is already exact. Cropping only its bitmap
        // would make OCR cells disagree with the screen coordinates used for taps.
        val warpedMat = if (lineQuad != null) warpedMatRaw.clone() else tightenWarpToGrid(warpedMatRaw)
        val warpedBitmap = Bitmap.createBitmap(
            Constants.WARPED_SIZE,
            Constants.WARPED_SIZE,
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(warpedMat, warpedBitmap)
        Log.i(TAG, "Warped board generated: ${warpedBitmap.width}x${warpedBitmap.height}")

        // Convert quad points to Android Point objects
        val cornerPoints = quad.toArray().map {
            Point(it.x.toInt(), it.y.toInt())
        }

        // Clean up
        mat.release()
        warpedMatRaw.release()
        warpedMat.release()
        quad.release()

        return DetectedBoard(warpedBitmap, cornerPoints, screenBounds)
    }

    private fun findScreenGrid(image: Mat): MatOfPoint2f? {
        val gray = Mat()
        val binary = Mat()
        val horizontal = Mat()
        val vertical = Mat()
        val rows = Mat()
        val cols = Mat()
        val shortSide = min(image.width(), image.height()).toDouble()
        val lineKernelLength = max(12.0, shortSide * SCREEN_GRID_KERNEL_RATIO)
        val minimumLineCoverage = max(8.0, shortSide * SCREEN_GRID_LINE_COVERAGE_RATIO)
        val minimumBoardSide = max(48.0, shortSide * SCREEN_GRID_MIN_BOARD_RATIO)
        val minimumCellStep = max(5.0, shortSide * SCREEN_GRID_MIN_CELL_STEP_RATIO)
        val hKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(lineKernelLength, 1.0))
        val vKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, lineKernelLength))
        try {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.adaptiveThreshold(gray, binary, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C,
                Imgproc.THRESH_BINARY_INV, 31, 5.0)
            Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, hKernel)
            Imgproc.morphologyEx(binary, vertical, Imgproc.MORPH_OPEN, vKernel)
            Core.reduce(horizontal, rows, 1, Core.REDUCE_SUM, CvType.CV_32S)
            Core.reduce(vertical, cols, 0, Core.REDUCE_SUM, CvType.CV_32S)
            val rowValues = DoubleArray(rows.rows()) { rows.get(it, 0)[0] / 255.0 }
            val colValues = DoubleArray(cols.cols()) { cols.get(0, it)[0] / 255.0 }
            // A 16x16 board also contains 9-cell subgrids. Validate the full
            // 16-cell span first so a top-left fragment cannot win as a 9x9.
            for (cells in listOf(16, 9)) {
                val rowSpans = GridLineGeometry.spans(rowValues, minimumLineCoverage, cells, minimumCellStep)
                val colSpans = GridLineGeometry.spans(colValues, minimumLineCoverage, cells, minimumCellStep)
                for (y in rowSpans.sortedByDescending { it.last - it.first }) {
                    for (x in colSpans.sortedByDescending { it.last - it.first }) {
                        val width = x.last - x.first
                        val height = y.last - y.first
                        if (width < minimumBoardSide || height < minimumBoardSide) continue
                        if (abs(width - height) > max(width, height) * 0.035) continue
                        if (!hasCompleteGridLines(horizontal, vertical, x, y, cells)) continue
                        Log.i(TAG, "Validated $cells x $cells screen grid: $x, $y")
                        return MatOfPoint2f(
                            org.opencv.core.Point(x.first.toDouble(), y.first.toDouble()),
                            org.opencv.core.Point(x.last.toDouble(), y.first.toDouble()),
                            org.opencv.core.Point(x.last.toDouble(), y.last.toDouble()),
                            org.opencv.core.Point(x.first.toDouble(), y.last.toDouble())
                        )
                    }
                }
            }
            return null
        } finally {
            listOf(gray, binary, horizontal, vertical, rows, cols, hKernel, vKernel).forEach { it.release() }
        }
    }

    /** Projection peaks from buttons or glyphs must not extend the actual grid. */
    private fun hasCompleteGridLines(
        horizontal: Mat, vertical: Mat, x: IntRange, y: IntRange, cells: Int
    ): Boolean {
        fun coversLine(source: Mat, roi: org.opencv.core.Rect, dimension: Int, length: Int): Boolean {
            val strip = source.submat(roi)
            val coverage = Mat()
            return try {
                Core.reduce(strip, coverage, dimension, Core.REDUCE_MAX, CvType.CV_8U)
                Core.countNonZero(coverage) >= length * 0.80
            } finally { strip.release(); coverage.release() }
        }
        val width = x.last - x.first
        val height = y.last - y.first
        val xTolerance = max(2, (width.toDouble() / cells * 0.055).toInt())
        val yTolerance = max(2, (height.toDouble() / cells * 0.055).toInt())
        for (line in 0..cells) {
            val row = y.first + (height.toDouble() * line / cells).toInt()
            val top = max(0, row - yTolerance)
            val bottom = min(horizontal.rows(), row + yTolerance + 1)
            if (!coversLine(horizontal, org.opencv.core.Rect(x.first, top, width, bottom - top), 0, width)) return false
            val col = x.first + (width.toDouble() * line / cells).toInt()
            val left = max(0, col - xTolerance)
            val right = min(vertical.cols(), col + xTolerance + 1)
            if (!coversLine(vertical, org.opencv.core.Rect(left, y.first, right - left, height), 1, height)) return false
        }
        return true
    }

    /**
     * Find the Sudoku board quadrilateral in the image
     * Ported from find_sudoku_quad() in a.py (lines 80-105)
     */
    private fun findSudokuQuad(image: Mat): MatOfPoint2f? {
        // Convert to grayscale
        val gray = Mat()
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGBA2GRAY)

        // Gaussian blur
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(7.0, 7.0), 0.0)

        // Adaptive threshold
        val thresh = Mat()
        Imgproc.adaptiveThreshold(
            blur, thresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            11, 2.0
        )

        // Morphological closing to fill gaps
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val closed = Mat()
        Imgproc.morphologyEx(
            thresh, closed, Imgproc.MORPH_CLOSE, kernel,
            Point(-1.0, -1.0), 2
        )

        // Find contours
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            closed, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )

        if (contours.isEmpty()) {
            Log.w(TAG, "No contours found")
            gray.release()
            blur.release()
            thresh.release()
            closed.release()
            hierarchy.release()
            return null
        }

        // Sort by area (largest first) and score the best Sudoku-like quad.
        contours.sortByDescending { Imgproc.contourArea(it) }
        val imageArea = image.width().toDouble() * image.height().toDouble()
        val minimumContourArea = ResponsiveGeometryPolicy.minimumBoardContourArea(
            image.width(),
            image.height()
        )
        var bestQuad: MatOfPoint2f? = null
        var bestScore = Double.NEGATIVE_INFINITY

        // Try to find 4-sided polygons with sufficient area and pick the best by score.
        for (contour in contours.take(12)) {
            val area = Imgproc.contourArea(contour)
            if (area < minimumContourArea) {
                continue
            }

            // Approximate polygon
            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(
                contour2f,
                approx,
                Constants.POLY_APPROX_EPSILON * peri,
                true
            )
            contour2f.release()

            // Check if it's a quadrilateral
            if (approx.rows() == 4) {
                val ordered = orderPoints(approx)
                val score = scoreSudokuCandidate(gray, ordered, area, imageArea)
                Log.d(TAG, "Quad candidate area=$area score=$score")

                if (score > bestScore) {
                    bestQuad?.release()
                    bestQuad = ordered
                    bestScore = score
                } else {
                    ordered.release()
                }
            }
            approx.release()
        }

        // Clean up
        gray.release()
        blur.release()
        thresh.release()
        closed.release()
        hierarchy.release()

        if (bestQuad == null) {
            Log.w(TAG, "No 4-sided polygon found")
            return null
        }

        if (bestScore < MIN_QUAD_SCORE) {
            Log.w(TAG, "Best quad score too low: $bestScore")
            bestQuad.release()
            return null
        }

        Log.i(TAG, "Selected Sudoku quad with score=$bestScore")
        return bestQuad
    }

    /**
     * Order points in consistent order: TL, TR, BR, BL
     * Ported from order_points() in a.py (lines 32-40)
     */
    private fun orderPoints(pts: MatOfPoint2f): MatOfPoint2f {
        val points = pts.toArray()

        // Calculate sums and diffs
        val sums = points.map { it.x + it.y }
        val diffs = points.map { it.y - it.x }

        // Find corners
        val tl = points[sums.indexOf(sums.minOrNull()!!)]  // top-left has smallest sum
        val br = points[sums.indexOf(sums.maxOrNull()!!)]  // bottom-right has largest sum
        val tr = points[diffs.indexOf(diffs.minOrNull()!!)] // top-right has smallest diff
        val bl = points[diffs.indexOf(diffs.maxOrNull()!!)] // bottom-left has largest diff

        return MatOfPoint2f(tl, tr, br, bl)
    }

    /**
     * Perform perspective transformation to get top-down view
     * Ported from four_point_warp() in a.py (lines 42-47)
     */
    private fun fourPointWarp(image: Mat, quad: MatOfPoint2f): Mat {
        val size = Constants.WARPED_SIZE.toDouble()

        // Destination points for a square
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(size - 1, 0.0),
            Point(size - 1, size - 1),
            Point(0.0, size - 1)
        )

        // Get perspective transform matrix
        val transform = Imgproc.getPerspectiveTransform(quad, dst)

        // Apply transformation
        val warped = Mat()
        Imgproc.warpPerspective(
            image, warped, transform,
            Size(size, size)
        )

        transform.release()
        dst.release()

        return warped
    }

    /**
     * Score how likely a quadrilateral is to be a Sudoku board.
     * Higher is better.
     */
    private fun scoreSudokuCandidate(grayImage: Mat, quad: MatOfPoint2f, area: Double, imageArea: Double): Double {
        val pts = quad.toArray()
        val top = hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y)
        val right = hypot(pts[1].x - pts[2].x, pts[1].y - pts[2].y)
        val bottom = hypot(pts[2].x - pts[3].x, pts[2].y - pts[3].y)
        val left = hypot(pts[3].x - pts[0].x, pts[3].y - pts[0].y)
        val avgWidth = (top + bottom) / 2.0
        val avgHeight = (left + right) / 2.0
        val aspect = if (avgHeight <= 1e-6) Double.POSITIVE_INFINITY else avgWidth / avgHeight

        // A Sudoku grid is square. Without a hard aspect-ratio guard, the row of
        // keypad buttons can win on line density and get stretched into a square
        // before OCR, making every button edge look like an occupied grid cell.
        if (aspect !in MIN_BOARD_ASPECT..MAX_BOARD_ASPECT) {
            Log.d(TAG, "Rejecting non-square board candidate with aspect=$aspect")
            return Double.NEGATIVE_INFINITY
        }

        val previewSize = CANDIDATE_PREVIEW_SIZE.toDouble()
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(previewSize - 1, 0.0),
            Point(previewSize - 1, previewSize - 1),
            Point(0.0, previewSize - 1)
        )
        val transform = Imgproc.getPerspectiveTransform(quad, dst)
        val warped = Mat()
        Imgproc.warpPerspective(grayImage, warped, transform, Size(previewSize, previewSize))

        val thresh = Mat()
        Imgproc.adaptiveThreshold(
            warped, thresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            31, 7.0
        )

        val hKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size((CANDIDATE_PREVIEW_SIZE / 12.0).coerceAtLeast(12.0), 1.0)
        )
        val vKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(1.0, (CANDIDATE_PREVIEW_SIZE / 12.0).coerceAtLeast(12.0))
        )
        val hLines = Mat()
        val vLines = Mat()
        Imgproc.morphologyEx(thresh, hLines, Imgproc.MORPH_OPEN, hKernel)
        Imgproc.morphologyEx(thresh, vLines, Imgproc.MORPH_OPEN, vKernel)

        val linePixels = Core.countNonZero(hLines).toDouble() + Core.countNonZero(vLines).toDouble()
        val lineDensity = linePixels / (2.0 * CANDIDATE_PREVIEW_SIZE * CANDIDATE_PREVIEW_SIZE)

        val squareScore = max(0.0, 1.0 - abs(1.0 - aspect))
        val areaRatio = (area / imageArea).coerceIn(0.0, 1.0)

        // Weighted score:
        // - lineDensity dominates (Sudoku grid has lots of long lines)
        // - areaRatio and squareScore are secondary tie-breakers
        val score = lineDensity * 1000.0 + areaRatio * 120.0 + squareScore * 40.0

        dst.release()
        transform.release()
        warped.release()
        thresh.release()
        hKernel.release()
        vKernel.release()
        hLines.release()
        vLines.release()

        return score
    }

    /**
     * Tighten the warped image to the inner 9x9 grid by projecting detected
     * horizontal/vertical line density and cropping to the strongest line span.
     */
    private fun tightenWarpToGrid(warped: Mat): Mat {
        val gray = Mat()
        if (warped.channels() == 1) {
            warped.copyTo(gray)
        } else {
            Imgproc.cvtColor(warped, gray, Imgproc.COLOR_RGBA2GRAY)
        }

        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), 0.0)

        val thresh = Mat()
        Imgproc.adaptiveThreshold(
            blur, thresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            31, 7.0
        )

        val hKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size((warped.width() / 12.0).coerceAtLeast(12.0), 1.0)
        )
        val vKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(1.0, (warped.height() / 12.0).coerceAtLeast(12.0))
        )
        val hLines = Mat()
        val vLines = Mat()
        Imgproc.morphologyEx(thresh, hLines, Imgproc.MORPH_OPEN, hKernel)
        Imgproc.morphologyEx(thresh, vLines, Imgproc.MORPH_OPEN, vKernel)

        val lines = Mat()
        Core.bitwise_or(hLines, vLines, lines)

        val rowProjection = Mat()
        val colProjection = Mat()
        Core.reduce(lines, rowProjection, 1, Core.REDUCE_SUM, CvType.CV_32S)
        Core.reduce(lines, colProjection, 0, Core.REDUCE_SUM, CvType.CV_32S)

        val rowMax = Core.minMaxLoc(rowProjection).maxVal
        val colMax = Core.minMaxLoc(colProjection).maxVal

        val rowRange = if (rowMax > 0.0) findProjectionBounds(rowProjection, rowMax * GRID_LINE_PROJECTION_RATIO, true) else null
        val colRange = if (colMax > 0.0) findProjectionBounds(colProjection, colMax * GRID_LINE_PROJECTION_RATIO, false) else null

        var tightened: Mat? = null
        if (rowRange != null && colRange != null) {
            val pad = max(2, (warped.width() * 0.01).toInt())
            var top = max(0, rowRange.first - pad)
            var bottom = min(warped.height() - 1, rowRange.last + pad)
            var left = max(0, colRange.first - pad)
            var right = min(warped.width() - 1, colRange.last + pad)

            var spanW = right - left + 1
            var spanH = bottom - top + 1
            val minSpan = (warped.width() * MIN_TIGHTEN_SPAN_RATIO).toInt()

            if (spanW >= minSpan && spanH >= minSpan) {
                val size = min(spanW, spanH)
                left += (spanW - size) / 2
                top += (spanH - size) / 2
                left = left.coerceIn(0, warped.width() - size)
                top = top.coerceIn(0, warped.height() - size)
                spanW = size
                spanH = size

                if (spanW > 1 && spanH > 1) {
                    val roi = org.opencv.core.Rect(left, top, spanW, spanH)
                    val cropped = Mat(warped, roi).clone()
                    tightened = Mat()
                    Imgproc.resize(
                        cropped,
                        tightened,
                        Size(Constants.WARPED_SIZE.toDouble(), Constants.WARPED_SIZE.toDouble()),
                        0.0,
                        0.0,
                        Imgproc.INTER_CUBIC
                    )
                    Log.i(
                        TAG,
                        "Tightened warped grid ROI: left=$left top=$top size=${spanW}x${spanH}"
                    )
                    cropped.release()
                }
            }
        }

        val output = if (tightened == null) {
            Log.w(TAG, "Grid tightening fallback: using original warp")
            warped.clone()
        } else {
            tightened
        }

        gray.release()
        blur.release()
        thresh.release()
        hKernel.release()
        vKernel.release()
        hLines.release()
        vLines.release()
        lines.release()
        rowProjection.release()
        colProjection.release()

        return output
    }

    private fun findProjectionBounds(projection: Mat, threshold: Double, vertical: Boolean): IntRange? {
        val length = if (vertical) projection.rows() else projection.cols()
        var first = -1
        var last = -1

        for (i in 0 until length) {
            val value = if (vertical) {
                projection.get(i, 0)?.firstOrNull() ?: 0.0
            } else {
                projection.get(0, i)?.firstOrNull() ?: 0.0
            }
            if (value >= threshold) {
                if (first < 0) first = i
                last = i
            }
        }

        return if (first >= 0 && last >= first) first..last else null
    }

    /**
     * Calculate bounding rectangle from quad points
     */
    private fun calculateBounds(quad: MatOfPoint2f, screenWidth: Int, screenHeight: Int): Rect {
        val points = quad.toArray()
        val xs = points.map { it.x.toInt() }
        val ys = points.map { it.y.toInt() }

        val left = (xs.minOrNull() ?: 0).coerceIn(0, (screenWidth - 1).coerceAtLeast(0))
        val top = (ys.minOrNull() ?: 0).coerceIn(0, (screenHeight - 1).coerceAtLeast(0))
        // Android Rect uses an exclusive right/bottom edge. Clamp the detected
        // inclusive contour point after advancing it by one physical pixel.
        val right = ((xs.maxOrNull() ?: left) + 1).coerceIn(left + 1, screenWidth.coerceAtLeast(left + 1))
        val bottom = ((ys.maxOrNull() ?: top) + 1).coerceIn(top + 1, screenHeight.coerceAtLeast(top + 1))

        return Rect(left, top, right, bottom)
    }

    /**
     * Preprocess warped board for cell extraction
     * Removes grid lines to improve OCR
     * Ported from preprocess_for_cells() in a.py (lines 107-123)
     */
    fun preprocessForCells(warpedBitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(warpedBitmap, mat)

        // Convert to grayscale
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

        // Gaussian blur
        val blur = Mat()
        Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), 0.0)

        // Adaptive threshold
        val thresh = Mat()
        Imgproc.adaptiveThreshold(
            blur, thresh, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            31, 7.0
        )

        // Remove horizontal lines
        val hKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size((Constants.WARPED_SIZE / 12).toDouble(), 1.0)
        )
        val hLines = Mat()
        Imgproc.morphologyEx(thresh, hLines, Imgproc.MORPH_OPEN, hKernel, Point(-1.0, -1.0), 1)

        // Remove vertical lines
        val vKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(1.0, (Constants.WARPED_SIZE / 12).toDouble())
        )
        val vLines = Mat()
        Imgproc.morphologyEx(thresh, vLines, Imgproc.MORPH_OPEN, vKernel, Point(-1.0, -1.0), 1)

        // Combine grid lines
        val gridLines = Mat()
        Core.bitwise_or(hLines, vLines, gridLines)

        // Remove grid lines from threshold image
        val gridLinesInv = Mat()
        Core.bitwise_not(gridLines, gridLinesInv)
        val thNoLines = Mat()
        Core.bitwise_and(thresh, gridLinesInv, thNoLines)

        // Convert back to bitmap
        val result = Bitmap.createBitmap(
            Constants.WARPED_SIZE,
            Constants.WARPED_SIZE,
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(thNoLines, result)

        // Clean up
        mat.release()
        gray.release()
        blur.release()
        thresh.release()
        hLines.release()
        vLines.release()
        gridLines.release()
        gridLinesInv.release()
        thNoLines.release()

        return result
    }

    companion object {
        private const val TAG = "BoardDetector"
        private const val SCREEN_GRID_KERNEL_RATIO = 0.10
        private const val SCREEN_GRID_LINE_COVERAGE_RATIO = 0.14
        private const val SCREEN_GRID_MIN_BOARD_RATIO = 0.18
        private const val SCREEN_GRID_MIN_CELL_STEP_RATIO = 0.008
        private const val CANDIDATE_PREVIEW_SIZE = 360
        private const val MIN_QUAD_SCORE = 70.0
        private const val MIN_BOARD_ASPECT = 0.72
        private const val MAX_BOARD_ASPECT = 1.38
        private const val GRID_LINE_PROJECTION_RATIO = 0.35
        private const val MIN_TIGHTEN_SPAN_RATIO = 0.60
    }
}
