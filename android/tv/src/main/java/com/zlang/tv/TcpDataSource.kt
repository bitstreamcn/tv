package com.zlang.tv

import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.json.JSONObject
import java.io.IOException

class TcpDataSource(serverIp: String, ffmpeg: Boolean) : BaseDataSource(/* isNetwork= */ true) {

    private external fun nativeCreate(): Long
    private external fun nativeConnect(handle: Long, ip: String, port: Int): Boolean
    private external fun nativeRead(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    private external fun nativeDestroy(handle: Long)

    private var nativeHandle: Long = 0
    private var isOpen = false
    private var currentDataSpec: DataSpec? = null
    private val serverIp = serverIp
    private val ffmpeg = ffmpeg

    companion object {
        var pause = false
        private const val TAG = "TcpDataSource"
    }

    init {
        System.loadLibrary("native-lib")
    }

    fun connect(ip: String, port: Int): Boolean {
        if (nativeHandle != 0L) {
            return true
        }
        nativeHandle = nativeCreate()
        return nativeConnect(nativeHandle, ip, port)
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        release()

        currentDataSpec = dataSpec

        try {
            val _uri = dataSpec.uri
            Log.d("TcpDataSource", _uri.toString())
            val size = if (ffmpeg)
            {
                0
            }
            else
            {
                startVideo(_uri.toString(), dataSpec.position)
            }

            if (!connect(serverIp, 25314)) {
                throw IOException("Failed to connect to server")
            }
            isOpen = true
            if (size <= 0 || ffmpeg){
                return C.LENGTH_UNSET.toLong()
            }
            return size
        } catch (e: Exception) {
            throw IOException("Error opening TcpDataSource", e)
        }
    }

    var zeroCount = 0
    var noEnoughCount = 0
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!isOpen || nativeHandle == 0L) {
            return C.RESULT_END_OF_INPUT
        }
        if (pause) {
            return 0
        }

        try {
            val bytesRead = nativeRead(nativeHandle, buffer, offset, length)
            if (bytesRead == 0) {
            zeroCount++
                if (zeroCount >= 100) {
                    //Log.d(TAG, "TcpDataSource read zeroCount: $zeroCount")
                zeroCount = 0
            }
            } else if (bytesRead < length) {
                noEnoughCount++
                if (noEnoughCount >= 100) {
                    //Log.d(TAG, "TcpDataSource read noEnoughCount: $noEnoughCount")
                    noEnoughCount = 0
                }
            }

            return if (bytesRead >= 0) bytesRead else C.RESULT_END_OF_INPUT
        } catch (e: Exception) {
            throw IOException("Error reading data", e)
        }
    }

    override fun getUri() = currentDataSpec?.uri

    override fun close() {
        try {
            isOpen = false
            release()
            currentDataSpec = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing source", e)
        }
    }

    class Factory(serverIp : String, ffmpeg: Boolean) : DataSource.Factory {
        val serverIp = serverIp
        val ffmpeg = ffmpeg
        override fun createDataSource(): DataSource {
             return TcpDataSource(serverIp, ffmpeg)
        }
    }

    private fun formatTimeForServer(millis: Long): Double {
        // 转换为带2位小数的浮点数秒
        return millis / 1000.0
    }


    private fun startVideo(currentVideoPath:String, position: Long) : Long {
        Log.d("PlayVideo", "调用startVideoFromPosition:  视频路径=$currentVideoPath")

        val command = JSONObject().apply {
            put("action", "stream")
            put("path", currentVideoPath)
            put("start_time", position)
            put("ffmpeg", ffmpeg)
        }

        //Thread {
            try {
                val commandStr = command.toString()
                Log.d("ServerComm", "发送视频定位请求: $commandStr")
                val response = TcpControlClient.sendTlv(commandStr)
                Log.d("ServerComm", "定位请求接收数据: $response")

                if (response == null) {
                    //return@Thread 0L
                    return 0L
                }

                val jsonResponse = response

                if (jsonResponse.getString("status") == "success") {
                    val durationSeconds = jsonResponse.optDouble("duration", 0.0)
                    Log.d("ServerComm", "视频时长(秒): $durationSeconds")
                    return durationSeconds.toLong()
                } else {
                    val errorMessage = jsonResponse.optString("message", "未知错误")
                    Log.e("PlayVideo", "调整视频位置失败: $errorMessage")
                }
            } catch (e: Exception) {
                Log.e("PlayVideo", "视频定位出错", e)
            }
        //}.start()
        return 0L
    }

    private fun stopVideoStream() {

        val command = JSONObject().apply {
            put("action", "stop_stream")
        }

        //Thread {
            try {
                val commandStr = command.toString()
                Log.d("ServerComm", "发送数据: $commandStr")
                //writer?.println(commandStr)
                //val response = reader?.readLine()
                val response = TcpControlClient.sendTlv(commandStr)

                Log.d("ServerComm", "接收数据: $response")

                if (response == null) {
                    //return@Thread
                    return
                }
            } catch (e: Exception) {
                Log.e("VideoPlayerActivity", "Error stopping video stream", e)
            }
        //}.start()
    }


} 