package com.zlang.tv

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.tooling.preview.Preview
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.bumptech.glide.Glide
import com.zlang.tv.FileListActivity.Companion
import com.zlang.tv.ui.theme.TvTheme
import com.zzzmode.android.remotelogcat.LogcatRunner
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        var instance: MainActivity? = null
        var player: ExoPlayer? = null

        var unfinishedRecords = mutableListOf<VideoRecord>()
        var finishedRecords = mutableListOf<VideoRecord>()
        var smbServer : JSONArray? = null

        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "ServerSettings"
        private const val KEY_SERVER_IP = "server_ip"

        // 界面状态常量
        private const val VIEW_STATE_MAIN = 0      // 主界面（播放记录和命令列表）
        private const val VIEW_STATE_FILE_LIST = 1 // 文件列表
        private const val VIEW_STATE_PLAYBACK = 2  // 视频播放
        private const val REQUEST_CODE_VIDEO_PLAYER = 1001  // 添加请求码
        private const val REQUEST_CODE_RECORD_LIST = 1002  // 添加记录列表请求码
    }

    private lateinit var unfinishedRecyclerView: RecyclerView
    private lateinit var finishedRecyclerView: RecyclerView
    private lateinit var commandRecyclerView: RecyclerView
    private lateinit var contentContainer: FrameLayout
    private lateinit var fileListView: RecyclerView
    private lateinit var playerView: PlayerView
    private lateinit var playerContainer: FrameLayout
    private lateinit var controlsOverlay: LinearLayout
    private lateinit var videoSeekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var loadingDialog: Dialog
    private var serverIp = "192.168.2.80"
    private val serverPort = 9999
    private var currentPath = "drives"
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: PrintWriter? = null
    private var currentItems = mutableListOf<FileItem>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isConnected = false
    private var currentPosition = 0
    private var isSeekMode = false
    private var pendingSeekPosition: Long = 0
    private var currentVideoPath: String = ""
    private var progressUpdateHandler: Handler? = null
    private var progressUpdateRunnable: Runnable? = null
    private var currentPlaybackPosition: Long = 0
    private var startPosition: Long = 0

    // 播放重试相关变量
    private var retryPlaybackCount = 0
    private val maxPlaybackRetries = 10
    private val initialRetryDelay = 2000L
    private val maxRetryDelay = 10000L

    private var isLongPress = false
    private var longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    private var autoHideHandler = Handler(Looper.getMainLooper())
    private var autoHideRunnable: Runnable? = null
    private val AUTO_HIDE_DELAY = 10000L // 10秒

    val pathMap = mutableMapOf("" to 0)

    private var screenshotHandler = Handler(Looper.getMainLooper())
    private var screenshotRunnable: Runnable? = null
    private val FIRST_SCREENSHOT_DELAY = 1000L // 1秒后进行第一次截图
    private val SCREENSHOT_INTERVAL = 60000L // 60秒，即1分钟截图一次
    private var isFirstScreenshot = true // 标记是否为当前视频的第一次截图

    // 定期保存播放记录的相关变量
    private var saveRecordHandler = Handler(Looper.getMainLooper())
    private var saveRecordRunnable: Runnable? = null
    private val SAVE_RECORD_INTERVAL = 5000L // 5秒
    private var lastSaveTime = 0L

    private var currentViewState = VIEW_STATE_MAIN // 初始状态为主界面
    private var previousViewState = VIEW_STATE_MAIN // 记录上一个状态

    private lateinit var unfinishedRecyclerAdapter: VideoRecordAdapter
    private lateinit var finishedRecyclerAdapter: VideoRecordAdapter


    private lateinit var timeTextView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateTime() {
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val currentTime = dateFormat.format(Date())
        timeTextView.text = currentTime
    }



    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    Greeting("Android")
                }
            }
        }

        CrashHandler.getInstance().init(this)

        setContentView(R.layout.activity_main)
        instance = this

        // 初始化界面状态
        currentViewState = VIEW_STATE_MAIN
        previousViewState = VIEW_STATE_MAIN

        initializeViews()
        setupLoadingDialog()
        discoverServer()

        // 添加调试代码，检查"更多..."按钮的状态
        mainHandler.postDelayed({
            debugMoreButtons()
        }, 3000) // 延迟3秒后检查


        // 初始化定期保存记录的Runnable
        saveRecordRunnable = Runnable {
            savePlayRecords()
            // 继续下一次定时保存
            saveRecordHandler.postDelayed(saveRecordRunnable!!, SAVE_RECORD_INTERVAL)
        }
        // 开始定时保存播放记录
        startSaveRecordTimer()


        timeTextView = TextView(this)
        timeTextView.textSize = 16f // 增大字体大小，方便在电视上查看
        timeTextView.setTextColor(android.graphics.Color.GRAY) // 设置文字颜色为白色
        timeTextView.setPadding(5, 5, 5, 5) // 增加内边距
        val layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        layoutParams.gravity = android.view.Gravity.TOP or android.view.Gravity.END

        val rootView = window.decorView as android.widget.FrameLayout
        rootView.addView(timeTextView, layoutParams)

        updateTime()
        handler.postDelayed(updateTimeRunnable, 1000)

        // 在子线程中启动服务
        Thread {
            TelnetLikeService.start()
        }.start()

        PathRecordsManager.init(this)
    }

    private fun initializeViews() {
        unfinishedRecyclerView = findViewById(R.id.unfinishedRecyclerView)
        finishedRecyclerView = findViewById(R.id.finishedRecyclerView)
        commandRecyclerView = findViewById(R.id.commandRecyclerView)
        contentContainer = findViewById(R.id.contentContainer)
        fileListView = findViewById(R.id.fileListView)
        playerView = findViewById(R.id.playerView)
        playerContainer = findViewById(R.id.playerContainer)
        controlsOverlay = findViewById(R.id.controlsOverlay)
        videoSeekBar = findViewById(R.id.videoSeekBar)
        currentTimeText = findViewById(R.id.currentTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)

        // 设置水平布局管理器
        unfinishedRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        finishedRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        commandRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        // 初始化适配器
        unfinishedRecyclerAdapter = VideoRecordAdapter(emptyList()) { record ->
            playVideo(record.path, record.position)
        }
        unfinishedRecyclerView.adapter = unfinishedRecyclerAdapter

        finishedRecyclerAdapter = VideoRecordAdapter(emptyList()) { record ->
            playVideo(record.path, 0) // 已完成的从头开始播放
        }
        finishedRecyclerView.adapter = finishedRecyclerAdapter

        // 设置更多按钮的点击事件
        findViewById<TextView>(R.id.unfinishedMoreBtn)?.setOnClickListener {
            showFullRecordList(false)
        }
        findViewById<TextView>(R.id.finishedMoreBtn)?.setOnClickListener {
            showFullRecordList(true)
        }

        // 设置播放器相关
        playerView.keepScreenOn = true
        fileListView.layoutManager = LinearLayoutManager(this)
        //fileListView.setHasFixedSize(true)

        autoHideRunnable = Runnable { hideProgressBar() }
        screenshotRunnable = Runnable { takeScreenshot() }

        // 初始化命令列表
        setupCommandList()

        // 加载播放记录
        loadPlayRecords()
    }

    private fun setupLoadingDialog() {
        loadingDialog = Dialog(this).apply {
            setContentView(R.layout.dialog_loading)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setCancelable(false)
        }
    }

    private fun discoverServer() {
        loadingDialog.show()

        Thread {
            try {
                // 尝试从存储中获取上次保存的IP
                val savedIp = getSavedServerIp()

                val serverList = ArrayList<String>()
                val timeoutSeconds = 10
                // 尝试发现服务器
                TcpControlClient.nativeSendBroadcastAndReceive(serverList, timeoutSeconds) // 10秒超时

                runOnUiThread {
                    loadingDialog.dismiss()

                    when {
                        serverList.isNotEmpty() -> {
                            if (serverList.size == 1) {
                                // 只有一个服务器，直接使用
                                serverIp = serverList[0]
                                saveServerIp(serverIp)
                                initializeApp()
                            } else {
                                // 多个服务器，显示选择对话框
                                showServerSelectionDialog(serverList)
                            }
                        }
                        savedIp.isNotEmpty() -> {
                            // 没有发现服务器但有保存的IP
                            serverIp = savedIp
                            showToast("使用上次连接的服务器: $serverIp")
                            initializeApp()
                        }
                        else -> {
                            // 既没有发现服务器也没有保存的IP
                            showToast("未找到可用的服务器")
                            //finish()
                            initializeApp()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loadingDialog.dismiss()
                    val savedIp = getSavedServerIp()
                    if (savedIp.isNotEmpty()) {
                        serverIp = savedIp
                        showToast("使用上次连接的服务器: $serverIp")
                        initializeApp()
                    } else {
                        showToast("服务器发现失败")
                        //finish()
                        initializeApp()
                    }
                }
            }
        }.start()
    }

    private fun showServerSelectionDialog(serverList: List<String>) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_server_selection)
        dialog.setCancelable(false)

        val recyclerView = dialog.findViewById<RecyclerView>(R.id.serverListRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val adapter = ServerListAdapter(serverList) { selectedIp ->
            dialog.dismiss()
            serverIp = selectedIp
            saveServerIp(serverIp)
            initializeApp()
        }

        recyclerView.adapter = adapter
        dialog.show()

        // 设置默认焦点到第一项
        recyclerView.post {
            recyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun getSavedServerIp(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_IP, "") ?: ""
    }

    private fun saveServerIp(ip: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_IP, ip).apply()
    }

    private fun initializeApp() {
        // 删除播放器相关的初始化
        connectToServerWithRetry()

        LogcatRunner.getInstance().config(LogcatRunner.LogConfig.builder().write2File(true)).start()
    }


    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "播放错误", error)
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> {
                    retryPlayback()
                }
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                    retryPlayback()
                }
                else -> {
                    Log.d("onPlayerError", "播放出错：${error.message}")
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    Log.d("Player", "准备完成")
                    hideLoading()
                    startProgressUpdate()
                    retryPlaybackCount = 0
                    Log.d("PlayerRetry", "播放成功，重置重试计数")
                }
                Player.STATE_BUFFERING -> {
                    Log.d("Player", "正在缓冲")
                    showLoading()
                }
                Player.STATE_ENDED -> {
                    Log.d("Player", "播放完成")
                    // 处理播放结束
                    stopProgressUpdate()
                }
                Player.STATE_IDLE -> {
                    // 处理播放器空闲状态
                    Log.d("Player", "播放器空闲")
                }
            }
        }
    }

    private fun retryPlayback() {
        if (retryPlaybackCount < maxPlaybackRetries) {
            val delay = minOf(
                initialRetryDelay * (1 shl retryPlaybackCount),
                maxRetryDelay
            )

            mainHandler.postDelayed({
                player?.prepare()
                player?.play()
                retryPlaybackCount++
            }, delay)
        } else {
            showToast("重试次数已达上限，请检查网络连接")
            retryPlaybackCount = 0
        }
    }


    private fun updateProgressBar(position: Long, duration: Long) {
        if (!isSeekMode) {
            videoSeekBar.max = duration.toInt()
            videoSeekBar.progress = position.toInt()
            currentTimeText.text = formatTime(position)
            totalTimeText.text = formatTime(duration)
        }
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun showLoading() {
        // 实现加载提示UI
    }

    private fun hideLoading() {
        // 隐藏加载提示UI
    }




    private fun hideProgressBar() {
        controlsOverlay.visibility = View.GONE
        isSeekMode = false
        // 安全地取消自动隐藏定时器
        autoHideRunnable?.let { runnable ->
            autoHideHandler.removeCallbacks(runnable)
        }
        // 恢复进度更新
        startProgressUpdate()

        // 将焦点设置到activity上，确保能接收按键事件
        this.window.decorView.rootView.clearFocus()
        Log.d("PlayVideo", "进度条隐藏时已将焦点设置到Activity上")
    }


    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdate()
        stopScreenshotTimer()
        stopSaveRecordTimer() // 停止定时保存播放记录
        player?.release()
        player = null
        progressUpdateHandler?.removeCallbacks(progressUpdateRunnable ?: return)

        stopVideoStream()
        socket?.close()
        reader?.close()
        writer?.close()
        // 安全地清理自动隐藏的Handler
        autoHideRunnable?.let { runnable ->
            autoHideHandler.removeCallbacks(runnable)
        }
        handler.removeCallbacks(updateTimeRunnable)
    }

    private fun connectToServerWithRetry() {
        Thread {
            var retryCount = 0
            val maxRetries = 10

            while (!isConnected && retryCount < maxRetries) {
                try {
                    connectToServer()
                    if (isConnected) {
                        loadFileList()
                        loadSmbServer()
                        break
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Connection attempt ${retryCount + 1} failed", e)
                    retryCount++
                    if (retryCount < maxRetries) {
                        Thread.sleep(2000) // 等待2秒后重试
                    }
                }
            }

            if (!isConnected) {
                showToast("无法连接到服务器，请检查网络连接")
            }
        }.start()
    }

    private fun connectToServer() {
        try {
            TcpControlClient.connect(serverIp)
            isConnected = true
            Log.d("MainActivity", "Connected to server")
            showToast("已连接到服务器")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error connecting to server", e)
            isConnected = false
            throw e
        }
    }

    private fun loadFileList() {
        if (!isConnected) {
            showToast("未连接到服务器")
            return
        }

        Log.d("loadFileList", "currentPath: ${currentPath}")

        val command = JSONObject().apply {
            put("action", "list")
            put("path", currentPath)
        }
        for (i in 0..2)
        {
            try {
                val commandStr = command.toString()
                Log.d("ServerComm", "发送数据: $commandStr")
                //writer?.println(commandStr)
                //val response = reader?.readLine()
                //Log.d("ServerComm", "接收数据: $response")

                val response = TcpControlClient.sendTlv(commandStr)

                if (response == null) {
                    isConnected = false
                    showToast("服务器连接已断开")
                    connectToServerWithRetry()
                    return
                }

                //val jsonResponse = JSONObject(response)
                val jsonResponse = response
                val responseStr = response.toString()

                Log.d("ServerComm", "接收数据: $responseStr")

                if (jsonResponse.getString("status") == "success") {
                    val items = jsonResponse.getJSONArray("items")
                    currentItems.clear()

                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        currentItems.add(
                            FileItem(
                                name = item.getString("name"),
                                type = item.getString("type"),
                                path = item.getString("path")
                            )
                        )
                    }

                    runOnUiThread {
                        fileListView.adapter = FileListAdapter(currentItems) { item ->
                            handleItemClick(item)
                        }

                        if (currentItems.isNotEmpty()) {
                            /*
                            fileListView.post {
                                fileListView.getChildAt(0)?.requestFocus()
                            }*/
                            // 恢复到之前的位置
                            fileListView.post {
                                // 恢复焦点到当前项
                                var pathkey = currentPath.replace("\\", "/")
                                if (pathkey.last() != '/')
                                {
                                    pathkey = "${pathkey}/"
                                }
                                var targetPosition = pathMap[pathkey]?:0

                                Log.d("position", "${pathkey}: ${targetPosition}")

                                // 滚动到指定位置
                                fileListView.scrollToPosition(targetPosition)

                                // 确保滚动完成后让指定位置的项目获得焦点
                                fileListView.post {
                                    val viewHolder = fileListView.findViewHolderForAdapterPosition(targetPosition)
                                    viewHolder?.itemView?.requestFocus()
                                }
                            }


                        }


                    }
                } else {
                    val errorMessage = jsonResponse.optString("message", "未知错误")
                    Log.e("MainActivity", "Error loading file list: $responseStr")
                    showToast("加载文件列表失败: $errorMessage")
                    continue
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading file list", e)
                e.printStackTrace()
                isConnected = false
                showToast("加载文件列表失败，正在重新连接")
                connectToServerWithRetry()
                continue
            }
            break
        }
    }

    private fun handleItemClick(item: FileItem) {
        Log.d("MainActivity", "Item clicked: ${item.name}, type: ${item.type}, path: ${item.path}")

        currentPosition = (fileListView.layoutManager as LinearLayoutManager)
            .findFirstVisibleItemPosition()

        val newSelectedPosition = (fileListView.adapter as? FileListAdapter)?.getSelectedPosition()

        var pathkey = currentPath.replace("\\", "/")
        if (pathkey.last() != '/') {
            pathkey = "${pathkey}/"
        }

        println("${pathkey}: 选择项改变到 $newSelectedPosition")
        pathMap[pathkey] = newSelectedPosition ?: 0

        when (item.type) {
            "drive", "directory" -> {
                currentPath = item.path
                Thread {
                    loadFileList()
                }.start()
            }
            "file" -> {
                if (isVideoFile(item.path)) {
                    // 检查是否有播放记录
                    val record = unfinishedRecords.find { it.path == item.path }
                    if (record != null) {
                        if (record.isCompleted()) {
                            // 如果已经播放完成，从头开始播放
                            playVideo(item.path, 0)
                        } else {
                            // 从上次播放位置继续播放
                            playVideo(item.path, record.position)
                        }
                    } else {
                        // 没有播放记录，从头开始播放
                        playVideo(item.path)
                    }
                }
            }
        }
    }

    private fun isVideoFile(path: String): Boolean {
        return path.endsWith(".mp4", true) ||
            path.endsWith(".mkv", true) ||
            path.endsWith(".avi", true) ||
            path.endsWith(".mov", true) ||
            path.endsWith(".wmv", true) ||
            path.endsWith(".flv", true) ||
            path.endsWith(".m4v", true) ||
            path.endsWith(".mpg", true) ||
            path.endsWith(".mpeg", true) ||
            path.endsWith(".webm", true) ||
            path.endsWith(".ts", true) ||
            path.endsWith(".3gp", true)
    }



    private fun sendAACEncodeCommand(videoPath: String) {

        Thread {
            try {
                val command = JSONObject().apply {
                    put("action", "aac5.1")
                    put("path", videoPath)
                }

                val commandStr = command.toString()
                Log.d(TAG, "发送编码命令: $commandStr")
                val response = TcpControlClient.sendTlv(commandStr)

                runOnUiThread {

                    if (response == null) {
                        showToast("发送编码命令失败")
                        return@runOnUiThread
                    }

                    try {
                        val jsonResponse = response
                        if (jsonResponse.getString("status") == "success") {
                            showToast("编码命令已发送，请稍后查看")
                        } else {
                            val errorMessage = jsonResponse.optString("message", "未知错误")
                            showToast("编码命令失败: $errorMessage")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析编码响应出错", e)
                        showToast("解析编码响应出错")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送编码命令出错", e)
                runOnUiThread {
                    showToast("发送编码命令出错")
                }
            }
        }.start()
    }

    private fun sendEncodeCommand(videoPath: String) {
        if (!isConnected) {
            showToast("未连接到服务器")
            return
        }

        val command = JSONObject().apply {
            put("action", "enc")
            put("path", videoPath)
        }

        Thread {
            try {
                val commandStr = command.toString()
                Log.d("ServerComm", "发送转码命令: $commandStr")
                val response = TcpControlClient.sendTlv(commandStr)
                Log.d("ServerComm", "接收数据: $response")

                if (response == null) {
                    isConnected = false
                    showToast("服务器连接已断开")
                    connectToServerWithRetry()
                    return@Thread
                }

                val jsonResponse = response
                if (jsonResponse.getString("status") == "success") {
                    showToast("转码任务已提交")
                    // 延迟2秒后刷新列表
                    Thread.sleep(2000)
                    loadFileList()
                } else {
                    val errorMessage = jsonResponse.optString("message", "未知错误")
                    showToast("转码失败: $errorMessage")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error sending encode command", e)
                showToast("转码命令发送失败")
            }
        }.start()
    }

    private fun formatTimeForServer(millis: Long): Double {
        // 转换为带2位小数的浮点数秒
        return millis / 1000.0
    }


    private fun updateTimeText(current: Long, total: Long) {
        currentTimeText.text = formatTime(current)
        totalTimeText.text = formatTime(total)
    }


    override fun onBackPressed() {
        Log.d("Navigation", "调用onBackPressed，当前状态: $currentViewState, 前一状态: $previousViewState")
        when (currentViewState) {
            VIEW_STATE_FILE_LIST -> {
                // 从文件列表返回
                Log.d("Navigation", "从文件列表返回")
                if (currentPath != "drives") {
                    // 如果不是在根目录，返回上一级目录
                    Log.d("Navigation", "返回上一级目录，当前路径: $currentPath")
                    currentPath = File(currentPath.replace("\\", "/")).parent ?: "drives"
                    Log.d("Navigation", "新路径: $currentPath")
                    Thread {
                        loadFileList()
                    }.start()
        } else {
                    // 如果在根目录，返回主界面
                    Log.d("Navigation", "从根目录返回到主界面")
                    showMainInterface()
                }
                return
            }
            VIEW_STATE_MAIN -> {
                // 从主界面退出应用
                Log.d("Navigation", "从主界面退出应用")
                contentContainer.visibility = View.GONE
                super.onBackPressed()
                Toast.makeText(this, "应用即将退出", Toast.LENGTH_SHORT).show()
                System.exit(0)
            }
        }
    }



    private fun resetAutoHideTimer() {
        // 安全地取消之前的定时器
        autoHideRunnable?.let { runnable ->
            autoHideHandler.removeCallbacks(runnable)
            // 设置新的定时器
            autoHideHandler.postDelayed(runnable, AUTO_HIDE_DELAY)
        }
    }

    private fun playVideo(path: String, startPosition: Long = 0, ffmpeg:Boolean)
    {
        if (path.startsWith("smb://")) {
            // 启动VideoPlayerActivity
            val intent = SmbVideoPlayerActivity.createIntent(this, path, startPosition, serverIp)
            startActivityForResult(intent, REQUEST_CODE_VIDEO_PLAYER)
        }else{
            if (ffmpeg)
            {
                val intent =
                    FFmpegVideoPlayerActivity.createIntent(this, path, startPosition, serverIp, true)
                startActivityForResult(intent, REQUEST_CODE_VIDEO_PLAYER)
            }
            else {
                val intent =
                    VideoPlayerActivity.createIntent(this, path, startPosition, serverIp, ffmpeg)
                startActivityForResult(intent, REQUEST_CODE_VIDEO_PLAYER)
            }
        }
    }

    private fun playVideo(path: String, startPosition: Long = 0) {
        if (path.startsWith("smb://")) {
            playVideo(path, startPosition, false)
            return
        }

        if (isVideoFile(path)) {
                // 检查是否有播放记录
                val record = unfinishedRecords.find { it.path == path }
                if (record != null) {
                    if (record.isCompleted()) {
                        // 如果已经播放完成，从头开始播放
                        playVideo(path, 0, false)
                    } else {
                        // 从上次播放位置继续播放
                        playVideo(path, record.position, false)
                    }
                } else {
                    // 没有播放记录，从头开始播放
                    playVideo(path, 0, false)
                }

        }



    }

    // 处理从VideoPlayerActivity返回的结果
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_VIDEO_PLAYER || requestCode == REQUEST_CODE_RECORD_LIST) {
        }
    }

    override fun onResume() {
        super.onResume()

        // 返回主界面
        showMainInterface()
    }


    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (fileListView.visibility == View.VISIBLE) {
                    // 从文件列表返回
                    Log.d("KeyEvent", "从文件列表返回")
                    if (currentPath != "drives") {
                        // 如果不是在根目录，返回上一级目录
                        Log.d("KeyEvent", "从文件列表返回上一级。currentPath: ${currentPath}")
                        currentPath = File(currentPath.replace("\\", "/")).parent ?: "drives"
                        Log.d("KeyEvent", "新currentPath: ${currentPath}")
                        Thread {
                            loadFileList()
                        }.start()
                    } else {
                        // 如果在根目录，返回主界面
                        Log.d("KeyEvent", "从文件列表根目录返回主界面")
                        showMainInterface()
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return super.onKeyUp(keyCode, event)
    }

    private fun returnToFileList() {
        Log.d("Navigation", "开始从播放界面返回。previousViewState: $previousViewState, currentViewState: $currentViewState")

        // 停止视频流
        stopVideoStream()

        Log.d("Navigation", "从播放界面返回完成")
    }

    private fun loadSmbServer() {
        if (!isConnected) {
            return
        }

        val command = JSONObject().apply {
            put("action", "smblist")
        }

        Thread {
            try {
                val commandStr = command.toString()
                Log.d("ServerComm", "发送数据: $commandStr")
                val response = TcpControlClient.sendTlv(commandStr)

                Log.d("ServerComm", "接收数据: $response")

                if (response == null) {
                    isConnected = false
                    connectToServerWithRetry()
                    return@Thread
                }
                if (response.getString("status") == "success") {
                    smbServer = response.getJSONArray("items")
                    SmbConnectionManager.init(smbServer)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error stopping video stream", e)
            }
        }.start()
    }

    private fun stopVideoStream() {
        if (!isConnected) {
            return
        }

        val command = JSONObject().apply {
            put("action", "stop_stream")
        }

        Thread {
            try {
                val commandStr = command.toString()
                Log.d("ServerComm", "发送数据: $commandStr")
                //writer?.println(commandStr)
                //val response = reader?.readLine()
                val response = TcpControlClient.sendTlv(commandStr)

                Log.d("ServerComm", "接收数据: $response")

                if (response == null) {
                    isConnected = false
                    connectToServerWithRetry()
                    return@Thread
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error stopping video stream", e)
            }
        }.start()
    }

    private fun startProgressUpdate() {
        progressUpdateHandler?.removeCallbacks(progressUpdateRunnable!!)
        progressUpdateHandler?.postDelayed(progressUpdateRunnable!!, 1000)
    }

    private fun stopProgressUpdate() {
        progressUpdateHandler?.removeCallbacks(progressUpdateRunnable!!)
        currentPlaybackPosition = 0
    }

    private fun setupCommandList() {
        val commands = listOf(
            Command("浏览", R.drawable.ic_browse) {
                // 启动FileListActivity
                val intent = FileListActivity.createIntent(this, serverIp)
                startActivity(intent)
            },
            Command("Windows共享", R.drawable.ic_browse) {
            // 启动FileListActivity
            val intent = SmbServerListActivity.createIntent(this, serverIp)
            startActivity(intent)
        }
        )
        commandRecyclerView.adapter = CommandAdapter(commands)

        // 设置命令列表的上一个焦点是已完成的更多按钮(如果有)或未完成的更多按钮
        val finishedMoreBtn = findViewById<TextView>(R.id.finishedMoreBtn)
        val unfinishedMoreBtn = findViewById<TextView>(R.id.unfinishedMoreBtn)

        if (finishedMoreBtn?.visibility == View.VISIBLE) {
            commandRecyclerView.nextFocusUpId = R.id.finishedMoreBtn
        } else if (unfinishedMoreBtn?.visibility == View.VISIBLE) {
            commandRecyclerView.nextFocusUpId = R.id.unfinishedMoreBtn
        }
    }

    private fun showFileList() {
        previousViewState = currentViewState
        currentViewState = VIEW_STATE_FILE_LIST
        Log.d("Navigation", "显示文件列表。previousViewState: $previousViewState, currentViewState: $currentViewState")

        // 使用runOnUiThread确保所有UI操作在主线程执行
        runOnUiThread {
            try {
                // 设置界面元素可见性
                contentContainer.visibility = View.VISIBLE
                fileListView.visibility = View.VISIBLE
                playerContainer.visibility = View.GONE

                // 隐藏主界面元素
                unfinishedRecyclerView.visibility = View.GONE
                finishedRecyclerView.visibility = View.GONE
                commandRecyclerView.visibility = View.GONE


                // 恢复到之前的位置
                fileListView.post {
                    // 恢复焦点到当前项
                    var pathkey = currentPath.replace("\\", "/")
                    if (pathkey.last() != '/') {
                        pathkey = "${pathkey}/"
                    }
                    var targetPosition = pathMap[pathkey] ?: 0

                    Log.d("position", "${pathkey}: ${targetPosition}")
                    // 滚动到指定位置
                    fileListView.scrollToPosition(targetPosition)

                    // 确保滚动完成后让指定位置的项目获得焦点
                    fileListView.post {
                        val viewHolder = fileListView.findViewHolderForAdapterPosition(targetPosition)
                        viewHolder?.itemView?.requestFocus()
                    }
                }

                Log.d("Navigation", "文件列表显示完成，contentContainer可见性: ${if (contentContainer.visibility == View.VISIBLE) "VISIBLE" else "GONE"}")
        } catch (e: Exception) {
                Log.e("Navigation", "显示文件列表时出错", e)
            }
        }
    }

    private fun showMainInterface() {
        Log.d("Navigation", "开始显示主界面。previousViewState: $previousViewState, currentViewState: $currentViewState")
        previousViewState = currentViewState
        currentViewState = VIEW_STATE_MAIN

        // 使用runOnUiThread确保所有UI操作在主线程执行
        runOnUiThread {
            try {
                // 关键修复：主界面要把contentContainer设为GONE，而不是VISIBLE
                contentContainer.visibility = View.GONE
                fileListView.visibility = View.GONE
                playerContainer.visibility = View.GONE

                // 显示主界面元素
                unfinishedRecyclerView.visibility = View.VISIBLE
                finishedRecyclerView.visibility = View.VISIBLE
                commandRecyclerView.visibility = View.VISIBLE

                // 更新播放记录列表（这将会处理"更多"按钮的显示）
                updateRecordLists()

                // 选中"浏览"按钮
                commandRecyclerView.post {
                    // 获取第一个命令项（"浏览"按钮）并设置焦点
                    val commandViewHolder = commandRecyclerView.findViewHolderForAdapterPosition(0)
                    commandViewHolder?.itemView?.requestFocus()
                }

                Log.d("Navigation", "主界面显示完成，contentContainer可见性: ${if (contentContainer.visibility == View.VISIBLE) "VISIBLE" else "GONE"}")
            } catch (e: Exception) {
                Log.e("Navigation", "显示主界面时出错", e)
                // 出错时强制设置关键视图
                contentContainer.visibility = View.GONE
                unfinishedRecyclerView.visibility = View.VISIBLE
                finishedRecyclerView.visibility = View.VISIBLE
                commandRecyclerView.visibility = View.VISIBLE
            }
        }
    }

    private fun loadPlayRecords() {
        val sharedPreferences = getSharedPreferences("video_records", MODE_PRIVATE)

        // 如果存在旧版本的合并记录，先尝试加载并迁移
        val oldRecordsJson = sharedPreferences.getString("records", null)
        if (oldRecordsJson != null) {
            try {
                // 迁移旧数据到新格式
                migrateOldRecords(oldRecordsJson)
                // 迁移完成后删除旧数据
                sharedPreferences.edit().remove("records").apply()
                //Log.d("Records", "旧版本播放记录迁移完成")
            } catch (e: Exception) {
                //Log.e("MainActivity", "迁移旧播放记录出错", e)
            }
        } else {
            // 从新格式加载记录
            val unfinishedRecordsJson = sharedPreferences.getString("unfinished_records", null)
            val finishedRecordsJson = sharedPreferences.getString("finished_records", null)

            // 清空现有记录
            unfinishedRecords.clear()
            finishedRecords.clear()

            // 加载未完成记录
            if (unfinishedRecordsJson != null) {
                try {
                    val jsonArray = JSONArray(unfinishedRecordsJson)
                    for (i in 0 until jsonArray.length()) {
                        val recordObj = jsonArray.getJSONObject(i)
                        val path = recordObj.getString("path")
                        val thumbnailPath = recordObj.optString("thumbnailPath", "")

                        // 创建记录对象并添加到未完成列表
                        val record = VideoRecord(
                            path = path,
                            name = recordObj.getString("name"),
                            duration = recordObj.getLong("duration"),
                            position = recordObj.getLong("position"),
                            lastPlayTime = recordObj.getLong("lastPlayTime"),
                            thumbnailPath = if (thumbnailPath.isEmpty()) null else thumbnailPath,
                            ffmpeg = recordObj.optBoolean("ffmpeg")
                        )

                        unfinishedRecords.add(record)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "加载未完成播放记录出错", e)
                }
            }

            // 加载已完成记录
            if (finishedRecordsJson != null) {
                try {
                    val jsonArray = JSONArray(finishedRecordsJson)
                    for (i in 0 until jsonArray.length()) {
                        val recordObj = jsonArray.getJSONObject(i)
                        val path = recordObj.getString("path")
                        val thumbnailPath = recordObj.optString("thumbnailPath", "")

                        // 创建记录对象并添加到已完成列表
                        val record = VideoRecord(
                            path = path,
                            name = recordObj.getString("name"),
                            duration = recordObj.getLong("duration"),
                            position = recordObj.getLong("position"),
                            lastPlayTime = recordObj.getLong("lastPlayTime"),
                            thumbnailPath = if (thumbnailPath.isEmpty()) null else thumbnailPath,
                            ffmpeg = recordObj.optBoolean("ffmpeg")
                        )

                        finishedRecords.add(record)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "加载已完成播放记录出错", e)
                }
            }

            //Log.d("Records", "成功加载播放记录（已完成: ${finishedRecords.size}, 未完成: ${unfinishedRecords.size}）")
        }

        // 确保记录分类正确
        cleanupDuplicateRecords()
        updateRecordLists()
    }

    // 迁移旧版记录数据到新格式
    private fun migrateOldRecords(oldRecordsJson: String) {
        try {
            val jsonArray = JSONArray(oldRecordsJson)

            // 清空现有记录
            unfinishedRecords.clear()
            finishedRecords.clear()

            // 使用临时Map存储唯一路径对应的记录
            val tmpRecords = mutableMapOf<String, VideoRecord>()

            for (i in 0 until jsonArray.length()) {
                val recordObj = jsonArray.getJSONObject(i)
                val path = recordObj.getString("path")
                val thumbnailPath = recordObj.optString("thumbnailPath", "")

                // 创建记录对象
                val record = VideoRecord(
                    path = path,
                    name = recordObj.getString("name"),
                    duration = recordObj.getLong("duration"),
                    position = recordObj.getLong("position"),
                    lastPlayTime = recordObj.getLong("lastPlayTime"),
                    thumbnailPath = if (thumbnailPath.isEmpty()) null else thumbnailPath
                )

                // 将记录添加到临时Map中，确保每个路径只有一条记录
                tmpRecords[path] = record
            }

            // 根据完成状态将记录分配到对应列表
            tmpRecords.values.forEach { record ->
                if (record.isCompleted()) {
                    finishedRecords.add(record)
                } else {
                    unfinishedRecords.add(record)
                }
            }

            // 保存到新格式
            savePlayRecords()

            //Log.d("Records", "迁移了 ${tmpRecords.size} 条播放记录（已完成: ${finishedRecords.size}, 未完成: ${unfinishedRecords.size}）")
        } catch (e: Exception) {
            Log.e("MainActivity", "迁移旧播放记录出错", e)
        }
    }

    private fun updateRecordLists() {
        //Log.d("Records", "开始更新记录列表，未完成记录: ${unfinishedRecords.size}，已完成记录: ${finishedRecords.size}")

        // 先检查并清理可能的重复记录
        cleanupDuplicateRecords()

        // 先进行过滤和排序，确保记录分类正确
        val displayUnfinishedRecords = unfinishedRecords
            .filter { !it.isCompleted() }
            .sortedByDescending { it.lastPlayTime }
            .take(4)

        val displayFinishedRecords = finishedRecords
            .filter { it.isCompleted() }
            .sortedByDescending { it.lastPlayTime }
            .take(4)

        //Log.d("Records", "过滤后的显示记录数量 - 未完成: ${displayUnfinishedRecords.size}，已完成: ${displayFinishedRecords.size}")

        // 输出记录详情到日志
        displayUnfinishedRecords.forEachIndexed { index, record ->
            //Log.d("Records", "显示未完成[$index]: ${record.path}, 进度: ${record.position}/${record.duration}, 完成状态: ${record.isCompleted()}")
        }

        displayFinishedRecords.forEachIndexed { index, record ->
            //Log.d("Records", "显示已完成[$index]: ${record.path}, 进度: ${record.position}/${record.duration}, 完成状态: ${record.isCompleted()}")
        }

        try {
            // 保存原始状态以便于日志对比
            val originalUnfinishedVisibility = unfinishedRecyclerView.visibility
            val originalFinishedVisibility = finishedRecyclerView.visibility
            val originalUnfinishedMoreBtnVisibility = findViewById<TextView>(R.id.unfinishedMoreBtn)?.visibility ?: View.GONE
            val originalFinishedMoreBtnVisibility = findViewById<TextView>(R.id.finishedMoreBtn)?.visibility ?: View.GONE

            // 重新创建适配器以确保点击事件正确处理
            unfinishedRecyclerAdapter = VideoRecordAdapter(displayUnfinishedRecords) { record ->
                //Log.d("Records", "点击未完成记录: ${record.path}, 位置: ${record.position}/${record.duration}")
                playVideo(record.path, record.position, record.ffmpeg)
            }
            unfinishedRecyclerView.adapter = unfinishedRecyclerAdapter

            finishedRecyclerAdapter = VideoRecordAdapter(displayFinishedRecords) { record ->
                //Log.d("Records", "点击已完成记录: ${record.path}, 位置: ${record.position}/${record.duration}")
                playVideo(record.path, 0, record.ffmpeg)  // 已完成记录从头开始播放
            }
            finishedRecyclerView.adapter = finishedRecyclerAdapter

            // 设置可见性
            unfinishedRecyclerView.visibility = if (displayUnfinishedRecords.isNotEmpty()) View.VISIBLE else View.GONE
            finishedRecyclerView.visibility = if (displayFinishedRecords.isNotEmpty()) View.VISIBLE else View.GONE

            // 设置"更多"按钮的可见性，并确保可点击性
            val unfinishedMoreBtn = findViewById<TextView>(R.id.unfinishedMoreBtn)
            unfinishedMoreBtn?.let {
                it.visibility = if (displayUnfinishedRecords.isNotEmpty()) View.VISIBLE else View.GONE
                it.isClickable = true
                it.isFocusable = true
                it.nextFocusDownId = if (displayFinishedRecords.isNotEmpty())
                    R.id.finishedRecyclerView else R.id.commandRecyclerView
            }

            val finishedMoreBtn = findViewById<TextView>(R.id.finishedMoreBtn)
            finishedMoreBtn?.let {
                it.visibility = if (displayFinishedRecords.isNotEmpty()) View.VISIBLE else View.GONE
                it.isClickable = true
                it.isFocusable = true
                it.nextFocusDownId = if (displayFinishedRecords.isNotEmpty())
                    R.id.finishedRecyclerView else R.id.commandRecyclerView
            }

            // 设置焦点顺序导航
            // 1. 未完成列表的下一个焦点是未完成的"更多"按钮
            unfinishedRecyclerView.nextFocusDownId = R.id.unfinishedMoreBtn

            // 2. 未完成的"更多"按钮的下一个焦点是已完成列表(如果有)或浏览按钮

            // 3. 已完成列表的下一个焦点是已完成的"更多"按钮
            finishedRecyclerView.nextFocusDownId = R.id.finishedMoreBtn
            finishedRecyclerView.nextFocusRightId = R.id.finishedMoreBtn
            unfinishedMoreBtn.nextFocusDownId = R.id.finishedMoreBtn

            // 4. 已完成的"更多"按钮的下一个焦点是浏览按钮

            // 记录可见性变化
            //Log.d("UI", "未完成记录可见性: $originalUnfinishedVisibility -> ${unfinishedRecyclerView.visibility}")
            //Log.d("UI", "已完成记录可见性: $originalFinishedVisibility -> ${finishedRecyclerView.visibility}")
            //Log.d("UI", "未完成'更多'按钮可见性: $originalUnfinishedMoreBtnVisibility -> ${unfinishedMoreBtn?.visibility ?: View.GONE}")
            //Log.d("UI", "已完成'更多'按钮可见性: $originalFinishedMoreBtnVisibility -> ${finishedMoreBtn?.visibility ?: View.GONE}")

            // 检查并日志记录按钮位置
            unfinishedMoreBtn?.post {
                val location = IntArray(2)
                unfinishedMoreBtn.getLocationOnScreen(location)
                //Log.d("UI", "未完成'更多'按钮位置: x=${location[0]}, y=${location[1]}, 宽=${unfinishedMoreBtn.width}, 高=${unfinishedMoreBtn.height}")
            }

            finishedMoreBtn?.post {
                val location = IntArray(2)
                finishedMoreBtn.getLocationOnScreen(location)
                //Log.d("UI", "已完成'更多'按钮位置: x=${location[0]}, y=${location[1]}, 宽=${finishedMoreBtn.width}, 高=${finishedMoreBtn.height}")
            }

        } catch (e: Exception) {
            Log.e("UI", "更新记录列表时出错", e)
        }
    }

    /**
     * 检查并清理两个列表间可能存在的重复记录
     * 确保同一个文件路径只会出现在一个列表中
     */
    private fun cleanupDuplicateRecords() {
        // 获取两个列表中的所有路径
        val unfinishedPaths = unfinishedRecords.map { it.path }
        val finishedPaths = finishedRecords.map { it.path }

        // 找出重复的路径
        val duplicatePaths = unfinishedPaths.intersect(finishedPaths.toSet())

        if (duplicatePaths.isNotEmpty()) {
            Log.w("Records", "清理重复记录: 发现 ${duplicatePaths.size} 个路径同时存在于两个列表")

            // 遍历每个重复路径
            for (path in duplicatePaths) {
                val unfinishedRecord = unfinishedRecords.find { it.path == path }
                val finishedRecord = finishedRecords.find { it.path == path }

                // 如果两条记录都存在，保留最新更新的一条
                if (unfinishedRecord != null && finishedRecord != null) {
                    if (unfinishedRecord.lastPlayTime > finishedRecord.lastPlayTime) {
                        // 未完成记录更新，保留未完成记录
                        finishedRecords.remove(finishedRecord)
                        //Log.d("Records", "保留未完成记录: $path")
                    } else {
                        // 已完成记录更新，保留已完成记录
                        unfinishedRecords.remove(unfinishedRecord)
                        //Log.d("Records", "保留已完成记录: $path")
                    }
                }
            }

            //Log.d("Records", "清理完成，现在未完成记录: ${unfinishedRecords.size}, 已完成记录: ${finishedRecords.size}")
        }
    }

    private fun showFullRecordList(isCompleted: Boolean) {
        // 启动RecordListActivity显示完整记录列表
        val intent = RecordListActivity.createIntent(this, serverIp, isCompleted)
        startActivityForResult(intent, REQUEST_CODE_RECORD_LIST)
    }

    fun getStringMD5(input: String): String {
        // 获取 MD5 算法实例
        val digest = MessageDigest.getInstance("MD5")
        // 对输入的字符串进行编码并更新摘要
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        // 将字节数组转换为十六进制字符串
        return bytesToHex(hash)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val result = StringBuilder()
        for (byte in bytes) {
            result.append(String.format("%02x", byte))
        }
        return result.toString()
    }

    private fun takeScreenshot() {
        if (player == null || playerView == null) {
            stopScreenshotTimer() // 如果播放器不可用，停止截图
            return
        }

        try {
            // 获取当前视频帧
            val textureView = playerView.videoSurfaceView as? TextureView
            if (textureView == null || !textureView.isAvailable) {
                Log.e("Screenshot", "TextureView 不可用")
                // 重新安排下一次截图
                scheduleNextScreenshot()
                return
            }

            val bitmap = textureView.bitmap
            if (bitmap == null) {
                Log.e("Screenshot", "无法获取视频帧")
                // 重新安排下一次截图
                scheduleNextScreenshot()
                return
            }

            // 将耗时的保存操作放到后台线程中执行
        Thread {
                var savedSuccessfully = false
                try {
                    // 创建截图保存目录
                    val screenshotDir = File(getExternalFilesDir(null), "screenshots")
                    if (!screenshotDir.exists()) {
                        screenshotDir.mkdirs()
                    }

                    // 使用视频路径的MD5加上时间戳作为文件名，确保每次生成不同名称的截图
                    val videoPathMd5 = getStringMD5(currentVideoPath)
                    val timestamp = System.currentTimeMillis()
                    val screenshotFile = File(screenshotDir, "${videoPathMd5}_${timestamp}.jpg")

                    // 保存图片
                    try {
                        FileOutputStream(screenshotFile).use { out ->
                            // 压缩并保存图片，使用JPEG格式，质量为80%
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            out.flush()
                        }

                        // 在主线程中更新播放记录的缩略图
                        mainHandler.post {
                            // 更新播放记录的缩略图
                            val record = unfinishedRecords.find { it.path == currentVideoPath }
                                ?: finishedRecords.find { it.path == currentVideoPath }
                            
                            record?.let {
                                // 如果之前有缩略图，则删除旧的缩略图文件
                                if (it.thumbnailPath != null) {
                                    val oldFile = File(it.thumbnailPath!!)
                                    if (oldFile.exists() && oldFile.absolutePath != screenshotFile.absolutePath) {
                                        try {
                                            oldFile.delete()
                                            Log.d("Screenshot", "删除旧缩略图：${oldFile.absolutePath}")
                                        } catch (e: Exception) {
                                            Log.e("Screenshot", "删除旧缩略图失败", e)
                                        }
                                    }
                                }
                                
                                it.thumbnailPath = screenshotFile.absolutePath
                                
                                // 保存更新后的记录
                                savePlayRecords()
                                
                                // 主动更新适配器，使列表中的缩略图立即更新
                                updateThumbnailInAdapters(it.path, it.thumbnailPath!!)
                            }
                        }

                        Log.d("Screenshot", "截图保存成功：${screenshotFile.absolutePath}")
                        savedSuccessfully = true
                    } catch (e: IOException) {
                        Log.e("Screenshot", "保存截图失败", e)
                    }
                } catch (e: Exception) {
                    Log.e("Screenshot", "保存截图过程出错", e)
                } finally {
                    // 在后台线程安全地回收位图
                    bitmap.recycle()
                    
                    // 如果保存成功，在主线程调度下一次截图
                    if (savedSuccessfully) {
                        mainHandler.post {
                            scheduleNextScreenshot()
                    }
                } else {
                        // 截图失败，稍后重试
                        mainHandler.postDelayed({
                            scheduleNextScreenshot()
                        }, 10000) // 10秒后重试
                    }
            }
        }.start()
        } catch (e: Exception) {
            Log.e("Screenshot", "截图过程出错", e)
            // 出错后也要调度下一次截图
            scheduleNextScreenshot()
        }
    }
    
    // 更新主界面适配器中的缩略图
    fun updateThumbnailInAdapters(videoPath: String, thumbnailPath: String) {
        try {
            // 查找并更新未完成记录适配器中的项
            val unfinishedPosition = unfinishedRecyclerAdapter.findPositionByPath(videoPath)
            if (unfinishedPosition >= 0) {
                unfinishedRecyclerAdapter.notifyItemChanged(unfinishedPosition)
                Log.d("Screenshot", "已更新未完成列表中的缩略图，位置：$unfinishedPosition")
                unfinishedRecords.find { it.path == videoPath } ?.thumbnailPath = thumbnailPath
            }
            
            // 查找并更新已完成记录适配器中的项
            val finishedPosition = finishedRecyclerAdapter.findPositionByPath(videoPath)
            if (finishedPosition >= 0) {
                finishedRecyclerAdapter.notifyItemChanged(finishedPosition)
                Log.d("Screenshot", "已更新已完成列表中的缩略图，位置：$finishedPosition")
                finishedRecords.find { it.path == videoPath } ?.thumbnailPath = thumbnailPath
            }
            Glide.get(this).clearMemory()
            Thread {
                Glide.get(this).clearDiskCache()
            }.start()
        } catch (e: Exception) {
            Log.e("Screenshot", "更新适配器中的缩略图失败", e)
        }
    }
    
    // 调度下一次截图
    private fun scheduleNextScreenshot() {
        screenshotRunnable?.let { runnable ->
            screenshotHandler.removeCallbacks(runnable)
            
            // 根据是否是当前视频的第一次截图设置不同的延迟
            val delay = if (isFirstScreenshot) {
                isFirstScreenshot = false // 设置为非第一次
                FIRST_SCREENSHOT_DELAY // 1秒后进行第一次截图
        } else {
                SCREENSHOT_INTERVAL // 后续每60秒截图一次
            }
            
            screenshotHandler.postDelayed(runnable, delay)
            Log.d("Screenshot", "已调度下一次截图，${delay/1000}秒后执行，视频路径: $currentVideoPath")
        }
    }

    private fun startScreenshotTimer() {
        screenshotHandler.removeCallbacks(screenshotRunnable!!)
        isFirstScreenshot = true // 重置为当前视频的第一次截图
        scheduleNextScreenshot() // 使用新的调度方法
        Log.d("Screenshot", "开始截图计时器，将在1秒后进行首次截图，视频路径: $currentVideoPath")
    }

    private fun stopScreenshotTimer() {
        screenshotHandler.removeCallbacks(screenshotRunnable!!)
    }

    // 添加专门用于调试"更多..."按钮的方法
    private fun debugMoreButtons() {
        val unfinishedMoreBtn = findViewById<TextView>(R.id.unfinishedMoreBtn)
        val finishedMoreBtn = findViewById<TextView>(R.id.finishedMoreBtn)
        
        Log.d("ButtonDebug", "未完成更多按钮: ${unfinishedMoreBtn != null}, 可见性: ${unfinishedMoreBtn?.visibility}")
        Log.d("ButtonDebug", "已完成更多按钮: ${finishedMoreBtn != null}, 可见性: ${finishedMoreBtn?.visibility}")
        
        // 检查按钮的位置和尺寸
        unfinishedMoreBtn?.let {
            val location = IntArray(2)
            it.getLocationOnScreen(location)
            Log.d("ButtonDebug", "未完成更多按钮位置: x=${location[0]}, y=${location[1]}, 宽度=${it.width}, 高度=${it.height}")
        }
        
        finishedMoreBtn?.let {
            val location = IntArray(2)
            it.getLocationOnScreen(location)
            Log.d("ButtonDebug", "已完成更多按钮位置: x=${location[0]}, y=${location[1]}, 宽度=${it.width}, 高度=${it.height}")
        }
    }

    // 开始定时保存播放记录
    private fun startSaveRecordTimer() {
        saveRecordHandler.removeCallbacks(saveRecordRunnable!!)
        saveRecordHandler.postDelayed(saveRecordRunnable!!, SAVE_RECORD_INTERVAL)
        lastSaveTime = System.currentTimeMillis()
    }

    // 停止定时保存播放记录
    private fun stopSaveRecordTimer() {
        saveRecordHandler.removeCallbacks(saveRecordRunnable!!)
    }

    fun savePlayRecords() {
        val sharedPreferences = getSharedPreferences("video_records", MODE_PRIVATE)

        // 分别创建未完成和已完成记录的JSON数组
        val unfinishedJsonArray = JSONArray()
        val finishedJsonArray = JSONArray()

        // 先清理可能存在的重复记录
        cleanupDuplicateRecords()

        // 保存未完成记录
        unfinishedRecords.forEach { record ->
            val recordObj = JSONObject().apply {
                put("path", record.path)
                put("name", record.name)
                put("duration", record.duration)
                put("position", record.position)
                put("lastPlayTime", record.lastPlayTime)
                put("thumbnailPath", record.thumbnailPath ?: "")
                put("ffmpeg", record.ffmpeg)
            }
            unfinishedJsonArray.put(recordObj)
        }

        // 保存已完成记录
        finishedRecords.forEach { record ->
            val recordObj = JSONObject().apply {
                put("path", record.path)
                put("name", record.name)
                put("duration", record.duration)
                put("position", record.position)
                put("lastPlayTime", record.lastPlayTime)
                put("thumbnailPath", record.thumbnailPath ?: "")
                put("ffmpeg", record.ffmpeg)
            }
            finishedJsonArray.put(recordObj)
        }

        // 分别保存两个数组到不同的键下
        sharedPreferences.edit()
            .putString("unfinished_records", unfinishedJsonArray.toString())
            .putString("finished_records", finishedJsonArray.toString())
            .apply()

        //Log.d("Records", "保存播放记录 - 未完成: ${unfinishedRecords.size}, 已完成: ${finishedRecords.size}")
    }

    fun updatePlayRecord(path: String, position: Long, videoDuration: Long, ffmpeg: Boolean = false) {
        //Log.d("Records", "更新播放记录: path=$path, position=$position, duration=$videoDuration")

        // 先检查并从两个列表中移除该路径的记录
        val existingRecordInUnfinished = unfinishedRecords.find { it.path == path }
        val existingRecordInFinished = finishedRecords.find { it.path == path }

        // 记录之前是否在完成列表中
        val wasInFinishedList = existingRecordInFinished != null

        // 确保从两个列表中都删除此路径的记录
        unfinishedRecords.removeAll { it.path == path }
        finishedRecords.removeAll { it.path == path }

        // 决定使用哪个现有记录，优先使用未完成的记录
        val existingRecord = existingRecordInUnfinished ?: existingRecordInFinished

        if (existingRecord != null) {
            // 更新现有记录
            existingRecord.position = position
            existingRecord.duration = videoDuration
            existingRecord.lastPlayTime = System.currentTimeMillis()
            existingRecord.ffmpeg = ffmpeg

            // 根据完成状态添加到相应列表
            if (existingRecord.isCompleted()) {
                //Log.d("Records", "更新记录已完成，添加到finishedRecords: $path")
                if (!wasInFinishedList) {
                    //Log.d("Records", "记录从未完成移动到已完成: $path")
                }
                finishedRecords.add(existingRecord)
            } else {
                //Log.d("Records", "更新记录未完成，添加到unfinishedRecords: $path")
                if (wasInFinishedList) {
                    //Log.d("Records", "记录从已完成移动到未完成: $path")
                }
                unfinishedRecords.add(existingRecord)
            }
        } else {
            // 创建新记录
            val newRecord = VideoRecord(
                path = path,
                name = File(path).name,
                duration = videoDuration,
                position = position,
                lastPlayTime = System.currentTimeMillis(),
                ffmpeg = ffmpeg
            )

            // 根据完成状态添加到相应列表
            if (newRecord.isCompleted()) {
                //Log.d("Records", "新记录已完成，添加到finishedRecords: $path")
                finishedRecords.add(newRecord)
            } else {
                //Log.d("Records", "新记录未完成，添加到unfinishedRecords: $path")
                unfinishedRecords.add(newRecord)
            }
        }

        // 检查是否有重复记录
        val unfinishedPaths = unfinishedRecords.map { it.path }
        val finishedPaths = finishedRecords.map { it.path }
        val duplicatePaths = unfinishedPaths.intersect(finishedPaths.toSet())

        if (duplicatePaths.isNotEmpty()) {
            //Log.w("Records", "警告：检测到同一文件同时存在于未完成和已完成列表中: $duplicatePaths")
            // 处理重复记录 - 从未完成列表中移除已完成的记录
            unfinishedRecords.removeAll { it.path in duplicatePaths }
            //Log.d("Records", "已移除重复记录，现在未完成记录数: ${unfinishedRecords.size}, 已完成记录数: ${finishedRecords.size}")
        }

        // 更新列表显示
        updateRecordLists()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    TvTheme {
        Greeting("Android")
    }
}
