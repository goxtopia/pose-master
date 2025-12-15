package com.example.posturemonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.EngineInfo
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import org.json.JSONObject

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var viewFinder: PreviewView
    private lateinit var overlay: OverlayView

    // UI Elements
    private lateinit var statusText: TextView
    private lateinit var deltaText: TextView
    private lateinit var timerJoints: TextView
    private lateinit var timerBody: TextView
    private lateinit var timerGaze: TextView
    private lateinit var alertOverlay: TextView
    private lateinit var pausedIndicator: View
    private lateinit var btnStart: Button
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnHideUi: FloatingActionButton
    private lateinit var btnRestoreUi: FloatingActionButton
    private lateinit var btnAdvancedSettings: Button
    private lateinit var btnStatistics: Button

    // Settings
    private lateinit var inputJoints: EditText
    private lateinit var inputBody: EditText
    private lateinit var inputGaze: EditText
    private lateinit var seekSensitivity: SeekBar
    private lateinit var seekMotionSensitivity: SeekBar

    // TTS UI
    private lateinit var switchShowFace: SwitchMaterial

    // Advanced Settings State
    private var useLocalTts = true
    private var serverIp = "10.0.2.2"

    // Statistics & Session Logic
    private var sessionStartTime = 0L
    private val SESSION_LIMIT_MS = 30 * 60 * 1000L // 30 minutes
    private var lastStatsUpdate = 0L
    // Shared state variables
    @Volatile private var dailyAlerts = 0
    @Volatile private var dailyMonitoringMs = 0L
    @Volatile private var dailyBreakMs = 0L
    @Volatile private var lastDayOfYear = -1
    private val statsLock = Any()

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

    // System Services
    private lateinit var audioManager: AudioManager
    private lateinit var vibrator: Vibrator

    // State
    private var isUiHidden = false
    private var isPipMode = false
    private var isUserAway = false
    private var lastPixelMotionTime = System.currentTimeMillis()
    private val AWAY_TIMEOUT = 10 * 1000L

    // Alerting
    private val httpClient = OkHttpClient()
    private var lastAlertTime = 0L
    private var lastSentAlertType: String? = null

    // TTS State
    private var tts: TextToSpeech? = null
    private var availableEngines: List<EngineInfo> = emptyList()
    private var selectedEnginePackage: String? = null
    private var isTtsReady = false

    // Gesture Detector
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on to prevent sleeping during monitoring
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        // Initialize System Services
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // Bind UI
        viewFinder = findViewById(R.id.viewFinder)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.status_text)
        deltaText = findViewById(R.id.delta_text)
        timerJoints = findViewById(R.id.timer_joints)
        timerBody = findViewById(R.id.timer_body)
        timerGaze = findViewById(R.id.timer_gaze)
        alertOverlay = findViewById(R.id.alert_overlay)
        pausedIndicator = findViewById(R.id.paused_indicator)
        btnStart = findViewById(R.id.btn_start)
        btnSwitchCamera = findViewById(R.id.btn_switch_camera)
        btnHideUi = findViewById(R.id.btn_hide_ui)
        btnRestoreUi = findViewById(R.id.btn_restore_ui)
        btnAdvancedSettings = findViewById(R.id.btn_advanced_settings)
        btnStatistics = findViewById(R.id.btn_statistics)

        inputJoints = findViewById(R.id.input_joints)
        inputBody = findViewById(R.id.input_body)
        inputGaze = findViewById(R.id.input_gaze)
        seekSensitivity = findViewById(R.id.seek_sensitivity)
        seekMotionSensitivity = findViewById(R.id.seek_motion_sensitivity)

        switchShowFace = findViewById(R.id.switch_show_face)

        // Initialize defaults or load from prefs
        loadConfig()

        // Initialize TTS with default engine
        initTts(selectedEnginePackage)

        if (allPermissionsGranted()) {
            setupMediaPipe()
            startCamera()
        } else {
            requestPermissions.launch(REQUIRED_PERMISSIONS)
        }

        btnStart.setOnClickListener {
            toggleMonitoring()
        }

        btnSwitchCamera.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            startCamera()
        }

        btnHideUi.setOnClickListener {
            isUiHidden = true
            updateUiVisibility()
        }

        btnRestoreUi.setOnClickListener {
            isUiHidden = false
            updateUiVisibility()
        }

        btnAdvancedSettings.setOnClickListener {
            showAdvancedSettings()
        }

        btnStatistics.setOnClickListener {
            showStatistics()
        }

        // Auto-save listeners
        val saveListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveConfig()
        }
        inputJoints.onFocusChangeListener = saveListener
        inputBody.onFocusChangeListener = saveListener
        inputGaze.onFocusChangeListener = saveListener

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateConfigFromUI() // Live update
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { saveConfig() }
        }
        seekSensitivity.setOnSeekBarChangeListener(seekListener)
        seekMotionSensitivity.setOnSeekBarChangeListener(seekListener)

        switchShowFace.setOnCheckedChangeListener { _, isChecked ->
            overlay.showFacePoints = isChecked
            saveConfig()
        }

        setupGestureDetector()

        // Initial state
        updatePausedIndicator()
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleMonitoring()
                return true
            }
        })

        viewFinder.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun toggleMonitoring() {
        val now = System.currentTimeMillis()
        isRunning = !isRunning
        if (isRunning) {
            // Start Monitoring
            btnStart.text = getString(R.string.btn_stop)
            btnStart.setBackgroundColor(Color.RED)
            // Apply config
            updateConfigFromUI()

            monitor.reset()
            lastPixelMotionTime = now
            sessionStartTime = now // Start/Resume session timer

            // Break time logic: if we have a previous stop time, add to break time
            synchronized(statsLock) {
                if (lastStatsUpdate > 0) {
                    val breakDuration = now - lastStatsUpdate
                    // Basic sanity check: less than 24h
                    if (breakDuration > 0 && breakDuration < 24 * 3600 * 1000) {
                        dailyBreakMs += breakDuration
                        saveStats()
                    }
                }
            }
            lastStatsUpdate = now

            statusText.text = getString(R.string.status_running)
            Toast.makeText(this, "Monitoring Started", Toast.LENGTH_SHORT).show()
        } else {
            // Stop Monitoring
            btnStart.text = getString(R.string.btn_start)
            btnStart.setBackgroundColor(Color.LTGRAY)
            statusText.text = getString(R.string.status_stopped)
            alertOverlay.visibility = View.GONE

            updateStats(true) // Commit monitoring time
            lastStatsUpdate = now // Mark time stopped, for next break calculation

            sendApiRequest("/api/stop", null)
            Toast.makeText(this, "Monitoring Stopped", Toast.LENGTH_SHORT).show()
        }
        updatePausedIndicator()
    }

    private fun updatePausedIndicator() {
        if (isRunning) {
            pausedIndicator.visibility = View.GONE
        } else {
            // Show only if not in PiP
            pausedIndicator.visibility = if (!isPipMode) View.VISIBLE else View.GONE
        }
    }

    private fun updateStats(isRunningState: Boolean) {
        synchronized(statsLock) {
            val now = System.currentTimeMillis()
            val delta = now - lastStatsUpdate
            if (delta > 0) {
                if (isRunningState) {
                    dailyMonitoringMs += delta
                }
            }
            lastStatsUpdate = now

            // Check Day Reset
            val calendar = java.util.Calendar.getInstance()
            val day = calendar.get(java.util.Calendar.DAY_OF_YEAR)
            if (day != lastDayOfYear) {
                dailyAlerts = 0
                dailyMonitoringMs = 0
                dailyBreakMs = 0
                lastDayOfYear = day
            }
            saveStats()
        }
    }

    private fun showAdvancedSettings() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_advanced_settings, null)
        val switchLocalTts = dialogView.findViewById<SwitchMaterial>(R.id.switch_local_tts)
        val labelTtsEngine = dialogView.findViewById<TextView>(R.id.label_tts_engine)
        val spinnerTtsEngine = dialogView.findViewById<Spinner>(R.id.spinner_tts_engine)
        val inputServerIpField = dialogView.findViewById<TextInputEditText>(R.id.input_server_ip)

        // Initialize values
        switchLocalTts.isChecked = useLocalTts
        inputServerIpField.setText(serverIp)

        // Logic for TTS engine spinner
        fun updateTtsUi(isLocal: Boolean) {
            if (isLocal) {
                labelTtsEngine.visibility = View.VISIBLE
                spinnerTtsEngine.visibility = View.VISIBLE
            } else {
                labelTtsEngine.visibility = View.GONE
                spinnerTtsEngine.visibility = View.GONE
            }
        }
        updateTtsUi(useLocalTts)

        // Populate Spinner
        if (tts != null) {
            try {
                // Refresh engines list
                availableEngines = tts!!.engines
                val engineNames = availableEngines.map { "${it.label} (${it.name})" }
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, engineNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerTtsEngine.adapter = adapter

                if (selectedEnginePackage == null) {
                     selectedEnginePackage = tts!!.defaultEngine
                }
                val index = availableEngines.indexOfFirst { it.name == selectedEnginePackage }
                if (index != -1) {
                    spinnerTtsEngine.setSelection(index, false)
                }
            } catch (e: Exception) {
                 Log.e(TAG, "Failed to get TTS engines: $e")
            }
        }

        // Listeners
        switchLocalTts.setOnCheckedChangeListener { _, isChecked ->
            updateTtsUi(isChecked)
        }

        spinnerTtsEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position >= 0 && position < availableEngines.size) {
                    val newPkg = availableEngines[position].name
                    if (newPkg != selectedEnginePackage) {
                        Log.d(TAG, "Switching TTS Engine to: $newPkg")
                        selectedEnginePackage = newPkg
                        initTts(newPkg)
                        saveConfig()
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setOnDismissListener {
                // Save settings on dismiss
                useLocalTts = switchLocalTts.isChecked
                serverIp = inputServerIpField.text.toString()
                saveConfig()
            }
            .show()
    }

    private fun initTts(packageName: String?) {
        // Shutdown old instance if exists
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) { Log.e(TAG, "Error stopping old TTS: $e") }

        isTtsReady = false
        tts = if (packageName != null) {
            TextToSpeech(this, this, packageName)
        } else {
            TextToSpeech(this, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val systemLocale = Locale.getDefault()
            val targetLocale = if (systemLocale.language == Locale.CHINESE.language) {
                Locale.CHINESE
            } else {
                Locale.US
            }

            val result = tts?.setLanguage(targetLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                 Log.e(TAG, "TTS Language not supported: $targetLocale")
                 tts?.setLanguage(Locale.US)
            }
            isTtsReady = true
        } else {
            Log.e(TAG, "TTS Initialization failed")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // If app is running, enter PiP mode automatically
        if (isRunning) {
            enterPictureInPictureMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPipMode = isInPictureInPictureMode
        updateUiVisibility()
        updatePausedIndicator()
    }

    private fun updateUiVisibility() {
        val settingsPanel = findViewById<View>(R.id.settings_panel)
        val statusCard = findViewById<View>(R.id.status_card)

        // Settings: Hidden if PiP OR UserHidden
        val showSettings = !isPipMode && !isUiHidden
        settingsPanel.visibility = if (showSettings) View.VISIBLE else View.GONE

        // Status: Hidden if PiP. Visible otherwise (Always shown in UserHidden mode per request)
        val showStatus = !isPipMode
        statusCard.visibility = if (showStatus) View.VISIBLE else View.GONE

        // Main Buttons: Hidden if PiP OR UserHidden
        val showControls = !isPipMode && !isUiHidden
        btnStart.visibility = if (showControls) View.VISIBLE else View.GONE
        btnSwitchCamera.visibility = if (showControls) View.VISIBLE else View.GONE
        btnHideUi.visibility = if (showControls) View.VISIBLE else View.GONE

        // Restore Button: Hidden if PiP. Visible ONLY if UserHidden
        val showRestore = !isPipMode && isUiHidden
        btnRestoreUi.visibility = if (showRestore) View.VISIBLE else View.GONE

        // Also toggle status/timer texts explicitly (though they are inside status_card)
        // No need if status_card handles it.
    }

    private fun updateConfigFromUI() {
        try {
            val joints = inputJoints.text.toString().toLongOrNull() ?: 1500
            val body = inputBody.text.toString().toLongOrNull() ?: 1800
            val gaze = inputGaze.text.toString().toLongOrNull() ?: 120
            val sens = seekSensitivity.progress
            val motionSens = seekMotionSensitivity.progress

            monitor.updateConfig(
                PostureMonitor.Limits(joints * 1000, body * 1000, gaze * 1000),
                sens
            )
            motionDetector.updateSensitivity(motionSens)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating config: $e")
        }
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences("PosturePrefs", MODE_PRIVATE)

        inputJoints.setText(prefs.getInt("joints", 1500).toString())
        inputBody.setText(prefs.getInt("body", 1800).toString())
        inputGaze.setText(prefs.getInt("gaze", 120).toString())
        seekSensitivity.progress = prefs.getInt("sensitivity", 50)
        seekMotionSensitivity.progress = prefs.getInt("motionSensitivity", 60)

        // Default Local TTS to true
        useLocalTts = prefs.getBoolean("useLocalTts", true)
        serverIp = prefs.getString("serverIp", "10.0.2.2") ?: "10.0.2.2"

        selectedEnginePackage = prefs.getString("ttsEngine", null)

        val showFace = prefs.getBoolean("showFacePoints", false)
        switchShowFace.isChecked = showFace
        overlay.showFacePoints = showFace

        // Load Stats
        lastDayOfYear = prefs.getInt("stats_day", -1)
        val calendar = java.util.Calendar.getInstance()
        val currentDay = calendar.get(java.util.Calendar.DAY_OF_YEAR)

        if (lastDayOfYear != currentDay) {
            // New day or first run
            lastDayOfYear = currentDay
            dailyAlerts = 0
            dailyMonitoringMs = 0L
            dailyBreakMs = 0L
        } else {
            dailyAlerts = prefs.getInt("stats_alerts", 0)
            dailyMonitoringMs = prefs.getLong("stats_monitoring", 0L)
            dailyBreakMs = prefs.getLong("stats_break", 0L)
        }

        updateConfigFromUI()
        Log.d(TAG, "Config loaded from prefs")
    }

    private fun saveStats() {
        val prefs = getSharedPreferences("PosturePrefs", MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt("stats_day", lastDayOfYear)
        editor.putInt("stats_alerts", dailyAlerts)
        editor.putLong("stats_monitoring", dailyMonitoringMs)
        editor.putLong("stats_break", dailyBreakMs)
        editor.apply()
    }

    private fun saveConfig() {
        val prefs = getSharedPreferences("PosturePrefs", MODE_PRIVATE)
        val editor = prefs.edit()

        editor.putInt("joints", inputJoints.text.toString().toIntOrNull() ?: 1500)
        editor.putInt("body", inputBody.text.toString().toIntOrNull() ?: 1800)
        editor.putInt("gaze", inputGaze.text.toString().toIntOrNull() ?: 120)
        editor.putInt("sensitivity", seekSensitivity.progress)
        editor.putInt("motionSensitivity", seekMotionSensitivity.progress)

        editor.putBoolean("useLocalTts", useLocalTts)
        editor.putString("ttsEngine", selectedEnginePackage)
        editor.putBoolean("showFacePoints", switchShowFace.isChecked)
        editor.putString("serverIp", serverIp)

        editor.apply()
        Log.d(TAG, "Config saved to prefs")
    }

    private fun setupMediaPipe() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_full.task")
            .build()

        val options = PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, image ->
                runOnUiThread { processLandmarks(result) }
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
            // Force TextureView implementation for better Z-order compatibility with OverlayView
            viewFinder.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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

        // Update Monitoring Stats continuously
        val now = System.currentTimeMillis()
        if (now - lastStatsUpdate > 1000) { // Update every second
             updateStats(true)

             // Check Session Limit (30 mins)
             if (now - sessionStartTime > SESSION_LIMIT_MS) {
                 runOnUiThread {
                     triggerAlert("walk")
                 }
                 sessionStartTime = now // Reset for next 30m
             }
        }

        val rawBitmap = Bitmap.createBitmap(
            imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
        )
        // Copy pixels
        rawBitmap.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

        // Rotate bitmap to match display orientation
        val rotation = imageProxy.imageInfo.rotationDegrees
        val bitmap = if (rotation != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotation.toFloat())
            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        } else {
            rawBitmap
        }

        // Close proxy
        imageProxy.close()

        val motionBitmap = Bitmap.createScaledBitmap(bitmap, 64, 36, true)
        val hasMotion = motionDetector.checkMotion(motionBitmap)

        runOnUiThread {
            if (isUserAway) {
                deltaText.text = getString(R.string.sensor_away)
                deltaText.setTextColor(Color.RED)
            } else if (hasMotion) {
                deltaText.text = getString(R.string.sensor_active)
                deltaText.setTextColor(Color.GREEN)
            } else {
                deltaText.text = getString(R.string.sensor_static)
                deltaText.setTextColor(Color.BLUE)
            }
        }

        if (hasMotion) {
            lastPixelMotionTime = now
            if (isUserAway) {
                Log.d(TAG, "User returned")
                isUserAway = false
                // No need to reset here
                // monitor.reset()
            }
            detectPose(bitmap, now)
        } else {
            if (isUserAway) {
                // Do nothing
            } else {
                 if (now - lastPixelMotionTime > AWAY_TIMEOUT) {
                     detectPose(bitmap, now)
                 } else {
                     runOnUiThread { updateMonitorState(null, false) }
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
            val isFrontCamera = (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)
            val rawList = landmarks[0].map {
                // Mirror x-coordinate if using front camera to align with mirrored preview
                val x = if (isFrontCamera) 1f - it.x() else it.x()

                // We default visibility to 1f because retrieving the optional visibility can be problematic
                // on some MediaPipe versions, and we want to ensure joints are displayed.
                // Note: We MUST pass 1f even for head joints (indices <= 10) because PostureMonitor
                // needs them (nose, ears) for Gaze detection (calculateYaw).
                // The OverlayView class explicitly filters out indices <= 10 from drawing, so they won't be displayed.
                Point3D(x, it.y(), it.z(), 1f)
            }

            overlay.setLandmarks(rawList)

            lastPixelMotionTime = now

            updateMonitorState(rawList, !isVerificationCheck)
        } else {
             overlay.setLandmarks(null)
             if (isVerificationCheck) {
                 Log.d(TAG, "User not found -> AWAY")
                 isUserAway = true
                 // monitor.reset()
             } else {
                 updateMonitorState(null, true)
             }
        }
    }

    private fun updateMonitorState(landmarks: List<Point3D>?, physicalMotion: Boolean) {
        // User is present if landmarks is not null/empty
        val state = monitor.process(landmarks, physicalMotion)

        timerJoints.text = getString(R.string.timer_joints, state.timers["joints"])
        timerBody.text = getString(R.string.timer_body, state.timers["body"])
        timerGaze.text = getString(R.string.timer_gaze, state.timers["gaze"])

        val lim = monitor.limits
        timerJoints.setTextColor(if(state.timers["joints"]!! * 1000 > lim.joints * 0.8) Color.YELLOW else Color.GREEN)
        timerBody.setTextColor(if(state.timers["body"]!! * 1000 > lim.body * 0.8) Color.YELLOW else Color.GREEN)
        timerGaze.setTextColor(if(state.timers["gaze"]!! * 1000 > lim.gaze * 0.8) Color.YELLOW else Color.GREEN)

        if (state.alertType != null) {
            alertOverlay.visibility = View.VISIBLE
            alertOverlay.text = getString(R.string.alert_prefix, state.alertType.toUpperCase())

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
                alertInterval = java.lang.Math.max(10000L, alertInterval)
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
        synchronized(statsLock) {
            dailyAlerts++
            saveStats()
        }

        val messages = mapOf(
            "joints" to getString(R.string.msg_joints),
            "body" to getString(R.string.msg_body),
            "gaze" to getString(R.string.msg_gaze),
            "walk" to getString(R.string.msg_walk)
        )

        val fallback = getString(R.string.msg_relax)

        // Determine what to say
        val messageToSpeak = messages[type] ?: fallback

        // Vibration Logic
        val ringerMode = audioManager.ringerMode
        val musicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // Vibrate if silent/vibrate mode OR if media volume is 0
        if (ringerMode == AudioManager.RINGER_MODE_SILENT ||
            ringerMode == AudioManager.RINGER_MODE_VIBRATE ||
            musicVolume == 0) {

            if (vibrator.hasVibrator()) {
                val pattern = longArrayOf(0, 400, 200, 400, 200, 400) // 3 pulses
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }

            // If strictly silent (and not just media volume 0 which might allow TTS on some channels but usually means user wants silence), don't speak
            if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                return
            }
        }

        if (useLocalTts) {
            // Local TTS
            if (isTtsReady && tts != null) {
                Log.d(TAG, "Local TTS speaking: $messageToSpeak")
                tts?.speak(messageToSpeak, TextToSpeech.QUEUE_FLUSH, null, "alert_${System.currentTimeMillis()}")
            } else {
                Log.e(TAG, "Local TTS not ready")
            }
        } else {
            // Server TTS
            val ip = serverIp
            if (ip.isEmpty()) return

            val endpoint = when(type) {
                "joints", "gaze", "body", "walk" -> "/api/say"
                else -> "/api/relax"
            }

            val json = if (type in messages) {
                 "{\"text\": \"${messages[type]}\"}"
            } else {
                "{}"
            }

            sendApiRequest(endpoint, json)
        }
    }

    private fun sendApiRequest(endpoint: String, jsonBody: String?) {
        val ip = serverIp
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

    private fun showStatistics() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_statistics, null)
        val txtAlerts = dialogView.findViewById<TextView>(R.id.stat_alerts)
        val txtMonitoring = dialogView.findViewById<TextView>(R.id.stat_monitoring)
        val txtBreak = dialogView.findViewById<TextView>(R.id.stat_break)

        // Format times
        fun formatDuration(ms: Long): String {
            val seconds = ms / 1000
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            return String.format("%dh %dm", hours, minutes)
        }

        txtAlerts.text = dailyAlerts.toString()
        txtMonitoring.text = formatDuration(dailyMonitoringMs)
        txtBreak.text = formatDuration(dailyBreakMs)

        MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setPositiveButton("OK", null)
            .show()
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
        tts?.stop()
        tts?.shutdown()
    }

    companion object {
        private const val TAG = "PostureMonitor"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(Manifest.permission.CAMERA).toTypedArray()
    }
}
