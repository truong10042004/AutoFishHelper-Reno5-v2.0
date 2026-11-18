package com.example.autofish

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnOverlay: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnBattery: Button

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, FishingBotService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Cần bấm 'Bắt đầu ngay' để nhận diện màn hình", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Auto Câu Cá v2.4 (OPPO Reno5)"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }

        val subTitle = TextView(this).apply {
            text = "Cập nhật v2.4 tối ưu treo máy:\n- Tăng thời gian chờ cá cắn lên 60 giây\n- Tự làm mới nền mỗi 1.2s chống giật nhầm khi cá rỉa lâu\n- Tự động nhận diện nút 'Bảo quản' cho cá hiếm"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 36)
        }

        btnOverlay = Button(this).apply {
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "Đã cấp quyền Hiển thị nổi!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnAccessibility = Button(this).apply {
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "Vào mục 'Ứng dụng đã tải xuống' -> Bật 'Auto Fish Reno5'",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        btnBattery = Button(this).apply {
            setOnClickListener {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Đã tắt tối ưu pin ColorOS!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnStart = Button(this).apply {
            text = "🚀 KHỞI CHẠY BẢNG ĐIỀU KHIỂN NỔI"
            setBackgroundColor(Color.parseColor("#0288D1"))
            setTextColor(Color.WHITE)
            setPadding(24, 28, 24, 28)
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Vui lòng bật Bước 1 (Hiển thị nổi) trước!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isAccessibilityServiceEnabled()) {
                    Toast.makeText(this@MainActivity, "Vui lòng bật Bước 2 (Trợ năng) trước!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(mpManager.createScreenCaptureIntent())
            }
        }

        layout.addView(title)
        layout.addView(subTitle)
        layout.addView(btnOverlay)
        layout.addView(btnAccessibility)
        layout.addView(btnBattery)
        layout.addView(btnStart)
        scroll.addView(layout)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionLabels()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (AutoClickAccessibilityService.instance != null) return true
        val expectedComponentName = "$packageName/${AutoClickAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun updatePermissionLabels() {
        val hasOverlay = Settings.canDrawOverlays(this)
        btnOverlay.text = if (hasOverlay) {
            "✅ 1. Đã bật Hiển thị trên ứng dụng khác"
        } else {
            "1. Cấp quyền Hiển thị trên ứng dụng khác"
        }

        val hasA11y = isAccessibilityServiceEnabled()
        btnAccessibility.text = if (hasA11y) {
            "✅ 2. Đã bật Dịch vụ Trợ năng (Auto Click)"
        } else {
            "2. Bật Dịch vụ Trợ năng (Auto Click)"
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoreBattery = pm.isIgnoringBatteryOptimizations(packageName)
        btnBattery.text = if (ignoreBattery) {
            "✅ 3. Đã cho phép chạy nền ColorOS"
        } else {
            "3. Cho phép chạy nền không bị ColorOS tắt (Khuyên dùng)"
        }
    }
}
