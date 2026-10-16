package com.sudoku.solver.service

import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.sudoku.solver.R
import com.sudoku.solver.processing.RepeatTapTimerPolicy
import com.sudoku.solver.processing.ResponsiveGeometryPolicy
import kotlin.math.ceil

/**
 * Service that displays a floating button overlay for triggering Sudoku solving
 * - Tap: Solve puzzle
 * - Long press: Restart services (clears OCR errors)
 */
class FloatingButtonService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var solveButton: Button? = null
    private var fixButton: Button? = null
    private var timerButton: Button? = null
    private var solverStatusText: TextView? = null
    private var timerConfigDialog: AlertDialog? = null
    private var positionSelectorView: View? = null
    private var timerRunning = false
    private var timerIntervalMs = 0L
    private var timerTargetX = 0
    private var timerTargetY = 0
    private var nextTimerTapAtMs = 0L
    private var lastTimerStoppedAtMs = 0L
    private var timerTapCount = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var isLongPress = false
    private val timerTapRunnable = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            val accessibilityService = SudokuAccessibilityService.instance
            if (accessibilityService == null) {
                stopTapTimer(showConfirmation = false)
                Toast.makeText(
                    this@FloatingButtonService,
                    "Timer stopped: Accessibility service is not connected.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            timerTapCount++
            Log.d(TAG, "Timer tap #$timerTapCount at ($timerTargetX, $timerTargetY)")
            accessibilityService.performTimerTap(timerTargetX, timerTargetY)
            val now = SystemClock.elapsedRealtime()
            nextTimerTapAtMs = RepeatTapTimerPolicy.nextDeadline(
                previousDeadlineMs = nextTimerTapAtMs,
                nowMs = now,
                intervalMs = timerIntervalMs
            )
            handler.postDelayed(this, (nextTimerTapAtMs - now).coerceAtLeast(1L))
        }
    }
    private val buttonStateUpdater = object : Runnable {
        override fun run() {
            val service = SudokuAccessibilityService.instance
            val isRunning = service?.isCurrentlySolving() == true
            val isRunning16x16 = service?.isCurrentlySolving16x16() == true
            solveButton?.text = if (isRunning && !isRunning16x16) "Stop" else "Solve"
            fixButton?.text = if (isRunning16x16) "STOP" else "16×16"
            timerButton?.text = timerButtonText()
            handler.postDelayed(this, 300)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        showFloatingButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingButton()
        if (instance === this) instance = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            val params = overlayLayoutParams ?: return@post
            updateSolverStatusWidth()
            clampToUsableScreen(params)
            floatingView?.let { view -> windowManager?.updateViewLayout(view, params) }
        }
    }

    private fun showFloatingButton() {
        // Get WindowManager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Inflate the floating view
        floatingView = LayoutInflater.from(this).inflate(R.layout.overlay_floating_button, null)

        // Set up layout parameters
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val environment = readWindowEnvironment()
            val margin = dpToPx(12)
            x = environment.insetLeft + margin
            y = environment.insetTop + margin
        }
        overlayLayoutParams = params

        // Add view to window
        windowManager?.addView(floatingView, params)
        floatingView?.post {
            clampToUsableScreen(params)
            floatingView?.let { view -> windowManager?.updateViewLayout(view, params) }
        }

        // Set up button click listener
        solveButton = floatingView?.findViewById(R.id.solveButton)
        fixButton = floatingView?.findViewById(R.id.fixButton)
        timerButton = floatingView?.findViewById(R.id.timerButton)
        solverStatusText = floatingView?.findViewById(R.id.solverStatusText)
        updateSolverStatusWidth()

        setupDraggableWithLongPress(solveButton, params) {
            onSolveButtonClicked()
        }

        setupDraggableWithLongPress(fixButton, params) {
            onHexadokuButtonClicked()
        }

        setupDraggableWithLongPress(timerButton, params) {
            onTimerButtonClicked()
        }

        handler.post(buttonStateUpdater)
    }

    private fun setupDraggableWithLongPress(
        view: View?,
        params: WindowManager.LayoutParams,
        onTap: () -> Unit
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val clickThreshold = dpToPx(8)
        val longPressTimeout = 800L // 800ms for long press

        view?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    isLongPress = false

                    // Start long press timer
                    longPressRunnable = Runnable {
                        isLongPress = true
                        onRestartServices()
                    }
                    handler.postDelayed(longPressRunnable!!, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > clickThreshold || Math.abs(dy) > clickThreshold) {
                        isDragging = true
                        // Cancel long press if dragging
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        clampToUsableScreen(params)
                        floatingView?.let { windowManager?.updateViewLayout(it, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Cancel long press timer
                    longPressRunnable?.let { handler.removeCallbacks(it) }

                    if (!isDragging && !isLongPress) {
                        onTap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }
    }

    private fun clampToUsableScreen(params: WindowManager.LayoutParams) {
        val environment = readWindowEnvironment()
        val rootView = floatingView
        val position = ResponsiveGeometryPolicy.clampOverlayPosition(
            requestedX = params.x,
            requestedY = params.y,
            screenWidth = environment.width,
            screenHeight = environment.height,
            viewWidth = rootView?.width ?: 0,
            viewHeight = rootView?.height ?: 0,
            insetLeft = environment.insetLeft,
            insetTop = environment.insetTop,
            insetRight = environment.insetRight,
            insetBottom = environment.insetBottom,
            margin = dpToPx(8)
        )
        params.x = position.x
        params.y = position.y
    }

    private fun readWindowEnvironment(): WindowEnvironment {
        val manager = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = manager.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return WindowEnvironment(
                width = bounds.width(),
                height = bounds.height(),
                insetLeft = insets.left,
                insetTop = insets.top,
                insetRight = insets.right,
                insetBottom = insets.bottom
            )
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        val statusBarHeight = systemDimension("status_bar_height")
        val navigationBarHeight = systemDimension("navigation_bar_height")
        return WindowEnvironment(
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            insetLeft = 0,
            insetTop = statusBarHeight,
            insetRight = 0,
            insetBottom = navigationBarHeight
        )
    }

    private fun systemDimension(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    private fun onRestartServices() {
        Log.i(TAG, "Long press detected - restarting services")
        stopTapTimer(showConfirmation = false)
        dismissTimerSetup()
        Toast.makeText(this, "Restarting capture service...", Toast.LENGTH_SHORT).show()
        SudokuAccessibilityService.instance?.stopSolving()

        // Restart the screen capture service
        val captureService = ScreenCaptureService.instance
        if (captureService != null) {
            // Stop and restart screen capture
            val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP_CAPTURE
            }
            stopService(stopIntent)

            // Brief delay then notify user to restart from main app
            handler.postDelayed({
                Toast.makeText(this, "Services stopped. Open app to restart capture.", Toast.LENGTH_LONG).show()
            }, 500)
        } else {
            Toast.makeText(this, "Capture service not running. Open app to start.", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeFloatingButton() {
        stopTapTimer(showConfirmation = false)
        dismissTimerSetup()
        handler.removeCallbacks(buttonStateUpdater)
        floatingView?.let { view ->
            windowManager?.removeView(view)
            floatingView = null
        }
        overlayLayoutParams = null
        solveButton = null
        fixButton = null
        timerButton = null
        solverStatusText = null
    }

    private fun onSolveButtonClicked() {
        Log.i(TAG, "Solve button clicked")
        dismissTimerSetup()
        val service = SudokuAccessibilityService.instance
        if (service != null) {
            if (service.isCurrentlySolving()) {
                Log.i(TAG, "Accessibility service connected, stopping solve")
                service.stopSolving()
            } else {
                Log.i(TAG, "Accessibility service connected, starting solve")
                service.startSolving()
            }
        } else {
            Log.e(TAG, "Accessibility service NOT connected! instance is null")
            Toast.makeText(
                this,
                "Accessibility is not connected. Turn Sudoku Solver off, then on again in Accessibility Settings.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showSolverStatus(message: String) {
        handler.post {
            solverStatusText?.apply {
                text = message
                visibility = View.VISIBLE
            }
        }
    }

    private fun updateSolverStatusWidth() {
        val status = solverStatusText ?: return
        val environment = readWindowEnvironment()
        val horizontalMargin = dpToPx(24)
        status.maxWidth = (
            environment.width - environment.insetLeft - environment.insetRight - horizontalMargin
            ).coerceAtLeast(dpToPx(160))
        status.requestLayout()
    }

    private fun onHexadokuButtonClicked() {
        Log.i(TAG, "16x16 button clicked")
        dismissTimerSetup()
        val service = SudokuAccessibilityService.instance
        if (service == null) {
            Log.e(TAG, "Accessibility service NOT connected! instance is null")
            Toast.makeText(
                this,
                "Accessibility is not connected. Turn Sudoku Solver off, then on again in Accessibility Settings.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (service.isCurrentlySolving()) {
            Log.i(TAG, "Solve in progress, stopping from 16x16 button")
            service.stopSolving()
            return
        }

        Toast.makeText(this, "Starting 16x16 solve (1-9, A-G)...", Toast.LENGTH_SHORT).show()
        service.startSolving16x16()
    }

    private fun onTimerButtonClicked() {
        if (timerRunning) {
            stopTapTimer(showConfirmation = true)
            return
        }

        // Ignore a trailing touch generated while the button changes from STOP back to TIMER.
        if (
            lastTimerStoppedAtMs > 0L &&
            SystemClock.elapsedRealtime() - lastTimerStoppedAtMs < TIMER_RESTART_GUARD_MS
        ) return

        val service = SudokuAccessibilityService.instance
        if (service == null) {
            Toast.makeText(
                this,
                "Accessibility is not connected. Enable it before starting the timer.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        showTimerConfiguration()
    }

    private fun showTimerConfiguration() {
        dismissTimerSetup()
        val preferences = getSharedPreferences(TIMER_PREFERENCES, Context.MODE_PRIVATE)
        val savedSeconds = preferences.getLong(
            PREF_INTERVAL_SECONDS,
            DEFAULT_TIMER_INTERVAL_SECONDS
        )
        val hasSavedPosition =
            preferences.contains(PREF_TARGET_X) && preferences.contains(PREF_TARGET_Y)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dpToPx(20)
            setPadding(padding, dpToPx(8), padding, 0)
        }
        val explanation = TextView(this).apply {
            text = "Tap the saved screen position repeatedly until you press STOP."
            setTextColor(Color.DKGRAY)
            textSize = 15f
        }
        val secondsInput = EditText(this).apply {
            hint = "Seconds"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(savedSeconds.toString())
            selectAll()
        }
        val savedPositionLabel = TextView(this).apply {
            val label = if (hasSavedPosition) {
                val x = preferences.getInt(PREF_TARGET_X, 0)
                val y = preferences.getInt(PREF_TARGET_Y, 0)
                "Saved position: x=$x, y=$y"
            } else {
                "No position saved yet."
            }
            text = label
            setTextColor(Color.GRAY)
            textSize = 13f
        }
        content.addView(explanation)
        content.addView(secondsInput)
        content.addView(savedPositionLabel)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Repeat-tap timer")
            .setView(content)
            .setPositiveButton("Choose position", null)
            .setNeutralButton("Start saved", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setType(overlayWindowType())
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val intervalMs = parseTimerInterval(secondsInput) ?: return@setOnClickListener
                saveTimerInterval(intervalMs)
                dialog.dismiss()
                timerConfigDialog = null
                beginPositionSelection(intervalMs)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                isEnabled = hasSavedPosition
                setOnClickListener {
                    val intervalMs = parseTimerInterval(secondsInput) ?: return@setOnClickListener
                    val targetX = preferences.getInt(PREF_TARGET_X, 0)
                    val targetY = preferences.getInt(PREF_TARGET_Y, 0)
                    saveTimerInterval(intervalMs)
                    dialog.dismiss()
                    timerConfigDialog = null
                    startTapTimer(targetX, targetY, intervalMs)
                }
            }
        }
        dialog.setOnDismissListener {
            if (timerConfigDialog === dialog) timerConfigDialog = null
        }
        timerConfigDialog = dialog
        dialog.show()
    }

    private fun parseTimerInterval(input: EditText): Long? {
        val intervalMs = RepeatTapTimerPolicy.parseIntervalMillis(input.text)
        if (intervalMs == null) {
            input.error = "Enter a whole number from 1 to 86400 seconds"
            input.requestFocus()
        }
        return intervalMs
    }

    private fun saveTimerInterval(intervalMs: Long) {
        getSharedPreferences(TIMER_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_INTERVAL_SECONDS, intervalMs / 1_000L)
            .apply()
    }

    private fun beginPositionSelection(intervalMs: Long) {
        removePositionSelector()
        val manager = windowManager ?: return
        val selector = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(72, 0, 0, 0))
            isClickable = true
        }
        val instruction = TextView(this).apply {
            text = "Tap the screen position for the timer"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(220, 32, 32, 32))
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            textSize = 16f
            gravity = Gravity.CENTER
        }
        selector.addView(
            instruction,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                topMargin = readWindowEnvironment().insetTop + dpToPx(12)
                marginStart = dpToPx(72)
                marginEnd = dpToPx(72)
            }
        )
        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                removePositionSelector()
                Toast.makeText(this@FloatingButtonService, "Timer setup cancelled.", Toast.LENGTH_SHORT).show()
            }
        }
        selector.addView(
            cancel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = readWindowEnvironment().insetBottom + dpToPx(24)
            }
        )
        var positionGestureStarted = false
        selector.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    positionGestureStarted = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Some devices forward the dialog button's trailing ACTION_UP to a new overlay.
                    // Only accept a complete gesture that began on this position selector.
                    if (!positionGestureStarted) return@setOnTouchListener true
                    positionGestureStarted = false
                    val targetX = event.rawX.toInt()
                    val targetY = event.rawY.toInt()
                    getSharedPreferences(TIMER_PREFERENCES, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(PREF_TARGET_X, targetX)
                        .putInt(PREF_TARGET_Y, targetY)
                        .apply()
                    removePositionSelector()
                    startTapTimer(targetX, targetY, intervalMs)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    positionGestureStarted = false
                    true
                }
                else -> true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        manager.addView(selector, params)
        positionSelectorView = selector
    }

    private fun startTapTimer(targetX: Int, targetY: Int, intervalMs: Long) {
        val service = SudokuAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Timer could not start: Accessibility is not connected.", Toast.LENGTH_LONG).show()
            return
        }

        val environment = readWindowEnvironment()
        timerTargetX = targetX.coerceIn(0, (environment.width - 1).coerceAtLeast(0))
        timerTargetY = targetY.coerceIn(0, (environment.height - 1).coerceAtLeast(0))
        timerIntervalMs = intervalMs
        timerRunning = true
        timerTapCount = 0L
        nextTimerTapAtMs = SystemClock.elapsedRealtime() + intervalMs
        handler.removeCallbacks(timerTapRunnable)
        handler.postDelayed(timerTapRunnable, intervalMs)
        Log.i(
            TAG,
            "Timer started: interval=${intervalMs}ms, target=($timerTargetX, $timerTargetY)"
        )
        Toast.makeText(
            this,
            "Timer started: tap every ${intervalMs / 1_000L}s at ($timerTargetX, $timerTargetY).",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun stopTapTimer(showConfirmation: Boolean) {
        val wasRunning = timerRunning
        timerRunning = false
        handler.removeCallbacks(timerTapRunnable)
        nextTimerTapAtMs = 0L
        if (wasRunning) {
            lastTimerStoppedAtMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "Timer stopped after $timerTapCount taps")
            timerTapCount = 0L
        }
        if (wasRunning && showConfirmation) {
            Toast.makeText(this, "Timer stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun timerButtonText(): String {
        if (!timerRunning) return "Timer"
        val remainingMs = (nextTimerTapAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val remainingSeconds = ceil(remainingMs / 1_000.0).toLong()
        return "STOP ${remainingSeconds}s"
    }

    private fun dismissTimerSetup() {
        timerConfigDialog?.dismiss()
        timerConfigDialog = null
        removePositionSelector()
    }

    private fun removePositionSelector() {
        val selector = positionSelectorView ?: return
        try {
            windowManager?.removeView(selector)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Timer position selector was already removed", error)
        } finally {
            positionSelectorView = null
        }
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    companion object {
        private const val TAG = "FloatingButtonService"
        @Volatile
        private var instance: FloatingButtonService? = null
        private const val TIMER_PREFERENCES = "repeat_tap_timer"
        private const val PREF_INTERVAL_SECONDS = "interval_seconds"
        private const val PREF_TARGET_X = "target_x"
        private const val PREF_TARGET_Y = "target_y"
        private const val DEFAULT_TIMER_INTERVAL_SECONDS = 5L
        private const val TIMER_RESTART_GUARD_MS = 1_000L

        fun publishSolverStatus(message: String) {
            instance?.showSolverStatus(message)
        }
    }

    private data class WindowEnvironment(
        val width: Int,
        val height: Int,
        val insetLeft: Int,
        val insetTop: Int,
        val insetRight: Int,
        val insetBottom: Int
    )
}
