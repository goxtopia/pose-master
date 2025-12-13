package com.example.posturemonitor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class PostureMonitor {
    private val smoothers = mutableMapOf<Int, PointSmoother>()
    private var bodyCenterSmoother = PointSmoother(0.05f)
    private val stabilityBuffer = StabilityBuffer(30)

    private var anchorPose: List<Point3D>? = null
    private var anchorYaw: Float? = null
    private var anchorBodyCenter: Point3D? = null

    var lastBodyMove: Long = System.currentTimeMillis()
    var lastJointsMove: Long = System.currentTimeMillis()
    var lastGazeMove: Long = System.currentTimeMillis()

    var isStatic = false

    data class Limits(var joints: Long = 1500000, var body: Long = 1800000, var gaze: Long = 600000)
    var limits = Limits()

    private var movementThreshold = 0.05f
    private val yawThreshold = 0.15f
    private val smoothingAlpha = 0.2f
    private val coarseThreshold = 0.05f

    // Indices: Nose(0), L-Eye(2), R-Eye(5), L-Ear(7), R-Ear(8), L-Shoulder(11), R-Shoulder(12)
    private val keyIndices = listOf(0, 2, 5, 7, 8, 11, 12)

    fun updateConfig(newLimits: Limits, sensitivity: Int) {
        limits = newLimits
        movementThreshold = 0.15f - (sensitivity / 100f) * 0.14f
    }

    fun reset() {
        anchorPose = null
        anchorYaw = null
        anchorBodyCenter = null

        val now = System.currentTimeMillis()
        lastBodyMove = now
        lastJointsMove = now
        lastGazeMove = now

        isStatic = false
        smoothers.clear()
        bodyCenterSmoother = PointSmoother(0.05f)
        stabilityBuffer.reset()
    }

    private fun calculateYaw(landmarks: List<Point3D>): Float? {
        if (landmarks.isEmpty()) return null
        val nose = landmarks[0]
        val leftEar = landmarks[7]
        val rightEar = landmarks[8]
        if (nose.visibility < 0.5f || leftEar.visibility < 0.5f || rightEar.visibility < 0.5f) return null
        val midEarX = (leftEar.x + rightEar.x) / 2
        val earDist = abs(leftEar.x - rightEar.x)
        if (earDist == 0f) return 0f
        return (nose.x - midEarX) / earDist
    }

    fun calculateBodyCenter(landmarks: List<Point3D>): Point3D? {
        // 11,12 (shoulders), 23,24 (hips)
        val indices = listOf(11, 12, 23, 24)
        var sumX = 0f
        var sumY = 0f
        var count = 0
        indices.forEach { idx ->
            if (idx < landmarks.size) {
                val p = landmarks[idx]
                if (p.visibility > 0.5f) {
                    sumX += p.x
                    sumY += p.y
                    count++
                }
            }
        }
        if (count == 0) return null
        return Point3D(sumX / count, sumY / count, 0f, 1f)
    }

    data class State(
        val timers: Map<String, Float>,
        val alertType: String?
    )

    fun process(rawLandmarks: List<Point3D>?, motionDetected: Boolean): State {
        val now = System.currentTimeMillis()
        var jointsMoved = false
        var gazeMoved = false
        var bodyMoved = false

        var bodyCenter: Point3D? = null
        var bodyStats: StabilityBuffer.Stats? = null

        if (motionDetected && rawLandmarks != null) {
            val smoothedLandmarks = ArrayList<Point3D>()
            for (i in rawLandmarks.indices) {
                val s = smoothers.getOrPut(i) { PointSmoother(smoothingAlpha) }
                smoothedLandmarks.add(s.smooth(rawLandmarks[i]))
            }

            val rawCenter = calculateBodyCenter(smoothedLandmarks)
            if (rawCenter != null) {
                bodyCenter = bodyCenterSmoother.smooth(rawCenter)
                stabilityBuffer.push(bodyCenter)
                bodyStats = stabilityBuffer.getStats()
            }

            val currentYaw = calculateYaw(smoothedLandmarks)

            if (anchorPose == null) {
                anchorPose = smoothedLandmarks.map { it.copy() }
                anchorYaw = currentYaw
                anchorBodyCenter = if (bodyStats != null) Point3D(bodyStats.x, bodyStats.y, 0f) else bodyCenter?.copy()

                lastBodyMove = now
                lastJointsMove = now
                lastGazeMove = now
                return State(mapOf("joints" to 0f, "body" to 0f, "gaze" to 0f), null)
            }

            // Joints Logic
            var totalDelta = 0f
            var validPoints = 0
            keyIndices.forEach { idx ->
                val p1 = anchorPose!![idx]
                val p2 = smoothedLandmarks[idx]
                if (p1.visibility > 0.5f && p2.visibility > 0.5f) {
                    val dist = sqrt((p1.x - p2.x).pow(2) + (p1.y - p2.y).pow(2))
                    totalDelta += dist
                    validPoints++
                }
            }
            val avgDelta = if (validPoints > 0) totalDelta / validPoints else 0f

            if (avgDelta > max(0.015f, movementThreshold)) {
                jointsMoved = true
                anchorPose = smoothedLandmarks.map { it.copy() }
            }

            // Gaze Logic
            if (currentYaw != null && anchorYaw != null) {
                val yawDelta = abs(currentYaw - anchorYaw!!)
                if (yawDelta > yawThreshold) {
                    gazeMoved = true
                    anchorYaw = currentYaw
                }
            }

            // Body Logic
            if (bodyStats != null && anchorBodyCenter != null) {
                val dist = sqrt((bodyStats.x - anchorBodyCenter!!.x).pow(2) + (bodyStats.y - anchorBodyCenter!!.y).pow(2))
                val noiseFactor = min(bodyStats.stdDev * 2, 0.05f)
                val effectiveThreshold = coarseThreshold + noiseFactor

                if (dist > effectiveThreshold) {
                    bodyMoved = true
                    anchorBodyCenter = Point3D(bodyStats.x, bodyStats.y, 0f)
                }
            }
        }

        if (bodyMoved) {
            lastBodyMove = now
            lastJointsMove = now
            lastGazeMove = now
        } else if (jointsMoved) {
            lastJointsMove = now
        }

        if (gazeMoved) {
            lastGazeMove = now
        }

        val tBody = now - lastBodyMove
        val tJoints = now - lastJointsMove
        val tGaze = now - lastGazeMove

        var alertType: String? = null
        if (tBody > limits.body) alertType = "body"
        else if (tJoints > limits.joints) alertType = "joints"
        else if (tGaze > limits.gaze) alertType = "gaze"

        return State(
            mapOf("joints" to tJoints / 1000f, "body" to tBody / 1000f, "gaze" to tGaze / 1000f),
            alertType
        )
    }
}
