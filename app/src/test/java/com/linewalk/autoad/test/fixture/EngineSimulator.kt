package com.linewalk.autoad.test.fixture

import android.graphics.Rect
import com.linewalk.autoad.engine.MainTab
import com.linewalk.autoad.engine.PatrolPhase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-fidelity authoritative simulator for LineWalkAutoAd Engine & UI contracts.
 * Matches specifications in PROJECT.md and ORIGINAL_REQUEST.md.
 */
class EngineSimulator(
    val targetGamePackages: Set<String> = setOf("com.linecorp.LGWKTW", "com.linecorp.LGWALK", "jp.naver.line.android"),
    val ownPackage: String = "com.linewalk.autoad"
) {

    // ==========================================
    // R1: Floating Overlay State
    // ==========================================
    var isRunning: Boolean = false
        private set
    var isCollapsed: Boolean = false
        private set
    var overlayStatusText: String = "待命"
    var layoutWidth: Int = -2 // WRAP_CONTENT (-2), MATCH_PARENT (-1)
    var layoutHeight: Int = -2
    var ellipsizeMode: String? = null // null means no truncation, "END" means ...

    fun startPatrol() {
        isRunning = true
        // R1: Automatic collapse to compact single-line pill on start
        isCollapsed = true
        layoutWidth = -2 // WRAP_CONTENT
        layoutHeight = -2 // compact pill
        ellipsizeMode = null // Text must adapt without truncation
        overlayStatusText = "[巡邏中] 首頁"
    }

    fun stopPatrol(reason: String = "使用者手動暫停") {
        isRunning = false
        overlayStatusText = "[待命] $reason"
    }

    fun toggleExpandCollapse() {
        isCollapsed = !isCollapsed
        if (isCollapsed) {
            layoutWidth = -2 // WRAP_CONTENT
            layoutHeight = -2
            ellipsizeMode = null
        } else {
            layoutWidth = 800 // Full control panel width
            layoutHeight = 600
        }
    }

    var posX: Int = 16
    var posY: Int = 396

    fun simulateDrag(
        deltaX: Int,
        deltaY: Int,
        screenW: Int = 1080,
        screenH: Int = 2400,
        viewW: Int = 200,
        viewH: Int = 80
    ): Pair<Int, Int> {
        val maxX = (screenW - viewW).coerceAtLeast(0)
        val maxY = (screenH - viewH).coerceAtLeast(0)
        posX = (posX + deltaX).coerceIn(0, maxX)
        posY = (posY + deltaY).coerceIn(0, maxY)
        return Pair(posX, posY)
    }

    fun simulateConfigurationChanged(
        newScreenW: Int,
        newScreenH: Int,
        viewW: Int = 200,
        viewH: Int = 80
    ): Pair<Int, Int> {
        val maxX = (newScreenW - viewW).coerceAtLeast(0)
        val maxY = (newScreenH - viewH).coerceAtLeast(0)
        posX = posX.coerceIn(0, maxX)
        posY = posY.coerceIn(0, maxY)
        return Pair(posX, posY)
    }

    // ==========================================
    // R2: External App Guard
    // ==========================================
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

    fun isAdPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val knownAdPackages = setOf(
            "com.facebook.katana",
            "com.facebook.orca",
            "com.facebook.lite",
            "com.facebook.mlite",
            "com.instagram.android",
            "com.google.android.gms",
            "com.google.android.as",
            "com.google.android.webview",
            "com.unity3d.player",
            "com.unity3d.ads",
            "com.applovin",
            "com.applovin.sdk",
            "com.android.chrome",
            "androidx.browser.customtabs"
        )
        if (pkg in knownAdPackages) return true
        val lower = pkg.lowercase()
        return lower.startsWith("com.facebook.ads") ||
            lower.startsWith("com.facebook.katana") ||
            lower.startsWith("com.facebook.orca") ||
            lower.contains("audience_network") ||
            lower.contains("admob") ||
            lower.contains("google.android.gms") ||
            lower.contains("google.android.as") ||
            lower.contains("unity3d") ||
            lower.contains("unity.ads") ||
            lower.contains("unityads") ||
            lower.contains("applovin") ||
            lower.contains("ironsource") ||
            lower.contains("vungle") ||
            lower.contains("liftoff") ||
            lower.contains("mintegral") ||
            lower.contains("mbridge") ||
            lower.contains("pangle") ||
            lower.startsWith("com.bytedance.pangle") ||
            lower.contains("inmobi") ||
            lower.contains("customtabs")
    }

    fun evaluateForegroundPackage(
        fgPkg: String?,
        hasAdGeometricX: Boolean = false,
        isWatchingAd: Boolean = false
    ): GuardDecision {
        if (fgPkg == null) {
            // Safe fallback when package is unknown
            return GuardDecision(
                isExternal = true,
                recommendedAction = GuardAction.SAFE_BACK_NAVIGATION,
                regularClicksAllowed = false
            )
        }

        val isAd = isAdPackage(fgPkg)
        val isTarget = fgPkg in targetGamePackages || fgPkg == ownPackage || isAd
        return if (!isTarget) {
            // External app detected: strictly block regular UI clicks
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

    // ==========================================
    // R3: Infinite Loop & 5s Cooldown Patrol
    // ==========================================
    var currentRound: Int = 1
        private set
    var currentPhase: PatrolPhase = PatrolPhase.MAIN_TABS
    var currentTab: MainTab = MainTab.MOVE
    val confirmedEmptyTabs = mutableSetOf<MainTab>()
    var isBingoCompleted = false
    var isLotteryCompleted = false
    var isMissionCompleted = false
    var isFriendGiftCompleted = false
    var isReceiveGiftCompleted = false

    data class CooldownResult(
        val completedRound: Int,
        val nextRound: Int,
        val wasPausedDuringCooldown: Boolean,
        val cooldownSecondsWaited: Int
    )

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
                stopPatrol("巡邏冷卻中手動暫停")
                break
            }
        }

        if (!paused) {
            currentRound++
            // Reset round states
            confirmedEmptyTabs.clear()
            isBingoCompleted = false
            isLotteryCompleted = false
            isMissionCompleted = false
            isFriendGiftCompleted = false
            isReceiveGiftCompleted = false
            currentTab = MainTab.MOVE
            currentPhase = PatrolPhase.MAIN_TABS
        }

        return CooldownResult(
            completedRound = completed,
            nextRound = currentRound,
            wasPausedDuringCooldown = paused,
            cooldownSecondsWaited = waited
        )
    }

    // ==========================================
    // R4: Daily Bingo Priority Execution
    // ==========================================
    sealed class BingoStepAction {
        data class ClickOpenCell(val label: String, val bounds: Rect) : BingoStepAction()
        data class ClaimConnectionReward(val bingoCol: Int, val label: String, val bounds: Rect) : BingoStepAction()
        data class CloseDialog(val label: String, val bounds: Rect) : BingoStepAction()
        data object WaitCooldown : BingoStepAction()
    }

    fun stepBingoDialog(
        hasOpenCellButton: Boolean,
        inCooldown: Boolean,
        isLimitReached: Boolean,
        unclaimedColumns: List<Int>,
        sw: Int = MockFixtures.SCREEN_WIDTH,
        sh: Int = MockFixtures.SCREEN_HEIGHT
    ): BingoStepAction {
        // Step 1: Priority 1 - Orange Open Cell Button (if not cooldown and not limit)
        if (hasOpenCellButton && !inCooldown && !isLimitReached) {
            val cellRect = Rect(
                (sw * 0.20f).toInt(),
                (sh * 0.61f).toInt(),
                (sw * 0.80f).toInt(),
                (sh * 0.67f).toInt()
            )
            return BingoStepAction.ClickOpenCell("立即開啟賓果格", cellRect)
        }

        // Step 2: Priority 2 - Connection Get Buttons
        if (unclaimedColumns.isNotEmpty()) {
            val firstCol = unclaimedColumns.first()
            val colRatios = mapOf(1 to 0.18f, 3 to 0.39f, 5 to 0.61f, 8 to 0.82f)
            val cx = colRatios[firstCol] ?: 0.18f
            val getRect = Rect(
                (sw * (cx - 0.07f)).toInt(),
                (sh * 0.80f).toInt(),
                (sw * (cx + 0.07f)).toInt(),
                (sh * 0.86f).toInt()
            )
            return BingoStepAction.ClaimConnectionReward(firstCol, "$firstCol BINGO [▶ Get]", getRect)
        }

        // Step 3: Priority 3 - Close Dialog
        val closeRect = Rect(
            (sw * 0.86f).toInt(),
            (sh * 0.095f).toInt(),
            (sw * 0.96f).toInt(),
            (sh * 0.145f).toInt()
        )
        return BingoStepAction.CloseDialog("關閉賓果(X)", closeRect)
    }

    // ==========================================
    // R5: Log History & Export System
    // ==========================================
    val fullSessionLogs = mutableListOf<String>()

    fun appendLog(message: String, timestamp: Date = Date()) {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(timestamp)
        fullSessionLogs.add("[$timeStr] $message")
    }

    fun getFullLogsFormatted(
        deviceModel: String = "Pixel 7 Pro",
        osVersion: String = "Android 14 (API 34)",
        appVersion: String = "v1.15.20"
    ): String {
        return getExportLogsFormatted(
            maxLines = Int.MAX_VALUE,
            deviceModel = deviceModel,
            osVersion = osVersion,
            appVersion = appVersion
        )
    }

    fun getExportLogsFormatted(
        maxLines: Int = 1500,
        deviceModel: String = "Pixel 7 Pro",
        osVersion: String = "Android 14 (API 34)",
        appVersion: String = "v1.15.20"
    ): String {
        val isTruncated = fullSessionLogs.size > maxLines
        val targetLogs = if (isTruncated) fullSessionLogs.takeLast(maxLines) else fullSessionLogs

        val sb = StringBuilder()
        sb.append("========================================\n")
        sb.append("《LINE 散步趣助手》系統診斷日誌\n")
        sb.append("應用版本：$appVersion\n")
        sb.append("裝置型號：$deviceModel\n")
        sb.append("系統版本：$osVersion\n")
        sb.append("匯出時間：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        if (isTruncated) {
            sb.append("日誌筆數：${targetLogs.size} (總計 ${fullSessionLogs.size} 筆，已截取最近 1,500 筆記錄)\n")
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

    data class ShareIntentData(
        val action: String,
        val type: String,
        val textExtra: String
    )

    fun createShareIntentData(logsText: String): ShareIntentData {
        return ShareIntentData(
            action = "android.intent.action.SEND",
            type = "text/plain",
            textExtra = logsText
        )
    }

    // ==========================================
    // R6: Friend Gift & Receive Gift Drawer
    // ==========================================
    sealed class FriendGiftStepAction {
        data class SendGift(val bounds: Rect, val alsoToSelf: Boolean) : FriendGiftStepAction()
        data object OpenReceiveGiftDrawer : FriendGiftStepAction()
        data class CloseCompletedModal(val bounds: Rect) : FriendGiftStepAction()
    }

    fun stepFriendGift(
        currentGiftCount: Int,
        maxGiftCount: Int = 5,
        hasFriendsInList: Boolean = true,
        sw: Int = MockFixtures.SCREEN_WIDTH,
        sh: Int = MockFixtures.SCREEN_HEIGHT
    ): FriendGiftStepAction {
        // If daily limit reached (5/5) or no more friends to gift, advance directly to gift drawer
        if (currentGiftCount >= maxGiftCount || !hasFriendsInList) {
            return FriendGiftStepAction.OpenReceiveGiftDrawer
        }

        // Regular gifting
        val giftBtn = Rect(
            (sw * 0.80f).toInt(),
            (sh * 0.40f).toInt(),
            (sw * 0.92f).toInt(),
            (sh * 0.45f).toInt()
        )
        return FriendGiftStepAction.SendGift(giftBtn, alsoToSelf = true)
    }

    sealed class ReceiveDrawerStepAction {
        data class ClaimSolidFreeGift(val label: String, val bounds: Rect) : ReceiveDrawerStepAction()
        data class ClaimAdPlayGift(val label: String, val bounds: Rect) : ReceiveDrawerStepAction()
        data class CloseDrawer(val bounds: Rect) : ReceiveDrawerStepAction()
    }

    fun stepReceiveDrawer(
        solidFreeGiftsCount: Int,
        adPlayGiftsCount: Int,
        isEmptyDrawer: Boolean,
        sw: Int = MockFixtures.SCREEN_WIDTH,
        sh: Int = MockFixtures.SCREEN_HEIGHT
    ): ReceiveDrawerStepAction {
        val closeBtn = Rect(
            (sw * 0.84f).toInt(),
            (sh * 0.18f).toInt(),
            (sw * 0.98f).toInt(),
            (sh * 0.26f).toInt()
        )

        // 1. If drawer is empty, immediately close (prevents infinite loop)
        if (isEmptyDrawer || (solidFreeGiftsCount == 0 && adPlayGiftsCount == 0)) {
            return ReceiveDrawerStepAction.CloseDrawer(closeBtn)
        }

        // 2. Highest priority: Solid green button [領取] (free without ad)
        if (solidFreeGiftsCount > 0) {
            val claimRect = Rect(
                (sw * 0.65f).toInt(),
                (sh * 0.32f).toInt(),
                (sw * 0.92f).toInt(),
                (sh * 0.36f).toInt()
            )
            return ReceiveDrawerStepAction.ClaimSolidFreeGift("實心領取(免廣告)", claimRect)
        }

        // 3. Secondary priority: Green outline play button [▶] (requires watching ad)
        val playRect = Rect(
            (sw * 0.75f).toInt(),
            (sh * 0.44f).toInt(),
            (sw * 0.85f).toInt(),
            (sh * 0.48f).toInt()
        )
        return ReceiveDrawerStepAction.ClaimAdPlayGift("看廣告領取(▶)", playRect)
    }
}
