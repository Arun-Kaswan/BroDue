package com.abk.brodue

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load

class PersonAdapter(
    private val onItemClick: (Person) -> Unit,
    private val onItemLongClick: ((Person) -> Boolean)? = null
) : ListAdapter<Person, PersonAdapter.PersonViewHolder>(DiffCallback) {

    // Last rolled values per person (home row rolls like the net card)
    private val lastAmounts = mutableMapOf<String, Long>()

    // Net-only skeleton: rows render instantly (avatar + name) while just
    // the amounts shimmer. Single shared pulse animator for all of them.
    var amountsLoading: Boolean = false
        set(value) {
            field = value
            if (!value) stopPulse()
        }
    private val pulseViews = mutableSetOf<android.widget.TextView>()
    private var pulseAnim: android.animation.ValueAnimator? = null

    private fun startPulse() {
        if (pulseAnim != null) return
        pulseAnim = android.animation.ValueAnimator.ofFloat(0.35f, 1f).apply {
            duration = 900L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { anim ->
                val alpha = anim.animatedValue as Float
                pulseViews.toList().forEach { it.alpha = alpha }
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnim?.cancel()
        pulseAnim = null
        pulseViews.toList().forEach { it.alpha = 1f }
        pulseViews.clear()
    }

    override fun onViewRecycled(holder: PersonViewHolder) {
        super.onViewRecycled(holder)
        holder.detachPulse(pulseViews)
    }

    override fun onCurrentListChanged(
        previousList: MutableList<Person>,
        currentList: MutableList<Person>
    ) {
        super.onCurrentListChanged(previousList, currentList)
        lastAmounts.keys.retainAll(currentList.map { it.id }.toSet())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PersonViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_person, parent, false)
        return PersonViewHolder(view)
    }

    override fun onBindViewHolder(holder: PersonViewHolder, position: Int) {
        holder.bind(getItem(position))
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onItemClick(getItem(pos))
        }
        holder.itemView.setOnLongClickListener { v ->
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onItemLongClick?.invoke(getItem(pos)) ?: false
            } else false
        }
    }

    inner class PersonViewHolder(private val view: View) : RecyclerView.ViewHolder(view) {

        private val tvName: TextView = view.findViewById(R.id.tvName)
        private val ivSyncCloud: android.widget.ImageView = view.findViewById(R.id.ivSyncCloud)
        private val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        private val avatar: TextView = view.findViewById(R.id.avatar)
        private val avatarPhoto: com.google.android.material.imageview.ShapeableImageView =
            view.findViewById(R.id.avatarPhoto)
        private val txDot: View = view.findViewById(R.id.txDot)

        fun detachPulse(pool: MutableSet<android.widget.TextView>) {
            pool.remove(tvAmount)
            tvAmount.alpha = 1f
        }

        fun bind(person: Person) {
            tvName.text = person.name
            // Paused (over-limit) sync shows the red paused-cloud mark
            val locked = try {
                person.isSyncedState &&
                    ShareSync.syncedCount(view.context) > ShareSync.cachedSyncCap(view.context)
            } catch (_: Exception) {
                false
            }
            when {
                locked -> {
                    ivSyncCloud.setImageResource(R.drawable.ic_cloud_paused)
                    ivSyncCloud.imageTintList =
                        androidx.core.content.ContextCompat.getColorStateList(view.context, R.color.save_red)
                    ivSyncCloud.visibility = View.VISIBLE
                }
                person.leftGroup -> {
                    ivSyncCloud.setImageResource(R.drawable.ic_cloud_off)
                    ivSyncCloud.imageTintList =
                        androidx.core.content.ContextCompat.getColorStateList(view.context, R.color.grey_soft)
                    ivSyncCloud.visibility = View.VISIBLE
                }
                person.isSyncedState -> {
                    ivSyncCloud.setImageResource(R.drawable.ic_synced_cloud)
                    ivSyncCloud.imageTintList =
                        androidx.core.content.ContextCompat.getColorStateList(view.context, R.color.grey_soft)
                    ivSyncCloud.visibility = View.VISIBLE
                }
                else -> ivSyncCloud.visibility = View.GONE
            }
            // Net-only skeleton state: synced persons only, local nets
            // are always known instantly
            val isSyncedPerson = try {
                ShareSync.isSynced(view.context, person.id)
            } catch (_: Exception) {
                false
            }
            if (amountsLoading && isSyncedPerson) {
                tvAmount.text = "000000"
                tvAmount.setTextColor(android.graphics.Color.TRANSPARENT)
                tvAmount.background = androidx.core.content.ContextCompat.getDrawable(
                    view.context, R.drawable.bg_skeleton
                )
                pulseViews.add(tvAmount)
                startPulse()
            } else {
                pulseViews.remove(tvAmount)
                tvAmount.alpha = 1f
                if (pulseViews.isEmpty()) stopPulse()
                tvAmount.background = null
            }
            if (person.hasUpdate) {
                txDot.visibility = View.VISIBLE
                if (txDot.tag == null) {
                    // Pop-in (plays once per appearance, not on rebinds)
                    txDot.tag = "shown"
                    txDot.alpha = 0f
                    txDot.scaleX = 0.4f
                    txDot.scaleY = 0.4f
                    txDot.animate()
                        .alpha(1f)
                        .scaleX(1.15f)
                        .scaleY(1.15f)
                        .setDuration(180)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.8f))
                        .withEndAction {
                            txDot.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        }
                        .start()
                }
            } else {
                txDot.tag = null
                txDot.animate().cancel()
                txDot.alpha = 1f
                txDot.scaleX = 1f
                txDot.scaleY = 1f
                txDot.visibility = View.GONE
            }
            if (!amountsLoading || !isSyncedPerson) {
                val symbol = CurrencyManager.symbolForPerson(view.context, person.id)
                val color = view.context.getColor(
                    when {
                        person.netAmount > 0 -> R.color.positive
                        person.netAmount < 0 -> R.color.negative
                        else -> R.color.text_secondary
                    }
                )
                val last = lastAmounts[person.id]
                lastAmounts[person.id] = person.netAmount
                val synced = isSyncedPerson
                if (synced && last != null && last != person.netAmount && tvAmount.width > 0) {
                    UiUtils.rollingAmount(
                        tvAmount, last, person.netAmount,
                        { v -> Formatters.amountWithSymbol(v, symbol) },
                        tvAmount.currentTextColor, color
                    )
                } else {
                    // Echo-stomp guard: an identical roll already playing wins
                    if (!(last != null && last == person.netAmount &&
                            UiUtils.isRollingTo(tvAmount, person.netAmount))
                    ) {
                        UiUtils.cancelRoll(tvAmount)
                        tvAmount.text = Formatters.amountWithSymbol(person.netAmount, symbol)
                        tvAmount.setTextColor(color)
                    }
                }
            }
            if (person.photoUrl.isNotBlank()) {
                avatarPhoto.visibility = View.VISIBLE
                avatar.visibility = View.INVISIBLE
                // Same URL: skip reload or the avatar blinks on every rebind
                if (avatarPhoto.tag != person.photoUrl) {
                    avatarPhoto.tag = person.photoUrl
                    avatarPhoto.load(person.photoUrl) { crossfade(true) }
                }
            } else {
                avatarPhoto.tag = null
                avatarPhoto.setImageDrawable(null)
                avatarPhoto.visibility = View.GONE
                avatar.visibility = View.VISIBLE
                avatar.text = person.name.trim().take(1).uppercase()
                AvatarColors.style(view.context, avatar, person.name)
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<Person>() {
            override fun areItemsTheSame(oldItem: Person, newItem: Person) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Person, newItem: Person) = oldItem == newItem
        }
    }
}