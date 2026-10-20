package com.zlang.tv

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 页面右上角时间显示条，样式与 FFmpegVideoPlayerActivity 中的实现一致（灰色 HH:mm，每秒刷新）。
 * 在 Activity 的 onCreate 中调用 PageTimeDisplay.attach(this) 即可，
 * Activity 销毁时 decorView 从窗口分离会自动停止刷新，无需手动清理。
 */
object PageTimeDisplay {

    private const val VIEW_TAG = "page_time_display"

    fun attach(activity: Activity) {
        val rootView = activity.window.decorView as FrameLayout

        // 防止重复添加
        if (rootView.findViewWithTag<View>(VIEW_TAG) != null) {
            return
        }

        val handler = Handler(Looper.getMainLooper())
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())

        val timeText = TextView(activity).apply {
            tag = VIEW_TAG
            textSize = 16f // 增大字体大小，方便在电视上查看
            setTextColor(Color.GRAY)
            setPadding(5, 5, 5, 5) // 增加内边距
            text = format.format(Date())
        }

        val layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.END

        rootView.addView(timeText, layoutParams)

        // 每秒刷新一次时间
        val updateRunnable = object : Runnable {
            override fun run() {
                timeText.text = format.format(Date())
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(updateRunnable, 1000)

        // Activity 销毁时 decorView 会从窗口分离，此时停止刷新，避免泄漏
        rootView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                handler.removeCallbacks(updateRunnable)
            }
        })
    }
}
