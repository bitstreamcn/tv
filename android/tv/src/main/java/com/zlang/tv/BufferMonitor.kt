package com.zlang.tv

import android.app.Activity
import org.json.JSONObject
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.MotionEvent
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.io.File
import android.os.SystemClock
import java.util.Timer
import android.os.HandlerThread
import androidx.media3.exoplayer.ExoPlayer


class BufferMonitor(private val activity: Activity?, private val player: ExoPlayer?) {
    private var lastReportTime = 0L
    private var lastBufferTime = 0
    private val handlerThread = HandlerThread("BufferHandlerThread")
    private var bufferTime = 0

    // 启动监控线程
    private var monitorHandler : Handler? = null
    private val monitorRunnable = object : Runnable {
        override fun run() {
            activity?.runOnUiThread {
                val currentPos = player?.currentPosition
                val bufferedPos = player?.bufferedPosition
                if (currentPos != null && bufferedPos != null) {
                    bufferTime = (bufferedPos - currentPos).toInt()
                } else {
                    // 处理 null 的情况，例如将 bufferTime 设为 0
                    bufferTime = 0
                }
            }
            // 防止抖动：变化超过5秒或间隔超过3秒才上报
            if (Math.abs(bufferTime - lastBufferTime) > 1000 ||
                SystemClock.elapsedRealtime() - lastReportTime > 1000) {

                if (bufferTime > 20000) {
                    TcpDataSource.pause = true
                    sendStreamCommand("pause", bufferTime)
                } else if (bufferTime < 10000) {
                    TcpDataSource.pause = false
                    sendStreamCommand("resume", bufferTime)
                }

                lastBufferTime = bufferTime
                lastReportTime = SystemClock.elapsedRealtime()
            }

            monitorHandler?.postDelayed(this, 1000) // 每秒检测
        }
    }

    fun start() {
        handlerThread.start()
        monitorHandler = Handler(handlerThread.looper)
        monitorHandler?.post(monitorRunnable)
    }

    fun stop() {
        monitorHandler?.removeCallbacks(monitorRunnable)
    }

    private fun sendStreamCommand(action: String, bufferTime: Int) {
        val json = JSONObject().apply {
            put("action", action)
            put("buffer_time", bufferTime)
        }

        TcpControlClient.sendTlv(
            json.toString()
        )

    }
}

