package com.sudoku.solver.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sudoku.solver.R
import com.sudoku.solver.service.FloatingButtonService
import com.sudoku.solver.service.ScreenCaptureService
import com.sudoku.solver.service.SudokuAccessibilityService
import com.sudoku.solver.util.OcrScanAreaPrefs

/**
 * Main activity for permission management and app configuration
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var enableAccessibilityButton: Button
    private lateinit var enableOverlayButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var manualInputButton: Button
    private lateinit var setOcrAreaButton: Button
    private lateinit var accessibilityStateText: TextView
    private lateinit var overlayStateText: TextView
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            updatePermissionStatus()
            statusHandler.postDelayed(this, STATUS_REFRESH_INTERVAL_MS)
        }
    }

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_NOTIFICATIONS = 1002
        private const val STATUS_REFRESH_INTERVAL_MS = 500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupClickListeners()
        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        // Returning to setup must stop gestures before they can hit its buttons.
        SudokuAccessibilityService.instance?.takeIf { it.isCurrentlySolving() }?.stopSolving()
        updatePermissionStatus()
        statusHandler.removeCallbacks(statusRefresh)
        statusHandler.post(statusRefresh)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    private fun initializeViews() {
        statusText = findViewById(R.id.statusText)
        enableAccessibilityButton = findViewById(R.id.enableAccessibilityButton)
        enableOverlayButton = findViewById(R.id.enableOverlayButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        manualInputButton = findViewById(R.id.manualInputButton)
        setOcrAreaButton = findViewById(R.id.setOcrAreaButton)
        accessibilityStateText = findViewById(R.id.accessibilityStateText)
        overlayStateText = findViewById(R.id.overlayStateText)
    }

    private fun setupClickListeners() {
        enableAccessibilityButton.setOnClickListener {
            openAccessibilitySettings()
        }

        enableOverlayButton.setOnClickListener {
            requestOverlayPermission()
        }

        startButton.setOnClickListener {
            startFloatingButtonService()
        }

        stopButton.setOnClickListener {
            stopAllServices()
        }

        manualInputButton.setOnClickListener {
            startActivity(Intent(this, ManualInputActivity::class.java))
        }

        setOcrAreaButton.setOnClickListener {
            if (ScreenCaptureService.instance == null) {
                Toast.makeText(this, "Start solving service first to capture a preview", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, OcrScanAreaActivity::class.java))
        }
    }

    private fun updatePermissionStatus() {
        val isAccessibilityConfigured = isAccessibilityServiceConfigured()
        val hasAccessibility = SudokuAccessibilityService.instance != null
        val hasOverlay = Settings.canDrawOverlays(this)
        // A Service object can briefly exist without a usable MediaProjection when
        // Android restores the process after reboot. Only a complete capture surface
        // means the solver is actually running.
        val captureService = ScreenCaptureService.instance
        val isRunning = captureService?.isCaptureActive() == true
        val captureDimensions = captureService?.captureDimensions()
        val hasCustomScanArea = if (captureDimensions != null) {
            OcrScanAreaPrefs.hasCustomArea(
                this,
                captureDimensions.first,
                captureDimensions.second
            )
        } else {
            OcrScanAreaPrefs.hasCustomArea(this)
        }

        // Update button states
        enableAccessibilityButton.isEnabled = !hasAccessibility
        enableAccessibilityButton.text = getString(
            if (isAccessibilityConfigured) R.string.reconnect_accessibility
            else R.string.enable_accessibility
        )
        enableOverlayButton.isEnabled = !hasOverlay
        startButton.isEnabled = hasAccessibility && hasOverlay && !isRunning
        stopButton.isEnabled = isRunning
        setOcrAreaButton.isEnabled = isRunning

        val accessibilityState = when {
            hasAccessibility -> getString(R.string.permission_ready)
            isAccessibilityConfigured -> "Enabled, but not connected"
            else -> getString(R.string.permission_needed)
        }
        accessibilityStateText.text = getString(R.string.accessibility_state, accessibilityState)
        overlayStateText.text = getString(
            R.string.overlay_state,
            getString(if (hasOverlay) R.string.permission_ready else R.string.permission_needed)
        )

        // Update status text
        statusText.text = when {
            !hasAccessibility && isAccessibilityConfigured ->
                "Accessibility is enabled but not connected. Tap Reconnect, turn Sudoku Solver off, then on again."
            !hasAccessibility -> "Enable accessibility to let the solver read and tap the board."
            !hasOverlay -> "Allow the floating Solve button over your Sudoku game."
            isRunning -> {
                val scanAreaText = if (hasCustomScanArea) {
                    "Custom scan area active for better accuracy."
                } else {
                    "Automatic full-screen board detection is active."
                }
                "Solver is running\nOpen a puzzle and tap the floating button.\n$scanAreaText"
            }
            else -> getString(R.string.status_idle)
        }
    }

    private fun stopAllServices() {
        SudokuAccessibilityService.instance?.takeIf { it.isCurrentlySolving() }?.stopSolving()
        // Stop screen capture service
        val stopCaptureIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_CAPTURE
        }
        stopService(stopCaptureIntent)

        // Stop floating button service
        stopService(Intent(this, FloatingButtonService::class.java))

        Toast.makeText(this, "Services stopped", Toast.LENGTH_SHORT).show()
        updatePermissionStatus()
    }

    private fun isAccessibilityServiceConfigured(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(packageName) == true
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun startFloatingButtonService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        requestScreenCapturePermission()
    }

    private fun requestScreenCapturePermission() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notifications are off; Android may hide solver status.", Toast.LENGTH_LONG).show()
            }
            requestScreenCapturePermission()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Start screen capture service with permission
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START_CAPTURE
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, data)
                }
                startForegroundService(intent)

                // Start floating button service
                startService(Intent(this, FloatingButtonService::class.java))

                Toast.makeText(this, "Screen capture enabled! Tap the floating button to solve.", Toast.LENGTH_LONG).show()

                // Minimize the app
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
