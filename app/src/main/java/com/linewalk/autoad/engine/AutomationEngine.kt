package com.linewalk.autoad.engine

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.config.TemplateCategory
import com.linewalk.autoad.config.TemplateStorage
import com.linewalk.autoad.service.AutoAdAccessibilityService
import com.linewalk.autoad.tasks.GachaTask
import com.linewalk.autoad.tasks.GameTask
import com.linewalk.autoad.tasks.IdleRewardTask
import com.linewalk.autoad.tasks.MoveGaugeTask
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

    private val tasks: List<GameTask> = listOf(
        StepBottleTask(),
        MoveGaugeTask(),
        IdleRewardTask(),
        GachaTask()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loopJob: Job? = null

    private var popupRetryTicks = 0
    private var rewardClaimRetryTicks = 0

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
        transitionTo(
            newState = EngineState.IN_GAME_SCAN,
            detail = "自動巡邏已啟動，正在掃描『獲得票券』與彈窗...",
            safeOverlayText = "🔍 巡邏任務目標中..."
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
            appendLog("🛡️ 偵測到外部套件 ($fgPkg)，自動按返回鍵退回遊戲")
            service.navigateBack()
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

    private suspend fun handleInGameScan(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // A. 最優先檢查：下半部是否已經彈出『紅利 [▶] 15 獲得』等看廣告彈窗按鈕！
        val existingAdBtn = findAdTriggerButton(service, screenBitmap, ocrItems)
        if (existingAdBtn != null) {
            appendLog("🎬 偵測到看廣告按鈕 (${existingAdBtn.first})，立即點擊！")
            service.clickRect(existingAdBtn.second)
            adCloserHandler.onEnterAdMode()
            transitionTo(
                EngineState.AD_WATCHING,
                "已點擊 ${existingAdBtn.first}，進入廣告監控",
                "📺 監控播放中..."
            )
            return
        }

        // B. 判斷目前是否在乾淨的《LINE 散步趣》主畫面（未開彈窗）
        val onMainScreen = ocrRecognizer.isLineWalkMainScreenVisible(screenBitmap, ocrItems, templateMatcher)
        if (onMainScreen) {
            val enabledTasks = tasks.filter { it.isEnabled(config) }
            for (task in enabledTasks) {
                val target = task.findTargetOnScreen(
                    screenBitmap = screenBitmap,
                    ocrItems = ocrItems,
                    config = config,
                    templateStorage = templateStorage,
                    templateMatcher = templateMatcher,
                    ocrRecognizer = ocrRecognizer
                )
                if (target != null && !FloatingOverlayService.isInsideOverlay(target.bounds.centerX(), target.bounds.centerY())) {
                    val newTaskCount = _uiStatus.value.tasksTriggeredCount + 1
                    _uiStatus.value = _uiStatus.value.copy(tasksTriggeredCount = newTaskCount)
                    appendLog("🎯 點擊【${target.taskName}】: ${target.targetLabel}")
                    service.clickRect(target.bounds)
                    popupRetryTicks = 0
                    transitionTo(
                        EngineState.TRIGGER_AD_POPUP,
                        "已點擊 ${target.targetLabel}，等待『紅利 15 獲得』彈窗...",
                        "⏳ 等待彈窗出現..."
                    )
                    return
                }
            }

            updateDetail(
                "🔍 主畫面巡邏中：等待粉紅色『獲得票券』亮起...",
                "🔍 巡邏主畫面目標中..."
            )
            return
        }

        // C. 若不在主畫面（代表有推銷視窗『玩遊戲賺點數 -> 跳過』、領獎完成確認窗或全螢幕廣告）：
        val dismissOrSkipOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.GAME_CLAIM_CONFIRM_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.05f, 0.04f, 0.95f, 0.96f),
            excludeKeywords = OcrRecognizer.GAME_CLAIM_EXCLUDE_KEYWORDS
        )
        if (dismissOrSkipOcr != null) {
            appendLog("✨ 自動點擊彈窗 [${dismissOrSkipOcr.text}]")
            service.clickRect(dismissOrSkipOcr.bounds)
            return
        }

        val closeNode = nodeInspector.findAdCloseNode(service.getSafeRootNode(), sw, sh)
        if (closeNode != null) {
            appendLog("✨ 自動點擊關閉節點: ${closeNode.label}")
            service.clickRect(closeNode.bounds)
            return
        }

        updateDetail(
            "🔍 正在搜尋可領取目標... (已完成 ${_uiStatus.value.adsCompletedCount} 次)",
            "🔍 搜尋畫面目標中..."
        )
    }

    /**
     * 狀態 2：點擊『獲得票券』後，等待並點擊下半部彈出的粉紅色『紅利 [▶] 15 獲得』按鈕。
     */
    private suspend fun handleTriggerAdPopup(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        popupRetryTicks++
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 1. 第一優先尋找彈窗中的粉紅色『紅利 [▶] 15 獲得』或看廣告按鈕
        val adBtn = findAdTriggerButton(service, screenBitmap, ocrItems)
        if (adBtn != null) {
            appendLog("🎬 點擊彈窗看廣告按鈕: ${adBtn.first}")
            service.clickRect(adBtn.second)
            adCloserHandler.onEnterAdMode()
            transitionTo(
                EngineState.AD_WATCHING,
                "廣告開始播放，等待倒數結束...",
                "📺 等待倒數結束..."
            )
            return
        }

        // 2. 若彈窗中沒有粉紅看廣告按鈕（例如今日廣告次數用完，只剩白色『5 獲得』或『確認/跳過』）
        if (popupRetryTicks >= 2 && config.claimNormalWhenNoAd) {
            val normalOrSkip = ocrRecognizer.findMatch(
                items = ocrItems,
                keywords = OcrRecognizer.GAME_CLAIM_CONFIRM_KEYWORDS,
                screenWidth = sw,
                screenHeight = sh,
                roiNormalized = RectF(0.08f, 0.15f, 0.92f, 0.95f),
                excludeKeywords = OcrRecognizer.GAME_CLAIM_EXCLUDE_KEYWORDS
            )
            if (normalOrSkip != null) {
                appendLog("ℹ️ 點擊彈窗一般按鈕: [${normalOrSkip.text}]")
                service.clickRect(normalOrSkip.bounds)
                rewardClaimRetryTicks = 0
                transitionTo(
                    EngineState.REWARD_CLAIM,
                    "已點擊 [${normalOrSkip.text}]，確認狀態中",
                    "✨ 確認完成狀態中..."
                )
                return
            }
        }

        if (popupRetryTicks >= 4) {
            appendLog("↩️ 未偵測到看廣告彈窗，返回主畫面繼續巡邏")
            transitionTo(EngineState.IN_GAME_SCAN, "重新掃描遊戲畫面...", "🔍 巡邏畫面目標中...")
        } else {
            updateDetail("⏳ 等待看廣告彈窗出現 ($popupRetryTicks/4)...", "⏳ 等待彈窗 ($popupRetryTicks/4)...")
        }
    }

    private suspend fun handleAdWatching(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        when (val res = adCloserHandler.processAdTick(service, screenBitmap, ocrItems)) {
            is AdStepResult.RedirectRecovered -> {
                appendLog("🛡️ 誤跳轉外部套件 (${res.externalPkg})，已自動按返回鍵並封鎖假 X 座標！")
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
                val newAdsCount = _uiStatus.value.adsCompletedCount + 1
                _uiStatus.value = _uiStatus.value.copy(adsCompletedCount = newAdsCount)
                appendLog("🎉 成功關閉廣告回到遊戲！(累計完成 $newAdsCount 支)")
                if (res.confirmBounds != null) {
                    service.clickRect(res.confirmBounds)
                }
                rewardClaimRetryTicks = 0
                transitionTo(EngineState.REWARD_CLAIM, "正在確認獎勵彈窗...", "✨ 完成確認中...")
            }

            is AdStepResult.WatchdogBackTriggered -> {
                appendLog("⚠️ 等待逾時 (${res.elapsedSec}s)，觸發防卡死返回/右上角關閉")
                if (res.elapsedSec >= config.maxAdWaitSeconds + 15) {
                    transitionTo(EngineState.IN_GAME_SCAN, "逾時重置回巡邏狀態", "🔍 重新巡邏中...")
                }
            }

            is AdStepResult.ScanningAd -> {
                if (adCloserHandler.getElapsedSeconds() >= 8 &&
                    ocrRecognizer.isLineWalkMainScreenVisible(screenBitmap, ocrItems, templateMatcher)
                ) {
                    val newAdsCount = _uiStatus.value.adsCompletedCount + 1
                    _uiStatus.value = _uiStatus.value.copy(adsCompletedCount = newAdsCount)
                    appendLog("🎉 偵測到已回到散步趣主畫面！(累計完成 $newAdsCount 支)")
                    transitionTo(EngineState.IN_GAME_SCAN, "已回到主畫面，繼續下一輪！", "🔍 繼續下一輪...")
                    return
                }
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

        if (ocrRecognizer.isLineWalkMainScreenVisible(screenBitmap, ocrItems, templateMatcher)) {
            transitionTo(EngineState.IN_GAME_SCAN, "已回到主畫面，繼續點下一張票券！", "🔍 巡邏目標中...")
            return
        }

        val confirmMatch = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.GAME_CLAIM_CONFIRM_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.08f, 0.15f, 0.92f, 0.95f),
            excludeKeywords = OcrRecognizer.GAME_CLAIM_EXCLUDE_KEYWORDS
        )
        if (confirmMatch != null) {
            appendLog("✅ 點擊完成確認按鈕: [${confirmMatch.text}]")
            service.clickRect(confirmMatch.bounds)
            transitionTo(EngineState.IN_GAME_SCAN, "完成！繼續下一輪...", "🔍 巡邏目標中...")
            return
        }

        if (rewardClaimRetryTicks >= 2) {
            transitionTo(EngineState.IN_GAME_SCAN, "繼續下一輪巡邏", "🔍 巡邏目標中...")
        } else {
            updateDetail("✨ 確認畫面狀態中...", "✨ 確認狀態中...")
        }
    }

    /**
     * 尋找《LINE 散步趣》彈窗中的「看廣告」按鈕：
     * 1. 最優先：直接掃描下半部彈窗區 (y = 0.68 ~ 0.83) 的珊瑚粉紅長條按鈕『紅利 [▶] 15 獲得』！
     * 2. 次優先：OCR 辨識『紅利』(點擊其正下方按鈕區) 或『15 獲得 / 看廣告 / 觀看影片』。
     * 3. 備援：使用者自訂框選的『看廣告按鈕』圖示範本。
     */
    private suspend fun findAdTriggerButton(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ): Pair<String, Rect>? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 1. 直接偵測下半部彈窗區 (y = 68% ~ 83%) 的珊瑚粉紅長條按鈕 (即『紅利 [▶] 15 獲得』)
        val pinkPopupBtn = templateMatcher.findCoralPinkButton(
            screenBitmap = screenBitmap,
            yStartRatio = 0.68f,
            yEndRatio = 0.83f,
            label = "紅利粉紅看廣告鈕"
        )
        if (pinkPopupBtn != null) {
            return pinkPopupBtn.label to pinkPopupBtn.bounds
        }

        // 2. 若 OCR 看到黃底『紅利』標籤 (y = 65% ~ 78%)，其正下方就是看廣告按鈕
        val bonusTag = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf("紅利"),
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.05f, 0.64f, 0.50f, 0.78f)
        )
        if (bonusTag != null) {
            val targetY = (bonusTag.bounds.bottom + (sh * 0.032f).toInt()).coerceAtMost((sh * 0.82f).toInt())
            val btnRect = Rect((sw * 0.30f).toInt(), targetY - 20, (sw * 0.70f).toInt(), targetY + 20)
            return "OCR[紅利下方看廣告鈕]" to btnRect
        }

        // 3. ML Kit 中文 OCR 搜尋彈窗區 (y = 0.22 ~ 0.83) 的關鍵字 (排除 y < 0.70 的灰色『跳過廣告 15 獲得』)
        val ocrMatch = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.GAME_AD_TRIGGER_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.08f, 0.71f, 0.92f, 0.83f),
            excludeKeywords = OcrRecognizer.GAME_AD_EXCLUDE_KEYWORDS
        )
        if (ocrMatch != null) {
            return "OCR[${ocrMatch.text}]" to ocrMatch.bounds
        }

        // 4. 使用者框選的「看廣告按鈕圖示」範本 (AD_TRIGGER_BTN)
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
            if (tplMatch != null) {
                return tplMatch.label to tplMatch.bounds
            }
        }

        return null
    }

    private fun transitionTo(newState: EngineState, detail: String, safeOverlayText: String) {
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
