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

enum class EngineState(val title: String) {
    IDLE("已暫停待命"),
    IN_GAME_SCAN("1. 掃描遊戲任務中"),
    TRIGGER_AD_POPUP("2. 尋找看廣告按鈕"),
    AD_WATCHING("3. 觀看廣告與關閉偵測"),
    REWARD_CLAIM("4. 確認領取獎勵")
}

data class EngineUiStatus(
    val isRunning: Boolean = false,
    val state: EngineState = EngineState.IDLE,
    val statusDetail: String = "點擊懸浮窗 [▶ 開始] 啟動自動看廣告",
    val adsCompletedCount: Int = 0,
    val tasksTriggeredCount: Int = 0,
    val recentLogs: List<String> = emptyList()
)

/**
 * 《LINE 散步趣》自動看廣告核心狀態機引擎 (AutomationEngine)。
 * 整合：
 * - 優先任務：步數獎勵（水瓶）及可選的移動、放置、扭蛋任務
 * - 三層混合辨識（UI 節點樹 + ML Kit 離線中文 OCR + 局部圖像模板匹配）
 * - 多階段廣告倒數與防迷路保護
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

    /** 註冊所有任務模組，將「步數水瓶 (StepBottleTask)」排在第一優先 */
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
        transitionTo(EngineState.IN_GAME_SCAN, "自動看廣告已啟動，正在掃描畫面...")
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
            statusDetail = reason
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

        val currentState = _uiStatus.value.state
        val screenBitmap = service.captureScreenBitmap()
        if (screenBitmap == null) {
            updateDetail("⚠️ 無法擷取螢幕畫面，等待下一次重試...")
            return
        }

        try {
            val ocrItems = ocrRecognizer.recognize(screenBitmap)

            when (currentState) {
                EngineState.IDLE -> Unit

                EngineState.IN_GAME_SCAN -> {
                    handleInGameScan(service, screenBitmap, ocrItems)
                }

                EngineState.TRIGGER_AD_POPUP -> {
                    handleTriggerAdPopup(service, screenBitmap, ocrItems)
                }

                EngineState.AD_WATCHING -> {
                    handleAdWatching(service, screenBitmap, ocrItems)
                }

                EngineState.REWARD_CLAIM -> {
                    handleRewardClaim(service, screenBitmap, ocrItems)
                }
            }
        } finally {
            screenBitmap.recycle()
        }
    }

    /**
     * 狀態 1：在《LINE 散步趣》主畫面掃描任務目標（優先掃描滿水瓶，或是畫面上已經打開的看廣告彈窗）。
     */
    private suspend fun handleInGameScan(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // A. 若畫面上已經有「看廣告 / 觀看影片」彈窗按鈕，直接點擊進入廣告！
        val existingAdBtn = findAdTriggerButton(service, screenBitmap, ocrItems)
        if (existingAdBtn != null) {
            appendLog("🎬 偵測到看廣告按鈕 (${existingAdBtn.first})，立即點擊")
            service.clickRect(existingAdBtn.second)
            adCloserHandler.onEnterAdMode()
            transitionTo(EngineState.AD_WATCHING, "已點擊看廣告，進入廣告倒數監控")
            return
        }

        // B. 若畫面上有尚未關閉的「確認 / 獲得」獎勵彈窗，先順手點掉
        val leftoverConfirm = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.GAME_CLAIM_CONFIRM_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.20f, 0.40f, 0.80f, 0.88f),
            excludeKeywords = listOf("步數", "移動", "好友", "設定", "商店")
        )
        if (leftoverConfirm != null) {
            appendLog("✨ 關閉殘留確認彈窗: ${leftoverConfirm.text}")
            service.clickRect(leftoverConfirm.bounds)
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
            if (target != null) {
                val newTaskCount = _uiStatus.value.tasksTriggeredCount + 1
                _uiStatus.value = _uiStatus.value.copy(tasksTriggeredCount = newTaskCount)
                appendLog("🎯 觸發【${target.taskName}】: ${target.targetLabel}")
                service.clickRect(target.bounds)
                popupRetryTicks = 0
                transitionTo(
                    EngineState.TRIGGER_AD_POPUP,
                    "已點擊${target.taskName}，等待看廣告彈窗..."
                )
                return
            }
        }

        // 若使用者開啟了「步數水瓶」但尚未框選任何水瓶範本，提供貼心提示
        val bottleTplCount = templateStorage.getTemplates(TemplateCategory.STEP_BOTTLE).size
        if (config.enableStepBottleTask && bottleTplCount == 0) {
            updateDetail("💡 尚未取樣『滿水瓶』圖示！請點懸浮窗 [✂️取樣] 框選一次滿水瓶")
        } else {
            updateDetail("🔍 正在掃描滿水瓶與可領取獎勵... (已看 ${_uiStatus.value.adsCompletedCount} 支廣告)")
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

        // 1. 尋找「觀看廣告 / 看影片 / 免費領取」按鈕 (結合 OCR + 節點樹 + 自訂按鈕範本)
        val adBtn = findAdTriggerButton(service, screenBitmap, ocrItems)
        if (adBtn != null) {
            appendLog("🎬 點擊彈窗看廣告按鈕: ${adBtn.first}")
            service.clickRect(adBtn.second)
            adCloserHandler.onEnterAdMode()
            transitionTo(EngineState.AD_WATCHING, "廣告開始播放，等待倒數結束...")
            return
        }

        // 2. 若等待超過 2 個 Tick 仍沒看到廣告按鈕，檢查是否只有「一般領取 / 確認」按鈕（例如廣告上限或純金幣）
        if (popupRetryTicks >= 2 && config.claimNormalWhenNoAd) {
            val normalClaim = ocrRecognizer.findMatch(
                items = ocrItems,
                keywords = OcrRecognizer.GAME_CLAIM_CONFIRM_KEYWORDS,
                screenWidth = sw,
                screenHeight = sh,
                roiNormalized = RectF(0.15f, 0.35f, 0.85f, 0.88f)
            )
            if (normalClaim != null) {
                appendLog("ℹ️ 彈窗無廣告按鈕，點擊一般領取/確認: [${normalClaim.text}]")
                service.clickRect(normalClaim.bounds)
                rewardClaimRetryTicks = 0
                transitionTo(EngineState.REWARD_CLAIM, "已點擊一般領取，確認彈窗關閉中")
                return
            }
        }

        // 3. 若等了 4 個 Tick 都沒有任何彈窗，代表剛剛可能沒點準或水瓶尚未集滿，退回主畫面重新掃描
        if (popupRetryTicks >= 4) {
            appendLog("↩️ 未偵測到獎勵彈窗，返回主畫面繼續掃描")
            transitionTo(EngineState.IN_GAME_SCAN, "重新掃描遊戲畫面...")
        } else {
            updateDetail("⏳ 等待看廣告彈窗出現 ($popupRetryTicks/4)...")
        }
    }

    /**
     * 狀態 3：廣告播放中，交由 AdCloserHandler 處理倒數、多階段 >> 與 X 關閉、以及防跳轉 Play 商店。
     */
    private suspend fun handleAdWatching(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ) {
        when (val res = adCloserHandler.processAdTick(service, screenBitmap, ocrItems)) {
            is AdStepResult.RedirectRecovered -> {
                appendLog("🛡️ 偵測到誤跳轉外部套件 (${res.externalPkg})，已自動按返回鍵並封鎖假 X 座標！")
            }

            is AdStepResult.WaitingGracePeriod -> {
                updateDetail("📺 廣告播放保護期 (${res.elapsedSec}s)...")
            }

            is AdStepResult.StillCountingDown -> {
                updateDetail("⏱️ 廣告倒數中：剩餘約 ${res.remainingSec} 秒 (已播 ${res.elapsedSec}s)")
            }

            is AdStepResult.ClickedCloseOrSkip -> {
                appendLog("👆 點擊關閉/快轉按鈕: ${res.sourceLabel}")
                updateDetail("👆 已點擊 ${res.sourceLabel}，確認是否回到遊戲或進入二階段...")
            }

            is AdStepResult.ReturnedToGame -> {
                val newAdsCount = _uiStatus.value.adsCompletedCount + 1
                _uiStatus.value = _uiStatus.value.copy(adsCompletedCount = newAdsCount)
                appendLog("🎉 成功關閉廣告回到遊戲！(累計完成 $newAdsCount 支廣告)")
                if (res.confirmBounds != null) {
                    service.clickRect(res.confirmBounds)
                }
                rewardClaimRetryTicks = 0
                transitionTo(EngineState.REWARD_CLAIM, "正在確認領取獎勵...")
            }

            is AdStepResult.WatchdogBackTriggered -> {
                appendLog("⚠️ 廣告等待逾時 (${res.elapsedSec}s)，觸發防卡死返回/右上角關閉")
                if (res.elapsedSec >= config.maxAdWaitSeconds + 15) {
                    transitionTo(EngineState.IN_GAME_SCAN, "逾時重置回遊戲掃描狀態")
                }
            }

            is AdStepResult.ScanningAd -> {
                updateDetail("🔍 正在搜尋廣告關閉 X / 略過按鈕 (已播 ${res.elapsedSec}s)...")
            }
        }
    }

    /**
     * 狀態 4：關閉廣告回到《LINE 散步趣》後，點擊「確認 / 獲得」完成本輪領獎，並立即檢查下一瓶水瓶。
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
            roiNormalized = RectF(0.15f, 0.30f, 0.85f, 0.90f),
            excludeKeywords = listOf("步數", "移動", "好友")
        )
        if (confirmMatch != null) {
            appendLog("✅ 點擊獎勵確認按鈕: [${confirmMatch.text}]")
            service.clickRect(confirmMatch.bounds)
            transitionTo(EngineState.IN_GAME_SCAN, "領獎完成！繼續掃描下一個滿水瓶...")
            return
        }

        if (rewardClaimRetryTicks >= 2) {
            transitionTo(EngineState.IN_GAME_SCAN, "已回到主畫面，繼續下一輪掃描")
        } else {
            updateDetail("✨ 確認領取獎勵狀態中...")
        }
    }

    /**
     * 結合「無障礙節點樹」、「ML Kit 中文 OCR」與「自訂按鈕圖示範本」尋找遊戲內的『看廣告』按鈕。
     */
    private suspend fun findAdTriggerButton(
        service: AutoAdAccessibilityService,
        screenBitmap: android.graphics.Bitmap,
        ocrItems: List<OcrItem>
    ): Pair<String, android.graphics.Rect>? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 1. ML Kit 中文 OCR 搜尋「看廣告 / 觀看影片 / 免費領取 / 2倍」
        val ocrMatch = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.GAME_AD_TRIGGER_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.08f, 0.25f, 0.92f, 0.88f)
        )
        if (ocrMatch != null) {
            return "OCR[${ocrMatch.text}]" to ocrMatch.bounds
        }

        // 2. 無障礙節點樹搜尋
        val nodeMatch = nodeInspector.findNodeByKeywords(
            root = service.getSafeRootNode(),
            keywords = OcrRecognizer.GAME_AD_TRIGGER_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh
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
                labelPrefix = "看廣告圖示"
            )
            if (tplMatch != null) {
                return tplMatch.label to tplMatch.bounds
            }
        }

        return null
    }

    private fun transitionTo(newState: EngineState, detail: String) {
        _uiStatus.value = _uiStatus.value.copy(
            state = newState,
            statusDetail = detail
        )
        appendLog("➡️ [${newState.title}] $detail")
    }

    private fun updateDetail(detail: String) {
        _uiStatus.value = _uiStatus.value.copy(statusDetail = detail)
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
