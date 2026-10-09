package com.zcc.ttstest

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * 容器 Activity：底部导航栏 + 4 个 Fragment 切换。
 *
 * Fragment 采用「首次点击时懒加载 + add/show/hide」策略：
 * 这样语音识别的 200MB 模型只在用户第一次切到「语音识别」时加载，
 * 且切走再切回来不会重建（模型、OCR 结果、常用语列表状态都保留）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView

    private val tags = listOf(TAG_TTS, TAG_OCR, TAG_SPEECH, TAG_PHRASES)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bottomNav = findViewById(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_tts -> selectTab(TAG_TTS)
                R.id.nav_ocr -> selectTab(TAG_OCR)
                R.id.nav_speech -> selectTab(TAG_SPEECH)
                R.id.nav_phrases -> selectTab(TAG_PHRASES)
            }
            true
        }

        // 首次启动默认落在「朗读」；旋转重建时由 FragmentManager 自动恢复可见状态
        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.nav_tts
        }
    }

    private fun selectTab(tag: String) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()

        tags.forEach { t ->
            fm.findFragmentByTag(t)?.let { f -> if (!f.isHidden) tx.hide(f) }
        }

        val existing = fm.findFragmentByTag(tag)
        if (existing != null) {
            tx.show(existing)
        } else {
            val fragment = createFragment(tag)
            tx.add(R.id.fragmentContainer, fragment, tag)
        }
        tx.commit()
    }

    private fun createFragment(tag: String): Fragment = when (tag) {
        TAG_TTS -> TtsFragment()
        TAG_OCR -> OcrFragment()
        TAG_SPEECH -> SpeechFragment()
        TAG_PHRASES -> PhrasesFragment()
        else -> throw IllegalArgumentException("Unknown tag: $tag")
    }

    private companion object {
        const val TAG_TTS = "tts"
        const val TAG_OCR = "ocr"
        const val TAG_SPEECH = "speech"
        const val TAG_PHRASES = "phrases"
    }
}
