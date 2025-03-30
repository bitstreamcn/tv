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
import androidx.compose.ui.text.toLowerCase
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.zlang.tv.MainActivity.Companion.unfinishedRecords
import com.zlang.tv.ShareListActivity.Companion
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.context.SingletonContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import org.bouncycastle.util.Properties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ShareFileListActivity : ComponentActivity() {
    companion object {
        private const val TAG = "FileListActivity"
        private const val EXTRA_SERVER_IP = "server_ip"
        private const val EXTRA_SMB_SERVER_IP = "smb_server_ip"
        private const val EXTRA_INITIAL_PATH = "initial_path"
        private const val REQUEST_CODE_VIDEO_PLAYER = 1001
        
        fun createIntent(context: Context, serverIp: String, smbServerIp: String, initialPath: String = ""): Intent {
            return Intent(context, ShareFileListActivity::class.java).apply {
                putExtra(EXTRA_SERVER_IP, serverIp)
                putExtra(EXTRA_SMB_SERVER_IP, smbServerIp)
                putExtra(EXTRA_INITIAL_PATH, initialPath)
            }
        }
    }
    
    private lateinit var fileListView: RecyclerView
    private lateinit var currentPathText: TextView
    private lateinit var loadingIndicator: ProgressBar
    
    private var serverIp: String = ""
    private var currentPath: String = ""
    private var fileListAdapter: FileListAdapter? = null

    private var smbServerIp: String = ""
    private var smbServer : JSONObject? = null
    private var shareName : String = ""

    private val pathMap = HashMap<String, Int>() // 保存路径和对应的位置

    fun fixSambaUrl(originalUrl: String): String {
        if (originalUrl.isBlank()) {
            throw IllegalArgumentException("URL 不能为空")
        }

        val url = StringBuilder()
        var remaining = originalUrl

        // 1. 处理协议头
        remaining = when {
            remaining.startsWith("smb://", ignoreCase = true) -> {
                url.append("smb://")
                remaining.substring(6)
            }
            remaining.startsWith("smb:/", ignoreCase = true) -> {
                url.append("smb://")
                remaining.substring(5)
            }
            else -> {
                url.append("smb://")
                remaining
            }
        }

        // 2. 处理认证信息 (user:pass@)
        val atIndex = remaining.indexOf('@')
        if (atIndex != -1) {
            val authPart = remaining.substring(0, atIndex)
            remaining = remaining.substring(atIndex + 1)

            val colonIndex = authPart.indexOf(':')
            if (colonIndex != -1) {
                url.append(authPart.substring(0, colonIndex + 1)) // user:
                url.append(authPart.substring(colonIndex + 1))    // pass
            } else {
                url.append(authPart)
            }
            url.append('@')
        }

        // 3. 处理主机和路径
        val firstSlash = remaining.indexOf('/')
        val host = if (firstSlash != -1) remaining.substring(0, firstSlash) else remaining
        val path = if (firstSlash != -1) remaining.substring(firstSlash) else ""

        url.append(host)

        // 4. 标准化路径斜杠
        if (path.isNotEmpty()) {
            var cleanPath = path
            // 移除开头多余斜杠
            while (cleanPath.startsWith('/')) {
                cleanPath = cleanPath.substring(1)
            }
            // 移除结尾多余斜杠
            while (cleanPath.endsWith('/')) {
                cleanPath = cleanPath.dropLast(1)
            }
            if (cleanPath.isNotEmpty()) {
                url.append('/').append(cleanPath).append('/')
            }
        }

        // 5. 统一转小写协议头
        val result = url.toString()
        return if (result.startsWith("smb://")) {
            result
        } else {
            "smb://" + result.removePrefix("smb://")
        }
    }

    //url格式："smb://user:password@192.168.1.100/share/"
    fun listSambaDirectory(smbUrl: String): Array<SmbFile>? {
        val url = fixSambaUrl(smbUrl)
        Log.d(TAG, "smbUrl:" + url)
        try {
            val smbFile = SmbFile(url, SmbConnectionManager.getContext(smbServerIp))
            if (smbFile.exists() && smbFile.isDirectory) {
                return smbFile.listFiles()
            }
        } catch (e: SmbException) {
            Log.e("SambaError", "Samba 操作错误: ${e.message}", e)
        } catch (e: Exception) {
            Log.e("SambaError", "发生未知错误: ${e.message}", e)
        }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_list)
        
        // 获取传入的参数
        serverIp = intent.getStringExtra(EXTRA_SERVER_IP) ?: ""
        smbServerIp = intent.getStringExtra(ShareFileListActivity.EXTRA_SMB_SERVER_IP) ?: ""
        shareName = intent.getStringExtra(EXTRA_INITIAL_PATH) ?: ""

        if (serverIp.isEmpty()) {
            showToast("服务器IP不能为空")
            finish()
            return
        }

        smbServer = MainActivity.smbServer?.let { serverArray ->
            for (i in 0 until serverArray.length()) {
                val server = serverArray.getJSONObject(i)
                if (server.getString("ip") == smbServerIp) {
                    return@let server
                }
            }
            return@let null
        }
        currentPath = fixSambaUrl("smb://${smbServer?.getString("user") ?: ""}:${smbServer?.getString("password")}@${smbServerIp}/${shareName}/")

        val sharedPreferences = getSharedPreferences("path_records", MODE_PRIVATE)
        val pathRecordsJson = sharedPreferences.getString("pos_records", null)
        // 加载未完成记录
        if (pathRecordsJson != null) {
            try {
                val jsonArray = JSONArray(pathRecordsJson)
                for (i in 0 until jsonArray.length()) {
                    val recordObj = jsonArray.getJSONObject(i)
                    val path = recordObj.getString("path")
                    val pos = recordObj.getInt("pos")
                    pathMap[path] = pos
                }
            } catch (e: Exception) {
                Log.e("FileListActivity", "加载记录出错", e)
            }
        }

        // 初始化视图
        initViews()
        
        // 加载文件列表
        loadFileList()

    }

    override fun onPause() {
        super.onPause()

        val sharedPreferences = getSharedPreferences("path_records", MODE_PRIVATE)
        val fileJsonArray = JSONArray()
        // 保存已完成记录
        pathMap.forEach { record ->
            val recordObj = JSONObject().apply {
                put("path", record.key)
                put("pos", record.value)
            }
            fileJsonArray.put(recordObj)
        }
        sharedPreferences.edit()
            .putString("pos_records", fileJsonArray.toString())
            .apply()
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

    fun isSmbRootUrl(url: String): Boolean {
        // 1. 基本格式验证
        if (!url.startsWith("smb://", ignoreCase = true)) {
            throw IllegalArgumentException("非 SMB 协议地址")
        }

        // 2. 提取路径部分
        val pathStart = url.indexOf('/', 6) // 跳过 "smb://" 的 6 个字符
        if (pathStart == -1) return true // 示例：smb://host

        // 3. 分割共享名称和后续路径
        val fullPath = url.substring(pathStart + 1) // 示例：电影/子目录/ 或 电影/
        val firstSlash = fullPath.indexOf('/')

        // 4. 判断路径深度
        return when {
            // 没有共享名称的情况（理论上不应该存在）
            fullPath.isEmpty() -> true

            // 只有共享名称没有子目录（可能带或不带结尾斜杠）
            firstSlash == -1 -> true

            // 标准化处理路径结构
            else -> {
                val normalizedPath = fullPath
                    .replace("/+".toRegex(), "/") // 合并连续斜杠
                    .removeSuffix("/")            // 移除结尾斜杠

                // 路径拆分后只剩共享名称（示例：电影）
                normalizedPath.indexOf('/') == -1
            }
        }
    }

    private fun loadFileList() {
        showLoading(true)
        
        Thread {
            try {
                val url = if (currentPath=="") {
                    "smb://${smbServer?.getString("user") ?: ""}:${smbServer?.getString("password")}@${smbServerIp}/${shareName}/${currentPath}"
                }else{
                    currentPath
                }
                val list = listSambaDirectory(url)

                runOnUiThread {
                    showLoading(false)
                    

                    try {

                            val fileItems = ArrayList<FileItem>()
                            
                            // 首先添加上级目录（除了在根目录时）
                            if (currentPath != "") {
                                fileItems.add(FileItem("...", "上级目录", "directory"))
                            }

                            val size = list?.size?:0
                            // 添加目录
                            for (i in 0 until size) {
                                val fileObj = list?.get(i)
                                val name = fileObj?.name
                                val path = fileObj?.path
                                val type = if(fileObj?.isDirectory?:false)
                                    "directory" else "file"
                                
                                fileItems.add(FileItem(name?:"", type, path?:""))
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
        if (path.isEmpty())
        {
            return ""
        }
        return fixSambaUrl(path)
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


    fun downloadSmbFile(smbUrl: String, localFilePath: String) {
        // 初始化CIFS上下文
        val properties = java.util.Properties()
        val smbUri = java.net.URI.create(smbUrl)
        val cifsContext = SmbConnectionManager.getContext(smbServerIp)

        try {
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
                println("文件下载成功：$localFilePath")
            } else {
                println("远程文件不存在：$smbUrl")
            }

            smbFile.close()
        } catch (e: IOException) {
            e.printStackTrace()
            println("文件下载失败：$e")
        }
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
        pathMap[pathkey] = newSelectedPosition ?: 0

        if (item.name == "...") {
            if (isSmbRootUrl(currentPath)){
                return
            }
            // 处理上级目录
            val parent = File(currentPath.replace("\\", "/")).parent ?: ""
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
                    //SMB URL（格式：smb://username:password@host/share/path/file.mp4）
                    val path = item.path
                    //打开播放器
                    val intent = SmbVideoPlayerActivity.createIntent(this, path,
                        0, serverIp)
                    startActivity(intent)

                }
                else if (item.path.lowercase().endsWith(".apk"))
                {
                    Thread {
                        try {
                            val fileName = getFileNameFromPath(item.path)
                            // 目标目录
                            val downloadsDir = getDownloadsDir()

                            // 目标文件路径
                            val targetFilePath = File(downloadsDir, fileName).absolutePath + "1" //坚果系统只能安装.apk1类型的文件

                            downloadSmbFile(item.path, targetFilePath)

                            runOnUiThread {
                                showLoading(false)
                                try {
                                    try {
                                        installApk(targetFilePath)
                                    }catch(e : Exception) {
                                        val apkUri =
                                            Uri.fromFile(File(targetFilePath)) // 注意：在Android 10及以上，你可能需要使用FileProvider来获取content URI

                                        val installIntent = Intent(Intent.ACTION_VIEW)
                                        installIntent.data = apkUri
                                        installIntent.type =
                                            "application/vnd.android.package-archive"
                                        installIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

                                        if (installIntent.resolveActivity(packageManager) != null) {
                                            startActivity(installIntent)
                                        } else {
                                            // 显示错误消息或处理无法安装的情况
                                            showToast("安装失败: $targetFilePath")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "解析响应出错", e)
                                    showToast("解析响应出错")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "发送安装命令出错", e)
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
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_VIDEO_PLAYER) {
            // 从播放器返回，刷新文件列表
            loadFileList()
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (currentPath != "" && !isSmbRootUrl(currentPath)) {
                // 如果不是在根目录，返回上一级目录
                val parent = File(currentPath.replace("\\", "/")).parent ?: ""
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
