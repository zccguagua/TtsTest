package com.zcc.ttstest

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

/** 常用语列表适配器：点击朗读（喇叭闪烁动画）、侧滑露出删除按钮、拖拽排序 */
class PhraseAdapter(
    private val phrases: MutableList<String>,
    private val onClick: (String) -> Unit,
    private val onDelete: (Int) -> Unit,
) : RecyclerView.Adapter<PhraseAdapter.PhraseViewHolder>() {

    /** 正在播放的短语文本，用于驱动对应条目的喇叭闪烁动画 */
    var playingText: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    class PhraseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val foreground: View = view.findViewById(R.id.itemForeground)
        val icon: ImageView = view.findViewById(R.id.ivSpeak)
        val tvPhrase: TextView = view.findViewById(R.id.tvPhrase)
        val btnDelete: View = view.findViewById(R.id.btnDelete)

        private var blink: ValueAnimator? = null

        fun setBlinking(on: Boolean) {
            if (on) {
                if (blink == null) {
                    blink = ValueAnimator.ofFloat(1f, 0.2f).apply {
                        duration = 400L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        addUpdateListener { icon.alpha = it.animatedValue as Float }
                    }
                }
                blink?.start()
            } else {
                blink?.cancel()
                blink = null
                icon.alpha = 1f
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhraseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_phrase, parent, false)
        return PhraseViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhraseViewHolder, position: Int) {
        val phrase = phrases[position]
        holder.tvPhrase.text = phrase
        holder.setBlinking(phrase == playingText)
        holder.foreground.setOnClickListener { onClick(phrase) }
        holder.btnDelete.setOnClickListener { onDelete(holder.adapterPosition) }
    }

    override fun getItemCount(): Int = phrases.size

    fun onItemMove(from: Int, to: Int) {
        Collections.swap(phrases, from, to)
        notifyItemMoved(from, to)
    }

    fun onItemRemoved(position: Int) {
        if (position in phrases.indices) {
            phrases.removeAt(position)
            notifyItemRemoved(position)
        }
    }
}

/**
 * 拖拽排序 + 侧滑露出删除按钮。
 * 侧滑不会直接删除，而是把前景平移露出后面的删除按钮，由用户点按钮确认删除。
 */
class SwipeRevealCallback(
    private val adapter: PhraseAdapter,
    private val revealWidth: Int,
    private val onChanged: () -> Unit,
) : ItemTouchHelper.SimpleCallback(
    ItemTouchHelper.UP or ItemTouchHelper.DOWN,
    ItemTouchHelper.LEFT,
) {

    private var openViewHolder: RecyclerView.ViewHolder? = null

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.25f

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean {
        adapter.onItemMove(viewHolder.adapterPosition, target.adapterPosition)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // 关掉之前打开的项
        openViewHolder?.let { old -> if (old !== viewHolder) closeForeground(old) }
        openViewHolder = viewHolder
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            // 只平移前景，把平移量限制在删除按钮宽度内
            val clamped = dX.coerceIn(-revealWidth.toFloat(), 0f)
            viewHolder.itemView.findViewById<View>(R.id.itemForeground).translationX = clamped
        } else {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        // 手势结束：不是打开项就收回去
        if (openViewHolder !== viewHolder) {
            closeForeground(viewHolder)
        }
        // 拖拽/侧滑结束后持久化顺序
        onChanged()
    }

    /** 关闭当前打开的项（供点击播放前调用，避免同时多个条目处于打开状态） */
    fun closeOpen() {
        openViewHolder?.let { closeForeground(it) }
        openViewHolder = null
    }

    private fun closeForeground(viewHolder: RecyclerView.ViewHolder) {
        viewHolder.itemView.findViewById<View>(R.id.itemForeground)
            .animate()
            .translationX(0f)
            .setDuration(150L)
            .start()
    }
}
