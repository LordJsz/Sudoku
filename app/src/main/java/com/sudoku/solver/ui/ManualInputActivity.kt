package com.sudoku.solver.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sudoku.solver.R
import com.sudoku.solver.model.SudokuGrid
import com.sudoku.solver.processing.BoardDetector
import com.sudoku.solver.processing.DigitRecognizer
import com.sudoku.solver.processing.SudokuSolver
import com.sudoku.solver.processing.ValidationStatus
import com.sudoku.solver.service.ScreenCaptureService
import com.sudoku.solver.service.SudokuAccessibilityService
import com.sudoku.solver.util.OcrScanAreaPrefs
import kotlinx.coroutines.*

class ManualInputActivity : AppCompatActivity() {

    private lateinit var gridLayout: GridLayout
    private lateinit var gridContainer: FrameLayout
    private lateinit var solveButton: Button
    private lateinit var clearButton: Button
    private lateinit var scanButton: Button
    private val cells = Array(9) { arrayOfNulls<EditText>(9) }
    private val solver = SudokuSolver()
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var highlightedDigit: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual_input)

        gridLayout = findViewById(R.id.sudokuGrid)
        gridContainer = findViewById(R.id.gridContainer)
        solveButton = findViewById(R.id.solveButton)
        clearButton = findViewById(R.id.clearButton)
        scanButton = findViewById(R.id.scanButton)

        createGrid()
        setupButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun createGrid() {
        for (row in 0..8) {
            for (col in 0..8) {
                val editText = EditText(this)

                val params = GridLayout.LayoutParams()
                // The final square size is calculated after the tablet has laid
                // out the available space. A tiny initial size avoids measuring
                // the grid from the physical screen width in landscape.
                params.width = 1
                params.height = 1
                // Each cell already receives an exact square size below. Grid
                // weights would redistribute that size a second time and make
                // the first cell consume the whole board on Fire OS GridLayout.
                params.rowSpec = GridLayout.spec(row)
                params.columnSpec = GridLayout.spec(col)
                params.setMargins(1, 1, 1, 1)

                editText.layoutParams = params
                editText.gravity = Gravity.CENTER
                editText.textSize = 20f
                editText.typeface = Typeface.MONOSPACE
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.filters = arrayOf(InputFilter.LengthFilter(1), DigitFilter())
                editText.setPadding(2, 2, 2, 2)
                editText.isSingleLine = true
                editText.imeOptions = EditorInfo.IME_ACTION_NEXT

                editText.setOnClickListener {
                    updateHighlightedDigitFromCell(row, col)
                }
                editText.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        updateHighlightedDigitFromCell(row, col)
                    }
                }
                editText.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        if (highlightedDigit != null) {
                            refreshCellHighlights()
                        }
                    }
                })

                editText.background = createCellBackground(row, col, isHighlighted = false)

                cells[row][col] = editText
                gridLayout.addView(editText)
            }
        }

        gridContainer.post { resizeGridToAvailableSpace() }
    }

    private fun resizeGridToAvailableSpace() {
        val availableWidth = gridContainer.width - gridContainer.paddingLeft - gridContainer.paddingRight
        val availableHeight = gridContainer.height - gridContainer.paddingTop - gridContainer.paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0) {
            gridContainer.post { resizeGridToAvailableSpace() }
            return
        }

        val configuredMaximum = resources.getDimensionPixelSize(R.dimen.manual_grid_max_size)
        val gridSize = minOf(availableWidth, availableHeight, configuredMaximum)
        val cellSize = (gridSize / 9).coerceAtLeast(1)
        val fittedGridSize = cellSize * 9

        gridLayout.layoutParams = (gridLayout.layoutParams as FrameLayout.LayoutParams).apply {
            width = fittedGridSize
            height = fittedGridSize
            gravity = Gravity.CENTER
        }

        for (row in 0..8) {
            for (col in 0..8) {
                val cell = cells[row][col] ?: continue
                cell.layoutParams = (cell.layoutParams as GridLayout.LayoutParams).apply {
                    width = cellSize
                    height = cellSize
                }
            }
        }
        gridLayout.requestLayout()
    }

    private fun createCellBackground(row: Int, col: Int, isHighlighted: Boolean): android.graphics.drawable.Drawable {
        val boxIndex = (row / 3) * 3 + (col / 3)
        val defaultColor = if (boxIndex % 2 == 1) Color.parseColor("#E8E8E8") else Color.WHITE
        val fillColor = if (isHighlighted) Color.parseColor("#D6ECFF") else defaultColor
        val shape = android.graphics.drawable.GradientDrawable()
        shape.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        shape.setColor(fillColor)
        shape.setStroke(1, Color.parseColor("#CCCCCC"))
        return shape
    }

    private fun updateHighlightedDigitFromCell(row: Int, col: Int) {
        val touchedDigit = cells[row][col]?.text?.toString()?.toIntOrNull()
        highlightedDigit = touchedDigit?.takeIf { it in 1..9 }
        refreshCellHighlights()
    }

    private fun refreshCellHighlights() {
        for (row in 0..8) {
            for (col in 0..8) {
                val cell = cells[row][col] ?: continue
                val cellDigit = cell.text?.toString()?.toIntOrNull()
                val isHighlighted = highlightedDigit != null && cellDigit == highlightedDigit
                cell.background = createCellBackground(row, col, isHighlighted)
            }
        }
    }

    private fun setupButtons() {
        clearButton.setOnClickListener {
            for (row in 0..8) {
                for (col in 0..8) {
                    cells[row][col]?.setText("")
                    cells[row][col]?.setTextColor(Color.BLACK)
                    cells[row][col]?.hint = null
                }
            }
            highlightedDigit = null
            refreshCellHighlights()
        }

        solveButton.setOnClickListener {
            solveAndAutoInput()
        }

        scanButton.setOnClickListener {
            scanPuzzle()
        }
    }

    private var scannedGrid: Array<IntArray>? = null

    override fun onResume() {
        super.onResume()
        scannedGrid?.let { grid ->
            for (row in 0..8) {
                for (col in 0..8) {
                    val value = grid[row][col]
                    if (value in 1..9) {
                        cells[row][col]?.setText(value.toString())
                        cells[row][col]?.setTextColor(Color.BLUE)
                    } else {
                        cells[row][col]?.setText("")
                        cells[row][col]?.setTextColor(Color.BLACK)
                    }
                }
            }
            val clueCount = grid.sumOf { r -> r.count { it != 0 } }
            Toast.makeText(this, "Scanned $clueCount digits. Verify and press SOLVE.", Toast.LENGTH_LONG).show()
            scannedGrid = null
            refreshCellHighlights()
        }
    }

    private fun scanPuzzle() {
        val captureService = ScreenCaptureService.instance
        if (captureService == null) {
            Toast.makeText(this, "Screen capture not running. Start services first.", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "Minimizing... Open Sudoku app and wait 3 seconds", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)

        GlobalScope.launch(Dispatchers.IO) {
            delay(3000)
            performScanInBackground()
        }
    }

    private suspend fun performScanInBackground() {
        try {
            val captureService = ScreenCaptureService.instance ?: return
            val screenshot = captureService.captureScreen() ?: return

            val boardDetector = BoardDetector()
            val configuredArea = OcrScanAreaPrefs.toAbsoluteRect(this@ManualInputActivity, screenshot.width, screenshot.height)
            val detectedBoard = if (configuredArea != null) {
                val cropped = Bitmap.createBitmap(
                    screenshot,
                    configuredArea.left,
                    configuredArea.top,
                    configuredArea.width(),
                    configuredArea.height()
                )
                try {
                    boardDetector.detectBoard(cropped)
                } finally {
                    cropped.recycle()
                }
            } else {
                boardDetector.detectBoard(screenshot)
            }
            screenshot.recycle()

            if (detectedBoard == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "No board detected", Toast.LENGTH_SHORT).show()
                }
                return
            }

            val digitRecognizer = DigitRecognizer(applicationContext)
            val ocrResult = try {
                digitRecognizer.extractGridWithUncertainty(detectedBoard.warpedBitmap)
            } finally {
                digitRecognizer.close()
                detectedBoard.recycle()
            }

            scannedGrid = ocrResult.grid.values

            withContext(Dispatchers.Main) {
                val intent = android.content.Intent(applicationContext, ManualInputActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                startActivity(intent)
            }

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "Scan error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun solveAndAutoInput() {
        // Reset all cell colors first
        for (row in 0..8) {
            for (col in 0..8) {
                val text = cells[row][col]?.text?.toString() ?: ""
                if (text.isNotEmpty()) {
                    cells[row][col]?.setTextColor(Color.BLUE)
                }
                cells[row][col]?.hint = null
            }
        }

        // Read grid from input
        val values = Array(9) { row ->
            IntArray(9) { col ->
                val text = cells[row][col]?.text?.toString() ?: ""
                if (text.isEmpty() || text == "0") 0 else text.toIntOrNull() ?: 0
            }
        }

        val locked = Array(9) { row ->
            BooleanArray(9) { col -> values[row][col] != 0 }
        }

        val grid = SudokuGrid(values, locked)
        val clueCount = values.sumOf { row -> row.count { it != 0 } }

        if (clueCount < 10) {
            Toast.makeText(this, "Please enter at least 10 clues", Toast.LENGTH_SHORT).show()
            return
        }

        // Validate and solve
        val result = solver.validateAndSolve(grid)

        // Highlight problem cells in RED
        for ((row, col) in result.problemCells) {
            cells[row][col]?.setTextColor(Color.RED)
            if (cells[row][col]?.text?.isEmpty() == true) {
                cells[row][col]?.hint = "?"
            }
        }

        // Highlight the recommended disambiguation cell (when available) in orange.
        val keyCell = result.keyCellRecommendation
        if (result.status == ValidationStatus.MULTIPLE_SOLUTIONS && keyCell != null) {
            cells[keyCell.row][keyCell.col]?.setTextColor(Color.parseColor("#CC6600"))
            if (cells[keyCell.row][keyCell.col]?.text?.isEmpty() == true) {
                cells[keyCell.row][keyCell.col]?.hint = "K"
            }
        }

        when (result.status) {
            ValidationStatus.SUCCESS -> {
                // If there were auto-corrections, update the UI to show them
                if (result.corrections.isNotEmpty()) {
                    for (correction in result.corrections) {
                        cells[correction.row][correction.col]?.setText(correction.newValue.toString())
                        cells[correction.row][correction.col]?.setTextColor(Color.parseColor("#008800"))  // Dark green
                    }
                }

                Toast.makeText(this, result.message + " Switch to Sudoku app...", Toast.LENGTH_LONG).show()

                // Use corrected grid if available, otherwise original
                val gridToUse = if (result.correctedGrid != null) {
                    val correctedLocked = Array(9) { row ->
                        BooleanArray(9) { col -> result.correctedGrid[row][col] != 0 }
                    }
                    SudokuGrid(result.correctedGrid, correctedLocked)
                } else {
                    grid
                }

                pendingSolution = result.solution
                pendingOriginal = gridToUse

                Handler(Looper.getMainLooper()).postDelayed({
                    startAutoInput()
                }, 3000)

                moveTaskToBack(true)
            }

            ValidationStatus.HAS_CONTRADICTIONS -> {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Duplicate Values Found")
                    .setMessage(result.message + "\n\nRED cells have duplicates.")
                    .setPositiveButton("OK") { _, _ -> }
                    .show()
            }

            ValidationStatus.MULTIPLE_SOLUTIONS -> {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Multiple Solutions")
                    .setMessage(result.message)
                    .setPositiveButton("OK") { _, _ -> }
                    .show()
            }

            ValidationStatus.NEEDS_MORE_CLUES -> {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Need More Clues")
                    .setMessage(result.message)
                    .setPositiveButton("OK") { _, _ -> }
                    .show()
            }

            ValidationStatus.UNSOLVABLE -> {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Cannot Solve")
                    .setMessage(result.message)
                    .setPositiveButton("OK") { _, _ -> }
                    .show()
            }

            ValidationStatus.INTERNAL_ERROR -> {
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startAutoInput() {
        val solution = pendingSolution ?: return
        val original = pendingOriginal ?: return

        val accessibilityService = SudokuAccessibilityService.instance
        if (accessibilityService == null) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "Accessibility service not running", Toast.LENGTH_LONG).show()
            }
            return
        }

        accessibilityService.startManualAutoInput(original, solution)
    }

    private class DigitFilter : InputFilter {
        override fun filter(
            source: CharSequence?,
            start: Int,
            end: Int,
            dest: android.text.Spanned?,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            if (source == null) return null
            for (i in start until end) {
                if (source[i] !in '0'..'9') return ""
            }
            return null
        }
    }

    companion object {
        private var pendingSolution: Array<IntArray>? = null
        private var pendingOriginal: SudokuGrid? = null
    }
}
