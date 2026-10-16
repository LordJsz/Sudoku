package com.sudoku.solver.util

import android.content.Context
import android.graphics.Rect
import com.sudoku.solver.processing.ResponsiveGeometryPolicy
import kotlin.math.max

data class OcrScanArea(
    val leftNorm: Float,
    val topNorm: Float,
    val rightNorm: Float,
    val bottomNorm: Float
)

object OcrScanAreaPrefs {
    private const val PREFS_NAME = "ocr_scan_area_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LEFT = "left"
    private const val KEY_TOP = "top"
    private const val KEY_RIGHT = "right"
    private const val KEY_BOTTOM = "bottom"
    private const val KEY_REFERENCE_ASPECT = "reference_aspect"

    fun save(
        context: Context,
        area: OcrScanArea,
        referenceWidth: Int,
        referenceHeight: Int
    ) {
        val clamped = clamp(area)
        val editor = prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putFloat(KEY_LEFT, clamped.leftNorm)
            .putFloat(KEY_TOP, clamped.topNorm)
            .putFloat(KEY_RIGHT, clamped.rightNorm)
            .putFloat(KEY_BOTTOM, clamped.bottomNorm)
        if (referenceWidth > 0 && referenceHeight > 0) {
            editor.putFloat(KEY_REFERENCE_ASPECT, referenceWidth.toFloat() / referenceHeight.toFloat())
        } else {
            editor.remove(KEY_REFERENCE_ASPECT)
        }
        editor.apply()
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, false)
            .remove(KEY_LEFT)
            .remove(KEY_TOP)
            .remove(KEY_RIGHT)
            .remove(KEY_BOTTOM)
            .remove(KEY_REFERENCE_ASPECT)
            .apply()
    }

    fun get(context: Context): OcrScanArea? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ENABLED, false)) return null
        val area = OcrScanArea(
            leftNorm = p.getFloat(KEY_LEFT, 0f),
            topNorm = p.getFloat(KEY_TOP, 0f),
            rightNorm = p.getFloat(KEY_RIGHT, 1f),
            bottomNorm = p.getFloat(KEY_BOTTOM, 1f)
        )
        return clamp(area).takeIf { isValid(it) }
    }

    fun hasCustomArea(context: Context): Boolean = get(context) != null

    fun getForDimensions(context: Context, width: Int, height: Int): OcrScanArea? {
        if (width <= 0 || height <= 0) return null
        val area = get(context) ?: return null
        val savedAspect = prefs(context).getFloat(KEY_REFERENCE_ASPECT, Float.NaN)
        val currentAspect = width.toFloat() / height.toFloat()
        return area.takeIf {
            ResponsiveGeometryPolicy.isCompatibleScanAspect(savedAspect, currentAspect)
        }
    }

    fun hasCustomArea(context: Context, width: Int, height: Int): Boolean {
        return getForDimensions(context, width, height) != null
    }

    fun toAbsoluteRect(context: Context, width: Int, height: Int): Rect? {
        val area = getForDimensions(context, width, height) ?: return null
        if (width <= 0 || height <= 0) return null

        val left = (area.leftNorm * width).toInt().coerceIn(0, width - 1)
        val top = (area.topNorm * height).toInt().coerceIn(0, height - 1)
        val right = (area.rightNorm * width).toInt().coerceIn(left + 1, width)
        val bottom = (area.bottomNorm * height).toInt().coerceIn(top + 1, height)

        if (right - left < MIN_AREA_SIZE_PX || bottom - top < MIN_AREA_SIZE_PX) {
            return null
        }
        return Rect(left, top, right, bottom)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun clamp(area: OcrScanArea): OcrScanArea {
        val l = area.leftNorm.coerceIn(0f, 1f)
        val t = area.topNorm.coerceIn(0f, 1f)
        val r = area.rightNorm.coerceIn(0f, 1f)
        val b = area.bottomNorm.coerceIn(0f, 1f)

        val minSpan = 0.05f
        val left = minOf(l, r - minSpan)
        val top = minOf(t, b - minSpan)
        val right = maxOf(r, left + minSpan).coerceAtMost(1f)
        val bottom = maxOf(b, top + minSpan).coerceAtMost(1f)

        return OcrScanArea(left, top, right, bottom)
    }

    private fun isValid(area: OcrScanArea): Boolean {
        val w = area.rightNorm - area.leftNorm
        val h = area.bottomNorm - area.topNorm
        return area.leftNorm >= 0f &&
            area.topNorm >= 0f &&
            area.rightNorm <= 1f &&
            area.bottomNorm <= 1f &&
            w > 0.01f &&
            h > 0.01f &&
            max(w, h) <= 1f
    }

    private const val MIN_AREA_SIZE_PX = 120
}
