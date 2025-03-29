package com.zlang.tv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

class SmbVideoPlayerActivity : ComponentActivity() {
    companion object {
        private const val TAG = "VideoPlayerActivity"
        private const val EXTRA_VIDEO_PATH = "video_path"
        private const val EXTRA_START_POSITION = "start_position"
        private const val EXTRA_SERVER_IP = "server_ip"

        var instance : SmbVideoPlayerActivity? = null
        
        // 创建启动Activity的Intent
        fun createIntent(context: Context, path: String, startPosition: Long, serverIp: String): Intent {
            return Intent(context, SmbVideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_PATH, path)
                putExtra(EXTRA_START_POSITION, startPosition)
                putExtra(EXTRA_SERVER_IP, serverIp)
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
    private var isSeekMode: Boolean = false
    
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
    private val FIRST_SCREENSHOT_DELAY = 1000L // 1秒后进行第一次截图
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


    private lateinit var timeTextView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 1000)
        }
    }

    private var isfinish = false;

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
                handler.postDelayed(this, 5000)
            }
        }
    }
    private var smbServerIp: String = ""
    private var smbServer : JSONObject? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_smb_video_player)
        
        // 从Intent获取参数
        currentVideoPath = intent.getStringExtra(EXTRA_VIDEO_PATH) ?: ""
        startPosition = intent.getLongExtra(EXTRA_START_POSITION, 0)
        serverIp = intent.getStringExtra(EXTRA_SERVER_IP) ?: ""

        // 解析 SMB URL（格式：smb://username:password@host/share/path/file.mp4）
        val uri = Uri.parse(currentVideoPath)
        smbServerIp = uri.host ?: throw IOException("Invalid SMB host")

        if (currentVideoPath.isEmpty() || serverIp.isEmpty()) {
            showToast("参数错误")
            finish()
            return
        }

        Log.d("SmbVideoPlayerActivity", "播放地址：" + currentVideoPath)

        smbServer = MainActivity.smbServer?.let { serverArray ->
            for (i in 0 until serverArray.length()) {
                val server = serverArray.getJSONObject(i)
                if (server.getString("ip") == smbServerIp) {
                    return@let server
                }
            }
            return@let null
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
        handler.postDelayed(retryRunnable, 5000)
    }

    private fun initializeViews() {
        playerView = findViewById(R.id.playerView)

        
        // 设置播放器相关
        playerView.keepScreenOn = true
        

        // 初始化截图Runnable
        screenshotRunnable = Runnable { takeScreenshot() }
        


    }


    private fun setupPlayer() {
        try {
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

            // 配置播放器参数
            playerView.useController = true // 禁用默认控制器
            // 禁用所有按键事件
            playerView.setUseController(true)  // 确保控制器不响应按键

            player?.repeatMode = Player.REPEAT_MODE_ONE

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
    

    

    
    private fun resetAutoHideTimer() {
        // 安全地取消之前的定时器
        autoHideRunnable?.let { runnable ->
            autoHideHandler.removeCallbacks(runnable)
            // 设置新的定时器
            autoHideHandler.postDelayed(runnable, AUTO_HIDE_DELAY)
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


    private fun formatTimeForServer(millis: Long): Double {
        // 转换为带2位小数的浮点数秒
        return millis / 1000.0
    }


    class StreamToMPEGTSConverter() {

        private val maxBufferSize: Int = 188 * 1024 * 128
        private val lock = ReentrantLock()
        private val condition = lock.newCondition()
        private val byteArrayOutputStream = ByteArrayOutputStream()
        private var isRunning = true
        private var conversionThread: Thread? = null
        private var seek: Long = 0L
        var filesize:Long = 0L

        fun startConvert(inputStream: InputStream, seek: Long)
        {
            this.seek = seek
            close()
            // 启动转换线程
            conversionThread = thread(start = true) { convert(inputStream) }
        }

        class InputStreamMediaDataSource(private val inputStream: InputStream, private val filesize:Long) : MediaDataSource() {
            private val buffer = ByteArray(1024 * 1024) // 1MB buffer

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                inputStream.skip(position)
                val bytesRead = inputStream.read(buffer, offset, size)
                return if (bytesRead == -1) {
                    -1
                } else {
                    bytesRead
                }
            }

            override fun getSize(): Long {
                return filesize
            }

            override fun close() {
                inputStream.close()
            }
        }

        @Throws(IOException::class)
        private fun convertToMPEGTS(inputStream: InputStream) {

            val dataSource = InputStreamMediaDataSource(inputStream, filesize)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(dataSource)
                // 继续处理 extractor，例如获取轨道信息等
            } catch (e: IOException) {
                e.printStackTrace()
            }

            val videoTracks = mutableListOf<Int>()
            val audioTracks = mutableListOf<Int>()
            val subtitleTracks = mutableListOf<Int>()

            val trackCount = extractor.trackCount
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                when {
                    mime?.startsWith("video/") == true -> videoTracks.add(i)
                    mime?.startsWith("audio/") == true -> audioTracks.add(i)
                    mime?.startsWith("application/") == true -> subtitleTracks.add(i) // 假设字幕轨道以 application/ 开头
                }
            }

            if (videoTracks.isEmpty() || audioTracks.isEmpty()) {
                throw IOException("No video or audio track found in the input stream.")
            }

            val muxer = MediaMuxer(byteArrayOutputStream.toString(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val videoTrackIndices = videoTracks.map { extractor.getTrackFormat(it) }
                .map { muxer.addTrack(it) }

            val audioTrackIndices = audioTracks.map { extractor.getTrackFormat(it) }
                .map { muxer.addTrack(it) }

            val subtitleTrackIndices = subtitleTracks.map { extractor.getTrackFormat(it) }
                .map { muxer.addTrack(it) }

            muxer.start()

            val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB buffer
            val bufferInfo = MediaCodec.BufferInfo()

            if (seek > 0L)
            {
                extractor.seekTo(seek, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            }

            while (isRunning) {
                var processed = false

                for (i in videoTracks) {
                    if (extractor.sampleTrackIndex == i) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) continue
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = extractor.sampleTime
                        bufferInfo.flags = extractor.sampleFlags

                        // 检查缓冲区的大小，超过最大缓冲区时，暂停转换线程
                        lock.lock()
                        try {
                            if (byteArrayOutputStream.size() > maxBufferSize) {
                                condition.await()
                            }

                            muxer.writeSampleData(videoTrackIndices[videoTracks.indexOf(i)], buffer, bufferInfo)
                            byteArrayOutputStream.write(buffer.array(), bufferInfo.offset, bufferInfo.size)  // Save data to output stream
                            extractor.advance()
                            processed = true
                        } finally {
                            lock.unlock()
                        }

                    }
                }

                for (i in audioTracks) {
                    if (extractor.sampleTrackIndex == i) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) continue
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = extractor.sampleTime
                        bufferInfo.flags = extractor.sampleFlags

                        lock.lock()
                        try {
                            if (byteArrayOutputStream.size() > maxBufferSize) {
                                condition.await()
                            }

                            muxer.writeSampleData(audioTrackIndices[audioTracks.indexOf(i)], buffer, bufferInfo)
                            byteArrayOutputStream.write(buffer.array(), bufferInfo.offset, bufferInfo.size)  // Save data to output stream
                            extractor.advance()
                            processed = true
                        } finally {
                            lock.unlock()
                        }
                    }
                }

                for (i in subtitleTracks) {
                    if (extractor.sampleTrackIndex == i) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) continue
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = extractor.sampleTime
                        bufferInfo.flags = extractor.sampleFlags

                        lock.lock()
                        try {
                            if (byteArrayOutputStream.size() > maxBufferSize) {
                                condition.await()
                            }

                            muxer.writeSampleData(subtitleTrackIndices[subtitleTracks.indexOf(i)], buffer, bufferInfo)
                            byteArrayOutputStream.write(buffer.array(), bufferInfo.offset, bufferInfo.size)  // Save data to output stream
                            extractor.advance()
                            processed = true
                        } finally {
                            lock.unlock()
                        }
                    }
                }

                if (!processed) break
            }

            muxer.stop()
            muxer.release()
            extractor.release()

        }

        // 读取方法，每次读取指定长度的数据
        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            lock.lock()
            try {
                val data = byteArrayOutputStream.toByteArray()
                val bytesToRead = minOf(length, data.size - offset)

                if (bytesToRead > 0) {
                    System.arraycopy(data, 0, buffer, offset, bytesToRead)
                    byteArrayOutputStream.reset()
                    byteArrayOutputStream.write(data, bytesToRead, data.size - bytesToRead)
                    condition.signalAll() // 唤醒任何等待的线程
                    return bytesToRead
                }
                return 0 // No data to read
            } finally {
                lock.unlock()
            }
        }

        // 关闭方法，停止转换线程并清理资源
        fun close() {
            isRunning = false
            lock.lock()
            try {
                condition.signalAll() // 唤醒任何等待的线程
            } finally {
                lock.unlock()
            }
            conversionThread?.join() // 等待转换线程结束
        }

        private fun convert(inputStream: InputStream) {
            // 这里添加转换的具体逻辑
            // 在 convertToMPEGTS 函数中已实现，转换线程会持续运行直到 isRunning 为 false
            convertToMPEGTS(inputStream)
        }
    }


    class SmbDataSource : BaseDataSource(false) {
        private var inputStream: SmbFileInputStream? = null

        private var smbFile: SmbFile? = null
        private var fileSize: Long = 0
        private var currentPosition: Long = 0  // 记录当前读取位置
        private var uri : String = ""
        private val convert = StreamToMPEGTSConverter()

        private fun createCifsContext(): CIFSContext {
            val props = java.util.Properties().apply {
                // 设置超时时间（单位：毫秒）
                setProperty("jcifs.smb.client.responseTimeout", "5000")
                setProperty("jcifs.smb.client.soTimeout", "5000")
                // 禁用签名验证（根据服务器配置调整）
                setProperty("jcifs.smb.client.disableSMB2SignatureVerify", "true")
            }
            val config = PropertyConfiguration(props)
            return BaseContext(config)
        }

        override fun open(dataSpec: DataSpec): Long {

            close()

            // 初始化CIFS上下文
            val properties = java.util.Properties()
            val smbUri = java.net.URI.create(dataSpec.uri.toString())
            val cifsContext = createCifsContext()
            val authenticator = NtlmPasswordAuthenticator(null, smbUri.userInfo.split(":")[0], smbUri.userInfo.split(":")[1]) // 域参数传 null（默认）
            // 构建认证上下文
            val authContext = cifsContext.withCredentials(authenticator)

            // 解析 SMB URL（格式：smb://username:password@host/share/path/file.mp4）
            val _uri = dataSpec.uri
            uri = _uri.toString()
            Log.d("SmbDataSource", _uri.toString())

            try {
                smbFile = SmbFile(_uri.toString(), authContext)
                inputStream = SmbFileInputStream(smbFile)
            } catch (e: SmbException) {
                Log.e("SambaError", "Samba 文件打开错误: ${e.message}", e)
            } catch (e: IOException) {
                Log.e("SambaError", "读取文件时发生 I/O 错误: ${e.message}", e)
            } catch (e: Exception) {
                Log.e("SambaError", "发生未知错误: ${e.message}", e)
            }

            // 跳转到指定位置
            if (dataSpec.position > 0) {
                var bytesToSkip = dataSpec.position
                inputStream?.skip(bytesToSkip)
            }
            currentPosition = dataSpec.position  // 初始化当前位置
            fileSize = smbFile?.length()?:0L

            convert.filesize = fileSize

            val _is = inputStream
            if (null != _is) {
                //convert.startConvert(_is, dataSpec.position)
            }

            return fileSize
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {

            //Log.d("SmbVideoPlayerActivity", "player read:" + offset + ", " + length)

            val bytesRead = try {
                inputStream?.read(buffer, offset, length)
                //convert.read(buffer, offset, length)
            } catch (e: Exception) {
                throw IOException("Read failed: ${e.message}", e)
            }

            if (bytesRead == -1) {
                return C.RESULT_END_OF_INPUT
            }

            //Log.d("SmbVideoPlayerActivity", "player read bytesRead:" + bytesRead)

            return bytesRead?:0
        }

        override fun getUri() : Uri{
            return Uri.parse(if (uri == "" ) {"smb://"}else uri)
        }

        override fun close() {
            inputStream?.close()
            smbFile?.close()
            inputStream = null
            smbFile = null
            convert.close()
        }
    }

    class SmbDataSourceFactory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }


    private fun startPlaying() {
        try {
            val user = smbServer?.getString("user")?:""
            val password = smbServer?.getString("password")?:""
            // 1. 创建 DataSource.Factory，传入serverIp
            val dataSourceFactory = SmbDataSourceFactory()
            // 构建 SMB URL（格式：smb://user:password@192.168.1.100/share/video.mp4）
            val smbUri = Uri.parse(currentVideoPath)
            val mediaItem = MediaItem.Builder()
                .setUri(smbUri)
                //.setMimeType(MimeTypes.VIDEO_MP4) // 或者使用适当的 MIME 类型
                .build()


            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(dataSourceFactory)

            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

            player?.apply {
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }

            if (startPosition > 0)
            {
                player?.seekTo(startPosition)
            }



            // 开始截图计时
            startScreenshotTimer()



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


            Thread {
                try {

                        runOnUiThread {
                            Log.d("PlayVideo", "定位请求成功，重新设置播放器")
                            setupPlayer()
                            startPlaying()

                        }
                } catch (e: Exception) {
                    Log.e("PlayVideo", "视频定位出错", e)
                    showToast("调整视频位置失败")
                }
            }.start()



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
        if (player == null) {
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
            
            MainActivity.instance?.updatePlayRecord(videoPath, position, player?.duration?:0)
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

        stopScreenshotTimer()


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