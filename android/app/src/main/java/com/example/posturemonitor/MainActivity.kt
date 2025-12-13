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
    private lateinit var inputServerIp: EditText
    private lateinit var btnStart: Button
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnHideUi: FloatingActionButton
    private lateinit var btnRestoreUi: FloatingActionButton

    // Settings
    private lateinit var inputJoints: EditText
    private lateinit var inputBody: EditText
    private lateinit var inputGaze: EditText
    private lateinit var seekSensitivity: SeekBar
    private lateinit var seekMotionSensitivity: SeekBar

    // TTS UI
    private lateinit var switchLocalTts: SwitchMaterial
    private lateinit var switchShowFace: SwitchMaterial
    private lateinit var labelTtsEngine: TextView
    private lateinit var spinnerTtsEngine: Spinner

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
    private var isUserAway = false
    private var lastPixelMotionTime = System.currentTimeMillis()
    private val AWAY_TIMEOUT = 5 * 60 * 1000L

    // Alerting
    private val httpClient = OkHttpClient()
    private var lastAlertTime = 0L
    private var lastSentAlertType: String? = null

    // TTS State
    private var tts: TextToSpeech? = null
    private var availableEngines: List<EngineInfo> = emptyList()
    private var selectedEnginePackage: String? = null
    private var isTtsReady = false

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
        inputServerIp = findViewById(R.id.input_server_ip)
        btnStart = findViewById(R.id.btn_start)
        btnSwitchCamera = findViewById(R.id.btn_switch_camera)
        btnHideUi = findViewById(R.id.btn_hide_ui)
        btnRestoreUi = findViewById(R.id.btn_restore_ui)

        inputJoints = findViewById(R.id.input_joints)
        inputBody = findViewById(R.id.input_body)
        inputGaze = findViewById(R.id.input_gaze)
        seekSensitivity = findViewById(R.id.seek_sensitivity)
        seekMotionSensitivity = findViewById(R.id.seek_motion_sensitivity)

        switchLocalTts = findViewById(R.id.switch_local_tts)
        switchShowFace = findViewById(R.id.switch_show_face)
        labelTtsEngine = findViewById(R.id.label_tts_engine)
        spinnerTtsEngine = findViewById(R.id.spinner_tts_engine)

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
            isRunning = !isRunning
            if (isRunning) {
                btnStart.text = getString(R.string.btn_stop)
                btnStart.setBackgroundColor(Color.RED)
                // Apply config
                updateConfigFromUI()

                monitor.reset()
                lastPixelMotionTime = System.currentTimeMillis()
                statusText.text = getString(R.string.status_running)
            } else {
                btnStart.text = getString(R.string.btn_start)
                btnStart.setBackgroundColor(Color.LTGRAY)
                statusText.text = getString(R.string.status_stopped)
                alertOverlay.visibility = View.GONE
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

        btnHideUi.setOnClickListener { setUiVisibility(false) }
        btnRestoreUi.setOnClickListener { setUiVisibility(true) }

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

        // TTS Listeners
        switchLocalTts.setOnCheckedChangeListener { _, isChecked ->
            updateTtsUiVisibility(isChecked)
            saveConfig()
        }

        switchShowFace.setOnCheckedChangeListener { _, isChecked ->
            overlay.showFacePoints = isChecked
            saveConfig()
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
            populateTtsEngines()
        } else {
            Log.e(TAG, "TTS Initialization failed")
        }
    }

    private fun populateTtsEngines() {
        if (tts == null) return
        try {
            availableEngines = tts!!.engines
            val engineNames = availableEngines.map { "${it.label} (${it.name})" }

            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, engineNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerTtsEngine.adapter = adapter

            // Set selection if we have a current package
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

    private fun updateTtsUiVisibility(isLocal: Boolean) {
        if (isLocal) {
            labelTtsEngine.visibility = View.VISIBLE
            spinnerTtsEngine.visibility = View.VISIBLE
        } else {
            labelTtsEngine.visibility = View.GONE
            spinnerTtsEngine.visibility = View.GONE
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
        if (isInPictureInPictureMode) {
            // Hide everything, including restore button (not interactive in PiP)
            setUiVisibility(false, hideRestoreButton = true)
        } else {
            // Restore UI
            setUiVisibility(true)
        }
    }

    private fun setUiVisibility(visible: Boolean, hideRestoreButton: Boolean = false) {
        val v = if (visible) View.VISIBLE else View.GONE
        val restoreV = if (visible || hideRestoreButton) View.GONE else View.VISIBLE

        statusText.visibility = v
        deltaText.visibility = v
        timerJoints.visibility = v
        timerBody.visibility = v
        timerGaze.visibility = v
        btnStart.visibility = v
        btnSwitchCamera.visibility = v
        btnHideUi.visibility = v
        findViewById<View>(R.id.status_card).visibility = v // Status Card
        findViewById<View>(R.id.settings_panel).visibility = v // Settings Panel

        btnRestoreUi.visibility = restoreV
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
        val useLocal = prefs.getBoolean("useLocalTts", true)
        switchLocalTts.isChecked = useLocal
        updateTtsUiVisibility(useLocal)

        selectedEnginePackage = prefs.getString("ttsEngine", null)

        val showFace = prefs.getBoolean("showFacePoints", false)
        switchShowFace.isChecked = showFace
        overlay.showFacePoints = showFace

        inputServerIp.setText(prefs.getString("serverIp", "10.0.2.2"))

        updateConfigFromUI()
        Log.d(TAG, "Config loaded from prefs")
    }

    private fun saveConfig() {
        val prefs = getSharedPreferences("PosturePrefs", MODE_PRIVATE)
        val editor = prefs.edit()

        editor.putInt("joints", inputJoints.text.toString().toIntOrNull() ?: 1500)
        editor.putInt("body", inputBody.text.toString().toIntOrNull() ?: 1800)
        editor.putInt("gaze", inputGaze.text.toString().toIntOrNull() ?: 120)
        editor.putInt("sensitivity", seekSensitivity.progress)
        editor.putInt("motionSensitivity", seekMotionSensitivity.progress)

        editor.putBoolean("useLocalTts", switchLocalTts.isChecked)
        editor.putString("ttsEngine", selectedEnginePackage)
        editor.putBoolean("showFacePoints", switchShowFace.isChecked)
        editor.putString("serverIp", inputServerIp.text.toString())

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

        val now = System.currentTimeMillis()

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
                monitor.reset()
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
                 updateMonitorState(null, true)
             }
        }
    }

    private fun updateMonitorState(landmarks: List<Point3D>?, physicalMotion: Boolean) {
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
        val messages = mapOf(
            "joints" to getString(R.string.msg_joints),
            "body" to getString(R.string.msg_body),
            "gaze" to getString(R.string.msg_gaze)
        )

        val fallback = getString(R.string.msg_relax)

        // Determine what to say
        val messageToSpeak = messages[type] ?: fallback

        // Vibration Logic
        val ringerMode = audioManager.ringerMode
        if (ringerMode == AudioManager.RINGER_MODE_SILENT || ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(500)
                }
            }
            // If strictly silent, don't speak
            if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
                return
            }
        }

        if (switchLocalTts.isChecked) {
            // Local TTS
            if (isTtsReady && tts != null) {
                Log.d(TAG, "Local TTS speaking: $messageToSpeak")
                tts?.speak(messageToSpeak, TextToSpeech.QUEUE_FLUSH, null, "alert_${System.currentTimeMillis()}")
            } else {
                Log.e(TAG, "Local TTS not ready")
            }
        } else {
            // Server TTS
            val ip = inputServerIp.text.toString()
            if (ip.isEmpty()) return

            val endpoint = when(type) {
                "joints", "gaze", "body" -> "/api/say"
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
        tts?.stop()
        tts?.shutdown()
    }

    companion object {
        private const val TAG = "PostureMonitor"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(Manifest.permission.CAMERA).toTypedArray()
    }
}
