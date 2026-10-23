package com.linewalk.autoad.engine

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.config.TemplateCategory
import com.linewalk.autoad.config.TemplateStorage
import com.linewalk.autoad.service.AutoAdAccessibilityService
import com.linewalk.autoad.tasks.BingoTask
import com.linewalk.autoad.tasks.FriendGiftTask
import com.linewalk.autoad.tasks.GachaTask
import com.linewalk.autoad.tasks.GameTask
import com.linewalk.autoad.tasks.IdleRewardTask
import com.linewalk.autoad.tasks.LotteryTask
import com.linewalk.autoad.tasks.MissionTask
import com.linewalk.autoad.tasks.MoveGaugeTask
import com.linewalk.autoad.tasks.ReceiveGiftTask
import com.linewalk.autoad.tasks.StepBottleTask
import com.linewalk.autoad.ui.FloatingOverlayService
import com.linewalk.autoad.vision.NodeInspector
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.TemplateMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

enum class EngineState(val title: String, val shortTitle: String) {
    IDLE("已暫停待命", "待命"),
    IN_GAME_SCAN("1. 掃描遊戲任務中", "1.巡邏中"),
    TRIGGER_AD_POPUP("2. 尋找看廣告按鈕", "2.點彈窗"),
    AD_WATCHING("3. 觀看廣告與關閉偵測", "3.廣告監控"),
    REWARD_CLAIM("4. 確認領取獎勵", "4.完成確認")
}

data class EngineUiStatus(
    val isRunning: Boolean = false,
    val state: EngineState = EngineState.IDLE,
    val currentRound: Int = 1,
    val statusDetail: String = "點擊懸浮窗 [開始] 啟動自動看廣告",
    val overlaySafeDetail: String = "點擊開始啟動自動巡邏",
    val adsCompletedCount: Int = 0,
    val tasksTriggeredCount: Int = 0,
    val recentLogs: List<String> = emptyList()
)

sealed class PopupActionTarget(val label: String, val bounds: Rect) {
    class CandySkipAd(label: String, bounds: Rect) : PopupActionTarget(label, bounds)
    class WatchAd(label: String, bounds: Rect) : PopupActionTarget(label, bounds)
}

enum class MainTab(
    val tabIndex: Int,
    val tabName: String,
    val shortLabel: String,
    val xRatio: Float,
    val yRatio: Float = 0.115f
) {
    MOVE(0, "首頁(移動)", "移動", 0.125f),
    STEP(1, "步數", "步數", 0.375f),
    TIME(2, "時間(放置)", "時間", 0.625f),
    GAME(3, "遊戲", "遊戲", 0.875f);

    fun isEnabled(config: AppConfig): Boolean = when (this) {
        MOVE -> config.enableMoveGaugeTask
        STEP -> config.enableStepBottleTask
        TIME -> config.enableIdleRewardTask
        GAME -> config.enableGachaTask
    }

    companion object {
        fun fromIndex(idx: Int): MainTab? = entries.firstOrNull { it.tabIndex == idx }
    }
}

enum class PatrolPhase(val title: String) {
    MAIN_TABS("四大分頁巡邏"),
    BINGO("每日賓果"),
    LOTTERY("抽抽樂"),
    MISSION("任務"),
    FRIEND_GIFT("好友送禮"),
    RECEIVE_GIFT("接收好友禮物"),
    COMPLETED("全任務完成")
}

class AutomationEngine private constructor(private val context: Context) {

    val config = AppConfig(context)
    val templateStorage = TemplateStorage(context)

    private val nodeInspector = NodeInspector()
    private val ocrRecognizer = OcrRecognizer()
    private val templateMatcher = TemplateMatcher()
    private val adCloserHandler = AdCloserHandler(
        config = config,
        templateStorage = templateStorage,
        nodeInspector = nodeInspector,
        ocrRecognizer = ocrRecognizer,
        templateMatcher = templateMatcher
    )

    private val stepTask = StepBottleTask()
    private val moveTask = MoveGaugeTask()
    private val timeTask = IdleRewardTask()
    private val gameTask = GachaTask()
    private val bingoTask = BingoTask()
    private val lotteryTask = LotteryTask()
    private val missionTask = MissionTask()
    private val receiveGiftTask = ReceiveGiftTask()
    private val friendGiftTask = FriendGiftTask()

    /**
     * 嚴格分頁巡邏優先順序：
     * 1. 移動 (首頁) ➔ 2. 時間 (放置) ➔ 3. 遊戲 ➔ 4. 步數
     */
    private val tabPriorityOrder = listOf(MainTab.MOVE, MainTab.TIME, MainTab.GAME, MainTab.STEP)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loopJob: Job? = null

    private var popupRetryTicks = 0
    private var rewardClaimRetryTicks = 0
    private var adWatchdogCooldownTicks = 0
    enum class GuardAction {
        ALLOW_IN_GAME_SCAN,
        SAFE_BACK_NAVIGATION,
        CLICK_AD_CLOSE_X,
        BLOCK_REGULAR_CLICK
    }

    data class GuardDecision(
        val isExternal: Boolean,
        val recommendedAction: GuardAction,
        val regularClicksAllowed: Boolean
    )

    data class CooldownResult(
        val completedRound: Int,
        val nextRound: Int,
        val wasPausedDuringCooldown: Boolean,
        val cooldownSecondsWaited: Int
    )

    var currentRound: Int = 1
        internal set

    var currentTab: MainTab = MainTab.MOVE
        internal set
    private var needsInitialTabSync = true
    private var tabSwitchRetries = 0
    private var emptyTicksOnCurrentTab = 0
    val confirmedEmptyTabs = mutableSetOf<MainTab>()

    var currentPhase: PatrolPhase = PatrolPhase.MAIN_TABS
        internal set
    var isBingoCompleted = false
        internal set
    var isLotteryCompleted = false
        internal set
    private var hasClickedLotteryAdReward = false
    private var lotteryResultRewardWaitTicks = 0
    var isMissionCompleted = false
        internal set
    var isReceiveGiftCompleted = false
        internal set
    var isFriendGiftCompleted = false
        internal set
    private val checkedMissionTabs = mutableSetOf<String>()
    private val missionTabSequence = listOf("特別", "每日", "每週")
    private var friendGiftEmptyTicks = 0
    private var friendGiftReturnHomeTicks = 0
    private var friendGiftOpenDrawerTicks = 0
    internal val claimedBingoColumns = mutableSetOf<Int>()
    internal val bingoColumnRetryCount = mutableMapOf<Int, Int>()
    private var currentPhaseEmptyTicks = 0
    internal var nonGameScreenTicks = 0

    private val _uiStatus = MutableStateFlow(EngineUiStatus())
    val uiStatus: StateFlow<EngineUiStatus> = _uiStatus.asStateFlow()

    fun evaluateForegroundPackage(
        fgPkg: String?,
        hasAdGeometricX: Boolean = false,
        isWatchingAd: Boolean = false
    ): GuardDecision {
        // 前景未知（系統短暫無焦點）或本助手 App 自身在前景：不點擊、不返回，原地等待
        if (fgPkg.isNullOrBlank() || fgPkg == context.packageName) {
            return GuardDecision(
                isExternal = false,
                recommendedAction = GuardAction.BLOCK_REGULAR_CLICK,
                regularClicksAllowed = false
            )
        }

        // 僅《LINE 散步趣》本體 (com.linecorp.LGWKTW, com.linecorp.LGWALK) 視為遊戲內；
        // 若前景為已知第三方官方廣告服務容器 (Meta com.facebook.katana, Google com.google.android.gms 等)，
        // 永遠視為合法廣告畫面，嚴禁判定為外部應用！
        val isAd = AppConfig.isAdPackage(fgPkg)
        val isTarget = AppConfig.isGamePackage(fgPkg) || isAd ||
            (fgPkg in config.targetGamePackages && fgPkg != "jp.naver.line.android")
        return if (!isTarget) {
            val action = if (hasAdGeometricX) GuardAction.CLICK_AD_CLOSE_X else GuardAction.SAFE_BACK_NAVIGATION
            GuardDecision(
                isExternal = true,
                recommendedAction = action,
                regularClicksAllowed = false
            )
        } else {
            GuardDecision(
                isExternal = false,
                recommendedAction = GuardAction.ALLOW_IN_GAME_SCAN,
                regularClicksAllowed = true
            )
        }
    }

    fun start(resetRound: Boolean = false) {
        if (loopJob?.isActive == true) return
        val accService = AutoAdAccessibilityService.instance.value
        if (accService == null) {
            appendLog("[提示] 無障礙服務尚未啟用，請先至主畫面開啟權限")
            return
        }

        if (resetRound) {
            currentRound = 1
        }
        popupRetryTicks = 0
        rewardClaimRetryTicks = 0
        nonGameScreenTicks = 0
        emptyTicksOnCurrentTab = 0
        confirmedEmptyTabs.clear()
        isBingoCompleted = false
        claimedBingoColumns.clear()
        bingoColumnRetryCount.clear()
        isLotteryCompleted = false
        hasClickedLotteryAdReward = false
        lotteryResultRewardWaitTicks = 0
        isMissionCompleted = false
        isReceiveGiftCompleted = false
        isFriendGiftCompleted = false
        checkedMissionTabs.clear()
        friendGiftEmptyTicks = 0
        friendGiftReturnHomeTicks = 0
        friendGiftOpenDrawerTicks = 0
        friendGiftTask.resetSession()
        currentPhaseEmptyTicks = 0
        currentTab = tabPriorityOrder.firstOrNull { it.isEnabled(config) } ?: MainTab.MOVE
        needsInitialTabSync = true
        tabSwitchRetries = 0

        currentPhase = if (tabPriorityOrder.any { it.isEnabled(config) }) {
            PatrolPhase.MAIN_TABS
        } else {
            getNextPhase(PatrolPhase.MAIN_TABS)
        }

        val phaseDesc = when (currentPhase) {
            PatrolPhase.MAIN_TABS -> "移動 ➔ 時間 ➔ 遊戲 ➔ 步數"
            PatrolPhase.BINGO -> "每日賓果"
            PatrolPhase.LOTTERY -> "抽抽樂"
            PatrolPhase.MISSION -> "任務"
            PatrolPhase.RECEIVE_GIFT -> "接收好友禮物"
            PatrolPhase.FRIEND_GIFT -> "好友送禮"
            PatrolPhase.COMPLETED -> "已完成"
        }

        transitionTo(
            newState = EngineState.IN_GAME_SCAN,
            detail = "全自動巡邏已啟動，首發階段：【$phaseDesc】...",
            safeOverlayText = "[巡邏] [$phaseDesc]..."
        )
        _uiStatus.value = _uiStatus.value.copy(
            isRunning = true,
            currentRound = currentRound
        )

        loopJob = scope.launch {
            while (isActive) {
                try {
                    executeSingleTick()
                } catch (e: Exception) {
                    appendLog("[錯誤] 循環發生例外: ${e.message}")
                }
                delay(config.loopIntervalMs)
            }
        }
    }

    fun startPatrol(resetRound: Boolean = false) {
        start(resetRound = resetRound)
    }

    fun stop(reason: String = "使用者手動暫停") {
        loopJob?.cancel()
        loopJob = null
        _uiStatus.value = _uiStatus.value.copy(
            isRunning = false,
            state = EngineState.IDLE,
            statusDetail = reason,
            overlaySafeDetail = "[待命] $reason"
        )
        appendLog("[待命] $reason")
    }

    fun toggle() {
        if (_uiStatus.value.isRunning) {
            stop()
        } else {
            start()
        }
    }

    private suspend fun executeSingleTick() {
        val service = AutoAdAccessibilityService.instance.value
        if (service == null) {
            stop("無障礙服務已中斷")
            return
        }

        var currentState = _uiStatus.value.state
        val fgPkg = service.getForegroundPackageName()

        // 0. 第三方官方廣告容器優先處理 (Meta Audience Network, Google AdMob, Unity, AppLovin, Chrome Custom Tabs 等)：
        // 當畫面處於任何合法廣告容器套件時，無條件判定為合法廣告播放，
        // 絕對不可判定為「外部應用」而發送返回鍵提早退出！
        // 若當前狀態尚未轉入 AD_WATCHING，立即無縫切換至 AD_WATCHING 並立即於本 Tick 執行廣告監控！
        if (AppConfig.isAdPackage(fgPkg)) {
            nonGameScreenTicks = 0
            if (currentState != EngineState.AD_WATCHING) {
                appendLog("[廣告] 偵測到第三方廣告容器 ($fgPkg)，自動無縫切換至廣告監控模式")
                adCloserHandler.onEnterAdMode()
                transitionTo(
                    newState = EngineState.AD_WATCHING,
                    detail = "已進入廣告畫面 ($fgPkg)，安全監控中...",
                    safeOverlayText = "[廣告] 監控中..."
                )
                currentState = EngineState.AD_WATCHING
            }
        }

        val guardDecision = evaluateForegroundPackage(
            fgPkg = fgPkg,
            isWatchingAd = true
        )

        if (guardDecision.isExternal) {
            handleExternalAppGuard(service, fgPkg)
            return
        } else if (!guardDecision.regularClicksAllowed) {
            updateDetail("[等待] 等待《LINE 散步趣》回到前景...", "[等待] 等待散步趣前景")
            return
        } else {
            nonGameScreenTicks = 0
        }
        val screenBitmap = service.captureScreenBitmap()
        if (screenBitmap == null) {
            updateDetail("[提示] 無法擷取螢幕畫面，等待重試...", "[提示] 等待畫面擷取...")
            return
        }

        try {
            val ocrItems = ocrRecognizer.recognize(screenBitmap)

            when (currentState) {
                EngineState.IDLE -> Unit
                EngineState.IN_GAME_SCAN -> handleInGameScan(service, screenBitmap, ocrItems)
                EngineState.TRIGGER_AD_POPUP -> handleTriggerAdPopup(service, screenBitmap, ocrItems)
                EngineState.AD_WATCHING -> handleAdWatching(service, screenBitmap, ocrItems)
                EngineState.REWARD_CLAIM -> handleRewardClaim(service, screenBitmap, ocrItems)
            }
        } finally {
            screenBitmap.recycle()
        }
    }

