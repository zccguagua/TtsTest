package com.zcc.ttstest

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * 悬浮按钮。
 *
 * 存在的唯一目的是把「窗口焦点」的变化暴露出来：View.onWindowFocusChanged 是收到
 * WMS 焦点授予/收回通知的地方，转成回调后，Service 就能在"确实拿到焦点"之后再读剪贴板，
 * 而不是靠 sleep 猜时机。
 *
 * 注意区分两种焦点：
 *  - window focus：整个窗口在 WMS 层面的输入焦点（这里要的，决定能否读剪贴板）
 *  - view focus：窗口内部哪个控件被选中（用 setFocusable，跟这里无关）
 */
class OverlayButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** 参数为 true 表示窗口刚拿到焦点，false 表示刚失去焦点 */
    var onWindowFocusChangedListener: ((Boolean) -> Unit)? = null

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        onWindowFocusChangedListener?.invoke(hasWindowFocus)
    }
}
