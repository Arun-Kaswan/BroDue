package com.abk.brodue

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class ArchivedPersonAdapter(
    private val onItemClick: (Person) -> Unit,
    private val onUnarchiveClick: (Person) -> Unit,
    private val onDeleteClick: (Person) -> Unit
) : ListAdapter<Person, ArchivedPersonAdapter.VH>(DiffCallback) {

    private var greyed = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_archived_person, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val person = getItem(position)
        holder.name.text = person.name
        holder.mobile.text = person.mobile.ifBlank { holder.itemView.context.getString(R.string.no_mobile) }
        holder.avatar.text = person.name.trim().take(1).uppercase()
        AvatarColors.style(holder.itemView.context, holder.avatar, person.name)
        holder.itemView.setOnClickListener { onItemClick(person) }
        holder.unarchive.setOnClickListener { onUnarchiveClick(person) }
        holder.delete.setOnClickListener { onDeleteClick(person) }
        holder.applyGrey(greyed)
    }

    fun setGreyed(greyed: Boolean) {
        if (this.greyed == greyed) return
        this.greyed = greyed
        notifyItemRangeChanged(0, itemCount)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.apName)
        val mobile: TextView = view.findViewById(R.id.apMobile)
        val avatar: TextView = view.findViewById(R.id.apAvatar)
        val unarchive: MaterialButton = view.findViewById(R.id.btnUnarchive)
        val delete: MaterialButton = view.findViewById(R.id.btnDeletePerson)

        fun applyGrey(greyed: Boolean) {
            unarchive.iconTint = ColorStateList.valueOf(
                itemView.context.getColor(if (greyed) R.color.grey_soft else R.color.navy_text)
            )
            delete.iconTint = ColorStateList.valueOf(
                itemView.context.getColor(if (greyed) R.color.grey_soft else R.color.negative)
            )
            // Stay clickable while greyed so taps can trigger a connection retry
            unarchive.isEnabled = true
            delete.isEnabled = true
            val alpha = if (greyed) 0.55f else 1f
            unarchive.alpha = alpha
            delete.alpha = alpha
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<Person>() {
            override fun areItemsTheSame(oldItem: Person, newItem: Person) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Person, newItem: Person) = oldItem == newItem
        }
    }
}