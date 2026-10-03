package com.zcc.ttstest

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.util.Locale

/**
 * 拍照识别界面：拍照或从相册选图，用 ML Kit 离线识别中文文字，
 * 识别结果可编辑、可复制、可直接朗读。
 */
class PhotoOcrActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var ivPreview: ImageView
    private lateinit var etResult: EditText
    private lateinit var btnCamera: Button
    private lateinit var btnGallery: Button
    private lateinit var btnCopy: Button
    private lateinit var btnSpeak: Button

    private var tts: TextToSpeech? = null
    private var ready = false

    /** 识别结果是否可用（复制/朗读前判断） */
    private var hasResult = false

    /** 相机拍下的照片临时 URI */
    private var cameraPhotoUri: Uri? = null

    /** ML Kit 中文离线识别器（懒加载，只建一次） */
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraPhotoUri?.let { showImageAndRecognize(it) }
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else toast(getString(R.string.ocr_camera_denied))
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) showImageAndRecognize(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_ocr)

        ivPreview = findViewById(R.id.ivPreview)
        etResult = findViewById(R.id.etResult)
        btnCamera = findViewById(R.id.btnCamera)
        btnGallery = findViewById(R.id.btnGallery)
        btnCopy = findViewById(R.id.btnCopy)
        btnSpeak = findViewById(R.id.btnSpeak)

        // 引擎就绪前禁用朗读
        btnSpeak.isEnabled = false

        btnCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                launchCamera()
            }
        }
        btnGallery.setOnClickListener { pickImageLauncher.launch("image/*") }
        btnCopy.setOnClickListener { copyResult() }
        btnSpeak.setOnClickListener { speakResult() }

        tts = TextToSpeech(this, this)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 点击输入框以外的区域时收起软键盘，避免遮挡底部按钮
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText) {
                val rect = Rect()
                focused.getGlobalVisibleRect(rect)
                if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    focused.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(focused.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "ocr")
        dir.mkdirs()
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraPhotoUri = uri
        takePictureLauncher.launch(uri)
    }

    private fun showImageAndRecognize(uri: Uri) {
        ivPreview.setImageURI(uri)
        recognize(uri)
    }

    private fun recognize(uri: Uri) {
        etResult.setText(getString(R.string.ocr_recognizing))
        hasResult = false
        try {
            // fromFilePath 会自动处理照片的 EXIF 旋转方向
            val image = InputImage.fromFilePath(this, uri)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text.trim()
                    hasResult = text.isNotEmpty()
                    etResult.setText(text.ifEmpty { getString(R.string.ocr_empty) })
                }
                .addOnFailureListener {
                    etResult.setText(getString(R.string.ocr_error))
                }
        } catch (e: Exception) {
            etResult.setText(getString(R.string.ocr_error))
        }
    }

    private fun copyResult() {
        val text = etResult.text.toString()
        if (!hasResult || text.isBlank()) {
            toast(getString(R.string.ocr_nothing_to_copy))
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OCR", text))
        toast(getString(R.string.ocr_copied))
    }

    private fun speakResult() {
        if (!ready) return
        val text = etResult.text.toString().trim()
        if (!hasResult || text.isEmpty()) {
            toast(getString(R.string.ocr_empty))
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ocr-${System.currentTimeMillis()}")
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
        tts?.setSpeechRate(1.0f)
        ready = true
        btnSpeak.isEnabled = true
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        // 相机照片本就存在 cache 目录（不进相册），退出时删掉更干净
        File(cacheDir, "ocr").deleteRecursively()
        super.onDestroy()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
