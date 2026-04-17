package com.zlang.tv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.widget.AppCompatImageView
import org.json.JSONObject
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 导入ScaleType
import android.widget.ImageView.ScaleType

class PhotoViewerActivity : ComponentActivity() {
    companion object {
        private const val TAG = "PhotoViewerActivity"
        private const val EXTRA_IMAGE_PATH = "image_path"
        private const val EXTRA_IMAGE_LIST = "image_list"
        private const val EXTRA_CURRENT_INDEX = "current_index"
        private const val EXTRA_SERVER_IP = "server_ip"

        var instance: PhotoViewerActivity? = null

        // 创建启动Activity的Intent（单张图片）
        fun createIntent(context: Context, path: String, serverIp: String): Intent {
            return Intent(context, PhotoViewerActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_PATH, path)
                putExtra(EXTRA_SERVER_IP, serverIp)
            }
        }

        // 创建启动Activity的Intent（图片列表）
        fun createIntent(context: Context, imageList: ArrayList<String>, currentIndex: Int, serverIp: String): Intent {
            return Intent(context, PhotoViewerActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_IMAGE_LIST, imageList)
                putExtra(EXTRA_CURRENT_INDEX, currentIndex)
                putExtra(EXTRA_SERVER_IP, serverIp)
            }
        }
    }

    // UI组件
    private lateinit var imageView: AppCompatImageView
    private lateinit var navigationTextView: TextView

    // 图片状态
    private var currentImagePath: String = ""
    private var imageList: ArrayList<String> = ArrayList()
    private var currentIndex: Int = 0
    private var serverIp: String = ""
    private var originalBitmap: Bitmap? = null
    private var isZoomed = false
    private var originalScaleType: ScaleType? = null
    private var originalMatrix: Matrix? = null
    
    // 图片旋转相关
    private var currentRotation = 0f

    // 时间显示相关
    private lateinit var timeTextView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 1000)
        }
    }

    // loading对话框相关
    private var loadingDialog: androidx.appcompat.app.AlertDialog? = null

    // 后台预下载相关
    private var isDownloadingNextImage = false
    private var nextImageDownloadThread: Thread? = null
    private var nextImageFilePath: String = ""
    
    // 前一张图片预下载相关
    private var isDownloadingPreviousImage = false
    private var previousImageDownloadThread: Thread? = null
    private var previousImageFilePath: String = ""
    
    // 后台预下载loading图标相关
    private lateinit var backgroundLoadingLayout: LinearLayout
    private var backgroundLoadingVisible = false

    private fun updateTime() {
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val currentTime = dateFormat.format(Date())
        timeTextView.text = currentTime
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        // 从Intent获取参数
        currentImagePath = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: ""
        imageList = intent.getStringArrayListExtra(EXTRA_IMAGE_LIST) ?: ArrayList()
        currentIndex = intent.getIntExtra(EXTRA_CURRENT_INDEX, 0)
        serverIp = intent.getStringExtra(EXTRA_SERVER_IP) ?: ""

        // 检查参数有效性
        if (imageList.isNotEmpty()) {
            // 图片列表模式
            if (currentIndex < 0 || currentIndex >= imageList.size) {
                showToast("索引错误")
                finish()
                return
            }
            currentImagePath = imageList[currentIndex]
        } else if (currentImagePath.isEmpty() || serverIp.isEmpty()) {
            // 单张图片模式
            showToast("参数错误")
            finish()
            return
        }

        // 初始化UI组件
        initializeViews()

        // 加载并显示图片
        loadImage(currentImagePath)

        // 更新导航信息
        updateNavigationInfo()

        instance = this

        // 设置时间显示
        setupTimeDisplay()

    }

    private fun initializeViews() {
        imageView = findViewById(R.id.imageView)
        navigationTextView = findViewById(R.id.navigationTextView)
        timeTextView = findViewById(R.id.timeTextView)
        
        // 初始化后台loading图标
        backgroundLoadingLayout = findViewById(R.id.backgroundLoadingLayout)
        
        // 设置焦点相关
        imageView.requestFocus()
        
        // 保存原始的缩放类型
        originalScaleType = imageView.scaleType
    }

    private fun setupTimeDisplay() {
        timeTextView = TextView(this)
        timeTextView.textSize = 16f
        timeTextView.setTextColor(android.graphics.Color.GRAY)
        timeTextView.setPadding(5, 5, 5, 5)
        val layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        layoutParams.gravity = android.view.Gravity.TOP or android.view.Gravity.END

        val rootView = window.decorView as android.widget.FrameLayout
        rootView.addView(timeTextView, layoutParams)

        updateTime()
        handler.postDelayed(updateTimeRunnable, 1000)
    }

    private fun loadImage(path: String) {
        try {
            Log.d(TAG, "开始加载图片: path=$path")

            if (path.isEmpty()) {
                showToast("图片路径不能为空")
                return
            }

            currentImagePath = path

            // 判断文件类型并选择相应的下载/显示方式
            when {
                path.startsWith("smb://") -> {
                    // SMB网络文件，使用SMB方式下载
                    downloadSmbImage()
                }
                path.contains(":/") || path.contains(":\\") -> {
                    // 盘符路径（如C:/或C:\），使用普通downloadImage下载
                    downloadImage()
                }
                else -> {
                    // 以/开头的路径为本地文件直接显示
                    showLocalImage(path)
                }
            }
            
            // 启动后台预下载下一张图片
            preDownloadNextImage()
        } catch (e: Exception) {
            Log.e(TAG, "加载图片时出错", e)
            showToast("加载图片时出错: ${e.message}")
            finish()
        }
    }

    private fun loadLocalImage(path: String): Bitmap? {
        return try {
            val file = File(path)
            if (file.exists()) {
                // 使用BitmapFactory加载图片，并适当缩小尺寸以避免内存溢出
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(path, options)
                
                // 计算合适的缩放比例
                val scale = calculateInSampleSize(options, imageView.width, imageView.height)
                
                options.inJustDecodeBounds = false
                options.inSampleSize = scale
                options.inPreferredConfig = Bitmap.Config.ARGB_8888
                
                BitmapFactory.decodeFile(path, options)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载本地图片失败", e)
            null
        }
    }

    private fun showLocalImage(path: String) {
        runOnUiThread {
            try {
                // 释放之前的bitmap
                originalBitmap?.recycle()
                originalBitmap = null

                // 重新初始化ImageView以确保显示新图片
                imageView.setImageDrawable(null)
                
                // 加载本地图片
                val bitmap = loadLocalImage(path)

                if (bitmap != null) {
                    originalBitmap = bitmap
                    imageView.setImageBitmap(bitmap)
                    
                    // 设置图片按长宽比自动缩放适配屏幕
                    imageView.scaleType = ScaleType.CENTER_INSIDE
                    imageView.adjustViewBounds = true
                    
                    // 重置缩放状态
                    isZoomed = false
                    imageView.imageMatrix = Matrix()
                    
                    // 重置旋转角度
                    currentRotation = 0f
                    
                    Log.d(TAG, "本地图片显示成功，尺寸: ${bitmap.width}x${bitmap.height}")
                } else {
                    showToast("本地图片显示失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "显示本地图片时出错", e)
                showToast("显示本地图片时出错: ${e.message}")
            }
        }
    }

    private fun downloadSmbImage() {
        if (currentImagePath.isEmpty()) {
            showToast("当前没有图片可下载")
            return
        }

        // 显示loading对话框
        showLoadingDialog("正在下载SMB图片...")

        Thread {
            try {
                // 使用固定的文件名但保持原图片扩展名
                val fileExtension = getFileExtension(currentImagePath)
                val fileName = "downloaded_image$fileExtension"
                val downloadsDir = getDownloadsDir()

                // 目标文件路径
                val targetFilePath = File(downloadsDir, fileName).absolutePath

                // 调用SMB下载方法
                downloadSmbFile(currentImagePath, targetFilePath)

                runOnUiThread {
                    // 隐藏loading对话框
                    hideLoadingDialog()
                    showToast("SMB图片下载成功: $targetFilePath")
                    // 下载成功后自动显示本地图片，并更新当前图片路径
                    currentImagePath = targetFilePath
                    showLocalImage(targetFilePath)
                }
            } catch (e: Exception) {
                Log.e(TAG, "下载SMB图片时出错", e)
                runOnUiThread {
                    // 隐藏loading对话框
                    hideLoadingDialog()
                    showToast("下载SMB图片时出错: ${e.message}")
                }
            }
        }.start()
    }

    private fun downloadSmbFile(smbUrl: String, localFilePath: String) {
        try {
            // 初始化CIFS上下文
            val properties = java.util.Properties()
            val smbUri = java.net.URI.create(smbUrl)
            val cifsContext = SmbConnectionManager.getContext(serverIp)

            // 创建SmbFile对象
            val smbFile = SmbFile(smbUrl, cifsContext)

            // 检查文件是否存在
            if (smbFile.exists()) {
                // 创建本地文件
                val localFile = File(localFilePath)
                localFile.parentFile?.mkdirs() // 创建父目录
                localFile.createNewFile()

                // 下载文件
                SmbFileInputStream(smbFile).use { inputStream ->
                    FileOutputStream(localFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Log.d(TAG, "SMB文件下载成功：$localFilePath")
            } else {
                Log.e(TAG, "远程文件不存在：$smbUrl")
                runOnUiThread {
                    showToast("远程文件不存在")
                }
            }

            smbFile.close()
        } catch (e: IOException) {
            Log.e(TAG, "SMB文件下载失败", e)
            runOnUiThread {
                showToast("SMB文件下载失败: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SMB文件下载时发生未知错误", e)
            runOnUiThread {
                showToast("SMB文件下载失败: ${e.message}")
            }
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (width, height) = options.run { outWidth to outHeight }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    private fun zoomImage() {
        // 放大图片
        if (originalBitmap != null) {
            // 保存当前状态
            if (originalMatrix == null) {
                originalMatrix = Matrix()
                imageView.imageMatrix?.let { matrix ->
                    originalMatrix?.set(matrix)
                }
            }
            
            // 重新加载高分辨率图片，避免放大时像素丢失
            val originalPath = getOriginalImagePath()
            if (originalPath != null) {
                try {
                    // 根据当前缩放大小决定是否进行缩放加载
                    val options = BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    BitmapFactory.decodeFile(originalPath, options)
                    
                    // 计算合适的缩放比例
                    val targetWidth = imageView.width * 2 // 放大2倍的目标宽度
                    val targetHeight = imageView.height * 2 // 放大2倍的目标高度
                    val scale = calculateInSampleSize(options, targetWidth, targetHeight)
                    
                    options.inJustDecodeBounds = false
                    options.inSampleSize = scale
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888 // 使用高质量配置
                    
                    val highResBitmap = BitmapFactory.decodeFile(originalPath, options)
                    if (highResBitmap != null) {
                        // 释放当前bitmap
                        originalBitmap?.recycle()
                        originalBitmap = highResBitmap
                        
                        // 应用当前旋转角度到高分辨率图片
                        val rotatedBitmap = if (currentRotation != 0f) {
                            createRotatedBitmap(highResBitmap, currentRotation)
                        } else {
                            highResBitmap
                        }
                        
                        imageView.setImageBitmap(rotatedBitmap)
                        
                        // 设置缩放类型为矩阵模式
                        imageView.scaleType = ScaleType.MATRIX
                        
                        // 计算当前显示的中心位置
                        val currentMatrix = Matrix()
                        imageView.imageMatrix?.let { currentMatrix.set(it) }
                        
                        // 获取ImageView的中心点（相对于屏幕）
                        val centerX = imageView.width / 2f
                        val centerY = imageView.height / 2f
                        
                        // 创建缩放矩阵，以当前中心位置为缩放中心
                        val matrix = Matrix(currentMatrix)
                        val zoomScale = 2.0f // 放大2倍
                        matrix.postScale(zoomScale, zoomScale, centerX, centerY)
                        
                        imageView.imageMatrix = matrix
                        isZoomed = true
                        
                        Log.d(TAG, "已加载高分辨率图片并应用矩阵缩放，缩放比例: $scale, 尺寸: ${highResBitmap.width}x${highResBitmap.height}, 旋转角度: ${currentRotation}度")
                    }
                } catch (e: OutOfMemoryError) {
                    // 处理内存溢出异常，使用当前图片进行缩放
                    Log.e(TAG, "内存不足，无法加载高分辨率图片，使用当前图片进行缩放", e)
                    
                    // 应用当前旋转角度到当前图片
                    val rotatedBitmap = if (currentRotation != 0f && originalBitmap != null) {
                        createRotatedBitmap(originalBitmap!!, currentRotation)
                    } else {
                        originalBitmap
                    }
                    
                    if (rotatedBitmap != null) {
                        imageView.setImageBitmap(rotatedBitmap)
                    }
                    
                    // 异常情况下使用矩阵缩放
                    imageView.scaleType = ScaleType.MATRIX
                    
                    // 计算当前显示的中心位置
                    val currentMatrix = Matrix()
                    imageView.imageMatrix?.let { currentMatrix.set(it) }
                    
                    // 获取ImageView的中心点（相对于屏幕）
                    val centerX = imageView.width / 2f
                    val centerY = imageView.height / 2f
                    
                    // 创建缩放矩阵，以当前中心位置为缩放中心
                    val matrix = Matrix(currentMatrix)
                    val zoomScale = 2.0f // 放大2倍
                    matrix.postScale(zoomScale, zoomScale, centerX, centerY)
                    
                    imageView.imageMatrix = matrix
                    isZoomed = true
                    
                    Log.d(TAG, "使用当前图片进行矩阵缩放，旋转角度: ${currentRotation}度")
                } catch (e: Exception) {
                    Log.e(TAG, "加载高分辨率图片失败，使用当前图片", e)
                    
                    // 应用当前旋转角度到当前图片
                    val rotatedBitmap = if (currentRotation != 0f && originalBitmap != null) {
                        createRotatedBitmap(originalBitmap!!, currentRotation)
                    } else {
                        originalBitmap
                    }
                    
                    if (rotatedBitmap != null) {
                        imageView.setImageBitmap(rotatedBitmap)
                    }
                    
                    // 异常情况下使用矩阵缩放
                    imageView.scaleType = ScaleType.MATRIX
                    
                    // 计算当前显示的中心位置
                    val currentMatrix = Matrix()
                    imageView.imageMatrix?.let { currentMatrix.set(it) }
                    
                    // 获取ImageView的中心点（相对于屏幕）
                    val centerX = imageView.width / 2f
                    val centerY = imageView.height / 2f
                    
                    // 创建缩放矩阵，以当前中心位置为缩放中心
                    val matrix = Matrix(currentMatrix)
                    val zoomScale = 2.0f // 放大2倍
                    matrix.postScale(zoomScale, zoomScale, centerX, centerY)
                    
                    imageView.imageMatrix = matrix
                    isZoomed = true
                    
                    Log.d(TAG, "加载失败，使用当前图片进行矩阵缩放，旋转角度: ${currentRotation}度")
                }
            } else {
                // 如果没有原始路径，使用矩阵缩放当前图片
                
                // 应用当前旋转角度到当前图片
                val rotatedBitmap = if (currentRotation != 0f && originalBitmap != null) {
                    createRotatedBitmap(originalBitmap!!, currentRotation)
                } else {
                    originalBitmap
                }
                
                if (rotatedBitmap != null) {
                    imageView.setImageBitmap(rotatedBitmap)
                }
                
                imageView.scaleType = ScaleType.MATRIX
                
                // 计算当前显示的中心位置
                val currentMatrix = Matrix()
                imageView.imageMatrix?.let { currentMatrix.set(it) }
                
                // 获取ImageView的中心点（相对于屏幕）
                val centerX = imageView.width / 2f
                val centerY = imageView.height / 2f
                
                // 创建缩放矩阵，以当前中心位置为缩放中心
                val matrix = Matrix(currentMatrix)
                val zoomScale = 2.0f // 放大2倍
                matrix.postScale(zoomScale, zoomScale, centerX, centerY)
                
                imageView.imageMatrix = matrix
                isZoomed = true
                
                Log.d(TAG, "没有原始路径，使用当前图片进行矩阵缩放，旋转角度: ${currentRotation}度")
            }
        }
    }

    private fun restoreImage() {
        if (isZoomed) {
            // 还原到原始状态
            originalScaleType?.let { imageView.scaleType = it }
            imageView.adjustViewBounds = true
            
            // 重置矩阵变换
            imageView.imageMatrix = Matrix()
            isZoomed = false
            
            Log.d(TAG, "图片已还原")
        }
    }

    private fun moveImage(dx: Float, dy: Float) {
        if (isZoomed && originalBitmap != null) {
            // 获取当前矩阵
            val matrix = Matrix()
            imageView.imageMatrix?.let { matrix.set(it) }
            
            // 应用平移变换
            matrix.postTranslate(dx, dy)
            
            // 设置新的矩阵
            imageView.imageMatrix = matrix
            
            Log.d(TAG, "图片移动: dx=$dx, dy=$dy")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // 取消键 - 还原图片或退出确认
                if (isZoomed) {
                    // 放大状态下 - 还原图片
                    restoreImage()
                    return true
                } else {
                    // 普通状态下 - 显示退出确认对话框
                    showExitConfirmationDialog()
                    return true
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // 确定键 - 放大图片
                zoomImage()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> {
                // 取消键 - 还原图片或退出确认
                if (isZoomed) {
                    // 放大状态下 - 还原图片
                    restoreImage()
                    return true
                } else {
                    // 普通状态下 - 显示退出确认对话框
                    showExitConfirmationDialog()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isZoomed) {
                    // 放大状态下 - 向右移动图片
                    moveImage(100f, 0f)

                    return true
                } else {
                    // 普通状态下 - 上一张图片
                    if (imageList.isNotEmpty()) {
                        showPreviousImage()
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isZoomed) {
                    // 放大状态下 - 向左移动图片
                    moveImage(-100f, 0f)
                    return true
                } else {
                    // 普通状态下 - 下一张图片
                    if (imageList.isNotEmpty()) {
                        showNextImage()
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isZoomed) {
                    // 放大状态下 - 向下移动图片
                    moveImage(0f, 100f)
                    return true
                } else {
                    // 普通状态下 - 逆时针旋转90度
                    rotateImage(-90f)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isZoomed) {
                    // 放大状态下 - 向上移动图片
                    moveImage(0f, -100f)
                    return true
                } else {
                    // 普通状态下 - 顺时针旋转90度
                    rotateImage(90f)
                    return true
                }
            }
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                // Y键或播放/暂停键 - 下载当前图片
                downloadImage()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showExitConfirmationDialog() {
        runOnUiThread {
            val dialogView = layoutInflater.inflate(R.layout.dialog_exit_confirm, null)
            val cancelButton = dialogView.findViewById<TextView>(R.id.cancelButton)
            val confirmButton = dialogView.findViewById<TextView>(R.id.confirmButton)
            
            // 创建自定义对话框
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create()
            
            // 设置对话框背景透明，让自定义背景生效
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            
            // 设置按钮点击事件
            cancelButton.setOnClickListener {
                dialog.dismiss()
            }
            
            confirmButton.setOnClickListener {
                dialog.dismiss()
                finish()
            }
            
            // 设置焦点和按键监听
            cancelButton.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            confirmButton.requestFocus()
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            dialog.dismiss()
                            return@setOnKeyListener true
                        }
                    }
                }
                false
            }
            
            confirmButton.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            cancelButton.requestFocus()
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            dialog.dismiss()
                            finish()
                            return@setOnKeyListener true
                        }
                    }
                }
                false
            }
            
            // 显示对话框并设置初始焦点
            dialog.show()
            cancelButton.requestFocus()
        }
    }

    private fun showLoadingDialog(message: String = "正在下载图片...", subtitle: String = "请稍候") {
        runOnUiThread {
            if (loadingDialog?.isShowing == true) {
                loadingDialog?.dismiss()
            }
            
            val dialogView = layoutInflater.inflate(R.layout.dialog_loading, null)
            val messageTextView = dialogView.findViewById<TextView>(R.id.messageTextView)
            val subtitleTextView = dialogView.findViewById<TextView>(R.id.subtitleTextView)
            
            messageTextView?.text = message
            subtitleTextView?.text = subtitle
            
            loadingDialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create()
            
            // 设置对话框背景透明，让自定义背景生效
            loadingDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
            
            loadingDialog?.show()
        }
    }

    private fun hideLoadingDialog() {
        runOnUiThread {
            loadingDialog?.dismiss()
            loadingDialog = null
        }
    }

    // 后台预下载下一张图片
    private fun preDownloadNextImage() {
        if (imageList.size <= 1 || currentIndex >= imageList.size - 1) {
            // 没有下一张图片可预下载
            return
        }

        val nextImagePath = imageList[currentIndex + 1]
        
        // 检查下一张图片是否需要下载（网络图片才需要下载）
        val needDownload = when {
            nextImagePath.startsWith("smb://") -> true
            nextImagePath.contains(":/") || nextImagePath.contains(":\\") -> true
            else -> false
        }

        if (!needDownload) {
            // 本地图片不需要预下载
            return
        }

        // 检查是否已经在下载
        if (isDownloadingNextImage) {
            Log.d(TAG, "下一张图片已经在下载中")
            return
        }

        // 停止之前的下载线程
        nextImageDownloadThread?.interrupt()
        
        // 开始后台预下载
        isDownloadingNextImage = true
        showBackgroundLoading()
        nextImageDownloadThread = Thread {
            try {
                Log.d(TAG, "开始后台预下载下一张图片: $nextImagePath")
                
                // 使用固定的文件名但保持原图片扩展名
                val fileExtension = getFileExtension(nextImagePath)
                val fileName = "downloaded_image_next$fileExtension"
                val downloadsDir = getDownloadsDir()

                // 目标文件路径
                nextImageFilePath = File(downloadsDir, fileName).absolutePath

                // 根据路径类型选择下载方式
                when {
                    nextImagePath.startsWith("smb://") -> {
                        downloadSmbFile(nextImagePath, nextImageFilePath)
                    }
                    else -> {
                        downloadFile(nextImagePath, nextImageFilePath)
                    }
                }

                Log.d(TAG, "后台预下载完成: $nextImageFilePath")
                
            } catch (e: Exception) {
                Log.e(TAG, "后台预下载出错", e)
            } finally {
                isDownloadingNextImage = false
                hideBackgroundLoading()
            }
        }
        
        nextImageDownloadThread?.start()
    }

    private fun preDownloadPreviousImage() {
        if (currentIndex <= 0) {
            // 没有前一张图片可预下载
            return
        }

        val previousImagePath = imageList[currentIndex - 1]
        
        // 检查前一张图片是否需要下载（网络图片才需要下载）
        val needDownload = when {
            previousImagePath.startsWith("smb://") -> true
            previousImagePath.contains(":/") || previousImagePath.contains(":\\\\") -> true
            else -> false
        }

        if (!needDownload) {
            // 本地图片不需要预下载
            return
        }

        // 检查是否已经在下载
        if (isDownloadingPreviousImage) {
            Log.d(TAG, "前一张图片已经在下载中")
            return
        }

        // 停止之前的下载线程
        previousImageDownloadThread?.interrupt()
        
        // 开始后台预下载
        isDownloadingPreviousImage = true
        showBackgroundLoading()
        previousImageDownloadThread = Thread {
            try {
                Log.d(TAG, "开始后台预下载前一张图片: $previousImagePath")
                
                // 使用固定的文件名但保持原图片扩展名
                val fileExtension = getFileExtension(previousImagePath)
                val fileName = "downloaded_image_prev$fileExtension"
                val downloadsDir = getDownloadsDir()

                // 目标文件路径
                previousImageFilePath = File(downloadsDir, fileName).absolutePath

                // 根据路径类型选择下载方式
                when {
                    previousImagePath.startsWith("smb://") -> {
                        downloadSmbFile(previousImagePath, previousImageFilePath)
                    }
                    else -> {
                        downloadFile(previousImagePath, previousImageFilePath)
                    }
                }

                Log.d(TAG, "后台预下载前一张图片完成: $previousImageFilePath")
                
            } catch (e: Exception) {
                Log.e(TAG, "后台预下载前一张图片出错", e)
            } finally {
                isDownloadingPreviousImage = false
                hideBackgroundLoading()
            }
        }
        
        previousImageDownloadThread?.start()
    }

    // 后台下载文件（不显示loading）
    private fun downloadFile(remotePath: String, localPath: String): JSONObject? {
        val command = JSONObject().apply {
            put("action", "download")
            put("path", remotePath)
        }

        val commandStr = command.toString()
        Log.d(TAG, "发送后台下载命令: $commandStr")

        return TcpControlClient.sendTlv(commandStr, localPath)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showBackgroundLoading() {
        runOnUiThread {
            if (!backgroundLoadingVisible) {
                backgroundLoadingLayout.visibility = View.VISIBLE
                backgroundLoadingVisible = true
            }
        }
    }

    private fun hideBackgroundLoading() {
        runOnUiThread {
            if (backgroundLoadingVisible) {
                backgroundLoadingLayout.visibility = View.GONE
                backgroundLoadingVisible = false
            }
        }
    }

    private fun rotateImage(degrees: Float) {
        if (originalBitmap == null) {
            return
        }
        
        // 更新旋转角度
        currentRotation += degrees
        if (currentRotation >= 360f) {
            currentRotation -= 360f
        } else if (currentRotation < 0f) {
            currentRotation += 360f
        }
        
        if (isZoomed) {
            // 放大状态下应用旋转到当前显示的图片
            applyRotationToZoomedImage()
        } else {
            // 普通状态下直接旋转原始图片
            val rotatedBitmap = createRotatedBitmap(originalBitmap!!, currentRotation)
            runOnUiThread {
                imageView.setImageBitmap(rotatedBitmap)
                Log.d(TAG, "图片已旋转: ${currentRotation}度")
            }
        }
    }

    private fun createRotatedBitmap(bitmap: Bitmap, rotation: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotation, bitmap.width / 2f, bitmap.height / 2f)
        
        return Bitmap.createBitmap(
            bitmap, 
            0, 0, 
            bitmap.width, 
            bitmap.height, 
            matrix, 
            true
        )
    }

    private fun applyRotationToZoomedImage() {
        if (originalBitmap == null) return
        
        // 创建旋转后的原始图片
        val rotatedBitmap = createRotatedBitmap(originalBitmap!!, currentRotation)
        
        runOnUiThread {
            // 设置旋转后的图片
            imageView.setImageBitmap(rotatedBitmap)
            
            // 如果当前是放大状态，保持放大效果
            if (isZoomed) {
                // 重新应用缩放矩阵
                val matrix = Matrix()
                imageView.imageMatrix?.let { matrix.set(it) }
                imageView.imageMatrix = matrix
            }
            
            Log.d(TAG, "放大状态下图片已旋转: ${currentRotation}度")
        }
    }

    private fun resetRotation() {
        currentRotation = 0f
        if (originalBitmap != null) {
            runOnUiThread {
                imageView.setImageBitmap(originalBitmap)
                Log.d(TAG, "图片旋转已重置")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 停止时间更新
        handler.removeCallbacks(updateTimeRunnable)
    }

    override fun onResume() {
        super.onResume()
        // 恢复时间更新
        handler.postDelayed(updateTimeRunnable, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // 释放bitmap资源
        originalBitmap?.recycle()
        originalBitmap = null
        
        // 取消所有定时器
        handler.removeCallbacks(updateTimeRunnable)
        
        instance = null

        TcpControlClient.breakRequest()

    }

    // 导航相关方法
    private fun showNextImage() {
        if (currentIndex < imageList.size - 1) {
            // 检查是否正在下载下一张图片
            if (isDownloadingNextImage) {
                showToast("下一张图片正在下载中，请稍候...")
                return
            }
            
            // 检查预下载文件是否存在
            val nextImagePath = imageList[currentIndex + 1]
            val fileExtension = getFileExtension(nextImagePath)
            val downloadsDir = getDownloadsDir()
            val nextImageFile = File(downloadsDir, "downloaded_image_next$fileExtension")
            val currentImageFile = File(downloadsDir, "downloaded_image$fileExtension")
            val previousImageFile = File(downloadsDir, "downloaded_image_prev$fileExtension")
            
            if (nextImageFile.exists()) {
                // 预下载文件存在，移动到当前文件并直接显示
                try {
                    // 将当前显示的文件移动到downloaded_image_prev
                    if (currentImageFile.exists()) {
                        if (previousImageFile.exists()) {
                            previousImageFile.delete()
                        }
                        currentImageFile.renameTo(previousImageFile)
                    }
                    
                    // 将预下载的下一张图片移动到当前文件
                    nextImageFile.renameTo(currentImageFile)
                    Log.d(TAG, "使用预下载图片: ${currentImageFile.absolutePath}")
                    
                    // 直接显示本地图片，不调用loadImage
                    currentIndex++
                    currentImagePath = currentImageFile.absolutePath
                    showLocalImage(currentImagePath)
                    updateNavigationInfo()
                    
                    // 预加载下一张图片
                    preDownloadNextImage()
                    
                } catch (e: Exception) {
                    Log.e(TAG, "移动预下载文件失败", e)
                    // 如果移动失败，回退到正常下载方式
                    currentIndex++
                    loadImage(imageList[currentIndex])
                    updateNavigationInfo()
                }
            } else {
                // 预下载文件不存在，将当前文件移动到downloaded_image_prev，然后下载下一张图片
                try {
                    // 将当前显示的文件移动到downloaded_image_prev
                    if (currentImageFile.exists()) {
                        if (previousImageFile.exists()) {
                            previousImageFile.delete()
                        }
                        currentImageFile.renameTo(previousImageFile)
                    }
                    
                    currentIndex++
                    loadImage(imageList[currentIndex])
                    updateNavigationInfo()
                    
                } catch (e: Exception) {
                    Log.e(TAG, "移动当前文件失败", e)
                    // 如果移动失败，直接下载下一张图片
                    currentIndex++
                    loadImage(imageList[currentIndex])
                    updateNavigationInfo()
                }
            }
        } else {
            showToast("已经是最后一张图片")
        }
    }

    private fun showPreviousImage() {
        if (currentIndex > 0) {
            // 检查是否正在下载前一张图片
            if (isDownloadingPreviousImage) {
                showToast("前一张图片正在下载中，请稍候...")
                return
            }
            
            // 检查是否有预下载的前一张图片
            val fileExtension = getFileExtension(currentImagePath)
            val downloadsDir = getDownloadsDir()
            val previousImageFile = File(downloadsDir, "downloaded_image_prev$fileExtension")
            
            if (previousImageFile.exists()) {
                // 预下载文件存在，移动到当前文件并直接显示
                try {
                    val currentImageFile = File(downloadsDir, "downloaded_image$fileExtension")
                    val nextImageFile = File(downloadsDir, "downloaded_image_next$fileExtension")
                    
                    // 将当前显示的文件移动到downloaded_image_next
                    if (currentImageFile.exists()) {
                        if (nextImageFile.exists()) {
                            nextImageFile.delete()
                        }
                        currentImageFile.renameTo(nextImageFile)
                    }
                    
                    // 将预下载的前一张图片移动到当前文件
                    previousImageFile.renameTo(currentImageFile)
                    
                    currentIndex--
                    currentImagePath = currentImageFile.absolutePath
                    showLocalImage(currentImagePath)
                    updateNavigationInfo()
                    
                    // 预下载前一张图片
                    preDownloadPreviousImage()
                    
                } catch (e: Exception) {
                    Log.e(TAG, "移动预下载文件失败", e)
                    // 如果移动失败，回退到正常下载方式
                    currentIndex--
                    loadImage(imageList[currentIndex])
                    updateNavigationInfo()
                }
            } else {
                // 预下载文件不存在，将当前文件移动到downloaded_image_next，然后下载前一张图片
                try {
                    val currentImageFile = File(downloadsDir, "downloaded_image$fileExtension")
                    val nextImageFile = File(downloadsDir, "downloaded_image_next$fileExtension")
                    
                    if (currentImageFile.exists()) {
                        // 将当前显示的文件移动到downloaded_image_next
                        if (nextImageFile.exists()) {
                            nextImageFile.delete()
                        }
                        currentImageFile.renameTo(nextImageFile)
                    }
                    
                    currentIndex--
                    loadImage(imageList[currentIndex])
                    updateNavigationInfo()
                    
                } catch (e: Exception) {
                    Log.e(TAG, "移动当前文件失败", e)
                    // 如果移动失败，直接下载前一张图片
                    currentIndex--
                    loadImage(imageList[currentIndex])
                    updateNavigationInfo()
                }
            }
        } else {
            showToast("已经是第一张图片")
        }
    }

    private fun updateNavigationInfo() {
        runOnUiThread {
            if (imageList.isNotEmpty()) {
                navigationTextView.visibility = View.VISIBLE
                navigationTextView.text = "${currentIndex + 1}/${imageList.size}"
                
                // 显示导航信息3秒后自动隐藏
                handler.removeCallbacks(hideNavigationRunnable)
                handler.postDelayed(hideNavigationRunnable, 3000)
            } else {
                navigationTextView.visibility = View.GONE
            }
        }
    }

    private val hideNavigationRunnable = Runnable {
        runOnUiThread {
            navigationTextView.visibility = View.GONE
        }
    }

    // 图片下载相关方法
    private fun downloadImage() {
        if (currentImagePath.isEmpty()) {
            showToast("当前没有图片可下载")
            return
        }

        // 显示loading对话框
        showLoadingDialog("正在下载图片...")

        Thread {
            try {
                val command = JSONObject().apply {
                    put("action", "download")
                    put("path", currentImagePath)
                }

                val commandStr = command.toString()
                Log.d(TAG, "发送下载命令: $commandStr")

                // 使用固定的文件名但保持原图片扩展名
                val fileExtension = getFileExtension(currentImagePath).lowercase()
                val fileName = "downloaded_image$fileExtension"
                val downloadsDir = getDownloadsDir()

                // 目标文件路径
                val targetFilePath = File(downloadsDir, fileName).absolutePath
                val response = TcpControlClient.sendTlv(commandStr, targetFilePath)

                runOnUiThread {
                    // 隐藏loading对话框
                    hideLoadingDialog()
                    
                    if (response == null) {
                        showToast("发送下载命令失败")
                        return@runOnUiThread
                    }
                    
                    try {
                        val jsonResponse = response
                        if (jsonResponse.getString("status") == "success") {
                            showToast("图片自动下载成功")
                            // 下载成功后自动显示本地图片，并更新当前图片路径
                            currentImagePath = targetFilePath
                            showLocalImage(targetFilePath)
                        } else {
                            val errorMessage = jsonResponse.optString("message", "未知错误")
                            showToast("下载失败: $errorMessage")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析响应出错", e)
                        showToast("解析响应出错")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送下载命令出错", e)
                runOnUiThread {
                    // 隐藏loading对话框
                    hideLoadingDialog()
                    showToast("发送下载命令出错")
                }
            }
        }.start()
    }

    private fun getDownloadsDir(): File {
        val externalStorageDirectory = Environment.getExternalStorageDirectory()
        val downloadsDir = File(externalStorageDirectory, Environment.DIRECTORY_DOWNLOADS)

        // 如果Downloads目录不存在，则创建它
        if (!downloadsDir.exists()) {
            if (!downloadsDir.mkdirs()) {
                throw IOException("Failed to create downloads directory")
            }
        }

        return downloadsDir
    }

    private fun getFileExtension(path: String): String {
        val fileName = path.split(Regex("[/\\\\]")).last()
        val dotIndex = fileName.lastIndexOf('.')
        return if (dotIndex > 0 && dotIndex < fileName.length - 1) {
            fileName.substring(dotIndex).lowercase()
        } else {
            ".jpg" // 默认使用.jpg扩展名
        }
    }

    private fun getOriginalImagePath(): String? {
        // 如果当前图片路径是本地文件，直接返回
        if (currentImagePath.startsWith("/") || currentImagePath.contains(":")) {
            // 检查是否是下载的图片文件
            val downloadsDir = getDownloadsDir()
            val downloadedFile = File(downloadsDir, "downloaded_image${getFileExtension(currentImagePath)}")
            
            if (downloadedFile.exists()) {
                // 如果是下载的图片，返回下载文件的路径
                return downloadedFile.absolutePath
            } else {
                // 如果是本地原始文件，返回原始路径
                return currentImagePath
            }
        }
        
        // 对于网络图片，检查是否已经下载到本地
        val downloadsDir = getDownloadsDir()
        val downloadedFile = File(downloadsDir, "downloaded_image${getFileExtension(currentImagePath)}")
        
        if (downloadedFile.exists()) {
            // 如果已经下载，返回下载文件的路径
            return downloadedFile.absolutePath
        }
        
        // 如果网络图片还没有下载，返回null（使用当前显示的图片）
        return null
    }

}