package com.example.posturemonitor

import kotlin.math.pow
import kotlin.math.sqrt

class StabilityBuffer(private val size: Int = 30) {
    private val buffer = ArrayDeque<Point3D>()

    fun push(point: Point3D?) {
        if (point == null) return
        buffer.addLast(point)
        if (buffer.size > size) {
            buffer.removeFirst()
        }
    }

    data class Stats(val x: Float, val y: Float, val stdDev: Float)

    fun getStats(): Stats? {
        if (buffer.size < 5) return null

        var sumX = 0f
        var sumY = 0f
        buffer.forEach { p ->
            sumX += p.x
            sumY += p.y
        }
        val meanX = sumX / buffer.size
        val meanY = sumY / buffer.size

        var sumSqDiff = 0f
        buffer.forEach { p ->
            sumSqDiff += (p.x - meanX).pow(2) + (p.y - meanY).pow(2)
        }
        val variance = sumSqDiff / buffer.size
        val stdDev = sqrt(variance)

        return Stats(meanX, meanY, stdDev)
    }

    fun reset() {
        buffer.clear()
    }
}
