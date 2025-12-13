package com.example.posturemonitor

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.abs

class MotionDetector(private val width: Int = 64, private val height: Int = 36) {
    private var prevBuffer: IntArray? = null
    // default 30 in JS (0-255), Java Color is mostly used here but we'll do raw diff
    private var threshold: Int = 30
    private var percentThreshold: Double = 0.02

    // We expect a small bitmap to check motion on
    fun checkMotion(bitmap: Bitmap): Boolean {
        // Resize if needed, but assuming caller passes small bitmap for performance
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaledBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        if (prevBuffer == null) {
            prevBuffer = pixels
            return true
        }

        var diffCount = 0
        val totalPixels = pixels.size
        val pb = prevBuffer!!

        // Step by 2 or more for performance? JS code stepped by 8 (r,g,b,a, r,g,b,a...) which is every 2nd pixel
        // Here pixels array is int color (ARGB).
        // Let's check every 2nd pixel
        for (i in pixels.indices step 2) {
            val c1 = pixels[i]
            val c2 = pb[i]

            val rDiff = abs(Color.red(c1) - Color.red(c2))
            val gDiff = abs(Color.green(c1) - Color.green(c2))
            val bDiff = abs(Color.blue(c1) - Color.blue(c2))

            if (rDiff + gDiff + bDiff > threshold * 3) {
                diffCount++
            }
        }

        prevBuffer = pixels
        // JS: (diffCount * 2) / totalPixels because it skipped every other pixel (step 8 on byte array)
        // Here we step 2 on int array, so similar logic
        val changedPercent = (diffCount.toDouble() * 2) / totalPixels
        return changedPercent > percentThreshold
    }

    fun updateSensitivity(value: Int) {
        val inverted = 101 - value
        threshold = (10 + (inverted / 100.0) * 50).toInt()
        percentThreshold = 0.005 + (inverted / 100.0) * 0.05
        Log.d("MotionDetector", "Updated: T=$threshold, P=$percentThreshold")
    }
}
