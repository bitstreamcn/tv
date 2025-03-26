package com.zlang.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Command(
    val name: String,
    val icon: Int,
    val action: () -> Unit
)

class CommandAdapter(
    private val commands: List<Command>
) : RecyclerView.Adapter<CommandAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.commandIcon)
        val name: TextView = view.findViewById(R.id.commandName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_command, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val command = commands[position]
        
        holder.icon.setImageResource(command.icon)
        holder.name.text = command.name
        
        holder.itemView.setOnClickListener {
            command.action()
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

    override fun getItemCount() = commands.size
} 