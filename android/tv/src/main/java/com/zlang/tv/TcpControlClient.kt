package com.zlang.tv
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
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.io.File
import java.util.Timer
import java.util.TimerTask
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.DataInputStream
import java.net.SocketTimeoutException
import java.net.SocketException
import java.net.ConnectException
import java.nio.charset.StandardCharsets

object TcpControlClient {

    private external fun nativeCreateClient(): Long
    private external fun nativeConnect(handle: Long, ip: String, port: Int): Boolean
    private external fun nativeDisconnect(handle: Long)
    private external fun nativeSendRequest(handle: Long, data: ByteArray): ByteArray?
    private external fun nativeSendRequestDownload(handle: Long, reqid: Long, data: ByteArray, filename: String): ByteArray?
    private external fun nativeDestroyClient(handle: Long)
    private external fun nativeisRunning(handle: Long): Boolean
    external fun nativeSendBroadcastAndReceive(list: ArrayList<String>, timeoutSeconds: Int)

    fun response(data: ByteArray): Boolean
    {
        try{
            val dataString = String(data, StandardCharsets.UTF_8)
            val responseJson : JSONObject = JSONObject(dataString)
            val id = responseJson.optLong("reqid", 0)
            if (id == reqid) {
                responseData = dataString
                isresponse = true
            }
            else if (id < 0){
                //处理服务器命令
                processServerRequest(responseJson)
            }
        }catch (e: Exception){
        }
        return true
    }

    private var nativeHandle: Long = 0
    private const val CONTROL_PORT = 25313
    private var serverIp: String? = null

    private var reqid : Long = 1
    private var isresponse : Boolean = false
    private var responseData : String? = null

    private val task = object : TimerTask() {
        override fun run() {

            checkAndReconnect()
            // 这里是定时执行的任务逻辑
            sendTlv("{}") // 类型0为心跳包
        }
    }
    // Android 端定时发送心跳
    private val heartbeatTimer = Timer().scheduleAtFixedRate(task, 1000 * 30, 1000 * 30)

    private fun checkAndReconnect()
    {
        synchronized(this) {
            var needconnect = false
            if (nativeHandle == 0L) {
                nativeHandle = nativeCreateClient()
                needconnect = true
            }
            else if (!nativeisRunning(nativeHandle)){
                nativeDestroyClient(nativeHandle)
                nativeHandle = nativeCreateClient()
                needconnect = true
            }
            if (needconnect)
            {
                val ip = serverIp
                if (ip != null)
                {
                    if (!nativeConnect(nativeHandle, ip, CONTROL_PORT)) {
                        //throw IllegalStateException("Connection failed")
                        nativeDestroyClient(nativeHandle)
                        nativeHandle = 0
                    }
                }
            }
        }
    }

    fun connect(serverIp: String) {
        synchronized(this) {
            this.serverIp = serverIp
        }
        checkAndReconnect()
    }

    fun sendTlv(json: String, filename: String? = null): JSONObject {
        checkAndReconnect()
        synchronized(this) {
            if (nativeHandle != 0L)
            {
                val noResponse = "{}".toByteArray()
                val jsonReq : JSONObject = JSONObject(json)
                reqid++
                jsonReq.put("reqid", reqid)
                responseData = null
                isresponse = false
                val requestData = jsonReq.toString().toByteArray(StandardCharsets.UTF_8)
                var waitms = 0
                if (null == filename){
                    nativeSendRequest(nativeHandle, requestData) ?: noResponse
                    waitms = 5000
                }else{
                    nativeSendRequestDownload(nativeHandle, reqid, requestData, filename) ?: noResponse
                    waitms = 1000 * 60 * 5
                }
                //等待返回
                for (i in 0..waitms / 100){
                    Thread.sleep(100)
                    if (isresponse)
                    {
                        break
                    }
                }

                val responseJson : String = responseData?:"{}"
                return JSONObject(responseJson).apply {
                    if (optString("status", "fail") != "success") {
                        //throw IllegalStateException(getString("message"))
                    }
                }
            }
            else{
                return JSONObject()
            }
        }
    }

    private fun processServerRequest(json : JSONObject)
    {

    }

    fun disconnect() {
        synchronized(this) {
            nativeDisconnect(nativeHandle)
            nativeDestroyClient(nativeHandle)
            nativeHandle = 0
        }
    }

