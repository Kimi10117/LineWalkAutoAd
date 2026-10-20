package com.linewalk.autoad.engine

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
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
    val statusDetail: String = "點擊懸浮窗 [▶ 開始] 啟動自動看廣告",
    val overlaySafeDetail: String = "點擊 ▶ 啟動自動巡邏",
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
    RECEIVE_GIFT("接收好友禮物"),
    FRIEND_GIFT("好友送禮"),
    COMPLETED("全任務完成")
}

class AutomationEngine private constructor(context: Context) {

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
    private var currentTab: MainTab = MainTab.MOVE
    private var needsInitialTabSync = true
    private var tabSwitchRetries = 0
    private var emptyTicksOnCurrentTab = 0
    private val confirmedEmptyTabs = mutableSetOf<MainTab>()

    private var currentPhase: PatrolPhase = PatrolPhase.MAIN_TABS
    private var isBingoCompleted = false
    private var isLotteryCompleted = false
    private var isMissionCompleted = false
    private var isReceiveGiftCompleted = false
    private var isFriendGiftCompleted = false
    private val checkedMissionTabs = mutableSetOf<String>()
    private val missionTabSequence = listOf("特別", "每日", "每週")
    private var friendGiftEmptyTicks = 0
    private var currentPhaseEmptyTicks = 0
    private var nonGameScreenTicks = 0

    private val _uiStatus = MutableStateFlow(EngineUiStatus())
    val uiStatus: StateFlow<EngineUiStatus> = _uiStatus.asStateFlow()

    fun start() {
        if (loopJob?.isActive == true) return
        val accService = AutoAdAccessibilityService.instance.value
        if (accService == null) {
            appendLog("⚠️ 無障礙服務尚未啟用，請先至主畫面開啟權限")
            return
        }

        popupRetryTicks = 0
        rewardClaimRetryTicks = 0
        nonGameScreenTicks = 0
        emptyTicksOnCurrentTab = 0
        confirmedEmptyTabs.clear()
        isBingoCompleted = false
        isLotteryCompleted = false
        isMissionCompleted = false
        isReceiveGiftCompleted = false
        isFriendGiftCompleted = false
        checkedMissionTabs.clear()
        friendGiftEmptyTicks = 0
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
            safeOverlayText = "🔍 巡邏[$phaseDesc]..."
        )
        _uiStatus.value = _uiStatus.value.copy(isRunning = true)