    private suspend fun handleExternalAppGuard(
        service: AutoAdAccessibilityService,
        fgPkg: String?
    ) {
        nonGameScreenTicks++
        val externalPkg = fgPkg ?: "未知外部應用"
        val safePkgLabel = if (externalPkg.length > 20) externalPkg.takeLast(20) else externalPkg

        appendLog("[防護] 偵測到非散步趣應用 ($externalPkg)，啟動嚴格防誤點保護 (連續 ${nonGameScreenTicks} 次)")
        _uiStatus.value = _uiStatus.value.copy(
            statusDetail = "[防護] 前景非散步趣 ($externalPkg)",
            overlaySafeDetail = "[外部保護] 非散步趣App ($safePkgLabel)"
        )

        // 逾時復原：連續外部應用超過 5 個 tick (約 6~8 秒)，強制將散步趣喚醒拉回前景
        if (nonGameScreenTicks >= 5) {
            appendLog("[防護] 停留外部應用達 5 次 tick 逾時，嘗試強制喚醒散步趣拉回前景！")
            val targetPkg = config.targetGamePackages.firstOrNull { it != "jp.naver.line.android" } ?: "com.linecorp.LGWKTW"
            service.bringAppToFront(targetPkg)
            nonGameScreenTicks = 0
            return
        }

        // 擷取畫面檢查是否有廣告 (X) 關閉鈕
        val screenBitmap = service.captureScreenBitmap()
        if (screenBitmap != null) {
            try {
                val sw = screenBitmap.width
                val sh = screenBitmap.height

                // 行為 1: 檢查無障礙節點樹是否有名確廣告關閉節點
                val rootNode = service.getSafeRootNode()
                val closeNode = nodeInspector.findAdCloseNode(rootNode, sw, sh, isFullScreenAd = true)
                if (closeNode != null) {
                    appendLog("[防護] 外部畫面偵測到廣告關閉按鈕 (${closeNode.label})，安全點擊關閉")
                    FloatingOverlayService.clickWithOverlayPassthrough(closeNode.bounds) {
                        service.clickRect(closeNode.bounds)
                    }
                    if (_uiStatus.value.state != EngineState.AD_WATCHING) {
                        adCloserHandler.onEnterAdMode(preserveRedirectState = true)
                        transitionTo(EngineState.AD_WATCHING, "外部廣告點擊關閉，監控廣告中...", "[廣告] 廣告監控中...")
                    }
                    return
                }

                // 行為 2: 檢查角落幾何 X 關閉鈕 (排除頂部安全禁區 y < sh * 0.08f)
                val geoMatch = templateMatcher.detectCornerGeometricX(
                    screenBitmap = screenBitmap,
                    blacklistedCoord = { x, y ->
                        FloatingOverlayService.isInsideOverlay(x, y) ||
                        y < screenBitmap.height * 0.08f
                    },
                    isRelaxed = false
                )
                if (geoMatch != null) {
                    appendLog("[防護] 外部畫面偵測到幾何廣告關閉鈕 (${geoMatch.label})，安全點擊關閉")
                    FloatingOverlayService.clickWithOverlayPassthrough(geoMatch.bounds) {
                        service.clickRect(geoMatch.bounds)
                    }
                    if (_uiStatus.value.state != EngineState.AD_WATCHING) {
                        adCloserHandler.onEnterAdMode(preserveRedirectState = true)
                        transitionTo(EngineState.AD_WATCHING, "外部廣告點擊關閉，監控廣告中...", "[廣告] 廣告監控中...")
                    }
                    return
                }
            } finally {
                screenBitmap.recycle()
            }
        }

        // 行為 3: 若無廣告關閉鈕，發送安全返回鍵退出外部應用
        appendLog("[防護] 前景非散步趣 ($externalPkg) 且無廣告關閉鈕，發送安全返回鍵退出外部 App")
        service.safeNavigateBack()
        if (AppConfig.isRedirectPackage(fgPkg)) {
            adCloserHandler.markRecentRedirect()
            if (_uiStatus.value.state != EngineState.AD_WATCHING) {
                adCloserHandler.onEnterAdMode(preserveRedirectState = true)
                transitionTo(EngineState.AD_WATCHING, "已關閉外部套件，監控廣告中...", "[廣告] 廣告監控中...")
            }
        }
    }

