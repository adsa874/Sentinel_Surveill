package com.sentinel.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sentinel.R

class ActivityFeedAdapter : ListAdapter<ActivityEvent, ActivityFeedAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_activity_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivEventIcon)
        private val tvTime: TextView = itemView.findViewById(R.id.tvEventTime)
        private val tvDescription: TextView = itemView.findViewById(R.id.tvEventDescription)
        private val tvConfidence: TextView = itemView.findViewById(R.id.tvEventConfidence)
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivEventThumbnail)

        fun bind(event: ActivityEvent) {
            ivIcon.setImageResource(event.getIconResId())
            tvTime.text = event.getFormattedTime()
            tvDescription.text = event.getDisplayText()

            if (event.confidence > 0) {
                tvConfidence.visibility = View.VISIBLE
                tvConfidence.text = "${(event.confidence * 100).toInt()}%"
            } else {
                tvConfidence.visibility = View.GONE
            }

            if (event.snapshotPath != null) {
                ivThumbnail.visibility = View.VISIBLE
                try {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(event.snapshotPath)
                    ivThumbnail.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    ivThumbnail.visibility = View.GONE
                }
            } else {
                ivThumbnail.visibility = View.GONE
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<ActivityEvent>() {
        override fun areItemsTheSame(oldItem: ActivityEvent, newItem: ActivityEvent): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ActivityEvent, newItem: ActivityEvent): Boolean {
            return oldItem == newItem
        }
    }
}
