package com.focusforge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * The session timeline: one score per second, drawn as a line across the whole session.
 *
 * Deliberately not a chart library — a polyline and two guide lines is the entire
 * requirement, and the APK budget is better spent on the model. Stretches of raised fatigue
 * are drawn in a different colour underneath, so a glance answers "when did I start to
 * fade" without reading any numbers.
 */
class SparklineView(context: Context) : View(context) {

    private var scores: IntArray = IntArray(0)
    private var fatigue: BooleanArray = BooleanArray(0)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val guidePaint = Paint().apply {
        color = Color.parseColor("#33000000")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val fatiguePaint = Paint().apply {
        color = Color.parseColor("#33D32F2F")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88000000")
        textSize = 22f
    }

    fun setData(scores: IntArray, fatigue: BooleanArray) {
        this.scores = scores
        this.fatigue = fatigue
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 28f
        val plotH = h - pad

        fun yFor(score: Int) = plotH - (score / 100f) * (plotH - 4f) + 2f

        // Guides at 100, 50 and 0 so the line has a scale without a full axis.
        for (level in intArrayOf(0, 50, 100)) {
            val y = yFor(level)
            canvas.drawLine(0f, y, w, y, guidePaint)
            canvas.drawText(level.toString(), 4f, y - 4f, labelPaint)
        }

        val n = scores.size
        if (n == 0) {
            canvas.drawText("timeline starts once the session is warmed up", 4f, h / 2f, labelPaint)
            return
        }
        val stepX = if (n <= 1) 0f else w / (n - 1).toFloat()

        // Fatigue stretches first, so the score line draws on top of them.
        var i = 0
        while (i < n) {
            if (i < fatigue.size && fatigue[i]) {
                var j = i
                while (j + 1 < n && j + 1 < fatigue.size && fatigue[j + 1]) j++
                canvas.drawRect(i * stepX, 0f, (j + 1).coerceAtMost(n - 1) * stepX, plotH, fatiguePaint)
                i = j + 1
            } else {
                i++
            }
        }

        val path = Path()
        for (k in 0 until n) {
            val x = k * stepX
            val y = yFor(scores[k])
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        canvas.drawText("start", 4f, h - 4f, labelPaint)
        val nowLabel = "now"
        canvas.drawText(nowLabel, w - labelPaint.measureText(nowLabel) - 4f, h - 4f, labelPaint)
    }
}
