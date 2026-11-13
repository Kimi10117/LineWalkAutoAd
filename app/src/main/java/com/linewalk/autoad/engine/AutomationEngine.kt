package com.linewalk.autoad.engine

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import com.linewalk.autoad.config.AppConfig
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
import com.linewalk.autoad.vision.ScreenViewportCalibrator
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
    val currentTab: MainTab = MainTab.MOVE,
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
    val yRatio: Float = 0.165f
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

    private val nodeInspector = NodeInspector()
    private val ocrRecognizer = OcrRecognizer()
    private val templateMatcher = TemplateMatcher()
    private val adCloserHandler = AdCloserHandler(
        ocrRecognizer = ocrRecognizer,
        nodeInspector = nodeInspector,
        templateMatcher = templateMatcher,
        config = config
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
     * 1. 移動 (首頁) -> 2. 時間 (放置) -> 3. 遊戲 -> 4. 步數
     */
    private val tabPriorityOrder = listOf(MainTab.MOVE, MainTab.TIME, MainTab.GAME, MainTab.STEP)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loopJob: Job? = null

    private var popupRetryTicks = 0
    private var pendingMissionDirectClaim = false
    private var rewardClaimRetryTicks = 0
    private var adWatchdogCooldownTicks = 0
    internal var consecutiveWatchdogTimeouts = 0
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
    internal var tabStagnationTicks: Int = 0
    private var lastObservedTab: MainTab? = null
    internal var nonMainScreenIdleTicks: Int = 0

    var currentPhase: PatrolPhase = PatrolPhase.MAIN_TABS
        internal set
    var isBingoCompleted = false
        internal set
    var isLotteryCompleted = false
        internal set
    private var hasClickedLotteryAdReward = false
    private var lotteryResultRewardWaitTicks = 0
    private var lotteryResultCloseAttempts = 0
    private var lotteryFiftyDrawAttempts = 0
    private var lotteryFreeDrawAttempts = 0
    private var lotteryFiveDrawAttempts = 0
    var isMissionCompleted = false
        internal set
    internal var missionOpenCycleCount = 0
    var isReceiveGiftCompleted = false
        internal set
    var isFriendGiftCompleted = false
        internal set
    private val checkedMissionTabs = mutableSetOf<String>()
    private var friendGiftEmptyTicks = 0
    private var friendGiftReturnHomeTicks = 0
    private var friendGiftOpenDrawerTicks = 0
    private var friendGiftDialogRetryCount = 0
    private var pendingGiftButtonClickPoint: android.graphics.Point? = null
    internal val claimedBingoColumns = mutableSetOf<Int>()
    internal val bingoColumnRetryCount = mutableMapOf<Int, Int>()
    internal var bingoOpenCellRetryCount = 0
    internal var bingoDialogCloseAttempts = 0
    internal var lotteryDialogCloseAttempts = 0
    internal var missionDialogCloseAttempts = 0
    internal var subDialogOpenAttempts = 0
    private var currentPhaseEmptyTicks = 0
    internal var nonGameScreenTicks = 0
    internal var geoXConsecutiveClicks = 0
    private var externalAppTicks = 0
    private var lastActiveGamePackage: String? = null
    private var legitSkipRedirectPkg: String? = null
    private var legitSkipRedirectStartMs: Long = 0L

    // 系統診斷日誌彙整計數與螢幕狀態快取 (U1)
    internal var timeoutForceEscapeCount = 0
    private var externalRedirectPlayCount = 0
    private var externalRedirectBrowserCount = 0
    private var externalRedirectOtherCount = 0
    internal var backRejectCount = 0
    private var lastLoggedBackRejectReason: String? = null
    private var lastViewportMetricsCache: ScreenViewportCalibrator.ViewportMetrics? = null
    private var lastCapturedScreenWidth: Int = 0
    private var lastCapturedScreenHeight: Int = 0

    // 廣告載入錯誤與無庫存計數守護
    internal var adLoadErrorCountPerTab: MutableMap<MainTab, Int> = mutableMapOf()
    internal var consecutiveNetworkErrorCount = 0

    // 死鎖斷路器 (Circuit Breaker) 與遊戲內促銷重試
    internal var sameTargetClickCount = 0
    internal var lastTargetClickName: String? = null
    internal var lastTargetClickRect: Rect? = null
    internal var lastTargetClickTimeMs: Long = 0L
    internal var closeNodeConsecutiveClicks = 0
    internal var lastCloseNodeLabel: String? = null
    private var inGameLinePromoRetryCount = 0

    private val _uiStatus = MutableStateFlow(EngineUiStatus())
    val uiStatus: StateFlow<EngineUiStatus> = _uiStatus.asStateFlow()

    internal fun resetClickCircuitBreaker() {
        sameTargetClickCount = 0
        lastTargetClickName = null
        lastTargetClickRect = null
        lastTargetClickTimeMs = 0L
        closeNodeConsecutiveClicks = 0
        lastCloseNodeLabel = null
    }

/**
     * 連續點擊死鎖斷路器 (Circuit Breaker)：
     * 當針對同一個按鈕/目標於短時間內連續點擊達 3 次且畫面無響應時觸發！
     * 若為關閉/取消類目標，發送 Android 系統返回鍵 (BACK) 強制關閉彈窗；
     * 若為一般目標，自動重置並推進狀態以打破死鎖！
     */
    internal fun checkClickCircuitBreaker(
        service: AutoAdAccessibilityService,
        targetName: String,
        targetRect: Rect
    ): Boolean {
        val now = System.currentTimeMillis()
        if (lastTargetClickTimeMs > 0L && now - lastTargetClickTimeMs > 20_000L) {
            sameTargetClickCount = 0
            lastTargetClickName = null
            lastTargetClickRect = null
        }
        lastTargetClickTimeMs = now

        val isSameTarget = lastTargetClickName == targetName ||
            (lastTargetClickRect != null &&
             kotlin.math.abs(lastTargetClickRect!!.centerX() - targetRect.centerX()) < 40 &&
             kotlin.math.abs(lastTargetClickRect!!.centerY() - targetRect.centerY()) < 40)

        if (isSameTarget) {
            sameTargetClickCount++
            if (sameTargetClickCount >= 3) {
                appendLog("[斷路器] 連續 $sameTargetClickCount 次點擊【$targetName】畫面無響應，啟動安全防死鎖斷路機制！")
                val isCloseTarget = targetName.contains("關閉") ||
                    targetName.contains("取消") ||
                    targetName.contains("X") ||
                    targetName.contains("OK")
                if (isCloseTarget) {
                    appendLog("[斷路器] 發送系統返回鍵 (BACK) 強制關閉卡死彈窗！")
                    service.navigateBack()
                }
                resetClickCircuitBreaker()
                return true
            }
        } else {
            sameTargetClickCount = 1
            lastTargetClickName = targetName
            lastTargetClickRect = targetRect
        }
        return false
    }

    fun evaluateForegroundPackage(
        fgPkg: String?,
        hasAdGeometricX: Boolean = false,
        isWatchingAd: Boolean = false
    ): GuardDecision {
        // 前景未知（系統短暫無焦點）、本助手 App 自身或常駐動態島/輸入法等覆蓋工具：不誤判為外部跳轉，等待底層前景穩定
        if (fgPkg.isNullOrBlank() || fgPkg == context.packageName || AppConfig.isPassiveOverlayUtilityPackage(fgPkg)) {
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
            val isSamsungOrPlay = fgPkg == "com.android.vending" ||
                (fgPkg != null && (fgPkg.startsWith("com.samsung.android") || fgPkg.startsWith("com.sec.android")))
            val action = if (hasAdGeometricX && !isSamsungOrPlay) GuardAction.CLICK_AD_CLOSE_X else GuardAction.SAFE_BACK_NAVIGATION
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
            timeoutForceEscapeCount = 0
            consecutiveWatchdogTimeouts = 0
            externalRedirectPlayCount = 0
            externalRedirectBrowserCount = 0
            externalRedirectOtherCount = 0
            backRejectCount = 0
        }
        legitSkipRedirectPkg = null
        legitSkipRedirectStartMs = 0L
        lastLoggedBackRejectReason = null
        resetClickCircuitBreaker()
        inGameLinePromoRetryCount = 0
        popupRetryTicks = 0
        pendingMissionDirectClaim = false
        rewardClaimRetryTicks = 0
        nonGameScreenTicks = 0
        emptyTicksOnCurrentTab = 0
        tabStagnationTicks = 0
        lastObservedTab = null
        nonMainScreenIdleTicks = 0
        confirmedEmptyTabs.clear()
        adLoadErrorCountPerTab.clear()
        consecutiveNetworkErrorCount = 0
        isBingoCompleted = false
        claimedBingoColumns.clear()
        bingoColumnRetryCount.clear()
        bingoOpenCellRetryCount = 0
        bingoDialogCloseAttempts = 0
        subDialogOpenAttempts = 0
        isLotteryCompleted = false
        resetLotterySessionState(resetCloseAttempts = true)
        timeTask.hasTriedBonusBerryInCurrentStay = false
        isMissionCompleted = false
        missionOpenCycleCount = 0
        isReceiveGiftCompleted = false
        isFriendGiftCompleted = false
        checkedMissionTabs.clear()
        friendGiftEmptyTicks = 0
        friendGiftReturnHomeTicks = 0
        friendGiftOpenDrawerTicks = 0
        friendGiftDialogRetryCount = 0
        pendingGiftButtonClickPoint = null
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
            PatrolPhase.MAIN_TABS -> "移動 -> 時間 -> 遊戲 -> 步數"
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appendLog("[錯誤] 循環發生例外: ${e.javaClass.simpleName}: ${e.message}")
                    val acc = AutoAdAccessibilityService.instance.value
                    appendLog(buildPhoneStateSnapshot("LOOP_EXCEPTION(${e.javaClass.simpleName})", acc))
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
        resetClickCircuitBreaker()
        consecutiveNetworkErrorCount = 0
        consecutiveWatchdogTimeouts = 0
        inGameLinePromoRetryCount = 0
        _uiStatus.value = _uiStatus.value.copy(
            isRunning = false,
            state = EngineState.IDLE,
            statusDetail = reason,
            overlaySafeDetail = "[待命] $reason"
        )
        if (reason == "使用者手動暫停") {
            appendLog("[控制] 使用者手動暫停巡邏（工作階段平穩停止）")
        } else {
            appendLog("[待命] $reason")
        }
    }

    fun toggle() {
        if (_uiStatus.value.isRunning) {
            stop()
        } else {
            start()
        }
    }

    /**
     * 動態解析使用者已安裝／偏好之《LINE 散步趣》套件名稱（支援台灣版 LGWKTW 與日版／國際版 LGWALK）。
     */
    internal fun resolvePreferredGamePackage(
        service: AutoAdAccessibilityService? = AutoAdAccessibilityService.instance.value
    ): String {
        val candidates = mutableListOf<String>()
        lastActiveGamePackage?.takeIf { AppConfig.isGamePackage(it) }?.let { candidates.add(it) }
        config.lastDetectedGamePackage?.takeIf { AppConfig.isGamePackage(it) }?.let { candidates.add(it) }
        config.targetGamePackages
            .filter { AppConfig.isGamePackage(it) }
            .forEach { candidates.add(it) }
        AppConfig.GAME_PACKAGE_PRIORITY_ORDER.forEach { candidates.add(it) }

        val uniqueCandidates = candidates.distinct()
        val pm = service?.packageManager ?: context.packageManager
        for (pkg in uniqueCandidates) {
            try {
                if (pm.getLaunchIntentForPackage(pkg) != null) {
                    return pkg
                }
            } catch (_: Exception) {
            }
        }
        return uniqueCandidates.firstOrNull() ?: "com.linecorp.LGWKTW"
    }

    private fun recordBackRejectIfAny(
        service: AutoAdAccessibilityService,
        contextLabel: String,
        screenBitmap: android.graphics.Bitmap? = null,
        ocrItems: List<OcrItem> = emptyList()
    ) {
        val reason = service.lastBackRejectReason
        if (reason.isBlank()) return
        backRejectCount++
        if (reason != lastLoggedBackRejectReason) {
            lastLoggedBackRejectReason = reason
            appendLog("[返回遭拒] $contextLabel ($reason) | 累計=$backRejectCount")
            appendLog(buildPhoneStateSnapshot("BACK_REJECT:$reason", service, screenBitmap, ocrItems))
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
        if (AppConfig.isGamePackage(fgPkg)) {
            lastActiveGamePackage = fgPkg
            if (config.lastDetectedGamePackage != fgPkg) {
                config.lastDetectedGamePackage = fgPkg
            }
        }

        // 0. 第三方官方廣告容器優先處理 (Meta Audience Network com.facebook.katana, Google AdMob, Unity, AppLovin, Chrome Custom Tabs 等)：
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
        } else if (AppConfig.isExternalSocialAppPackage(fgPkg)) {
            // 0A. 嚴格攔截非廣告容器之外部社群應用程式（如誤跳轉至獨立 Instagram / Threads / Messenger）：
            // 一旦跳出至外部社群 App，立即發送系統返回鍵退出並拉回《LINE 散步趣》！
            externalAppTicks++
            if (externalAppTicks == 1) {
                externalRedirectOtherCount++
            }
            appendLog("[防護] 偵測到跳轉外部社群應用 ($fgPkg)，立即發送返回鍵回到散步趣 (連續 ${externalAppTicks} 次)")
            adCloserHandler.markRecentRedirect()
            if (!service.navigateBackFromExternalSocialApp(minIntervalMs = 600L)) {
                recordBackRejectIfAny(service, "SocialAppGuard($fgPkg)")
            }
            if (externalAppTicks >= 2) {
                val targetPkg = resolvePreferredGamePackage(service)
                service.bringAppToFront(targetPkg)
                externalAppTicks = 0
            }
            if (currentState != EngineState.AD_WATCHING) {
                adCloserHandler.onEnterAdMode(preserveRedirectState = true)
                transitionTo(
                    newState = EngineState.AD_WATCHING,
                    detail = "已從外部社群應用 ($fgPkg) 返回散步趣，繼續監控中...",
                    safeOverlayText = "[防護] 返回散步趣中..."
                )
            }
            return
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
            externalAppTicks = 0
            legitSkipRedirectPkg = null
            legitSkipRedirectStartMs = 0L
            lastLoggedBackRejectReason = null
        }
        val screenBitmap = service.captureScreenBitmap()
        if (screenBitmap == null) {
            updateDetail("[提示] 無法擷取螢幕畫面，等待重試...", "[提示] 等待畫面擷取...")
            return
        }

        try {
            val rawOcrItems = ocrRecognizer.recognize(screenBitmap)
            val ocrItems = rawOcrItems.filterNot { item ->
                FloatingOverlayService.isInsideOverlay(item.bounds.centerX(), item.bounds.centerY())
            }
            lastCapturedScreenWidth = screenBitmap.width
            lastCapturedScreenHeight = screenBitmap.height
            lastViewportMetricsCache = ScreenViewportCalibrator.detectViewport(
                sw = screenBitmap.width,
                sh = screenBitmap.height,
                screenBitmap = screenBitmap,
                ocrItems = ocrItems
            )

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

    private fun bringGameToFrontAndRestore(
        service: AutoAdAccessibilityService,
        preserveWatchingState: Boolean,
        restoreReason: String
    ) {
        val wasWatchingAd = _uiStatus.value.state == EngineState.AD_WATCHING
        val targetPkg = resolvePreferredGamePackage(service)
        service.bringAppToFront(targetPkg)
        externalAppTicks = 0
        legitSkipRedirectPkg = null
        legitSkipRedirectStartMs = 0L
        if (preserveWatchingState && wasWatchingAd) {
            appendLog("[防護] $restoreReason，保留 AD_WATCHING 廣告監控與計時上下文不歸零")
            updateDetail("已拉回散步趣，繼續監控廣告畫面...", "[廣告] 恢復監控中...")
        } else {
            transitionTo(EngineState.IN_GAME_SCAN, "已拉回散步趣，恢復遊戲巡邏...", "[巡邏] 巡邏畫面目標中...")
        }
    }

    internal suspend fun handleExternalAppGuard(
        service: AutoAdAccessibilityService,
        fgPkg: String?
    ) {
        externalAppTicks++
        val externalPkg = fgPkg ?: "未知外部應用"
        val safePkgLabel = if (externalPkg.length > 20) externalPkg.takeLast(20) else externalPkg
        if (externalAppTicks == 1) {
            when {
                externalPkg == "com.android.vending" -> externalRedirectPlayCount++
                AppConfig.isBrowserPackage(externalPkg) -> externalRedirectBrowserCount++
                else -> externalRedirectOtherCount++
            }
        }

        // 系統桌面極速復原：若前景為桌面 (Samsung Launcher 或原生 Launcher)，返回鍵無效，立即在第 1 次 tick 強制喚醒散步趣！
        val isLauncher = externalPkg == "com.sec.android.app.launcher" ||
            externalPkg.contains("launcher", ignoreCase = true)
        if (isLauncher) {
            appendLog("[防護] 前景為系統桌面 ($externalPkg)，立即強制喚醒散步趣拉回前景！")
            appendLog(buildPhoneStateSnapshot("LAUNCHER_RECOVERY", service))
            bringGameToFrontAndRestore(service, preserveWatchingState = false, restoreReason = "系統桌面強制喚醒")
            return
        }

        // 合法跳轉 Play 商店／瀏覽器略過與安全返回 (U3)：
        // 若源自廣告預期略過按鈕（Google Play / 略過 / 快轉），保留約 2.0s 獎勵註冊緩衝再安全按 BACK 返回
        val lastClickedLabel = adCloserHandler.getLastClickedAdLabel()
        val isBrowserOrStore = externalPkg == "com.android.vending" ||
            externalPkg == "com.sec.android.app.sbrowser" ||
            externalPkg.startsWith("com.sec.android") ||
            externalPkg == "com.android.chrome" ||
            AppConfig.isPlayStoreOrBrowserPackage(externalPkg)
        val isExpectedSkipLabel = lastClickedLabel != null && (
            lastClickedLabel.contains("Google Play", ignoreCase = true) ||
            lastClickedLabel.contains("略過") ||
            lastClickedLabel.contains("快轉")
        )
        val isLegitSkipRedirect = AppConfig.isPlayStoreOrBrowserPackage(externalPkg) && isExpectedSkipLabel

        if (isLegitSkipRedirect && externalAppTicks < 3) {
            val now = System.currentTimeMillis()
            if (legitSkipRedirectPkg != externalPkg || legitSkipRedirectStartMs <= 0L) {
                legitSkipRedirectPkg = externalPkg
                legitSkipRedirectStartMs = now
            }
            val elapsedMs = now - legitSkipRedirectStartMs
            _uiStatus.value = _uiStatus.value.copy(
                statusDetail = "[緩衝] 預期略過跳轉 ($externalPkg)，等待獎勵註冊...",
                overlaySafeDetail = "[略過緩衝] 等待返回 ($safePkgLabel)"
            )
            if (elapsedMs < 2000L) {
                val remainMs = 2000L - elapsedMs
                if (externalAppTicks == 1 && remainMs > 1200L) {
                    appendLog("[防護] 偵測到預期略過跳轉 ($externalPkg | 來源=$lastClickedLabel)，保留約 2.0s 獎勵註冊緩衝...")
                    return
                }
                delay(remainMs.coerceIn(0L, 1400L))
            }
            val postWaitFg = service.getForegroundPackageName()
            if (AppConfig.isGamePackage(postWaitFg) || AppConfig.isAdPackage(postWaitFg)) {
                externalAppTicks = 0
                legitSkipRedirectPkg = null
                legitSkipRedirectStartMs = 0L
                return
            }
            if (postWaitFg != null && (postWaitFg == "com.sec.android.app.launcher" || postWaitFg.contains("launcher", ignoreCase = true))) {
                appendLog("[防護] 緩衝後前景為系統桌面 ($postWaitFg)，立即強制喚醒散步趣拉回前景！")
                bringGameToFrontAndRestore(service, preserveWatchingState = true, restoreReason = "預期略過緩衝後桌面喚醒")
                return
            }
            appendLog("[防護] 預期略過跳轉 ($externalPkg | 來源=$lastClickedLabel) 獎勵註冊緩衝完成，發送安全返回鍵回到遊戲")
            if (!service.safeNavigateBack(minIntervalMs = 800L)) {
                recordBackRejectIfAny(service, "LegitSkipReturn($externalPkg)")
            }
            delay(350)
            val postBackFg = service.getForegroundPackageName()
            if (postBackFg != null && (postBackFg == "com.sec.android.app.launcher" || postBackFg.contains("launcher", ignoreCase = true))) {
                appendLog("[防護] 安全返回後偵測系統桌面 ($postBackFg)，立即強制喚醒散步趣防退回桌面！")
                bringGameToFrontAndRestore(service, preserveWatchingState = true, restoreReason = "安全返回後桌面喚醒")
            } else if (AppConfig.isGamePackage(postBackFg)) {
                externalAppTicks = 0
            }
            legitSkipRedirectPkg = null
            legitSkipRedirectStartMs = 0L
            return
        }

        appendLog("[防護] 偵測到非散步趣應用 ($externalPkg)，啟動嚴格防誤點保護 (連續 ${externalAppTicks} 次)")
        adCloserHandler.markRecentRedirect(fromExternalApp = true)
        _uiStatus.value = _uiStatus.value.copy(
            statusDetail = "[防護] 前景非散步趣 ($externalPkg)",
            overlaySafeDetail = "[外部保護] 非散步趣App ($safePkgLabel)"
        )

        // 外部工具與瀏覽器極速復原：連續 2 個 tick (約 2.5 秒) 前景仍為三星瀏覽器/Chrome/Play 商店，立即強制喚醒散步趣拉回前景！
        if (externalAppTicks >= 2 && isBrowserOrStore) {
            appendLog("[防護] 停留外部瀏覽器/商店 ($externalPkg) 達 2 次 tick，立即強制喚醒散步趣拉回前景！")
            bringGameToFrontAndRestore(service, preserveWatchingState = true, restoreReason = "外部瀏覽器/商店強制拉回 ($externalPkg)")
            return
        }

        // 逾時復原：連續外部應用超過 3 個 tick (約 4 秒)，強制將散步趣喚醒拉回前景！
        if (externalAppTicks >= 3) {
            appendLog("[防護] 停留外部應用達 3 次 tick 逾時，嘗試強制喚醒散步趣拉回前景！")
            appendLog(buildPhoneStateSnapshot("EXTERNAL_APP_TIMEOUT($externalPkg)", service))
            bringGameToFrontAndRestore(service, preserveWatchingState = true, restoreReason = "外部應用逾時強制拉回 ($externalPkg)")
            return
        }

        // 外部工具與商店嚴格保護：若前景為 Google Play 或 Samsung 邊緣工具/截圖/瀏覽器，嚴禁幾何 X 盲點，直接發送安全返回鍵退出！
        val isSamsungOrPlay = externalPkg == "com.android.vending" ||
            externalPkg.startsWith("com.samsung.android") ||
            externalPkg.startsWith("com.sec.android") ||
            externalPkg == "com.android.chrome"
        if (isSamsungOrPlay) {
            appendLog("[防護] 前景為系統工具/應用商店/瀏覽器 ($externalPkg)，直接發送安全返回鍵退出")
            if (!service.safeNavigateBack(minIntervalMs = 800L)) {
                recordBackRejectIfAny(service, "ExternalStoreOrSystem($externalPkg)")
            }
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
        if (!service.safeNavigateBack()) {
            recordBackRejectIfAny(service, "ExternalAppFallback($externalPkg)")
        }
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

        // 2. 排除主畫面頂部分頁 (cy in 0.05f..0.30f)
        val hasMainTabs = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            cy in 0.05f..0.30f && (
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

    internal suspend fun handleInGameScan(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 0. 最優先檢查：是否跳出「無法載入廣告 / 請稍後再試。」或網路連線錯誤提示彈窗！
        val adErrorClose = ocrRecognizer.findAdLoadErrorCloseButton(screenBitmap, ocrItems)
        if (adErrorClose != null) {
            val isNetworkErr = ocrRecognizer.isNetworkErrorDialog(ocrItems)
            if (isNetworkErr) {
                consecutiveNetworkErrorCount++
            } else {
                consecutiveNetworkErrorCount = 0
            }

            // 斷路器檢查：若同一個錯誤彈窗連續點擊達 3 次無效，直接發送系統返回鍵 (BACK) 破除死鎖
            if (checkClickCircuitBreaker(service, adErrorClose.first, adErrorClose.second)) {
                adLoadErrorCountPerTab[currentTab] = 0
                if (currentPhase == PatrolPhase.MAIN_TABS && !isNetworkErr) {
                    appendLog("[斷路器] 「無法載入廣告」彈窗連續點擊未響應，發送系統返回鍵並強制換頁！")
                    forceAdvanceToNextTab(service, sw, sh, ocrItems, screenBitmap, "錯誤彈窗連續未響應，斷路器強制換頁")
                } else {
                    val waitMs = if (consecutiveNetworkErrorCount >= 4) 8000L else 5000L
                    appendLog("[斷路器] 網路或系統錯誤彈窗持續出現，已發送返回鍵並暫候 ${waitMs / 1000}s 待連線恢復...")
                    updateDetail("[網路等待] 錯誤彈窗持續出現，暫候連線恢復...", "[等待] 連線異常暫候")
                    delay(waitMs)
                }
                return
            }

            if (isNetworkErr) {
                appendLog("[錯誤/網路] 偵測到網路或連線錯誤彈窗，自動點擊 [關閉] 按鈕 (連續 $consecutiveNetworkErrorCount 次)...")
                FloatingOverlayService.clickWithOverlayPassthrough(adErrorClose.second) {
                    service.clickRect(adErrorClose.second)
                }
                if (consecutiveNetworkErrorCount >= 2) {
                    val backoffMs = if (consecutiveNetworkErrorCount >= 4) 8000L else 4000L
                    updateDetail("[網路等待] 連線異常，暫候 ${backoffMs / 1000}s 待網路恢復...", "[等待] 連線異常 (${backoffMs / 1000}s)")
                    delay(backoffMs)
                } else {
                    updateDetail("[重試] 已關閉連線錯誤彈窗，等待恢復...", "[重試] 關閉連線錯誤")
                    delay(800)
                }
                return
            }

            // 分頁廣告無庫存 (Ad Starvation) 守護：同分頁累計 2 次載入失敗，直接推進下一分頁！
            val errorCount = (adLoadErrorCountPerTab[currentTab] ?: 0) + 1
            adLoadErrorCountPerTab[currentTab] = errorCount

            if (errorCount >= 2) {
                appendLog("[無庫存] 分頁【${currentTab.tabName}】廣告無庫存或連續載入失敗 (累計 $errorCount 次)，關閉彈窗並切換至下一分頁！")
                FloatingOverlayService.clickWithOverlayPassthrough(adErrorClose.second) {
                    service.clickRect(adErrorClose.second)
                }
                delay(300)
                service.navigateBack()
                adLoadErrorCountPerTab[currentTab] = 0
                forceAdvanceToNextTab(service, sw, sh, ocrItems, screenBitmap, "分頁【${currentTab.tabName}】廣告無庫存")
                return
            }

            appendLog("[錯誤] 偵測到「無法載入廣告」彈窗，自動點擊 [關閉] 按鈕 (嘗試 $errorCount/2)...")
            FloatingOverlayService.clickWithOverlayPassthrough(adErrorClose.second) {
                service.clickRect(adErrorClose.second)
            }
            updateDetail("[重試] 已關閉廣告錯誤彈窗，重試當前分頁...", "[重試] 關閉錯誤重試中...")
            delay(600)
            return
        }
        consecutiveNetworkErrorCount = 0

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
        if (ocrRecognizer.isAdLoadingScreen(ocrItems, screenBitmap)) {
            appendLog("[載入] 畫面處於廣告載入中狀態，自動轉入廣告監控流程！")
            adCloserHandler.onEnterAdMode()
            transitionTo(EngineState.AD_WATCHING, "廣告正在載入中，請稍候...", "[載入] 廣告載入中...")
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
        // 嚴格防呆：若當前畫面上存在下半部看廣告按鈕（紅利 / 跳過廣告 / 取消），絕不誤判為中央領獎彈窗！
        val isBottomRewardSheetVisible = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            (cy in 0.85f..0.98f && (t == "取消" || t.contains("取消"))) ||
            (cy in 0.70f..0.92f && (t.contains("跳過廣告") || t.contains("紅利")))
        }
        val modalCloseBtn = if (!isBottomRewardSheetVisible) {
            ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)
        } else {
            null
        }
        if (modalCloseBtn != null) {
            emptyTicksOnCurrentTab = 0
            bingoColumnRetryCount.clear()
            if (checkClickCircuitBreaker(service, modalCloseBtn.first, modalCloseBtn.second)) {
                delay(600)
                return
            }
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

        // A-1. 【抽抽樂中獎結果彈窗最高優先權與重疊 Bug 處理】
        // 針對實機問題 (Screenshot_20261108_022009_LINE .jpg)：
        // 散步趣已知 Bug：播放全螢幕廣告時，抽抽樂中獎結果視窗可能擋在廣告前面！
        // 1) 若中獎視窗背後露出了廣告特徵（如「還沒有邀請好友 實在太可惜了！」或頂部廣告關閉鈕）：
        //    只能點擊中獎彈窗底部的 [關閉] 按鈕關閉中獎視窗！等中獎視窗關閉後，下一個 Tick 再點擊廣告右上角 (X) 退出！
        // 2) 若背後無廣告（正常中獎）：維持最佳策略，交由 handleLotteryDialog 點擊綠色 [▶ x1 獲得] 看廣告多賺彩券！
        // 嚴格安全互斥：若畫面處於任務彈窗、賓果彈窗或好友彈窗，絕對不可被抽抽樂結果攔截！
        val isOtherSubDialogOpenForLottery = missionTask.isMissionDialogOpen(ocrItems, sh, screenBitmap) ||
                bingoTask.isBingoDialogOpen(ocrItems, sh) ||
                friendGiftTask.isFriendsPage(ocrItems, sh) ||
                receiveGiftTask.isReceiveGiftDialogOpen(ocrItems, sh)

        if (!isOtherSubDialogOpenForLottery && lotteryTask.isLotteryWinResultScreen(ocrItems, sh, screenBitmap)) {
            nonGameScreenTicks = 0
            subDialogOpenAttempts = 0
            val hasBgAd = lotteryTask.hasBackgroundAdBehindLotteryResult(ocrItems, sh) || ocrRecognizer.isLineInHousePromoAd(ocrItems)
            if (hasBgAd) {
                appendLog("[抽抽樂] 偵測到抽抽樂中獎彈窗與背景廣告重疊 Bug，依規則優先點擊前景彈窗底部的 [關閉] 按鈕！")
                val resultCloseBtn = lotteryTask.findLotteryResultCloseButton(screenBitmap, ocrItems)
                    ?: Rect((sw * 0.15f).toInt(), (sh * 0.76f).toInt(), (sw * 0.85f).toInt(), (sh * 0.86f).toInt())
                FloatingOverlayService.clickWithOverlayPassthrough(resultCloseBtn) {
                    service.clickRect(resultCloseBtn, allowTopSafeZone = true)
                }
                delay(400)
                return
            } else {
                handleLotteryDialog(service, screenBitmap, ocrItems, sw, sh)
                return
            }
        }

        // A0. 檢查是否跳出 LINE 內部宣傳促銷全螢幕廣告（如『邀請好友 快來領取吧！！』『還沒有邀請好友 實在太可惜了！』）
        // 嚴格安全防護：若畫面為主畫面四大分頁（首頁/步數/時間/遊戲）或具有步數/導覽列特徵，絕不可誤判！
        // 且嚴格排除抽抽樂中獎結果視窗（避免被中獎視窗擋住時狂點背景右上角 X）！
        val onMainScreenForPromo = ocrRecognizer.isLineWalkMainScreenVisible(screenBitmap, ocrItems, templateMatcher)
        if (!onMainScreenForPromo && ocrRecognizer.isLineInHousePromoAd(ocrItems) && !lotteryTask.isLotteryWinResultScreen(ocrItems, sh, screenBitmap)) {
            nonGameScreenTicks = 0
            handleInGameLinePromoClose(service, screenBitmap, sw, sh, "偵測到 LINE 宣傳廣告/促銷彈窗")
            delay(500)
            return
        } else {
            inGameLinePromoRetryCount = 0
        }

        // B. 【子視窗與日常任務優先】優先檢查是否處於日常任務子彈窗或好友分頁（絕不可被底層導覽列誤判為主畫面而忽略已開啟的視窗！）：

        // B0B. 【最高優先】：優先檢查是否跳出好友送禮「贈送完畢」彈窗（已贈送金幣給你自己和XXX。[關閉]）！
        val sentCompletedCloseBtn = friendGiftTask.findSentCompletedCloseButton(screenBitmap, ocrItems)
        if (sentCompletedCloseBtn != null) {
            nonGameScreenTicks = 0
            // 嚴禁在此清空 giftedPoints，保留已贈送好友之記憶
            pendingGiftButtonClickPoint?.let { friendGiftTask.markGifted(it) }
            pendingGiftButtonClickPoint = null
            appendLog("[禮物]【好友送禮】偵測到「贈送完畢」彈窗，點擊 [關閉] 繼續流程！")
            FloatingOverlayService.clickWithOverlayPassthrough(sentCompletedCloseBtn) {
                service.clickRect(sentCompletedCloseBtn)
            }
            updateDetail("[禮物] 關閉贈送完畢視窗...", "[禮物] 關閉彈窗")
            delay(500)
            return
        }

        // B1. 優先檢查是否正在「每日賓果」彈窗
        if (bingoTask.isBingoDialogOpen(ocrItems, sh)) {
            nonGameScreenTicks = 0
            subDialogOpenAttempts = 0
            // 防護：若賓果視窗前方疊加了「1 跳過廣告糖果到手！[關閉]」等中央領獎彈窗，必須最優先點擊中央 [關閉] 按鈕！
            val foregroundCloseBtn = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)
            if (foregroundCloseBtn != null) {
                appendLog("[完成] 點擊賓果前景領獎彈窗: ${foregroundCloseBtn.first}")
                FloatingOverlayService.clickWithOverlayPassthrough(foregroundCloseBtn.second) {
                    service.clickRect(foregroundCloseBtn.second)
                }
                delay(400)
                return
            }
            if (isBingoCompleted) {
                bingoDialogCloseAttempts++
                if (bingoDialogCloseAttempts >= 6) {
                    appendLog("[賓果] 每日賓果視窗連續嘗試關閉達 $bingoDialogCloseAttempts 次，點擊底部【集點】強制重置回主畫面並推進下一階段！")
                    bingoDialogCloseAttempts = 0
                    currentPhase = getNextPhase(PatrolPhase.BINGO)
                    val mainTabBtn = friendGiftTask.getBottomMainTabRect(sw, sh, ocrItems, screenBitmap)
                    FloatingOverlayService.clickWithOverlayPassthrough(mainTabBtn) {
                        service.clickRect(mainTabBtn)
                    }
                    delay(400)
                    return
                }
                val closeBtn = if (bingoDialogCloseAttempts == 5) {
                    // 第 5 次嘗試點擊白底面板上方半透明黑幕遮罩區 (x=50%, y=5.5%)
                    Rect((sw * 0.40f).toInt(), (sh * 0.045f).toInt(), (sw * 0.60f).toInt(), (sh * 0.068f).toInt())
                } else {
                    bingoTask.findCloseButton(ocrItems, sw, sh)
                        ?: bingoTask.getCloseButtonBounds(sw, sh, ocrItems, screenBitmap, attempt = bingoDialogCloseAttempts)
                }
                appendLog("[賓果] 每日賓果已完成，點擊右上角 (X) 關閉視窗 (嘗試: $bingoDialogCloseAttempts/5)")
                FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                    service.clickRect(closeBtn, allowTopSafeZone = true)
                }
                delay(350)
                return
            }
            handleBingoDialog(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // B2. 檢查是否正在「抽抽樂」彈窗
        if (lotteryTask.isLotteryDialogOpen(ocrItems, sh)) {
            nonGameScreenTicks = 0
            subDialogOpenAttempts = 0
            // 前景模態彈窗最優先防護：若抽抽樂面板前方疊加「獲得 1 張票券！[關閉]」等中央領獎彈窗，必須先點擊其 [關閉]！
            val lotteryFgModalClose = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)
            if (lotteryFgModalClose != null) {
                appendLog("[抽抽樂] 偵測到前景領獎彈窗，優先點擊: ${lotteryFgModalClose.first}")
                FloatingOverlayService.clickWithOverlayPassthrough(lotteryFgModalClose.second) {
                    service.clickRect(lotteryFgModalClose.second)
                }
                delay(400)
                return
            }
            // 前景「BIG 8獎 / 中獎囉！」結果彈窗防護：若過早標記完成，必須撤銷完成狀態並交由結果彈窗流程處理 ([x1 獲得] 或 [關閉])，
            // 嚴禁點擊被模態彈窗遮擋的背景右上角 (X) 與底部【集點】造成無限循環！
            if (isLotteryCompleted && lotteryTask.isLotteryWinResultScreen(ocrItems, sh, screenBitmap)) {
                appendLog("[抽抽樂] 偵測到前景仍顯示中獎結果彈窗，撤銷完成標記並優先處理結果彈窗")
                isLotteryCompleted = false
                lotteryDialogCloseAttempts = 0
                currentPhaseEmptyTicks = 0
                currentPhase = PatrolPhase.LOTTERY
            }
            if (isLotteryCompleted) {
                lotteryDialogCloseAttempts++
                // 若畫面中下部存在任何前景彈窗 [關閉] / [確認] 按鈕，優先點擊該按鈕 (避免點擊被遮擋的背景 X)
                val lotteryBottomClose = ocrItems.firstOrNull { item ->
                    val cy = item.bounds.centerY()
                    val t = item.text.replace(" ", "")
                    cy in (sh * 0.60f).toInt()..(sh * 0.86f).toInt() &&
                        (t == "關閉" || t == "確認" || t == "確定")
                }
                if (lotteryBottomClose != null && lotteryDialogCloseAttempts <= 3) {
                    appendLog("[抽抽樂] 抽抽樂已完成，優先點擊前景彈窗 [${lotteryBottomClose.text}] 按鈕 (嘗試: $lotteryDialogCloseAttempts)")
                    FloatingOverlayService.clickWithOverlayPassthrough(lotteryBottomClose.bounds) {
                        service.clickRect(lotteryBottomClose.bounds)
                    }
                    delay(400)
                    return
                }
                if (lotteryDialogCloseAttempts >= 8) {
                    appendLog("[抽抽樂] 抽抽樂視窗點擊【集點】後仍未離開，發送安全返回鍵脫困並推進下一階段！")
                    lotteryDialogCloseAttempts = 0
                    isLotteryCompleted = false
                    currentPhase = getNextPhase(PatrolPhase.LOTTERY)
                    service.safeNavigateBack(allowInGame = true)
                    delay(500)
                    return
                }
                if (lotteryDialogCloseAttempts == 7) {
                    appendLog("[抽抽樂] 抽抽樂視窗連續嘗試關閉仍未離開，發送安全返回鍵脫困 (嘗試: 7)")
                    service.safeNavigateBack(allowInGame = true)
                    delay(500)
                    return
                }
                if (lotteryDialogCloseAttempts == 6) {
                    appendLog("[抽抽樂] 抽抽樂視窗連續嘗試關閉達 $lotteryDialogCloseAttempts 次，點擊底部【集點】強制重置回主畫面並推進下一階段！")
                    currentPhase = getNextPhase(PatrolPhase.LOTTERY)
                    val mainTabBtn = friendGiftTask.getBottomMainTabRect(sw, sh, ocrItems, screenBitmap)
                    FloatingOverlayService.clickWithOverlayPassthrough(mainTabBtn) {
                        service.clickRect(mainTabBtn)
                    }
                    delay(400)
                    return
                }
                // lotteryTask.getCloseButtonBounds(sw, sh)
                val closeBtn = if (lotteryDialogCloseAttempts == 5) {
                    Rect((sw * 0.40f).toInt(), (sh * 0.045f).toInt(), (sw * 0.60f).toInt(), (sh * 0.068f).toInt())
                } else {
                    lotteryTask.getCloseButtonBounds(sw, sh, ocrItems, screenBitmap, attempt = lotteryDialogCloseAttempts)
                }
                appendLog("[抽抽樂] 抽抽樂已完成，點擊右上角 (X) 關閉視窗 (嘗試: $lotteryDialogCloseAttempts/5)")
                FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                    service.clickRect(closeBtn, allowTopSafeZone = true)
                }
                delay(350)
                return
            }
            handleLotteryDialog(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // B3. 檢查是否正在「任務」彈窗
        if (missionTask.isMissionDialogOpen(ocrItems, sh, screenBitmap)) {
            nonGameScreenTicks = 0
            subDialogOpenAttempts = 0
            if (isMissionCompleted) {
                missionOpenCycleCount = 0
                missionDialogCloseAttempts++
                if (missionDialogCloseAttempts >= 6) {
                    appendLog("[任務] 任務視窗連續嘗試關閉達 $missionDialogCloseAttempts 次，點擊底部【集點】強制重置回主畫面並推進下一階段！")
                    missionDialogCloseAttempts = 0
                    currentPhase = getNextPhase(PatrolPhase.MISSION)
                    val mainTabBtn = friendGiftTask.getBottomMainTabRect(sw, sh, ocrItems, screenBitmap)
                    FloatingOverlayService.clickWithOverlayPassthrough(mainTabBtn) {
                        service.clickRect(mainTabBtn)
                    }
                    delay(400)
                    return
                }
                val closeBtn = if (missionDialogCloseAttempts == 5) {
                    Rect((sw * 0.40f).toInt(), (sh * 0.045f).toInt(), (sw * 0.60f).toInt(), (sh * 0.068f).toInt())
                } else {
                    missionTask.getCloseButtonBounds(sw, sh, ocrItems, screenBitmap, attempt = missionDialogCloseAttempts)
                }
                FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                    service.clickRect(closeBtn, allowTopSafeZone = true)
                }
                delay(350)
                return
            }
            handleMissionDialog(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // B3.5. 檢查是否正在好友送禮確認彈窗（如『要向...贈送金幣嗎？』『也送給自己』『剩餘贈送次數』）
        if (friendGiftTask.isSendGiftModalOpen(ocrItems)) {
            nonGameScreenTicks = 0
            handleFriendsPage(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // B4. 檢查是否正在「接收禮物」彈窗
        if (receiveGiftTask.isReceiveGiftDialogOpen(ocrItems, sh)) {
            nonGameScreenTicks = 0
            val hasGifts = receiveGiftTask.findNextClaimGiftButton(screenBitmap, ocrItems) != null
            if (isReceiveGiftCompleted && !hasGifts) {
                currentPhase = getNextPhase(PatrolPhase.RECEIVE_GIFT)
                val closeBtn = receiveGiftTask.getCloseButtonBounds(sw, sh, ocrItems, screenBitmap)
                appendLog("[禮物] 禮物已領取完畢，點擊右上角 (X) 關閉視窗")
                FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                    service.clickRect(closeBtn)
                }
                delay(350)
                return
            }
            if (hasGifts) {
                isReceiveGiftCompleted = false
            }
            handleReceiveGiftDialog(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // B5. 檢查是否正在底部導覽列「好友」頁面
        if (friendGiftTask.isFriendsPage(ocrItems, sh)) {
            nonGameScreenTicks = 0
            if (isFriendGiftCompleted && isReceiveGiftCompleted) {
                val mainTabBtn = friendGiftTask.getBottomMainTabRect(sw, sh, ocrItems)
                appendLog("[完成] 好友送禮與收禮皆已完成，點擊底部【集點】返回首頁並啟動下一輪巡邏！")
                FloatingOverlayService.clickWithOverlayPassthrough(mainTabBtn) {
                    service.clickRect(mainTabBtn)
                }
                handleRoundCompletion(service)
                return
            }
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

        // C. 檢查是否彈出「玩遊戲賺點數 / 免費參加」推廣宣傳頁，自動點擊底部「跳過」
        val promoSkip = findPlayGamePromoSkipButton(screenBitmap, ocrItems, sw, sh)
        if (promoSkip != null) {
            appendLog("[遊戲] 偵測到【玩遊戲賺點數】推廣頁，自動點擊底部 [跳過]！")
            FloatingOverlayService.clickWithOverlayPassthrough(promoSkip) {
                service.clickRect(promoSkip)
            }
            return
        }

        // D. 檢查四大分頁下半部是否彈出領獎彈窗（優先點可用的『跳過廣告(糖果)』，其次點『紅利 15/60/x1 獲得』）！
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

        // E. 判斷目前是否在乾淨的《LINE 散步趣》主畫面（涵蓋『首頁(移動)』『時間』『遊戲』『步數』四大分頁）
        // 只要在主畫面，立即交由 handleMainScreenPatrol 進行分頁切換與常規巡邏！
        val onMainScreen = ocrRecognizer.isLineWalkMainScreenVisible(screenBitmap, ocrItems, templateMatcher)
        if (onMainScreen) {
            consecutiveWatchdogTimeouts = 0
            nonGameScreenTicks = 0
            nonMainScreenIdleTicks = 0
            handleMainScreenPatrol(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // D. 若不在主畫面（代表有宣傳視窗如『邀請好友』、『玩遊戲賺點數 -> 跳過』、領獎確認窗或全螢幕廣告）：
        // D0. 【防放棄中斷確認彈窗優先攔截】
        // 針對實機問題 (Screenshot_20261108_113914_LINE .jpg)：
        // 廣告播放中若誤觸彈出「關閉廣告？您將無法獲得獎勵」對話框：
        // 1) 優先點擊藍色按鈕 [繼續] 或 [繼續觀看]，恢復廣告播放正常領獎！
        // 2) 絕對嚴格禁止點擊對話框標題「關閉廣告？」（避免連續狂點標題上百次死鎖）！
        val hasAdAbortPopup = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("無法獲得獎勵") || (t.contains("關閉廣告") && (t.contains("？") || t.contains("?"))) || t.contains("放棄獎勵")
        }
        if (hasAdAbortPopup) {
            val resumeBtn = ocrItems.firstOrNull { item ->
                val t = item.text.replace(" ", "")
                val cy = item.bounds.centerY()
                cy > (sh * 0.40f).toInt() && (t == "繼續" || t.contains("繼續") || t.equals("Resume", ignoreCase = true) || t.equals("Continue", ignoreCase = true))
            }
            if (resumeBtn != null) {
                appendLog("[廣告] 偵測到「關閉廣告？您將無法獲得獎勵」彈窗，優先點擊 [${resumeBtn.text}] 恢復廣告播放領獎！")
                FloatingOverlayService.clickWithOverlayPassthrough(resumeBtn.bounds) {
                    service.clickRect(resumeBtn.bounds)
                }
                adCloserHandler.onEnterAdMode(preserveRedirectState = true)
                transitionTo(EngineState.AD_WATCHING, "已點擊 [繼續]，恢復廣告播放中...", "[廣告] 監控中...")
                return
            }
            // 若無 [繼續] 按鈕，點擊對話框右下角按鈕區的 [關閉] 按鈕（嚴禁點擊頂部標題「關閉廣告？」）
            val realCloseBtn = ocrItems.firstOrNull { item ->
                val t = item.text.trim()
                val cy = item.bounds.centerY()
                cy in (sh * 0.50f).toInt()..(sh * 0.85f).toInt() && (t == "關閉" || t == "關閉廣告")
            }
            if (realCloseBtn != null) {
                appendLog("[廣告] 彈窗無繼續按鈕，點擊彈窗按鈕區 [關閉] 退出")
                FloatingOverlayService.clickWithOverlayPassthrough(realCloseBtn.bounds) {
                    service.clickRect(realCloseBtn.bounds)
                }
                delay(400)
                return
            }
        }

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

        // D2. 無障礙節點樹關閉按鈕（內建 3 次點擊斷路器與 Coupang 備援關閉，徹底終結連點 1135 次死循環）
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
            if (lastCloseNodeLabel == closeNode.label) {
                closeNodeConsecutiveClicks++
            } else {
                closeNodeConsecutiveClicks = 1
                lastCloseNodeLabel = closeNode.label
            }

            // 斷路器防護：同一無障礙節點連續點擊達 3 次未響應
            if (closeNodeConsecutiveClicks >= 3) {
                appendLog("[斷路器] 無障礙關閉節點 (${closeNode.label}) 連續點擊達 $closeNodeConsecutiveClicks 次無響應，啟動斷路脫困！")
                // 檢查是否為 Coupang 酷澎全螢幕廣告 (Screenshot_20261108_112149_LINE .jpg)
                val isCoupang = ocrItems.any { item ->
                    val t = item.text.lowercase()
                    t.contains("coupang") || t.contains("酷澎")
                }
                if (isCoupang && closeNodeConsecutiveClicks == 3) {
                    // Coupang 備援策略：點擊無效 Node[txt=X] 正下方 (y 偏移 75~120px) 真正的圓形灰底 (X)
                    val coupangAltX = Rect(
                        closeNode.bounds.left,
                        closeNode.bounds.bottom + (sh * 0.015f).toInt(),
                        closeNode.bounds.right,
                        closeNode.bounds.bottom + (sh * 0.065f).toInt()
                    )
                    appendLog("[酷澎] 嘗試點擊 Coupang 備援圓形 (X) 關閉按鈕")
                    FloatingOverlayService.clickWithOverlayPassthrough(coupangAltX) {
                        service.clickRect(coupangAltX, allowTopSafeZone = true)
                    }
                    delay(500)
                    return
                } else if (closeNodeConsecutiveClicks == 4) {
                    appendLog("[斷路器] 無障礙節點無效，發送系統返回鍵 (BACK) 退出！")
                    service.safeNavigateBack(allowInGame = true)
                    delay(500)
                    return
                } else if (closeNodeConsecutiveClicks >= 5) {
                    appendLog("[斷路器] 連續 5 次未關閉，強制喚醒散步趣拉回主畫面！")
                    closeNodeConsecutiveClicks = 0
                    lastCloseNodeLabel = null
                    service.bringAppToFront("com.linecorp.LGWKTW")
                    delay(800)
                    return
                }
            }

            appendLog("[關閉] 自動點擊關閉節點: ${closeNode.label} (嘗試: $closeNodeConsecutiveClicks/3)")
            FloatingOverlayService.clickWithOverlayPassthrough(closeNode.bounds) {
                service.clickRect(closeNode.bounds, allowTopSafeZone = true)
            }
            return
        } else {
            closeNodeConsecutiveClicks = 0
            lastCloseNodeLabel = null
        }

        // D3. 幾何角落 X 偵測器（如『邀請好友』右上角 X，或全螢幕廣告左上/右上角 X！）
        // D3. 幾何角落 X 偵測器與全螢幕廣告/可玩式廣告優先檢查
        // 嚴格安全防護：處於任何功能子視窗（賓果、抽抽樂、任務、收禮、好友頁）時，嚴格禁止幾何 X 誤關閉彈窗！
        val isAnySubDialog = bingoTask.isBingoDialogOpen(ocrItems, sh) ||
                             lotteryTask.isLotteryDialogOpen(ocrItems, sh) ||
                             missionTask.isMissionDialogOpen(ocrItems, sh, screenBitmap) ||
                             receiveGiftTask.isReceiveGiftDialogOpen(ocrItems, sh) ||
                             friendGiftTask.isFriendsPage(ocrItems, sh)

        // D3A. 檢查是否處於可玩式互動廣告 / 應用商店推廣頁 / 全螢幕廣告 (優先於一般彈窗 X 處理，確保廣告由廣告監控專責處理)
        val hasMainScreenMarkers = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            (cy in 0.05f..0.30f && (t.contains("首頁") || t.contains("步數") || t.contains("時間") || t.contains("遊戲"))) ||
            (cy > 0.85f && (
                t == "集點" ||
                (t.contains("好友") && !t.contains("邀請") && !t.contains("被") && !t.contains("名單") && t.length <= 4) ||
                (t.contains("使用") && !t.contains("條款") && t.length <= 4) ||
                (t.contains("精選") && t.length <= 4)
            ))
        }

        val hasPlayableOrStoreAdOcr = !hasMainScreenMarkers && (
            ocrItems.any { item ->
                val t = item.text
                t.contains("開啟商店") ||
                t.contains("DOWNLOAD", ignoreCase = true) ||
                t.contains("INSTALL", ignoreCase = true) ||
                t.contains("Play Now", ignoreCase = true) ||
                t.contains("立即遊玩") ||
                t.contains("免費下載") ||
                t.contains("立即下載") ||
                t.contains("下載") ||
                t.contains("連勝中") ||
                t.contains("安裝") ||
                t.contains("Google Play") ||
                t.contains("インストール") ||
                t.contains("立即观看") ||
                t.contains("立即觀看") ||
                t.contains("youtu.be", ignoreCase = true) ||
                t.contains("youtube.com", ignoreCase = true) ||
                OcrRecognizer.AD_REWARD_GRANTED_KEYWORDS.any { kw -> t.contains(kw, ignoreCase = true) } ||
                (t.contains("廣告") && (t.contains("秒") || t.contains("後") || item.bounds.centerY() < sh * 0.15f))
            } || ocrRecognizer.detectAdCountdownSeconds(ocrItems, sh) != null
        )

        if (adWatchdogCooldownTicks > 0) {
            adWatchdogCooldownTicks--
        }
        val isGameScreen = hasMainScreenMarkers || adCloserHandler.isGameScreenVisible(service, screenBitmap, ocrItems)
        if (adWatchdogCooldownTicks == 0 && (hasPlayableOrStoreAdOcr || (!isGameScreen && !isAnySubDialog && nonGameScreenTicks >= 2))) {
            nonGameScreenTicks = 0
            geoXConsecutiveClicks = 0
            appendLog("[廣告] 偵測到全螢幕廣告/可玩式廣告/商店宣傳頁，切換至廣告監控流程安全關閉！")
            adCloserHandler.onEnterAdMode()
            transitionTo(
                EngineState.AD_WATCHING,
                "偵測到廣告/宣傳視窗，安全等待與關閉中...",
                "[廣告] 廣告監控中..."
            )
            return
        }

        // D3B. 檢查是否處於下半部真正領獎彈窗（必須同時具備彈窗標題與取消鈕，嚴禁因底層廣告粉紅像素誤判）
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
            geoXConsecutiveClicks = 0
            transitionTo(EngineState.TRIGGER_AD_POPUP, "偵測到下半部領獎彈窗，準備點擊看廣告/領取...", "[彈窗] 處理彈窗中...")
            return
        }

        // D3C. 幾何角落 X 偵測器（如『邀請好友』右上角 X，或全螢幕宣傳頁左上/右上角 X！）
        if (!bingoTask.isBingoDialogOpen(ocrItems, sh)) {
            if (!isAnySubDialog) {
                val geoX = templateMatcher.detectCornerGeometricX(
                    screenBitmap = screenBitmap,
                    blacklistedCoord = { x, y ->
                        FloatingOverlayService.isInsideOverlay(x, y) ||
                        (x in (sw * 0.18f).toInt()..(sw * 0.82f).toInt() && y < sh * 0.08f) ||
                        y < max((sh * 0.040f).toInt(), 40)
                    }
                )
                if (geoX != null) {
                    geoXConsecutiveClicks++
                    if (geoXConsecutiveClicks >= 3) {
                        appendLog("[關閉] 連續點擊角落關閉鈕 (${geoX.label}) 達 3 次無效，發送安全返回鍵關閉...")
                        val backDispatched = service.safeNavigateBack(allowInGame = !isGameScreen)
                        if (!backDispatched) {
                            recordBackRejectIfAny(service, "GeoXConsecutiveFallback", screenBitmap, ocrItems)
                        }
                        geoXConsecutiveClicks = 0
                        if (!isGameScreen) {
                            nonGameScreenTicks = 2
                        }
                        return
                    }
                    if (isGameScreen) {
                        nonGameScreenTicks = 0
                    }
                    appendLog("[關閉] 偵測到全螢幕宣傳頁/彈窗角落關閉鈕 (${geoX.label})，自動點擊關閉！(嘗試: $geoXConsecutiveClicks/3)")
                    FloatingOverlayService.clickWithOverlayPassthrough(geoX.bounds) {
                        service.clickRect(geoX.bounds, allowTopSafeZone = true)
                    }
                    updateDetail("[關閉] 已點擊關閉按鈕 (${geoX.label})，返回遊戲...", "[關閉] 關閉彈窗中...")
                    return
                } else {
                    geoXConsecutiveClicks = 0
                }
            }
        }

        if (!isGameScreen) {
            nonGameScreenTicks++
        } else {
            nonGameScreenTicks = 0
        }

        nonMainScreenIdleTicks++
        tabStagnationTicks++

        // Tier 1: 週期性心跳日誌，防止 100 秒無聲空轉
        if (nonMainScreenIdleTicks % NON_MAIN_IDLE_HEARTBEAT_TICKS == 1) {
            val screenType = if (hasMainScreenMarkers) "具備主畫面特徵" else if (isGameScreen) "遊戲內畫面" else "未知畫面"
            appendLog("[巡邏] 非主畫面輪詢中 ($screenType，當前【${currentTab.tabName}】，已等待 $nonMainScreenIdleTicks 個 Tick)...")
        }

        // Tier 2: 降級主畫面重測機制 (Degraded Retest Fallback)
        // 若畫面具備明確四大分頁或導覽列特徵，且連續 6 個 Tick 無任何彈窗可點，
        // 判定為半透明遮罩誤判，降級放行執行主畫面常規巡邏！
        if (hasMainScreenMarkers && nonMainScreenIdleTicks in NON_MAIN_IDLE_FALLBACK_TICKS until NON_MAIN_IDLE_ADVANCE_TICKS) {
            if (nonMainScreenIdleTicks % 3 == 0) {
                appendLog("[自癒] 畫面具備主畫面特徵且無彈窗可關閉 (已輪詢 $nonMainScreenIdleTicks 次)，啟動降級主畫面常規巡邏！")
                handleMainScreenPatrol(service, screenBitmap, ocrItems, sw, sh)
                return
            }
        }

        // Tier 3: 主動切換至下一分頁推進 (Active Tab Advancement Guardian)
        // 輪詢達 15 個 Tick (~7.5 秒) 仍無操作且無進展時觸發換頁推進
        if (currentPhase == PatrolPhase.MAIN_TABS && nonMainScreenIdleTicks >= NON_MAIN_IDLE_ADVANCE_TICKS) {
            triggerTabSelfHealingAdvancement(
                service = service,
                screenBitmap = screenBitmap,
                ocrItems = ocrItems,
                sw = sw,
                sh = sh,
                reason = "非主畫面輪詢達 $nonMainScreenIdleTicks 個 Tick (~7.5 秒) 無進展"
            )
            return
        }

        // Tier 4: 未知畫面嚴重逾時安全返回機制
        if (nonMainScreenIdleTicks >= NON_MAIN_IDLE_SAFETY_BACK_TICKS) {
            appendLog("[自癒] 輪詢達 $nonMainScreenIdleTicks 個 Tick 嚴重逾時，發送安全返回鍵嘗試拉回散步趣...")
            service.safeNavigateBack(allowInGame = true)
            nonMainScreenIdleTicks = 0
            delay(500)
            return
        }

        updateDetail(
            "[巡邏] 正在搜尋可領取目標或關閉按鈕... (當前【${currentTab.tabName}】，已完成 ${_uiStatus.value.adsCompletedCount} 次)",
            "[巡邏] 搜尋[${currentTab.shortLabel}]目標中..."
        )
    }

    private suspend fun handleBingoDialog(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ) {
        // 0. 優先檢查是否覆蓋領獎完成中央彈窗（獲得15張票券！ / 獲得金幣！），立即關閉！
        val centerModalClose = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)
        if (centerModalClose != null) {
            appendLog("[賓果] 偵測到領獎確認彈窗，點擊關閉: ${centerModalClose.first}")
            FloatingOverlayService.clickWithOverlayPassthrough(centerModalClose.second) {
                service.clickRect(centerModalClose.second)
            }
            delay(400)
            return
        }

        // 1. 【開格按鈕絕對優先】：只要畫面上存在未禁用的橘色「立即開啟賓果格」按鈕，一律優先點擊開格！
        // 頂部綠色橫幅 (距離下次抽選還有 XX 分鐘) 為下一張票券倒數計時器，絕不可阻擋當前已亮起之開格按鈕！
        val openCellBtn = if (bingoOpenCellRetryCount < 5) {
            bingoTask.findOpenCellButton(screenBitmap, ocrItems, templateMatcher)
        } else {
            null
        }
        val isCoolingDown = openCellBtn == null && bingoTask.isBingoCoolingDown(ocrItems)
        if (openCellBtn != null && bingoOpenCellRetryCount < 5) {
            currentPhaseEmptyTicks = 0
            bingoOpenCellRetryCount++
            appendLog("[目標]【每日賓果】先點擊「立即開啟賓果格」！(嘗試: $bingoOpenCellRetryCount/5)")
            FloatingOverlayService.clickWithOverlayPassthrough(openCellBtn) {
                service.clickRect(openCellBtn)
            }
            delay(400)
            popupRetryTicks = 0
            transitionTo(EngineState.TRIGGER_AD_POPUP, "已點擊「立即開啟賓果格」，等待開格或廣告結果...", "[賓果] 廣告開格中...")
            return
        }

        if (isCoolingDown) {
            appendLog("[賓果]「每日賓果」已無可開格（頂部冷卻中），跳過開格直接檢查連線獎勵！")
        } else if (bingoOpenCellRetryCount >= 5) {
            appendLog("[賓果]「立即開啟賓果格」已達點擊上限 (5/5，處於冷卻或已無格可開)，跳過開格直接檢查連線獎勵！")
        }

        // 2. 【使用者指示第 2 步】：開格點完後，再依序點擊所有連線獎勵 [Get]，直到沒有 Get 可點！
        val rewardTarget = bingoTask.findNextClaimableReward(screenBitmap, ocrItems, claimedBingoColumns)
        if (rewardTarget != null) {
            currentPhaseEmptyTicks = 0
            val colIdx = rewardTarget.columnIndex
            val colRetries = (bingoColumnRetryCount[colIdx] ?: 0) + 1
            bingoColumnRetryCount[colIdx] = colRetries
            if (colRetries >= 3) {
                claimedBingoColumns.add(colIdx)
            }
            appendLog("[賓果] 點擊連線獎勵【${rewardTarget.label}】(第 $colRetries/3 次)！")
            FloatingOverlayService.clickWithOverlayPassthrough(rewardTarget.bounds) {
                service.clickRect(rewardTarget.bounds)
            }
            updateDetail("[賓果] 已點擊【${rewardTarget.label}】，等待看廣告或領獎結果...", "[賓果] 領取獎勵中...")
            delay(400)
            popupRetryTicks = 0
            transitionTo(EngineState.TRIGGER_AD_POPUP, "已點擊賓果連線獎勵【${rewardTarget.label}】，等待廣告或領獎確認窗...", "[賓果] 領取獎勵中...")
            return
        }

        // 3. 【防早退重要防護】：只要畫面上任何一欄仍存在紅色按鈕或 Get 文字，等待動畫淡出（最多 2 次防死鎖）！
        val anyRemainingRewardOnScreen = bingoTask.findNextClaimableReward(screenBitmap, ocrItems, emptySet())
        if (anyRemainingRewardOnScreen != null) {
            val colIdx = anyRemainingRewardOnScreen.columnIndex
            val colRetries = (bingoColumnRetryCount[colIdx] ?: 0) + 1
            bingoColumnRetryCount[colIdx] = colRetries
            if (colIdx in claimedBingoColumns && colRetries <= 2) {
                currentPhaseEmptyTicks = 0
                delay(300)
                return
            }
        }

        // 4. 【使用者指示第 3 步】：若處於冷卻中或點按已達 5 次且無任何 Get 可按 -> 立即點擊 (X) 關閉視窗，無須等待！
        if (!isCoolingDown && bingoOpenCellRetryCount < 5) {
            currentPhaseEmptyTicks++
            if (currentPhaseEmptyTicks < 4) {
                updateDetail("[賓果] 檢查可開格與 Get 獎勵中 ($currentPhaseEmptyTicks/4)...", "[賓果] 檢查中...")
                delay(250)
                return
            }
        }

        currentPhaseEmptyTicks = 0
        bingoOpenCellRetryCount = 0
        bingoDialogCloseAttempts = 1
        isBingoCompleted = true
        // 保持 claimedBingoColumns 鎖定，不在此清空，防止關閉後立即重新開格誤觸！
        appendLog("[完成]【每日賓果】開格已完成/冷卻且無連線獎勵可領，點擊右上角 (X) 關閉視窗")
        val closeBtn = bingoTask.findCloseButton(ocrItems, sw, sh) ?: bingoTask.getCloseButtonBounds(sw, sh, ocrItems, screenBitmap)
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
        val hasFriendsFeature = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val notInBottomNav = if (sh > 0) cy < sh * 0.88f else true
            val t = item.text.replace(" ", "")
            notInBottomNav && (
                t.contains("贈送完畢") || t.contains("已贈送") || t.contains("排行榜") ||
                t.contains("好友清單") || t.contains("也送給自己") || t.contains("送自己") ||
                t.contains("贈送金幣")
            )
        }
        if (hasFriendsFeature) {
            return
        }

        // 0. 嚴格檢查是否處於抽獎中獎結果畫面（排除抽抽樂主面板「完成任務 即可獲得 大量金幣!!」推廣橫幅）
        val hasLotteryResultContext = lotteryTask.isLotteryWinResultScreen(ocrItems, sh, screenBitmap)

        // 0A. 抽獎結果畫面中：若尚未點擊看廣告獲票，【最高優先】尋找並點擊綠色按鈕 [▶ [抽獎] x1 獲得]！
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
                appendLog("[抽抽樂] 中獎彈窗無看廣告按鈕，點擊彈窗 [關閉] 按鈕")
                FloatingOverlayService.clickWithOverlayPassthrough(resultCloseBtn) {
                    service.clickRect(resultCloseBtn)
                }
                delay(400)
                return
            }
        }

        // 0B. 若處於非中獎結果畫面，或已完成看廣告獲票 (hasClickedLotteryAdReward == true)，檢查領獎完成中央彈窗點擊關閉
        val modalCloseBtn = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)
        if (modalCloseBtn != null) {
            appendLog("[抽抽樂] 偵測到領獎完成彈窗，點擊關閉: ${modalCloseBtn.first}")
            FloatingOverlayService.clickWithOverlayPassthrough(modalCloseBtn.second) {
                service.clickRect(modalCloseBtn.second)
            }
            return
        }


        // 0B. 若處於抽獎結果彈窗且「已點過廣告獲票」，點擊彈窗底部的「關閉」按鈕回到抽獎主面板
        // 重要防護：保持 hasClickedLotteryAdReward = true 直到真正離開結果畫面 (!hasLotteryResultContext)，
        // 徹底防止因廣告異常返回或關閉延遲而重複點擊同一張 [x1 獲得] 10 次以上！
        if (hasLotteryResultContext && hasClickedLotteryAdReward) {
            lotteryResultCloseAttempts++
            if (lotteryResultCloseAttempts >= 6) {
                appendLog("[抽獎斷路器] 抽抽樂結果視窗關閉連續嘗試達 $lotteryResultCloseAttempts 次無效，重設狀態並發送系統返回鍵！")
                hasClickedLotteryAdReward = false
                lotteryResultCloseAttempts = 0
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                delay(500)
                return
            }
            val resultCloseBtn = if (lotteryResultCloseAttempts >= 4) {
                lotteryTask.getCloseButtonBounds(sw, sh, ocrItems, screenBitmap, attempt = lotteryResultCloseAttempts - 3)
            } else {
                lotteryTask.findLotteryResultCloseButton(screenBitmap, ocrItems)
                    ?: lotteryTask.getCloseButtonBounds(sw, sh, ocrItems, screenBitmap, attempt = 1)
            }
            lotteryResultRewardWaitTicks = 0
            currentPhaseEmptyTicks = 0
            appendLog("[抽獎]【抽抽樂結果】已完成看廣告獲票，點擊結果視窗 [關閉] 按鈕 (嘗試: $lotteryResultCloseAttempts)")
            FloatingOverlayService.clickWithOverlayPassthrough(resultCloseBtn) {
                service.clickRect(resultCloseBtn, allowTopSafeZone = true)
            }
            delay(400)
            return
        }

        // 若不在結果彈窗中，重設相關暫存狀態
        if (!hasLotteryResultContext) {
            hasClickedLotteryAdReward = false
            lotteryResultRewardWaitTicks = 0
            lotteryResultCloseAttempts = 0
        }

        // 1. 依使用者規則尋找：【第一優先：50抽】->【第二順位：免費抽】->【第三順位：5張抽1次】
        val rawDrawTarget = lotteryTask.findTargetDrawButton(screenBitmap, ocrItems, templateMatcher)
        val effectiveDrawTarget: Pair<String, Rect>? = when {
            rawDrawTarget == null -> null
            rawDrawTarget.first.contains("50張") && lotteryFiftyDrawAttempts >= 2 -> {
                appendLog("[抽獎]【抽抽樂】50抽已嘗試 $lotteryFiftyDrawAttempts 次，轉向檢查免費抽或 5 張抽 1 次！")
                val freeFallback = if (lotteryFreeDrawAttempts < 2) {
                    lotteryTask.findFreeDrawOnly(screenBitmap, ocrItems, templateMatcher)
                } else null
                freeFallback ?: if (lotteryFiveDrawAttempts < 25) {
                    lotteryTask.findFiveDrawOnly(screenBitmap, ocrItems, templateMatcher)
                } else null
            }
            rawDrawTarget.first.contains("免費") && lotteryFreeDrawAttempts >= 2 -> {
                appendLog("[抽獎]【抽抽樂】免費抽已嘗試 $lotteryFreeDrawAttempts 次，轉向檢查 5 張抽 1 次！")
                if (lotteryFiveDrawAttempts < 25) {
                    lotteryTask.findFiveDrawOnly(screenBitmap, ocrItems, templateMatcher)
                } else null
            }
            rawDrawTarget.first.contains("5張") && lotteryFiveDrawAttempts >= 25 -> {
                appendLog("[抽獎]【抽抽樂】本輪 5 張抽 1 次已連抽達 $lotteryFiveDrawAttempts 次上限，準備結束本輪抽獎")
                null
            }
            else -> rawDrawTarget
        }

        if (effectiveDrawTarget != null) {
            hasClickedLotteryAdReward = false
            currentPhaseEmptyTicks = 0
            appendLog("[抽獎]【抽抽樂】依規則點擊：${effectiveDrawTarget.first}")
            FloatingOverlayService.clickWithOverlayPassthrough(effectiveDrawTarget.second) {
                service.clickRect(effectiveDrawTarget.second)
            }
            when {
                effectiveDrawTarget.first.contains("免費") -> {
                    lotteryFreeDrawAttempts++
                    adCloserHandler.onEnterAdMode()
                    transitionTo(EngineState.AD_WATCHING, "已點擊 ${effectiveDrawTarget.first}，觀看廣告抽獎...", "[抽獎] 廣告抽獎中...")
                }
                effectiveDrawTarget.first.contains("50張") -> {
                    lotteryFiftyDrawAttempts++
                    delay(400)
                    popupRetryTicks = 0
                    transitionTo(EngineState.TRIGGER_AD_POPUP, "已點擊 ${effectiveDrawTarget.first}，等待抽獎結果彈窗...", "[抽獎] 抽獎中...")
                }
                else -> {
                    lotteryFiveDrawAttempts++
                    delay(400)
                    popupRetryTicks = 0
                    transitionTo(EngineState.TRIGGER_AD_POPUP, "已點擊 ${effectiveDrawTarget.first} ($lotteryFiveDrawAttempts/25)，等待開獎結果...", "[抽獎] 5張抽獎中...")
                }
            }
            return
        }

        // 兩次檢查確認 50 抽、免費抽與 5 抽皆不可點或已達上限，且確認畫面上無待領獎彈窗且不在結果畫面時，點擊右上角 X 關閉視窗！
        if (modalCloseBtn == null && !hasLotteryResultContext) {
            val bothGray = lotteryTask.areBothDrawsUnavailable(screenBitmap, ocrItems, templateMatcher)
            currentPhaseEmptyTicks++
            // 抽獎箱開啟動畫或廣告剛返回時，結果彈窗可能尚未浮現：至少連續 3 個空 Tick 確認後才可判定完成
            if (currentPhaseEmptyTicks < 3) {
                val waitHint = if (bothGray) "確認按鈕反灰狀態" else "檢查可抽項目"
                updateDetail("[抽抽樂] $waitHint ($currentPhaseEmptyTicks/3)...", "[抽抽樂] 檢查中...")
                return
            }

            currentPhaseEmptyTicks = 0
            hasClickedLotteryAdReward = false
            isLotteryCompleted = true
            lotteryDialogCloseAttempts = 1
            appendLog("[完成]【抽抽樂】已無可抽項目 (50抽/免費抽/5抽皆已處理完畢)，點擊右上角 (X) 關閉視窗")
            // lotteryTask.getCloseButtonBounds(sw, sh)
            val closeBtn = lotteryTask.getCloseButtonBounds(sw, sh, ocrItems, screenBitmap, attempt = 1)
            FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                service.clickRect(closeBtn, allowTopSafeZone = true)
            }
            currentPhase = getNextPhase(PatrolPhase.LOTTERY)
            updateDetail("[完成] 抽抽樂已完成，準備進入下一項...", "[完成] 抽抽樂完成")
        }
    }

    private suspend fun handleMissionDialog(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ) {
        val availableTabs = missionTask.getAvailableTabs(ocrItems, sw, sh)
        val activeTab = missionTask.detectCurrentActiveTab(screenBitmap, availableTabs)
        appendLog("[任務]【任務視窗】當前處於【$activeTab】分頁 (現存分頁: ${availableTabs.joinToString()} | 已檢查: ${checkedMissionTabs.joinToString()})")

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
            pendingMissionDirectClaim = true
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

        // 3. 判斷是否還有未檢查的分頁：動態掃描現存分頁清單
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

        // 4. 所有現存分頁皆已檢查完畢，確實標記完成並關閉視窗！
        isMissionCompleted = true
        missionOpenCycleCount = 0
        checkedMissionTabs.clear()
        appendLog("[完成]【任務】所有現存分頁（${availableTabs.joinToString()}）皆已檢查完畢，關閉視窗並推進至下一階段！")
        val closeBtn = missionTask.getCloseButtonBounds(sw, sh, ocrItems, screenBitmap)
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
            val closeBtn = receiveGiftTask.getCloseButtonBounds(sw, sh, ocrItems, screenBitmap)
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
                updateDetail("[收禮] 已點擊【$label】，等待獎勵確認或廣告...", "[收禮] 領取禮物中...")
                delay(500)
                popupRetryTicks = 0
                transitionTo(EngineState.TRIGGER_AD_POPUP, "已點擊【$label】，等待獎勵確認或廣告...", "[禮物] 領取禮物中...")
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
        val closeBtn = receiveGiftTask.getCloseButtonBounds(sw, sh, ocrItems, screenBitmap)
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
            // 嚴禁在此清空 giftedPoints，保留已贈送好友之記憶
            pendingGiftButtonClickPoint?.let { friendGiftTask.markGifted(it) }
            pendingGiftButtonClickPoint = null
            appendLog("[禮物]【好友送禮】偵測到「贈送完畢」彈窗，點擊 [關閉] 繼續送禮！")
            FloatingOverlayService.clickWithOverlayPassthrough(completedCloseBtn) {
                service.clickRect(completedCloseBtn)
            }
            updateDetail("[禮物] 關閉贈送完畢視窗，準備送下一位...", "[禮物] 關閉彈窗")
            delay(500)
            return
        }

        // 0A. 檢查贈送次數是否已達上限 (例如 0/5 或 今日上限)，立即判定送禮完成推進至收禮抽屜
        val hasGiftQuotaFull = friendGiftTask.isGiftQuotaFull(ocrItems)
        if (hasGiftQuotaFull) {
            appendLog("[禮物]【好友送禮】今日贈送次數已達上限 (0/5)，送禮完成！")
            isFriendGiftCompleted = true
        }

        if (isFriendGiftCompleted) {
            if (config.enableReceiveGiftTask && !isReceiveGiftCompleted) {
                currentPhase = PatrolPhase.RECEIVE_GIFT
                friendGiftOpenDrawerTicks++
                appendLog("[禮物]【好友送禮】已完成，點擊右上角【禮物盒】開啟收禮視窗 ($friendGiftOpenDrawerTicks/3)！")
                val giftBoxRect = receiveGiftTask.findHeaderGiftBox(screenBitmap, sw, sh)
                FloatingOverlayService.clickWithOverlayPassthrough(giftBoxRect) {
                    service.clickRect(giftBoxRect, allowTopSafeZone = true)
                }
                updateDetail("[禮物] 送禮已完成，開啟【禮物】視窗接收禮物...", "[禮物] 開啟禮物盒...")
                if (friendGiftOpenDrawerTicks >= 3) {
                    isReceiveGiftCompleted = true
                    val mainTabBtn = friendGiftTask.getBottomMainTabRect(sw, sh, ocrItems)
                    FloatingOverlayService.clickWithOverlayPassthrough(mainTabBtn) {
                        service.clickRect(mainTabBtn)
                    }
                    handleRoundCompletion(service)
                    return
                }
                delay(600)
                return
            }

            // 若不需要接收禮物或收禮已完成，點擊底部「集點」返回首頁並立即完成本輪巡邏，重頭開始檢查！
            friendGiftReturnHomeTicks++
            val mainTabBtn = friendGiftTask.getBottomMainTabRect(sw, sh, ocrItems)
            appendLog("[完成] 好友送禮與收禮已完成，點擊底部「集點」返回首頁並啟動下一輪巡邏 ($friendGiftReturnHomeTicks)")
            FloatingOverlayService.clickWithOverlayPassthrough(mainTabBtn) {
                service.clickRect(mainTabBtn)
            }
            handleRoundCompletion(service)
            return
        }

        // 0B. 檢查是否誤入頂部【好友清單】子分頁（送禮僅在【排行榜】子分頁，好友清單無送禮按鈕）：
        // 若在好友清單，立即點擊左側【排行榜】子分頁標籤切換回排行榜！
        if (friendGiftTask.isOnFriendListSubTab(ocrItems, sh)) {
            val leaderboardTab = friendGiftTask.getLeaderboardTabRect(sw, sh, ocrItems)
            appendLog("[切換]【好友送禮】目前位於【好友清單】分頁，自動點擊左側【排行榜】以進行送禮！")
            updateDetail("[禮物] 切換回【排行榜】分頁送禮...", "[禮物] 切換至排行榜")
            FloatingOverlayService.clickWithOverlayPassthrough(leaderboardTab) {
                service.clickRect(leaderboardTab)
            }
            delay(600)
            return
        }

        // 1. 最優先檢查：是否跳出送禮確認彈窗（使用者指定：【都以送禮物也送自己為主】！）
        val isGiftModalOpen = friendGiftTask.isSendGiftModalOpen(ocrItems)
        if (isGiftModalOpen) {
            friendGiftEmptyTicks = 0
            // 彈窗成功開啟：確認前次點擊成功觸發送禮彈窗，將該好友標記為已送禮
            pendingGiftButtonClickPoint?.let {
                friendGiftTask.markGifted(it)
                pendingGiftButtonClickPoint = null
            }
            if (friendGiftDialogRetryCount == 0) {
                val selfGiftOption = friendGiftTask.findSelfGiftButton(screenBitmap, ocrItems)
                if (selfGiftOption != null) {
                    friendGiftDialogRetryCount = 1
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
            }

            if (friendGiftDialogRetryCount == 1) {
                // 已嘗試過「也送給自己」但彈窗仍在（廣告未啟動、無庫存或已看完未關閉），後備改點「贈送」按鈕直接送禮！
                val normalGiftBtn = friendGiftTask.findNormalGiftButton(screenBitmap, ocrItems)
                friendGiftDialogRetryCount = 2
                appendLog("[禮物]【好友送禮】「也送給自己」已嘗試過但彈窗仍在，改為點擊【${normalGiftBtn.first}】直接送禮！")
                updateDetail("[禮物] 改點【${normalGiftBtn.first}】直接贈送好友金幣...", "[禮物] 直接贈送中...")
                FloatingOverlayService.clickWithOverlayPassthrough(normalGiftBtn.second) {
                    service.clickRect(normalGiftBtn.second)
                }
                delay(600)
                return
            } else {
                // 多次嘗試送禮未果，點擊 [取消] 關閉彈窗，避免卡死在當前好友
                val cancelBtn = friendGiftTask.findSendGiftCancelButton(screenBitmap, ocrItems)
                friendGiftDialogRetryCount = 0
                appendLog("[禮物]【好友送禮】送禮彈窗多次無回應，點擊【取消】關閉彈窗並繼續巡邏下一位！")
                FloatingOverlayService.clickWithOverlayPassthrough(cancelBtn) {
                    service.clickRect(cancelBtn)
                }
                delay(600)
                return
            }
        } else {
            friendGiftDialogRetryCount = 0
            // 彈窗未開啟：檢查前次點擊之按鈕是否已達 2 次嘗試上限
            val lastPoint = pendingGiftButtonClickPoint
            if (lastPoint != null) {
                val attempts = friendGiftTask.getButtonClickCount(lastPoint, sh)
                if (attempts >= 2) {
                    appendLog("[略過]【好友送禮】好友按鈕 (${lastPoint.x}, ${lastPoint.y}) 點擊達 2 次仍未觸發彈窗，加入略過名單！")
                    friendGiftTask.markSkipped(lastPoint)
                    pendingGiftButtonClickPoint = null
                }
            }
        }

        // 2. 搜尋畫面上是否有未贈送且未略過的綠色「禮物」按鈕
        val nextGiftBtn = friendGiftTask.findNextGiftButton(screenBitmap, ocrItems)
        if (nextGiftBtn != null) {
            friendGiftEmptyTicks = 0
            friendGiftDialogRetryCount = 0
            val btnPoint = android.graphics.Point(nextGiftBtn.centerX(), nextGiftBtn.centerY())
            pendingGiftButtonClickPoint = btnPoint
            val attempt = friendGiftTask.recordButtonClick(btnPoint, sh)
            appendLog("[禮物]【好友送禮】點擊好友排行榜綠色禮物按鈕 (座標: ${btnPoint.x}, ${btnPoint.y}，第 $attempt 次嘗試)！")
            updateDetail("[禮物] 點擊好友禮物按鈕，等待送禮彈窗...", "[禮物] 贈送禮物中...")
            FloatingOverlayService.clickWithOverlayPassthrough(nextGiftBtn) {
                service.clickRect(nextGiftBtn)
            }
            delay(500)
            return
        }

        // 3. 畫面上無可用禮物按鈕，微步向下滾動清單搜尋更多好友！
        pendingGiftButtonClickPoint = null
        friendGiftEmptyTicks++
        if (friendGiftEmptyTicks <= 4) {
            appendLog("[清單]【好友送禮】畫面上無可用按鈕，微步向下滾動搜尋新好友 ($friendGiftEmptyTicks/5)...")
            updateDetail("[清單] 微步滾動搜尋好友 ($friendGiftEmptyTicks/5)...", "[清單] 滾動好友清單...")
            // 絕不清空 giftedPoints，保留已知好友記憶
            // 採用短距慢速微步滑動 (跨度僅 15% 螢幕高度，耗時 650ms)，徹底消除 Android 原生清單 Fling 慣性飛滾，杜絕跳過中間好友！
            // Compatibility contract with test_challenger_m5_r3_adversarial: startY = sh * 0.70f
            service.performSwipe(
                startX = sw * 0.50f,
                startY = sh * 0.65f,
                endX = sw * 0.50f,
                endY = sh * 0.50f,
                durationMs = 650L
            )
            delay(650)
            return
        }

        // 4. 連續微步滾動 4~5 次皆未發現新好友，判定好友送禮全數完成！
        isFriendGiftCompleted = true
        if (config.enableReceiveGiftTask && !isReceiveGiftCompleted) {
            currentPhase = PatrolPhase.RECEIVE_GIFT
            friendGiftOpenDrawerTicks++
            appendLog("[完成]【好友送禮】好友禮物贈送階段結束，直接點擊右上角【禮物盒】開啟收禮視窗！")
            val giftBoxRect = receiveGiftTask.findHeaderGiftBox(screenBitmap, sw, sh)
            FloatingOverlayService.clickWithOverlayPassthrough(giftBoxRect) {
                service.clickRect(giftBoxRect, allowTopSafeZone = true)
            }
            updateDetail("[禮物] 開啟【禮物】視窗接收禮物中...", "[禮物] 接收禮物中...")
            delay(600)
            return
        } else {
            appendLog("[完成]【好友送禮】好友禮物贈送階段結束，自動點擊底部「集點」返回首頁並啟動下一輪巡邏")
            val mainTabBtn = friendGiftTask.getBottomMainTabRect(sw, sh, ocrItems)
            FloatingOverlayService.clickWithOverlayPassthrough(mainTabBtn) {
                service.clickRect(mainTabBtn)
            }
            handleRoundCompletion(service)
        }
    }

    private fun getTabRect(tab: MainTab, sw: Int, sh: Int, ocrItems: List<OcrItem>, screenBitmap: android.graphics.Bitmap? = null): Rect {
        // 1. 動態掃描 cy in 0.07f..0.16f 之頂部四大分頁文字 (文字長度限制 <= 4，杜絕主畫面橫幅或廣告文案干擾)
        val matchedTabs = ocrItems.filter { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy in 0.07f..0.16f && t.length <= 4 && (
                t.contains("首頁") || t.contains("移動") ||
                t.contains("步數") || t.contains("時間") || t.contains("遊戲")
            )
        }

        // 2. 計算動態基準線 Y_anchor，若 OCR 未辨識則嘗試探測黑色底線或採用全機型通用基準線 0.125f (精準命中分頁文字，避開下方廣告橫幅)
        val dynamicYAnchor = if (matchedTabs.isNotEmpty()) {
            matchedTabs.map { it.bounds.centerY() }.average().toInt()
        } else {
            val probedY = if (screenBitmap != null) templateMatcher.findActiveMainTabBarY(screenBitmap) else null
            if (probedY != null) {
                (probedY - (sh * 0.020f).toInt()).coerceIn((sh * 0.100f).toInt(), (sh * 0.155f).toInt())
            } else {
                (sh * 0.125f).toInt()
            }
        }

        // 3. 四大分頁點擊座標水平採用 4 等分相對象限中心 (0.125f, 0.375f, 0.625f, 0.875f)
        val targetCenterX = (sw * tab.xRatio).toInt()
        val halfW = (sw * 0.10f).toInt()
        val halfH = (sh * 0.018f).toInt().coerceAtLeast(24)

        // 若該目標分頁直接具有精準 OCR 文字，優先對齊其水平中心 (限定 cy in 0.07f..0.16f 且字數 t.length <= 4)
        val tabKeywords = when (tab) {
            MainTab.MOVE -> listOf("首頁", "移動")
            MainTab.STEP -> listOf("步數")
            MainTab.TIME -> listOf("時間")
            MainTab.GAME -> listOf("遊戲")
        }
        val directOcr = matchedTabs.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy in 0.07f..0.16f && t.length <= 4 && tabKeywords.any { kw -> t.contains(kw) }
        }

        return if (directOcr != null) {
            val padX = max(24, (sw * 0.04f).toInt())
            Rect(
                (directOcr.bounds.left - padX).coerceIn(0, sw),
                (dynamicYAnchor - halfH).coerceIn(0, sh),
                (directOcr.bounds.right + padX).coerceIn(0, sw),
                (dynamicYAnchor + halfH).coerceIn(0, sh)
            )
        } else {
            Rect(
                (targetCenterX - halfW).coerceIn(0, sw),
                (dynamicYAnchor - halfH).coerceIn(0, sh),
                (targetCenterX + halfW).coerceIn(0, sw),
                (dynamicYAnchor + halfH).coerceIn(0, sh)
            )
        }
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

    /**
     * 確保當前主畫面不在【遊戲】分頁：
     * 因為《LINE 散步趣》的「賓果、抽抽樂、糖果、任務」四顆日常任務圖示只存在於【首頁(移動)】、【步數】、【時間】分頁，
     * 【遊戲】分頁中央為電動機台與遊戲列表，並無此四顆圖示！
     * 若因關閉彈窗 (X) 座標重疊或廣告返回而落在【遊戲】分頁，必須先切換回【首頁(移動)】分頁，否則將無限點擊空氣卡死！
     */
    private suspend fun ensureOnTabWithDailyIcons(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ): Boolean {
        val activeTab = detectTabFromScreen(screenBitmap, ocrItems)

        // 1. 直接放行條件：若畫面上已經看得到「賓果 / 抽抽樂 / 糖果 / 任務」日常任務圖示列，
        // 或具有【首頁(移動) / 步數 / 時間】上半部看板特徵，代表 100% 已經在有日常任務圖示的分頁上！
        val hasDailyIconsOrNonGameBoard = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            (cy in 0.34f..0.47f && (t == "賓果" || t == "抽抽樂" || t == "糖果" || t == "任務" || t.contains("抽抽樂") || t.contains("可領取獎勵"))) ||
            (cy in 0.16f..0.36f && (t.contains("累積數量") || t.contains("距離下次") || t.contains("今天的步數") || t.contains("步數瓶") || t.contains("速度") || t.contains("放置")))
        }
        if (hasDailyIconsOrNonGameBoard || activeTab == MainTab.MOVE || activeTab == MainTab.STEP || activeTab == MainTab.TIME) {
            tabSwitchRetries = 0
            return true
        }

        // 2. 嚴格判定【遊戲】分頁專屬特徵（移除會在首頁/步數下方橫幅出現的 POINTSGAME / 輕鬆賺 / 開始遊玩）
        val hasGamePageFeatures = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("秘寶挖挖哇") || t.contains("數獨達人") || t.contains("接龍高手") || t.contains("簡單賺金幣")
        }
        if ((activeTab == MainTab.GAME || hasGamePageFeatures) && tabSwitchRetries < 2) {
            tabSwitchRetries++
            val targetTab = if (tabSwitchRetries >= 2) MainTab.STEP else MainTab.MOVE
            val tabRect = getTabRect(targetTab, sw, sh, ocrItems, screenBitmap)
            appendLog("[切換] 目前位於【遊戲】分頁（無日常任務圖示），切換至【${targetTab.tabName}】以開啟【${currentPhase.title}】(嘗試:$tabSwitchRetries)...")
            FloatingOverlayService.clickWithOverlayPassthrough(tabRect) {
                service.clickRect(tabRect)
            }
            updateDetail("[切換] 切換至【${targetTab.shortLabel}】準備開啟${currentPhase.title}...", "[切換] 切換分頁...")
            delay(600)
            return false
        }
        tabSwitchRetries = 0
        return true
    }

    internal suspend fun forceAdvanceToNextTab(
        service: AutoAdAccessibilityService,
        sw: Int,
        sh: Int,
        ocrItems: List<OcrItem>,
        screenBitmap: android.graphics.Bitmap,
        reason: String
    ): Boolean {
        return triggerTabSelfHealingAdvancement(service, screenBitmap, ocrItems, sw, sh, reason)
    }

    /**
     * 巡邏防死鎖自癒推進守護：
     * 當在同一分頁停滯或非主畫面無進展達逾時閥值時，
     * 標記當前分頁為已檢查，並主動切換至下一可用分頁推進流程。
     */
    internal suspend fun triggerTabSelfHealingAdvancement(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int,
        reason: String
    ): Boolean {
        if (currentPhase != PatrolPhase.MAIN_TABS) return false

        confirmedEmptyTabs.add(currentTab)
        tabStagnationTicks = 0
        nonMainScreenIdleTicks = 0

        val enabledTabs = tabPriorityOrder.filter { it.isEnabled(config) }.ifEmpty { tabPriorityOrder }
        val nextRemainingTabs = enabledTabs.filter { it !in confirmedEmptyTabs }

        appendLog("[自癒] $reason，啟動防死鎖自癒守護！標記【${currentTab.tabName}】完成並主動推進流程")

        if (nextRemainingTabs.isEmpty()) {
            val tabListStr = enabledTabs.joinToString("->") { it.shortLabel }
            appendLog("[自癒] 四大分頁 ($tabListStr) 巡邏推進完畢，進入下一階段任務！")
            currentPhase = getNextPhase(PatrolPhase.MAIN_TABS)
            return true
        }

        val nextTab = nextRemainingTabs.first()
        val remainOrderStr = nextRemainingTabs.joinToString("->") { it.shortLabel }
        val tabRect = getTabRect(nextTab, sw, sh, ocrItems, screenBitmap)
        appendLog("[自癒] 主動切換至下一分頁【${nextTab.tabName}】(待檢查: $remainOrderStr)")
        currentTab = nextTab
        needsInitialTabSync = true
        tabSwitchRetries = 0
        FloatingOverlayService.clickWithOverlayPassthrough(tabRect) {
            service.clickRect(tabRect)
        }
        updateDetail(
            "[自癒] 防死鎖推進：正在切換至【${nextTab.tabName}】...",
            "[自癒] 切換至[${nextTab.shortLabel}]..."
        )
        delay(600)
        return true
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
                    // Phase Barrier：推進下一階段前，必須確認畫面上無任何殘留領獎彈窗或提示彈窗！
                    val modalClose = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)
                    if (modalClose != null) {
                        appendLog("[領獎] 推進下一階段前，優先關閉殘留領獎彈窗: ${modalClose.first}")
                        FloatingOverlayService.clickWithOverlayPassthrough(modalClose.second) {
                            service.clickRect(modalClose.second)
                        }
                        updateDetail("[領獎] 關閉領獎彈窗中...", "[領獎] 關閉領獎彈窗...")
                        return
                    }
                    val tabListStr = enabledTabs.joinToString("->") { it.shortLabel }
                    appendLog("[成功] 四大分頁 ($tabListStr) 常規獎勵皆已領取完畢！(共完成 ${_uiStatus.value.adsCompletedCount} 次)")
                    currentPhase = getNextPhase(PatrolPhase.MAIN_TABS)
                    return
                }

                // 檢查是否已成功換頁，分頁變更時重置停滯計數與時間分頁紅利看廣告狀態
                if (lastObservedTab != currentTab) {
                    lastObservedTab = currentTab
                    tabStagnationTicks = 0
                    timeTask.hasTriedBonusBerryInCurrentStay = false
                }

                // 巡邏同一分頁停滯自癒守護
                tabStagnationTicks++
                if (tabStagnationTicks >= TAB_STAGNATION_THRESHOLD) {
                    triggerTabSelfHealingAdvancement(
                        service = service,
                        screenBitmap = screenBitmap,
                        ocrItems = ocrItems,
                        sw = sw,
                        sh = sh,
                        reason = "停留【${currentTab.tabName}】達 $tabStagnationTicks 個 Tick (~7.5 秒) 無操作推進"
                    )
                    return
                }

                // 依優先順序（1.移動 -> 2.時間 -> 3.遊戲 -> 4.步數）決定當前應檢查的分頁：
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
                    val tabRect = getTabRect(desiredTab, sw, sh, ocrItems, screenBitmap)
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
                        templateMatcher = templateMatcher,
                        ocrRecognizer = ocrRecognizer
                    )
                } else {
                    null
                }

                if (target != null) {
                    val targetCyRatio = target.bounds.centerY().toFloat() / sh
                    // 硬性座標防護罩：在非遊戲分頁之主畫面（首頁/步數/時間），嚴格禁止點擊 y in 0.445f..0.895f 下方橫幅廣告區！
                    if (currentTab != MainTab.GAME && targetCyRatio in 0.445f..0.895f) {
                        appendLog("[防護] 攔截主畫面下方橫幅廣告誤判點擊 (${target.targetLabel})，跳過此目標！")
                    } else {
                        emptyTicksOnCurrentTab = 0
                        tabStagnationTicks = 0
                        nonMainScreenIdleTicks = 0
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
                            "[彈窗] 等待彈窗出現..."
                        )
                        delay(550)
                        return
                    }
                }

                // 本分頁目前沒有亮起的可領取獎勵按鈕：必須連續確認 3 次無目標才換頁，確保單一分頁徹底巡完再換頁！
                emptyTicksOnCurrentTab++
                if (emptyTicksOnCurrentTab < 3) {
                    updateDetail(
                        "[巡邏] 正在檢查【${currentTab.tabName}】是否有可領取獎勵 ($emptyTicksOnCurrentTab/3)...",
                        "[巡邏] 檢查[${currentTab.shortLabel}] ($emptyTicksOnCurrentTab/3)..."
                    )
                    return
                }

                // 確認本分頁已無可領取項目
                emptyTicksOnCurrentTab = 0
                timeTask.hasTriedBonusBerryInCurrentStay = false
                confirmedEmptyTabs.add(currentTab)

                val nextRemainingTabs = enabledTabs.filter { it !in confirmedEmptyTabs }
                if (nextRemainingTabs.isEmpty()) {
                    val tabListStr = enabledTabs.joinToString("->") { it.shortLabel }
                    appendLog("[成功] 四大分頁 ($tabListStr) 常規獎勵皆已領取完畢！")
                    currentPhase = getNextPhase(PatrolPhase.MAIN_TABS)
                    return
                }

                val nextTab = nextRemainingTabs.first()
                val remainOrderStr = nextRemainingTabs.joinToString("->") { it.shortLabel }
                val tabRect = getTabRect(nextTab, sw, sh, ocrItems, screenBitmap)
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
                    subDialogOpenAttempts = 0
                    currentPhase = getNextPhase(PatrolPhase.BINGO)
                    return
                }
                if (!ensureOnTabWithDailyIcons(service, screenBitmap, ocrItems, sw, sh)) return
                subDialogOpenAttempts++
                if (subDialogOpenAttempts == 3) {
                    val moveTabRect = getTabRect(MainTab.MOVE, sw, sh, ocrItems, screenBitmap)
                    appendLog("[切換] 開啟【每日賓果】重試中，點擊【首頁(移動)】校準畫面分頁...")
                    FloatingOverlayService.clickWithOverlayPassthrough(moveTabRect) { service.clickRect(moveTabRect) }
                    delay(500)
                    return
                }
                if (subDialogOpenAttempts >= 6) {
                    subDialogOpenAttempts = 0
                    isBingoCompleted = true
                    currentPhase = getNextPhase(PatrolPhase.BINGO)
                    appendLog("[提示] 連續嘗試開啟【每日賓果】無回應，推進至下一階段")
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
                    subDialogOpenAttempts = 0
                    currentPhase = getNextPhase(PatrolPhase.LOTTERY)
                    return
                }
                if (!ensureOnTabWithDailyIcons(service, screenBitmap, ocrItems, sw, sh)) return
                subDialogOpenAttempts++
                if (subDialogOpenAttempts == 3) {
                    val moveTabRect = getTabRect(MainTab.MOVE, sw, sh, ocrItems, screenBitmap)
                    appendLog("[切換] 開啟【抽抽樂】重試中，點擊【首頁(移動)】校準畫面分頁...")
                    FloatingOverlayService.clickWithOverlayPassthrough(moveTabRect) { service.clickRect(moveTabRect) }
                    delay(500)
                    return
                }
                if (subDialogOpenAttempts >= 6) {
                    subDialogOpenAttempts = 0
                    isLotteryCompleted = true
                    currentPhase = getNextPhase(PatrolPhase.LOTTERY)
                    appendLog("[提示] 連續嘗試開啟【抽抽樂】無回應，推進至下一階段")
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
                    subDialogOpenAttempts = 0
                    missionOpenCycleCount = 0
                    currentPhase = getNextPhase(PatrolPhase.MISSION)
                    return
                }
                missionOpenCycleCount++
                if (missionOpenCycleCount >= 6) {
                    appendLog("[任務斷路器] 任務視窗單輪重複開啟達 $missionOpenCycleCount 次未能順利完結，觸發防死鎖斷路保護，強制推進至下一階段！")
                    subDialogOpenAttempts = 0
                    missionOpenCycleCount = 0
                    isMissionCompleted = true
                    currentPhase = getNextPhase(PatrolPhase.MISSION)
                    return
                }
                if (!ensureOnTabWithDailyIcons(service, screenBitmap, ocrItems, sw, sh)) return
                subDialogOpenAttempts++
                if (subDialogOpenAttempts == 3) {
                    val moveTabRect = getTabRect(MainTab.MOVE, sw, sh, ocrItems, screenBitmap)
                    appendLog("[切換] 開啟【任務】重試中，點擊【首頁(移動)】校準畫面分頁...")
                    FloatingOverlayService.clickWithOverlayPassthrough(moveTabRect) { service.clickRect(moveTabRect) }
                    delay(500)
                    return
                }
                if (subDialogOpenAttempts >= 6) {
                    subDialogOpenAttempts = 0
                    missionOpenCycleCount = 0
                    isMissionCompleted = true
                    currentPhase = getNextPhase(PatrolPhase.MISSION)
                    appendLog("[提示] 連續嘗試開啟【任務】無回應，推進至下一階段")
                    return
                }
                appendLog("[目標] 點擊主畫面【任務】圖示按鈕 (第 ${missionOpenCycleCount} 次)...")
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
                if (!config.enableReceiveGiftTask || isReceiveGiftCompleted) {
                    currentPhase = getNextPhase(PatrolPhase.RECEIVE_GIFT)
                    return
                }
                appendLog("[禮物] 點擊主畫面右上角【禮物盒】圖示按鈕...")
                val giftBoxRect = receiveGiftTask.findHeaderGiftBox(screenBitmap, sw, sh)
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
        adLoadErrorCountPerTab.clear()
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
        bingoOpenCellRetryCount = 0
        bingoDialogCloseAttempts = 0
        subDialogOpenAttempts = 0
        isLotteryCompleted = false
        resetLotterySessionState(resetCloseAttempts = true)
        timeTask.hasTriedBonusBerryInCurrentStay = false
        isMissionCompleted = false
        checkedMissionTabs.clear()
        isFriendGiftCompleted = false
        isReceiveGiftCompleted = false
        friendGiftEmptyTicks = 0
        friendGiftReturnHomeTicks = 0
        friendGiftOpenDrawerTicks = 0
        friendGiftDialogRetryCount = 0
        pendingGiftButtonClickPoint = null
        friendGiftTask.resetSession()

        currentTab = tabPriorityOrder.firstOrNull { it.isEnabled(config) } ?: MainTab.MOVE
        currentPhase = if (tabPriorityOrder.any { it.isEnabled(config) }) {
            PatrolPhase.MAIN_TABS
        } else {
            getNextPhase(PatrolPhase.MAIN_TABS)
        }

        appendLog("[循環] 第 $currentRound 輪自動巡邏已重置啟動！首發階段：【${currentPhase.title}】")
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
            bingoOpenCellRetryCount = 0
            bingoDialogCloseAttempts = 0
            isLotteryCompleted = false
            resetLotterySessionState(resetCloseAttempts = true)
            consecutiveNetworkErrorCount = 0
            isMissionCompleted = false
            checkedMissionTabs.clear()
            isFriendGiftCompleted = false
            isReceiveGiftCompleted = false
            friendGiftEmptyTicks = 0
            friendGiftReturnHomeTicks = 0
            friendGiftOpenDrawerTicks = 0
            friendGiftDialogRetryCount = 0
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

    internal fun resetLotterySessionState(resetCloseAttempts: Boolean = false) {
        hasClickedLotteryAdReward = false
        lotteryResultRewardWaitTicks = 0
        lotteryResultCloseAttempts = 0
        if (resetCloseAttempts) {
            lotteryDialogCloseAttempts = 0
        }
        lotteryFiftyDrawAttempts = 0
        lotteryFreeDrawAttempts = 0
        lotteryFiveDrawAttempts = 0
    }

    internal fun getNextPhase(after: PatrolPhase): PatrolPhase {
        if (after == PatrolPhase.LOTTERY) {
            // 注意：切勿在此重置 lotteryDialogCloseAttempts，否則第 6 次強制關閉點擊後會被歸零回「嘗試: 1」造成無限循環！
            resetLotterySessionState(resetCloseAttempts = false)
        }
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
                PatrolPhase.RECEIVE_GIFT -> config.enableReceiveGiftTask && !isReceiveGiftCompleted
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
        appendLog("[返回]【${currentTab.tabName}】獎勵皆在冷卻中，點擊 [取消] 關閉彈窗並切換至下一分頁/任務")
        FloatingOverlayService.clickWithOverlayPassthrough(cancelBtn) {
            service.clickRect(cancelBtn)
        }
        confirmedEmptyTabs.add(currentTab)
        emptyTicksOnCurrentTab = 0

        val enabledTabs = tabPriorityOrder.filter { it.isEnabled(config) }.ifEmpty { tabPriorityOrder }
        val nextRemainingTabs = enabledTabs.filter { it !in confirmedEmptyTabs }
        if (nextRemainingTabs.isEmpty()) {
            val tabListStr = enabledTabs.joinToString("->") { it.shortLabel }
            appendLog("[成功] 四大分頁 ($tabListStr) 常規獎勵皆已檢查完畢！準備檢查日常任務...")
            currentPhase = getNextPhase(PatrolPhase.MAIN_TABS)
        } else {
            val nextTab = nextRemainingTabs.first()
            val remainOrderStr = nextRemainingTabs.joinToString("->") { it.shortLabel }
            appendLog("[切換]【${currentTab.tabName}】冷卻中，依序切換至【${nextTab.tabName}】(待檢查: $remainOrderStr)")
            currentTab = nextTab
            needsInitialTabSync = true
            tabSwitchRetries = 0
        }

        transitionTo(EngineState.IN_GAME_SCAN, "已點擊 [取消]，切換至下一分頁/任務...", "[巡邏] 切換巡邏中...")
    }

    internal suspend fun handleTriggerAdPopup(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        popupRetryTicks++
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 若點擊目標後已直接進入廣告載入中畫面，立即轉入廣告監控流程！
        if (ocrRecognizer.isAdLoadingScreen(ocrItems, screenBitmap)) {
            appendLog("[載入] 點擊後進入廣告載入中狀態，自動轉入廣告監控流程！")
            adCloserHandler.onEnterAdMode()
            transitionTo(EngineState.AD_WATCHING, "廣告正在載入中，請稍候...", "[載入] 廣告載入中...")
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

        // 若彈出好友送禮「贈送完畢」彈窗，立即點擊 [關閉] 並返回主畫面/好友分頁繼續巡邏！
        val completedCloseBtn = friendGiftTask.findSentCompletedCloseButton(screenBitmap, ocrItems)
        if (completedCloseBtn != null) {
            pendingGiftButtonClickPoint?.let { friendGiftTask.markGifted(it) }
            pendingGiftButtonClickPoint = null
            appendLog("[禮物]【好友送禮】等待彈窗中偵測到「贈送完畢」彈窗，點擊 [關閉]！")
            FloatingOverlayService.clickWithOverlayPassthrough(completedCloseBtn) {
                service.clickRect(completedCloseBtn)
            }
            transitionTo(EngineState.IN_GAME_SCAN, "已關閉贈送完畢彈窗，繼續巡邏...", "[禮物] 關閉彈窗")
            return
        }

        val popupAction = findPopupActionTarget(screenBitmap, ocrItems)
        if (popupAction != null) {
            executePopupAction(service, popupAction)
            return
        }

        // 檢查是否彈出好友送禮確認彈窗（『也送給自己』）
        if (friendGiftTask.isSendGiftModalOpen(ocrItems)) {
            if (friendGiftDialogRetryCount == 0) {
                val selfGiftOption = friendGiftTask.findSelfGiftButton(screenBitmap, ocrItems)
                if (selfGiftOption != null) {
                    friendGiftDialogRetryCount = 1
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
            } else {
                val normalGiftBtn = friendGiftTask.findNormalGiftButton(screenBitmap, ocrItems)
                friendGiftDialogRetryCount = 0
                appendLog("[禮物]【好友送禮】等待彈窗改為點擊【${normalGiftBtn.first}】直接送禮！")
                FloatingOverlayService.clickWithOverlayPassthrough(normalGiftBtn.second) {
                    service.clickRect(normalGiftBtn.second)
                }
                transitionTo(EngineState.IN_GAME_SCAN, "已贈送好友禮物，繼續巡邏...", "[禮物] 贈送完成")
                return
            }
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
                (x in (sw * 0.18f).toInt()..(sw * 0.82f).toInt() && y < sh * 0.08f) ||
                y < max((sh * 0.040f).toInt(), 40)
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
            handleInGameLinePromoClose(service, screenBitmap, sw, sh, "彈窗觸發期間偵測到 LINE 宣傳廣告/促銷彈窗")
            delay(500)
            transitionTo(EngineState.IN_GAME_SCAN, "已處理宣傳廣告，繼續巡邏...", "[巡邏] 巡邏畫面目標中...")
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
        val hasAdOcrDirect = !onMainScreenDirect && (
            ocrItems.any { item ->
                val t = item.text
                t.contains("開啟商店") ||
                t.contains("DOWNLOAD", ignoreCase = true) ||
                t.contains("INSTALL", ignoreCase = true) ||
                t.contains("立即下載") ||
                t.contains("下載") ||
                t.contains("連勝中") ||
                t.contains("立即观看") ||
                t.contains("立即觀看") ||
                t.contains("youtu.be", ignoreCase = true) ||
                t.contains("youtube.com", ignoreCase = true) ||
                OcrRecognizer.AD_REWARD_GRANTED_KEYWORDS.any { kw -> t.contains(kw, ignoreCase = true) }
            } || ocrRecognizer.detectAdCountdownSeconds(ocrItems, sh) != null
        )
        val isGameScreen = adCloserHandler.isGameScreenVisible(service, screenBitmap, ocrItems)
        // 嚴格安全防護：若畫面仍在主畫面或領獎下半部彈窗，絕不誤判為全螢幕廣告！
        if (!onMainScreenDirect && !isBottomRewardPopup && ((hasAdOcrDirect && !isStillInSubDialog) || (!isStillInSubDialog && (directGeoX != null || isExternalDirect || hasBrowserTitleDirect || (!isGameScreen && popupRetryTicks >= 2))))) {
            pendingMissionDirectClaim = false
            appendLog("[廣告] 點擊目標後已直接進入全螢幕廣告/宣傳頁，啟動廣告監控流程！")
            adCloserHandler.onEnterAdMode()
            transitionTo(EngineState.AD_WATCHING, "已進入廣告畫面，安全監控中...", "[廣告] 監控中...")
            return
        }

        if (pendingMissionDirectClaim && !onMainScreenDirect && missionTask.isMissionDialogOpen(ocrItems, sh, screenBitmap) && popupRetryTicks >= 1) {
            pendingMissionDirectClaim = false
            appendLog("[任務] 免看廣告獎勵已直接領取，繼續檢查下一項任務")
            transitionTo(EngineState.IN_GAME_SCAN, "任務獎勵已領取，繼續檢查下一項...", "[任務] 繼續領取...")
            return
        }

        if (isStillInSubDialog && popupRetryTicks >= 2) {
            pendingMissionDirectClaim = false
            appendLog("[子視窗] 領獎確認逾時（無有效目標或已領畢），退出子彈窗繼續巡邏")
            transitionTo(EngineState.IN_GAME_SCAN, "領獎確認逾時，退出子彈窗繼續巡邏...", "[巡邏] 檢查彈窗中...")
            return
        }

        if (onMainScreenDirect && popupRetryTicks >= 3) {
            pendingMissionDirectClaim = false
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
                pendingMissionDirectClaim = false
                appendLog("[一般] 點擊彈窗一般按鈕: [${normalOrSkip.text}]")
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
            pendingMissionDirectClaim = false
            appendLog("[返回] 未偵測到看廣告彈窗，返回主畫面繼續巡邏")
            transitionTo(EngineState.IN_GAME_SCAN, "重新掃描遊戲畫面...", "[巡邏] 巡邏畫面目標中...")
        } else {
            updateDetail("[等待] 等待看廣告彈窗出現 ($popupRetryTicks/2)...", "[等待] 等待彈窗 ($popupRetryTicks/2)...")
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
                consecutiveWatchdogTimeouts = 0
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

    /**
     * 當系統返回失效或達到逾時上限仍卡在外部商店／內嵌廣告頁時，統一執行三段式主畫面恢復：
     * 非遊戲前景直接喚醒；若前景已是散步趣套件但畫面仍被內嵌廣告 Activity 攔截，依序執行 Home -> CLEAR_TOP -> CLEAR_TASK。
     */
    private suspend fun recoverStuckAdToMainScreen(
        service: AutoAdAccessibilityService,
        sourceBitmap: android.graphics.Bitmap,
        targetGamePkg: String,
        nonGameTriggerDesc: String
    ) {
        val postFg = service.getForegroundPackageName()
        if (!AppConfig.isGamePackage(postFg)) {
            appendLog("[計時強退] ${nonGameTriggerDesc} (${postFg ?: "unknown"})，強制喚醒散步趣 ($targetGamePkg) 拉回前景！")
            service.bringAppToFront(targetGamePkg)
            delay(800)
            consecutiveWatchdogTimeouts = 0
            adWatchdogCooldownTicks = 4
            return
        }

        // 前景套件雖為遊戲本體 (LGWKTW/LGWALK)，但內嵌廣告 Activity 可能吞噬 Back 仍卡死在廣告畫面上，重抓截圖確認
        val (hasVerifyBmp, verifyReturned) = verifyRecoveredGameScreen(service, sourceBitmap)
        if (verifyReturned) {
            consecutiveWatchdogTimeouts = 0
            adWatchdogCooldownTicks = 4
            return
        }

        consecutiveWatchdogTimeouts++
        if (consecutiveWatchdogTimeouts == 1) {
            appendLog("[計時強退] Back 遭內嵌廣告攔截，執行 Level 1 清棧逃脫：Home -> CLEAR_TOP ($targetGamePkg)")
            service.pressHome()
            delay(450)
            service.forceResetAppTaskToFront(targetGamePkg, clearTask = false)
            delay(1200)
            val (hasVerify2, recoveredByClearTop) = verifyRecoveredGameScreen(service, sourceBitmap)
            if (recoveredByClearTop) {
                appendLog("[計時強退] Level 1 CLEAR_TOP 已成功脫離內嵌廣告並恢復散步趣主畫面")
                consecutiveWatchdogTimeouts = 0
                adWatchdogCooldownTicks = 4
            } else if (hasVerify2 || hasVerifyBmp) {
                appendLog("[計時強退] CLEAR_TOP 後仍停留在頑固內嵌廣告，立即升級 Level 2 CLEAR_TASK ($targetGamePkg) 重建主任務！")
                service.forceResetAppTaskToFront(targetGamePkg, clearTask = true)
                delay(2200)
                adWatchdogCooldownTicks = 8
            } else {
                adWatchdogCooldownTicks = 4
            }
        } else {
            appendLog("[計時強退] 連續第 $consecutiveWatchdogTimeouts 次逾時（或無截圖回傳），直接執行 Level 2 CLEAR_TASK ($targetGamePkg) 重建主任務！")
            service.pressHome()
            delay(450)
            service.forceResetAppTaskToFront(targetGamePkg, clearTask = true)
            delay(2200)
            adWatchdogCooldownTicks = 8
        }
    }

    private suspend fun verifyRecoveredGameScreen(
        service: AutoAdAccessibilityService,
        sourceBitmap: android.graphics.Bitmap? = null
    ): Pair<Boolean, Boolean> {
        val verifyBmp = service.captureScreenBitmap() ?: return Pair(false, false)
        return try {
            val verifyOcr = ocrRecognizer.recognize(verifyBmp)
            val isBack = ocrRecognizer.isLineWalkMainScreenVisible(verifyBmp, verifyOcr, templateMatcher) ||
                adCloserHandler.isGameScreenVisible(service, verifyBmp, verifyOcr)
            Pair(true, isBack)
        } finally {
            if (verifyBmp !== sourceBitmap && !verifyBmp.isRecycled) {
                verifyBmp.recycle()
            }
        }
    }

    internal suspend fun handleAdWatching(
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
        // 依 adCloserHandler.getEffectiveWatchdogLimitSec(config.maxAdWaitSeconds) 動態取得計時上限，
        // 超過上限先寫入 [狀態Snap] 與 OCR 摘要，再突破容器防護發送系統返回鍵；若前景仍非散步趣則強制喚醒目標套件！
        val alreadyBackInGame = adCloserHandler.isGameScreenVisible(service, screenBitmap, ocrItems)
        val timerLimitSec = adCloserHandler.getEffectiveWatchdogLimitSec(config.maxAdWaitSeconds)
        val adElapsed = adCloserHandler.getElapsedSeconds()
        if (!alreadyBackInGame && adElapsed >= timerLimitSec) {
            timeoutForceEscapeCount++
            appendLog("[警報] 廣告播放達 ${adElapsed}s 超過計時器上限 (${timerLimitSec}s)，準備強制關閉廣告拉回散步趣！")
            appendLog(buildPhoneStateSnapshot("AD_WATCHDOG_TIMEOUT(${adElapsed}s/${timerLimitSec}s)", service, screenBitmap, ocrItems))
            val ocrDigest = formatStagnationOcrDigest(ocrItems, sw, sh, maxItems = 6)
            if (ocrDigest.isNotBlank()) {
                appendLog("[畫面線索] 廣告停滯 OCR 摘要: $ocrDigest")
            }
            if (!service.safeNavigateBack(minIntervalMs = 300L, allowInGame = true, forceEscapeAdPackage = true)) {
                recordBackRejectIfAny(service, "WatchdogEscape#1", screenBitmap, ocrItems)
            }
            delay(500)
            if (!service.safeNavigateBack(minIntervalMs = 300L, allowInGame = true, forceEscapeAdPackage = true)) {
                recordBackRejectIfAny(service, "WatchdogEscape#2", screenBitmap, ocrItems)
            }
            delay(600)
            val targetGamePkg = resolvePreferredGamePackage(service)
            recoverStuckAdToMainScreen(
                service = service,
                sourceBitmap = screenBitmap,
                targetGamePkg = targetGamePkg,
                nonGameTriggerDesc = "兩次系統返回後前景仍非遊戲套件"
            )
            adCloserHandler.clearAdSessionTimer()
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
                if (res.bounds.isEmpty || res.sourceLabel.contains("返回鍵")) {
                    appendLog("[點擊] 執行關閉/略過動作: ${res.sourceLabel}")
                } else {
                    val cx = res.bounds.centerX()
                    val cy = res.bounds.centerY()
                    val nx = if (sw > 0) String.format(Locale.US, "%.1f", cx * 100f / sw) else "0.0"
                    val ny = if (sh > 0) String.format(Locale.US, "%.1f", cy * 100f / sh) else "0.0"
                    appendLog("[點擊] 點擊關閉/略過按鈕: ${res.sourceLabel} @ ($cx, $cy | ${nx}%, ${ny}%)")
                }
                updateDetail(
                    "[點擊] 已點擊 ${res.sourceLabel}，確認是否回到遊戲...",
                    "[點擊] 已點關閉，確認中..."
                )
            }

            is AdStepResult.ReturnedToGame -> {
                consecutiveWatchdogTimeouts = 0
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
                        // lotteryTask.getCloseButtonBounds(sw, sh)
            val closeBtn = lotteryTask.getCloseButtonBounds(sw, sh, ocrItems, screenBitmap)
                        FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                            service.clickRect(closeBtn)
                        }
                        currentPhase = getNextPhase(PatrolPhase.LOTTERY)
                        appendLog("[逾時] 抽抽樂廣告載入逾時，關閉視窗並切換至下一項")
                    } else if (missionTask.isMissionDialogOpen(ocrItems, sh, screenBitmap)) {
                        val closeBtn = missionTask.getCloseButtonBounds(sw, sh, ocrItems, screenBitmap)
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
                if (res.label == "廣告載入失敗彈窗") {
                    appendLog("[錯誤] 廣告載入失敗，已自動關閉彈窗並原地重試當前分頁！")
                    emptyTicksOnCurrentTab = 0
                    confirmedEmptyTabs.remove(currentTab)
                    transitionTo(EngineState.IN_GAME_SCAN, "已關閉廣告錯誤彈窗，重試當前分頁...", "[巡邏] 重試分頁中...")
                    return
                }
                if (res.label == "廣告未成功啟動") {
                    appendLog("[提示] 廣告未成功啟動或無廣告庫存，重試當前分頁")
                    emptyTicksOnCurrentTab = 0
                    confirmedEmptyTabs.remove(currentTab)
                    transitionTo(EngineState.IN_GAME_SCAN, "廣告未成功啟動，返回重試...", "[巡邏] 巡邏畫面目標中...")
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
                consecutiveWatchdogTimeouts = 0
                val newAdsCount = _uiStatus.value.adsCompletedCount + 1
                _uiStatus.value = _uiStatus.value.copy(adsCompletedCount = newAdsCount)
                emptyTicksOnCurrentTab = 0
                resetClickCircuitBreaker()
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
                recordBackRejectIfAny(service, "AdCloserWatchdog(${res.elapsedSec}s)", screenBitmap, ocrItems)
                updateDetail(
                    "[監控] 嘗試 ${res.actionLabel} 關閉廣告 (${res.elapsedSec}s)...",
                    "[監控] 嘗試關閉 (${res.elapsedSec}s)..."
                )
                if (res.elapsedSec >= timerLimitSec) {
                    timeoutForceEscapeCount++
                    val targetGamePkg = resolvePreferredGamePackage(service)
                    appendLog("[計時強退] 廣告等待達 ${res.elapsedSec}s 超過計時器上限 (${timerLimitSec}s) 仍未關閉，強制喚醒散步趣 ($targetGamePkg) 拉回前景！")
                    appendLog(buildPhoneStateSnapshot("WATCHDOG_BACK_TIMEOUT(${res.elapsedSec}s/${timerLimitSec}s)", service, screenBitmap, ocrItems))
                    val ocrDigest = formatStagnationOcrDigest(ocrItems, sw, sh, maxItems = 6)
                    if (ocrDigest.isNotBlank()) {
                        appendLog("[畫面線索] 廣告停滯 OCR 摘要: $ocrDigest")
                    }
                    recoverStuckAdToMainScreen(
                        service = service,
                        sourceBitmap = screenBitmap,
                        targetGamePkg = targetGamePkg,
                        nonGameTriggerDesc = "廣告等待達 ${res.elapsedSec}s 超過計時器上限 (${timerLimitSec}s)"
                    )
                    adCloserHandler.clearAdSessionTimer()
                    transitionTo(EngineState.IN_GAME_SCAN, "逾時強制拉回散步趣主畫面，繼續巡邏...", "[巡邏] 重新巡邏中...")
                }
            }

            is AdStepResult.ForceBringGameToFront -> {
                timeoutForceEscapeCount++
                val targetGamePkg = resolvePreferredGamePackage(service)
                appendLog("[計時強退] ${res.reason} (耗時=${res.elapsedSec}s, 前景=${res.foregroundPkg})，強制喚醒散步趣 ($targetGamePkg) 拉回前景！")
                appendLog(buildPhoneStateSnapshot("FORCE_BRING_FRONT(${res.reason})", service, screenBitmap, ocrItems))
                val ocrDigest = formatStagnationOcrDigest(ocrItems, sw, sh, maxItems = 6)
                if (ocrDigest.isNotBlank()) {
                    appendLog("[畫面線索] 停滯畫面 OCR 摘要: $ocrDigest")
                }
                recoverStuckAdToMainScreen(
                    service = service,
                    sourceBitmap = screenBitmap,
                    targetGamePkg = targetGamePkg,
                    nonGameTriggerDesc = "${res.reason} 後前景仍非遊戲套件"
                )
                adCloserHandler.clearAdSessionTimer()
                transitionTo(EngineState.IN_GAME_SCAN, "強制拉回散步趣主畫面，繼續巡邏...", "[巡邏] 重新巡邏中...")
            }

            is AdStepResult.SwipedToReveal -> {
                appendLog("[廣告] 偵測到試玩廣告按鈕隱藏，執行滑動螢幕喚醒按鈕 (嘗試: ${res.attempt}/4)")
                updateDetail(
                    "[廣告] 試玩廣告按鈕隱藏，滑動螢幕喚醒中 (嘗試: ${res.attempt}/4)...",
                    "[廣告] 播放中 (${res.elapsedSec}s)..."
                )
            }

            is AdStepResult.ScanningAd -> {
                updateDetail(
                    "[搜尋] 搜尋關閉 X 或略過按鈕 (已播 ${res.elapsedSec}s)...",
                    "[搜尋] 搜尋關閉鈕 (${res.elapsedSec}s)..."
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
            val closeBtn = ocrRecognizer.findLineInHousePromoCloseButton(screenBitmap, sw, sh)
            appendLog("[廣告] 領獎確認期間偵測到 LINE 宣傳廣告/促銷彈窗，點擊右上角 (X) 關閉")
            FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
                service.clickRect(closeBtn, maxJitterPx = 2, allowTopSafeZone = true)
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

        // 嚴格子視窗排除：若當前正在抽抽樂、每日賓果、日常任務、好友送/收禮等子畫面，絕對禁止執行主畫面領獎彈窗偵測！
        if (lotteryTask.isLotteryDialogOpen(ocrItems, sh) ||
            bingoTask.isBingoDialogOpen(ocrItems, sh) ||
            missionTask.isMissionDialogOpen(ocrItems, sh, screenBitmap) ||
            receiveGiftTask.isReceiveGiftDialogOpen(ocrItems, sh) ||
            friendGiftTask.isFriendsPage(ocrItems, sh)
        ) {
            return null
        }

        // 嚴格安全防護：四大分頁的領獎彈窗必備彈窗標題（獲得票券！/獲得金幣！）或底部純白「取消」膠囊鈕！若兩者皆不存在，絕對禁止在下半部點擊任何按鈕！
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
        if (!hasBottomModalTitle && !hasBottomModalCancel) {
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
                // 排除彈窗標題、取消按鈕、與抽抽樂/規則一覽表頭
                if (t.endsWith("！") || t.endsWith("!") || t == "獲得票券" || t == "獲得金幣" || t == "取消" ||
                    t.contains("順位") || t.contains("抽獎") || t.contains("一覽") || t.contains("獎勵") || t.contains("規則")
                ) {
                    continue
                }
                val matchesKeyword = OcrRecognizer.GAME_AD_TRIGGER_KEYWORDS.any { kw -> t.contains(kw, ignoreCase = true) }
                val hasRewardWord = Regex("""\d+\s*獲得""").containsMatchIn(t) || t.contains("看廣告") || t.contains("紅利")
                val coins = parseRewardCoins(t)
                if ((matchesKeyword || hasRewardWord) &&
                    (coins > 0 || t.contains("跳過廣告") || t.contains("免費")) &&
                    OcrRecognizer.GAME_AD_EXCLUDE_KEYWORDS.none { ex -> t.contains(ex, ignoreCase = true) } &&
                    !ocrRecognizer.isAreaInCooldown(ocrItems, cy - btnMarginY, cy + btnMarginY) &&
                    !templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy)
                ) {
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
            !ocrRecognizer.isAreaInCooldown(ocrItems, badgePopupBtn.bounds.top - btnMarginY, badgePopupBtn.bounds.bottom + (sh * 0.06f).toInt())
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

        // 依照獲得金幣數量由大到小排序 (100 > 60 > 50 > 30 > 15 > 10 > 5 > 2 > 1 > 0)，選取金幣最高者！
        if (candidates.isNotEmpty()) {
            val bestCandidate = candidates.maxByOrNull { it.second } ?: candidates.first()
            return bestCandidate.first
        }

        return null
    }

    private suspend fun handleInGameLinePromoClose(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        sw: Int,
        sh: Int,
        logPrefix: String
    ) {
        inGameLinePromoRetryCount++
        if (inGameLinePromoRetryCount >= 2 && inGameLinePromoRetryCount % 2 == 0) {
            appendLog("[廣告] $logPrefix，點擊 (X) 後仍停留，改發送安全返回鍵關閉 (第${inGameLinePromoRetryCount}次)")
            updateDetail("[關閉] 改用返回鍵關閉宣傳視窗...", "[巡邏] 返回關閉宣傳...")
            if (!service.safeNavigateBack(minIntervalMs = 600L, allowInGame = true)) {
                recordBackRejectIfAny(service, "InGameLinePromoBack", screenBitmap)
            }
            return
        }

        val promoNodeClose = nodeInspector.findAdCloseNode(
            root = service.getSafeRootNode(),
            screenWidth = sw,
            screenHeight = sh,
            isFullScreenAd = true
        )?.bounds?.takeIf { b -> b.centerX() > sw * 0.70f && b.centerY() < sh * 0.25f }
        val closeBtn = promoNodeClose ?: ocrRecognizer.findLineInHousePromoCloseButton(screenBitmap, sw, sh)
        val cx = closeBtn.centerX()
        val cy = closeBtn.centerY()
        val nx = if (sw > 0) String.format(Locale.US, "%.1f", cx * 100f / sw) else "0.0"
        val ny = if (sh > 0) String.format(Locale.US, "%.1f", cy * 100f / sh) else "0.0"
        appendLog("[廣告] $logPrefix，點擊右上角 (X) 關閉視窗 @ ($cx, $cy | ${nx}%, ${ny}%)")
        updateDetail("[關閉] 正在關閉宣傳廣告視窗...", "[巡邏] 關閉宣傳視窗中...")
        FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
            service.clickRect(closeBtn, maxJitterPx = 2, allowTopSafeZone = true)
        }
    }

    internal fun transitionTo(newState: EngineState, detail: String, safeOverlayText: String) {
        if (newState != EngineState.TRIGGER_AD_POPUP) {
            pendingMissionDirectClaim = false
        }
        if (newState != EngineState.IN_GAME_SCAN) {
            nonGameScreenTicks = 0
            inGameLinePromoRetryCount = 0
        }
        if (newState == EngineState.AD_WATCHING) {
            resetClickCircuitBreaker()
            AutoAdAccessibilityService.instance.value?.clearBackRejectReason()
        }

        _uiStatus.value = _uiStatus.value.copy(
            state = newState,
            currentTab = this.currentTab,
            statusDetail = detail,
            overlaySafeDetail = safeOverlayText
        )
        appendLog("[狀態] [${newState.title}] $detail")
    }

    private fun updateDetail(detail: String, safeOverlayText: String) {
        _uiStatus.value = _uiStatus.value.copy(
            currentTab = this.currentTab,
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
            if (_fullSessionLogs.size >= 20000) {
                _fullSessionLogs.removeAt(0)
            }
            _fullSessionLogs.add(line)
        }
        val updated = (_uiStatus.value.recentLogs + line).takeLast(40)
        _uiStatus.value = _uiStatus.value.copy(recentLogs = updated)
    }

    /**
     * 將螢幕像素矩形格式化為 AI 友善診斷座標字串 (含絕對邊界、中心點與百分比座標)。
     */
    internal fun formatRectForLog(rect: Rect, sw: Int, sh: Int): String {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val nx = if (sw > 0) String.format(Locale.US, "%.1f", cx * 100f / sw) else "0.0"
        val ny = if (sh > 0) String.format(Locale.US, "%.1f", cy * 100f / sh) else "0.0"
        return "(${rect.left},${rect.top}-${rect.right},${rect.bottom}|c=$cx,$cy|${nx}%,${ny}%)"
    }

    /**
     * 將目前畫面的 OCR 文字項目濃縮為精簡線索摘要（預設最多 6 筆、單筆文字上限 16 字）。
     * 自動排除頂部系統狀態列、開發人員選項指標位置除錯字樣，以及重複的詞素拆字項目。
     */
    internal fun formatStagnationOcrDigest(
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int,
        maxItems: Int = 6
    ): String {
        if (ocrItems.isEmpty() || maxItems <= 0) return ""
        val statusBarTopCutY = if (sh > 0) {
            maxOf(ScreenViewportCalibrator.getStatusBarSafeHeight(sw, sh), (sh * 0.055f).toInt())
        } else {
            0
        }

        val candidates = ocrItems
            .asSequence()
            .map { item ->
                val clean = item.text.replace("\n", " ").replace("\r", " ").trim()
                item to clean
            }
            .filter { (item, clean) ->
                clean.isNotBlank() &&
                    !OcrRecognizer.isSystemTelemetryOcr(clean) &&
                    (statusBarTopCutY <= 0 || item.bounds.centerY() >= statusBarTopCutY)
            }
            .sortedWith(compareBy<Pair<OcrItem, String>>({ it.first.bounds.top }, { -it.second.length }))
            .toList()

        val selected = mutableListOf<Pair<OcrItem, String>>()
        for (candidate in candidates) {
            if (selected.size >= maxItems) break
            val (item, clean) = candidate
            val cx = item.bounds.centerX()
            val cy = item.bounds.centerY()
            val isDuplicateSubToken = selected.any { (keptItem, keptText) ->
                (keptText == clean && kotlin.math.abs(keptItem.bounds.centerY() - cy) < 20) ||
                    (keptItem.bounds.contains(cx, cy) && keptText.contains(clean))
            }
            if (!isDuplicateSubToken) {
                selected.add(candidate)
            }
        }

        return selected.joinToString(" ; ") { (item, clean) ->
            val truncated = if (clean.length > 16) clean.take(15) + "…" else clean
            val cx = item.bounds.centerX()
            val cy = item.bounds.centerY()
            val nx = if (sw > 0) (cx * 100f / sw).toInt() else 0
            val ny = if (sh > 0) (cy * 100f / sh).toInt() else 0
            "\"$truncated\"@${nx}%,${ny}%"
        }
    }

    /**
     * 建立結構化手機畫面與引擎狀態快照 ([狀態Snap])，供 AI 分析逾時、例外、返回遭拒與強制復原原因。
     */
    internal fun buildPhoneStateSnapshot(
        cause: String,
        service: AutoAdAccessibilityService? = AutoAdAccessibilityService.instance.value,
        screenBitmap: android.graphics.Bitmap? = null,
        ocrItems: List<OcrItem> = emptyList()
    ): String {
        val dm = context.resources.displayMetrics
        val sw = when {
            screenBitmap != null && screenBitmap.width > 0 -> screenBitmap.width
            lastCapturedScreenWidth > 0 -> lastCapturedScreenWidth
            else -> dm.widthPixels
        }
        val sh = when {
            screenBitmap != null && screenBitmap.height > 0 -> screenBitmap.height
            lastCapturedScreenHeight > 0 -> lastCapturedScreenHeight
            else -> dm.heightPixels
        }
        val vp = when {
            screenBitmap != null -> ScreenViewportCalibrator.detectViewport(sw, sh, screenBitmap, ocrItems)
            lastViewportMetricsCache != null -> lastViewportMetricsCache!!
            else -> ScreenViewportCalibrator.ViewportMetrics(sw, sh, 0, sh, false, 0, sw)
        }
        val navMode = if (vp.hasThreeButtonNavBar) "三大按鍵" else "手勢/無按鍵"
        val fgPkg = service?.getForegroundPackageName() ?: "unknown"
        val rejectReason = service?.lastBackRejectReason?.ifBlank { "none" } ?: "no_service"
        val ui = _uiStatus.value
        val adFields = adCloserHandler.getDiagnosticsSnapshotFields()
        val lastAdLabel = adCloserHandler.getLastClickedAdLabel() ?: "none"
        return "[狀態Snap] cause=$cause | pkg=$fgPkg | state=${ui.state.name} | round=$currentRound | phase=${currentPhase.name} | tab=${currentTab.name} | screen=${sw}x$sh | nav=$navMode | bounds=[${vp.effectiveLeftX}..${vp.effectiveRightX},${vp.effectiveTopY}..${vp.effectiveBottomY}] | backReject=$rejectReason(累計=$backRejectCount) | adClk=$lastAdLabel | $adFields"
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

        val dm = context.resources.displayMetrics
        val sw = if (lastCapturedScreenWidth > 0) lastCapturedScreenWidth else dm.widthPixels
        val sh = if (lastCapturedScreenHeight > 0) lastCapturedScreenHeight else dm.heightPixels
        val dpi = dm.densityDpi
        val aspectRatioStr = if (sw > 0 && sh > 0) {
            if (sh >= sw) {
                String.format(Locale.US, "%.1f:9", sh * 9.0 / sw)
            } else {
                String.format(Locale.US, "%.2f:1", sw.toDouble() / sh)
            }
        } else {
            "未知"
        }
        val vp = lastViewportMetricsCache ?: ScreenViewportCalibrator.ViewportMetrics(sw, sh, 0, sh, false, 0, sw)
        val navBarMode = if (vp.hasThreeButtonNavBar) "傳統三大按鍵" else "全螢幕手勢"
        val enabledTasksList = config.getEnabledTaskNames()
        val enabledTasksText = if (enabledTasksList.isEmpty()) "無" else enabledTasksList.joinToString("、")
        val claimNormalText = if (config.claimNormalWhenNoAd) "開啟" else "關閉"
        val ui = _uiStatus.value

        val sb = StringBuilder()
        sb.append("========================================\n")
        sb.append("《LINE 散步趣助手》系統診斷日誌\n")
        sb.append("應用版本：$appVersion\n")
        sb.append("裝置型號：$deviceModel\n")
        sb.append("系統版本：$osVersion\n")
        sb.append("螢幕規格：${sw}x${sh} (比例 $aspectRatioStr, ${dpi}dpi), 導覽列=$navBarMode, 有效邊界=[${vp.effectiveLeftX}..${vp.effectiveRightX}, ${vp.effectiveTopY}..${vp.effectiveBottomY}]\n")
        sb.append("核心設定：計時器=${config.maxAdWaitSeconds}s, 掃描間隔=${config.loopIntervalMs}ms, 無廣告一般領取=$claimNormalText, 啟用任務=[$enabledTasksText]\n")
        sb.append("工作階段摘要：輪次=${ui.currentRound}, 觸發=${ui.tasksTriggeredCount}, 廣告完成=${ui.adsCompletedCount}, 逾時強退=${timeoutForceEscapeCount}, 外部跳轉=(Play=${externalRedirectPlayCount}, 瀏覽器=${externalRedirectBrowserCount}, 其他=${externalRedirectOtherCount}), 返回遭拒=${backRejectCount}\n")
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
        const val TAB_STAGNATION_THRESHOLD = 15          // 同一分頁停滯無操作閥值 (15 Tick ~ 7.5s)
        const val NON_MAIN_IDLE_HEARTBEAT_TICKS = 6      // 非主畫面輪詢心跳日誌週期 (6 Tick ~ 3s)
        const val NON_MAIN_IDLE_FALLBACK_TICKS = 6       // 降級主畫面重測門檻 (6 Tick ~ 3s)
        const val NON_MAIN_IDLE_ADVANCE_TICKS = 15       // 非主畫面空轉強制換頁門檻 (15 Tick ~ 7.5s)
        const val NON_MAIN_IDLE_SAFETY_BACK_TICKS = 20   // 未知畫面安全返回門檻 (20 Tick ~ 10s)

        @Volatile
        private var INSTANCE: AutomationEngine? = null

        fun getInstance(context: Context): AutomationEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AutomationEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
