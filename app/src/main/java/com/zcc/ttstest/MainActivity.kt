package com.zcc.ttstest

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * 使用系统自带的 TextToSpeech 引擎做离线语音合成。
 * 不依赖第三方库，也不需要网络或任何权限。
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

    /** 引擎初始化完成且支持当前语言后为 true */
    private var ready = false

    private lateinit var etInput: EditText
    private lateinit var sbSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnSpeak: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etInput = findViewById(R.id.etInput)
        sbSpeed = findViewById(R.id.sbSpeed)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvStatus = findViewById(R.id.tvStatus)
        btnSpeak = findViewById(R.id.btnSpeak)
        btnStop = findViewById(R.id.btnStop)

        // 引擎就绪之前先禁用按钮
        btnSpeak.isEnabled = false
        btnStop.isEnabled = false

        sbSpeed.max = 100
        sbSpeed.progress = DEFAULT_SPEED_PROGRESS
        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = rateOf(progress)
                tvSpeed.text = getString(R.string.speed_value, rate)
                tts?.setSpeechRate(rate)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        tvSpeed.text = getString(R.string.speed_value, rateOf(DEFAULT_SPEED_PROGRESS))

        btnSpeak.setOnClickListener { speak() }
        btnStop.setOnClickListener { tts?.stop() }

        // 构造 TextToSpeech 是异步的，结果通过 onInit() 回调
        tts = TextToSpeech(this, this)
    }

    /** 引擎初始化完成的回调 */
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            tvStatus.text = getString(R.string.status_init_failed)
            return
        }

        val result = tts?.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            ready = false
            tvStatus.text = getString(R.string.status_lang_unsupported)
            return
        }

        tts?.apply {
            setSpeechRate(rateOf(sbSpeed.progress))
            setPitch(1.0f)
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread { tvStatus.text = getString(R.string.status_speaking) }
                }

                override fun onDone(utteranceId: String?) {
                    runOnUiThread { tvStatus.text = getString(R.string.status_ready) }
                }

                // 这是该监听器里唯一被声明为 abstract 的 onError 重载，必须实现
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    runOnUiThread { tvStatus.text = getString(R.string.status_error) }
                }
            })
        }

        ready = true
        tvStatus.text = getString(R.string.status_ready)
        btnSpeak.isEnabled = true
        btnStop.isEnabled = true
    }

    private fun speak() {
        if (!ready) return

        val text = etInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.toast_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val utteranceId = "utterance-${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun onDestroy() {
        // 必须释放引擎，否则会一直占着系统服务连接
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private companion object {
        const val DEFAULT_SPEED_PROGRESS = 50

        /** 进度条 0~100 映射为语速 0.1~2.0（50 对应正常语速 1.0） */
        fun rateOf(progress: Int): Float = (progress / 50f).coerceAtLeast(0.1f)
    }
}
