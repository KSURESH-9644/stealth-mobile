package com.stealth.assistant

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OverlayService : Service() {

    companion object {
        private const val TAG = "StealthAssistant"
        private const val WS_URL = "wss://stealth-mobile.onrender.com"
        private const val NOTIFICATION_CHANNEL_ID = "stealth_assistant_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var audioManager: AudioManager

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null
    private var autoStreamingJob: Job? = null
    private var reconnectJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var rawAudioStream: ByteArrayOutputStream? = null

    @Volatile
    private var isRecordingRaw = false

    @Volatile
    private var isAutoStreaming = false

    private var lastSpokenTime = 0L
    private var speechDetectedInCurrentChunk = false

    private var resumeText = ""
    private var customContext = ""
    private var currentSizeLevel = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        startForegroundServiceNotification()
        setupAudioRouting()
        initOverlayView()
        connectWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        resumeText = MainActivity.sharedResumeText
        customContext = MainActivity.sharedCustomContext
        sendContextToServer()
        return START_STICKY
    }

    private fun setupAudioRouting() {
        // App does not hijack system audio routing; handles Speaker/Headset naturally
    }

    private fun resetAudioRouting() {
        // No-op
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Stealth Assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Stealth Copilot Active")
            .setContentText("Listening & streaming to AI...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, serviceTypes)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initOverlayView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Screen Bottom Placement
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 80 // Safe margin from navigation bar
        }

        windowManager.addView(overlayView, params)

        val expandedWindow = overlayView.findViewById<View>(R.id.expandedWindow)
        val minimizedBubble = overlayView.findViewById<View>(R.id.minimizedBubble)
        val dragHeader = overlayView.findViewById<View>(R.id.dragHeader)
        val btnClose = overlayView.findViewById<TextView>(R.id.btnClose)
        val btnMinimize = overlayView.findViewById<TextView>(R.id.btnMinimize)
        val btnResize = overlayView.findViewById<TextView>(R.id.btnResize)
        val btnAuto = overlayView.findViewById<Button>(R.id.btnAutoCapture)
        val btnManual = overlayView.findViewById<Button>(R.id.btnManualCapture)
        val tvAppVersion = overlayView.findViewById<TextView>(R.id.tvAppVersion)

        // Read Dynamic App Version
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val currentVersion = packageInfo.versionName ?: "1.0.1"
            tvAppVersion?.text = "v$currentVersion"
        } catch (_: Exception) {
            tvAppVersion?.text = "v1.0.1"
        }

        btnClose.setOnClickListener { stopSelf() }

        btnMinimize.setOnClickListener {
            expandedWindow.visibility = View.GONE
            minimizedBubble.visibility = View.VISIBLE
        }

        btnResize.setOnClickListener {
            currentSizeLevel = (currentSizeLevel + 1) % 3
            val layoutParams = expandedWindow.layoutParams
            when (currentSizeLevel) {
                0 -> {
                    layoutParams.width = (280 * resources.displayMetrics.density).toInt()
                    layoutParams.height = (200 * resources.displayMetrics.density).toInt()
                }
                1 -> {
                    layoutParams.width = (330 * resources.displayMetrics.density).toInt()
                    layoutParams.height = (290 * resources.displayMetrics.density).toInt()
                }
                2 -> {
                    layoutParams.width = (370 * resources.displayMetrics.density).toInt()
                    layoutParams.height = (400 * resources.displayMetrics.density).toInt()
                }
            }
            expandedWindow.layoutParams = layoutParams
        }

        btnAuto.setOnClickListener {
            if (isAutoStreaming) {
                isAutoStreaming = false
                autoStreamingJob?.cancel()
                if (isRecordingRaw) stopHardwareRecording(sendAudio = false)
                btnAuto.text = "Auto Stream"
                btnAuto.setBackgroundColor(Color.parseColor("#1E293B"))
                updateStatus("🟢 Ready")
            } else {
                if (isRecordingRaw) stopHardwareRecording(sendAudio = false)
                isAutoStreaming = true
                btnAuto.text = "Streaming..."
                btnAuto.setBackgroundColor(Color.parseColor("#15803D"))
                startAutoAudioLoop()
            }
        }

        btnManual.setOnClickListener {
            if (isRecordingRaw) {
                stopHardwareRecording(sendAudio = true)
                btnManual.text = "Tap to Record"
                btnManual.setBackgroundColor(Color.parseColor("#0284C7"))
            } else {
                if (isAutoStreaming) {
                    isAutoStreaming = false
                    autoStreamingJob?.cancel()
                    btnAuto.text = "Auto Stream"
                    btnAuto.setBackgroundColor(Color.parseColor("#1E293B"))
                }
                startHardwareRecording()
                btnManual.text = "Stop & Answer"
                btnManual.setBackgroundColor(Color.parseColor("#DC2626"))
            }
        }

        setupDragTouchListener(dragHeader, isBubble = false)
        setupDragTouchListener(minimizedBubble, isBubble = true)
    }

    private fun setupDragTouchListener(targetView: View, isBubble: Boolean) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        targetView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val diffX = (event.rawX - initialTouchX).toInt()
                    val diffY = (event.rawY - initialTouchY).toInt()

                    if (kotlin.math.abs(diffX) > 10 || kotlin.math.abs(diffY) > 10) {
                        isClick = false
                        params.x = initialX + diffX
                        params.y = initialY - diffY // Adjust for Bottom gravity
                        windowManager.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick && isBubble) {
                        overlayView.findViewById<View>(R.id.minimizedBubble).visibility = View.GONE
                        overlayView.findViewById<View>(R.id.expandedWindow).visibility = View.VISIBLE
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun connectWebSocket() {
        reconnectJob?.cancel()
        val request = Request.Builder().url(WS_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                updateStatus("🟢 Connected")
                sendContextToServer()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                updateStatus("🔴 Reconnecting...")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                updateStatus("⚪ Disconnected")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = serviceScope.launch {
            delay(4000)
            if (isActive) connectWebSocket()
        }
    }

    private fun sendContextToServer() {
        if (resumeText.isEmpty() && customContext.isEmpty()) return

        val payload = JSONObject().apply {
            put("type", "update-context")
            put("resumeText", resumeText)
            put("isPdf", MainActivity.isPdfDocument)
            put("customContext", customContext)
        }
        webSocket?.send(payload.toString())
    }

    private fun updateStatus(status: String) {
        CoroutineScope(Dispatchers.Main).launch {
            if (::overlayView.isInitialized) {
                overlayView.findViewById<TextView>(R.id.tvStatus)?.text = status
            }
        }
    }

    private fun handleServerMessage(jsonString: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val json = JSONObject(jsonString)
                val tvQuestion = overlayView.findViewById<TextView>(R.id.tvQuestion)
                val tvAnswer = overlayView.findViewById<TextView>(R.id.tvAnswer)
                val scroll = overlayView.findViewById<ScrollView>(R.id.scrollAnswer)

                when (json.optString("type")) {
                    "question" -> {
                        tvQuestion?.text = "🎙️ ${json.optString("text")}"
                        tvAnswer?.text = ""
                    }
                    "stream-token" -> {
                        tvAnswer?.append(json.optString("text"))
                        scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    }
                    "stream-end" -> {
                        updateStatus("⚡ Answered (${json.optString("duration")})")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server parse error: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startHardwareRecording() {
        if (isRecordingRaw) return

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        if (minBufferSize <= 0) {
            updateStatus("❌ Buffer Error")
            return
        }

        try {
            val audioSources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )

            var recorder: AudioRecord? = null
            for (source in audioSources) {
                try {
                    val testRecorder = AudioRecord(
                        source,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        minBufferSize * 4
                    )
                    if (testRecorder.state == AudioRecord.STATE_INITIALIZED) {
                        recorder = testRecorder
                        Log.d(TAG, "Initialized AudioRecord with source: $source")
                        break
                    } else {
                        testRecorder.release()
                    }
                } catch (_: Exception) {}
            }

            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                updateStatus("❌ Mic In Use")
                return
            }

            audioRecord = recorder
            rawAudioStream = ByteArrayOutputStream()
            recorder.startRecording()
            isRecordingRaw = true
            speechDetectedInCurrentChunk = false
            updateStatus("🔴 Listening...")

            recordingJob = serviceScope.launch {
                val buffer = ByteArray(minBufferSize)
                while (isActive && isRecordingRaw) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        rawAudioStream?.write(buffer, 0, read)

                        // Amplitude check for speech
                        var maxSample = 0
                        for (i in 0 until read step 2) {
                            val sample = Math.abs((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8))
                            if (sample > maxSample) maxSample = sample
                        }

                        // Speech energy threshold
                        if (maxSample > 800) {
                            speechDetectedInCurrentChunk = true
                            lastSpokenTime = System.currentTimeMillis()
                        }

                        // Silence detected for 1.5s after speech (Wait for full question)
                        val isSilenceAfterSpeech = speechDetectedInCurrentChunk &&
                                (System.currentTimeMillis() - lastSpokenTime > 1500)

                        // 18 seconds max duration cap
                        val streamTooLong = (rawAudioStream?.size() ?: 0) > (sampleRate * 2 * 18)

                        if (isSilenceAfterSpeech || streamTooLong) {
                            stopHardwareRecording(sendAudio = true)
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording init error: ${e.message}")
            stopHardwareRecording(sendAudio = false)
        }
    }

    private fun stopHardwareRecording(sendAudio: Boolean) {
        isRecordingRaw = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        val rawBytes = rawAudioStream?.toByteArray() ?: ByteArray(0)
        rawAudioStream = null

        if (sendAudio && rawBytes.isNotEmpty() && speechDetectedInCurrentChunk) {
            updateStatus("⚡ Thinking...")
            val wavBytes = addWavHeader(rawBytes, 16000, 1, 16)
            sendAudioBytes(wavBytes)
        } else if (isAutoStreaming) {
            updateStatus("🟢 Listening...")
        }
    }

    private fun startAutoAudioLoop() {
        autoStreamingJob?.cancel()
        autoStreamingJob = serviceScope.launch {
            while (isActive && isAutoStreaming) {
                if (!isRecordingRaw) {
                    startHardwareRecording()
                }
                delay(400)
            }
        }
    }

    private fun sendAudioBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        try {
            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val payload = JSONObject().apply {
                put("type", "process-audio")
                put("data", base64Data)
                put("format", "wav")
            }
            webSocket?.send(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Audio send error: ${e.message}")
        }
    }

    private fun addWavHeader(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * bitsPerSample / 8).toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(totalAudioLen)

        val wavData = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, wavData, 0, 44)
        System.arraycopy(pcmData, 0, wavData, 44, pcmData.size)
        return wavData
    }

    override fun onDestroy() {
        isAutoStreaming = false
        isRecordingRaw = false
        autoStreamingJob?.cancel()
        recordingJob?.cancel()
        reconnectJob?.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}

        resetAudioRouting()

        try {
            webSocket?.close(1000, "Service destroyed")
        } catch (_: Exception) {}

        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }

        serviceScope.cancel()
        super.onDestroy()
    }
}