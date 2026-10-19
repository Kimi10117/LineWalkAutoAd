package com.linewalk.autoad.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.linewalk.autoad.R
import com.linewalk.autoad.engine.AutomationEngine
import com.linewalk.autoad.service.AutoAdAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 遊戲懸浮控制窗服務 (FloatingOverlayService)：
 * 提供可自由拖曳的迷你控制面板，支援：
 * - [▶ 開始 / ⏸ 暫停] 自動看廣告狀態機
 * - [✂️ 取樣] 一鍵擷取當前《LINE 散步趣》畫面並框選「滿水瓶」或「廣告 X」
 * - [➖ 縮小 / ➕ 展開] 避免遮擋遊戲或廣告按鈕
 */
class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var engine: AutomationEngine
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var rootView: View? = null
    private var isCollapsed = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        engine = AutomationEngine.getInstance(this)
        startForegroundNotification()
        createFloatingWidget()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_OVERLAY) {
            engine.stop("關閉懸浮控制窗")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        rootView = null
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingWidget() {
        val bgDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#E61E293B"))
            cornerRadius = 28f
            setStroke(3, Color.parseColor("#06C755"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgDrawable
            setPadding(24, 18, 24, 18)
        }

        // 第一列：拖曳標題列 + 狀態 + 展開/縮小按鈕
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleTv = TextView(this).apply {
            text = "🟢 散步趣助手 (待命)"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }

        val collapseBtn = TextView(this).apply {
            text = "  [➖]"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
        }

        headerRow.addView(titleTv)
        headerRow.addView(collapseBtn)
        container.addView(headerRow)

        // 第二列：可折疊詳細資訊與控制按鈕區
        val bodyLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val detailTv = TextView(this).apply {
            text = "點擊 [▶開始] 啟動；首次請點 [✂️取樣] 框選滿水瓶"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11.5f
            maxWidth = 620
            setPadding(0, 6, 0, 10)
        }
        bodyLayout.addView(detailTv)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun styleMiniBtn(btn: Button, bgColor: String) {
            btn.setTextColor(Color.WHITE)
            btn.textSize = 11.5f
            btn.isAllCaps = false
            btn.minHeight = 0
            btn.minimumHeight = 0
            btn.minWidth = 0
            btn.minimumWidth = 0
            btn.setPadding(22, 12, 22, 12)
            btn.background = GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = 16f
            }
        }

        val toggleBtn = Button(this).apply {
            text = "▶ 開始"
            styleMiniBtn(this, "#06C755")
            setOnClickListener {
                engine.toggle()
            }
        }

        val cropBtn = Button(this).apply {
            text = "✂️ 取樣"
            styleMiniBtn(this, "#FFB300")
            setOnClickListener {
                launchTemplateCropper()
            }
        }

        val homeBtn = Button(this).apply {
            text = "⚙️"
            styleMiniBtn(this, "#475569")
            setOnClickListener {
                val intent = Intent(this@FloatingOverlayService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            }
        }

        val closeOverlayBtn = Button(this).apply {
            text = "✖"
            styleMiniBtn(this, "#E53935")
            setOnClickListener {
                engine.stop("使用者關閉懸浮窗")
                stopSelf()
            }
        }

        val marginParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = 12
        }

        btnRow.addView(toggleBtn)
        btnRow.addView(cropBtn, marginParams)
        btnRow.addView(homeBtn, marginParams)
        btnRow.addView(closeOverlayBtn, marginParams)
        bodyLayout.addView(btnRow)
        container.addView(bodyLayout)

        collapseBtn.setOnClickListener {
            isCollapsed = !isCollapsed
            bodyLayout.visibility = if (isCollapsed) View.GONE else View.VISIBLE
            collapseBtn.text = if (isCollapsed) "  [➕]" else "  [➖]"
        }

        // 預設停靠在螢幕左側中間，避開右上角廣告 X 與下方水瓶區
        val wmParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 360
        }

        // 支援手指按住標題列自由拖曳懸浮窗位置
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        headerRow.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = wmParams.x
                    initialY = wmParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    wmParams.x = initialX + (event.rawX - touchX).toInt()
                    wmParams.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(container, wmParams)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - touchX) < 12f && abs(event.rawY - touchY) < 12f) {
                        collapseBtn.performClick()
                    }
                    true
                }

                else -> false
            }
        }

        rootView = container
        windowManager.addView(container, wmParams)

        // 訂閱 AutomationEngine 即時狀態更新懸浮窗文字
        serviceScope.launch {
            engine.uiStatus.collectLatest { status ->
                val badge = if (status.isRunning) "🟢" else "⏸"
                titleTv.text = "$badge ${status.state.title} (廣告:${status.adsCompletedCount})"
                detailTv.text = status.statusDetail
                toggleBtn.text = if (status.isRunning) "⏸ 暫停" else "▶ 開始"
                styleMiniBtn(toggleBtn, if (status.isRunning) "#E53935" else "#06C755")
            }
        }
    }

    /**
     * 點擊 [✂️ 取樣] 時：
     * 1. 先暫停自動化循環並隱藏懸浮窗，避免截圖拍到懸浮窗本身。
     * 2. 呼叫無障礙服務擷取乾淨全螢幕畫面。
     * 3. 開啟 TemplateCropOverlay 供使用者手指拉框儲存範本。
     */
    private fun launchTemplateCropper() {
        val accService = AutoAdAccessibilityService.instance.value
        if (accService == null) {
            Toast.makeText(this, "請先開啟無障礙服務權限才能擷取畫面！", Toast.LENGTH_SHORT).show()
            return
        }

        if (engine.uiStatus.value.isRunning) {
            engine.stop("進入框選取樣模式")
        }

        serviceScope.launch {
            rootView?.visibility = View.INVISIBLE
            delay(180L)
            val bitmap = accService.captureScreenBitmap()
            if (bitmap == null) {
                rootView?.visibility = View.VISIBLE
                Toast.makeText(
                    this@FloatingOverlayService,
                    "截圖失敗，請稍候 1 秒再試",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            TemplateCropOverlay(
                context = this@FloatingOverlayService,
                windowManager = windowManager,
                templateStorage = engine.templateStorage,
                screenshot = bitmap,
                onFinished = { savedCategory ->
                    rootView?.visibility = View.VISIBLE
                    if (savedCategory != null) {
                        engine.appendLog("✂️ 新增圖示範本: ${savedCategory.displayName}")
                    }
                }
            ).show()
        }
    }

    private fun startForegroundNotification() {
        val channelId = "line_walk_auto_ad_overlay"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "散步趣自動看廣告懸浮窗服務",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val openMainIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("懸浮控制窗執行中，點擊返回設定畫面")
            .setContentIntent(openMainIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_OVERLAY = "com.linewalk.autoad.ACTION_STOP_OVERLAY"
    }
}
