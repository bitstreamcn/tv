package com.zlang.tv

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView


class FileListAdapter(
    private val items: List<FileItem>,
    private val onItemClick: (FileItem) -> Unit
) : RecyclerView.Adapter<FileListAdapter.ViewHolder>() {

    private var selectedPosition = RecyclerView.NO_POSITION

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconView: ImageView = view.findViewById(R.id.iconView)
        val nameView: TextView = view.findViewById(R.id.nameView)
        val sizeView: TextView = view.findViewById(R.id.sizeView)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.nameView.text = item.name
        holder.iconView.setImageResource(
            if (item.type == "directory") R.drawable.ic_folder
            else R.drawable.ic_file
        )
        if (item.size > 1024 * 1024 * 1024)
        {
            holder.sizeView.text = (item.size / (1024 * 1024 * 1024)).toString() + "G"
        }
        else if (item.size > 1024 * 1024)
        {
            holder.sizeView.text = (item.size / (1024 * 1024)).toString() + "M"
        }
        else if (item.size > 1024)
        {
            holder.sizeView.text = (item.size / 1024).toString() + "K"
        }
        else if (item.size > 0)
        {
            holder.sizeView.text = (item.size).toString() + "B"
        }

        holder.itemView.apply {
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    selectedPosition = position
                }
                v.setBackgroundResource(
                    if (hasFocus) R.drawable.item_background_focused
                    else R.drawable.item_background_normal
                )
            }
            
            setOnKeyListener { v, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            onItemClick(item)
                            true
                        }
                        else -> false
                    }
                } else false
            }
            
            setOnClickListener {
                onItemClick(item)
            }
        }
    }

    fun getSelectedPosition(): Int {
        return selectedPosition
    }
    
    override fun getItemCount() = items.size
} 