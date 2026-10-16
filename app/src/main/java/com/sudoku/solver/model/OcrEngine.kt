package com.sudoku.solver.model

/**
 * Available OCR engines for digit recognition
 */
enum class OcrEngine {
    ML_KIT,     // Google ML Kit Text Recognition
    TESSERACT,  // Tesseract OCR
    HYBRID      // Run both and use puzzle logic to resolve conflicts
}
