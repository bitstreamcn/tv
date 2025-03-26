package com.zlang.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.io.File

class VideoRecordAdapter(
    private var records: List<VideoRecord>,
    private val onItemClick: (VideoRecord) -> Unit
) : RecyclerView.Adapter<VideoRecordAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.thumbnail)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val progressText: TextView = view.findViewById(R.id.progressText)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val fileName: TextView = view.findViewById(R.id.fileName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_record, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        
        // 使用Glide加载缩略图
        if (record.thumbnailPath != null) {
            val thumbnailFile = File(record.thumbnailPath!!)
            if (thumbnailFile.exists()) {
                Glide.with(holder.thumbnail)
                    .load(thumbnailFile)
                    .centerCrop()
                    .into(holder.thumbnail)
            }
        }
        
        // 设置进度条
        val progress = record.getProgress()
        holder.progressBar.progress = progress.toInt()
        holder.progressText.text = String.format("%.1f%%", progress)
        
        // 设置时间文本
        holder.timeText.text = String.format("%s / %s",
            formatTime(record.position),
            formatTime(record.duration)
        )
        
        // 设置文件名
        holder.fileName.text = record.name
        
        // 设置点击事件
        holder.itemView.setOnClickListener {
            onItemClick(record)
        }
        
        // 设置焦点变化效果
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.1f else 1.0f)
                .scaleY(if (hasFocus) 1.1f else 1.0f)
                .setDuration(200)
                .start()
        }
    }

    override fun getItemCount() = records.size
    
    /**
     * 更新适配器的记录列表
     */
    fun setVideoRecords(newRecords: List<VideoRecord>) {
        this.records = newRecords
    }
    
    /**
     * 根据视频路径查找在适配器中的位置
     * @return 如果找到返回位置索引，否则返回-1
     */
    fun findPositionByPath(path: String): Int {
        return records.indexOfFirst { it.path == path }
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
} 