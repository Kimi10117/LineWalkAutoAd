package com.linewalk.autoad.engine

import android.content.Context
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
    /** 專供懸浮窗顯示的簡短狀態（不含『水瓶/領取/跳過』等會被 OCR 誤認的字眼） */
    val overlaySafeDetail: String = "點擊 ▶ 啟動自動巡邏",
    val adsCompletedCount: Int = 0,
    val tasksTriggeredCount: Int = 0,
    val recentLogs: List<String> = emptyList()
)

/**
 * 《LINE 散步趣》自動看廣告核心狀態機引擎 (AutomationEngine)。
 */
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
            detail = "自動巡邏已啟動，正在掃描畫面...",
            safeOverlayText = "🔍 正在巡邏畫面目標..."
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

        // 若任何狀態下誤跳轉到 Google Play 商店或外部瀏覽器，第一時間按返回鍵退回
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

    /**
     * 狀態 1：在《LINE 散步趣》主畫面巡邏：
     * 優先順序：
     * A. 畫面上若已有「看廣告 / 觀看影片」按鈕 -> 直接點開進入廣告！
     * B. 畫面上若有推銷彈窗或廣告殘留的「跳過 / 略過 / 確認 / 關閉」（如『玩遊戲賺點數 -> 跳過』） -> 自動點掉！
     * C. 掃描已勾選任務圖示（優先找滿水瓶）。
     */
    private suspend fun handleInGameScan(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // A. 若畫面上已經有「看廣告 / 觀看影片」彈窗按鈕，第一優先點擊進入廣告！
        val existingAdBtn = findAdTriggerButton(service, screenBitmap, ocrItems)
        if (existingAdBtn != null) {
            appendLog("🎬 偵測到看廣告按鈕 (${existingAdBtn.first})，立即點擊")
            service.clickRect(existingAdBtn.second)
            adCloserHandler.onEnterAdMode()
            transitionTo(
                EngineState.AD_WATCHING,
                "已點擊看廣告，進入倒數監控",
                "📺 監控播放中..."
            )
            return
        }

        // B1. 若畫面沒有「看廣告」按鈕，但出現「跳過 / 略過 / 確認 / 關閉」（包含底部 y=0.90 的『玩遊戲賺點數 -> 跳過』或廣告畫面）
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

        // B2. 檢查無障礙節點樹是否有「跳過 / 略過 / 關閉 / X」節點
        val closeNode = nodeInspector.findAdCloseNode(service.getSafeRootNode(), sw, sh)
        if (closeNode != null) {
            appendLog("✨ 自動點擊關閉節點: ${closeNode.label}")
            service.clickRect(closeNode.bounds)
            return
        }

        // C. 依優先順序執行已勾選的任務（1. 步數水瓶 -> 2. 移動 -> 3. 放置 -> 4. 扭蛋）
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
                appendLog("🎯 觸發【${target.taskName}】: ${target.targetLabel}")
                service.clickRect(target.bounds)
                popupRetryTicks = 0
                transitionTo(
                    EngineState.TRIGGER_AD_POPUP,
                    "已點擊${target.taskName}，等待彈窗...",
                    "⏳ 等待彈窗出現..."
                )
                return
            }
        }

        val bottleTplCount = templateStorage.getTemplates(TemplateCategory.STEP_BOTTLE).size
        if (config.enableStepBottleTask && bottleTplCount == 0) {
            updateDetail(
                "💡 尚未取樣『滿水瓶』圖示！請點懸浮窗 [✂️取樣] 框選一次滿水瓶",
                "💡 請點 [✂️取樣] 框選目標圖示"
            )
        } else {
            updateDetail(
                "🔍 正在巡邏任務目標... (已完成 ${_uiStatus.value.adsCompletedCount} 次)",
                "🔍 巡邏畫面目標中..."
            )
        }
    }

    /**
     * 狀態 2：點擊水瓶或獎勵圖示後，等待並點擊彈出的「觀看廣告領取」按鈕。
     */
    private suspend fun handleTriggerAdPopup(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        popupRetryTicks++
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 1. 第一優先尋找「觀看廣告 / 看影片 / 2倍」按鈕
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

        // 2. 若出現推銷視窗或無廣告時的一般按鈕（包含『跳過 / 略過 / 確認 / 領取』）
        if (popupRetryTicks >= 2 && config.claimNormalWhenNoAd) {
            val normalOrSkip = ocrRecognizer.findMatch(
                items = ocrItems,
                keywords = OcrRecognizer.GAME_CLAIM_CONFIRM_KEYWORDS,
                screenWidth = sw,
                screenHeight = sh,
                roiNormalized = RectF(0.05f, 0.15f, 0.95f, 0.96f),
                excludeKeywords = OcrRecognizer.GAME_CLAIM_EXCLUDE_KEYWORDS
            )
            if (normalOrSkip != null) {
                appendLog("ℹ️ 點擊彈窗按鈕: [${normalOrSkip.text}]")
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
            appendLog("↩️ 未偵測到目標彈窗，返回主畫面繼續巡邏")
            transitionTo(EngineState.IN_GAME_SCAN, "重新掃描遊戲畫面...", "🔍 巡邏畫面目標中...")
        } else {
            updateDetail("⏳ 等待彈窗出現 ($popupRetryTicks/4)...", "⏳ 等待彈窗 ($popupRetryTicks/4)...")
        }
    }

    /**
     * 狀態 3：廣告播放中，交由 AdCloserHandler 處理倒數、多階段 >> 與 X 關閉、以及底部「跳過/略過」。
     */
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
                updateDetail(
                    "🔍 搜尋關閉 X 或略過按鈕 (已播 ${res.elapsedSec}s)...",
                    "🔍 搜尋關閉鈕 (${res.elapsedSec}s)..."
                )
            }
        }
    }

    /**
     * 狀態 4：回到《LINE 散步趣》後，點擊「確認 / 收下 / 跳過」完成本輪循環。
     */
    private suspend fun handleRewardClaim(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        rewardClaimRetryTicks++
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        val confirmMatch = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.GAME_CLAIM_CONFIRM_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.08f, 0.15f, 0.92f, 0.96f),
            excludeKeywords = OcrRecognizer.GAME_CLAIM_EXCLUDE_KEYWORDS
        )
        if (confirmMatch != null) {
            appendLog("✅ 點擊完成確認按鈕: [${confirmMatch.text}]")
            service.clickRect(confirmMatch.bounds)
            transitionTo(EngineState.IN_GAME_SCAN, "完成！繼續巡邏下一個目標...", "🔍 巡邏畫面目標中...")
            return
        }

        if (rewardClaimRetryTicks >= 2) {
            transitionTo(EngineState.IN_GAME_SCAN, "已回到主畫面，繼續下一輪巡邏", "🔍 巡邏畫面目標中...")
        } else {
            updateDetail("✨ 確認畫面狀態中...", "✨ 確認狀態中...")
        }
    }

    private suspend fun findAdTriggerButton(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ): Pair<String, android.graphics.Rect>? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 1. ML Kit 中文 OCR 搜尋「看廣告 / 觀看影片 / 2倍」（排除『不看廣告』）
        val ocrMatch = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.GAME_AD_TRIGGER_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.08f, 0.25f, 0.92f, 0.88f),
            excludeKeywords = OcrRecognizer.GAME_AD_EXCLUDE_KEYWORDS
        )
        if (ocrMatch != null) {
            return "OCR[${ocrMatch.text}]" to ocrMatch.bounds
        }

        // 2. 無障礙節點樹搜尋
        val nodeMatch = nodeInspector.findNodeByKeywords(
            root = service.getSafeRootNode(),
            keywords = OcrRecognizer.GAME_AD_TRIGGER_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            excludeKeywords = OcrRecognizer.GAME_AD_EXCLUDE_KEYWORDS
        )
        if (nodeMatch != null) {
            return nodeMatch.label to nodeMatch.bounds
        }

        // 3. 使用者框選的「看廣告按鈕圖示」範本 (AD_TRIGGER_BTN)
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
