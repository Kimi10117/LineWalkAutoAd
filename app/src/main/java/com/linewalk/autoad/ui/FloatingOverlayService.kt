package com.linewalk.autoad.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.linewalk.autoad.R
import com.linewalk.autoad.engine.AutomationEngine
import com.linewalk.autoad.service.AutoAdAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
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

    internal var rootView: View? = null
        private set
    internal var layoutParams: WindowManager.LayoutParams? = null
        private set
    internal var isCollapsed = false
        private set
    internal var bodyLayout: LinearLayout? = null
        private set
    internal var headerRow: LinearLayout? = null
        private set
    internal var titleTv: TextView? = null
        private set
    internal var collapseBtn: ImageView? = null
        private set
    internal var statusDot: ImageView? = null
        private set
    private var bgDrawable: GradientDrawable? = null

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
        layoutParams = null
        bodyLayout = null
        headerRow = null
        titleTv = null
        collapseBtn = null
        statusDot = null
        bgDrawable = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val container = rootView ?: return
        val params = layoutParams ?: return
        val displayMetrics = resources.displayMetrics

        val viewW = container.width.takeIf { it > 0 } ?: container.measuredWidth.coerceAtLeast(1)
        val viewH = container.height.takeIf { it > 0 } ?: container.measuredHeight.coerceAtLeast(1)
        val maxX = (displayMetrics.widthPixels - viewW).coerceAtLeast(0)
        val maxY = (displayMetrics.heightPixels - viewH).coerceAtLeast(0)

        val clampedX = params.x.coerceIn(0, maxX)
        val clampedY = params.y.coerceIn(0, maxY)

        if (clampedX != params.x || clampedY != params.y) {
            params.x = clampedX
            params.y = clampedY
            try {
                windowManager.updateViewLayout(container, params)
            } catch (_: Exception) {
            }
        }

        container.post {
            val curContainer = rootView ?: return@post
            val curParams = layoutParams ?: return@post
            val curMetrics = resources.displayMetrics
            val curW = curContainer.width.takeIf { it > 0 } ?: curContainer.measuredWidth.coerceAtLeast(1)
            val curH = curContainer.height.takeIf { it > 0 } ?: curContainer.measuredHeight.coerceAtLeast(1)
            val curMaxX = (curMetrics.widthPixels - curW).coerceAtLeast(0)
            val curMaxY = (curMetrics.heightPixels - curH).coerceAtLeast(0)

            val reClampedX = curParams.x.coerceIn(0, curMaxX)
            val reClampedY = curParams.y.coerceIn(0, curMaxY)
            if (reClampedX != curParams.x || reClampedY != curParams.y) {
                curParams.x = reClampedX
                curParams.y = reClampedY
                try {
                    windowManager.updateViewLayout(curContainer, curParams)
                } catch (_: Exception) {
                }
            }
            updateOverlayBoundsOnScreen()
        }
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

    internal fun setCollapsedState(collapsed: Boolean) {
        isCollapsed = collapsed
        val body = bodyLayout ?: return
        val btn = collapseBtn ?: return
        val density = resources.displayMetrics.density

        body.visibility = if (collapsed) View.GONE else View.VISIBLE
        btn.setImageResource(if (collapsed) R.drawable.ic_expand else R.drawable.ic_minimize)

        val bg = bgDrawable
        if (collapsed) {
            bg?.cornerRadius = 999f
            val padH = (12 * density).toInt()
            val padV = (6 * density).toInt()
            rootView?.setPadding(padH, padV, padH, padV)
        } else {
            bg?.cornerRadius = 24f * density / 2f
            val padH = (14 * density).toInt()
            val padV = (10 * density).toInt()
            rootView?.setPadding(padH, padV, padH, padV)
        }

        rootView?.post {
            val container = rootView ?: return@post
            val params = layoutParams ?: return@post
            val displayMetrics = resources.displayMetrics
            val viewW = container.width.takeIf { it > 0 } ?: container.measuredWidth.coerceAtLeast(1)
            val viewH = container.height.takeIf { it > 0 } ?: container.measuredHeight.coerceAtLeast(1)
            val maxX = (displayMetrics.widthPixels - viewW).coerceAtLeast(0)
            val maxY = (displayMetrics.heightPixels - viewH).coerceAtLeast(0)

            val clampedX = params.x.coerceIn(0, maxX)
            val clampedY = params.y.coerceIn(0, maxY)
            if (clampedX != params.x || clampedY != params.y) {
                params.x = clampedX
                params.y = clampedY
                try {
                    windowManager.updateViewLayout(container, params)
                } catch (_: Exception) {
                }
            }
            updateOverlayBoundsOnScreen()
        }
    }

    internal fun formatActiveStatusText(status: com.linewalk.autoad.engine.EngineUiStatus): String {
        if (!status.isRunning) {
            return "散步趣助手 · 待命"
        }

        val detail = status.statusDetail
        val safeDetail = status.overlaySafeDetail

        return when {
            detail.contains("外部保護") || safeDetail.contains("外部保護") || detail.contains("防護") -> {
                "[外部保護] 安全退出中"
            }
            detail.contains("每日賓果") || safeDetail.contains("每日賓果") || safeDetail.contains("賓果") -> {
                when {
                    detail.contains("開格") || safeDetail.contains("開格") -> "[每日賓果] 開格中"
                    detail.contains("連線") || detail.contains("Get") || safeDetail.contains("Get") -> "[每日賓果] 領取Get"
                    else -> "[每日賓果] 執行中"
                }
            }
            detail.contains("抽抽樂") || safeDetail.contains("抽抽樂") || detail.contains("抽獎") || safeDetail.contains("抽獎") -> {
                "[抽抽樂] 開啟中"
            }
            detail.contains("任務") || safeDetail.contains("任務") -> {
                "[任務] 領取中"
            }
            detail.contains("好友") || safeDetail.contains("好友") || detail.contains("送禮") || safeDetail.contains("送禮") -> {
                "[好友送禮] 贈送中"
            }
            detail.contains("收禮") || safeDetail.contains("收禮") || detail.contains("接收禮物") || safeDetail.contains("接收禮物") -> {
                "[接收禮物] 領取中"
            }
            status.state == com.linewalk.autoad.engine.EngineState.AD_WATCHING || safeDetail.contains("廣告") -> {
                val secMatch = Regex("""\((\d+)s?\)""").find(safeDetail) ?: Regex("""\((\d+)s?\)""").find(detail)
                if (secMatch != null) {
                    "[廣告監控] 播放中 (${secMatch.groupValues[1]}s)"
                } else {
                    "[廣告監控] 播放中"
                }
            }
            detail.contains("冷卻") || safeDetail.contains("冷卻") -> {
                "[巡邏中] 冷卻等待中"
            }
            detail.contains("首頁") || detail.contains("移動") -> "[巡邏中] 首頁"
            detail.contains("步數") -> "[巡邏中] 步數"
            detail.contains("時間") || detail.contains("放置") -> "[巡邏中] 時間"
            detail.contains("遊戲") -> "[巡邏中] 遊戲"
            status.state == com.linewalk.autoad.engine.EngineState.IN_GAME_SCAN -> "[巡邏中] 首頁"
            else -> "${status.state.shortTitle} (${status.adsCompletedCount})"
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingWidget() {
        val density = resources.displayMetrics.density

        val bgDrawable = GradientDrawable().apply {
            setColor(Color.parseColor("#F00F172A"))
            cornerRadius = 24f * density / 2f
            setStroke((2 * density).toInt(), Color.parseColor("#334155"))
        }
        this.bgDrawable = bgDrawable

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bgDrawable
            val padH = (14 * density).toInt()
            val padV = (10 * density).toInt()
            setPadding(padH, padV, padH, padV)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateOverlayBoundsOnScreen()
            }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        this.headerRow = headerRow

        val statusDot = ImageView(this).apply {
            setImageResource(R.drawable.ic_status_dot_slate)
            val dotSize = (8 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = (6 * density).toInt()
            }
        }
        this.statusDot = statusDot

        val titleTv = TextView(this).apply {
            text = "散步趣助手 · 待命"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            maxLines = 1
            ellipsize = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (6 * density).toInt()
            }
        }
        this.titleTv = titleTv

        val collapseBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_minimize)
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
            val btnSize = (22 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
        }
        this.collapseBtn = collapseBtn

        headerRow.addView(statusDot)
        headerRow.addView(titleTv)
        headerRow.addView(collapseBtn)
        container.addView(headerRow)

        val bodyLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * density).toInt()
            }
            layoutParams = params
        }
        this.bodyLayout = bodyLayout

        val detailTv = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
            isSingleLine = false
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (260 * density).toInt()
            val padB = (4 * density).toInt()
            setPadding(0, 0, 0, padB)
            visibility = View.GONE
        }
        bodyLayout.addView(detailTv)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun styleIconBtn(btn: Button, iconRes: Int, bgColor: String, textStr: String = "") {
            btn.text = textStr
            btn.setTextColor(Color.WHITE)
            btn.textSize = 11.5f
            btn.typeface = Typeface.DEFAULT_BOLD
            btn.isAllCaps = false
            btn.minHeight = 0
            btn.minimumHeight = 0
            btn.minWidth = 0
            btn.minimumWidth = 0

            val icon = ContextCompat.getDrawable(this, iconRes)?.mutate()
            val iconSize = (14 * density).toInt()
            icon?.setBounds(0, 0, iconSize, iconSize)
            btn.setCompoundDrawables(icon, null, null, null)
            btn.compoundDrawablePadding = if (textStr.isNotEmpty()) (4 * density).toInt() else 0

            val hPad = if (textStr.isNotEmpty()) (12 * density).toInt() else (8 * density).toInt()
            val vPad = (6 * density).toInt()
            btn.setPadding(hPad, vPad, hPad, vPad)
            btn.background = GradientDrawable().apply {
                setColor(Color.parseColor(bgColor))
                cornerRadius = 14f * density / 2f
            }
        }

        val toggleBtn = Button(this).apply {
            styleIconBtn(this, R.drawable.ic_play_arrow, "#059669", "開始")
            setOnClickListener {
                engine.toggle()
            }
        }

        val cropBtn = Button(this).apply {
            styleIconBtn(this, R.drawable.ic_crop, "#D97706", "取樣")
            setOnClickListener {
                launchTemplateCropper()
            }
        }

        val homeBtn = Button(this).apply {
            styleIconBtn(this, R.drawable.ic_settings, "#334155", "")
            setOnClickListener {
                val intent = Intent(this@FloatingOverlayService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            }
        }

        val closeOverlayBtn = Button(this).apply {
            styleIconBtn(this, R.drawable.ic_close, "#475569", "")
            setOnClickListener {
                engine.stop("使用者關閉懸浮窗")
                stopSelf()
            }
        }

        val marginParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = (8 * density).toInt()
        }

        btnRow.addView(toggleBtn)
        btnRow.addView(cropBtn, marginParams)
        btnRow.addView(homeBtn, marginParams)
        btnRow.addView(closeOverlayBtn, marginParams)
        bodyLayout.addView(btnRow)
        container.addView(bodyLayout)

        collapseBtn.setOnClickListener {
            setCollapsedState(!isCollapsed)
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
                    val displayMetrics = resources.displayMetrics
                    val viewW = container.width.takeIf { it > 0 } ?: container.measuredWidth.coerceAtLeast(1)
                    val viewH = container.height.takeIf { it > 0 } ?: container.measuredHeight.coerceAtLeast(1)
                    val maxX = (displayMetrics.widthPixels - viewW).coerceAtLeast(0)
                    val maxY = (displayMetrics.heightPixels - viewH).coerceAtLeast(0)

                    val deltaX = (event.rawX - touchX).toInt()
                    val deltaY = (event.rawY - touchY).toInt()
                    wmParams.x = (initialX + deltaX).coerceIn(0, maxX)
                    wmParams.y = (initialY + deltaY).coerceIn(0, maxY)
                    try {
                        windowManager.updateViewLayout(container, wmParams)
                        updateOverlayBoundsOnScreen()
                    } catch (_: Exception) {
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    updateOverlayBoundsOnScreen()
                    if (abs(event.rawX - touchX) < 12f && abs(event.rawY - touchY) < 12f) {
                        setCollapsedState(!isCollapsed)
                    }
                    true
                }

                else -> false
            }
        }

        layoutParams = wmParams
        rootView = container
        windowManager.addView(container, wmParams)
        container.post { updateOverlayBoundsOnScreen() }

        var wasRunning = false
        serviceScope.launch {
            engine.uiStatus.collectLatest { status ->
                if (status.isRunning && !wasRunning) {
                    setCollapsedState(true)
                }
                wasRunning = status.isRunning

                if (status.isRunning) {
                    statusDot.setImageResource(R.drawable.ic_status_dot_green)
                    bgDrawable.setStroke((2 * density).toInt(), Color.parseColor("#10B981"))
                    titleTv.text = formatActiveStatusText(status)

                    val cleanDetail = status.overlaySafeDetail
                        .replace("[完成]", "")
                        .replace("[巡邏]", "")
                        .replace("[防護]", "")
                        .replace("[廣告]", "")
                        .replace("[搜尋]", "")
                        .replace("[點擊]", "")
                        .replace("[載入]", "")
                        .replace("[倒數]", "")
                        .replace("[監控]", "")
                        .replace("[推廣]", "")
                        .replace("[遊戲]", "")
                        .replace("[目標]", "")
                        .replace("[抽獎]", "")
                        .replace("[任務]", "")
                        .replace("[切換]", "")
                        .replace("[禮物]", "")
                        .replace("[清單]", "")
                        .replace("[糖果]", "")
                        .replace("[金幣]", "")
                        .replace("[關閉]", "")
                        .replace("[介面]", "")
                        .replace("[提示]", "")
                        .replace("[警報]", "")
                        .replace("[成功]", "")
                        .replace("...", "")
                        .trim()

                    if (cleanDetail.isNotEmpty()) {
                        detailTv.text = cleanDetail
                        detailTv.visibility = View.VISIBLE
                    } else {
                        detailTv.visibility = View.GONE
                    }
                    styleIconBtn(toggleBtn, R.drawable.ic_pause, "#DC2626", "暫停")
                } else {
                    statusDot.setImageResource(R.drawable.ic_status_dot_slate)
                    bgDrawable.setStroke((2 * density).toInt(), Color.parseColor("#334155"))
                    titleTv.text = "散步趣助手 · 待命"
                    detailTv.visibility = View.GONE
                    styleIconBtn(toggleBtn, R.drawable.ic_play_arrow, "#059669", "開始")
                }
                container.post { updateOverlayBoundsOnScreen() }
            }
        }
    }

    fun setPassthrough(enable: Boolean) {
        val v = rootView ?: return
        val lp = layoutParams ?: return
        if (enable) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try {
            windowManager.updateViewLayout(v, lp)
        } catch (_: Exception) {}
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
                        engine.appendLog("[取樣] 新增圖示範本: ${savedCategory.displayName}")
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

        /** 若目標點擊座標剛好被使用者拖曳的懸浮窗蓋住，設定 FLAG_NOT_TOUCHABLE 並暫時隱藏懸浮窗，確保手勢 100% 直達底層遊戲 */
        suspend fun clickWithOverlayPassthrough(targetRect: Rect, clickBlock: suspend () -> Unit) {
            val inst = activeInstance
            val v = inst?.rootView
            val overlaps = !overlayScreenBounds.isEmpty && Rect.intersects(overlayScreenBounds, targetRect)
            if (overlaps && inst != null && v != null) {
                withContext(Dispatchers.Main) {
                    inst.setPassthrough(true)
                    v.visibility = View.INVISIBLE
                }
                delay(100L)
                try {
                    clickBlock()
                    delay(120L)
                } finally {
                    withContext(Dispatchers.Main) {
                        inst.setPassthrough(false)
                        v.visibility = View.VISIBLE
                        v.post { inst.updateOverlayBoundsOnScreen() }
                    }
                }
            } else {
                clickBlock()
            }
        }
    }
}
