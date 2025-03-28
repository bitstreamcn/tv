package com.zlang.tv

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.Thread.UncaughtExceptionHandler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandler private constructor() : UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        private var instance: CrashHandler? = null
        private lateinit var context: Context
        private var defaultHandler: UncaughtExceptionHandler? = null

        @JvmStatic
        fun getInstance(): CrashHandler {
            if (instance == null) {
                instance = CrashHandler()
            }
            return instance!!
        }
    }

    fun init(context: Context) {
        CrashHandler.context = context
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        if (!handleException(e) && defaultHandler != null) {
            // 如果自定义处理未成功，交给系统默认的异常处理器
            defaultHandler!!.uncaughtException(t, e)
        } else {
            try {
                // 延迟一段时间后退出应用，给处理异常的操作留出时间
                Thread.sleep(3000)
            } catch (interruptedException: InterruptedException) {
                Log.e(TAG, "Error : ", interruptedException)
            }
            // 退出应用
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }

    private fun handleException(ex: Throwable?): Boolean {
        if (ex == null) {
            return false
        }
        try {
            // 在子线程中显示 Toast
            Thread {
                Looper.prepare()
                Toast.makeText(context, "很抱歉，应用出现异常，即将退出。", Toast.LENGTH_LONG).show()
                Looper.loop()
            }.start()
            // 保存错误日志
            saveCrashInfo2File(ex)
            // 输出错误信息
            printCrashInfo(ex)
        } catch (e: Exception) {
            Log.e(TAG, "Error : ", e)
        }
        return true
    }

    private fun printCrashInfo(ex: Throwable) {
        val sb = StringBuilder()
        try {
            val pm = context.packageManager
            val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
            sb.append("APP Version: ${pi.versionName} + ${pi.versionCode}\n")
            sb.append("OS Version: ${Build.VERSION.RELEASE} + ${Build.VERSION.SDK_INT}\n")
            sb.append("Device: ${Build.MANUFACTURER} + ${Build.MODEL}\n")
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Error while collect package info", e)
        }
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        ex.printStackTrace(pw)
        var cause = ex.cause
        while (cause != null) {
            cause.printStackTrace(pw)
            cause = cause.cause
        }
        pw.close()
        sb.append(sw.toString())
        Log.e(TAG, sb.toString())
    }

    private fun saveCrashInfo2File(ex: Throwable) {
        val sb = StringBuilder()
        try {
            val pm = context.packageManager
            val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_ACTIVITIES)
            sb.append("APP Version: ${pi.versionName} + ${pi.versionCode}\n")
            sb.append("OS Version: ${Build.VERSION.RELEASE} + ${Build.VERSION.SDK_INT}\n")
            sb.append("Device: ${Build.MANUFACTURER} + ${Build.MODEL}\n")
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Error while collect package info", e)
        }
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        ex.printStackTrace(pw)
        var cause = ex.cause
        while (cause != null) {
            cause.printStackTrace(pw)
            cause = cause.cause
        }
        pw.close()
        sb.append(sw.toString())
        try {
            val formatter = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
            val time = formatter.format(Date())
            val fileName = "crash-$time.log"
            val fos = context.openFileOutput(fileName, Context.MODE_PRIVATE)
            fos.write(sb.toString().toByteArray())
            fos.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error while writing crash info to file", e)
        }
    }
}