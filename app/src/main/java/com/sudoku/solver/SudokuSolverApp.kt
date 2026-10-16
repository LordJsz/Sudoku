package com.sudoku.solver

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * Application class for initializing OpenCV
 */
class SudokuSolverApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize OpenCV
        if (OpenCVLoader.initLocal()) {
            Log.i(TAG, "OpenCV loaded successfully")
        } else {
            Log.e(TAG, "OpenCV initialization failed!")
        }
    }

    companion object {
        private const val TAG = "SudokuSolverApp"
    }
}
