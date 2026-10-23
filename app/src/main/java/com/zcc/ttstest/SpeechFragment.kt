package com.zcc.ttstest

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 语音识别（ASR）：按住说话、松开识别。
 * 使用 sherpa-onnx 的 SenseVoice 离线模型（16k 单声道 PCM 16bit）。
 *
 * 界面类似微信输入：底部按住说话，识别结果以气泡形式依次追加到中间列表的最下方，
 * 气泡上方显示时间，右侧小三角可点击用系统 TTS 朗读该条文字。
 */
class SpeechFragment : Fragment() {

    private val sampleRate = 16000

    private lateinit var tvStatus: TextView
    private lateinit var btnHold: Button
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyScroll: ScrollView
    private lateinit var btnClearHistory: Button

    private var recognizer: OfflineRecognizer? = null

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    @Volatile
    private var isRecording = false

    /** 当前这次录音的采样点。每次按住新建，避免和上一次待识别音频互相污染 */
    private var sessionSamples: MutableList<Float>? = null

    /**
     * 单线程队列：多次「松开」的结果按顺序依次显示，
     * 同时避免同一个 recognizer 被并发调用。
     */
    private lateinit var decodeExecutor: ExecutorService
    private var pendingDecodes = 0

    /** 识别结果，按时间从旧到新（最新在最后，显示在最下方） */
    private val history = LinkedList<SpeechEntry>()
    private lateinit var prefs: SharedPreferences

    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    // ------------------------------------------------------------ 气泡朗读（TTS）

    private var tts: TextToSpeech? = null
    private var ttsStatus = TtsStatus.INITIALIZING
    private var ttsErrorRes = R.string.status_init_failed

    /** TTS 还没初始化完就点了小三角时，先把文字记下来，就绪后再读 */
    private var pendingSpeakText: String? = null

