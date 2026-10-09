package com.zcc.ttstest

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import java.util.Locale

/**
 * 常用语：点击直接朗读（喇叭闪烁动画），支持拖拽排序、侧滑露出删除按钮、新增。
 * 数据用 SharedPreferences + JSON 持久化，首次启动预置几条默认用语。
 */
class PhrasesFragment : Fragment(), TextToSpeech.OnInitListener {

    private lateinit var rvPhrases: RecyclerView
    private lateinit var tvPhraseEmpty: TextView
    private lateinit var btnAddPhrase: Button
    private lateinit var adapter: PhraseAdapter
    private lateinit var touchCallback: SwipeRevealCallback

    private var tts: TextToSpeech? = null
    private var ready = false

    private val blinkHandler = Handler(Looper.getMainLooper())
    private var stopBlinkRunnable: Runnable? = null

    private val phrases = mutableListOf<String>()
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_phrases, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rvPhrases = view.findViewById(R.id.rvPhrases)
        tvPhraseEmpty = view.findViewById(R.id.tvPhraseEmpty)
        btnAddPhrase = view.findViewById(R.id.btnAddPhrase)

        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        adapter = PhraseAdapter(
            phrases = phrases,
            onClick = { phrase ->
                touchCallback.closeOpen()
                speakPhrase(phrase)
            },
            onDelete = { position -> deletePhrase(position) },
        )
        rvPhrases.layoutManager = LinearLayoutManager(requireContext())
        rvPhrases.adapter = adapter

        val revealWidth = (88 * requireContext().resources.displayMetrics.density).toInt()
        touchCallback = SwipeRevealCallback(adapter, revealWidth) { savePhrases() }
        ItemTouchHelper(touchCallback).attachToRecyclerView(rvPhrases)

        btnAddPhrase.setOnClickListener { showAddDialog() }

        loadPhrases()
        tts = TextToSpeech(requireContext(), this)
    }

    private fun speakPhrase(text: String) {
        if (!ready) {
            toast(getString(R.string.phrases_tts_not_ready))
            return
        }
        adapter.playingText = text
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "phrase-${System.currentTimeMillis()}")

        // 喇叭图标闪烁 2 秒后自动停止
        stopBlinkRunnable?.let { blinkHandler.removeCallbacks(it) }
        val runnable = Runnable { adapter.playingText = null }
        stopBlinkRunnable = runnable
        blinkHandler.postDelayed(runnable, 2000L)
    }

    private fun deletePhrase(position: Int) {
        adapter.onItemRemoved(position)
        touchCallback.closeOpen()
        savePhrases()
        toast(getString(R.string.phrases_deleted))
    }

    private fun showAddDialog() {
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.phrases_add_hint)
            isSingleLine = true
            val p = dp(20)
            setPadding(p, p, p, p)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.phrases_add_title)
            .setView(editText)
            .setPositiveButton(R.string.phrases_confirm) { _, _ ->
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    phrases.add(text)
                    adapter.notifyItemInserted(phrases.size - 1)
                    rvPhrases.scrollToPosition(phrases.size - 1)
                    savePhrases()
                }
            }
            .setNegativeButton(R.string.phrases_cancel, null)
            .show()
    }

    private fun loadPhrases() {
        phrases.clear()
        val raw = prefs.getString(KEY_PHRASES, null)
        if (raw == null) {
            phrases.addAll(DEFAULT_PHRASES)
            savePhrases()
        } else {
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    phrases.add(array.getString(i))
                }
            } catch (_: Exception) {
                phrases.addAll(DEFAULT_PHRASES)
            }
        }
        adapter.notifyDataSetChanged()
        updateEmpty()
    }

    private fun savePhrases() {
        val array = JSONArray()
        for (phrase in phrases) {
            array.put(phrase)
        }
        prefs.edit().putString(KEY_PHRASES, array.toString()).apply()
        updateEmpty()
    }

    private fun updateEmpty() {
        tvPhraseEmpty.visibility = if (phrases.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            return
        }
        val result = tts?.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            ready = false
            return
        }
        tts?.apply {
            setSpeechRate(1.0f)
        }
        ready = true
    }

    override fun onDestroyView() {
        stopBlinkRunnable?.let { blinkHandler.removeCallbacks(it) }
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroyView()
    }

    private fun dp(value: Int): Int =
        (value * requireContext().resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PREFS_NAME = "phrases"
        const val KEY_PHRASES = "phrases"

        val DEFAULT_PHRASES = listOf(
            "你好，请问有什么可以帮您？",
            "谢谢",
            "请问洗手间在哪里？",
            "麻烦再说一遍",
            "好的，没问题",
        )
    }
}
