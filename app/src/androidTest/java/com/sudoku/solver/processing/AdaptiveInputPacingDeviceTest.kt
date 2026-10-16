package com.sudoku.solver.processing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveInputPacingDeviceTest {
    @Test fun fixedHuaweiSpeedIgnoresAccumulatedSlowdownAndNeverSavesAnother() = withPreferences { context, prefs ->
        for (mode in InputPacingMode.values()) {
            val prefix = "${mode.name}_fast_v1"
            prefs.edit().putLong("${prefix}_cell", 120).putLong("${prefix}_digit", 120)
                .putLong("${prefix}_gesture", 90).putLong("${prefix}_floor_cell", 120)
                .putLong("${prefix}_floor_digit", 120).putLong("${prefix}_floor_gesture", 90).commit()
            val before = prefs.all.toMap()
            fun create() = AdaptiveInputPacing(context, mode, InputPace(70, 65, 61),
                maximumSpeed = true, maximumSpeedPace = InputPace.HUAWEI_FAST,
                fixedMaximumSpeed = true)
            val pacing = create()
            assertEquals(InputPace.HUAWEI_FAST, pacing.pace)
            repeat(30) {
                pacing.rejected("missing entry")
                pacing.wrongValueDetected("red value")
                pacing.verified(16)
                assertEquals(InputPace.HUAWEI_FAST, pacing.pace)
            }
            assertEquals(InputPace.HUAWEI_FAST, create().pace)
            assertEquals(before, prefs.all)
        }
    }

    @Test fun samsungHexProfileIgnoresLegacySlowdownAndKeepsMissedEntryBackoff() = withPreferences { context, prefs ->
        prefs.edit().putLong("HEX_16_cell", 45).putLong("HEX_16_digit", 45)
            .putLong("HEX_16_gesture", 46).putLong("HEX_16_floor_cell", 45)
            .putLong("HEX_16_floor_digit", 45).putLong("HEX_16_floor_gesture", 46).commit()
        fun create() = AdaptiveInputPacing(context, InputPacingMode.HEX_16, InputPace(0, 0, 1),
            maximumSpeed = true, maximumSpeedPace = InputPace(0, 0, 1),
            preferenceProfile = "a07_fast_v1")
        val pacing = create()
        assertEquals(InputPace(0, 0, 1), pacing.pace)
        pacing.wrongValueDetected("wrong solution")
        assertEquals(InputPace(0, 0, 1), create().pace)
        pacing.rejected("missing symbol")
        assertEquals(InputPace(15, 15, 11), pacing.pace)
        val retry = create()
        assertEquals(pacing.pace, retry.pace)
        repeat(10) { retry.verified(16) }
        assertEquals(InputPace(5, 5, 6), retry.pace)
        assertEquals(45L, prefs.getLong("HEX_16_cell", -1))
        assertEquals(InputPace.HUAWEI_FAST,
            AdaptiveInputPacing(context, InputPacingMode.HEX_16, InputPace.HUAWEI_FAST,
                maximumSpeed = true, maximumSpeedPace = InputPace.HUAWEI_FAST).pace)
    }

    private fun withPreferences(test: (Context, SharedPreferences) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "adaptive_pacing_device_test"
        target.deleteSharedPreferences(name)
        val prefs = target.getSharedPreferences(name, Context.MODE_PRIVATE)
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = prefs
        }
        try { test(context, prefs) } finally { target.deleteSharedPreferences(name) }
    }

    @Test fun oldSlowTimingsCannotThrottleAnyHuaweiMode() = withPreferences { context, prefs ->
        for (mode in InputPacingMode.values()) {
            prefs.edit().putLong("${mode.name}_cell", 120)
                .putLong("${mode.name}_digit", 120).putLong("${mode.name}_gesture", 90)
                .putLong("${mode.name}_floor_cell", 120).commit()
            val pacing = AdaptiveInputPacing(context, mode, InputPace(70, 65, 61),
                maximumSpeed = true, maximumSpeedPace = InputPace.HUAWEI_FAST)
            assertEquals(InputPace.HUAWEI_FAST, pacing.pace)
            pacing.verified(256)
            assertEquals(InputPace.HUAWEI_FAST, pacing.pace)
        }
    }

    @Test fun missingEntriesBackOffAcrossRetriesButWrongValuesDoNot() = withPreferences { context, _ ->
        fun create() = AdaptiveInputPacing(context, InputPacingMode.BATTLE_9, InputPace(70, 65, 61),
            maximumSpeed = true, maximumSpeedPace = InputPace.HUAWEI_FAST)
        val pacing = create()
        pacing.wrongValueDetected("wrong solution")
        assertEquals(InputPace.HUAWEI_FAST, pacing.pace)
        pacing.rejected("missing digit")
        assertEquals(InputPace(47, 47, 11), pacing.pace)
        val retry = create()
        assertEquals(pacing.pace, retry.pace)
        repeat(10) { retry.verified(16) }
        assertEquals(InputPace(37, 37, 6), retry.pace)
        // Other modes retain their independently verified fast starting pace.
        assertEquals(InputPace.HUAWEI_FAST,
            AdaptiveInputPacing(context, InputPacingMode.HEX_16, InputPace(70, 65, 61),
                maximumSpeed = true, maximumSpeedPace = InputPace.HUAWEI_FAST).pace)
    }
}