    init {
        System.loadLibrary("native-lib")
    }


/*
        private var socket: Socket? = null
        private var writer: DataOutputStream? = null
        private var reader : DataInputStream ?= null
        private var ip : String? = null

        object TlvSender {
            private const val MAGIC_T = 0x7a321465

            fun readNextPacket(): String? {
                val input : DataInputStream ?= reader
                // Step 1: 读取T和L
                val header = ByteArray(4)
                while(true)
                {
                    try{
                        if (input?.read(header) != 4) return null
                    } catch (e: SocketTimeoutException) {
                        // 处理超时异常
                        println("读取超时: ${e.message}")
                        return null
                    } 
                    // 解析T和L（大端序）
                    val t = ByteBuffer.wrap(header, 0, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .int
                    if (t != MAGIC_T) {
                        Log.d("readNextPacket", ("Invalid T value: ${t}"))
                        println("tBytes: ${header}")
                        for (byte in header) {
                            print("%02X ".format(byte.toInt() and 0xFF))
                        }
                        println()
                    }
                    else
                    {
                        break
                    }
                }
                if (input?.read(header) != 4) return null
                var l = ByteBuffer.wrap(header, 0, 4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .int
                if (l == MAGIC_T)
                {
                    if (input?.read(header) != 4) return null
                    l = ByteBuffer.wrap(header, 0, 4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .int
                }
                if (l > 1024 * 1024)
                {
                    println("错误的长度：${l}")
                    return null
                }
                // Step 2: 读取V数据
                val jsonBytes = ByteArray(l)
                input?.readFully(jsonBytes)
                return String(jsonBytes, StandardCharsets.UTF_8)
            }


            fun IntToBigEndianBytes(value: Int): ByteArray {
                val bytes = ByteArray(4)
                bytes[0] = (value shr 24).toByte()
                bytes[1] = (value shr 16).toByte()
                bytes[2] = (value shr 8).toByte()
                bytes[3] = value.toByte()
                return bytes
            }

            fun sendJsonCommand(output: DataOutputStream?, json: String): String? {

                if (output == null)
                {
                    return null
                }

                // 转换字节序：大端
                val tBytes = IntToBigEndianBytes(MAGIC_T)

                println("tBytes: ${tBytes}")
                for (byte in tBytes) {
                    print("%02X ".format(byte.toInt() and 0xFF))
                }
                println()

                val jsonBytes = json.toByteArray(StandardCharsets.UTF_8)
                val lBytes = IntToBigEndianBytes(jsonBytes.size)

                println("lBytes: ${lBytes}")
                for (byte in lBytes) {
                    print("%02X ".format(byte.toInt() and 0xFF))
                }
                println()

                // 发送TLV
                synchronized(output) {
                    output?.write(tBytes)
                    output?.write(tBytes)
                    output?.write(lBytes)
                    output?.write(jsonBytes)
                    output?.flush()

                    return readNextPacket()
                }
            }
        }

        private val task = object : TimerTask() {
            override fun run() {
                // 这里是定时执行的任务逻辑
                sendTlv("{}") // 类型0为心跳包
            }
        }
        // Android 端定时发送心跳
        private val heartbeatTimer = Timer().scheduleAtFixedRate(task, 1000 * 30, 1000 * 30)
        
        fun connect(serverIp: String) {
            ip = serverIp
            socket = Socket(serverIp, 25313).apply {
                soTimeout = 5000 // 5秒超时
                writer = DataOutputStream(getOutputStream())
                reader = DataInputStream(getInputStream())
            }
        }

        fun sendPause() {
            val json = JSONObject().apply {
                put("action", "pause")
            }.toString()
            sendTlv(json)
        }

        fun sendSeek(positionMs: Long) {
            val json = JSONObject().apply {
                put("action", "seek")
                put("position", positionMs)
            }.toString()
            sendTlv(json)
        }

        @Synchronized
        fun sendTlv(json: String) : JSONObject {
            

            var jsonRsp = JSONObject()
            jsonRsp.put("status", "fail")

            try{
                val jsonStr = TlvSender.sendJsonCommand(writer, json)
                println(jsonStr)
                jsonRsp = JSONObject(jsonStr)
            }
            catch(e : SocketException)
            {
                if (e.message?.contains("sendto failed: EPIPE (Broken pipe)") == true) {
                    println("捕获到 Broken pipe 异常: ${e.message}")
                    //重连
                    val nonNullIp = ip
                    if (null != nonNullIp)
                    {
                        try{
                            connect(nonNullIp)
                        }
                        catch(e:ConnectException)
                        {
                            //连接不上
                        }
                        catch(e : Exception){
                            Log.e("sendTlv", "Error", e)
                            e.printStackTrace()
                        }
                    }
                } else {
                    println("捕获到其他 SocketException: ${e.message}")
                }
            }
            catch(e : Exception){
                Log.e("sendTlv", "Error", e)
                e.printStackTrace()
            }

            return jsonRsp
        }
    */
}
