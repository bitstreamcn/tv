package com.zlang.tv

import android.app.Activity
import android.app.Dialog
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
import com.bumptech.glide.Glide
import com.zlang.tv.MainActivity.Companion
import com.zlang.tv.MainActivity.Companion.unfinishedRecords
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RecordListActivity : ComponentActivity() {
    companion object {
        private const val TAG = "RecordListActivity"
        private const val EXTRA_SERVER_IP = "server_ip"
        private const val EXTRA_IS_COMPLETED = "is_completed"
        private const val REQUEST_CODE_VIDEO_PLAYER = 1001
        
        fun createIntent(context: Context, serverIp: String, isCompleted: Boolean): Intent {
            return Intent(context, RecordListActivity::class.java).apply {
                putExtra(EXTRA_SERVER_IP, serverIp)
                putExtra(EXTRA_IS_COMPLETED, isCompleted)
            }
        }
    }
    
    private lateinit var titleText: TextView
    private lateinit var recordsRecyclerView: RecyclerView
    private lateinit var loadingIndicator: ProgressBar
    
    private var serverIp: String = ""
    private var isCompleted: Boolean = false
    private var recordsList = mutableListOf<VideoRecord>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record_list)
        
        // 获取传入的参数
        serverIp = intent.getStringExtra(EXTRA_SERVER_IP) ?: ""
        isCompleted = intent.getBooleanExtra(EXTRA_IS_COMPLETED, false)
        
        if (serverIp.isEmpty()) {
            showToast("服务器IP不能为空")
            finish()
            return
        }

        Glide.get(this).clearMemory()
        Thread {
            Glide.get(this).clearDiskCache()
        }.start()
        
        // 初始化视图
        initViews()
        
        // 加载播放记录
        loadRecords()
    }
    
    private fun initViews() {
        titleText = findViewById(R.id.titleText)
        recordsRecyclerView = findViewById(R.id.recordsRecyclerView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        
        // 设置标题
        titleText.text = if (isCompleted) "已看完的视频" else "未看完的视频"
        
        // 设置RecyclerView
        recordsRecyclerView.layoutManager = LinearLayoutManager(this)
    }
    
    private fun loadRecords() {
        showLoading(true)
        
        Thread {
            try {
                // 根据类型加载不同的记录
                val records = if (isCompleted) {
                    MainActivity.finishedRecords
                } else {
                    MainActivity.unfinishedRecords
                }
                runOnUiThread {
                    showLoading(false)
                    recordsList = records
                    updateRecordsList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载记录出错", e)
                runOnUiThread {
                    showLoading(false)
                    showToast("加载记录出错")
                }
            }
        }.start()
    }
    
    private fun updateRecordsList() {
        if (recordsList.isEmpty()) {
            showToast("没有${if (isCompleted) "已完成" else "未完成"}的播放记录")
            return
        }

        val displayRecords = recordsList
            .sortedByDescending { it.lastPlayTime }
        
        val adapter = RecordAdapter(displayRecords) { record ->
            playVideo(record)
        }
        recordsRecyclerView.adapter = adapter
        
        // 设置焦点到第一项
        recordsRecyclerView.post {
            recordsRecyclerView.requestFocus()
            if (recordsList.isNotEmpty()) {
                recordsRecyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    private fun playVideo(path: String, startPosition: Long = 0, ffmpeg:Boolean)
    {
        if (path.startsWith("smb://")) {
            // 启动VideoPlayerActivity
            val intent = SmbVideoPlayerActivity.createIntent(this, path, startPosition, serverIp)
            startActivityForResult(intent, REQUEST_CODE_VIDEO_PLAYER)
        }else{
            val intent = VideoPlayerActivity.createIntent(this, path, startPosition, serverIp, ffmpeg)
            startActivityForResult(intent, REQUEST_CODE_VIDEO_PLAYER)
        }
    }

    private fun playVideo(path: String, startPosition: Long = 0) {
        playVideo(path, startPosition, false)
    }
    
    private fun playVideo(record: VideoRecord) {
        val path = record.path
        val startPosition = if (isCompleted) 0 else record.position

        if (path.startsWith("smb://")) {
            playVideo(path, startPosition, false)
            return
        }

        playVideo(path, startPosition)
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



                val jsonResponse = response
                if (jsonResponse.getString("status") == "success") {
                    showToast("转码任务已提交")
                    // 延迟2秒后刷新列表
                    Thread.sleep(2000)
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
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_VIDEO_PLAYER) {
            // 从播放器返回，重新加载记录
            loadRecords()
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 处理返回按钮
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 直接返回主界面
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    
    private fun showLoading(show: Boolean) {
        loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        recordsRecyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }
    
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

// 记录适配器
class RecordAdapter(
    private val records: List<VideoRecord>,
    private val onItemClick: (VideoRecord) -> Unit
) : RecyclerView.Adapter<RecordAdapter.ViewHolder>() {
    
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileName: TextView = view.findViewById(R.id.fileName)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val progressText: TextView = view.findViewById(R.id.progressText)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val thumbnail: ImageView = view.findViewById(R.id.thumbnail)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video_record_list, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        
        // 设置视频名称
        holder.fileName.text = record.path
        val duration = if(record.duration == 0L) {
            1L
        }else{
            record.duration
        }
        
        // 设置进度条
        val progress = (record.position * 100 / duration).toInt()
        holder.progressBar.progress = progress
        holder.progressText.text = "${progress}%"
        
        // 设置时长
        holder.timeText.text = "播放进度： " + formatDuration(record.position) + " / " +  formatDuration(record.duration)
        
        // 设置缩略图
        if (record.thumbnailPath != null) {
            val thumbnailFile = File(record.thumbnailPath!!)
            if (thumbnailFile.exists()) {
                Glide.with(holder.thumbnail)
                    .load(thumbnailFile)
                    .into(holder.thumbnail)
            }
        }
        
        // 设置点击事件
        holder.itemView.setOnClickListener {
            onItemClick(record)
        }
        
        // 设置焦点变化监听
        holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                holder.itemView.setBackgroundResource(R.drawable.item_focused)
            } else {
                holder.itemView.setBackgroundResource(R.drawable.item_background)
            }
        }
    }
    
    override fun getItemCount() = records.size
    
    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
} 