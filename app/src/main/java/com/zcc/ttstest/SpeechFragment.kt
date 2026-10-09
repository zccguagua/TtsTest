package com.zcc.ttstest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import org.json.JSONArray
import java.util.LinkedList

/**
 * 语音识别（ASR）：按住说话、松开识别。
 * 使用 sherpa-onnx 的 SenseVoice 离线模型（16k 单声道 PCM 16bit）。
 */
class SpeechFragment : Fragment() {

    private val sampleRate = 16000

    private lateinit var tvStatus: TextView
    private lateinit var btnHold: Button
    private lateinit var tvResult: TextView
    private lateinit var tvHistoryEmpty: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var btnClearHistory: Button

    private var recognizer: OfflineRecognizer? = null

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    @Volatile
    private var isRecording = false
    private val recordedSamples = mutableListOf<Float>()

    /** 最近识别结果，最新在最前 */
    private val history = LinkedList<String>()
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var permissionLauncher: androidx.activity.result.ActivityResultLauncher<String>

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
        tvResult = view.findViewById(R.id.tvResult)
        tvHistoryEmpty = view.findViewById(R.id.tvHistoryEmpty)
        historyContainer = view.findViewById(R.id.historyContainer)
        btnClearHistory = view.findViewById(R.id.btnClearHistory)

        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadHistory()

        // 模型加载完成前禁用按住说话
        btnHold.isEnabled = false

        btnHold.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    startRecording()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    stopRecordingAndDecode()
                    true
                }

                else -> false
            }
        }

        btnClearHistory.setOnClickListener { clearHistory() }

        initRecognizer()
    }

    // ------------------------------------------------------------ 模型初始化

    private fun initRecognizer() {
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
                recognizer = OfflineRecognizer(assetManager = requireContext().assets, config = config)
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
        if (recognizer == null) return

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

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            numBytes * 2,
        )

        recordedSamples.clear()
        audioRecord?.startRecording()
        isRecording = true
        tvStatus.text = getString(R.string.speech_listening)

        recordThread = Thread {
            val buffer = ShortArray(512)
            while (isRecording) {
                val ret = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (ret > 0) {
                    synchronized(recordedSamples) {
                        for (i in 0 until ret) {
                            recordedSamples.add(buffer[i] / 32768.0f)
                        }
                    }
                }
            }
        }.apply { start() }
    }

    private fun stopRecordingAndDecode() {
        if (!isRecording) return
        isRecording = false
        tvStatus.text = getString(R.string.speech_decoding)

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        recordThread?.join(1000)
        recordThread = null
        audioRecord = null

        val samples = synchronized(recordedSamples) { recordedSamples.toFloatArray() }
        recordedSamples.clear()

        if (samples.isEmpty()) {
            tvStatus.text = getString(R.string.speech_ready)
            toast(getString(R.string.speech_no_audio))
            return
        }

        decodeAsync(samples)
    }

    private fun decodeAsync(samples: FloatArray) {
        Thread {
            val rec = recognizer ?: return@Thread
            try {
                val stream = rec.createStream()
                stream.acceptWaveform(samples, sampleRate)
                rec.decode(stream)
                val text = rec.getResult(stream).text
                stream.release()
                view?.post { onResult(text) }
            } catch (e: Exception) {
                view?.post {
                    tvStatus.text = getString(R.string.speech_ready)
                    toast(getString(R.string.speech_decode_failed))
                }
            }
        }.start()
    }

    private fun onResult(text: String) {
        tvStatus.text = getString(R.string.speech_ready)
        val trimmed = text.trim()
        tvResult.text = trimmed.ifEmpty { getString(R.string.speech_empty_result) }
        if (trimmed.isNotEmpty()) {
            addHistory(trimmed)
        }
    }

    // ------------------------------------------------------------ 历史记录

    private fun addHistory(text: String) {
        history.addFirst(text)
        while (history.size > MAX_HISTORY) {
            history.removeLast()
        }
        saveHistory()
        renderHistory()
    }

    private fun saveHistory() {
        val array = JSONArray()
        for (item in history) {
            array.put(item)
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun loadHistory() {
        history.clear()
        val raw = prefs.getString(KEY_HISTORY, null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                if (history.size >= MAX_HISTORY) break
                history.addLast(array.getString(i))
            }
        } catch (_: Exception) {
        }
        renderHistory()
    }

    private fun renderHistory() {
        historyContainer.removeAllViews()
        tvHistoryEmpty.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        for (item in history) {
            val tv = TextView(requireContext())
            tv.text = item
            tv.textSize = 14f
            tv.setPadding(0, dp(8), 0, dp(8))
            historyContainer.addView(tv)
        }
    }

    private fun clearHistory() {
        history.clear()
        saveHistory()
        renderHistory()
    }

    override fun onDestroyView() {
        if (isRecording) {
            isRecording = false
            try {
                audioRecord?.stop()
            } catch (_: Exception) {
            }
            try {
                audioRecord?.release()
            } catch (_: Exception) {
            }
            audioRecord = null
        }
        recordThread?.join(500)
        recognizer?.release()
        recognizer = null
        super.onDestroyView()
    }

    private fun dp(value: Int): Int =
        (value * requireContext().resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PREFS_NAME = "speech_history"
        const val KEY_HISTORY = "history"
        const val MAX_HISTORY = 20
    }
}
