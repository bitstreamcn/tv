package com.zlang.tv

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*
import java.util.Timer
import kotlin.concurrent.scheduleAtFixedRate

class FloatingTimeService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: TextView

    override fun onCreate() {
        super.onCreate()

        // 初始化窗口管理器
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // 加载布局
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_time, null) as TextView

        // 设置窗口布局参数
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        // 设置位置到右上角
        layoutParams.gravity = Gravity.TOP or Gravity.END

        // 添加视图到窗口
        windowManager.addView(floatingView, layoutParams)

        // 启动定时器更新时间
        val timer = Timer()
        timer.scheduleAtFixedRate(0, 1000) {
            updateTime()
        }
    }

    private fun updateTime() {
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val currentTime = dateFormat.format(Date())
        floatingView.text = currentTime
    }

    override fun onDestroy() {
        super.onDestroy()
        // 移除视图
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}