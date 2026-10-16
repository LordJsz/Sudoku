package com.sudoku.solver.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class ScanAreaSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val selection = RectF()
    private var actionMode: ActionMode = ActionMode.NONE
    private var downX = 0f
    private var downY = 0f

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 63, 169, 245)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val handleRadius = dp(8f)
    private val edgeHit = dp(22f)
    private val minSpan = dp(80f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (selection.isEmpty) {
            val marginX = w * 0.10f
            val marginY = h * 0.10f
            selection.set(marginX, marginY, w - marginX, h - marginY)
        } else {
            clampSelection()
        }
    }

    fun setSelectionNormalized(left: Float, top: Float, right: Float, bottom: Float) {
        if (width <= 0 || height <= 0) return
        selection.set(
            (left.coerceIn(0f, 1f) * width),
            (top.coerceIn(0f, 1f) * height),
            (right.coerceIn(0f, 1f) * width),
            (bottom.coerceIn(0f, 1f) * height)
        )
        clampSelection()
        invalidate()
    }

    fun resetToFull() {
        selection.set(0f, 0f, width.toFloat(), height.toFloat())
        invalidate()
    }

    fun getSelectionNormalized(): RectF {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        return RectF(
            (selection.left / w).coerceIn(0f, 1f),
            (selection.top / h).coerceIn(0f, 1f),
            (selection.right / w).coerceIn(0f, 1f),
            (selection.bottom / h).coerceIn(0f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(0f, 0f, width.toFloat(), selection.top, dimPaint)
        canvas.drawRect(0f, selection.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, selection.top, selection.left, selection.bottom, dimPaint)
        canvas.drawRect(selection.right, selection.top, width.toFloat(), selection.bottom, dimPaint)

        canvas.drawRect(selection, borderPaint)

        canvas.drawCircle(selection.left, selection.top, handleRadius, handlePaint)
        canvas.drawCircle(selection.right, selection.top, handleRadius, handlePaint)
        canvas.drawCircle(selection.left, selection.bottom, handleRadius, handlePaint)
        canvas.drawCircle(selection.right, selection.bottom, handleRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                actionMode = detectActionMode(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                return actionMode != ActionMode.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                applyDrag(dx, dy)
                downX = event.x
                downY = event.y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                actionMode = ActionMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun detectActionMode(x: Float, y: Float): ActionMode {
        val nearLeft = abs(x - selection.left) <= edgeHit
        val nearRight = abs(x - selection.right) <= edgeHit
        val nearTop = abs(y - selection.top) <= edgeHit
        val nearBottom = abs(y - selection.bottom) <= edgeHit

        return when {
            nearLeft && nearTop -> ActionMode.TOP_LEFT
            nearRight && nearTop -> ActionMode.TOP_RIGHT
            nearLeft && nearBottom -> ActionMode.BOTTOM_LEFT
            nearRight && nearBottom -> ActionMode.BOTTOM_RIGHT
            nearLeft && y in selection.top..selection.bottom -> ActionMode.LEFT
            nearRight && y in selection.top..selection.bottom -> ActionMode.RIGHT
            nearTop && x in selection.left..selection.right -> ActionMode.TOP
            nearBottom && x in selection.left..selection.right -> ActionMode.BOTTOM
            selection.contains(x, y) -> ActionMode.MOVE
            else -> ActionMode.NONE
        }
    }

    private fun applyDrag(dx: Float, dy: Float) {
        when (actionMode) {
            ActionMode.MOVE -> selection.offset(dx, dy)
            ActionMode.LEFT -> selection.left += dx
            ActionMode.RIGHT -> selection.right += dx
            ActionMode.TOP -> selection.top += dy
            ActionMode.BOTTOM -> selection.bottom += dy
            ActionMode.TOP_LEFT -> {
                selection.left += dx
                selection.top += dy
            }
            ActionMode.TOP_RIGHT -> {
                selection.right += dx
                selection.top += dy
            }
            ActionMode.BOTTOM_LEFT -> {
                selection.left += dx
                selection.bottom += dy
            }
            ActionMode.BOTTOM_RIGHT -> {
                selection.right += dx
                selection.bottom += dy
            }
            ActionMode.NONE -> Unit
        }
        clampSelection()
    }

    private fun clampSelection() {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        if (selection.width() < minSpan) {
            val cx = selection.centerX()
            selection.left = cx - minSpan / 2f
            selection.right = cx + minSpan / 2f
        }
        if (selection.height() < minSpan) {
            val cy = selection.centerY()
            selection.top = cy - minSpan / 2f
            selection.bottom = cy + minSpan / 2f
        }

        if (selection.left < 0f) selection.offset(-selection.left, 0f)
        if (selection.top < 0f) selection.offset(0f, -selection.top)
        if (selection.right > w) selection.offset(w - selection.right, 0f)
        if (selection.bottom > h) selection.offset(0f, h - selection.bottom)

        selection.left = selection.left.coerceIn(0f, w)
        selection.top = selection.top.coerceIn(0f, h)
        selection.right = selection.right.coerceIn(0f, w)
        selection.bottom = selection.bottom.coerceIn(0f, h)

        if (selection.right - selection.left < minSpan) {
            selection.right = (selection.left + minSpan).coerceAtMost(w)
            selection.left = max(0f, selection.right - minSpan)
        }
        if (selection.bottom - selection.top < minSpan) {
            selection.bottom = (selection.top + minSpan).coerceAtMost(h)
            selection.top = max(0f, selection.bottom - minSpan)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private enum class ActionMode {
        NONE,
        MOVE,
        LEFT,
        RIGHT,
        TOP,
        BOTTOM,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }
}

