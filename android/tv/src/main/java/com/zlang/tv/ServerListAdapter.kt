package com.zlang.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ServerListAdapter(
    private val serverList: List<String>,
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<ServerListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val serverIpText: TextView = view.findViewById(R.id.serverIpText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_server, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val serverIp = serverList[position]
        holder.serverIpText.text = serverIp

        holder.itemView.setOnClickListener {
            onItemClick(serverIp)
        }

        // 处理焦点变化
        holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            holder.itemView.isSelected = hasFocus
        }
    }

    override fun getItemCount() = serverList.size
} 