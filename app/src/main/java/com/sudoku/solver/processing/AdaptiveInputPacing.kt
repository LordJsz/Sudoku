package com.sudoku.solver.processing

import android.content.Context
import android.os.Build
import android.util.Log

internal enum class InputPacingMode { SOLO_9, BATTLE_9, HEX_16 }

internal data class InputPace(
    val cellSettleMs: Long,
    val digitSettleMs: Long,
    val gestureMs: Long
) {
    fun faster(floor: InputPace = InputPace(0, 0, 1)) = InputPace(
        (cellSettleMs - 5).coerceAtLeast(floor.cellSettleMs),
        (digitSettleMs - 5).coerceAtLeast(floor.digitSettleMs),
        (gestureMs - 5).coerceAtLeast(floor.gestureMs)
    )

    fun slower() = InputPace(
        (cellSettleMs + 15).coerceAtMost(120),
        (digitSettleMs + 15).coerceAtMost(120),
        (gestureMs + 10).coerceAtMost(90)
    )

    companion object {
        // Two UI frames at 60 Hz between taps. Removing either settling period
        // caused entries in the previous cell during JAD-LX9 live tests.
        val HUAWEI_FAST = InputPace(32, 32, 1)
    }
}

/** Tests faster timings only after the game has visibly accepted previous entries. */
internal class AdaptiveInputPacing(
    context: Context,
    val mode: InputPacingMode,
    initial: InputPace,
    private val maximumSpeed: Boolean = false,
    private val maximumSpeedPace: InputPace = InputPace(0, 0, 1),
    private val preferenceProfile: String? = null,
    private val fixedMaximumSpeed: Boolean = false
) {
    // v3 discards the old global floor. A wrong OCR value used to be treated as a
    // missed tap, permanently slowing a device even though its gestures were fine.
    private val preferences = context.getSharedPreferences("adaptive_input_pacing_v3", Context.MODE_PRIVATE)
    private val tunedFastProfile = maximumSpeed &&
        (preferenceProfile != null || maximumSpeedPace != InputPace(0, 0, 1))
    private val holdsMaximumSpeed = maximumSpeed && (fixedMaximumSpeed || !tunedFastProfile)
    // Keep the verified fast profile separate from older OCR-related slowdowns.
    private val prefix = mode.name + when {
        preferenceProfile != null -> "_$preferenceProfile"
        tunedFastProfile -> "_fast_v1"
        else -> ""
    }
    private var acceptedSinceStep = 0
    private var cooldownEntries = 0
    private var floor = InputPace(
        preferences.getLong("${prefix}_floor_cell", 0),
        preferences.getLong("${prefix}_floor_digit", 0),
        preferences.getLong("${prefix}_floor_gesture", 1)
    ).bounded().atLeast(if (tunedFastProfile) maximumSpeedPace.bounded() else InputPace(0, 0, 1))
    // A validated fixed profile must never inherit a learned slowdown. Missing
    // entries still stop input for recovery, but cannot ratchet up future delays.
    var pace = if (maximumSpeed && fixedMaximumSpeed) {
        maximumSpeedPace.bounded()
    } else if (tunedFastProfile) {
        InputPace(
            preferences.getLong("${prefix}_cell", maximumSpeedPace.cellSettleMs),
            preferences.getLong("${prefix}_digit", maximumSpeedPace.digitSettleMs),
            preferences.getLong("${prefix}_gesture", maximumSpeedPace.gestureMs)
        ).bounded().atLeast(floor)
    } else if (maximumSpeed) {
        InputPace(0, 0, 1)
    } else InputPace(
        preferences.getLong("${prefix}_cell", initial.cellSettleMs),
        preferences.getLong("${prefix}_digit", initial.digitSettleMs),
        preferences.getLong("${prefix}_gesture", initial.gestureMs)
    ).bounded().atLeast(floor)
        private set

    fun verified(count: Int) {
        if (holdsMaximumSpeed) return
        if (count <= 0) return
        if (cooldownEntries > 0) {
            cooldownEntries = (cooldownEntries - count).coerceAtLeast(0)
            return
        }
        acceptedSinceStep += count
        if (acceptedSinceStep < 16) return
        acceptedSinceStep = 0
        update(pace.faster(floor), "16 verified entries")
    }

    fun rejected(reason: String) {
        if (holdsMaximumSpeed) {
            Log.i("AdaptiveInputPacing", "$mode remains at maximum speed after $reason")
            return
        }
        acceptedSinceStep = 0
        cooldownEntries = 24
        floor = InputPace(
            maxOf(floor.cellSettleMs, (pace.cellSettleMs + 5).coerceAtMost(120)),
            maxOf(floor.digitSettleMs, (pace.digitSettleMs + 5).coerceAtMost(120)),
            maxOf(floor.gestureMs, (pace.gestureMs + 5).coerceAtMost(90))
        )
        preferences.edit()
            .putLong("${prefix}_floor_cell", floor.cellSettleMs)
            .putLong("${prefix}_floor_digit", floor.digitSettleMs)
            .putLong("${prefix}_floor_gesture", floor.gestureMs)
            .apply()
        update(pace.slower(), reason)
    }

    /** A delivered but incorrect value is an OCR/solution issue, not a pacing failure. */
    fun wrongValueDetected(reason: String) {
        acceptedSinceStep = 0
        Log.i(
            "AdaptiveInputPacing",
            "$mode pace unchanged at $pace after $reason on ${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    private fun update(next: InputPace, reason: String) {
        if (next == pace) return
        pace = next.bounded()
        preferences.edit()
            .putLong("${prefix}_cell", pace.cellSettleMs)
            .putLong("${prefix}_digit", pace.digitSettleMs)
            .putLong("${prefix}_gesture", pace.gestureMs)
            .apply()
        Log.i("AdaptiveInputPacing", "$mode pace=$pace after $reason on ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    private fun InputPace.bounded() = InputPace(
        cellSettleMs.coerceIn(0, 120),
        digitSettleMs.coerceIn(0, 120),
        gestureMs.coerceIn(1, 90)
    )

    private fun InputPace.atLeast(minimum: InputPace) = InputPace(
        maxOf(cellSettleMs, minimum.cellSettleMs),
        maxOf(digitSettleMs, minimum.digitSettleMs),
        maxOf(gestureMs, minimum.gestureMs)
    )
}
