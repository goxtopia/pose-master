package com.example.posturemonitor

data class Point3D(var x: Float, var y: Float, var z: Float, var visibility: Float = 0f)

class PointSmoother(private val alpha: Float = 0.5f) {
    private var history: Point3D? = null

    fun smooth(newValue: Point3D): Point3D {
        if (history == null) {
            history = newValue.copy()
            return history!!
        }
        val h = history!!
        h.x = alpha * newValue.x + (1 - alpha) * h.x
        h.y = alpha * newValue.y + (1 - alpha) * h.y
        h.z = alpha * newValue.z + (1 - alpha) * h.z
        h.visibility = alpha * newValue.visibility + (1 - alpha) * h.visibility
        return h
    }
}
