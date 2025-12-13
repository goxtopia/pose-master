package com.example.posturemonitor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker.PoseLandmarkerOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage

class MainActivity : AppCompatActivity() {
    private lateinit var viewFinder: PreviewView
    private lateinit var overlay: OverlayView

    // UI Elements
    private lateinit var statusText: TextView
    private lateinit var deltaText: TextView
    private lateinit var timerJoints: TextView
    private lateinit var timerBody: TextView
    private lateinit var timerGaze: TextView
    private lateinit var alertOverlay: TextView
    private lateinit var inputServerIp: EditText
    private lateinit var btnStart: Button
    private lateinit var btnSwitchCamera: ImageButton

    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    // Logic
    private var isRunning = false
    private val monitor = PostureMonitor()
    private val motionDetector = MotionDetector()
    private var landmarker: PoseLandmarker? = null

    // State
    private var isUserAway = false
    private var lastPixelMotionTime = System.currentTimeMillis()
    private val AWAY_TIMEOUT = 5 * 60 * 1000L

    // Alerting
    private val httpClient = OkHttpClient()
    private var lastAlertTime = 0L
    private var lastSentAlertType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.status_text)
        deltaText = findViewById(R.id.delta_text)
        timerJoints = findViewById(R.id.timer_joints)
        timerBody = findViewById(R.id.timer_body)
        timerGaze = findViewById(R.id.timer_gaze)
        alertOverlay = findViewById(R.id.alert_overlay)
        inputServerIp = findViewById(R.id.input_server_ip)
        btnStart = findViewById(R.id.btn_start)
        btnSwitchCamera = findViewById(R.id.btn_switch_camera)

        if (allPermissionsGranted()) {
            setupMediaPipe()
            startCamera()
        } else {
            requestPermissions.launch(REQUIRED_PERMISSIONS)
        }

        btnStart.setOnClickListener {
            isRunning = !isRunning
            if (isRunning) {
                btnStart.text = "Stop"
                monitor.reset()
                lastPixelMotionTime = System.currentTimeMillis()
                statusText.text = "Status: Running"
                // Load config from server if possible, but for now use defaults
            } else {
                btnStart.text = "Start"
                statusText.text = "Status: Stopped"
                alertOverlay.visibility = View.GONE
                // Send stop to server
                sendApiRequest("/api/stop", null)
            }
        }

        btnSwitchCamera.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            startCamera()
        }
    }

    private fun setupMediaPipe() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_full.task")
            .build()

        val options = PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE) // Using IMAGE mode for manual sync control inside analysis
            // OR use VIDEO/LIVE_STREAM mode. LIVE_STREAM is better for async but we need to control flow with motion detection.
            // Let's use LIVE_STREAM but only feed it when we want?
            // Actually, `checkMotion` runs on Bitmap.
            // Let's use LIVE_STREAM mode.
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, image ->
                // Handle results
                runOnUiThread {
                    processLandmarks(result)
                }
            }
            .setErrorListener { e -> Log.e(TAG, "MediaPipe error: $e") }
            .build()

        try {
            landmarker = PoseLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MediaPipe: ${e.message}")
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(viewFinder.surfaceProvider)

            imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!isRunning) {
            imageProxy.close()
            return
        }

        val bitmap = Bitmap.createBitmap(
            imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
        )
        imageProxy.use {
             bitmap.copyPixelsFromBuffer(it.planes[0].buffer)
        }

        // 1. Motion Detection
        // Resize for performance in motion detector
        val motionBitmap = Bitmap.createScaledBitmap(bitmap, 64, 36, true)
        val hasMotion = motionDetector.checkMotion(motionBitmap)

        val now = System.currentTimeMillis()

        runOnUiThread {
            if (isUserAway) {
                deltaText.text = "User Away (Paused)"
                deltaText.setTextColor(Color.RED)
            } else if (hasMotion) {
                deltaText.text = "Active"
                deltaText.setTextColor(Color.GREEN)
            } else {
                deltaText.text = "Static"
                deltaText.setTextColor(Color.BLUE)
            }
        }

        if (hasMotion) {
            lastPixelMotionTime = now
            if (isUserAway) {
                Log.d(TAG, "User returned")
                isUserAway = false
                monitor.reset()
            }
            // Feed to MediaPipe
            detectPose(bitmap, now)
        } else {
            // No Motion
            if (isUserAway) {
                // Do nothing
            } else {
                 if (now - lastPixelMotionTime > AWAY_TIMEOUT) {
                     // Check if user really gone by running pose once
                     detectPose(bitmap, now)
                     // Logic inside processLandmarks will determine if user is present
                 } else {
                     // Assume static user
                     runOnUiThread {
                        updateMonitorState(null, false)
                     }
                 }
            }
        }
    }

    private fun detectPose(bitmap: Bitmap, timestamp: Long) {
        if (landmarker == null) return
        val mpImage = BitmapImageBuilder(bitmap).build()
        landmarker?.detectAsync(mpImage, timestamp)
    }

    private fun processLandmarks(result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult) {
        val landmarks = result.landmarks()
        val now = System.currentTimeMillis()
        val isVerificationCheck = (now - lastPixelMotionTime > AWAY_TIMEOUT)

        if (landmarks.isNotEmpty() && landmarks[0].isNotEmpty()) {
            val rawList = landmarks[0].map {
                Point3D(it.x(), it.y(), it.z(), it.visibility().orElse(0f))
            }

            overlay.setLandmarks(rawList)

            if (isVerificationCheck) {
                Log.d(TAG, "User verified present (Static)")
                lastPixelMotionTime = now
            }

            updateMonitorState(rawList, !isVerificationCheck)
        } else {
             overlay.setLandmarks(null)
             if (isVerificationCheck) {
                 Log.d(TAG, "User not found -> AWAY")
                 isUserAway = true
                 monitor.reset()
             } else {
                 updateMonitorState(null, true) // Treating empty as movement/noise or just reset? JS treats as null logic.
             }
        }
    }

    private fun updateMonitorState(landmarks: List<Point3D>?, physicalMotion: Boolean) {
        val state = monitor.process(landmarks, physicalMotion)

        timerJoints.text = "Joints: %.1fs".format(state.timers["joints"])
        timerBody.text = "Body: %.1fs".format(state.timers["body"])
        timerGaze.text = "Gaze: %.1fs".format(state.timers["gaze"])

        // Colors
        val lim = monitor.limits
        timerJoints.setTextColor(if(state.timers["joints"]!! * 1000 > lim.joints * 0.8) Color.YELLOW else Color.GREEN)
        timerBody.setTextColor(if(state.timers["body"]!! * 1000 > lim.body * 0.8) Color.YELLOW else Color.GREEN)
        timerGaze.setTextColor(if(state.timers["gaze"]!! * 1000 > lim.gaze * 0.8) Color.YELLOW else Color.GREEN)

        if (state.alertType != null) {
            alertOverlay.visibility = View.VISIBLE
            alertOverlay.text = "⚠️ ${state.alertType.toUpperCase()} ALERT ⚠️"

            val now = System.currentTimeMillis()
            var alertInterval = 60000L
            val currentDurationMs = (state.timers[state.alertType] ?: 0f) * 1000
            val limitMs = when(state.alertType) {
                "body" -> lim.body
                "joints" -> lim.joints
                "gaze" -> lim.gaze
                else -> 0L
            }

            if (currentDurationMs > limitMs) {
                val overtimeMs = currentDurationMs - limitMs
                val coefficient = 300000L
                alertInterval = (60000.0 / (1 + (overtimeMs.toDouble() / coefficient))).toLong()
                alertInterval = max(10000L, alertInterval)
            }

            if (now - lastAlertTime > alertInterval || state.alertType != lastSentAlertType) {
                triggerAlert(state.alertType)
                lastAlertTime = now
                lastSentAlertType = state.alertType
            }

        } else {
            alertOverlay.visibility = View.GONE
            if (lastSentAlertType != null) {
                lastSentAlertType = null
            }
        }
    }

    private fun triggerAlert(type: String?) {
        val ip = inputServerIp.text.toString()
        if (ip.isEmpty()) return

        // Construct endpoint logic similar to JS
        val endpoint = when(type) {
            "joints", "gaze", "body" -> "/api/say"
            else -> "/api/relax"
        }

        val messages = mapOf(
            "joints" to "长时间保持一个姿势了，活动一下关节吧",
            "body" to "坐太久了，起来走两步吧",
            "gaze" to "眼睛累了吗，看看远处吧"
        )

        val json = if (type in messages) {
             "{\"text\": \"${messages[type]}\"}"
        } else {
            "{}"
        }

        sendApiRequest(endpoint, json)
    }

    private fun sendApiRequest(endpoint: String, jsonBody: String?) {
        val ip = inputServerIp.text.toString()
        val url = "http://$ip:8080$endpoint"

        val requestBuilder = Request.Builder().url(url)
        if (jsonBody != null) {
            val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            requestBuilder.post(body)
        }

        httpClient.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "API Fail: $e")
            }
            override fun onResponse(call: Call, response: Response) {
                 Log.d(TAG, "API Success: ${response.code}")
                 response.close()
            }
        })
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (allPermissionsGranted()) {
                setupMediaPipe()
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        landmarker?.close()
    }

    companion object {
        private const val TAG = "PostureMonitor"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(Manifest.permission.CAMERA).toTypedArray()
    }
}
