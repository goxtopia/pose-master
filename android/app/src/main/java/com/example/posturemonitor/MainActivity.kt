package com.example.posturemonitor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.Switch
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

    // Settings
    private lateinit var inputJoints: EditText
    private lateinit var inputBody: EditText
    private lateinit var inputGaze: EditText
    private lateinit var seekSensitivity: SeekBar
    private lateinit var seekMotionSensitivity: SeekBar

    // TTS UI
    private lateinit var switchLocalTts: Switch
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
        setContentView(R.layout.activity_main)

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

        inputJoints = findViewById(R.id.input_joints)
        inputBody = findViewById(R.id.input_body)
        inputGaze = findViewById(R.id.input_gaze)
        seekSensitivity = findViewById(R.id.seek_sensitivity)
        seekMotionSensitivity = findViewById(R.id.seek_motion_sensitivity)

        switchLocalTts = findViewById(R.id.switch_local_tts)
        labelTtsEngine = findViewById(R.id.label_tts_engine)
        spinnerTtsEngine = findViewById(R.id.spinner_tts_engine)

        // Initialize TTS with default engine
        initTts(null)

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
                btnStart.setBackgroundColor(Color.RED)
                // Apply config
                updateConfigFromUI()

                monitor.reset()
                lastPixelMotionTime = System.currentTimeMillis()
                statusText.text = "Status: Running"
                // Try load config just in case
                loadConfig()
            } else {
                btnStart.text = "Start"
                btnStart.setBackgroundColor(Color.LTGRAY)
                statusText.text = "Status: Stopped"
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
            val result = tts?.setLanguage(Locale.CHINESE) // Default to Chinese
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                 Log.e(TAG, "TTS Language not supported")
                 // Fallback to English?
                 tts?.setLanguage(Locale.ENGLISH)
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

    private fun updateConfigFromUI() {
        try {
            val joints = inputJoints.text.toString().toLongOrNull() ?: 1500
            val body = inputBody.text.toString().toLongOrNull() ?: 1800
            val gaze = inputGaze.text.toString().toLongOrNull() ?: 600
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
        val ip = inputServerIp.text.toString()
        if (ip.isEmpty()) return
        val url = "http://$ip:8080/api/config"

        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to load config: $e")
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    if (bodyStr != null) {
                        try {
                            val json = JSONObject(bodyStr)
                            runOnUiThread {
                                if (json.has("joints")) inputJoints.setText(json.getInt("joints").toString())
                                if (json.has("body")) inputBody.setText(json.getInt("body").toString())
                                if (json.has("gaze")) inputGaze.setText(json.getInt("gaze").toString())
                                if (json.has("sensitivity")) seekSensitivity.progress = json.getInt("sensitivity")
                                if (json.has("motionSensitivity")) seekMotionSensitivity.progress = json.getInt("motionSensitivity")

                                // Load TTS settings
                                if (json.has("useLocalTts")) {
                                    val useLocal = json.getBoolean("useLocalTts")
                                    switchLocalTts.isChecked = useLocal
                                    updateTtsUiVisibility(useLocal)
                                }
                                if (json.has("ttsEngine")) {
                                    val savedEngine = json.getString("ttsEngine")
                                    if (savedEngine != selectedEnginePackage) {
                                         selectedEnginePackage = savedEngine
                                         initTts(savedEngine)
                                    }
                                }

                                updateConfigFromUI()
                                Log.d(TAG, "Config loaded")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Json parse error: $e")
                        }
                    }
                }
            }
        })
    }

    private fun saveConfig() {
        val ip = inputServerIp.text.toString()
        if (ip.isEmpty()) return
        val url = "http://$ip:8080/api/config"

        try {
            val json = JSONObject()
            json.put("joints", inputJoints.text.toString().toIntOrNull() ?: 1500)
            json.put("body", inputBody.text.toString().toIntOrNull() ?: 1800)
            json.put("gaze", inputGaze.text.toString().toIntOrNull() ?: 600)
            json.put("sensitivity", seekSensitivity.progress)
            json.put("motionSensitivity", seekMotionSensitivity.progress)

            // Save TTS settings
            json.put("useLocalTts", switchLocalTts.isChecked)
            json.put("ttsEngine", selectedEnginePackage ?: "")

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { Log.e(TAG, "Save failed: $e") }
                override fun onResponse(call: Call, response: Response) { response.close() }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Save setup failed: $e")
        }
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
        // it.visibility().orElse(0f)
        if (landmarks.isNotEmpty() && landmarks[0].isNotEmpty()) {
            val rawList = landmarks[0].map {
                Point3D(it.x(), it.y(), it.z(), 0F)
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

        timerJoints.text = "Joints: %.1fs".format(state.timers["joints"])
        timerBody.text = "Body: %.1fs".format(state.timers["body"])
        timerGaze.text = "Gaze: %.1fs".format(state.timers["gaze"])

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
            "joints" to "长时间保持一个姿势了，活动一下关节吧",
            "body" to "坐太久了，起来走两步吧",
            "gaze" to "眼睛累了吗，看看远处吧"
        )

        // Determine what to say
        val messageToSpeak = messages[type] ?: "请放松一下" // Fallback for 'relax' or unknown

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