        loopJob = scope.launch {
            while (isActive) {
                try {
                    executeSingleTick()
                } catch (e: Exception) {
                    appendLog("❌ 循環發生例外: ${e.message}")
                }
                delay(config.loopIntervalMs)
            }
        }
    }

    fun stop(reason: String = "使用者手動暫停") {
        loopJob?.cancel()
        loopJob = null
        _uiStatus.value = _uiStatus.value.copy(
            isRunning = false,
            state = EngineState.IDLE,
            statusDetail = reason,
            overlaySafeDetail = "⏸ $reason"
        )
        appendLog("⏸ $reason")
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

        val fgPkg = service.getForegroundPackageName()
        if (fgPkg != null && fgPkg in AppConfig.EXTERNAL_REDIRECT_PACKAGES) {
            appendLog("🛡️ 偵測到外部套件 ($fgPkg)，自動按返回鍵退回廣告")
            adCloserHandler.markRecentRedirect()
            service.navigateBack()
            if (_uiStatus.value.state != EngineState.AD_WATCHING) {
                adCloserHandler.onEnterAdMode(preserveRedirectState = true)
                transitionTo(EngineState.AD_WATCHING, "已關閉外部套件，監控廣告中...", "📺 廣告監控中...")
            }
            return
        }

        val currentState = _uiStatus.value.state
        val screenBitmap = service.captureScreenBitmap()
        if (screenBitmap == null) {
            updateDetail("⚠️ 無法擷取螢幕畫面，等待重試...", "⚠️ 等待畫面擷取...")
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
            appendLog("🛡️ 偵測到外部瀏覽器/跳轉網頁 (${fgPkg ?: "InAppBrowser"})，自動按返回鍵關閉並啟動廣告監控")
            adCloserHandler.markRecentRedirect()
            service.navigateBack()
            adCloserHandler.onEnterAdMode(preserveRedirectState = true)
            transitionTo(EngineState.AD_WATCHING, "已關閉外部網頁，監控廣告中...", "📺 廣告監控中...")
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
            appendLog("🛡️ 偵測到『Hide Ad / Report Ad』選單，自動點擊上方背景關閉並繼續監控廣告")
            service.clickRect(backdropRect)
            adCloserHandler.onEnterAdMode()
            transitionTo(
                EngineState.AD_WATCHING,
                "已關閉 Hide Ad 選單，繼續等待廣告結束...",
                "📺 監控廣告中..."
            )
            return
        }

        // 0A. 檢查是否誤入「選單」(設定) 畫面，自動點擊右上角 (X) 或按返回鍵關閉選單退回主畫面！
        val isMenuScreen = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            val cy = item.bounds.centerY().toFloat() / sh
            (cy < 0.12f && (t == "選單" || t.contains("選單"))) ||
            t.contains("應用程式設定") ||
            t.contains("關閉動畫效果") ||
            t.contains("省電模式") ||
            t.contains("位置資訊設定") ||
            t.contains("目前版本")
        }
        if (isMenuScreen) {
            appendLog("⚙️ 偵測到【選單/設定】畫面，自動點擊右上角 (X) 返回主畫面")
            val closeMenuRect = Rect((sw * 0.86f).toInt(), (sh * 0.045f).toInt(), (sw * 0.96f).toInt(), (sh * 0.09f).toInt())
            FloatingOverlayService.clickWithOverlayPassthrough(closeMenuRect) {
                service.clickRect(closeMenuRect)
            }
            delay(350)
            service.navigateBack()
            updateDetail("⚙️ 正在關閉【選單】畫面...", "⚙️ 關閉選單中...")
            return
        }

        // 0. 檢查是否誤入「輕鬆賺」(adjoe) 服務條款或遊戲推薦頁，自動點擊右上角 (X) 或按返回鍵退回遊戲！
        val isAdjoeTermsOrOfferwall = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("想體驗更多遊戲") ||
            t.contains("解鎖更多遊戲") ||
            t.contains("探索最熱門遊戲") ||
            t.contains("開始玩吧") ||
            (t.contains("輕鬆賺") && (t.contains("服務條款") || t.contains("條款與條件") || t.contains("使用條款") || t.contains("隱私權政策")))
        }
        if (isAdjoeTermsOrOfferwall) {
            appendLog("🛡️ 偵測到【輕鬆賺】服務條款/推薦頁，自動點擊右上角 (X) 返回主畫面")
            val closeXRect = Rect((sw * 0.85f).toInt(), (sh * 0.04f).toInt(), (sw * 0.98f).toInt(), (sh * 0.10f).toInt())
            FloatingOverlayService.clickWithOverlayPassthrough(closeXRect) {
                service.clickRect(closeXRect)
            }
            delay(400)
            service.navigateBack()
            updateDetail("🛡️ 正在退出【輕鬆賺】廣告頁面...", "🛡️ 退出廣告頁中...")
            return
        }

        // A. 最優先檢查：是否停留在看完廣告後的『獲得15張票券！ / 獲得金幣！』中央彈窗（下方有白色 [ 關閉 ] 按鈕）！
        val modalCloseBtn = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)
        if (modalCloseBtn != null) {
            emptyTicksOnCurrentTab = 0
            appendLog("✨ 點擊領獎完成彈窗: ${modalCloseBtn.first}")
            FloatingOverlayService.clickWithOverlayPassthrough(modalCloseBtn.second) {
                service.clickRect(modalCloseBtn.second)
            }
            updateDetail(
                "✨ 已點掉領獎完成 [關閉] 按鈕，準備檢查下一項...",
                "✨ 已點關閉，繼續檢查..."
            )
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
            appendLog("🎮 偵測到【玩遊戲賺點數】推廣頁，自動點擊底部 [跳過]！")
            FloatingOverlayService.clickWithOverlayPassthrough(promoSkip) {
                service.clickRect(promoSkip)
            }
            return
        }

        // D. 【最高優先】判斷目前是否在乾淨的《LINE 散步趣》主畫面（涵蓋『首頁(移動)』『時間』『遊戲』『步數』四大分頁）
        // 只要在主畫面，絕不被日常子彈窗誤判劫持，立即交由 handleMainScreenPatrol 進行分頁切換與常規巡邏！
        val onMainScreen = ocrRecognizer.isLineWalkMainScreenVisible(screenBitmap, ocrItems, templateMatcher)
        if (onMainScreen) {
            nonGameScreenTicks = 0
            handleMainScreenPatrol(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // E. 若不在乾淨主畫面，檢查各個日常任務子彈窗或好友分頁：
        // E1. 優先檢查是否正在「每日賓果」彈窗
        if (bingoTask.isBingoDialogOpen(ocrItems, sh)) {
            nonGameScreenTicks = 0
            handleBingoDialog(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // E2. 檢查是否正在「抽抽樂」彈窗
        if (lotteryTask.isLotteryDialogOpen(ocrItems, sh)) {
            nonGameScreenTicks = 0
            handleLotteryDialog(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // E3. 檢查是否正在「任務」彈窗
        if (missionTask.isMissionDialogOpen(ocrItems, sh, screenBitmap)) {
            nonGameScreenTicks = 0
            handleMissionDialog(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // E4. 檢查是否正在「接收禮物」彈窗
        if (receiveGiftTask.isReceiveGiftDialogOpen(ocrItems, sh)) {
            nonGameScreenTicks = 0
            handleReceiveGiftDialog(service, screenBitmap, ocrItems, sw, sh)
            return
        }

        // E5. 檢查是否正在底部導覽列「好友」頁面
        if (friendGiftTask.isFriendsPage(ocrItems, sh)) {
            nonGameScreenTicks = 0
            handleFriendsPage(service, screenBitmap, ocrItems, sw, sh)
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
            appendLog("✨ 自動點擊彈窗 [${dismissOrSkipOcr.text}]")
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
            appendLog("✨ 自動點擊關閉節點: ${closeNode.label}")
            service.clickRect(closeNode.bounds)
            return
        }

        // D3. 幾何角落 X 偵測器（如『邀請好友』右上角 X，或全螢幕廣告左上/右上角 X！）
        val geoX = templateMatcher.detectCornerGeometricX(
            screenBitmap = screenBitmap,
            blacklistedCoord = { x, y ->
                FloatingOverlayService.isInsideOverlay(x, y) ||
                y < sh * 0.035f
            }
        )
        if (geoX != null) {
            nonGameScreenTicks = 0
            appendLog("📺 偵測到全螢幕宣傳頁/廣告角落關閉鈕 (${geoX.label})，切換至廣告監控流程安全關閉！")
            adCloserHandler.onEnterAdMode()
            transitionTo(
                EngineState.AD_WATCHING,
                "偵測到廣告/宣傳視窗 (${geoX.label})，安全等待與關閉中...",
                "📺 廣告監控中..."
            )
            return
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
            (cy > 0.85f && (t.contains("集點") || t.contains("好友") || t.contains("使用") || t.contains("精選")))
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

        val isGameScreen = hasMainScreenMarkers || adCloserHandler.isGameScreenVisible(service, screenBitmap, ocrItems)
        if (hasPlayableOrStoreAdOcr || (!isGameScreen && nonGameScreenTicks >= 2)) {
            nonGameScreenTicks = 0
            appendLog("📺 偵測到全螢幕廣告/可玩式廣告/商店宣傳頁，切換至廣告監控流程安全關閉！")
            adCloserHandler.onEnterAdMode()
            transitionTo(
                EngineState.AD_WATCHING,
                "偵測到廣告/宣傳視窗，安全等待與關閉中...",
                "📺 廣告監控中..."
            )
            return
        }

        if (!isGameScreen) {
            nonGameScreenTicks++
        } else {
            nonGameScreenTicks = 0
        }

        updateDetail(
            "🔍 正在搜尋可領取目標或關閉按鈕... (已完成 ${_uiStatus.value.adsCompletedCount} 次)",
            "🔍 搜尋畫面目標中..."
        )
    }

    private suspend fun handleBingoDialog(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ) {
        // 1. 優先檢查是否有連線獎勵按鈕 (例如 1 BINGO 下方的紅底白字 [▶ Get] 或 [領取])
        val rewardBtn = bingoTask.findConnectionRewardButton(screenBitmap, ocrItems)
        if (rewardBtn != null) {
            currentPhaseEmptyTicks = 0
            appendLog("🎯【每日賓果】點擊領取連線獎勵 [▶ Get]！")
            FloatingOverlayService.clickWithOverlayPassthrough(rewardBtn) {
                service.clickRect(rewardBtn)
            }
            adCloserHandler.onEnterAdMode()
            transitionTo(EngineState.AD_WATCHING, "已點擊賓果連線獎勵 [▶ Get]，觀看廣告中...", "📺 廣告監控中...")
            return
        }

        // 2. 檢查是否有橘色「立即開啟賓果格」按鈕
        val openCellBtn = bingoTask.findOpenCellButton(screenBitmap, ocrItems, templateMatcher)
        if (openCellBtn != null) {
            currentPhaseEmptyTicks = 0
            appendLog("🎯【每日賓果】點擊「立即開啟賓果格」！")
            FloatingOverlayService.clickWithOverlayPassthrough(openCellBtn) {
                service.clickRect(openCellBtn)
            }
            adCloserHandler.onEnterAdMode()
            transitionTo(EngineState.AD_WATCHING, "已點擊「立即開啟賓果格」，觀看廣告開格中...", "📺 廣告開格中...")
            return
        }

        // 3. 連續 2 次未偵測到可執行項目 (冷卻中或無連線獎勵) -> 關閉賓果彈窗並前往下一階段
        currentPhaseEmptyTicks++
        if (currentPhaseEmptyTicks < 2) {
            updateDetail("🔍 檢查每日賓果可開格/連線獎勵中 ($currentPhaseEmptyTicks/2)...", "🔍 檢查賓果中...")
            return
        }

        currentPhaseEmptyTicks = 0
        isBingoCompleted = true
        appendLog("✅【每日賓果】檢查完畢，點擊右上角關閉視窗")
        val closeBtn = bingoTask.getCloseButtonBounds(sw, sh)
        FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
            service.clickRect(closeBtn)
        }
        currentPhase = getNextPhase(PatrolPhase.BINGO)
        updateDetail("✅ 每日賓果已完成，準備進入下一項...", "✅ 賓果完成")
    }

    private suspend fun handleLotteryDialog(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ) {
        // 依使用者規則尋找：【優先50抽】➔【次選免費抽】➔【嚴禁點5張】
        val drawTarget = lotteryTask.findTargetDrawButton(screenBitmap, ocrItems, templateMatcher)
        if (drawTarget != null) {
            currentPhaseEmptyTicks = 0
            appendLog("🎰【抽抽樂】依規則點擊：${drawTarget.first}")
            FloatingOverlayService.clickWithOverlayPassthrough(drawTarget.second) {
                service.clickRect(drawTarget.second)
            }
            if (drawTarget.first.contains("免費")) {
                adCloserHandler.onEnterAdMode()
                transitionTo(EngineState.AD_WATCHING, "已點擊 ${drawTarget.first}，觀看廣告抽獎...", "🎰 廣告抽獎中...")
            } else {
                delay(600)
                popupRetryTicks = 0
                transitionTo(EngineState.TRIGGER_AD_POPUP, "已點擊 ${drawTarget.first}，等待抽獎結果彈窗...", "🎰 抽獎中...")
            }
            return
        }

        // 兩次檢查確認 50 抽與免費抽皆不可點 (50 抽已無次數/票券不足，且免費抽已完成，且嚴格不點 5 抽)
        currentPhaseEmptyTicks++
        if (currentPhaseEmptyTicks < 2) {
            updateDetail("🔍 檢查抽抽樂可抽項目 ($currentPhaseEmptyTicks/2)...", "🎰 檢查抽抽樂...")
            return
        }

        currentPhaseEmptyTicks = 0
        isLotteryCompleted = true
        appendLog("✅【抽抽樂】已無可抽項目 (50抽已滿或票券不足，免費抽已完成，且絕不點5張)，關閉視窗")
        val closeBtn = lotteryTask.getCloseButtonBounds(sw, sh)
        FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
            service.clickRect(closeBtn)
        }
        currentPhase = getNextPhase(PatrolPhase.LOTTERY)
        updateDetail("✅ 抽抽樂已完成，準備進入下一項...", "✅ 抽抽樂完成")
    }

    private suspend fun handleMissionDialog(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ) {
        val activeTab = missionTask.detectCurrentActiveTab(screenBitmap)
        appendLog("📋【任務視窗】當前處於【$activeTab】分頁 (已檢查: ${checkedMissionTabs.joinToString()})")

        // 1. 尋找清單中綠色「領取」按鈕 (嚴格排除「挑戰」與「已領取」)
        val claimBtn = missionTask.findClaimableMissionButton(screenBitmap, ocrItems)
        if (claimBtn != null) {
            currentPhaseEmptyTicks = 0
            appendLog("🎯【任務-$activeTab】點擊綠色「領取」按鈕！")
            FloatingOverlayService.clickWithOverlayPassthrough(claimBtn) {
                service.clickRect(claimBtn)
            }
            delay(500)
            popupRetryTicks = 0
            // 領取後可能觸發看廣告或獎勵彈窗，切入 TRIGGER_AD_POPUP 流程安全等候
            transitionTo(
                EngineState.TRIGGER_AD_POPUP,
                "【任務-$activeTab】已點擊領取，等待獎勵或廣告載入中...",
                "🎯 領取獎勵中..."
            )
            return
        }

        // 2. 當前分頁已無可領取項目，將當前分頁加入已檢查清單
        checkedMissionTabs.add(activeTab)

        // 3. 尋找下一個尚未檢查的分頁 (特別 ➔ 每日 ➔ 每週)
        val nextTab = missionTabSequence.firstOrNull { it !in checkedMissionTabs }
        if (nextTab != null) {
            appendLog("🔄【任務】$activeTab 分頁已無可領取項目，切換至【$nextTab】分頁檢查...")
            val tabRect = missionTask.getTabRect(nextTab, sw, sh, ocrItems)
            FloatingOverlayService.clickWithOverlayPassthrough(tabRect) {
                service.clickRect(tabRect)
            }
            updateDetail("🔄 切換至【$nextTab】任務分頁...", "🔄 切換分頁...")
            return
        }

        // 4. 所有分頁 (特別、每日、每週) 皆已檢查完畢，關閉視窗
        isMissionCompleted = true
        appendLog("✅【任務】所有分頁（特別、每日、每週）皆已檢查領取完畢，關閉視窗")
        val closeBtn = missionTask.getCloseButtonBounds(sw, sh)
        FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
            service.clickRect(closeBtn)
        }
        currentPhase = getNextPhase(PatrolPhase.MISSION)
        updateDetail("✅ 任務已檢查完畢，準備進入下一項...", "✅ 任務完成")
    }

    private suspend fun handleReceiveGiftDialog(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ) {
        val claimBtn = receiveGiftTask.findNextClaimGiftButton(screenBitmap, ocrItems)
        if (claimBtn != null) {
            currentPhaseEmptyTicks = 0
            appendLog("🎁【接收禮物】點擊領取好友送來的禮物 (綠色播放鈕)！")
            FloatingOverlayService.clickWithOverlayPassthrough(claimBtn) {
                service.clickRect(claimBtn)
            }
            delay(500)
            popupRetryTicks = 0
            transitionTo(EngineState.TRIGGER_AD_POPUP, "已點擊領取禮物，等待廣告或確認視窗...", "🎁 領取禮物中...")
            return
        }

        currentPhaseEmptyTicks++
        if (currentPhaseEmptyTicks < 2) {
            updateDetail("🔍 檢查是否有好友送來的禮物 ($currentPhaseEmptyTicks/2)...", "🔍 檢查禮物中...")
            return
        }

        currentPhaseEmptyTicks = 0
        isReceiveGiftCompleted = true
        appendLog("✅【接收禮物】檢查完畢，點擊右上角 (X) 關閉視窗")
        val closeBtn = receiveGiftTask.getCloseButtonBounds(sw, sh)
        FloatingOverlayService.clickWithOverlayPassthrough(closeBtn) {
            service.clickRect(closeBtn)
        }
        currentPhase = getNextPhase(PatrolPhase.RECEIVE_GIFT)
        updateDetail("✅ 好友禮物已接收完畢，準備進入下一項...", "✅ 收禮完成")
    }

    private suspend fun handleFriendsPage(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ) {
        // 1. 最優先檢查：是否跳出送禮確認彈窗（使用者指定：【都以送禮物也送自己為主】！）
        val selfGiftOption = friendGiftTask.findSelfGiftButton(screenBitmap, ocrItems)
        if (selfGiftOption != null) {
            appendLog("🎁【好友送禮】點擊【${selfGiftOption.first}】(優先送禮物也送自己)！")
            FloatingOverlayService.clickWithOverlayPassthrough(selfGiftOption.second) {
                service.clickRect(selfGiftOption.second)
            }
            if (selfGiftOption.first.contains("看廣告") || selfGiftOption.first.contains("送自己")) {
                adCloserHandler.onEnterAdMode()
                transitionTo(EngineState.AD_WATCHING, "送禮物也送自己，觀看廣告中...", "📺 廣告監控中...")
            }
            return
        }

        // 2. 搜尋畫面上是否有未贈送的綠色「禮物」按鈕
        val nextGiftBtn = friendGiftTask.findNextGiftButton(screenBitmap, ocrItems)
        if (nextGiftBtn != null) {
            friendGiftEmptyTicks = 0
            friendGiftTask.markGifted(android.graphics.Point(nextGiftBtn.centerX(), nextGiftBtn.centerY()))
            appendLog("🎁【好友送禮】點擊好友排行榜禮物按鈕！")
            FloatingOverlayService.clickWithOverlayPassthrough(nextGiftBtn) {
                service.clickRect(nextGiftBtn)
            }
            return
        }

        // 3. 畫面上沒有禮物按鈕了，向下滾動清單搜尋更多好友！
        friendGiftEmptyTicks++
        if (friendGiftEmptyTicks <= 2) {
            appendLog("📜【好友送禮】向下滾動清單搜尋更多好友 ($friendGiftEmptyTicks/2)...")
            service.performSwipe(
                startX = sw * 0.50f,
                startY = sh * 0.70f,
                endX = sw * 0.50f,
                endY = sh * 0.35f,
                durationMs = 380L
            )
            return
        }

        // 4. 連續兩次滾動皆無新禮物按鈕 -> 代表所有好友皆已贈送完畢！
        isFriendGiftCompleted = true
        appendLog("🎉【好友送禮】所有好友禮物皆已贈送完畢！自動點擊底部「集點」返回首頁")
        val mainTabBtn = friendGiftTask.getBottomMainTabRect(sw, sh)
        FloatingOverlayService.clickWithOverlayPassthrough(mainTabBtn) {
            service.clickRect(mainTabBtn)
        }
        currentPhase = getNextPhase(PatrolPhase.FRIEND_GIFT)
        updateDetail("🎉 好友送禮全部完成，返回首頁中...", "🎉 好友送禮完成")
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
                    appendLog("🎉 四大分頁 ($tabListStr) 常規獎勵皆已領取完畢！(共完成 ${_uiStatus.value.adsCompletedCount} 次)")
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
                    appendLog("🔄 依優先順序切換至【${desiredTab.tabName}】(目前在【${currentTab.tabName}】，嘗試:$tabSwitchRetries/3)...")
                    FloatingOverlayService.clickWithOverlayPassthrough(tabRect) {
                        service.clickRect(tabRect)
                    }
                    updateDetail(
                        "🔄 正在切換至【${desiredTab.tabName}】($tabSwitchRetries/3)...",
                        "🔄 切換至[${desiredTab.shortLabel}]..."
                    )
                    if (tabSwitchRetries >= 4) {
                        appendLog("⚠️ 切換至【${desiredTab.tabName}】逾時，強制同步分頁狀態並開始檢查")
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
                    appendLog("🎯【${currentTab.tabName}】點擊: ${target.targetLabel}")
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
                if (emptyTicksOnCurrentTab < 4) {
                    updateDetail(
                        "🔍 正在檢查【${currentTab.tabName}】是否有可領取獎勵 ($emptyTicksOnCurrentTab/4)...",
                        "🔍 檢查[${currentTab.shortLabel}] ($emptyTicksOnCurrentTab/4)..."
                    )
                    return
                }

                // 連續 4 次確認本分頁已無可領取項目
                emptyTicksOnCurrentTab = 0
                confirmedEmptyTabs.add(currentTab)

                val nextRemainingTabs = enabledTabs.filter { it !in confirmedEmptyTabs }
                if (nextRemainingTabs.isEmpty()) {
                    val tabListStr = enabledTabs.joinToString("➔") { it.shortLabel }
                    appendLog("🎉 四大分頁 ($tabListStr) 常規獎勵皆已領取完畢！")
                    currentPhase = getNextPhase(PatrolPhase.MAIN_TABS)
                    return
                }

                val nextTab = nextRemainingTabs.first()
                val remainOrderStr = nextRemainingTabs.joinToString("➔") { it.shortLabel }
                val tabRect = getTabRect(nextTab, sw, sh, ocrItems)
                appendLog("🔄【${currentTab.tabName}】已領完，依序切換至【${nextTab.tabName}】(待檢查:$remainOrderStr)")
                needsInitialTabSync = true
                tabSwitchRetries = 0
                FloatingOverlayService.clickWithOverlayPassthrough(tabRect) {
                    service.clickRect(tabRect)
                }
                updateDetail(
                    "🔄 正在切換至【${nextTab.tabName}】...",
                    "🔄 切換至[${nextTab.shortLabel}]檢查中..."
                )
                delay(600)
                return
            }

            PatrolPhase.BINGO -> {
                if (!config.enableBingoTask || isBingoCompleted) {
                    currentPhase = getNextPhase(PatrolPhase.BINGO)
                    return
                }
                appendLog("🎯 點擊主畫面【每日賓果】圖示按鈕...")
                val iconRect = bingoTask.getMainScreenBingoIconRect(sw, sh, ocrItems)
                FloatingOverlayService.clickWithOverlayPassthrough(iconRect) {
                    service.clickRect(iconRect)
                }
                updateDetail("🎯 開啟【每日賓果】視窗中...", "🎯 開啟每日賓果...")
            }

            PatrolPhase.LOTTERY -> {
                if (!config.enableLotteryTask || isLotteryCompleted) {
                    currentPhase = getNextPhase(PatrolPhase.LOTTERY)
                    return
                }
                appendLog("🎰 點擊主畫面【抽抽樂】圖示按鈕...")
                val iconRect = lotteryTask.getMainScreenLotteryIconRect(sw, sh, ocrItems)
                FloatingOverlayService.clickWithOverlayPassthrough(iconRect) {
                    service.clickRect(iconRect)
                }
                updateDetail("🎰 開啟【抽抽樂】視窗中...", "🎰 開啟抽抽樂...")
            }

            PatrolPhase.MISSION -> {
                if (!config.enableDailyMissionTask || isMissionCompleted) {
                    currentPhase = getNextPhase(PatrolPhase.MISSION)
                    return
                }
                appendLog("🎯 點擊主畫面【任務】圖示按鈕...")
                val iconRect = missionTask.getMainScreenMissionIconRect(sw, sh, ocrItems)
                FloatingOverlayService.clickWithOverlayPassthrough(iconRect) {
                    service.clickRect(iconRect)
                }
                updateDetail("🎯 開啟【任務】視窗中...", "🎯 開啟任務...")
            }

            PatrolPhase.RECEIVE_GIFT -> {
                if (!config.enableFriendGiftTask || isReceiveGiftCompleted) {
                    currentPhase = getNextPhase(PatrolPhase.RECEIVE_GIFT)
                    return
                }
                appendLog("🎁 點擊主畫面右上角【禮物盒】圖示按鈕...")
                val giftBoxRect = receiveGiftTask.getHeaderGiftBoxRect(sw, sh)
                FloatingOverlayService.clickWithOverlayPassthrough(giftBoxRect) {
                    service.clickRect(giftBoxRect)
                }
                updateDetail("🎁 開啟【禮物】視窗接收禮物中...", "🎁 接收禮物中...")
            }

            PatrolPhase.FRIEND_GIFT -> {
                if (!config.enableFriendGiftTask || isFriendGiftCompleted) {
                    currentPhase = getNextPhase(PatrolPhase.FRIEND_GIFT)
                    return
                }
                appendLog("🎁 點擊底部導覽列【好友】按鈕...")
                val friendsTab = friendGiftTask.getBottomFriendsTabRect(sw, sh)
                FloatingOverlayService.clickWithOverlayPassthrough(friendsTab) {
                    service.clickRect(friendsTab)
                }
                updateDetail("🎁 進入【好友排行榜】送禮中...", "🎁 進入好友...")
            }

            PatrolPhase.COMPLETED -> {
                stop("🎉 全日任務與巡邏獎勵皆已領取完畢！（四大分頁/每日賓果/抽抽樂/任務/收禮/送禮）")
            }
        }
    }

    private fun getNextPhase(after: PatrolPhase): PatrolPhase {
        val phaseList = listOf(
            PatrolPhase.MAIN_TABS,
            PatrolPhase.BINGO,
            PatrolPhase.LOTTERY,
            PatrolPhase.MISSION,
            PatrolPhase.RECEIVE_GIFT,
            PatrolPhase.FRIEND_GIFT
        )
        val startIdx = phaseList.indexOf(after) + 1
        for (i in startIdx until phaseList.size) {
            val p = phaseList[i]
            val isEnabled = when (p) {
                PatrolPhase.MAIN_TABS -> tabPriorityOrder.any { it.isEnabled(config) }
                PatrolPhase.BINGO -> config.enableBingoTask && !isBingoCompleted
                PatrolPhase.LOTTERY -> config.enableLotteryTask && !isLotteryCompleted
                PatrolPhase.MISSION -> config.enableDailyMissionTask && !isMissionCompleted
                PatrolPhase.RECEIVE_GIFT -> config.enableFriendGiftTask && !isReceiveGiftCompleted
                PatrolPhase.FRIEND_GIFT -> config.enableFriendGiftTask && !isFriendGiftCompleted
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
            appendLog("🎉 四大分頁 ($tabListStr) 常規獎勵皆已檢查完畢！準備檢查日常任務...")
            currentPhase = getNextPhase(PatrolPhase.MAIN_TABS)
        } else {
            val nextTab = nextRemainingTabs.first()
            val remainOrderStr = nextRemainingTabs.joinToString("➔") { it.shortLabel }
            appendLog("🔄【${currentTab.tabName}】冷卻中，依序切換至【${nextTab.tabName}】(待檢查: $remainOrderStr)")
            needsInitialTabSync = true
            tabSwitchRetries = 0
        }

        transitionTo(EngineState.IN_GAME_SCAN, "已點擊 [取消]，切換至下一分頁/任務...", "🔍 切換巡邏中...")
    }

    private suspend fun handleTriggerAdPopup(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        popupRetryTicks++
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        val popupAction = findPopupActionTarget(screenBitmap, ocrItems)
        if (popupAction != null) {
            executePopupAction(service, popupAction)
            return
        }

        // 若彈出「玩遊戲賺點數」推廣宣傳頁，自動點擊底部 [跳過]
        val promoSkip = findPlayGamePromoSkipButton(screenBitmap, ocrItems, sw, sh)
        if (promoSkip != null) {
            appendLog("🎮 偵測到【玩遊戲賺點數】推廣頁，自動點擊底部 [跳過]！")
            FloatingOverlayService.clickWithOverlayPassthrough(promoSkip) {
                service.clickRect(promoSkip)
            }
            transitionTo(EngineState.IN_GAME_SCAN, "已跳過推廣頁，繼續巡邏...", "🔍 巡邏畫面目標中...")
            return
        }

        // 若彈窗按鈕皆在冷卻中且出現 [取消]，且至少已嘗試 1 次 (確保非畫面剛彈出時的載入延遲)
        if (popupRetryTicks >= 2) {
            val cancelBtn = ocrRecognizer.findPopupCancelButton(screenBitmap, ocrItems, templateMatcher)
            if (cancelBtn != null) {
                handlePopupCooldownCancel(service, cancelBtn, sw, sh)
                return
            }
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
                y < sh * 0.035f
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

        val fgPkgDirect = service.getForegroundPackageName()
        val isExternalDirect = fgPkgDirect != null && AppConfig.isRedirectPackage(fgPkgDirect)
        val hasBrowserTitleDirect = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            cy < sh * 0.16f && (item.text.contains("正在載入") || item.text.contains(".com") || item.text.contains("http"))
        }
        val isGameScreen = adCloserHandler.isGameScreenVisible(service, screenBitmap, ocrItems)
        // 嚴格安全防護：若畫面仍在主畫面或領獎下半部彈窗，絕不誤判為全螢幕廣告！
        if (!onMainScreenDirect && !isStillInSubDialog && !isBottomRewardPopup && (directGeoX != null || isExternalDirect || hasBrowserTitleDirect || (!isGameScreen && popupRetryTicks >= 2))) {
            appendLog("📺 點擊目標後已直接進入全螢幕廣告/宣傳頁，啟動廣告監控流程！")
            adCloserHandler.onEnterAdMode()
            transitionTo(EngineState.AD_WATCHING, "已進入廣告畫面，安全監控中...", "📺 廣告監控中...")
            return
        }

        if (isStillInSubDialog && popupRetryTicks >= 2) {
            transitionTo(EngineState.IN_GAME_SCAN, "已領取獎勵，繼續彈窗內部巡邏...", "🔍 檢查彈窗中...")
            return
        }

        if (onMainScreenDirect && popupRetryTicks >= 3) {
            transitionTo(EngineState.IN_GAME_SCAN, "已回到主畫面，繼續常規巡邏...", "🔍 巡邏畫面目標中...")
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
                    "✨ 確認完成狀態中..."
                )
                return
            }
        }

        if (popupRetryTicks >= 3) {
            appendLog("↩️ 未偵測到看廣告彈窗，返回主畫面繼續巡邏")
            transitionTo(EngineState.IN_GAME_SCAN, "重新掃描遊戲畫面...", "🔍 巡邏畫面目標中...")
        } else {
            updateDetail("⏳ 等待看廣告彈窗出現 ($popupRetryTicks/3)...", "⏳ 等待彈窗 ($popupRetryTicks/3)...")
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
                appendLog("🍬 偵測到可用糖果！點擊 [${action.label}] 直接跳過廣告！(累計 $newCount 次)")
                FloatingOverlayService.clickWithOverlayPassthrough(action.bounds) {
                    service.clickRect(action.bounds)
                }
                rewardClaimRetryTicks = 0
                transitionTo(
                    EngineState.REWARD_CLAIM,
                    "已使用糖果跳過廣告，確認領取狀態中...",
                    "🍬 糖果跳過確認中..."
                )
            }

            is PopupActionTarget.WatchAd -> {
                appendLog("🎬 點擊彈窗看廣告按鈕: ${action.label}")
                FloatingOverlayService.clickWithOverlayPassthrough(action.bounds) {
                    service.clickRect(action.bounds)
                }
                adCloserHandler.onEnterAdMode()
                transitionTo(
                    EngineState.AD_WATCHING,
                    "已點擊 ${action.label}，等待廣告播放與關閉...",
                    "📺 監控廣告中..."
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
            appendLog("🎮 廣告中偵測到【玩遊戲賺點數】推廣頁，自動點擊底部 [跳過]！")
            FloatingOverlayService.clickWithOverlayPassthrough(promoSkip) {
                service.clickRect(promoSkip)
            }
            transitionTo(EngineState.IN_GAME_SCAN, "已跳過推廣頁，繼續巡邏...", "🔍 巡邏畫面目標中...")
            return
        }

        when (val res = adCloserHandler.processAdTick(service, screenBitmap, ocrItems)) {
            is AdStepResult.RedirectRecovered -> {
                appendLog("🛡️ 誤跳轉外部套件/網頁 (${res.externalPkg})，已自動按返回鍵關閉並繼續監控廣告")
                updateDetail(
                    "🛡️ 已關閉外部網頁 (${res.externalPkg})，繼續監控廣告...",
                    "🛡️ 關閉網頁，繼續監控..."
                )
            }

            is AdStepResult.AdChoicesDismissed -> {
                appendLog("🛡️ 偵測到誤觸『Hide Ad / Report Ad』選單，已自動關閉選單並封鎖該座標！")
                updateDetail(
                    "🛡️ 已關閉 Hide Ad 選單並封鎖誤觸座標，繼續播放廣告...",
                    "🛡️ 已關閉選單，繼續監控..."
                )
            }

            is AdStepResult.WaitingGracePeriod -> {
                updateDetail("📺 廣告播放保護期 (${res.elapsedSec}s)...", "📺 播放中 (${res.elapsedSec}s)...")
            }

            is AdStepResult.StillCountingDown -> {
                updateDetail(
                    "⏱️ 廣告倒數中：剩餘約 ${res.remainingSec} 秒 (已播 ${res.elapsedSec}s)",
                    "⏱️ 倒數約 ${res.remainingSec}s (已播 ${res.elapsedSec}s)"
                )
            }

            is AdStepResult.ClickedCloseOrSkip -> {
                appendLog("👆 點擊關閉/略過按鈕: ${res.sourceLabel}")
                updateDetail(
                    "👆 已點擊 ${res.sourceLabel}，確認是否回到遊戲...",
                    "👆 已點關閉，確認中..."
                )
            }

            is AdStepResult.ReturnedToGame -> {
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
                    appendLog("🎉 廣告已關閉，自動點擊領獎彈窗 [關閉]！(累計完成 $newAdsCount 次)")
                    FloatingOverlayService.clickWithOverlayPassthrough(res.confirmBounds) {
                        service.clickRect(res.confirmBounds)
                    }
                } else {
                    appendLog("🎉 成功關閉廣告回到散步趣主畫面！(累計完成 $newAdsCount 次)")
                }
                transitionTo(EngineState.IN_GAME_SCAN, "完成一輪！繼續檢查本頁與其他分頁獎勵...", "🔍 繼續下一輪...")
            }

            is AdStepResult.WatchdogBackTriggered -> {
                appendLog("🛡️ 廣告等待 (${res.elapsedSec}s)：嘗試發送 ${res.actionLabel} 關閉廣告")
                updateDetail(
                    "🛡️ 嘗試 ${res.actionLabel} 關閉廣告 (${res.elapsedSec}s)...",
                    "🛡️ 嘗試關閉 (${res.elapsedSec}s)..."
                )
                if (res.elapsedSec >= config.maxAdWaitSeconds + 15) {
                    transitionTo(EngineState.IN_GAME_SCAN, "逾時重置回巡邏狀態", "🔍 重新巡邏中...")
                }
            }

            is AdStepResult.ScanningAd -> {
                updateDetail(
                    "🔍 搜尋關閉 X 或略過按鈕 (已播 ${res.elapsedSec}s)...",
                    "🔍 搜尋關閉鈕 (${res.elapsedSec}s)..."
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

        val modalClose = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)
        if (modalClose != null) {
            appendLog("✅ 點擊領獎完成彈窗: ${modalClose.first}")
            FloatingOverlayService.clickWithOverlayPassthrough(modalClose.second) {
                service.clickRect(modalClose.second)
            }
            emptyTicksOnCurrentTab = 0
            transitionTo(EngineState.IN_GAME_SCAN, "已關閉領獎彈窗，繼續下一輪！", "🔍 巡邏目標中...")
            return
        }

        if (ocrRecognizer.isLineWalkMainScreenVisible(screenBitmap, ocrItems, templateMatcher)) {
            emptyTicksOnCurrentTab = 0
            transitionTo(EngineState.IN_GAME_SCAN, "已回到主畫面，繼續檢查下一項！", "🔍 巡邏目標中...")
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
            appendLog("✅ 點擊完成確認按鈕: [${confirmMatch.text}]")
            FloatingOverlayService.clickWithOverlayPassthrough(confirmMatch.bounds) {
                service.clickRect(confirmMatch.bounds)
            }
            transitionTo(EngineState.IN_GAME_SCAN, "完成！繼續下一輪...", "🔍 巡邏目標中...")
            return
        }

        if (rewardClaimRetryTicks >= 2) {
            transitionTo(EngineState.IN_GAME_SCAN, "繼續下一輪巡邏", "🔍 巡邏目標中...")
        } else {
            updateDetail("✨ 確認畫面狀態中...", "✨ 確認狀態中...")
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
            (cy > 0.85f && (t.contains("集點") || t.contains("好友") || t.contains("使用") || t.contains("精選")))
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
            !ocrRecognizer.isAreaInCooldown(ocrItems, skipAdCandyOcr.bounds.top - 20, skipAdCandyOcr.bounds.bottom + 20) &&
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

        // 1. 搜尋彈窗區 (y = 65% ~ 90%) 中所有符合關鍵字的 OCR 項目，解析其金幣數值
        val roiTop = (sh * 0.65f).toInt()
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
                    !ocrRecognizer.isAreaInCooldown(ocrItems, cy - 20, cy + 20) &&
                    !templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy)
                ) {
                    val coins = parseRewardCoins(t)
                    candidates.add(PopupActionTarget.WatchAd("OCR[${t}](${coins}幣)", item.bounds) to coins)
                }
            }
        }

        // 2. 偵測下半部彈窗區 (y = 66% ~ 88%) 的整條珊瑚粉紅按鈕 (如時間放置蘋果的『▶ 8 獲得』、步數/首頁的『紅利 [▶] 15/60 獲得』)
        val pinkPopupBtn = templateMatcher.findCoralPinkButton(
            screenBitmap = screenBitmap,
            yStartRatio = 0.66f,
            yEndRatio = 0.88f,
            label = "紅利粉紅看廣告鈕"
        )
        if (pinkPopupBtn != null &&
            !ocrRecognizer.isAreaInCooldown(ocrItems, pinkPopupBtn.bounds.top - 20, pinkPopupBtn.bounds.bottom + 20) &&
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
            !ocrRecognizer.isAreaInCooldown(ocrItems, badgePopupBtn.bounds.top - 20, badgePopupBtn.bounds.bottom + 20) &&
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
            if (!ocrRecognizer.isAreaInCooldown(ocrItems, targetY - 25, targetY + 25) &&
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
                !ocrRecognizer.isAreaInCooldown(ocrItems, tplMatch.bounds.top - 20, tplMatch.bounds.bottom + 20) &&
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

    fun appendLog(message: String) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$timeStr] $message"
        val updated = (_uiStatus.value.recentLogs + line).takeLast(40)
        _uiStatus.value = _uiStatus.value.copy(recentLogs = updated)
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
