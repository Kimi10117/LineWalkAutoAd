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
import android.graphics.Rect
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
 * - 記錄自身在螢幕上的精確座標 (overlayScreenBounds)，讓 OCR 與圖像比對完全忽略懸浮窗區域。
 * - 懸浮窗內文字避免出現「水瓶 / 領取 / 跳過」等關鍵字，防止 OCR 誤判點到懸浮窗自己。
 */
class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var engine: AutomationEngine
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var rootView: View? = null
    private var isCollapsed = false

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        engine = AutomationEngine.getInstance(this)
        startForegroundNotification()
        createFloatingWidget()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_OVERLAY) {
            engine.stop("關閉懸浮窗")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        serviceScope.cancel()
        overlayScreenBounds.setEmpty()
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        rootView = null
        super.onDestroy()
    }

    private fun updateOverlayBoundsOnScreen() {
        val v = rootView ?: return
        if (v.visibility != View.VISIBLE) {
            overlayScreenBounds.setEmpty()
            return
        }
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        // 外擴 16px 作為安全隔離區，避免 OCR 或模板比對掃到懸浮窗邊緣
        overlayScreenBounds.set(
            loc[0] - 16,
            loc[1] - 16,
            loc[0] + v.width + 16,
            loc[1] + v.height + 16
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingWidget() {
        val bgDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#E61E293B"))
            cornerRadius = 26f
            setStroke(3, Color.parseColor("#06C755"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgDrawable
            setPadding(22, 16, 22, 16)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateOverlayBoundsOnScreen()
            }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleTv = TextView(this).apply {
            text = "🟢 散步趣助手 (待命)"
            setTextColor(Color.WHITE)
            textSize = 12.5f
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

        val bodyLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val detailTv = TextView(this).apply {
            text = "準備就緒 (首次可點 ✂️ 框選目標)"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
            maxWidth = 540
            setPadding(0, 4, 0, 8)
        }
        bodyLayout.addView(detailTv)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun styleMiniBtn(btn: Button, bgColor: String) {
            btn.setTextColor(Color.WHITE)
            btn.textSize = 11f
            btn.isAllCaps = false
            btn.minHeight = 0
            btn.minimumHeight = 0
            btn.minWidth = 0
            btn.minimumWidth = 0
            btn.setPadding(20, 10, 20, 10)
            btn.background = GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = 14f
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
            marginStart = 10
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
            container.post { updateOverlayBoundsOnScreen() }
        }

        val defaultY = (resources.displayMetrics.heightPixels * 0.165f).toInt().coerceAtLeast(280)
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
            y = defaultY
        }

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
                    updateOverlayBoundsOnScreen()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    updateOverlayBoundsOnScreen()
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
        container.post { updateOverlayBoundsOnScreen() }

        serviceScope.launch {
            engine.uiStatus.collectLatest { status ->
                val badge = if (status.isRunning) "🟢" else "⏸"
                titleTv.text = "$badge ${status.state.shortTitle} (完成:${status.adsCompletedCount})"
                detailTv.text = status.overlaySafeDetail
                toggleBtn.text = if (status.isRunning) "⏸ 暫停" else "▶ 開始"
                styleMiniBtn(toggleBtn, if (status.isRunning) "#E53935" else "#06C755")
                container.post { updateOverlayBoundsOnScreen() }
            }
        }
    }

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
            overlayScreenBounds.setEmpty()
            delay(200L)
            val bitmap = accService.captureScreenBitmap()
            if (bitmap == null) {
                rootView?.visibility = View.VISIBLE
                updateOverlayBoundsOnScreen()
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
                    rootView?.post { updateOverlayBoundsOnScreen() }
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
                "散步趣助手懸浮窗",
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
            .setContentText("懸浮窗執行中，點擊返回主設定")
            .setContentIntent(openMainIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_OVERLAY = "com.linewalk.autoad.ACTION_STOP_OVERLAY"

        @Volatile
        private var activeInstance: FloatingOverlayService? = null

        /** 紀錄懸浮窗目前在螢幕上的絕對區域，讓 OCR 與圖像比對自動避開懸浮窗本身 */
        val overlayScreenBounds = Rect()

        fun isInsideOverlay(x: Int, y: Int): Boolean {
            return !overlayScreenBounds.isEmpty && overlayScreenBounds.contains(x, y)
        }

        /** 若目標點擊座標剛好被使用者拖曳的懸浮窗蓋住，暫時隱藏懸浮窗 120ms 再執行點擊，確保手勢直達底層遊戲 */
        suspend fun clickWithOverlayPassthrough(targetRect: Rect, clickBlock: suspend () -> Unit) {
            val inst = activeInstance
            val v = inst?.rootView
            val overlaps = !overlayScreenBounds.isEmpty && Rect.intersects(overlayScreenBounds, targetRect)
            if (overlaps && v != null && v.visibility == View.VISIBLE) {
                v.visibility = View.INVISIBLE
                delay(90L)
                try {
                    clickBlock()
                    delay(90L)
                } finally {
                    v.visibility = View.VISIBLE
                    v.post { inst.updateOverlayBoundsOnScreen() }
                }
            } else {
                clickBlock()
            }
        }
    }
}
