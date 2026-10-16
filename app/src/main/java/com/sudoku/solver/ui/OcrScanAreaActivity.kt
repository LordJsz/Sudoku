package com.sudoku.solver.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sudoku.solver.R
import com.sudoku.solver.service.ScreenCaptureService
import com.sudoku.solver.ui.view.ScanAreaSelectorView
import com.sudoku.solver.util.OcrScanArea
import com.sudoku.solver.util.OcrScanAreaPrefs

class OcrScanAreaActivity : AppCompatActivity() {

    private lateinit var screenshotView: ImageView
    private lateinit var selectorView: ScanAreaSelectorView
    private var screenshotBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocr_scan_area)

        screenshotView = findViewById(R.id.scanAreaPreviewImage)
        selectorView = findViewById(R.id.scanAreaSelector)
        val saveButton: Button = findViewById(R.id.saveScanAreaButton)
        val resetButton: Button = findViewById(R.id.resetScanAreaButton)
        val cancelButton: Button = findViewById(R.id.cancelScanAreaButton)

        val captureService = ScreenCaptureService.instance
        if (captureService == null) {
            Toast.makeText(this, "Start capture service first", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val screenshot = captureService.captureScreen()
        if (screenshot == null) {
            Toast.makeText(this, "Failed to capture screen", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        screenshotBitmap = screenshot
        screenshotView.setImageBitmap(screenshot)

        selectorView.post {
            val savedArea = OcrScanAreaPrefs.getForDimensions(
                this,
                screenshot.width,
                screenshot.height
            )
            if (savedArea != null) {
                selectorView.setSelectionNormalized(
                    savedArea.leftNorm,
                    savedArea.topNorm,
                    savedArea.rightNorm,
                    savedArea.bottomNorm
                )
            } else {
                selectorView.resetToFull()
            }
        }

        saveButton.setOnClickListener {
            val n = selectorView.getSelectionNormalized()
            val area = OcrScanArea(
                leftNorm = n.left,
                topNorm = n.top,
                rightNorm = n.right,
                bottomNorm = n.bottom
            )
            OcrScanAreaPrefs.save(
                this,
                area,
                screenshot.width,
                screenshot.height
            )
            Log.i(TAG, "Saved OCR scan area: $area")
            Toast.makeText(this, "OCR scan area saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        resetButton.setOnClickListener {
            OcrScanAreaPrefs.clear(this)
            selectorView.resetToFull()
            Toast.makeText(this, "Using full-screen auto detection", Toast.LENGTH_SHORT).show()
        }

        cancelButton.setOnClickListener {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        screenshotBitmap?.recycle()
        screenshotBitmap = null
    }

    companion object {
        private const val TAG = "OcrScanAreaActivity"
    }
}
