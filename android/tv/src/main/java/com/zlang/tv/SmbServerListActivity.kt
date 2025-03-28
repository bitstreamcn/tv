package com.zlang.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zlang.tv.MainActivity.Companion.finishedRecords
import com.zlang.tv.MainActivity.Companion.unfinishedRecords
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SmbServerListActivity : ComponentActivity() {
    companion object {
        private const val TAG = "FileListActivity"
        private const val EXTRA_SERVER_IP = "server_ip"
        private const val EXTRA_INITIAL_PATH = "initial_path"
        private const val REQUEST_CODE_VIDEO_PLAYER = 1001
        
        fun createIntent(context: Context, serverIp: String, initialPath: String = "drives"): Intent {
            return Intent(context, SmbServerListActivity::class.java).apply {
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


        // 初始化视图
        initViews()
        
        // 加载文件列表
        loadFileList()

    }

    override fun onPause() {
        super.onPause()

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

                runOnUiThread {
                    showLoading(false)
                    

                    try {
                            val filesArray = MainActivity.smbServer
                            val fileItems = ArrayList<FileItem>()
                            if (null != filesArray) {
                                // 添加目录
                                for (i in 0 until filesArray.length()) {
                                    val fileObj = filesArray.getJSONObject(i)
                                    val name = fileObj.getString("name")
                                    val path = fileObj.getString("ip")
                                    val type = "directory"

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
    
    private fun handleFileItemClick(item: FileItem) {

        val record : JSONObject? = MainActivity.smbServer?.let { serverArray ->
            for (i in 0 until serverArray.length()) {
                val server = serverArray.getJSONObject(i)
                if (server.getString("ip") == item.path) {
                    return@let server
                }
            }
            return@let null
        }

        if (record != null) {
            val intent = ShareListActivity.createIntent(this, serverIp, record.getString("ip"))
            startActivity(intent)
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
                    playVideo(videoPath, 0)
                } else {
                    // 从上次播放位置继续播放
                    playVideo(videoPath, record.position)
                }
            } else {
                // 没有播放记录，从头开始播放
                playVideo(videoPath)
            }
        }
        
        encodeOption.setOnClickListener {
            dialog.dismiss()
            sendEncodeCommand(videoPath)
        }
        
        dialog.show()
    }
    
    private fun playVideo(path: String, startPosition: Long = 0) {
        // 启动VideoPlayerActivity播放视频
        val intent = VideoPlayerActivity.createIntent(this, path, startPosition, serverIp)
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
