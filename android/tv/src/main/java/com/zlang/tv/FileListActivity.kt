package com.zlang.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zlang.tv.MainActivity.Companion.finishedRecords
import com.zlang.tv.MainActivity.Companion.unfinishedRecords
import com.zlang.tv.ShareFileListActivity.Companion
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class FileListActivity : ComponentActivity() {
    companion object {
        private const val TAG = "FileListActivity"
        private const val EXTRA_SERVER_IP = "server_ip"
        private const val EXTRA_INITIAL_PATH = "initial_path"
        private const val REQUEST_CODE_VIDEO_PLAYER = 1001
        
        fun createIntent(context: Context, serverIp: String, initialPath: String = "drives"): Intent {
            return Intent(context, FileListActivity::class.java).apply {
                putExtra(EXTRA_SERVER_IP, serverIp)
                putExtra(EXTRA_INITIAL_PATH, initialPath)
            }
        }
    }
    
    private lateinit var fileListView: RecyclerView
    private lateinit var currentPathText: TextView
    private lateinit var loadingIndicator: ProgressBar
    
    private var serverIp: String = ""
    private var currentPath: String = "drives"
    private var fileListAdapter: FileListAdapter? = null
    private val pathMap = HashMap<String, Int>() // 保存路径和对应的位置

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_list)
        
        // 获取传入的参数
        serverIp = intent.getStringExtra(EXTRA_SERVER_IP) ?: ""
        currentPath = path_standardizing(intent.getStringExtra(EXTRA_INITIAL_PATH) ?: "drives")
        
        if (serverIp.isEmpty()) {
            showToast("服务器IP不能为空")
            finish()
            return
        }

        PathRecordsManager.init(this)

        // 初始化视图
        initViews()
        
        // 加载文件列表
        loadFileList()

    }

    override fun onPause() {
        super.onPause()

        PathRecordsManager.save(this)
    }
    
    private fun initViews() {
        fileListView = findViewById(R.id.fileListView)
        currentPathText = findViewById(R.id.currentPathText)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        
        fileListView.layoutManager = LinearLayoutManager(this)
        fileListView.setHasFixedSize(true)
        
        // 更新路径显示
        updatePathDisplay()
    }
    
    private fun updatePathDisplay() {
        currentPathText.text = "当前路径: $currentPath"
    }
    
    private fun loadFileList() {
        showLoading(true)
        
        Thread {
            try {
                val command = JSONObject().apply {
                    put("action", "list")
                    put("path", currentPath)
                }
                
                val commandStr = command.toString()
                Log.d(TAG, "发送数据: $commandStr")
                val response = TcpControlClient.sendTlv(commandStr)
                
                runOnUiThread {
                    showLoading(false)
                    
                    if (response == null) {
                        showToast("获取文件列表失败")
                        return@runOnUiThread
                    }
                    
                    try {
                        val jsonResponse = response
                        if (jsonResponse.getString("status") == "success") {
                            val filesArray = jsonResponse.getJSONArray("items")
                            val fileItems = ArrayList<FileItem>()
                            
                            // 首先添加上级目录（除了在根目录时）
                            if (currentPath != "drives") {
                                fileItems.add(FileItem("...", "上级目录", "directory"))
                            }
                            
                            // 添加目录
                            for (i in 0 until filesArray.length()) {
                                val fileObj = filesArray.getJSONObject(i)
                                val name = fileObj.getString("name")
                                val path = fileObj.getString("path")
                                val type = fileObj.getString("type")
                                
                                fileItems.add(FileItem(name, type, path))
                            }
                            
                            // 更新适配器
                            fileListAdapter = FileListAdapter(fileItems) { item ->
                                handleFileItemClick(item)
                            }
                            fileListView.adapter = fileListAdapter
                            
                            // 恢复焦点
                            fileListView.post {
                                fileListView.requestFocus()
                                if (fileItems.isNotEmpty()) {
                                    fileListView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                                }
                            }

                            if (fileItems.isNotEmpty()) {
                                /*
                                fileListView.post {
                                    fileListView.getChildAt(0)?.requestFocus()
                                }*/
                                // 恢复到之前的位置
                                fileListView.post {
                                    // 恢复焦点到当前项
                                    var pathkey = path_standardizing(currentPath)
                                    var targetPosition = PathRecordsManager.getPos(pathkey)

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

                        } else {
                            val errorMessage = jsonResponse.optString("message", "未知错误")
                            showToast("获取文件列表失败: $errorMessage")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "解析文件列表出错", e)
                        showToast("解析文件列表出错")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载文件列表出错", e)
                runOnUiThread {
                    showLoading(false)
                    showToast("加载文件列表出错")
                }
            }
        }.start()
    }

    private fun path_standardizing(path: String) : String
    {
        if (path == "drives")
        {
            return path
        }
        var pathkey = path.replace("\\", "/")
        if (pathkey.last() != '/') {
            pathkey = "${pathkey}/"
        }
        return pathkey
    }

    fun getFileNameFromPath(path: String): String {
        return path.split(Regex("""[/\\]""")).last() // 使用正则表达式匹配/或\，并获取最后一个部分作为文件名
    }

    fun getDownloadsDir(): File {
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

    private fun installApk(apkFilePath: String) {
        val apkFile = File(apkFilePath)
        if (apkFile.exists()) {
            val intent = Intent(Intent.ACTION_VIEW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0 及以上版本需要使用 FileProvider
                intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                val contentUri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    apkFile
                )
                intent.setDataAndType(contentUri, "application/vnd.android.package-archive")
            } else {
                intent.setDataAndType(
                    Uri.fromFile(apkFile),
                    "application/vnd.android.package-archive"
                )
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }
    
    private fun handleFileItemClick(item: FileItem) {

        val newSelectedPosition = (fileListView.adapter as? FileListAdapter)?.getSelectedPosition()

        val pathkey = path_standardizing(currentPath)

        println("${pathkey}: 选择项改变到 $newSelectedPosition")
        PathRecordsManager.setPos(pathkey, newSelectedPosition ?: 0)


        if (item.name == "...") {
            // 处理上级目录
            val parent = File(currentPath.replace("\\", "/")).parent ?: "drives"
            currentPath = path_standardizing(parent)
            updatePathDisplay()
            loadFileList()
            return
        }
        
        when (item.type) {
            "drive", "directory" -> {
                currentPath = path_standardizing(item.path)
                updatePathDisplay()
                loadFileList()
            }
            "file" -> {
                Log.d("click", item.path)
                if (isVideoFile(item.path)) {
                    if (item.path.endsWith(".ts", true)) {

                        // 检查是否有播放记录
                        val record = unfinishedRecords.find { it.path == item.path }
                        if (record != null) {
                            if (record.isCompleted()) {
                                // 如果已经播放完成，从头开始播放
                                playVideo(item.path, 0, false)
                            } else {
                                // 从上次播放位置继续播放
                                playVideo(item.path, record.position, false)
                            }
                        } else {
                            // 没有播放记录，从头开始播放
                            playVideo(item.path, 0, false)
                        }


                    } else {
                        // 其他视频文件显示选项对话框
                        showVideoOptionsDialog(item.path)
                    }
                }
                else if (item.path.lowercase().endsWith(".apk"))
                {
                    Thread {
                        try {
                            val command = JSONObject().apply {
                                put("action", "download")
                                put("path", item.path)
                            }

                            val commandStr = command.toString()
                            Log.d(FileListActivity.TAG, "发送下载命令: $commandStr")
                            runOnUiThread {
                                showToast("开始下载。。。")
                            }
                            val fileName = getFileNameFromPath(item.path)
                            // 目标目录
                            val downloadsDir = getDownloadsDir()

                            // 目标文件路径
                            val targetFilePath = File(downloadsDir, fileName).absolutePath + "1" //坚果系统只能安装.apk1类型的文件
                            val response = TcpControlClient.sendTlv(commandStr, targetFilePath)

                            runOnUiThread {
                                showLoading(false)

                                if (response == null) {
                                    showToast("发送安装命令失败")
                                    return@runOnUiThread
                                }
                                try {
                                    val jsonResponse = response
                                    if (jsonResponse.getString("status") == "success") {
// 假设你已经下载了一个APK文件到某个位置，并且你知道它的URI
                                        val apkUri = Uri.fromFile(File(targetFilePath)) // 注意：在Android 10及以上，你可能需要使用FileProvider来获取content URI

                                        try {
                                            installApk(targetFilePath)
                                        }catch (e : Exception) {
                                            val installIntent = Intent(Intent.ACTION_VIEW)
                                            installIntent.data = apkUri
                                            installIntent.type =
                                                "application/vnd.android.package-archive"
                                            installIntent.flags =
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION

// 检查是否有处理这个Intent的Activity
                                            if (installIntent.resolveActivity(packageManager) != null) {
                                                startActivity(installIntent)
                                            } else {
                                                // 显示错误消息或处理无法安装的情况
                                                showToast("安装失败: $targetFilePath")
                                            }
                                        }
                                    } else {
                                        val errorMessage = jsonResponse.optString("message", "未知错误")
                                        showToast("安装失败: $errorMessage")
                                    }
                                } catch (e: Exception) {
                                    Log.e(FileListActivity.TAG, "解析响应出错", e)
                                    showToast("解析响应出错")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(FileListActivity.TAG, "发送安装命令出错", e)
                            runOnUiThread {
                                showLoading(false)
                                showToast("发送安装命令出错")
                            }
                        }
                    }.start()
                }
                else {
                    showToast("不支持的文件类型")
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
    
    private fun showVideoOptionsDialog(videoPath: String) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.video_options_dialog)
        
        // 设置对话框窗口参数
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            // 设置对话框位置为屏幕中心
            setGravity(android.view.Gravity.CENTER)
            // 设置动画
            setWindowAnimations(android.R.style.Animation_Dialog)
        }
        
        val openOption = dialog.findViewById<TextView>(R.id.openOption)
        val encodeOption = dialog.findViewById<TextView>(R.id.encodeOption)
        val aacEncodeOption = dialog.findViewById<TextView>(R.id.aacEncodeOption)
        
        // 设置默认焦点
        dialog.setOnShowListener {
            openOption.requestFocus()
        }
        
        openOption.setOnClickListener {
            dialog.dismiss()
            //playVideo(videoPath)
            // 检查是否有播放记录
            val record = unfinishedRecords.find { it.path == videoPath }
            if (record != null) {
                if (record.isCompleted()) {
                    // 如果已经播放完成，从头开始播放
                    playVideo(videoPath, 0, false)
                } else {
                    // 从上次播放位置继续播放
                    playVideo(videoPath, record.position, false)
                }
            } else {
                // 没有播放记录，从头开始播放
                playVideo(videoPath, 0, false)
            }
        }

        encodeOption.setOnClickListener {
            dialog.dismiss()
            sendEncodeCommand(videoPath)
        }

        aacEncodeOption.setOnClickListener{
            dialog.dismiss()
            sendAACEncodeCommand(videoPath)
        }
        
        dialog.show()
    }
    
    private fun playVideo(path: String, startPosition: Long = 0, ffmpeg: Boolean) {
        // 启动VideoPlayerActivity播放视频
        val intent = VideoPlayerActivity.createIntent(this, path, startPosition, serverIp, ffmpeg)
        startActivityForResult(intent, REQUEST_CODE_VIDEO_PLAYER)
    }
    
    private fun sendEncodeCommand(videoPath: String) {
        showLoading(true)
        
        Thread {
            try {
                val command = JSONObject().apply {
                    put("action", "encode")
                    put("path", videoPath)
                }
                
                val commandStr = command.toString()
                Log.d(TAG, "发送编码命令: $commandStr")
                val response = TcpControlClient.sendTlv(commandStr)
                
                runOnUiThread {
                    showLoading(false)
                    
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
                    showLoading(false)
                    showToast("发送编码命令出错")
                }
            }
        }.start()
    }

    private fun sendAACEncodeCommand(videoPath: String) {
        showLoading(true)

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
                    showLoading(false)

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
                    showLoading(false)
                    showToast("发送编码命令出错")
                }
            }
        }.start()
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_VIDEO_PLAYER) {
            // 从播放器返回，刷新文件列表
            loadFileList()
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (currentPath != "drives") {
                // 如果不是在根目录，返回上一级目录
                val parent = File(currentPath.replace("\\", "/")).parent ?: "drives"
                currentPath = path_standardizing(parent)
                updatePathDisplay()
                loadFileList()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    private fun showLoading(show: Boolean) {
        loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        fileListView.visibility = if (show) View.GONE else View.VISIBLE
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
