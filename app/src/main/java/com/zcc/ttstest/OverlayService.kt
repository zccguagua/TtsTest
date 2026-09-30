package com.zcc.ttstest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.abs

/**
 * 前台服务：在屏幕上挂一个圆形悬浮按钮，点击它朗读剪贴板里的文字。
 *
 * 为什么要前台服务：悬浮窗由本进程绘制，进程一旦被系统回收窗口就没了，
 * 所以必须用前台服务（带常驻通知）把进程保活。
 *
 * 关于剪贴板：Android 10 (API 29) 起，只有「当前持有输入焦点的应用」才能读剪贴板。
 * 而输入焦点同一时刻只属于一个窗口，长期占着会带来副作用（收起别人的输入法、吞掉返回键）。
 * 所以这里的策略是「点按瞬间生效」：
 *   1. 平时窗口带 FLAG_NOT_FOCUSABLE，完全不参与焦点竞争；
 *   2. 手指按下时（ACTION_DOWN）临时去掉该 flag 去要焦点；
 *   3. 靠 OverlayButtonView 的 onWindowFocusChanged 回调确认"真的拿到了焦点"，
 *      而不是靠 sleep 猜时机；
 *   4. 读完剪贴板立刻把 flag 加回去，焦点还给下层应用。
 */
class OverlayService : Service(), TextToSpeech.OnInitListener {

    companion object {
        /** 服务是否在运行，供 Activity 查询以同步按钮文案（同进程，直接读静态变量即可） */
        @Volatile
        var isRunning = false
            private set

        private const val CHANNEL_ID = "overlay_tts"
        private const val NOTIFICATION_ID = 1001

        /** 按下后等待窗口焦点的最长时间，超时就放弃并提示 */
        private const val FOCUS_TIMEOUT_MS = 600L
    }

    private var tts: TextToSpeech? = null

    /** 引擎初始化完成且支持当前语言后为 true */
    private var ready = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private var overlayView: OverlayButtonView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    /** 悬浮窗当前是否持有窗口焦点 */
    private var hasWindowFocus = false

    /** 手指已抬起、正在等焦点就绪后去读剪贴板 */
    private var pendingRead = false
    private var focusTimeout: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 服务生命周期内复用一个 TTS 实例
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 5 秒内调用 startForeground，否则系统会直接抛异常杀掉服务
        startForegroundCompat()
        showOverlay()
        isRunning = true
        // 被系统回收后重启时 intent 可能为 null，这里仍按「显示悬浮窗」处理
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        cancelFocusTimeout()
        pendingRead = false

        overlayView?.let { view ->
            view.onWindowFocusChangedListener = null
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null

        tts?.stop()
        tts?.shutdown()
        tts = null

        super.onDestroy()
    }

    /** 引擎初始化完成的回调 */
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready = false
            toast(getString(R.string.status_init_failed))
            return
        }

        val result = tts?.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            ready = false
            toast(getString(R.string.status_lang_unsupported))
            return
        }

        tts?.apply {
            setSpeechRate(1.0f)
            setPitch(1.0f)
        }
        ready = true
    }

    // ---------------------------------------------------------------- 悬浮窗

    private fun showOverlay() {
        if (overlayView != null) return

        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.overlay_permission_missing))
            stopSelf()
            return
        }

        val view = LayoutInflater.from(this)
            .inflate(R.layout.overlay_button, null) as OverlayButtonView

        hasWindowFocus = false
        pendingRead = false

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            // 平时带 FLAG_NOT_FOCUSABLE：不参与焦点竞争，不打扰下层应用。
            // FLAG_NOT_TOUCH_MODAL 保证悬浮窗以外的点击依然能传给下层应用。
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = resources.displayMetrics.heightPixels / 3
        }

        view.onWindowFocusChangedListener = { focused ->
            hasWindowFocus = focused
            // 抬起后才等到焦点的情况，在这里补上这次朗读
            if (focused && pendingRead) {
                pendingRead = false
                cancelFocusTimeout()
                speakClipboard()
            }
        }

        attachDragAndTap(view)

        runCatching {
            windowManager.addView(view, layoutParams)
            overlayView = view
        }.onFailure {
            toast(getString(R.string.overlay_permission_missing))
            stopSelf()
        }
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /**
     * 悬浮窗既要能拖动又要能点击，所以在 OnTouchListener 里手动区分：
     * 位移超过系统的 touchSlop 就算拖动，否则松手时算点击。
     */
    private fun attachDragAndTap(view: OverlayButtonView) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    dragging = false
                    // 「点按瞬间生效」的起点：按下就去要焦点
                    requestOverlayFocus()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        // 判定为拖动：本次不要剪贴板，焦点立刻还回去
                        releaseOverlayFocus()
                    }
                    if (dragging) {
                        layoutParams.x = (startX + dx).toInt()
                        layoutParams.y = (startY + dy).toInt()
                        runCatching { windowManager.updateViewLayout(view, layoutParams) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    finishGesture(readClipboard = !dragging)
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    finishGesture(readClipboard = false)
                    true
                }

                else -> false
            }
        }
    }

    /** 手势结束：要么去读剪贴板，要么什么都不做、把焦点还回去 */
    private fun finishGesture(readClipboard: Boolean) {
        if (!readClipboard) {
            pendingRead = false
            cancelFocusTimeout()
            releaseOverlayFocus()
            return
        }

        if (hasWindowFocus) {
            // 焦点已经在按下时就拿到了，直接读
            speakClipboard()
        } else {
            // 焦点还没到，交给 onWindowFocusChanged 回调；超时则放弃
            pendingRead = true
            scheduleFocusTimeout()
        }
    }

    // ------------------------------------------------------------ 瞬时的焦点

    /** 临时去掉 FLAG_NOT_FOCUSABLE，让窗口有资格拿输入焦点 */
    private fun requestOverlayFocus() {
        val view = overlayView ?: return
        if (layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) return
        layoutParams.flags =
            layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
    }

    /** 加回 FLAG_NOT_FOCUSABLE，把焦点还给下层应用 */
    private fun releaseOverlayFocus() {
        val view = overlayView ?: return
        if (layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) return
        layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
    }

    private fun scheduleFocusTimeout() {
        cancelFocusTimeout()
        val timeout = Runnable {
            if (pendingRead) {
                pendingRead = false
                releaseOverlayFocus()
                toast(getString(R.string.overlay_focus_failed))
            }
        }
        focusTimeout = timeout
        mainHandler.postDelayed(timeout, FOCUS_TIMEOUT_MS)
    }

    private fun cancelFocusTimeout() {
        focusTimeout?.let { mainHandler.removeCallbacks(it) }
        focusTimeout = null
    }

    // ---------------------------------------------------------------- 朗读

    private fun speakClipboard() {
        val text = currentClipText()
        // 读完立刻把焦点还回去，尽量少影响下层应用
        releaseOverlayFocus()

        if (text.isNullOrBlank()) {
            toast(getString(R.string.overlay_clipboard_empty))
            return
        }

        val engine = tts
        if (engine == null || !ready) {
            toast(getString(R.string.status_init_failed))
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "overlay-${System.currentTimeMillis()}")
    }

    private fun currentClipText(): String? {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip: ClipData = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    // ---------------------------------------------------------------- 通知

    private fun startForegroundCompat() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 起必须给出 type，且要和 manifest 里声明的保持一致
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
