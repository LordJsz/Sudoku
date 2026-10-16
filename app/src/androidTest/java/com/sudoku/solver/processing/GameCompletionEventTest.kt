package com.sudoku.solver.processing

import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.sudoku.solver.service.SudokuAccessibilityService
import org.junit.Assert.*
import org.junit.Test

class GameCompletionEventTest {
    @Test fun completionNotificationPausesServiceAndBlocksRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String = automation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
        }
        val original = shell("settings get secure enabled_accessibility_services")
        require(original.matches(Regex("[A-Za-z0-9_./:]+")) && original.contains("com.sudoku.solver/"))
        val others = original.split(':').filterNot { it.startsWith("com.sudoku.solver/") }.joinToString(":")
        try {
            // UiAutomation executes arguments directly; shell quotes would be
            // stored literally and turn a valid component into an invalid one.
            if (others.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $others")
            SystemClock.sleep(500L)
        } finally {
            shell("settings put secure enabled_accessibility_services $original")
        }
        val deadline = SystemClock.elapsedRealtime() + 8_000L
        while (SudokuAccessibilityService.instance == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100L)
        }
        val service = requireNotNull(SudokuAccessibilityService.instance) { "Accessibility did not reconnect" }
        instrumentation.runOnMainSync {
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
            try {
                event.packageName = GameInputPolicy.GAME_PACKAGE
                event.text.add("Autocompletando…")
                service.onAccessibilityEvent(event)
                assertTrue(service.isWaitingForGameCompletion())
                assertFalse(service.isCurrentlySolving())
                service.startSolving()
                assertFalse("Completion must not restart OCR/input", service.isCurrentlySolving())
            } finally { event.recycle() }
        }
    }
}
