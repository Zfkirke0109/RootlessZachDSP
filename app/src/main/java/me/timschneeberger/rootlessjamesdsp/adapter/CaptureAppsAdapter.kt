package me.timschneeberger.rootlessjamesdsp.adapter

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.audio.capture.CaptureApplicationChoice

data class CaptureAppListItem(
    val choice: CaptureApplicationChoice,
    val icon: Drawable?,
    val isSelected: Boolean,
)

class CaptureAppsAdapter(
    private val onSelectionChanged: (packageName: String) -> Unit,
) : ListAdapter<CaptureAppListItem, CaptureAppsAdapter.ViewHolder>(Comparator) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_capture_app, parent, false),
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(android.R.id.icon)
        private val title: TextView = itemView.findViewById(android.R.id.title)
        private val summary: TextView = itemView.findViewById(android.R.id.summary)
        private val checkBox: MaterialCheckBox = itemView.findViewById(R.id.capture_app_checkbox)
        private var item: CaptureAppListItem? = null

        init {
            itemView.setOnClickListener { checkBox.toggle() }
        }

        fun bind(item: CaptureAppListItem) {
            this.item = item
            title.text = item.choice.label
            summary.text = when {
                !item.choice.isInstalledAndVisible -> itemView.context.getString(
                    R.string.capture_app_missing_summary,
                    item.choice.packageName,
                )
                !item.choice.isEnabled -> itemView.context.getString(
                    R.string.capture_app_disabled_summary,
                    item.choice.packageName,
                    item.choice.uid,
                )
                item.choice.isSystem -> itemView.context.getString(
                    R.string.capture_app_system_summary,
                    item.choice.packageName,
                    item.choice.uid,
                )
                else -> itemView.context.getString(
                    R.string.capture_app_summary,
                    item.choice.packageName,
                    item.choice.uid,
                )
            }
            if (item.icon != null) {
                icon.setImageDrawable(item.icon)
            } else {
                icon.setImageResource(R.drawable.ic_twotone_headphones_24dp)
            }

            checkBox.setOnCheckedChangeListener(null)
            checkBox.isChecked = item.isSelected
            checkBox.contentDescription = itemView.context.getString(
                R.string.capture_app_toggle_description,
                item.choice.label,
            )
            checkBox.setOnCheckedChangeListener { _, _ ->
                this.item?.choice?.packageName?.let(onSelectionChanged)
            }
        }
    }

    private object Comparator : DiffUtil.ItemCallback<CaptureAppListItem>() {
        override fun areItemsTheSame(oldItem: CaptureAppListItem, newItem: CaptureAppListItem) =
            oldItem.choice.packageName == newItem.choice.packageName

        override fun areContentsTheSame(oldItem: CaptureAppListItem, newItem: CaptureAppListItem) =
            oldItem.choice == newItem.choice &&
                oldItem.isSelected == newItem.isSelected
    }
}
