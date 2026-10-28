package com.zcc.ttstest

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 全局共享的 TTS 引擎。
 *
 * 之前每个页面各自 new 一个 TextToSpeech，导致不同页面的朗读互相独立：
 * 一处还在播放时，另一处用 QUEUE_FLUSH 也打断不了它。这里收敛成单例，
 * 所有页面共用同一个引擎，speak() 一律 QUEUE_FLUSH——立刻停止当前、播放最新。
 */
object TtsManager : TextToSpeech.OnInitListener {

    enum class State { INITIALIZING, READY, UNAVAILABLE }

    private var tts: TextToSpeech? = null
    private var rate = 1.0f
    private var progressListener: UtteranceProgressListener? = null

    var state = State.INITIALIZING
        private set

    var errorRes = R.string.status_init_failed
        private set

    /** 状态监听器，key 用于各自注册/注销 */
    private val listeners = mutableMapOf<String, (State) -> Unit>()

    /** 幂等初始化，可被任意页面调用 */
    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext, this)
        progressListener?.let { tts?.setOnUtteranceProgressListener(it) }
    }

    fun registerListener(key: String, listener: (State) -> Unit) {
        listeners[key] = listener
        // 已就绪则立刻回传当前状态
        listener(state)
    }

    fun unregisterListener(key: String) {
        listeners.remove(key)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            state = State.UNAVAILABLE
            errorRes = R.string.status_init_failed
            notifyListeners()
            return
        }

        val result = tts?.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            state = State.UNAVAILABLE
            errorRes = R.string.status_lang_unsupported
            notifyListeners()
            return
        }

        tts?.setSpeechRate(rate)
        tts?.setPitch(1.0f)
        state = State.READY
        notifyListeners()
    }

    private fun notifyListeners() {
        listeners.values.toList().forEach { it(state) }
    }

    /** 立刻停止当前朗读，播放新文字 */
    fun speak(text: String, utteranceId: String = "tts-${System.currentTimeMillis()}"): Int {
        return tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            ?: TextToSpeech.ERROR
    }

    fun stop() {
        tts?.stop()
    }

    fun setSpeechRate(r: Float) {
        rate = r
        tts?.setSpeechRate(r)
    }

    /** 朗读进度监听（目前只有首页朗读用它来更新状态文案） */
    fun setProgressListener(listener: UtteranceProgressListener?) {
        progressListener = listener
        tts?.setOnUtteranceProgressListener(listener)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        state = State.INITIALIZING
    }
}
