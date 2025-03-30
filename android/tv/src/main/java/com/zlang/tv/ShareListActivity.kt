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
import java.io.IOException

import jcifs.CIFSContext
import jcifs.CIFSException
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.net.MalformedURLException
import java.util.Properties


class ShareListActivity : ComponentActivity() {
    companion object {
        private const val TAG = "FileListActivity"
        private const val EXTRA_SERVER_IP = "server_ip"
        private const val EXTRA_SMB_SERVER_IP = "smb_server_ip"
        private const val REQUEST_CODE_VIDEO_PLAYER = 1001
        
        fun createIntent(context: Context, serverIp: String, smbServerIp: String): Intent {
            return Intent(context, ShareListActivity::class.java).apply {
                putExtra(EXTRA_SERVER_IP, serverIp)
                putExtra(EXTRA_SMB_SERVER_IP, smbServerIp)
            }
        }
    }
    
    private lateinit var fileListView: RecyclerView
    private lateinit var currentPathText: TextView
    private lateinit var loadingIndicator: ProgressBar
    
    private var serverIp: String = ""
    private var smbServerIp: String = ""
    private var currentPath: String = "drives"
    private var fileListAdapter: FileListAdapter? = null
    private val pathMap = HashMap<String, Int>() // 保存路径和对应的位置

    private var smbServer : JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_list)
        
        // 获取传入的参数
        serverIp = intent.getStringExtra(EXTRA_SERVER_IP) ?: ""
        smbServerIp = intent.getStringExtra(EXTRA_SMB_SERVER_IP) ?: ""

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

        currentPathText.visibility = View.GONE
        
        // 更新路径显示
        updatePathDisplay()
    }
    
    private fun updatePathDisplay() {
        //currentPathText.text = "当前路径: $currentPath"
    }


    class SmbShareScanner {
        // 获取共享列表
        fun listShares(hostIp: String, username: String?, password: String?) : List<SmbFile>{
            val shareList = mutableListOf<SmbFile>()
            try {
                val cifsContext = SmbConnectionManager.getContext(hostIp)

                // 构建 SMB URL（格式：smb://IP_OR_HOSTNAME/）
                val smbUrl = "smb://$hostIp/"
                val smbFile = SmbFile(smbUrl, cifsContext)

                // 列出所有共享
                val shares = smbFile.listFiles() ?: return shareList
                for (share in shares) {
                    if (share.isDirectory()) {
                        Log.d("SMB Share", "Name: ${share.name}, Path: ${share.path}")
                        shareList.add(share)
                    }
                }
                smbFile.close()
            } catch (e: MalformedURLException) {
                Log.e("SMB Error", "Invalid URL: ${e.message}")
            } catch (e: CIFSException) {
                Log.e("SMB Error", "Connection failed: ${e.message}")
            } catch (e: Exception) {
                Log.e("SMB Error", "General error: ${e.message}")
            }
            return shareList
        }
    }
    
    private fun loadFileList() {
        showLoading(true)
        
        Thread {
            try {
                // 在 Activity 或 ViewModel 中调用
                val scanner = SmbShareScanner()
                // 带用户名密码的访问
                val list = scanner.listShares(smbServerIp, smbServer?.getString("user"), smbServer?.getString("password"))


                runOnUiThread {
                    showLoading(false)
                    
                    try {
                            val fileItems = ArrayList<FileItem>()

                            // 添加目录
                            for (i in 0 until list.size) {
                                val name = list[i].name
                                val path = list[i].path
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
        val intent = ShareFileListActivity.createIntent(this, serverIp, smbServerIp, item.name)
        startActivity(intent)
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
