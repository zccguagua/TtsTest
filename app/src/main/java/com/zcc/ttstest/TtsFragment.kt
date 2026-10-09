package com.zcc.ttstest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.Locale

/**
 * 朗读：系统 TextToSpeech 离线合成 + 悬浮窗朗读开关。
 */
class TtsFragment : Fragment(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

    /** 引擎初始化完成且支持当前语言后为 true */
    private var ready = false

    private lateinit var etInput: EditText
    private lateinit var sbSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnSpeak: Button
    private lateinit var btnStop: Button
    private lateinit var btnOverlay: Button
    private lateinit var tvOverlayHint: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var notificationPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // registerForActivityResult 必须在 attach 之后、onStart 之前注册
        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_tts, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        etInput = view.findViewById(R.id.etInput)
        sbSpeed = view.findViewById(R.id.sbSpeed)
        tvSpeed = view.findViewById(R.id.tvSpeed)
        tvStatus = view.findViewById(R.id.tvStatus)
        btnSpeak = view.findViewById(R.id.btnSpeak)
        btnStop = view.findViewById(R.id.btnStop)
        btnOverlay = view.findViewById(R.id.btnOverlay)
        tvOverlayHint = view.findViewById(R.id.tvOverlayHint)

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
        btnOverlay.setOnClickListener { toggleOverlay() }

        // 点击输入框以外区域时收起软键盘
        view.findViewById<View>(R.id.ttsRoot).setOnTouchListener { _, event ->
            dismissKeyboardOnOutsideTap(event)
            false
        }

        // 构造 TextToSpeech 是异步的，结果通过 onInit() 回调
        tts = TextToSpeech(requireContext(), this)
    }

    override fun onResume() {
        super.onResume()
        // 从悬浮窗权限设置页返回后重新判断
        refreshOverlayUi()
    }

    // ------------------------------------------------------------ 软键盘

    private fun dismissKeyboardOnOutsideTap(ev: MotionEvent) {
        if (ev.action != MotionEvent.ACTION_DOWN) return
        val focused = activity?.currentFocus
        if (focused is EditText) {
            val rect = Rect()
            focused.getGlobalVisibleRect(rect)
            if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                focused.clearFocus()
                val imm = requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(focused.windowToken, 0)
            }
        }
    }

    // ------------------------------------------------------------ 悬浮窗开关

    private fun toggleOverlay() {
        if (OverlayService.isRunning) {
            requireContext().stopService(Intent(requireContext(), OverlayService::class.java))
            refreshOverlayUi()
            return
        }

        // 悬浮窗是特殊权限，只能由用户在系统设置里亲自授予
        if (!Settings.canDrawOverlays(requireContext())) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${requireContext().packageName}")
                )
            )
            return
        }

        requestNotificationPermissionIfNeeded()
        ContextCompat.startForegroundService(
            requireContext(),
            Intent(requireContext(), OverlayService::class.java)
        )

        // 服务的 onStartCommand 是稍后在主线程执行的，等它把 isRunning 置位后再刷新文案
        mainHandler.postDelayed({ refreshOverlayUi() }, 300L)
    }

    private fun refreshOverlayUi() {
        val running = OverlayService.isRunning
        btnOverlay.setText(if (running) R.string.overlay_stop else R.string.overlay_start)
        tvOverlayHint.text = when {
            running -> getString(R.string.overlay_running_hint)
            !Settings.canDrawOverlays(requireContext()) -> getString(R.string.overlay_permission_hint)
            else -> getString(R.string.overlay_idle_hint)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        // Android 13 起没有通知权限的话，前台服务的常驻通知不会显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------ 朗读

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
                    tvStatus.text = getString(R.string.status_speaking)
                }

                override fun onDone(utteranceId: String?) {
                    tvStatus.text = getString(R.string.status_ready)
                }

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    tvStatus.text = getString(R.string.status_error)
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
            Toast.makeText(requireContext(), R.string.toast_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val utteranceId = "utterance-${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    override fun onDestroyView() {
        // 必须释放引擎，否则会一直占着系统服务连接
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroyView()
    }

    private companion object {
        const val DEFAULT_SPEED_PROGRESS = 50

        /** 进度条 0~100 映射为语速 0.1~2.0（50 对应正常语速 1.0） */
        fun rateOf(progress: Int): Float = (progress / 50f).coerceAtLeast(0.1f)
    }
}
