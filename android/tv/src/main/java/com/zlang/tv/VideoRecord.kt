package com.zlang.tv

import android.graphics.Bitmap
import java.io.Serializable

data class VideoRecord(
    val path: String,                // 视频文件路径
    val name: String,                // 视频文件名
    var duration: Long,              // 视频总时长（毫秒）
    var position: Long,              // 当前播放位置（毫秒）
    var thumbnail: String? = null,   // 缩略图文件路径
    var lastPlayTime: Long = System.currentTimeMillis(), // 最后播放时间
    var isFinished: Boolean = false,  // 是否播放完成
    var thumbnailPath: String? = null,
    var ffmpeg: Boolean = false
) : Serializable {
    fun getProgress(): Float {
        return if (duration > 0) (position.toFloat() / duration.toFloat()) * 100 else 0f
    }
    
    fun isCompleted(): Boolean {
        return getProgress() >= 95f
    }
} 