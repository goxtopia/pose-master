package com.example.posturemonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var landmarks: List<Point3D>? = null
    private val paintLine = Paint().apply {
        color = Color.GREEN
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }
    private val paintPoint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        strokeWidth = 10f
    }
    private val paintCenter = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
    }

    private val bodyConnections = listOf(
        Pair(11, 12), Pair(11, 23), Pair(12, 24), Pair(23, 24),
        Pair(11, 13), Pair(13, 15), Pair(12, 14), Pair(14, 16),
        Pair(23, 25), Pair(25, 27), Pair(24, 26), Pair(26, 28)
    )

    fun setLandmarks(newLandmarks: List<Point3D>?) {
        landmarks = newLandmarks
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val list = landmarks ?: return

        // Draw Connections
        for (conn in bodyConnections) {
            val idx1 = conn.first
            val idx2 = conn.second
            if (idx1 < list.size && idx2 < list.size) {
                val p1 = list[idx1]
                val p2 = list[idx2]
                // Visibility check
                if (p1.visibility > 0.5f && p2.visibility > 0.5f) {
                     canvas.drawLine(
                         p1.x * width, p1.y * height,
                         p2.x * width, p2.y * height,
                         paintLine
                     )
                }
            }
        }

        // Draw Landmarks (body only > 10)
        for ((i, p) in list.withIndex()) {
            if (i > 10 && p.visibility > 0.5f) {
                canvas.drawCircle(p.x * width, p.y * height, 8f, paintPoint)
            }
        }
    }
}
