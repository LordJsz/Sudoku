package com.sudoku.solver.util

import com.sudoku.solver.model.OcrEngine

/**
 * Application-wide constants
 */
object Constants {
    // OCR Engine Selection - Change this to switch between ML Kit, Tesseract, or Hybrid
    val OCR_ENGINE = OcrEngine.HYBRID  // Options: OcrEngine.ML_KIT, OcrEngine.TESSERACT, or OcrEngine.HYBRID
    // Board detection
    const val WARPED_SIZE = 1404  // Divisible by 9 (1404/9 = 156)
    const val POLY_APPROX_EPSILON = 0.02
    const val GRID_SIZE = 9
    const val CELL_SIZE = WARPED_SIZE / GRID_SIZE  // = 156
    const val CELL_CROP_MARGIN = 0.14f // tighter inner crop to avoid grid-line bleed into OCR
    // BoardDetector already tightens to the inner 9x9 grid. Keep this small to avoid shifting edge cells.
    const val BOARD_INNER_CROP_PX = 6

    // OCR
    const val MIN_WHITE_PIXELS = 24  // Looser gate to recover thin/stylized digits while still filtering blanks
    const val OCR_CONFIDENCE_THRESHOLD = 0.5f

    // Solver
    const val SOLVER_TIMEOUT_MS = 5000L
    const val SKIP_OCR_VALIDATION = false

    // Auto-input
    const val TAP_DELAY_MS = 180L
    const val DIGIT_INPUT_DELAY_MS = 150L
    const val GESTURE_DURATION_MS = 100L

    // Screen capture
    const val SCREEN_CAPTURE_TIMEOUT_MS = 500L

    // Service notifications
    const val NOTIFICATION_CHANNEL_ID = "sudoku_solver_channel"
    const val NOTIFICATION_ID = 1001

    // Request codes
    const val REQUEST_MEDIA_PROJECTION = 1001
    const val REQUEST_ACCESSIBILITY = 1002
    const val REQUEST_OVERLAY_PERMISSION = 1003
}