    /**
     * 檢查是否彈出「玩遊戲賺點數 / 免費參加」推廣宣傳頁，並自動定位底部的「跳過」文字按鈕 (y ≈ 0.88f)
     */
    private fun findPlayGamePromoSkipButton(
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ): Rect? {
        val hasPromoContent = ocrItems.any { item ->
            val t = item.text
            t.contains("挑選喜歡的遊戲") || t.contains("同意並繼續")
        }
        if (!hasPromoContent) return null

        // 1. OCR 尋找「跳過」或「略過」
        val skipItem = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.80f).toInt()..(sh * 0.96f).toInt() &&
            (item.text.contains("跳過") || item.text.contains("略過"))
        }
        if (skipItem != null) return skipItem.bounds

        // 2. 藍色文字色塊辨識：搜尋底部中央「跳過」文字 (y ≈ 0.86f..0.94f, x ≈ 0.35f..0.65f)
        val bluePoints = mutableListOf<android.graphics.Point>()
        val y1 = (sh * 0.86f).toInt().coerceIn(0, sh - 1)
        val y2 = (sh * 0.94f).toInt().coerceIn(y1 + 1, sh)
        val x1 = (sw * 0.35f).toInt().coerceIn(0, sw - 1)
        val x2 = (sw * 0.65f).toInt().coerceIn(x1 + 1, sw)

        var y = y1
        while (y < y2) {
            var x = x1
            while (x < x2) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (b > 140 && b > r + 40 && b > g + 40) {
                    bluePoints.add(android.graphics.Point(x, y))
                }
                x += 2
            }
            y += 2
        }

        if (bluePoints.size >= 15) {
            val minX = bluePoints.minOf { it.x }
            val maxX = bluePoints.maxOf { it.x }
            val minY = bluePoints.minOf { it.y }
            val maxY = bluePoints.maxOf { it.y }
            return Rect(minX, minY, maxX, maxY)
        }

        // 3. 固定熱區 Fallback
        return Rect(
            (sw * 0.38f).toInt(),
            (sh * 0.88f).toInt(),
            (sw * 0.62f).toInt(),
            (sh * 0.94f).toInt()
        )
    }

    /**
     * R1 多重條件嚴格驗證「選單/設定」畫面 (isVerifiedMenuScreen)：
     * 1. 嚴格排除每日賓果彈窗 (isBingoDialogOpen)
     * 2. 嚴格排除主畫面頂部分頁 (首頁/步數/時間/遊戲)
     * 3. 嚴格排除主畫面底部導覽列 (集點/好友/使用/精選)
     * 4. 必須具備安全區域以下的選單標題 (cy in 0.08f..0.22f) 與設定項目，或具備至少 2 個相異設定項
     */
    fun isVerifiedMenuScreen(ocrItems: List<OcrItem>, sw: Int, sh: Int): Boolean {
        // 1. 排除賓果彈窗
        if (bingoTask.isBingoDialogOpen(ocrItems, sh)) return false

        // 2. 排除主畫面頂部分頁 (cy in 0.08f..0.20f)
        val hasMainTabs = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            cy in 0.08f..0.20f && (
                item.text.contains("首頁") || item.text.contains("步數") ||
                item.text.contains("時間") || item.text.contains("遊戲")
            )
        }
        if (hasMainTabs) return false

        // 3. 排除底部導覽列 (cy > 0.85f)
        val hasBottomNav = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            cy > 0.85f && (
                item.text.contains("集點") || item.text.contains("好友") ||
                item.text.contains("使用") || item.text.contains("精選")
            )
        }
        if (hasBottomNav) return false

        // 4. 選單標題 (嚴格位於安全禁區以下 cy in 0.08f..0.22f)
        val hasMenuTitle = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            val cy = item.bounds.centerY().toFloat() / sh
            cy in 0.08f..0.22f && (t == "選單" || t == "設定" || t.equals("SETTINGS", ignoreCase = true))
        }

        // 5. 設定清單項目關鍵字
        val settingKeywords = listOf(
            "應用程式設定", "帳號", "提醒", "關於", "條款",
            "關閉動畫效果", "省電模式", "位置資訊設定", "目前版本",
            "常見問題", "服務條款", "隱私權政策", "登出"
        )
        val matchedSettingKeywords = settingKeywords.filter { kw ->
            ocrItems.any { it.text.replace(" ", "").contains(kw) }
        }

        return (hasMenuTitle && matchedSettingKeywords.isNotEmpty()) || matchedSettingKeywords.size >= 2
    }

    private suspend fun handleInGameScan(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 0A. 防外部瀏覽器/商店殘留：若目前前景停留在外部跳轉網頁或 Chrome Custom Tabs，立即按 BACK 並切換至廣告監控
        val fgPkg = service.getForegroundPackageName()
        val isExternal = fgPkg != null && AppConfig.isRedirectPackage(fgPkg)
        val hasBrowserTitle = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            cy < sh * 0.16f && (
                item.text.contains("正在載入") ||
                item.text.contains("klook") ||
                item.text.contains(".com") ||
                item.text.contains("http") ||
                item.text.contains("www.")
            )
        }
        if (isExternal || hasBrowserTitle) {
            appendLog("[防護] 偵測到外部瀏覽器/跳轉網頁 (${fgPkg ?: "InAppBrowser"})，自動按返回鍵關閉並啟動廣告監控")
            adCloserHandler.markRecentRedirect()
            service.navigateBack()
            adCloserHandler.onEnterAdMode(preserveRedirectState = true)
            transitionTo(EngineState.AD_WATCHING, "已關閉外部網頁，監控廣告中...", "[廣告] 監控中...")
            return
        }

        // 0. 防誤觸 AdChoices『ⓘ ▷』彈出的『Hide Ad / Report Ad / Why am I seeing this ad?』選單檢查
        if (ocrRecognizer.isAdChoicesMenuOpen(ocrItems)) {
            val backdropRect = Rect(
                (sw * 0.40f).toInt(),
                (sh * 0.25f).toInt(),
                (sw * 0.60f).toInt(),
                (sh * 0.35f).toInt()
            )
            appendLog("[防護] 偵測到『Hide Ad / Report Ad』選單，自動點擊上方背景關閉並繼續監控廣告")
            service.clickRect(backdropRect)
            adCloserHandler.onEnterAdMode()
            transitionTo(
                EngineState.AD_WATCHING,
                "已關閉 Hide Ad 選單，繼續等待廣告結束...",
                "[廣告] 監控廣告中..."
            )
            return
        }

        // 0-1. 檢查是否正在向廣告伺服器載入廣告（畫面中央『載入中...』與白色 [取消] 鈕），直接進入廣告監控流程！
        if (ocrRecognizer.isAdLoadingScreen(ocrItems)) {
            appendLog("⏳ 畫面處於廣告載入中狀態，自動轉入廣告監控流程！")
            adCloserHandler.onEnterAdMode()
            transitionTo(EngineState.AD_WATCHING, "廣告正在載入中，請稍候...", "⏳ 廣告載入中...")
            return
        }

        // 0A. 檢查是否誤入「選單」(設定) 畫面，採用多重條件驗證並安全發送返回鍵退出選單！
        if (isVerifiedMenuScreen(ocrItems, sw, sh)) {
            appendLog("[介面] 偵測到經多重驗證之【選單/設定】畫面，安全返回主畫面")
            service.safeNavigateBack(allowInGame = true)
            updateDetail("[介面] 正在退出【選單】畫面...", "[介面] 退出選單中...")
            return
        }

        // 0. 檢查是否誤入「輕鬆賺」(adjoe) 服務條款或遊戲推薦頁，安全返回主畫面！
        val isAdjoeTermsOrOfferwall = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("想體驗更多遊戲") ||
            t.contains("解鎖更多遊戲") ||
            t.contains("探索最熱門遊戲") ||
            t.contains("開始玩吧") ||
            (t.contains("輕鬆賺") && (t.contains("服務條款") || t.contains("條款與條件") || t.contains("使用條款") || t.contains("隱私權政策")))
        }
        if (isAdjoeTermsOrOfferwall) {
            appendLog("[防護] 偵測到【輕鬆賺】服務條款/推薦頁，發送安全返回鍵退出")
            service.safeNavigateBack(allowInGame = true)
            updateDetail("[防護] 正在退出【輕鬆賺】廣告頁面...", "[防護] 退出廣告頁中...")
            return
        }

        // A. 最優先檢查：是否停留在看完廣告後的『獲得15張票券！ / 獲得金幣！』中央彈窗（下方有白色 [ 關閉 ] 按鈕）！
        val modalCloseBtn = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)
        if (modalCloseBtn != null) {
            emptyTicksOnCurrentTab = 0
            bingoColumnRetryCount.clear()
            appendLog("[完成] 點擊領獎完成彈窗: ${modalCloseBtn.first}")
            FloatingOverlayService.clickWithOverlayPassthrough(modalCloseBtn.second) {
                service.clickRect(modalCloseBtn.second)
            }
            updateDetail(
                "[完成] 已點掉領獎完成 [關閉] 按鈕，準備檢查下一項...",
                "[完成] 已點關閉，繼續檢查..."
            )
            return
        }

        // A0. 最優先檢查是否跳出 LINE 內部宣傳促銷全螢幕廣告（如『邀請好友 快來領取吧！！』『還沒有邀請好友 實在太可惜了！』）
        if (ocrRecognizer.isLineInHousePromoAd(ocrItems)) {
            nonGameScreenTicks = 0
            val closeBtn = ocrRecognizer.getLineInHousePromoCloseButton(sw, sh)
            appendLog("[廣告] 偵測到 LINE 宣傳廣告/促銷彈窗，點擊右上角 (X) 關閉視窗")
            updateDetail("[關閉] 正在關閉宣傳廣告視窗...", "[關閉] 關閉宣傳廣告中...")
            FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                service.clickRect(closeBtn)
            }
            delay(500)
            return
        }

        // B. 檢查四大分頁下半部是否彈出領獎彈窗（優先點可用的『跳過廣告(糖果)』，其次點『紅利 15/60/x1 獲得』）！
        val popupAction = findPopupActionTarget(screenBitmap, ocrItems)
        if (popupAction != null) {
            executePopupAction(service, popupAction)
            return
        }
        val cancelBtn = ocrRecognizer.findPopupCancelButton(screenBitmap, ocrItems, templateMatcher)
        if (cancelBtn != null) {
            handlePopupCooldownCancel(service, cancelBtn, sw, sh)
            return
        }

        // C. 檢查是否彈出「玩遊戲賺點數 / 免費參加」推廣宣傳頁，自動點擊底部「跳過」
        val promoSkip = findPlayGamePromoSkipButton(screenBitmap, ocrItems, sw, sh)
        if (promoSkip != null) {
            appendLog("[遊戲] 偵測到【玩遊戲賺點數】推廣頁，自動點擊底部 [跳過]！")
            FloatingOverlayService.clickWithOverlayPassthrough(promoSkip) {
                service.clickRect(promoSkip)
            }
            return
        }

        // D. 【子視窗與日常任務優先】優先檢查是否處於日常任務子彈窗或好友分頁（絕不可被底層導覽列誤判為主畫面而忽略已開啟的視窗！）：
        // D0. 檢查是否跳出 LINE 內部宣傳促銷全螢幕廣告（如『還沒有邀請好友 實在太可惜了！』『獲得金幣的流程』）
        if (ocrRecognizer.isLineInHousePromoAd(ocrItems)) {
            nonGameScreenTicks = 0
            val closeBtn = ocrRecognizer.getLineInHousePromoCloseButton(sw, sh)
            appendLog("[廣告] 偵測到 LINE 宣傳廣告/促銷彈窗，點擊右上角 (X) 關閉視窗")
            updateDetail("[關閉] 正在關閉宣傳廣告視窗...", "[關閉] 關閉宣傳廣告中...")
            FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                service.clickRect(closeBtn)
            }
            delay(500)
            return
        }

        // D1. 優先檢查是否正在「每日賓果」彈窗
        if (bingoTask.isBingoDialogOpen(ocrItems, sh)) {
            nonGameScreenTicks = 0
            handleBingoDialog(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // D2. 檢查是否正在「抽抽樂」彈窗
        if (lotteryTask.isLotteryDialogOpen(ocrItems, sh)) {
            nonGameScreenTicks = 0
            handleLotteryDialog(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // D3. 檢查是否正在「任務」彈窗
        if (missionTask.isMissionDialogOpen(ocrItems, sh, screenBitmap)) {
            nonGameScreenTicks = 0
            if (isMissionCompleted) {
                val closeBtn = missionTask.getCloseButtonBounds(sw, sh)
                FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                    service.clickRect(closeBtn)
                }
                delay(350)
                return
            }
            handleMissionDialog(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // D4. 檢查是否正在「接收禮物」彈窗
        if (receiveGiftTask.isReceiveGiftDialogOpen(ocrItems, sh)) {
            nonGameScreenTicks = 0
            handleReceiveGiftDialog(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // D5. 檢查是否正在底部導覽列「好友」頁面
        if (friendGiftTask.isFriendsPage(ocrItems, sh)) {
            nonGameScreenTicks = 0
            if (currentPhase != PatrolPhase.FRIEND_GIFT && currentPhase != PatrolPhase.RECEIVE_GIFT) {
                appendLog("[切換] 巡邏起始於【好友】分頁，先切換回底部【集點】以執行【首頁(移動)】巡邏！")
                updateDetail("[切換] 切換回集點首頁...", "[切換] 回首頁...")
                val mainTabBtn = friendGiftTask.getBottomMainTabRect(sw, sh, ocrItems)
                FloatingOverlayService.clickWithOverlayPassthrough(mainTabBtn) {
                    service.clickRect(mainTabBtn)
                }
                delay(600)
                return
            }
            handleFriendsPage(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // E. 判斷目前是否在乾淨的《LINE 散步趣》主畫面（涵蓋『首頁(移動)』『時間』『遊戲』『步數』四大分頁）
        // 只要在主畫面，立即交由 handleMainScreenPatrol 進行分頁切換與常規巡邏！
        val onMainScreen = ocrRecognizer.isLineWalkMainScreenVisible(screenBitmap, ocrItems, templateMatcher)
        if (onMainScreen) {
            nonGameScreenTicks = 0
            handleMainScreenPatrol(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // D. 若不在主畫面（代表有宣傳視窗如『邀請好友』、『玩遊戲賺點數 -> 跳過』、領獎確認窗或全螢幕廣告）：
        // D1. 短字按鈕 OCR（跳過 / 略過 / 確認 / 確定 / 關閉，字數 <= 6，覆蓋到 y < 0.94f）
        val dismissOrSkipOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.GAME_CLAIM_CONFIRM_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.05f, 0.04f, 0.95f, 0.94f),
            excludeKeywords = OcrRecognizer.GAME_CLAIM_EXCLUDE_KEYWORDS,
            maxTextLength = 6
        )
        if (dismissOrSkipOcr != null) {
            nonGameScreenTicks = 0
            appendLog("[關閉] 自動點擊彈窗 [${dismissOrSkipOcr.text}]")
            FloatingOverlayService.clickWithOverlayPassthrough(dismissOrSkipOcr.bounds) {
                service.clickRect(dismissOrSkipOcr.bounds)
            }
            return
        }

        // D2. 無障礙節點樹關閉按鈕
        val closeNode = nodeInspector.findAdCloseNode(
            root = service.getSafeRootNode(),
            screenWidth = sw,
            screenHeight = sh,
            blacklistedCoord = { x, y ->
                FloatingOverlayService.isInsideOverlay(x, y) || (y in (sh * 0.12f).toInt()..(sh * 0.36f).toInt())
            }
        )
        if (closeNode != null) {
            nonGameScreenTicks = 0
            appendLog("[關閉] 自動點擊關閉節點: ${closeNode.label}")
            service.clickRect(closeNode.bounds)
            return
        }

        // D3. 幾何角落 X 偵測器（如『邀請好友』右上角 X，或全螢幕廣告左上/右上角 X！）
        // R2: 處於每日賓果彈窗時，嚴格禁止幾何 X 誤關閉賓果！
        if (!bingoTask.isBingoDialogOpen(ocrItems, sh)) {
            val geoX = templateMatcher.detectCornerGeometricX(
                screenBitmap = screenBitmap,
                blacklistedCoord = { x, y ->
                    FloatingOverlayService.isInsideOverlay(x, y) ||
                    y < sh * 0.08f
                }
            )
            if (geoX != null) {
                nonGameScreenTicks = 0
                appendLog("[關閉] 偵測到全螢幕宣傳頁/彈窗角落關閉鈕 (${geoX.label})，自動點擊關閉！")
                FloatingOverlayService.clickWithOverlayPassthrough(geoX.bounds) {
                    service.clickRect(geoX.bounds)
                }
                updateDetail("[關閉] 已點擊關閉按鈕 (${geoX.label})，返回遊戲...", "[關閉] 關閉彈窗中...")
                return
            }
        }

        // D4. 檢查是否處於下半部真正領獎彈窗（必須同時具備彈窗標題與取消鈕，嚴禁因底層廣告粉紅像素誤判）
        val hasBottomModalTitle = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy in 0.52f..0.75f &&
                !t.contains("輕鬆") && !t.contains("簡單") && !t.contains("遊玩") && !t.contains("Sponsored") && !t.contains("AD") &&
                (t.contains("獲得票券") || t.contains("獲得金幣"))
        }
        val hasBottomModalCancel = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.trim()
            cy in 0.85f..0.97f && t == "取消"
        }
        val isBottomRewardPopup = hasBottomModalTitle && hasBottomModalCancel

        if (isBottomRewardPopup) {
            nonGameScreenTicks = 0
            popupRetryTicks = 0
            transitionTo(EngineState.TRIGGER_AD_POPUP, "偵測到下半部領獎彈窗，準備點擊看廣告/領取...", "⏳ 處理彈窗中...")
            return
        }

        // D5. 檢查是否處於可玩式互動廣告 / 應用商店推廣頁 / 全螢幕廣告 (如『開啟商店』、『DOWNLOAD』、『安裝』、『下載』、『Google Play』)
        // 重要安全防護：若能看見主畫面頂部分頁 (首頁/步數/時間/遊戲) 或底部導覽列 (集點/好友/使用/精選)，代表處於主畫面，嚴禁將底層橫幅廣告誤判為全螢幕廣告！
        val hasMainScreenMarkers = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            (cy in 0.08f..0.18f && (t.contains("首頁") || t.contains("步數") || t.contains("時間") || t.contains("遊戲"))) ||
            (cy > 0.85f && (
                t == "集點" ||
                (t.contains("好友") && !t.contains("邀請") && !t.contains("被") && !t.contains("名單") && t.length <= 4) ||
                (t.contains("使用") && !t.contains("條款") && t.length <= 4) ||
                (t.contains("精選") && t.length <= 4)
            ))
        }

        val hasPlayableOrStoreAdOcr = !hasMainScreenMarkers && ocrItems.any { item ->
            val t = item.text
            t.contains("開啟商店") ||
            t.contains("DOWNLOAD", ignoreCase = true) ||
            t.contains("INSTALL", ignoreCase = true) ||
            t.contains("Play Now", ignoreCase = true) ||
            t.contains("立即遊玩") ||
            t.contains("免費下載") ||
            t.contains("安裝") ||
            t.contains("Google Play") ||
            t.contains("インストール") ||
            (t.contains("廣告") && (t.contains("秒") || t.contains("後") || item.bounds.centerY() < sh * 0.15f))
        }

        if (adWatchdogCooldownTicks > 0) {
            adWatchdogCooldownTicks--
        }
        val isGameScreen = hasMainScreenMarkers || adCloserHandler.isGameScreenVisible(service, screenBitmap, ocrItems)
        if (adWatchdogCooldownTicks == 0 && (hasPlayableOrStoreAdOcr || (!isGameScreen && nonGameScreenTicks >= 2))) {
            nonGameScreenTicks = 0
            appendLog("[廣告] 偵測到全螢幕廣告/可玩式廣告/商店宣傳頁，切換至廣告監控流程安全關閉！")
            adCloserHandler.onEnterAdMode()
            transitionTo(
                EngineState.AD_WATCHING,
                "偵測到廣告/宣傳視窗，安全等待與關閉中...",
                "[廣告] 廣告監控中..."
            )
            return
        }

        if (!isGameScreen) {
            nonGameScreenTicks++
        } else {
            nonGameScreenTicks = 0
        }

        updateDetail(
            "[巡邏] 正在搜尋可領取目標或關閉按鈕... (已完成 ${_uiStatus.value.adsCompletedCount} 次)",
            "[巡邏] 搜尋畫面目標中..."
        )
    }

    private suspend fun handleBingoDialog(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ) {
        // 1. 【使用者指示第 1 步】：必須先點擊完橘色「立即開啟賓果格」直到全部開完或冷卻！
        val openCellBtn = bingoTask.findOpenCellButton(screenBitmap, ocrItems, templateMatcher)
        if (openCellBtn != null) {
            currentPhaseEmptyTicks = 0
            appendLog("[目標]【每日賓果】先點擊「立即開啟賓果格」！")
            FloatingOverlayService.clickWithOverlayPassthrough(openCellBtn) {
                service.clickRect(openCellBtn)
            }
            delay(400)
            popupRetryTicks = 0
            transitionTo(EngineState.TRIGGER_AD_POPUP, "已點擊「立即開啟賓果格」，等待開格或廣告結果...", "[賓果] 廣告開格中...")
            return
        }

        // 2. 【使用者指示第 2 步】：開格點完後，再依序點擊所有連線獎勵 [Get]，直到沒有 Get 可點！
        val rewardTarget = bingoTask.findNextClaimableReward(screenBitmap, ocrItems, claimedBingoColumns)
        if (rewardTarget != null) {
            currentPhaseEmptyTicks = 0
            claimedBingoColumns.add(rewardTarget.columnIndex)
            appendLog("[賓果] 點擊連線獎勵【${rewardTarget.label}】！")
            FloatingOverlayService.clickWithOverlayPassthrough(rewardTarget.bounds) {
                service.clickRect(rewardTarget.bounds)
            }
            updateDetail("[賓果] 已點擊【${rewardTarget.label}】，繼續領取獎勵...", "[賓果] 領取獎勵中...")
            delay(350)
            // 保持在 IN_GAME_SCAN，下一 Tick 立即掃描下一欄，秒速領取所有連線 Get！
            return
        }

        // 3. 【使用者指示第 3 步】：無立即開啟且無任何 Get 可點 -> 即完成賓果，點擊 (X) 關閉視窗！
        currentPhaseEmptyTicks++
        if (currentPhaseEmptyTicks < 2) {
            updateDetail("[賓果] 檢查可開格與 Get 獎勵中 ($currentPhaseEmptyTicks/2)...", "[賓果] 檢查中...")
            return
        }

        currentPhaseEmptyTicks = 0
        isBingoCompleted = true
        // 保持 claimedBingoColumns 鎖定，不在此清空，防止關閉後立即重新開格誤觸！
        appendLog("[完成]【每日賓果】所有開格與 Get 領取皆已完成，點擊右上角 (X) 關閉視窗")
        val closeBtn = bingoTask.getCloseButtonBounds(sw, sh)
        FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
            service.clickRect(closeBtn)
        }
        currentPhase = PatrolPhase.LOTTERY
        updateDetail("[完成] 每日賓果已完成，準備進入下一項...", "[完成] 賓果完成")
    }

    private suspend fun handleLotteryDialog(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ) {
        // 0. 檢查是否處於抽獎結果畫面（中獎囉、中獎、Thank you for watching 等）
        val hasLotteryResultContext = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("中獎囉") || t.contains("中獎") || t.contains("Thankyouforwatching") ||
            (t.contains("枚金幣") && t.contains("中獎")) ||
            (t.contains("金幣") && t.contains("中獎")) ||
            (item.bounds.centerY() > sh * 0.65f && (t.contains("分享給好友") || t.contains("分享"))) ||
            (item.bounds.centerY() in (sh * 0.60f).toInt()..(sh * 0.74f).toInt() &&
                (t.contains("x1", ignoreCase = true) || t.contains("1張")) &&
                !t.contains("金幣") && !t.contains("票券"))
        }

        // 0A. 抽獎結果畫面中：若尚未點擊看廣告獲票，優先尋找並點擊綠色按鈕 [▶ [抽獎] x1 獲得]！
        if (hasLotteryResultContext && !hasClickedLotteryAdReward) {
            val rewardAdBtn = lotteryTask.findLotteryRewardAdButton(screenBitmap, ocrItems)
            if (rewardAdBtn != null) {
                hasClickedLotteryAdReward = true
                lotteryResultRewardWaitTicks = 0
                currentPhaseEmptyTicks = 0
                appendLog("[抽抽樂] 偵測到中獎結果，優先點擊看廣告獲票按鈕！")
                FloatingOverlayService.clickWithOverlayPassthrough(rewardAdBtn) {
                    service.clickRect(rewardAdBtn)
                }
                adCloserHandler.onEnterAdMode()
                transitionTo(EngineState.AD_WATCHING, "已點擊 [x1 獲得]，觀看廣告獲得票券中...", "[抽獎] 廣告獲票中...")
                return
            }

            // 若尚未找到廣告獲票按鈕，先等待最多 3 輪確認（避免因動畫延遲或 OCR 讀取時機過早關閉彈窗）
            lotteryResultRewardWaitTicks++
            if (lotteryResultRewardWaitTicks < 4) {
                updateDetail("[抽獎] 等待看廣告獲票按鈕 ($lotteryResultRewardWaitTicks/4)...", "[抽獎] 等待獲票按鈕...")
                return
            }
            // 超過 3 輪等待仍無獲票按鈕，表示該次中獎確無廣告獲票可點，允許關閉
            lotteryResultRewardWaitTicks = 0
            val resultCloseBtn = lotteryTask.findLotteryResultCloseButton(screenBitmap, ocrItems)
            if (resultCloseBtn != null) {
                appendLog("[抽獎]【抽抽樂結果】無廣告獲票按鈕，點擊彈窗底部的 [關閉] 按鈕")
                FloatingOverlayService.clickWithOverlayPassthrough(resultCloseBtn) {
                    service.clickRect(resultCloseBtn)
                }
                delay(400)
                return
            }
        }

        // 0B. 若處於抽獎結果彈窗且「已點過廣告獲票」，點擊彈窗底部的「關閉」按鈕回到抽獎主面板
        if (hasLotteryResultContext && hasClickedLotteryAdReward) {
            val resultCloseBtn = lotteryTask.findLotteryResultCloseButton(screenBitmap, ocrItems)
            if (resultCloseBtn != null) {
                hasClickedLotteryAdReward = false
                lotteryResultRewardWaitTicks = 0
                currentPhaseEmptyTicks = 0
                appendLog("[抽獎]【抽抽樂結果】已完成看廣告獲票，點擊彈窗底部的 [關閉] 按鈕")
                FloatingOverlayService.clickWithOverlayPassthrough(resultCloseBtn) {
                    service.clickRect(resultCloseBtn)
                }
                delay(400)
                return
            }
        }

        // 若不在結果彈窗中，重設相關暫存狀態
        if (!hasLotteryResultContext) {
            hasClickedLotteryAdReward = false
            lotteryResultRewardWaitTicks = 0
        }

        // 1. 依使用者規則尋找：【優先50抽】➔【次選免費抽】➔【嚴禁點5張】
        val drawTarget = lotteryTask.findTargetDrawButton(screenBitmap, ocrItems, templateMatcher)
        if (drawTarget != null) {
            hasClickedLotteryAdReward = false
            currentPhaseEmptyTicks = 0
            appendLog("[抽獎]【抽抽樂】依規則點擊：${drawTarget.first}")
            FloatingOverlayService.clickWithOverlayPassthrough(drawTarget.second) {
                service.clickRect(drawTarget.second)
            }
            if (drawTarget.first.contains("免費")) {
                adCloserHandler.onEnterAdMode()
                transitionTo(EngineState.AD_WATCHING, "已點擊 ${drawTarget.first}，觀看廣告抽獎...", "[抽獎] 廣告抽獎中...")
            } else {
                delay(400)
                popupRetryTicks = 0
                transitionTo(EngineState.TRIGGER_AD_POPUP, "已點擊 ${drawTarget.first}，等待抽獎結果彈窗...", "[抽獎] 抽獎中...")
            }
            return
        }

        // 兩次檢查確認 50 抽與免費抽皆不可點 (50 抽已無次數/票券不足，且免費抽已完成，且嚴格不點 5 抽)
        currentPhaseEmptyTicks++
        if (currentPhaseEmptyTicks < 2) {
            updateDetail("[抽抽樂] 檢查可抽項目 ($currentPhaseEmptyTicks/2)...", "[抽抽樂] 檢查中...")
            return
        }

        currentPhaseEmptyTicks = 0
        hasClickedLotteryAdReward = false
        isLotteryCompleted = true
        appendLog("[完成]【抽抽樂】已無可抽項目 (50抽已滿或票券不足，免費抽已完成，且絕不點5張)，關閉視窗")
        val closeBtn = lotteryTask.getCloseButtonBounds(sw, sh)
        FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
            service.clickRect(closeBtn)
        }
        currentPhase = getNextPhase(PatrolPhase.LOTTERY)
        updateDetail("[完成] 抽抽樂已完成，準備進入下一項...", "[完成] 抽抽樂完成")
    }

    private suspend fun handleMissionDialog(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ) {
        val activeTab = missionTask.detectCurrentActiveTab(screenBitmap)
        appendLog("[任務]【任務視窗】當前處於【$activeTab】分頁 (已檢查: ${checkedMissionTabs.joinToString()})")

        // 1. 尋找清單中綠色「領取」按鈕 (嚴格排除「挑戰」與「已領取」)
        val claimBtn = missionTask.findClaimableMissionButton(screenBitmap, ocrItems)
        if (claimBtn != null) {
            currentPhaseEmptyTicks = 0
            appendLog("[目標]【任務-$activeTab】點擊綠色「領取」按鈕！")
            FloatingOverlayService.clickWithOverlayPassthrough(claimBtn) {
                service.clickRect(claimBtn)
            }
            delay(500)
            popupRetryTicks = 0
            // 領取後可能觸發看廣告或獎勵彈窗，切入 TRIGGER_AD_POPUP 流程安全等候
            transitionTo(
                EngineState.TRIGGER_AD_POPUP,
                "【任務-$activeTab】已點擊領取，等待獎勵或廣告載入中...",
                "[目標] 領取獎勵中..."
            )
            return
        }

        // 2. 當前分頁已無可領取項目，將當前分頁加入已檢查清單
        checkedMissionTabs.add(activeTab)

        // 3. 判斷是否還有未檢查的分頁：LINE 散步趣任務彈窗固定包含「特別」、「每日」、「每週」三大分頁！
        val availableTabs = listOf("特別", "每日", "每週")
        val nextTab = availableTabs.firstOrNull { it !in checkedMissionTabs }
        if (nextTab != null) {
            appendLog("[切換]【任務】$activeTab 分頁已無可領取項目，切換至【$nextTab】分頁檢查...")
            val tabRect = missionTask.getTabRect(nextTab, sw, sh, ocrItems)
            FloatingOverlayService.clickWithOverlayPassthrough(tabRect) {
                service.clickRect(tabRect)
            }
            updateDetail("[切換] 切換至【$nextTab】任務分頁...", "[切換] 切換分頁...")
            delay(500)
            return
        }

        // 4. 所有分頁 (特別、每日、每週) 皆已檢查完畢，確實標記完成並關閉視窗！
        isMissionCompleted = true
        checkedMissionTabs.clear()
        appendLog("[完成]【任務】所有分頁（特別、每日、每週）皆已檢查完畢，關閉視窗並推進至下一階段！")
        val closeBtn = missionTask.getCloseButtonBounds(sw, sh)
        FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
            service.clickRect(closeBtn)
        }
        currentPhase = getNextPhase(PatrolPhase.MISSION)
        updateDetail("[完成] 任務已檢查完畢，推進至下一階段...", "[完成] 任務完成")
        delay(500)
    }

    private suspend fun handleReceiveGiftDialog(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ) {
        // 若為空抽屜狀態 (例如顯示『尚未收到禮物。』)，立即點擊右上角 (X) 關閉，絕不盲點底層廣告橫幅！
        if (receiveGiftTask.hasEmptyDrawer(ocrItems)) {
            currentPhaseEmptyTicks = 0
            isReceiveGiftCompleted = true
            appendLog("[禮物]【接收禮物】尚無收到任何好友禮物，點擊右上角 (X) 關閉視窗")
            val closeBtn = receiveGiftTask.getCloseButtonBounds(sw, sh)
            FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                service.clickRect(closeBtn)
            }
            currentPhase = getNextPhase(PatrolPhase.RECEIVE_GIFT)
            updateDetail("[完成] 禮物視窗已關閉 (無待領禮物)，進入下一階段...", "[完成] 收禮完成")
            delay(500)
            return
        }

        val claimTarget = receiveGiftTask.findNextClaimGiftButton(screenBitmap, ocrItems)
        if (claimTarget != null) {
            currentPhaseEmptyTicks = 0
            val isAd = claimTarget.isAd
            val label = claimTarget.label
            val rect = claimTarget.bounds

            appendLog("[禮物]【接收禮物】點擊【$label】(座標: ${rect.centerX()}, ${rect.centerY()})！")
            FloatingOverlayService.clickWithOverlayPassthrough(rect) {
                service.clickRect(rect)
            }

            if (isAd) {
                updateDetail("[收禮] 點擊【$label】，觀看廣告中...", "[廣告] 廣告監控中...")
                delay(500)
                popupRetryTicks = 0
                transitionTo(EngineState.TRIGGER_AD_POPUP, "已點擊看廣告領取禮物，等待廣告...", "[禮物] 領取禮物中...")
            } else {
                updateDetail("[收禮] 已點擊【$label】，直接領取獎勵！", "[收禮] 直接領取完成")
                delay(600)
            }
            return
        }

        currentPhaseEmptyTicks++
        if (currentPhaseEmptyTicks == 1) {
            appendLog("[清單]【接收禮物】向下滾動禮物清單搜尋更多待領禮物...")
            updateDetail("[清單] 滾動禮物清單搜尋更多禮物...", "[清單] 滾動禮物清單...")
            service.performSwipe(
                startX = sw * 0.50f,
                startY = sh * 0.75f,
                endX = sw * 0.50f,
                endY = sh * 0.35f,
                durationMs = 380L
            )
            delay(600)
            return
        }

        currentPhaseEmptyTicks = 0
        isReceiveGiftCompleted = true
        appendLog("[完成]【接收禮物】全部禮物領取完畢，點擊右上角 (X) 關閉視窗")
        val closeBtn = receiveGiftTask.getCloseButtonBounds(sw, sh)
        FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
            service.clickRect(closeBtn)
        }
        currentPhase = getNextPhase(PatrolPhase.RECEIVE_GIFT)
        updateDetail("[完成] 好友禮物已全部接收完畢！", "[完成] 收禮完成")
        delay(600)
    }

    private suspend fun handleFriendsPage(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ) {
        // 0. 最優先檢查：是否跳出送禮結束的「贈送完畢」彈窗（已贈送金幣給你自己和XXX。[關閉]）！
        val completedCloseBtn = friendGiftTask.findSentCompletedCloseButton(screenBitmap, ocrItems)
        if (completedCloseBtn != null) {
            friendGiftTask.clearGiftedPoints()
            appendLog("[禮物]【好友送禮】偵測到「贈送完畢」彈窗，點擊 [關閉] 繼續送禮！")
            FloatingOverlayService.clickWithOverlayPassthrough(completedCloseBtn) {
                service.clickRect(completedCloseBtn)
            }
            updateDetail("[禮物] 關閉贈送完畢視窗，準備送下一位...", "[禮物] 關閉彈窗")
            delay(500)
            return
        }

        // 0B. 檢查贈送次數是否已達上限 (例如 0/5 或 今日上限)，且畫面上已無任何可點擊之綠色禮物按鈕
        val hasGiftQuotaFull = friendGiftTask.isGiftQuotaFull(ocrItems)
        val hasRemainingQuota = friendGiftTask.hasRemainingGiftQuota(ocrItems)
        val hasAnyGiftButton = friendGiftTask.findNextGiftButton(screenBitmap, ocrItems) != null
        if (hasGiftQuotaFull && !hasAnyGiftButton) {
            appendLog("[禮物]【好友送禮】今日贈送次數已達上限 (0/5)，送禮完成！")
            isFriendGiftCompleted = true
        }

        if (isFriendGiftCompleted) {
            if (config.enableFriendGiftTask && !isReceiveGiftCompleted) {
                currentPhase = PatrolPhase.RECEIVE_GIFT
                friendGiftOpenDrawerTicks++
                appendLog("[禮物]【好友送禮】已完成，點擊右上角【禮物盒】開啟收禮視窗 ($friendGiftOpenDrawerTicks/3)！")
                val giftBoxRect = receiveGiftTask.getHeaderGiftBoxRect(sw, sh)
                FloatingOverlayService.clickWithOverlayPassthrough(giftBoxRect) {
                    service.clickRect(giftBoxRect, allowTopSafeZone = true)
                }
                updateDetail("[禮物] 送禮已完成，開啟【禮物】視窗接收禮物...", "[禮物] 開啟禮物盒...")
                if (friendGiftOpenDrawerTicks >= 3) {
                    val mainTabBtn = friendGiftTask.getBottomMainTabRect(sw, sh, ocrItems)
                    service.clickRect(mainTabBtn)
                }
                delay(600)
                return
            }

            // 若不需要接收禮物或收禮已完成，點擊底部「集點」返回首頁
            friendGiftReturnHomeTicks++
            val mainTabBtn = friendGiftTask.getBottomMainTabRect(sw, sh, ocrItems)
            appendLog("[完成] 好友送禮已完成，點擊底部「集點」返回首頁 ($friendGiftReturnHomeTicks)")
            FloatingOverlayService.clickWithOverlayPassthrough(mainTabBtn) {
                service.clickRect(mainTabBtn)
            }
            if (friendGiftReturnHomeTicks >= 3) {
                currentPhase = getNextPhase(PatrolPhase.RECEIVE_GIFT)
            }
            delay(600)
            return
        }

        // 1. 最優先檢查：是否跳出送禮確認彈窗（使用者指定：【都以送禮物也送自己為主】！）
        val selfGiftOption = friendGiftTask.findSelfGiftButton(screenBitmap, ocrItems)
        if (selfGiftOption != null) {
            friendGiftEmptyTicks = 0
            appendLog("[禮物]【好友送禮】點擊【${selfGiftOption.first}】(優先送禮物也送自己)！")
            FloatingOverlayService.clickWithOverlayPassthrough(selfGiftOption.second) {
                service.clickRect(selfGiftOption.second)
            }
            val isAd = selfGiftOption.first.contains("也送給自己") ||
                       selfGiftOption.first.contains("送給自己") ||
                       selfGiftOption.first.contains("送自己") ||
                       selfGiftOption.first.contains("看廣告")
            if (isAd) {
                updateDetail("[禮物] 點擊【${selfGiftOption.first}】，觀看廣告中...", "[廣告] 廣告監控中...")
                adCloserHandler.onEnterAdMode()
                transitionTo(EngineState.AD_WATCHING, "送禮物也送自己，觀看廣告中...", "[廣告] 廣告監控中...")
            } else {
                updateDetail("[禮物] 已贈送好友禮物，繼續檢查下一位...", "[禮物] 贈送完成")
                delay(500)
            }
            return
        }

        // 2. 搜尋畫面上是否有未贈送的綠色「禮物」按鈕
        val nextGiftBtn = friendGiftTask.findNextGiftButton(screenBitmap, ocrItems)
        if (nextGiftBtn != null) {
            friendGiftEmptyTicks = 0
            friendGiftTask.markGifted(android.graphics.Point(nextGiftBtn.centerX(), nextGiftBtn.centerY()))
            appendLog("[禮物]【好友送禮】點擊好友排行榜綠色禮物按鈕 (座標: ${nextGiftBtn.centerX()}, ${nextGiftBtn.centerY()})！")
            updateDetail("[禮物] 點擊好友禮物按鈕，等待送禮彈窗...", "[禮物] 贈送禮物中...")
            FloatingOverlayService.clickWithOverlayPassthrough(nextGiftBtn) {
                service.clickRect(nextGiftBtn)
            }
            delay(500)
            return
        }

        // 3. 畫面上沒有未贈送的禮物按鈕了，向下滾動清單搜尋更多好友！
        friendGiftEmptyTicks++
        if (friendGiftEmptyTicks <= 2) {
            appendLog("[清單]【好友送禮】向下滾動清單搜尋更多好友 ($friendGiftEmptyTicks/2)...")
            updateDetail("[清單] 滾動清單搜尋更多好友 ($friendGiftEmptyTicks/2)...", "[清單] 滾動好友清單...")
            friendGiftTask.clearGiftedPoints()
            service.performSwipe(
                startX = sw * 0.50f,
                startY = sh * 0.70f,
                endX = sw * 0.50f,
                endY = sh * 0.35f,
                durationMs = 380L
            )
            delay(600)
            return
        }

        // 4. 連續兩次滾動皆無新禮物按鈕：
        // 嚴格防護：若畫面上仍明確顯示有剩餘額度 (如 3/5)，絕不可直接判定完成！
        if (hasRemainingQuota) {
            if (friendGiftEmptyTicks <= 4) {
                appendLog("[清單]【好友送禮】尚有剩餘額度，向上滾動清單重新檢查 ($friendGiftEmptyTicks/4)...")
                updateDetail("[清單] 向上滾動清單搜尋好友 ($friendGiftEmptyTicks/4)...", "[清單] 滾動好友清單...")
                friendGiftTask.clearGiftedPoints()
                service.performSwipe(
                    startX = sw * 0.50f,
                    startY = sh * 0.35f,
                    endX = sw * 0.50f,
                    endY = sh * 0.70f,
                    durationMs = 380L
                )
                delay(600)
                return
            }
        }

        isFriendGiftCompleted = true
        if (config.enableFriendGiftTask && !isReceiveGiftCompleted) {
            currentPhase = PatrolPhase.RECEIVE_GIFT
            appendLog("[完成]【好友送禮】好友禮物贈送階段結束，直接點擊右上角【禮物盒】開啟收禮視窗！")
            val giftBoxRect = receiveGiftTask.getHeaderGiftBoxRect(sw, sh)
            FloatingOverlayService.clickWithOverlayPassthrough(giftBoxRect) {
                service.clickRect(giftBoxRect, allowTopSafeZone = true)
            }
            updateDetail("[禮物] 開啟【禮物】視窗接收禮物中...", "[禮物] 接收禮物中...")
            delay(600)
            return
        } else {
            appendLog("[完成]【好友送禮】好友禮物贈送階段結束，自動點擊底部「集點」返回首頁")
            val mainTabBtn = friendGiftTask.getBottomMainTabRect(sw, sh, ocrItems)
            FloatingOverlayService.clickWithOverlayPassthrough(mainTabBtn) {
                service.clickRect(mainTabBtn)
            }
            currentPhase = getNextPhase(PatrolPhase.FRIEND_GIFT)
            updateDetail("[完成] 好友送禮全部完成，返回首頁中...", "[完成] 好友送禮完成")
            delay(600)
        }
    }

    private fun getTabRect(tab: MainTab, sw: Int, sh: Int, ocrItems: List<OcrItem>): Rect {
        val tabKeywords = when (tab) {
            MainTab.MOVE -> listOf("首頁", "移動")
            MainTab.STEP -> listOf("步數")
            MainTab.TIME -> listOf("時間")
            MainTab.GAME -> listOf("遊戲")
        }

        val ocrMatch = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            val yInRange = cy in (sh * 0.08f).toInt()..(sh * 0.16f).toInt()
            val xInRange = when (tab) {
                MainTab.MOVE -> cx < sw * 0.28f
                MainTab.STEP -> cx in (sw * 0.22f).toInt()..(sw * 0.52f).toInt()
                MainTab.TIME -> cx in (sw * 0.48f).toInt()..(sw * 0.78f).toInt()
                MainTab.GAME -> cx > sw * 0.72f
            }
            yInRange && xInRange && tabKeywords.any { kw -> item.text.contains(kw) }
        }

        if (ocrMatch != null) {
            val padX = max(24, (sw * 0.04f).toInt())
            val padY = max(12, (sh * 0.015f).toInt())
            return Rect(
                (ocrMatch.bounds.left - padX).coerceIn(0, sw),
                (ocrMatch.bounds.top - padY).coerceIn(0, sh),
                (ocrMatch.bounds.right + padX).coerceIn(0, sw),
                (ocrMatch.bounds.bottom + padY).coerceIn(0, sh)
            )
        }

        // Fallback: 幾何座標 (每個分頁佔 25% 寬度，高 10.5%~14.2%)
        val left = when (tab) {
            MainTab.MOVE -> (sw * 0.02f).toInt()
            MainTab.STEP -> (sw * 0.26f).toInt()
            MainTab.TIME -> (sw * 0.51f).toInt()
            MainTab.GAME -> (sw * 0.76f).toInt()
        }
        val right = when (tab) {
            MainTab.MOVE -> (sw * 0.24f).toInt()
            MainTab.STEP -> (sw * 0.49f).toInt()
            MainTab.TIME -> (sw * 0.74f).toInt()
            MainTab.GAME -> (sw * 0.98f).toInt()
        }
        val top = (sh * 0.105f).toInt()
        val bottom = (sh * 0.142f).toInt()
        return Rect(left, top, right, bottom)
    }

    /**
     * 結合黑色指示條與畫面特徵 OCR 雙重判定當前真正停留在哪一個主要分頁
     */
    private fun detectTabFromScreen(screenBitmap: android.graphics.Bitmap, ocrItems: List<OcrItem>): MainTab? {
        templateMatcher.detectActiveMainTabIndex(screenBitmap)?.let { idx ->
            MainTab.fromIndex(idx)?.let { return it }
        }
        val sh = screenBitmap.height
        for (item in ocrItems) {
            val t = item.text.replace(" ", "")
            val cy = item.bounds.centerY().toFloat() / sh
            if (cy < 0.60f) {
                if (t.contains("距離下次") || t.contains("集滿量表") || t.contains("移動距離")) return MainTab.MOVE
                if (t.contains("放置") || t.contains("時間累積") || t.contains("累積時間")) return MainTab.TIME
                if (t.contains("今天的步數") || t.contains("步數累積") || t.contains("步數瓶")) return MainTab.STEP
                if (t.contains("秘寶挖挖哇") || t.contains("接龍高手") || t.contains("數獨達人") || t.contains("簡單賺金幣")) return MainTab.GAME
            }
        }
        return null
    }

    private suspend fun handleMainScreenPatrol(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ) {
        when (currentPhase) {
            PatrolPhase.MAIN_TABS -> {
                val enabledTabs = tabPriorityOrder.filter { it.isEnabled(config) }.ifEmpty { tabPriorityOrder }
                val remainingTabs = enabledTabs.filter { it !in confirmedEmptyTabs }

                if (remainingTabs.isEmpty()) {
                    val tabListStr = enabledTabs.joinToString("➔") { it.shortLabel }
                    appendLog("[成功] 四大分頁 ($tabListStr) 常規獎勵皆已領取完畢！(共完成 ${_uiStatus.value.adsCompletedCount} 次)")
                    currentPhase = getNextPhase(PatrolPhase.MAIN_TABS)
                    return
                }

                // 依優先順序（1.移動 ➔ 2.時間 ➔ 3.遊戲 ➔ 4.步數）決定當前應檢查的分頁：
                // 若當前停留在尚有獎勵待領取的分頁（且已完成初始分頁同步），優先留在此分頁全部領取完畢，絕不提前跳轉！
                val desiredTab = if (currentTab in remainingTabs && !needsInitialTabSync) currentTab else remainingTabs.first()

                detectTabFromScreen(screenBitmap, ocrItems)?.let { detectedTab ->
                    currentTab = detectedTab
                    if (currentTab == desiredTab) {
                        needsInitialTabSync = false
                        tabSwitchRetries = 0
                    }
                }

                // 若當前停留在其他分頁（如遊戲分頁），必須先切換分頁，絕不在錯誤分頁上執行任務！
                if (needsInitialTabSync || currentTab != desiredTab) {
                    emptyTicksOnCurrentTab = 0
                    tabSwitchRetries++
                    val tabRect = getTabRect(desiredTab, sw, sh, ocrItems)
                    appendLog("[切換] 依優先順序切換至【${desiredTab.tabName}】(目前在【${currentTab.tabName}】，嘗試:$tabSwitchRetries/3)...")
                    FloatingOverlayService.clickWithOverlayPassthrough(tabRect) {
                        service.clickRect(tabRect)
                    }
                    updateDetail(
                        "[切換] 正在切換至【${desiredTab.tabName}】($tabSwitchRetries/3)...",
                        "[切換] 切換至[${desiredTab.shortLabel}]..."
                    )
                    if (tabSwitchRetries >= 4) {
                        appendLog("[提示] 切換至【${desiredTab.tabName}】逾時，強制同步分頁狀態並開始檢查")
                        currentTab = desiredTab
                        needsInitialTabSync = false
                        tabSwitchRetries = 0
                    }
                    delay(600)
                    return
                }

                val currentTask: GameTask = when (currentTab) {
                    MainTab.MOVE -> moveTask
                    MainTab.TIME -> timeTask
                    MainTab.GAME -> gameTask
                    MainTab.STEP -> stepTask
                }

                val target = if (currentTask.isEnabled(config)) {
                    currentTask.findTargetOnScreen(
                        screenBitmap = screenBitmap,
                        ocrItems = ocrItems,
                        config = config,
                        templateStorage = templateStorage,
                        templateMatcher = templateMatcher,
                        ocrRecognizer = ocrRecognizer
                    )
                } else {
                    null
                }

                if (target != null) {
                    emptyTicksOnCurrentTab = 0
                    confirmedEmptyTabs.remove(currentTab)
                    val newTaskCount = _uiStatus.value.tasksTriggeredCount + 1
                    _uiStatus.value = _uiStatus.value.copy(tasksTriggeredCount = newTaskCount)
                    appendLog("[目標]【${currentTab.tabName}】點擊: ${target.targetLabel}")
                    FloatingOverlayService.clickWithOverlayPassthrough(target.bounds) {
                        service.clickRect(target.bounds)
                    }
                    popupRetryTicks = 0
                    transitionTo(
                        EngineState.TRIGGER_AD_POPUP,
                        "已點擊 ${target.targetLabel}，等待『跳過廣告 / 紅利獲得』彈窗...",
                        "⏳ 等待彈窗出現..."
                    )
                    return
                }

                // 本分頁目前沒有亮起的可領取獎勵按鈕
                emptyTicksOnCurrentTab++
                if (emptyTicksOnCurrentTab < 2) {
                    updateDetail(
                        "[巡邏] 正在檢查【${currentTab.tabName}】是否有可領取獎勵 ($emptyTicksOnCurrentTab/2)...",
                        "[巡邏] 檢查[${currentTab.shortLabel}] ($emptyTicksOnCurrentTab/2)..."
                    )
                    return
                }

                // 確認本分頁已無可領取項目
                emptyTicksOnCurrentTab = 0
                confirmedEmptyTabs.add(currentTab)

                val nextRemainingTabs = enabledTabs.filter { it !in confirmedEmptyTabs }
                if (nextRemainingTabs.isEmpty()) {
                    val tabListStr = enabledTabs.joinToString("➔") { it.shortLabel }
                    appendLog("[成功] 四大分頁 ($tabListStr) 常規獎勵皆已領取完畢！")
                    currentPhase = getNextPhase(PatrolPhase.MAIN_TABS)
                    return
                }

                val nextTab = nextRemainingTabs.first()
                val remainOrderStr = nextRemainingTabs.joinToString("➔") { it.shortLabel }
                val tabRect = getTabRect(nextTab, sw, sh, ocrItems)
                appendLog("[切換]【${currentTab.tabName}】已領完，依序切換至【${nextTab.tabName}】(待檢查:$remainOrderStr)")
                needsInitialTabSync = true
                tabSwitchRetries = 0
                FloatingOverlayService.clickWithOverlayPassthrough(tabRect) {
                    service.clickRect(tabRect)
                }
                updateDetail(
                    "[切換] 正在切換至【${nextTab.tabName}】...",
                    "[切換] 切換至[${nextTab.shortLabel}]檢查中..."
                )
                delay(600)
                return
            }

            PatrolPhase.BINGO -> {
                if (!config.enableBingoTask || isBingoCompleted) {
                    currentPhase = getNextPhase(PatrolPhase.BINGO)
                    return
                }
                appendLog("[目標] 點擊主畫面【每日賓果】圖示按鈕...")
                val iconRect = bingoTask.getMainScreenBingoIconRect(sw, sh, ocrItems)
                FloatingOverlayService.clickWithOverlayPassthrough(iconRect) {
                    service.clickRect(iconRect)
                }
                updateDetail("[目標] 開啟【每日賓果】視窗中...", "[目標] 開啟每日賓果...")
            }

            PatrolPhase.LOTTERY -> {
                if (!config.enableLotteryTask || isLotteryCompleted) {
                    currentPhase = getNextPhase(PatrolPhase.LOTTERY)
                    return
                }
                appendLog("[抽獎] 點擊主畫面【抽抽樂】圖示按鈕...")
                val iconRect = lotteryTask.getMainScreenLotteryIconRect(sw, sh, ocrItems)
                FloatingOverlayService.clickWithOverlayPassthrough(iconRect) {
                    service.clickRect(iconRect)
                }
                updateDetail("[抽獎] 開啟【抽抽樂】視窗中...", "[抽獎] 開啟抽抽樂...")
            }

            PatrolPhase.MISSION -> {
                if (!config.enableDailyMissionTask || isMissionCompleted) {
                    currentPhase = getNextPhase(PatrolPhase.MISSION)
                    return
                }
                appendLog("[目標] 點擊主畫面【任務】圖示按鈕...")
                val iconRect = missionTask.getMainScreenMissionIconRect(sw, sh, ocrItems)
                FloatingOverlayService.clickWithOverlayPassthrough(iconRect) {
                    service.clickRect(iconRect)
                }
                updateDetail("[目標] 開啟【任務】視窗中...", "[目標] 開啟任務...")
            }

            PatrolPhase.FRIEND_GIFT -> {
                if (!config.enableFriendGiftTask || isFriendGiftCompleted) {
                    currentPhase = getNextPhase(PatrolPhase.FRIEND_GIFT)
                    return
                }
                appendLog("[禮物] 點擊底部導覽列【好友】按鈕...")
                val friendsTab = friendGiftTask.getBottomFriendsTabRect(sw, sh, ocrItems)
                FloatingOverlayService.clickWithOverlayPassthrough(friendsTab) {
                    service.clickRect(friendsTab)
                }
                updateDetail("[禮物] 進入【好友排行榜】送禮中...", "[禮物] 進入好友...")
            }

            PatrolPhase.RECEIVE_GIFT -> {
                if (!config.enableFriendGiftTask || isReceiveGiftCompleted) {
                    currentPhase = getNextPhase(PatrolPhase.RECEIVE_GIFT)
                    return
                }
                appendLog("[禮物] 點擊主畫面右上角【禮物盒】圖示按鈕...")
                val giftBoxRect = receiveGiftTask.getHeaderGiftBoxRect(sw, sh)
                FloatingOverlayService.clickWithOverlayPassthrough(giftBoxRect) {
                    service.clickRect(giftBoxRect, allowTopSafeZone = true)
                }
                updateDetail("[禮物] 開啟【禮物】視窗接收禮物中...", "[禮物] 接收禮物中...")
            }

            PatrolPhase.COMPLETED -> {
                handleRoundCompletion(service)
            }
        }
    }

    internal suspend fun handleRoundCompletion(service: AutoAdAccessibilityService? = null) {
        val completed = currentRound
        appendLog("[完成] 第 $completed 輪全日巡邏任務皆已領取完畢！等待 5 秒防風控冷卻時間後開始下一輪")
        appendLog("[冷卻] 進入防風控冷卻間隔 (約 5 秒)，準備啟動第 ${completed + 1} 輪巡邏...")

        _uiStatus.value = _uiStatus.value.copy(
            statusDetail = "[巡邏中] 冷卻等待中 (5s)",
            overlaySafeDetail = "[冷卻] 冷卻等待中 (5s)..."
        )

        for (sec in 5 downTo 1) {
            if (!_uiStatus.value.isRunning || !scope.isActive) {
                appendLog("[冷卻] 巡邏冷卻中偵測到手動暫停，中斷循環")
                return
            }
            updateDetail(
                "[冷卻] 輪次間隔冷卻中：剩餘 $sec 秒後開始第 ${completed + 1} 輪巡邏...",
                "[冷卻] 冷卻中 (${sec}s)..."
            )
            delay(1000L)
        }

        if (!_uiStatus.value.isRunning || !scope.isActive) {
            return
        }

        currentRound++
        _uiStatus.value = _uiStatus.value.copy(currentRound = currentRound)

        confirmedEmptyTabs.clear()
        emptyTicksOnCurrentTab = 0
        needsInitialTabSync = true
        tabSwitchRetries = 0
        popupRetryTicks = 0
        rewardClaimRetryTicks = 0
        nonGameScreenTicks = 0
        currentPhaseEmptyTicks = 0

        isBingoCompleted = false
        claimedBingoColumns.clear()
        bingoColumnRetryCount.clear()
        isLotteryCompleted = false
        hasClickedLotteryAdReward = false
        lotteryResultRewardWaitTicks = 0
        isMissionCompleted = false
        checkedMissionTabs.clear()
        isFriendGiftCompleted = false
        isReceiveGiftCompleted = false
        friendGiftEmptyTicks = 0
        friendGiftReturnHomeTicks = 0
        friendGiftOpenDrawerTicks = 0
        friendGiftTask.resetSession()

        currentTab = tabPriorityOrder.firstOrNull { it.isEnabled(config) } ?: MainTab.MOVE
        currentPhase = if (tabPriorityOrder.any { it.isEnabled(config) }) {
            PatrolPhase.MAIN_TABS
        } else {
            getNextPhase(PatrolPhase.MAIN_TABS)
        }

        appendLog("[循環] 🚀 第 $currentRound 輪自動巡邏已重置啟動！首發階段：【${currentPhase.title}】")
        transitionTo(
            newState = EngineState.IN_GAME_SCAN,
            detail = "第 $currentRound 輪巡邏開始，首發階段：【${currentPhase.title}】...",
            safeOverlayText = "[第${currentRound}輪] [${currentPhase.title}]..."
        )
    }

    fun simulateRoundCompletion(
        pauseRequestedAtSec: Int? = null,
        totalCooldownSec: Int = 5
    ): CooldownResult {
        val completed = currentRound
        var waited = 0
        var paused = false

        for (sec in 1..totalCooldownSec) {
            waited = sec
            if (pauseRequestedAtSec != null && sec >= pauseRequestedAtSec) {
                paused = true
                stop("巡邏冷卻中手動暫停")
                break
            }
        }

        if (!paused) {
            currentRound++
            _uiStatus.value = _uiStatus.value.copy(currentRound = currentRound)

            confirmedEmptyTabs.clear()
            emptyTicksOnCurrentTab = 0
            needsInitialTabSync = true
            tabSwitchRetries = 0
            popupRetryTicks = 0
            rewardClaimRetryTicks = 0
            nonGameScreenTicks = 0
            currentPhaseEmptyTicks = 0

            isBingoCompleted = false
            claimedBingoColumns.clear()
            bingoColumnRetryCount.clear()
            isLotteryCompleted = false
            hasClickedLotteryAdReward = false
            lotteryResultRewardWaitTicks = 0
            isMissionCompleted = false
            checkedMissionTabs.clear()
            isFriendGiftCompleted = false
            isReceiveGiftCompleted = false
            friendGiftEmptyTicks = 0
            friendGiftReturnHomeTicks = 0
            friendGiftOpenDrawerTicks = 0
            friendGiftTask.resetSession()

            currentTab = tabPriorityOrder.firstOrNull { it.isEnabled(config) } ?: MainTab.MOVE
            currentPhase = if (tabPriorityOrder.any { it.isEnabled(config) }) {
                PatrolPhase.MAIN_TABS
            } else {
                getNextPhase(PatrolPhase.MAIN_TABS)
            }
        }

        return CooldownResult(
            completedRound = completed,
            nextRound = currentRound,
            wasPausedDuringCooldown = paused,
            cooldownSecondsWaited = waited
        )
    }

    private fun getNextPhase(after: PatrolPhase): PatrolPhase {
        val phaseList = listOf(
            PatrolPhase.MAIN_TABS,
            PatrolPhase.BINGO,
            PatrolPhase.LOTTERY,
            PatrolPhase.MISSION,
            PatrolPhase.FRIEND_GIFT,
            PatrolPhase.RECEIVE_GIFT
        )
        val startIdx = phaseList.indexOf(after) + 1
        for (i in startIdx until phaseList.size) {
            val p = phaseList[i]
            val isEnabled = when (p) {
                PatrolPhase.MAIN_TABS -> tabPriorityOrder.any { it.isEnabled(config) }
                PatrolPhase.BINGO -> config.enableBingoTask && !isBingoCompleted
                PatrolPhase.LOTTERY -> config.enableLotteryTask && !isLotteryCompleted
                PatrolPhase.MISSION -> config.enableDailyMissionTask && !isMissionCompleted
                PatrolPhase.FRIEND_GIFT -> config.enableFriendGiftTask && !isFriendGiftCompleted
                PatrolPhase.RECEIVE_GIFT -> config.enableFriendGiftTask && !isReceiveGiftCompleted
                PatrolPhase.COMPLETED -> false
            }
            if (isEnabled) return p
        }
        return PatrolPhase.COMPLETED
    }

    private suspend fun handlePopupCooldownCancel(
        service: AutoAdAccessibilityService,
        cancelBtn: Rect,
        sw: Int,
        sh: Int
    ) {
        appendLog("↩️【${currentTab.tabName}】獎勵皆在冷卻中，點擊 [取消] 關閉彈窗並切換至下一分頁/任務")
        FloatingOverlayService.clickWithOverlayPassthrough(cancelBtn) {
            service.clickRect(cancelBtn)
        }
        confirmedEmptyTabs.add(currentTab)
        emptyTicksOnCurrentTab = 0

        val enabledTabs = tabPriorityOrder.filter { it.isEnabled(config) }.ifEmpty { tabPriorityOrder }
        val nextRemainingTabs = enabledTabs.filter { it !in confirmedEmptyTabs }
        if (nextRemainingTabs.isEmpty()) {
            val tabListStr = enabledTabs.joinToString("➔") { it.shortLabel }
            appendLog("[成功] 四大分頁 ($tabListStr) 常規獎勵皆已檢查完畢！準備檢查日常任務...")
            currentPhase = getNextPhase(PatrolPhase.MAIN_TABS)
        } else {
            val nextTab = nextRemainingTabs.first()
            val remainOrderStr = nextRemainingTabs.joinToString("➔") { it.shortLabel }
            appendLog("[切換]【${currentTab.tabName}】冷卻中，依序切換至【${nextTab.tabName}】(待檢查: $remainOrderStr)")
            currentTab = nextTab
            needsInitialTabSync = true
            tabSwitchRetries = 0
        }

        transitionTo(EngineState.IN_GAME_SCAN, "已點擊 [取消]，切換至下一分頁/任務...", "[巡邏] 切換巡邏中...")
    }

    private suspend fun handleTriggerAdPopup(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        popupRetryTicks++
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 若點擊目標後已直接進入廣告載入中畫面，立即轉入廣告監控流程！
        if (ocrRecognizer.isAdLoadingScreen(ocrItems)) {
            appendLog("⏳ 點擊後進入廣告載入中狀態，自動轉入廣告監控流程！")
            adCloserHandler.onEnterAdMode()
            transitionTo(EngineState.AD_WATCHING, "廣告正在載入中，請稍候...", "⏳ 廣告載入中...")
            return
        }

        // 優先檢查：若處於抽抽樂階段或彈窗，檢查是否為抽抽樂中獎結果彈窗上的綠色獲票按鈕 [▶ [抽獎] x1 獲得]！
        if (currentPhase == PatrolPhase.LOTTERY || lotteryTask.isLotteryDialogOpen(ocrItems, sh)) {
            val lotteryRewardBtn = if (!hasClickedLotteryAdReward) lotteryTask.findLotteryRewardAdButton(screenBitmap, ocrItems) else null
            if (lotteryRewardBtn != null) {
                hasClickedLotteryAdReward = true
                lotteryResultRewardWaitTicks = 0
                appendLog("[抽抽樂] 偵測到中獎結果！點擊看廣告獲票按鈕")
                FloatingOverlayService.clickWithOverlayPassthrough(lotteryRewardBtn) {
                    service.clickRect(lotteryRewardBtn)
                }
                adCloserHandler.onEnterAdMode()
                transitionTo(
                    EngineState.AD_WATCHING,
                    "點擊抽抽樂獎勵看廣告再獲得票券 [x1 獲得]...",
                    "[抽獎] 廣告獲票中..."
                )
                return
            }

            // 若處於抽獎結果彈窗但已點過廣告獲票，點擊彈窗底部的「關閉」按鈕回到抽抽樂面板
            val resultCloseBtn = lotteryTask.findLotteryResultCloseButton(screenBitmap, ocrItems)
            if (resultCloseBtn != null && hasClickedLotteryAdReward) {
                hasClickedLotteryAdReward = false
                lotteryResultRewardWaitTicks = 0
                appendLog("[抽獎]【抽抽樂結果】點擊彈窗底部的 [關閉] 按鈕")
                FloatingOverlayService.clickWithOverlayPassthrough(resultCloseBtn) {
                    service.clickRect(resultCloseBtn)
                }
                transitionTo(EngineState.IN_GAME_SCAN, "已關閉抽獎結果，繼續抽抽樂...", "[抽獎] 抽抽樂中...")
                return
            }
        }

        // 若已彈出抽獎/領獎完成彈窗（如『獲得金幣！ [關閉]』），直接點擊 [關閉] 並返回主畫面/子視窗繼續巡邏！
        val modalCloseBtn = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)
        if (modalCloseBtn != null) {
            appendLog("[完成] 點擊抽獎/領獎完成彈窗: ${modalCloseBtn.first}")
            FloatingOverlayService.clickWithOverlayPassthrough(modalCloseBtn.second) {
                service.clickRect(modalCloseBtn.second)
            }
            transitionTo(EngineState.IN_GAME_SCAN, "已關閉領獎彈窗，繼續巡邏...", "[關閉] 關閉領獎彈窗...")
            return
        }

        val popupAction = findPopupActionTarget(screenBitmap, ocrItems)
        if (popupAction != null) {
            executePopupAction(service, popupAction)
            return
        }

        // 檢查是否彈出好友送禮確認彈窗（『也送給自己』）
        val selfGiftOption = friendGiftTask.findSelfGiftButton(screenBitmap, ocrItems)
        if (selfGiftOption != null) {
            appendLog("[禮物]【好友送禮】等待彈窗中偵測到【${selfGiftOption.first}】，立即點擊！")
            FloatingOverlayService.clickWithOverlayPassthrough(selfGiftOption.second) {
                service.clickRect(selfGiftOption.second)
            }
            val isAd = selfGiftOption.first.contains("也送給自己") ||
                       selfGiftOption.first.contains("送給自己") ||
                       selfGiftOption.first.contains("送自己") ||
                       selfGiftOption.first.contains("看廣告")
            if (isAd) {
                adCloserHandler.onEnterAdMode()
                transitionTo(EngineState.AD_WATCHING, "送禮物也送自己，觀看廣告中...", "[廣告] 廣告監控中...")
            } else {
                transitionTo(EngineState.IN_GAME_SCAN, "已贈送好友禮物，繼續巡邏...", "[禮物] 贈送完成")
            }
            return
        }

        // 若彈出「玩遊戲賺點數」推廣宣傳頁，自動點擊底部 [跳過]
        val promoSkip = findPlayGamePromoSkipButton(screenBitmap, ocrItems, sw, sh)
        if (promoSkip != null) {
            appendLog("[遊戲] 偵測到【玩遊戲賺點數】推廣頁，自動點擊底部 [跳過]！")
            FloatingOverlayService.clickWithOverlayPassthrough(promoSkip) {
                service.clickRect(promoSkip)
            }
            transitionTo(EngineState.IN_GAME_SCAN, "已跳過推廣頁，繼續巡邏...", "[巡邏] 巡邏畫面目標中...")
            return
        }

        // 若彈窗按鈕皆在冷卻中且出現 [取消]，立即點擊 [取消] 關閉彈窗並切換至下一分頁/任務
        val cancelBtn = ocrRecognizer.findPopupCancelButton(screenBitmap, ocrItems, templateMatcher)
        if (cancelBtn != null) {
            handlePopupCooldownCancel(service, cancelBtn, sw, sh)
            return
        }

        // 檢查是否處於真正的四大分頁下半部領獎彈窗（必須具備彈窗標題與取消按鈕，嚴禁因底層廣告粉紅像素誤判）
        val hasBottomModalTitle = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy in 0.52f..0.75f &&
                !t.contains("輕鬆") && !t.contains("簡單") && !t.contains("遊玩") && !t.contains("Sponsored") && !t.contains("AD") &&
                (t.contains("獲得票券") || t.contains("獲得金幣"))
        }
        val hasBottomModalCancel = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.trim()
            cy in 0.85f..0.97f && t == "取消"
        }
        val isBottomRewardPopup = hasBottomModalTitle && hasBottomModalCancel

        // 檢查是否點擊目標後已直接開啟全螢幕廣告、宣傳頁或外部跳轉網頁
        val directGeoX = templateMatcher.detectCornerGeometricX(
            screenBitmap = screenBitmap,
            blacklistedCoord = { x, y ->
                FloatingOverlayService.isInsideOverlay(x, y) ||
                y < sh * 0.08f
            }
        )
        val onMainScreenDirect = ocrRecognizer.isLineWalkMainScreenVisible(screenBitmap, ocrItems, templateMatcher)
        val isStillInSubDialog = !onMainScreenDirect && (
            missionTask.isMissionDialogOpen(ocrItems, sh, screenBitmap) ||
            bingoTask.isBingoDialogOpen(ocrItems, sh) ||
            lotteryTask.isLotteryDialogOpen(ocrItems, sh) ||
            receiveGiftTask.isReceiveGiftDialogOpen(ocrItems, sh) ||
            friendGiftTask.isFriendsPage(ocrItems, sh)
        )

        // 檢查是否彈出 LINE 內部宣傳促銷廣告 (如『還沒有邀請好友 實在太可惜了！』)
        if (ocrRecognizer.isLineInHousePromoAd(ocrItems)) {
            val closeBtn = ocrRecognizer.getLineInHousePromoCloseButton(sw, sh)
            appendLog("[廣告] 彈窗觸發期間偵測到 LINE 宣傳廣告/促銷彈窗，點擊右上角 (X) 關閉")
            FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                service.clickRect(closeBtn)
            }
            delay(500)
            transitionTo(EngineState.IN_GAME_SCAN, "已關閉宣傳廣告，繼續巡邏...", "[巡邏] 巡邏畫面目標中...")
            return
        }

        val fgPkgDirect = service.getForegroundPackageName()
        val isAdPkgDirect = AppConfig.isAdPackage(fgPkgDirect)
        if (isAdPkgDirect) {
            appendLog("[廣告] 彈窗觸發偵測到第三方廣告容器 ($fgPkgDirect)，啟動廣告監控流程！")
            adCloserHandler.onEnterAdMode()
            transitionTo(EngineState.AD_WATCHING, "已進入廣告畫面 ($fgPkgDirect)，安全監控中...", "[廣告] 監控中...")
            return
        }

        val isExternalDirect = fgPkgDirect != null && AppConfig.isRedirectPackage(fgPkgDirect)
        val hasBrowserTitleDirect = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            cy < sh * 0.16f && (item.text.contains("正在載入") || item.text.contains(".com") || item.text.contains("http"))
        }
        val isGameScreen = adCloserHandler.isGameScreenVisible(service, screenBitmap, ocrItems)
        // 嚴格安全防護：若畫面仍在主畫面或領獎下半部彈窗，絕不誤判為全螢幕廣告！
        if (!onMainScreenDirect && !isStillInSubDialog && !isBottomRewardPopup && (directGeoX != null || isExternalDirect || hasBrowserTitleDirect || (!isGameScreen && popupRetryTicks >= 2))) {
            appendLog("[廣告] 點擊目標後已直接進入全螢幕廣告/宣傳頁，啟動廣告監控流程！")
            adCloserHandler.onEnterAdMode()
            transitionTo(EngineState.AD_WATCHING, "已進入廣告畫面，安全監控中...", "[廣告] 監控中...")
            return
        }

        if (isStillInSubDialog && popupRetryTicks >= 1) {
            transitionTo(EngineState.IN_GAME_SCAN, "已領取獎勵，繼續彈窗內部巡邏...", "[巡邏] 檢查彈窗中...")
            return
        }

        if (onMainScreenDirect && popupRetryTicks >= 3) {
            transitionTo(EngineState.IN_GAME_SCAN, "已回到主畫面，繼續常規巡邏...", "[巡邏] 巡邏畫面目標中...")
            return
        }

        if (popupRetryTicks >= 2 && config.claimNormalWhenNoAd) {
            val normalOrSkip = ocrRecognizer.findMatch(
                items = ocrItems,
                keywords = OcrRecognizer.GAME_CLAIM_CONFIRM_KEYWORDS,
                screenWidth = sw,
                screenHeight = sh,
                roiNormalized = RectF(0.08f, 0.15f, 0.92f, 0.88f),
                excludeKeywords = OcrRecognizer.GAME_CLAIM_EXCLUDE_KEYWORDS,
                maxTextLength = 6
            )
            if (normalOrSkip != null) {
                appendLog("ℹ️ 點擊彈窗一般按鈕: [${normalOrSkip.text}]")
                FloatingOverlayService.clickWithOverlayPassthrough(normalOrSkip.bounds) {
                    service.clickRect(normalOrSkip.bounds)
                }
                rewardClaimRetryTicks = 0
                transitionTo(
                    EngineState.REWARD_CLAIM,
                    "已點擊 [${normalOrSkip.text}]，確認狀態中",
                    "[完成] 確認完成狀態中..."
                )
                return
            }
        }

        if (popupRetryTicks >= 2) {
            appendLog("↩️ 未偵測到看廣告彈窗，返回主畫面繼續巡邏")
            transitionTo(EngineState.IN_GAME_SCAN, "重新掃描遊戲畫面...", "[巡邏] 巡邏畫面目標中...")
        } else {
            updateDetail("⏳ 等待看廣告彈窗出現 ($popupRetryTicks/2)...", "⏳ 等待彈窗 ($popupRetryTicks/2)...")
        }
    }

    private suspend fun executePopupAction(
        service: AutoAdAccessibilityService,
        action: PopupActionTarget
    ) {
        emptyTicksOnCurrentTab = 0
        confirmedEmptyTabs.remove(currentTab)
        when (action) {
            is PopupActionTarget.CandySkipAd -> {
                val newCount = _uiStatus.value.adsCompletedCount + 1
                _uiStatus.value = _uiStatus.value.copy(adsCompletedCount = newCount)
                appendLog("[糖果] 偵測到可用糖果！點擊 [${action.label}] 直接跳過廣告！(累計 $newCount 次)")
                FloatingOverlayService.clickWithOverlayPassthrough(action.bounds) {
                    service.clickRect(action.bounds)
                }
                rewardClaimRetryTicks = 0
                transitionTo(
                    EngineState.REWARD_CLAIM,
                    "已使用糖果跳過廣告，確認領取狀態中...",
                    "[糖果] 糖果跳過確認中..."
                )
            }

            is PopupActionTarget.WatchAd -> {
                appendLog("[廣告] 點擊彈窗看廣告按鈕: ${action.label}")
                FloatingOverlayService.clickWithOverlayPassthrough(action.bounds) {
                    service.clickRect(action.bounds)
                }
                adCloserHandler.onEnterAdMode()
                transitionTo(
                    EngineState.AD_WATCHING,
                    "已點擊 ${action.label}，等待廣告播放與關閉...",
                    "[廣告] 監控廣告中..."
                )
            }
        }
    }

    private suspend fun handleAdWatching(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val promoSkip = findPlayGamePromoSkipButton(screenBitmap, ocrItems, sw, sh)
        if (promoSkip != null) {
            appendLog("[推廣] 廣告中偵測到【玩遊戲賺點數】推廣頁，自動點擊底部 [跳過]！")
            FloatingOverlayService.clickWithOverlayPassthrough(promoSkip) {
                service.clickRect(promoSkip)
            }
            transitionTo(EngineState.IN_GAME_SCAN, "已跳過推廣頁，繼續巡邏...", "[巡邏] 巡邏畫面目標中...")
            return
        }


        // 0. 全域廣告超時絕對防護 (Global Ad Timeout Watchdog)：
        // 無論後續辨識回傳何種狀態，若單支廣告已播放達 (maxAdWaitSeconds + 4 秒，預設 36 秒) 仍未結束，
        // 絕對強制發送返回鍵、喚醒《LINE 散步趣》拉回前景並重置引擎狀態，絕不允許任何廣告造成卡死！
        val adElapsed = adCloserHandler.getElapsedSeconds()
        if (adElapsed >= config.maxAdWaitSeconds + 4) {
            appendLog("[警報] 廣告播放達 ${adElapsed}s 超過上限 (${config.maxAdWaitSeconds}s)，強制喚醒散步趣拉回前景！")
            service.safeNavigateBack()
            delay(300)
            service.bringAppToFront("com.linecorp.LGWKTW")
            delay(800)
            adWatchdogCooldownTicks = 4
            transitionTo(EngineState.IN_GAME_SCAN, "廣告逾時強制拉回散步趣主畫面，繼續巡邏...", "[巡邏] 重新巡邏中...")
            return
        }

        when (val res = adCloserHandler.processAdTick(service, screenBitmap, ocrItems)) {
            is AdStepResult.RedirectRecovered -> {
                appendLog("[防護] 誤跳轉外部套件/網頁 (${res.externalPkg})，已自動按返回鍵關閉並繼續監控廣告")
                updateDetail(
                    "[防護] 已關閉外部網頁 (${res.externalPkg})，繼續監控廣告...",
                    "[防護] 關閉網頁，繼續監控..."
                )
            }

            is AdStepResult.AdChoicesDismissed -> {
                appendLog("[防護] 偵測到誤觸『Hide Ad / Report Ad』選單，已自動關閉選單並封鎖該座標！")
                updateDetail(
                    "[防護] 已關閉 Hide Ad 選單並封鎖誤觸座標，繼續播放廣告...",
                    "[防護] 已關閉選單，繼續監控..."
                )
            }

            is AdStepResult.AdLoading -> {
                updateDetail(
                    "[載入] 廣告向伺服器載入中 (${res.elapsedSec}s)，請稍候...",
                    "[載入] 廣告載入中 (${res.elapsedSec}s)..."
                )
            }

            is AdStepResult.WaitingGracePeriod -> {
                updateDetail("[防護] 廣告播放保護期 (${res.elapsedSec}s)...", "[廣告] 播放中 (${res.elapsedSec}s)...")
            }

            is AdStepResult.StillCountingDown -> {
                updateDetail(
                    "[倒數] 廣告倒數中：剩餘約 ${res.remainingSec} 秒 (已播 ${res.elapsedSec}s)",
                    "[倒數] 倒數約 ${res.remainingSec}s (已播 ${res.elapsedSec}s)"
                )
            }

            is AdStepResult.ClickedCloseOrSkip -> {
                appendLog("[點擊] 點擊關閉/略過按鈕: ${res.sourceLabel}")
                updateDetail(
                    "[點擊] 已點擊 ${res.sourceLabel}，確認是否回到遊戲...",
                    "[點擊] 已點關閉，確認中..."
                )
            }

            is AdStepResult.ReturnedToGame -> {
                if (res.label == "廣告載入逾時已取消") {
                    appendLog("[逾時] 廣告伺服器載入逾時 (超過 18 秒)，已自動取消並返回遊戲！")
                    // 若當前在日常子視窗，關閉該視窗並推進至下一項目，避免無廣告庫存時持續重試
                    if (bingoTask.isBingoDialogOpen(ocrItems, sh)) {
                        val closeBtn = bingoTask.getCloseButtonBounds(sw, sh)
                        FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                            service.clickRect(closeBtn)
                        }
                        currentPhase = getNextPhase(PatrolPhase.BINGO)
                        appendLog("[逾時] 每日賓果廣告載入逾時，關閉視窗並切換至下一項")
                    } else if (lotteryTask.isLotteryDialogOpen(ocrItems, sh)) {
                        val closeBtn = lotteryTask.getCloseButtonBounds(sw, sh)
                        FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                            service.clickRect(closeBtn)
                        }
                        currentPhase = getNextPhase(PatrolPhase.LOTTERY)
                        appendLog("[逾時] 抽抽樂廣告載入逾時，關閉視窗並切換至下一項")
                    } else if (missionTask.isMissionDialogOpen(ocrItems, sh, screenBitmap)) {
                        val closeBtn = missionTask.getCloseButtonBounds(sw, sh)
                        FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                            service.clickRect(closeBtn)
                        }
                        currentPhase = getNextPhase(PatrolPhase.MISSION)
                        appendLog("[逾時] 任務廣告載入逾時，關閉視窗並切換至下一項")
                    } else {
                        confirmedEmptyTabs.add(currentTab)
                        appendLog("[逾時]【${currentTab.tabName}】廣告載入逾時，標記本頁並切換至下一分頁")
                    }
                    transitionTo(EngineState.IN_GAME_SCAN, "廣告載入逾時，返回巡邏...", "[巡邏] 巡邏畫面目標中...")
                    return
                }
                if (res.label == "廣告未成功啟動") {
                    appendLog("[提示] 廣告未成功啟動或無廣告庫存，返回遊戲巡邏")
                    transitionTo(EngineState.IN_GAME_SCAN, "廣告未成功啟動，返回巡邏...", "[巡邏] 巡邏畫面目標中...")
                    return
                }
                if (res.label == "已點擊彈窗 [取消]") {
                    val cancelRect = res.confirmBounds ?: Rect(
                        (screenBitmap.width * 0.38f).toInt(),
                        (screenBitmap.height * 0.90f).toInt(),
                        (screenBitmap.width * 0.62f).toInt(),
                        (screenBitmap.height * 0.95f).toInt()
                    )
                    handlePopupCooldownCancel(service, cancelRect, screenBitmap.width, screenBitmap.height)
                    return
                }
                val newAdsCount = _uiStatus.value.adsCompletedCount + 1
                _uiStatus.value = _uiStatus.value.copy(adsCompletedCount = newAdsCount)
                emptyTicksOnCurrentTab = 0
                if (res.confirmBounds != null) {
                    appendLog("[成功] 廣告已關閉，自動點擊領獎彈窗 [關閉]！(累計完成 $newAdsCount 次)")
                    FloatingOverlayService.clickWithOverlayPassthrough(res.confirmBounds) {
                        service.clickRect(res.confirmBounds)
                    }
                } else {
                    appendLog("[成功] 成功關閉廣告回到散步趣主畫面！(累計完成 $newAdsCount 次)")
                }
                transitionTo(EngineState.IN_GAME_SCAN, "完成一輪！繼續檢查本頁與其他分頁獎勵...", "[巡邏] 繼續下一輪...")
            }

            is AdStepResult.WatchdogBackTriggered -> {
                appendLog("[監控] 廣告等待 (${res.elapsedSec}s)：嘗試發送 ${res.actionLabel} 關閉廣告")
                updateDetail(
                    "[監控] 嘗試 ${res.actionLabel} 關閉廣告 (${res.elapsedSec}s)...",
                    "[監控] 嘗試關閉 (${res.elapsedSec}s)..."
                )
                if (res.elapsedSec >= config.maxAdWaitSeconds + 8) {
                    appendLog("[警報] 廣告等待達 ${res.elapsedSec}s 逾時仍未關閉，強制喚醒散步趣拉回前景！")
                    service.bringAppToFront("com.linecorp.LGWKTW")
                    delay(800)
                    adWatchdogCooldownTicks = 4
                    transitionTo(EngineState.IN_GAME_SCAN, "逾時強制拉回散步趣主畫面，繼續巡邏...", "[巡邏] 重新巡邏中...")
                }
            }

            is AdStepResult.ScanningAd -> {
                updateDetail(
                    "[搜尋] 搜尋關閉 X 或略過按鈕 (已播 ${res.elapsedSec}s)...",
                    "[搜尋] 搜尋關閉鈕 (${res.elapsedSec}s)..."
                )
            }

            is AdStepResult.WokeUpControls -> {
                appendLog("[廣告] 廣告控制項自動隱藏中，已滑動螢幕喚醒略過/關閉按鈕 (${res.elapsedSec}s)！")
                updateDetail(
                    "[廣告] 喚醒略過按鈕中 (${res.elapsedSec}s)...",
                    "[廣告] 喚醒按鈕..."
                )
            }
        }
    }

    private suspend fun handleRewardClaim(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        rewardClaimRetryTicks++
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 檢查是否彈出 LINE 內部宣傳促銷全螢幕廣告 (如『還沒有邀請好友 實在太可惜了！』)
        if (ocrRecognizer.isLineInHousePromoAd(ocrItems)) {
            val closeBtn = ocrRecognizer.getLineInHousePromoCloseButton(sw, sh)
            appendLog("[廣告] 領獎確認期間偵測到 LINE 宣傳廣告/促銷彈窗，點擊右上角 (X) 關閉")
            FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                service.clickRect(closeBtn)
            }
            delay(500)
            return
        }

        val modalClose = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)
        if (modalClose != null) {
            appendLog("[完成] 點擊領獎完成彈窗: ${modalClose.first}")
            FloatingOverlayService.clickWithOverlayPassthrough(modalClose.second) {
                service.clickRect(modalClose.second)
            }
            emptyTicksOnCurrentTab = 0
            transitionTo(EngineState.IN_GAME_SCAN, "已關閉領獎彈窗，繼續下一輪！", "[巡邏] 巡邏目標中...")
            return
        }

        if (ocrRecognizer.isLineWalkMainScreenVisible(screenBitmap, ocrItems, templateMatcher)) {
            emptyTicksOnCurrentTab = 0
            transitionTo(EngineState.IN_GAME_SCAN, "已回到主畫面，繼續檢查下一項！", "[巡邏] 巡邏目標中...")
            return
        }

        val confirmMatch = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.GAME_CLAIM_CONFIRM_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.08f, 0.15f, 0.92f, 0.88f),
            excludeKeywords = OcrRecognizer.GAME_CLAIM_EXCLUDE_KEYWORDS,
            maxTextLength = 6
        )
        if (confirmMatch != null) {
            appendLog("[完成] 點擊完成確認按鈕: [${confirmMatch.text}]")
            FloatingOverlayService.clickWithOverlayPassthrough(confirmMatch.bounds) {
                service.clickRect(confirmMatch.bounds)
            }
            transitionTo(EngineState.IN_GAME_SCAN, "完成！繼續下一輪...", "[巡邏] 巡邏目標中...")
            return
        }

        if (rewardClaimRetryTicks >= 2) {
            transitionTo(EngineState.IN_GAME_SCAN, "繼續下一輪巡邏", "[巡邏] 巡邏目標中...")
        } else {
            updateDetail("[確認] 確認畫面狀態中...", "[確認] 確認狀態中...")
        }
    }

    private suspend fun findPopupActionTarget(
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ): PopupActionTarget? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 嚴格安全防護：若畫面為主畫面特徵（能看見頂部四大分頁或底部導覽列）且無下半部領獎彈窗標題與取消鈕，嚴禁尋找任何目標！
        val hasMainScreenMarkers = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            (cy in 0.08f..0.18f && (t.contains("首頁") || t.contains("步數") || t.contains("時間") || t.contains("遊戲"))) ||
            (cy > 0.85f && (
                t == "集點" ||
                (t.contains("好友") && !t.contains("邀請") && !t.contains("被") && !t.contains("名單") && t.length <= 4) ||
                (t.contains("使用") && !t.contains("條款") && t.length <= 4) ||
                (t.contains("精選") && t.length <= 4)
            ))
        }
        val hasBottomModalTitle = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy in 0.52f..0.75f &&
                !t.contains("輕鬆") && !t.contains("簡單") && !t.contains("遊玩") && !t.contains("Sponsored") && !t.contains("AD") &&
                (t.contains("獲得票券") || t.contains("獲得金幣"))
        }
        val hasBottomModalCancel = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.trim()
            cy in 0.85f..0.97f && t == "取消"
        }
        // 若在主畫面且無彈窗標題與取消鈕，直接回傳 null，絕對禁止在主畫面下半部點擊任何按鈕！
        if (hasMainScreenMarkers && !hasBottomModalTitle && !hasBottomModalCancel) {
            return null
        }

        val btnMarginY = (sh * 0.045f).toInt()

        // 0. 【最高優先】檢查彈窗內是否有可用的『跳過廣告』(糖果) 按鈕！
        // 若使用者擁有糖果，『跳過廣告』按鈕不會是反灰底色 (!isPopupButtonDisabledGray)，直接點擊即可免看廣告領獎！
        val skipAdCandyOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf("跳過廣告"),
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.10f, 0.58f, 0.90f, 0.82f),
            maxTextLength = 10
        )
        if (skipAdCandyOcr != null &&
            !ocrRecognizer.isAreaInCooldown(ocrItems, skipAdCandyOcr.bounds.top - btnMarginY, skipAdCandyOcr.bounds.bottom + btnMarginY) &&
            !templateMatcher.isPopupButtonDisabledGray(screenBitmap, skipAdCandyOcr.bounds.centerY())
        ) {
            return PopupActionTarget.CandySkipAd("糖果[跳過廣告]", skipAdCandyOcr.bounds)
        }

        // 解析金幣/票券數值輔助函式（嚴格遵循使用者指令：以收到金幣數量最多的按鈕為第一優先選取！）
        fun parseRewardCoins(text: String): Int {
            val numMatch = Regex("""(?:x|×)?\s*(\d+)\s*獲得""").find(text)
            if (numMatch != null) {
                return numMatch.groupValues[1].toIntOrNull() ?: 1
            }
            val t = text.replace(" ", "")
            if (t.contains("100")) return 100
            if (t.contains("60")) return 60
            if (t.contains("50")) return 50
            if (t.contains("30")) return 30
            if (t.contains("15")) return 15
            if (t.contains("10")) return 10
            if (t.contains("9")) return 9
            if (t.contains("8")) return 8
            if (t.contains("7")) return 7
            if (t.contains("6")) return 6
            if (t.contains("5")) return 5
            if (t.contains("4")) return 4
            if (t.contains("3")) return 3
            if (t.contains("2")) return 2
            if (t.contains("2倍") || t.contains("加倍")) return 2
            if (t.contains("1")) return 1
            return 0
        }

        // 儲存所有可點擊的看廣告/領取按鈕候選項目及其對應金幣數量: List<Pair<PopupActionTarget, Int>>
        val candidates = mutableListOf<Pair<PopupActionTarget, Int>>()

        // 1. 搜尋彈窗區 (y = 58% ~ 90%) 中所有符合關鍵字的 OCR 項目，解析其金幣數值
        val roiTop = (sh * 0.58f).toInt()
        val roiBottom = (sh * 0.90f).toInt()
        for (item in ocrItems) {
            val cy = item.bounds.centerY()
            if (cy in roiTop..roiBottom && item.text.length <= 16) {
                val t = item.text.trim()
                // 排除彈窗標題（如『獲得票券！』『獲得金幣！』）與取消按鈕
                if (t.endsWith("！") || t.endsWith("!") || t == "獲得票券" || t == "獲得金幣" || t == "取消") {
                    continue
                }
                val matchesKeyword = OcrRecognizer.GAME_AD_TRIGGER_KEYWORDS.any { kw -> t.contains(kw, ignoreCase = true) }
                val hasRewardWord = Regex("""\d+\s*獲得""").containsMatchIn(t) || t.contains("看廣告") || t.contains("紅利")
                if ((matchesKeyword || hasRewardWord) &&
                    OcrRecognizer.GAME_AD_EXCLUDE_KEYWORDS.none { ex -> t.contains(ex, ignoreCase = true) } &&
                    !ocrRecognizer.isAreaInCooldown(ocrItems, cy - btnMarginY, cy + btnMarginY) &&
                    !templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy)
                ) {
                    val coins = parseRewardCoins(t)
                    candidates.add(PopupActionTarget.WatchAd("OCR[${t}](${coins}幣)", item.bounds) to coins)
                }
            }
        }

        // 2. 偵測下半部彈窗區 (y = 60% ~ 90%) 的整條珊瑚粉紅按鈕 (如時間放置蘋果的『▶ 8 獲得』、步數/首頁的『紅利 [▶] 15/60 獲得』)
        val pinkPopupBtn = templateMatcher.findCoralPinkButton(
            screenBitmap = screenBitmap,
            yStartRatio = 0.60f,
            yEndRatio = 0.90f,
            label = "紅利粉紅看廣告鈕"
        )
        if (pinkPopupBtn != null &&
            !ocrRecognizer.isAreaInCooldown(ocrItems, pinkPopupBtn.bounds.top - btnMarginY, pinkPopupBtn.bounds.bottom + btnMarginY) &&
            !templateMatcher.isPopupButtonDisabledGray(screenBitmap, pinkPopupBtn.bounds.centerY())
        ) {
            // 必須確認按鈕附近有「獲得 / 看廣告 / 紅利」字樣，且嚴格排除廣告推廣字眼，絕不點擊底層橫幅廣告！
            val nearbyOcr = ocrItems.firstOrNull {
                kotlin.math.abs(it.bounds.centerY() - pinkPopupBtn.bounds.centerY()) < (sh * 0.05f).toInt() &&
                !it.text.contains("取消") && !it.text.endsWith("！") &&
                OcrRecognizer.GAME_AD_EXCLUDE_KEYWORDS.none { ex -> it.text.contains(ex, ignoreCase = true) } &&
                (it.text.contains("獲得") || it.text.contains("看廣告") || it.text.contains("紅利"))
            }
            if (nearbyOcr != null) {
                val coins = parseRewardCoins(nearbyOcr.text).coerceAtLeast(1)
                // 實體珊瑚粉紅按鈕點擊命中率最高，加權 100 分確保優先於模糊零散文字
                candidates.add(PopupActionTarget.WatchAd("${pinkPopupBtn.label}(${coins}幣)", pinkPopupBtn.bounds) to (coins + 100))
            }
        }

        // 3. 偵測彈窗左側的珊瑚粉紅『紅利』小標籤 (特別針對『時間』分頁的白底黑框『紅利 [▶] x 1 獲得』按鈕！)
        val badgePopupBtn = templateMatcher.findPopupBonusBadgeAdButton(screenBitmap)
        if (badgePopupBtn != null &&
            !ocrRecognizer.isAreaInCooldown(ocrItems, badgePopupBtn.bounds.top - btnMarginY, badgePopupBtn.bounds.bottom + (sh * 0.06f).toInt()) &&
            !templateMatcher.isPopupButtonDisabledGray(screenBitmap, badgePopupBtn.bounds.centerY())
        ) {
            val nearbyBonusOcr = ocrItems.firstOrNull {
                kotlin.math.abs(it.bounds.centerY() - badgePopupBtn.bounds.centerY()) < (sh * 0.06f).toInt() &&
                !it.text.contains("取消") &&
                OcrRecognizer.GAME_AD_EXCLUDE_KEYWORDS.none { ex -> it.text.contains(ex, ignoreCase = true) } &&
                (it.text.contains("紅利") || it.text.contains("獲得") || it.text.contains("x 1") || it.text.contains("x1"))
            }
            if (nearbyBonusOcr != null || hasBottomModalTitle || hasBottomModalCancel) {
                candidates.add(PopupActionTarget.WatchAd(badgePopupBtn.label, badgePopupBtn.bounds) to (1 + 90))
            }
        }

        // 4. 若 OCR 看到『紅利』標籤 (y = 64% ~ 86%)，加入其正下方的看廣告按鈕
        val bonusTag = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf("紅利"),
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.05f, 0.64f, 0.50f, 0.86f),
            maxTextLength = 4
        )
        if (bonusTag != null) {
            val targetY = (bonusTag.bounds.bottom + (sh * 0.032f).toInt()).coerceAtMost((sh * 0.90f).toInt())
            if (!ocrRecognizer.isAreaInCooldown(ocrItems, targetY - btnMarginY, targetY + btnMarginY) &&
                !templateMatcher.isPopupButtonDisabledGray(screenBitmap, targetY)
            ) {
                val btnRect = Rect((sw * 0.30f).toInt(), targetY - 20, (sw * 0.70f).toInt(), targetY + 20)
                candidates.add(PopupActionTarget.WatchAd("OCR[紅利下方看廣告鈕]", btnRect) to 15)
            }
        }

        // 5. 使用者框選的「看廣告按鈕圖示」範本 (AD_TRIGGER_BTN)
        val adBtnTemplates = templateStorage.getTemplates(TemplateCategory.AD_TRIGGER_BTN).map { it.bitmap }
        if (adBtnTemplates.isNotEmpty()) {
            val tplMatch = templateMatcher.findBestTemplateMatch(
                screenBitmap = screenBitmap,
                templates = adBtnTemplates,
                roiNormalized = TemplateCategory.AD_TRIGGER_BTN.defaultSearchRoi,
                threshold = config.templateMatchThreshold,
                labelPrefix = "看廣告圖示",
                blacklistedCoord = FloatingOverlayService::isInsideOverlay
            )
            if (tplMatch != null &&
                !ocrRecognizer.isAreaInCooldown(ocrItems, tplMatch.bounds.top - btnMarginY, tplMatch.bounds.bottom + btnMarginY) &&
                !templateMatcher.isPopupButtonDisabledGray(screenBitmap, tplMatch.bounds.centerY())
            ) {
                candidates.add(PopupActionTarget.WatchAd(tplMatch.label, tplMatch.bounds) to 1)
            }
        }

        // 依照獲得金幣數量由大到小排序 (100 > 60 > 50 > 30 > 15 > 10 > 5 > 2 > 1 > 0)，選取金幣最高者！
        if (candidates.isNotEmpty()) {
            val bestCandidate = candidates.maxByOrNull { it.second } ?: candidates.first()
            return bestCandidate.first
        }

        return null
    }

    private fun transitionTo(newState: EngineState, detail: String, safeOverlayText: String) {
        if (newState != EngineState.IN_GAME_SCAN) {
            nonGameScreenTicks = 0
        }
        _uiStatus.value = _uiStatus.value.copy(
            state = newState,
            statusDetail = detail,
            overlaySafeDetail = safeOverlayText
        )
        appendLog("➡️ [${newState.title}] $detail")
    }

    private fun updateDetail(detail: String, safeOverlayText: String) {
        _uiStatus.value = _uiStatus.value.copy(
            statusDetail = detail,
            overlaySafeDetail = safeOverlayText
        )
    }

    private val _fullSessionLogs = java.util.Collections.synchronizedList(mutableListOf<String>())
    val fullSessionLogs: List<String>
        get() = synchronized(_fullSessionLogs) { _fullSessionLogs.toList() }

    private var sessionStartTime: Date = Date()

    fun appendLog(message: String) {
        val date = Date()
        if (message.contains("全自動巡邏已啟動")) {
            sessionStartTime = date
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
        val line = "[$timestamp] $message"
        synchronized(_fullSessionLogs) {
            if (_fullSessionLogs.size >= 5000) {
                _fullSessionLogs.removeAt(0)
            }
            _fullSessionLogs.add(line)
        }
        val updated = (_uiStatus.value.recentLogs + line).takeLast(40)
        _uiStatus.value = _uiStatus.value.copy(recentLogs = updated)
    }

    /**
     * 回傳安全適用於系統 Intent 分享 (IPC) 的格式化日誌。
     * 限制最大日誌筆數為 [maxLines]（預設 1,500 筆），避免 Android Binder 事務緩衝區 (1MB) 溢位觸發 TransactionTooLargeException。
     * 若日誌總量超出限制，自動截取最近 [maxLines] 筆並附帶安全提示說明。
     */
    fun getExportLogsFormatted(
        maxLines: Int = 1500,
        deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        osVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        appVersion: String = "v${try { com.linewalk.autoad.BuildConfig.VERSION_NAME } catch (_: Exception) { "1.15.30" }}"
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val startTimeStr = dateFormat.format(sessionStartTime)
        val exportTimeStr = dateFormat.format(Date())
        val logsSnapshot = fullSessionLogs
        val isTruncated = logsSnapshot.size > maxLines
        val targetLogs = if (isTruncated) logsSnapshot.takeLast(maxLines) else logsSnapshot

        val sb = StringBuilder()
        sb.append("========================================\n")
        sb.append("《LINE 散步趣助手》系統診斷日誌\n")
        sb.append("應用版本：$appVersion\n")
        sb.append("裝置型號：$deviceModel\n")
        sb.append("系統版本：$osVersion\n")
        sb.append("啟動時間：$startTimeStr\n")
        sb.append("匯出時間：$exportTimeStr\n")
        if (isTruncated) {
            sb.append("日誌筆數：${targetLogs.size} (總計 ${logsSnapshot.size} 筆，已截取最近 1,500 筆記錄)\n")
        } else {
            sb.append("日誌筆數：${targetLogs.size}\n")
        }
        sb.append("========================================\n\n")

        if (isTruncated) {
            sb.append("[注意：日誌量龐大，已匯出最近 1,500 筆記錄以防系統溢位。如需完整記錄請使用「複製日誌」功能]\n\n")
        }

        if (targetLogs.isEmpty()) {
            sb.append("(目前尚無執行日誌記錄)")
        } else {
            targetLogs.forEach { logLine ->
                sb.append(logLine).append("\n")
            }
        }
        return sb.toString()
    }

    /**
     * 回傳包含診斷標頭（應用版本、裝置型號、系統版本、啟動時間、日誌筆數）與完整歷史歷程之格式化日誌字串（不限筆數），供剪貼簿完整複製使用。
     */
    fun getFullLogsFormatted(
        deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        osVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        appVersion: String = "v${try { com.linewalk.autoad.BuildConfig.VERSION_NAME } catch (_: Exception) { "1.15.30" }}"
    ): String {
        return getExportLogsFormatted(
            maxLines = Int.MAX_VALUE,
            deviceModel = deviceModel,
            osVersion = osVersion,
            appVersion = appVersion
        )
    }

    fun clearFullLogs() {
        synchronized(_fullSessionLogs) {
            _fullSessionLogs.clear()
        }
        _uiStatus.value = _uiStatus.value.copy(recentLogs = emptyList())
    }

    companion object {
        @Volatile
        private var INSTANCE: AutomationEngine? = null

        fun getInstance(context: Context): AutomationEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AutomationEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
