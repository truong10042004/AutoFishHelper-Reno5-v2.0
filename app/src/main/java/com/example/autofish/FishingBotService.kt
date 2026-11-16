package com.example.autofish

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer

class FishingBotService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private val scaleFactor = 0.5f

    private var visionThread: HandlerThread? = null
    private var visionHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var controlPanelView: View
    private lateinit var markerScanView: View
    private lateinit var markerCastView: View
    private lateinit var markerReelView: View
    private lateinit var markerStoreView: View

    private lateinit var btnToggle: Button
    private lateinit var btnSensitivity: Button
    private lateinit var tvStatus: TextView

    private enum class BotState { IDLE, CASTING, CALIBRATING, WAITING_BITE, REELING, STORING }
    @Volatile private var currentState = BotState.IDLE
    private var stateStartTime = 0L
    private var fishCount = 0

    private val sensitivityLevels = intArrayOf(12, 18, 25)
    private val sensitivityLabels = arrayOf("Nhạy: Cao", "Nhạy: Chuẩn", "Nhạy: Thấp")
    private var sensitivityIndex = 1

    private val roiRadius = 26
    private var baselineLuma: IntArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("AutoFishPrefs", Context.MODE_PRIVATE)
        sensitivityIndex = prefs.getInt("sens_idx", 1).coerceIn(0, 2)

        updateScreenMetrics()

        visionThread = HandlerThread("FishVisionThread", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            visionHandler = Handler(looper)
        }

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "AUTO_FISH_CHANNEL")
            .setContentTitle("Auto Fish Reno5 đang chạy")
            .setContentText("Chạm vào thanh điều khiển nổi trên màn hình để Bật/Tắt")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(101, notification)
        }

        setupFloatingUI()
    }

    private fun updateScreenMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val oldW = screenWidth
        val oldH = screenHeight
        updateScreenMetrics()

        if (oldW != screenWidth || oldH != screenHeight) {
            if (mediaProjection != null) {
                setupVirtualDisplay()
            }
            restoreMarkerPositions()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { stopSelf() }
                }
            }, mainHandler)
            setupVirtualDisplay()
        }
        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        virtualDisplay?.release()
        imageReader?.close()

        val captureW = (screenWidth * scaleFactor).toInt().coerceAtLeast(100)
        val captureH = (screenHeight * scaleFactor).toInt().coerceAtLeast(100)

        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AutoFishCapture",
            captureW, captureH, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, visionHandler
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingUI() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E61A1A1A"))
            setPadding(16, 10, 16, 10)
            gravity = Gravity.CENTER_VERTICAL
        }

        btnToggle = Button(this).apply {
            text = "▶ BẮT ĐẦU"
            textSize = 12f
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener { toggleBot() }
        }

        btnSensitivity = Button(this).apply {
            text = sensitivityLabels[sensitivityIndex]
            textSize = 11f
            setBackgroundColor(Color.parseColor("#37474F"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                sensitivityIndex = (sensitivityIndex + 1) % sensitivityLevels.size
                text = sensitivityLabels[sensitivityIndex]
                prefs.edit().putInt("sens_idx", sensitivityIndex).apply()
            }
        }

        tvStatus = TextView(this).apply {
            text = " Sẵn sàng (4 điểm) "
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(12, 0, 12, 0)
        }

        val btnClose = Button(this).apply {
            text = "✕"
            textSize = 12f
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            setOnClickListener { stopSelf() }
        }

        panel.addView(btnToggle)
        panel.addView(btnSensitivity)
        panel.addView(tvStatus)
        panel.addView(btnClose)
        controlPanelView = panel

        val panelParams = createOverlayParams(
            prefs.getInt("panel_x", 40),
            prefs.getInt("panel_y", 30)
        )
        windowManager.addView(controlPanelView, panelParams)
        makeDraggable(controlPanelView, panelParams, "panel")

        markerScanView = createTargetMarker(
            "1: (!)\n+",
            Color.parseColor("#B3FFEB3B"),
            "m1",
            screenWidth / 2 - 55,
            screenHeight / 2 - 140
        )
        markerCastView = createTargetMarker(
            "2: Quăng\n+",
            Color.parseColor("#B300E5FF"),
            "m2",
            screenWidth - 260,
            screenHeight - 220
        )
        markerReelView = createTargetMarker(
            "3: Kéo\n+",
            Color.parseColor("#B3FF9100"),
            "m3",
            screenWidth - 180,
            screenHeight - 220
        )
        markerStoreView = createTargetMarker(
            "4: Cất\n+",
            Color.parseColor("#B376FF03"),
            "m4",
            screenWidth - 360,
            screenHeight - 160
        )
    }

    private fun restoreMarkerPositions() {
        val prefix = if (screenWidth > screenHeight) "land_" else "port_"
        updateViewPos(markerScanView, prefs.getInt("${prefix}m1_x", screenWidth / 2 - 55), prefs.getInt("${prefix}m1_y", screenHeight / 2 - 140))
        updateViewPos(markerCastView, prefs.getInt("${prefix}m2_x", screenWidth - 260), prefs.getInt("${prefix}m2_y", screenHeight - 220))
        updateViewPos(markerReelView, prefs.getInt("${prefix}m3_x", screenWidth - 180), prefs.getInt("${prefix}m3_y", screenHeight - 220))
        updateViewPos(markerStoreView, prefs.getInt("${prefix}m4_x", screenWidth - 360), prefs.getInt("${prefix}m4_y", screenHeight - 160))
    }

    private fun updateViewPos(view: View, x: Int, y: Int) {
        if (!view.isAttachedToWindow) return
        val params = view.layoutParams as WindowManager.LayoutParams
        params.x = x.coerceIn(0, (screenWidth - 110).coerceAtLeast(0))
        params.y = y.coerceIn(0, (screenHeight - 110).coerceAtLeast(0))
        windowManager.updateViewLayout(view, params)
    }

    private fun createTargetMarker(label: String, color: Int, key: String, defX: Int, defY: Int): View {
        val prefix = if (screenWidth > screenHeight) "land_" else "port_"
        val savedX = prefs.getInt("${prefix}${key}_x", defX).coerceIn(0, (screenWidth - 110).coerceAtLeast(0))
        val savedY = prefs.getInt("${prefix}${key}_y", defY).coerceIn(0, (screenHeight - 110).coerceAtLeast(0))

        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(4, Color.WHITE)
        }
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.BLACK)
            textSize = 11f
            gravity = Gravity.CENTER
            background = shape
            width = 112
            height = 112
        }
        val params = createOverlayParams(savedX, savedY)
        windowManager.addView(tv, params)
        makeDraggable(tv, params, key)
        return tv
    }

    private fun createOverlayParams(x: Int, y: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
    }

    private fun setMarkersPassThrough(passThrough: Boolean) {
        val markers = listOf(markerScanView, markerCastView, markerReelView, markerStoreView)
        for (marker in markers) {
            val params = marker.layoutParams as WindowManager.LayoutParams
            if (passThrough) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                marker.alpha = 0.20f
            } else {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                marker.alpha = 0.95f
            }
            windowManager.updateViewLayout(marker, params)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(view: View, params: WindowManager.LayoutParams, key: String) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initialX + (event.rawX - initialTouchX).toInt()).coerceIn(0, (screenWidth - 80).coerceAtLeast(0))
                    params.y = (initialY + (event.rawY - initialTouchY).toInt()).coerceIn(0, (screenHeight - 80).coerceAtLeast(0))
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val prefix = if (key == "panel") "" else if (screenWidth > screenHeight) "land_" else "port_"
                    prefs.edit()
                        .putInt("${prefix}${key}_x", params.x)
                        .putInt("${prefix}${key}_y", params.y)
                        .apply()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleBot() {
        if (currentState == BotState.IDLE) {
            if (AutoClickAccessibilityService.instance == null) {
                tvStatus.text = " LỖI: Bị tắt Trợ năng! "
                tvStatus.setTextColor(Color.parseColor("#FF5252"))
                Toast.makeText(this, "ColorOS đã tắt quyền Trợ năng! Hãy bật lại trong Cài đặt.", Toast.LENGTH_LONG).show()
                return
            }
            tvStatus.setTextColor(Color.WHITE)
            setMarkersPassThrough(true)
            btnToggle.text = "⏸ DỪNG"
            btnToggle.setBackgroundColor(Color.parseColor("#EF6C00"))
            transitionTo(BotState.CASTING)
            visionHandler?.post(botLoopRunnable)
        } else {
            transitionTo(BotState.IDLE)
            visionHandler?.removeCallbacks(botLoopRunnable)
            setMarkersPassThrough(false)
            btnToggle.text = "▶ BẮT ĐẦU"
            btnToggle.setBackgroundColor(Color.parseColor("#2E7D32"))
            tvStatus.text = " Đã dừng (Câu: $fishCount) "
        }
    }

    private fun transitionTo(newState: BotState) {
        currentState = newState
        stateStartTime = SystemClock.elapsedRealtime()
        val statusMsg = when (newState) {
            BotState.IDLE -> " Đã dừng (Câu: $fishCount) "
            BotState.CASTING -> " [1] Bấm Quăng cần (Ô 2) "
            BotState.CALIBRATING -> " [2] Đợi phao ổn định... "
            BotState.WAITING_BITE -> " [3] Canh dấu (!) ở Ô 1 "
            BotState.REELING -> " [4] KÉO CÁ! (Ô 3) "
            BotState.STORING -> " [5] Bảo quản (Ô 4) #$fishCount "
        }
        mainHandler.post { tvStatus.text = statusMsg }
    }

    private val botLoopRunnable = object : Runnable {
        override fun run() {
            if (currentState == BotState.IDLE) return

            val now = SystemClock.elapsedRealtime()
            val elapsed = now - stateStartTime
            val clicker = AutoClickAccessibilityService.instance

            if (clicker == null) {
                mainHandler.post {
                    toggleBot()
                    tvStatus.text = " Mất kết nối Trợ năng! "
                }
                return
            }

            when (currentState) {
                BotState.CASTING -> {
                    val (cx, cy) = getViewCenter(markerCastView)
                    clicker.tap(cx, cy)
                    transitionTo(BotState.CALIBRATING)
                }

                BotState.CALIBRATING -> {
                    if (elapsed >= 2100L) {
                        val luma = sampleScanRoiLuma()
                        if (luma != null && luma.isNotEmpty()) {
                            baselineLuma = luma
                            transitionTo(BotState.WAITING_BITE)
                        } else if (elapsed > 5000L) {
                            setupVirtualDisplay()
                            stateStartTime = now
                        }
                    }
                }

                BotState.WAITING_BITE -> {
                    val currentLuma = sampleScanRoiLuma()
                    val base = baselineLuma
                    if (currentLuma != null && base != null && currentLuma.size == base.size) {
                        var spikePixels = 0
                        var sumDiff = 0
                        for (i in currentLuma.indices) {
                            sumDiff += (currentLuma[i] - base[i])
                        }
                        val avgShift = sumDiff / currentLuma.size

                        for (i in currentLuma.indices) {
                            val correctedDiff = (currentLuma[i] - base[i]) - avgShift
                            if (correctedDiff > 48 && currentLuma[i] > 175) {
                                spikePixels++
                            }
                        }

                        val threshold = sensitivityLevels[sensitivityIndex]
                        if (spikePixels >= threshold) {
                            val (rx, ry) = getViewCenter(markerReelView)
                            clicker.tap(rx, ry)
                            fishCount++
                            transitionTo(BotState.REELING)
                        }
                    }

                    if (elapsed > 35000L) {
                        transitionTo(BotState.CASTING)
                    }
                }

                BotState.REELING -> {
                    if (elapsed >= 4100L) {
                        val (sx, sy) = getViewCenter(markerStoreView)
                        clicker.tap(sx, sy)
                        transitionTo(BotState.STORING)
                    }
                }

                BotState.STORING -> {
                    if (elapsed >= 1400L) {
                        transitionTo(BotState.CASTING)
                    }
                }

                else -> {}
            }

            val nextDelay = if (currentState == BotState.WAITING_BITE) 45L else 220L
            visionHandler?.postDelayed(this, nextDelay)
        }
    }

    private fun sampleScanRoiLuma(): IntArray? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return null

        try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val (centerX, centerY) = getViewCenter(markerScanView)
            val scaledCx = (centerX * scaleFactor).toInt()
            val scaledCy = (centerY * scaleFactor).toInt()
            val scaledR = (roiRadius * scaleFactor).toInt()

            val startX = (scaledCx - scaledR).coerceIn(0, image.width - 1)
            val endX = (scaledCx + scaledR).coerceIn(0, image.width - 1)
            val startY = (scaledCy - scaledR).coerceIn(0, image.height - 1)
            val endY = (scaledCy + scaledR).coerceIn(0, image.height - 1)

            val width = endX - startX + 1
            val height = endY - startY + 1
            if (width <= 0 || height <= 0) return null

            val result = IntArray(width * height)
            var idx = 0
            for (y in startY..endY) {
                val rowOffset = y * rowStride
                for (x in startX..endX) {
                    val offset = rowOffset + x * pixelStride
                    if (offset + 2 < buffer.limit()) {
                        val r = buffer.get(offset).toInt() and 0xFF
                        val g = buffer.get(offset + 1).toInt() and 0xFF
                        val b = buffer.get(offset + 2).toInt() and 0xFF
                        result[idx++] = (r * 77 + g * 150 + b * 29) shr 8
                    }
                }
            }
            return result
        } finally {
            image.close()
        }
    }

    private fun getViewCenter(view: View): Pair<Float, Float> {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Pair(loc[0] + view.width / 2f, loc[1] + view.height / 2f)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "AUTO_FISH_CHANNEL",
                "Auto Fish Reno5 Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        visionHandler?.removeCallbacksAndMessages(null)
        visionThread?.quitSafely()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (::controlPanelView.isInitialized && controlPanelView.isAttachedToWindow) windowManager.removeView(controlPanelView)
        if (::markerScanView.isInitialized && markerScanView.isAttachedToWindow) windowManager.removeView(markerScanView)
        if (::markerCastView.isInitialized && markerCastView.isAttachedToWindow) windowManager.removeView(markerCastView)
        if (::markerReelView.isInitialized && markerReelView.isAttachedToWindow) windowManager.removeView(markerReelView)
        if (::markerStoreView.isInitialized && markerStoreView.isAttachedToWindow) windowManager.removeView(markerStoreView)
        super.onDestroy()
    }
}