    private val timeFormatToday = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val timeFormatOtherDay = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                toast(getString(R.string.speech_permission_granted))
            } else {
                toast(getString(R.string.speech_permission_denied))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_speech, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvStatus = view.findViewById(R.id.tvStatus)
        btnHold = view.findViewById(R.id.btnHold)
        tvHistoryEmpty = view.findViewById(R.id.tvHistoryEmpty)
        historyContainer = view.findViewById(R.id.historyContainer)
        historyScroll = view.findViewById(R.id.historyScroll)
        btnClearHistory = view.findViewById(R.id.btnClearHistory)

        decodeExecutor = Executors.newSingleThreadExecutor()

        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadHistory()

        // 模型加载完成前禁用按住说话
        btnHold.isEnabled = false

        btnHold.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 防止父布局（如可滚动容器）抢走这次触摸，导致录音被中途取消
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.isPressed = true
                    btnHold.setText(R.string.speech_release_hint)
                    startRecording()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.isPressed = false
                    btnHold.setText(R.string.speech_hold_hint)
                    stopRecordingAndDecode()
                    true
                }

                else -> false
            }
        }

        btnClearHistory.setOnClickListener { clearHistory() }

        // 朗读用 TTS 只在这里初始化一次，点小三角时就能直接出声
        initTts()

        if (recognizer != null) {
            // Fragment 的 View 被重建时，模型还在，直接恢复可用状态
            tvStatus.text = getString(R.string.speech_ready)
            btnHold.isEnabled = true
        } else {
            initRecognizer()
        }
    }

    // ------------------------------------------------------------ 模型初始化

    private fun initRecognizer() {
        val appContext = requireContext().applicationContext

        // 加载约 200MB 的模型，务必放子线程，否则主线程会 ANR
        Thread {
            try {
                val modelDir = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = "$modelDir/model.int8.onnx",
                        ),
                        tokens = "$modelDir/tokens.txt",
                        numThreads = 2,
                        debug = false,
                    ),
                )
                val loaded = OfflineRecognizer(assetManager = appContext.assets, config = config)
                if (view == null) {
                    // 页面已经销毁，别把 200MB 的模型留在内存里
                    loaded.release()
                    return@Thread
                }
                recognizer = loaded
                view?.post {
                    tvStatus.text = getString(R.string.speech_ready)
                    btnHold.isEnabled = true
                }
            } catch (e: Exception) {
                view?.post {
                    tvStatus.text = getString(R.string.speech_init_failed)
                }
            }
        }.start()
    }

    // ------------------------------------------------------------ 录音

    private fun startRecording() {
        if (isRecording || recognizer == null) return

        // 正在朗读气泡时开始录音，先停掉，避免把自己的朗读声录进去
        tts?.stop()

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val numBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (numBytes <= 0) {
            toast(getString(R.string.speech_decode_failed))
            return
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                numBytes * 2,
            )
        } catch (e: Exception) {
            toast(getString(R.string.speech_decode_failed))
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            toast(getString(R.string.speech_decode_failed))
            return
        }

        val samples = Collections.synchronizedList(ArrayList<Float>())
        sessionSamples = samples
        audioRecord = record

        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            audioRecord = null
            sessionSamples = null
            toast(getString(R.string.speech_decode_failed))
            return
        }

        isRecording = true
        tvStatus.text = getString(R.string.speech_listening)

        recordThread = Thread {
            val buffer = ShortArray(512)
            while (isRecording) {
                val ret = record.read(buffer, 0, buffer.size)
                if (ret > 0) {
                    synchronized(samples) {
                        for (i in 0 until ret) {
                            samples.add(buffer[i] / 32768.0f)
                        }
                    }
                } else if (ret < 0) {
                    break
                }
            }
        }.apply { start() }
    }

    private fun stopRecordingAndDecode() {
        if (!isRecording) return
        isRecording = false
        tvStatus.text = getString(R.string.speech_decoding)

        val samples = sessionSamples
        sessionSamples = null
        val thread = recordThread
        recordThread = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        val rec = recognizer
        if (samples == null || rec == null || !::decodeExecutor.isInitialized) {
            tvStatus.text = getString(R.string.speech_ready)
            return
        }

        pendingDecodes++
        decodeExecutor.execute {
            // 等录音线程收尾后再拷贝，避免读到不完整的这一句
            try {
                thread?.join(1000)
            } catch (_: InterruptedException) {
            }
            val pcm = synchronized(samples) { samples.toFloatArray() }

            val outcome = when {
                pcm.isEmpty() -> DecodeOutcome.NoAudio
                else -> {
                    val text = decodeBlocking(rec, pcm)
                    if (text == null) DecodeOutcome.Failed else DecodeOutcome.Text(text)
                }
            }
            view?.post { onDecodeFinished(outcome) }
        }
    }

    private fun decodeBlocking(rec: OfflineRecognizer, pcm: FloatArray): String? =
        try {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(pcm, sampleRate)
                rec.decode(stream)
                rec.getResult(stream).text
            } finally {
                stream.release()
            }
        } catch (_: Exception) {
            null
        }

    private fun onDecodeFinished(outcome: DecodeOutcome) {
        if (!isAdded || view == null) return

        pendingDecodes = (pendingDecodes - 1).coerceAtLeast(0)

        when (outcome) {
            is DecodeOutcome.Text -> {
                val trimmed = outcome.text.trim()
                if (trimmed.isEmpty()) {
                    toast(getString(R.string.speech_empty_result))
                } else {
                    addHistory(trimmed)
                }
            }

            DecodeOutcome.NoAudio -> toast(getString(R.string.speech_no_audio))
            DecodeOutcome.Failed -> toast(getString(R.string.speech_decode_failed))
        }

        // 还有录音在进行或还有结果没出完时，不要抢着把状态改回「就绪」
        if (!isRecording && pendingDecodes == 0) {
            tvStatus.text = getString(R.string.speech_ready)
        }
    }

    // ------------------------------------------------------------ 历史记录（气泡列表）

    private fun addHistory(text: String) {
        history.addLast(SpeechEntry(text, System.currentTimeMillis()))
        while (history.size > MAX_HISTORY) {
            history.removeFirst()
        }
        saveHistory()
        renderHistory()
    }

    private fun saveHistory() {
        val array = JSONArray()
        for (entry in history) {
            array.put(
                JSONObject().apply {
                    put(KEY_TEXT, entry.text)
                    put(KEY_TIME, entry.time)
                }
            )
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun loadHistory() {
        history.clear()
        val raw = prefs.getString(KEY_HISTORY, null)
        if (raw != null) {
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    if (history.size >= MAX_HISTORY) break
                    val obj = array.optJSONObject(i)
                    if (obj != null) {
                        val text = obj.optString(KEY_TEXT)
                        if (text.isNotEmpty()) {
                            history.addLast(
                                SpeechEntry(text, obj.optLong(KEY_TIME, 0L))
                            )
                        }
                    } else {
                        // 兼容旧版本：存的是纯字符串，没有时间戳
                        val text = array.optString(i)
                        if (text.isNotEmpty()) {
                            history.addLast(SpeechEntry(text, 0L))
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        renderHistory()
    }

    private fun renderHistory() {
        historyContainer.removeAllViews()
        tvHistoryEmpty.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE

        val maxBubbleWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        val bubbleTextColor = ContextCompat.getColor(requireContext(), R.color.text_black)
        val secondaryColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        val tailColor = ContextCompat.getColor(requireContext(), R.color.wechat_green)

        for (entry in history) {
            // 一条记录 = 时间戳 + [气泡 + 小三角]
            val wrapper = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            historyContainer.addView(
                wrapper,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
            )

            if (entry.time > 0L) {
                val timeView = TextView(requireContext()).apply {
                    text = formatTime(entry.time)
                    textSize = 10f
                    setTextColor(secondaryColor)
                }
                // 右内边距 = 小三角宽度，让时间与气泡右边缘对齐
                wrapper.addView(
                    timeView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginEnd = dp(30)
                        bottomMargin = dp(2)
                    }
                )
            }

            val bubbleRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            wrapper.addView(bubbleRow)

            val bubble = TextView(requireContext()).apply {
                text = entry.text
                textSize = 14f
                setTextColor(bubbleTextColor)
                setBackgroundResource(R.drawable.bg_bubble_speech)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                maxWidth = maxBubbleWidth
            }
            bubbleRow.addView(bubble)

            // 小三角：点击用 TTS 朗读这条气泡
            val tail = ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_bubble_tail)
                contentDescription = getString(R.string.speech_play_bubble)
                scaleType = ImageView.ScaleType.CENTER
                setColorFilter(tailColor)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = borderlessRipple()
                setOnClickListener { speakBubble(entry.text) }
            }
            bubbleRow.addView(
                tail,
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(2) }
            )
        }

        scrollToBottom()
    }

    /** 新消息追加后自动滚到最底部 */
    private fun scrollToBottom() {
        historyScroll.post {
            historyScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun clearHistory() {
        history.clear()
        saveHistory()
        renderHistory()
    }

    private fun formatTime(time: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = time }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val formatter = if (sameDay) timeFormatToday else timeFormatOtherDay
        return formatter.format(Date(time))
    }

    private fun borderlessRipple(): Drawable? {
        val value = TypedValue()
        val resolved = requireContext().theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, value, true
        )
        return if (resolved && value.resourceId != 0) {
            ContextCompat.getDrawable(requireContext(), value.resourceId)
        } else {
            null
        }
    }

    // ------------------------------------------------------------ 气泡朗读（TTS）

    private fun initTts() {
        ttsStatus = TtsStatus.INITIALIZING
        tts = TextToSpeech(requireContext().applicationContext, TextToSpeech.OnInitListener {
            onTtsInit(it)
        })
    }

    private fun onTtsInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ttsStatus = TtsStatus.UNAVAILABLE
            ttsErrorRes = R.string.status_init_failed
            return
        }

        val result = tts?.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            ttsStatus = TtsStatus.UNAVAILABLE
            ttsErrorRes = R.string.status_lang_unsupported
            return
        }

        tts?.setPitch(1.0f)
        tts?.setSpeechRate(1.0f)
        ttsStatus = TtsStatus.READY

        pendingSpeakText?.let { text ->
            pendingSpeakText = null
            speakNow(text)
        }
    }

    private fun speakBubble(text: String) {
        when (ttsStatus) {
            TtsStatus.READY -> speakNow(text)
            TtsStatus.INITIALIZING -> {
                pendingSpeakText = text
                toast(getString(R.string.status_initializing))
            }

            TtsStatus.UNAVAILABLE -> toast(getString(ttsErrorRes))
        }
    }

    private fun speakNow(text: String) {
        val result = tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "speech-bubble-${System.currentTimeMillis()}",
        )
        if (result == TextToSpeech.ERROR) {
            toast(getString(R.string.status_error))
        }
    }

    override fun onDestroyView() {
        isRecording = false
        sessionSamples = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        recordThread?.let {
            try {
                it.join(500)
            } catch (_: InterruptedException) {
            }
        }
        recordThread = null

        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsStatus = TtsStatus.INITIALIZING
        pendingSpeakText = null

        val rec = recognizer
        recognizer = null
        pendingDecodes = 0

        if (::decodeExecutor.isInitialized) {
            decodeExecutor.shutdown()
            // 等队列里正在跑的识别结束，再释放原生模型，避免 use-after-free
            Thread {
                try {
                    decodeExecutor.awaitTermination(3, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                }
                rec?.release()
            }.start()
        } else {
            rec?.release()
        }

        super.onDestroyView()
    }

    private fun dp(value: Int): Int =
        (value * requireContext().resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private data class SpeechEntry(val text: String, val time: Long)

    private enum class TtsStatus { INITIALIZING, READY, UNAVAILABLE }

    private sealed interface DecodeOutcome {
        data class Text(val text: String) : DecodeOutcome
        object NoAudio : DecodeOutcome
        object Failed : DecodeOutcome
    }

    private companion object {
        const val PREFS_NAME = "speech_history"
        const val KEY_HISTORY = "history"
        const val KEY_TEXT = "text"
        const val KEY_TIME = "time"
        const val MAX_HISTORY = 20
    }
}
