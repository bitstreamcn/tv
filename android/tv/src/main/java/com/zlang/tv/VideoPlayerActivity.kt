package com.zlang.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoPlayerActivity : ComponentActivity() {
    companion object {
        private const val TAG = "VideoPlayerActivity"
        private const val EXTRA_VIDEO_PATH = "video_path"
        private const val EXTRA_START_POSITION = "start_position"
        private const val EXTRA_SERVER_IP = "server_ip"
        private const val EXTRA_FFMPEG = "ffmpeg"

        var instance : VideoPlayerActivity? = null
        
        // 创建启动Activity的Intent
        fun createIntent(context: Context, path: String, startPosition: Long, serverIp: String, ffmpeg: Boolean): Intent {
            return Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_PATH, path)
                putExtra(EXTRA_START_POSITION, startPosition)
                putExtra(EXTRA_SERVER_IP, serverIp)
                putExtra(EXTRA_FFMPEG, ffmpeg)
            }
        }
    }
    
    // UI组件
    private lateinit var playerView: PlayerView

    
    // 播放器状态
    var player: ExoPlayer? = null
    private var serverIp: String = ""
    private var currentVideoPath: String = ""
    private var startPosition: Long = 0
    private var currentPlaybackPosition: Long = 0
    private var videoDuration: Long = 0
    private var isSeekMode: Boolean = false
    private var isFfmpeg:Boolean = false

    private var isfinish = false;
    
    // 重试相关变量
    private var retryPlaybackCount = 0
    private val maxPlaybackRetries = 10
    private val initialRetryDelay = 2000L
    private val maxRetryDelay = 10000L
    
    // 控制界面相关
    private var autoHideHandler = Handler(Looper.getMainLooper())
    private var autoHideRunnable: Runnable? = null
    private val AUTO_HIDE_DELAY = 10000L // 10秒
    
    // 截图相关
    private var screenshotHandler = Handler(Looper.getMainLooper())
    private var screenshotRunnable: Runnable? = null
    private val FIRST_SCREENSHOT_DELAY = 10000L // 10秒后进行第一次截图
    private val SCREENSHOT_INTERVAL = 60000L // 60秒，即1分钟截图一次
    private var isFirstScreenshot = true // 标记是否为当前视频的第一次截图
    
    // 保存记录相关
    private var saveRecordHandler = Handler(Looper.getMainLooper())
    private var saveRecordRunnable: Runnable? = null
    private val SAVE_RECORD_INTERVAL = 5000L // 5秒
    private var lastSaveTime = 0L
    
    // 长按相关
    private var isLongPress = false
    private var longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 进度更新
    private var progressUpdateHandler: Handler? = null
    private var progressUpdateRunnable: Runnable? = null

    private var bufferMonitor: BufferMonitor? = null


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

    private val retryRunnable = object : Runnable {
        override fun run() {
            if (isfinish){
                return;
            }
            if (null == player || player?.currentPosition == 0L || player?.bufferedPosition == 0L) {
                // 初始化播放器
                setupPlayer()
                // 开始播放
                playVideo(currentVideoPath, startPosition)
                handler.postDelayed(this, 10000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)
        
        // 从Intent获取参数
        currentVideoPath = intent.getStringExtra(EXTRA_VIDEO_PATH) ?: ""
        startPosition = intent.getLongExtra(EXTRA_START_POSITION, 0)
        serverIp = intent.getStringExtra(EXTRA_SERVER_IP) ?: ""
        isFfmpeg = intent.getBooleanExtra(EXTRA_FFMPEG, false)
        
        if (currentVideoPath.isEmpty() || serverIp.isEmpty()) {
            showToast("参数错误")
            finish()
            return
        }
        
        // 初始化UI组件
        initializeViews()
        
        // 初始化播放器
        setupPlayer()
        
        // 设置进度更新
        setupProgressUpdate()
        
        // 开始播放
        playVideo(currentVideoPath, startPosition)

        instance = this


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
        handler.postDelayed(retryRunnable, 10000)
    }


    
    private fun initializeViews() {
        playerView = findViewById(R.id.playerView)


        // 设置播放器相关
        playerView.keepScreenOn = true

        playerView.requestFocus()
        
        // 初始化自动隐藏Runnable
        autoHideRunnable = Runnable { hideProgressBar() }
        // 初始化截图Runnable
        screenshotRunnable = Runnable { takeScreenshot() }
        
        // 设置SeekBar监听
        setupSeekBar()
        
        // 设置控制按钮点击事件
        setupControlButtons()
    }
    
    private fun setupPlayer() {
        try {
            bufferMonitor?.stop()
            player?.release()


            // 配置 ExoPlayer
            val trackSelector = DefaultTrackSelector(this).apply {
                setParameters(buildUponParameters().setMaxVideoSizeSd())
            }
            
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    2000,//DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    60000,//DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    1000,//DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    2000//DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .build()

            player = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .build()
                .apply {
                    addListener(playerListener)
                    // 设置默认的播放参数
                    playWhenReady = true
                    repeatMode = Player.REPEAT_MODE_OFF
                }
                
            playerView.player = player

            player?.repeatMode = Player.REPEAT_MODE_OFF

            bufferMonitor = BufferMonitor(this, player).apply {
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up player", e)
            showToast("播放器初始化失败")
        }
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
                    retryPlayback();
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
                    // 返回结果
                    setResult(RESULT_OK)
                    finish()
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
    
    private fun showLoading() {
        // 实现加载提示UI
    }

    private fun hideLoading() {
        // 隐藏加载提示UI
    }

    
    private fun setupSeekBar() {

    }
    
    private fun setupControlButtons() {

        


    }
    
    private fun updatePlayPauseText() {

    }
    

    
    private fun setupProgressUpdate() {

        
        // 初始化定期保存记录的Runnable
        saveRecordRunnable = Runnable {
            if (currentVideoPath.isNotEmpty()) {
                Log.d("Records", "定时保存播放记录: $currentVideoPath, 位置: $currentPlaybackPosition")
                savePlaybackPosition(currentVideoPath, currentPlaybackPosition)
            }
            // 继续下一次定时保存
            saveRecordHandler.postDelayed(saveRecordRunnable!!, SAVE_RECORD_INTERVAL)
        }
    }
    
    private fun startProgressUpdate() {
        progressUpdateHandler?.removeCallbacks(progressUpdateRunnable!!)
        progressUpdateHandler?.postDelayed(progressUpdateRunnable!!, 1000)
        // 开始定时保存记录
        startSaveRecordTimer()
    }
    
    private fun stopProgressUpdate() {
        progressUpdateHandler?.removeCallbacks(progressUpdateRunnable!!)
        currentPlaybackPosition = 0
    }
    
    private fun showProgressBar() {
        Log.d("ProgressBar", "显示进度条")

        isSeekMode = true

        // 重置自动隐藏定时器
        resetAutoHideTimer()
        

        Log.d("ProgressBar", "进度条显示时已将焦点设置到进度条上")
    }
    
    private fun hideProgressBar() {

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
    
    private fun resetAutoHideTimer() {
        // 安全地取消之前的定时器
        autoHideRunnable?.let { runnable ->
            autoHideHandler.removeCallbacks(runnable)
            // 设置新的定时器
            autoHideHandler.postDelayed(runnable, AUTO_HIDE_DELAY)
        }
    }
    
    private fun updateTimeText(current: Long, total: Long) {

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
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (isSeekMode) {
                    hideProgressBar()
                    return true
                }
                finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (isSeekMode) {
                    if (!(player?.playWhenReady?:false))
                    {
                        player?.playWhenReady = !(player?.playWhenReady ?: false)
                        hideProgressBar()
                    }
                    else {
                        // 确认当前进度并开始播放
                        hideProgressBar()
                    }
                    return true
                } else {

                    // 切换播放/暂停状态
                    player?.playWhenReady = !(player?.playWhenReady ?: false)
                    updatePlayPauseText()
                    Log.d("KeyEvent", "切换播放状态: ${if (player?.playWhenReady == true) "播放" else "暂停"}")
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                Log.d("KeyEvent", "处理左右键: ${if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) "左" else "右"}")

                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    player?.let {
                        val newPos = it.currentPosition - 5000
                        it.seekTo(newPos.coerceAtLeast(0))
                    }
                }else{
                    player?.let {
                        val newPos = it.currentPosition + 5000
                        it.seekTo(newPos.coerceAtMost(it.duration))
                    }
                }
                playerView.showController() // 强制显示控制器
                playerView.controllerAutoShow = true // 确保自动隐藏机制生效
                // 立即显示进度条
                if (!isSeekMode) {
                    Log.d("KeyEvent", "显示进度条")

                } else {
                    // 如果已经显示，则重置自动隐藏定时器
                    resetAutoHideTimer()
                }


                // 处理进度调整
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            isLongPress = false
            longPressHandler.removeCallbacks(longPressRunnable ?: return super.onKeyUp(keyCode, event))
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun formatTimeForServer(millis: Long): Double {
        // 转换为带2位小数的浮点数秒
        return millis / 1000.0
    }

    private fun startPlaying() {
        try {
            // 1. 创建 DataSource.Factory，传入serverIp
            val dataSourceFactory = TcpDataSource.Factory(serverIp, isFfmpeg)

            // 2. 创建 MediaSource
            val mediaItem = MediaItem.Builder()
                .setUri(currentVideoPath)
                //.setMimeType(MimeTypes.VIDEO_MP4) // 或者使用适当的 MIME 类型
                .build()

            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)

            // 3. 创建并设置 MediaSource
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

            // 配置播放参数
            player?.apply {
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
                seekTo(startPosition)
            }

            // 将焦点设置到activity上，确保能接收按键事件
            this.window.decorView.rootView.clearFocus()
            Log.d("PlayVideo", "启动播放时已将焦点设置到Activity上")

            // 更新UI
            updatePlayPauseText()

            // 开始截图计时
            startScreenshotTimer()

            // 将焦点设置到Activity上，确保能接收按键事件
            this.window.decorView.rootView.clearFocus()
            Log.d(TAG, "已将焦点设置到Activity上")


        } catch (e: Exception) {
            Log.e("PlayerError", "设置媒体源错误", e)
            retryPlayback()
        }
    }
    
    private fun playVideo(path: String, startPosition: Long = 0) {
        try {
            Log.d(TAG, "开始播放视频: path=$path, startPosition=$startPosition")
            
            if (path.isEmpty()) {
                showToast("视频路径不能为空")
                return
            }
            
            currentVideoPath = path
            this.startPosition = startPosition

            runOnUiThread {
                Log.d("PlayVideo", "定位请求成功，重新设置播放器")
                setupPlayer()
                startPlaying()

                // 将焦点设置到activity上，确保能接收按键事件
                this.window.decorView.rootView.clearFocus()
                Log.d("PlayVideo", "从指定位置播放时已将焦点设置到Activity上")
            }


        } catch (e: Exception) {
            Log.e(TAG, "播放视频时出错", e)
            showToast("播放视频时出错: ${e.message}")
        }
    }

    // 调度下一次截图
    private fun scheduleNextScreenshot() {
        screenshotRunnable?.let { runnable ->
            screenshotHandler.removeCallbacks(runnable)

            // 根据是否是当前视频的第一次截图设置不同的延迟
            val delay = if (isFirstScreenshot) {
                if (player?.isPlaying?:false) {
                    isFirstScreenshot = false // 设置为非第一次
                }
                FIRST_SCREENSHOT_DELAY // 1秒后进行第一次截图
            } else {
                SCREENSHOT_INTERVAL // 后续每60秒截图一次
            }

            screenshotHandler.postDelayed(runnable, delay)
            Log.d("Screenshot", "已调度下一次截图，${delay/1000}秒后执行，视频路径: $currentVideoPath")
        }
    }
    private fun startScreenshotTimer() {
        isFirstScreenshot = true
        // 取消之前的截图任务
        screenshotRunnable?.let { runnable ->
            screenshotHandler.removeCallbacks(runnable)
        }
        scheduleNextScreenshot() // 使用新的调度方法
    }
    private fun stopScreenshotTimer() {
        screenshotHandler.removeCallbacks(screenshotRunnable!!)
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
                    val screenshotFile = File(screenshotDir, "${videoPathMd5}.jpg")

                    // 保存图片
                    try {
                        FileOutputStream(screenshotFile).use { out ->
                            // 压缩并保存图片，使用JPEG格式，质量为80%
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            out.flush()
                        }

                        // 更新播放记录的缩略图
                        val record = MainActivity.unfinishedRecords.find { it.path == currentVideoPath }
                            ?: MainActivity.finishedRecords.find { it.path == currentVideoPath }

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
                            MainActivity.instance?.savePlayRecords()

                            // 主动更新适配器，使列表中的缩略图立即更新
                            runOnUiThread {
                                MainActivity.instance?.updateThumbnailInAdapters(
                                    it.path,
                                    it.thumbnailPath!!
                                )
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

    private fun startSaveRecordTimer() {
        // 取消之前的保存记录任务
        saveRecordRunnable?.let { runnable ->
            saveRecordHandler.removeCallbacks(runnable)
        }
        // 设置定时保存记录
        saveRecordHandler.postDelayed(saveRecordRunnable!!, SAVE_RECORD_INTERVAL)
        lastSaveTime = System.currentTimeMillis()
    }
    
    private fun savePlaybackPosition(videoPath: String, position: Long) {
        try {
            if (videoPath.isEmpty()) return
            
            //MainActivity.instance?.updatePlayRecord(videoPath, position, videoDuration)
            MainActivity.instance?.updatePlayRecord(videoPath, player?.currentPosition?:0, player?.duration?:0, isFfmpeg)
        } catch (e: Exception) {
            Log.e(TAG, "保存播放记录时出错", e)
        }
    }

    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    override fun onPause() {
        super.onPause()
        // 暂停播放
        player?.playWhenReady = false

        // 停止自动隐藏定时器
        autoHideRunnable?.let { runnable ->
            autoHideHandler.removeCallbacks(runnable)
        }
        
        // 停止截图定时器
        screenshotRunnable?.let { runnable ->
            screenshotHandler.removeCallbacks(runnable)
        }
        
        // 停止保存记录定时器
        saveRecordRunnable?.let { runnable ->
            saveRecordHandler.removeCallbacks(runnable)
        }
        
        // 停止进度更新
        stopProgressUpdate()
    }
    
    override fun onResume() {
        super.onResume()
        // 如果已经初始化了播放器，则恢复播放
        if (player != null && currentVideoPath.isNotEmpty()) {
            // 恢复播放
            player?.playWhenReady = true
            // 恢复进度更新
            startProgressUpdate()
            // 恢复截图定时器
            startScreenshotTimer()
            // 恢复保存记录定时器
            startSaveRecordTimer()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()

        bufferMonitor?.stop()
        // 释放播放器资源
        player?.release()
        player = null
        
        // 取消所有定时器
        autoHideRunnable?.let { runnable ->
            autoHideHandler.removeCallbacks(runnable)
        }
        
        screenshotRunnable?.let { runnable ->
            screenshotHandler.removeCallbacks(runnable)
        }
        
        saveRecordRunnable?.let { runnable ->
            saveRecordHandler.removeCallbacks(runnable)
        }
        
        progressUpdateRunnable?.let { runnable ->
            progressUpdateHandler?.removeCallbacks(runnable)
        }

        instance = null

        handler.removeCallbacks(updateTimeRunnable)

        isfinish = true;
    }
} 