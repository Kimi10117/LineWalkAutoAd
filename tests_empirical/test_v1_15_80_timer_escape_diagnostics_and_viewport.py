# -*- coding: utf-8 -*-
"""
v1.15.80 全面驗證測試：
1. 完全移除現場取樣與手勢教學／無圓點單擊雙擊暫停負面防護
2. maxAdWaitSeconds 計時器與 safeNavigateBack(forceEscapeAdPackage=true) + ForceBringGameToFront 強退鏈
3. [狀態Snap] 結構化診斷快照、OCR 摘要 16 字上限、formatRectForLog 與匯出診斷擴充標頭 + clearFullLogs()
4. 廣告誤點防堵：AD_BAIT_PROMO_KEYWORDS（「開始遊戲」等）與 GameTask 座標／色彩閥值
5. ReceiveGiftTask 綠色膠囊像素守門（hasActiveGreenClaimCapsule）與已領取排除模擬
6. ScreenViewportCalibrator 連續比例插值（1.33f..2.45f）、左右黑邊 mapContentX 與多螢幕動態點擊黑名單半徑
7. UI runtime 設定（40~90s 計時滑桿、1000/1200/1500ms 三段速度、僅過濾顯示日誌、dpToPx(96) 懸浮窗安全高度）、版號 113 / 1.15.80 與零 Emoji／純繁體中文查核
"""

import math
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.dirname(__file__))

import test_challenger_m3_2_emoji_and_chinese_audit as m32


def read_repo_file(rel_path: str) -> str:
    full_path = os.path.join(ROOT, rel_path)
    with open(full_path, "r", encoding="utf-8") as f:
        return f.read()


# =========================================================================
# 1. 移除現場取樣機制與負面防護驗證
# =========================================================================

def test_live_sampling_completely_removed():
    """確認 TemplateStorage / TemplateCropOverlay / ic_crop 與所有取樣入口已完全移除"""
    removed_files = [
        "app/src/main/java/com/linewalk/autoad/config/TemplateStorage.kt",
        "app/src/main/java/com/linewalk/autoad/ui/TemplateCropOverlay.kt",
        "app/src/main/res/drawable/ic_crop.xml",
    ]
    for rel in removed_files:
        assert not os.path.exists(os.path.join(ROOT, rel)), f"應已移除檔案卻仍存在: {rel}"

    forbidden_tokens = [
        "TemplateStorage",
        "TemplateCategory",
        "TemplateCropOverlay",
        "ic_crop",
        "refreshPermissionAndTemplateStatus",
        "launchTemplateCropper",
        "tvTemplateSummary",
        "btnClearBottleTemplates",
        "btnClearAllTemplates",
        "AD_TRIGGER_BTN",
        "AD_CLOSE_ICON",
    ]
    for folder in ("app/src/main", "app/src/test"):
        base_dir = os.path.join(ROOT, folder)
        for root_dir, _, files in os.walk(base_dir):
            for fname in files:
                if not fname.endswith((".kt", ".java", ".xml", ".py")):
                    continue
                fpath = os.path.join(root_dir, fname)
                with open(fpath, "r", encoding="utf-8") as f:
                    content = f.read()
                for tok in forbidden_tokens:
                    assert tok not in content, f"{fpath} 殘留已移除符號: {tok}"


def test_cross_module_signatures_after_sampling_removal():
    """確認移除 TemplateStorage 後各跨模組建構與呼叫簽章完全一致"""
    ad_closer = read_repo_file("app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt")
    assert "class AdCloserHandler(\n" in ad_closer or "class AdCloserHandler(" in ad_closer
    assert "private val ocrRecognizer: OcrRecognizer" in ad_closer
    assert "private val nodeInspector: NodeInspector" in ad_closer
    assert "private val templateMatcher: TemplateMatcher" in ad_closer
    assert "private val config: AppConfig" in ad_closer

    engine = read_repo_file("app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt")
    assert re.search(
        r"AdCloserHandler\s*\(\s*ocrRecognizer\s*=\s*ocrRecognizer,\s*nodeInspector\s*=\s*nodeInspector,\s*templateMatcher\s*=\s*templateMatcher,\s*config\s*=\s*config\s*\)",
        engine,
    ), "AutomationEngine 初始化 AdCloserHandler 參數必須移除 templateStorage"

    game_task = read_repo_file("app/src/main/java/com/linewalk/autoad/tasks/GameTask.kt")
    assert re.search(
        r"suspend fun findTargetOnScreen\(\s*screenBitmap:\s*Bitmap,\s*ocrItems:\s*List<OcrItem>,\s*config:\s*AppConfig,\s*templateMatcher:\s*TemplateMatcher,\s*ocrRecognizer:\s*OcrRecognizer\s*\):",
        game_task,
    ), "GameTask.findTargetOnScreen 簽章不得再包含 templateStorage"

    main_activity = read_repo_file("app/src/main/java/com/linewalk/autoad/ui/MainActivity.kt")
    assert "private fun refreshPermissionStatus()" in main_activity
    assert "refreshPermissionStatus()" in main_activity


def test_negative_guards_no_tutorial_or_dot_double_tap_pause():
    """負面防護：嚴禁出現互動手勢教學或狀態圓點單擊／膠囊雙擊暫停控制"""
    main_activity = read_repo_file("app/src/main/java/com/linewalk/autoad/ui/MainActivity.kt")
    overlay_svc = read_repo_file("app/src/main/java/com/linewalk/autoad/ui/FloatingOverlayService.kt")
    activity_main = read_repo_file("app/src/main/res/layout/activity_main.xml")
    strings_xml = read_repo_file("app/src/main/res/values/strings.xml")

    combined = "\n".join([main_activity, overlay_svc, activity_main, strings_xml])
    for forbidden in [
        "DoubleTap",
        "onDoubleTap",
        "GestureDetector",
        "statusDot.setOnClickListener",
        "setOnDoubleClick",
        "手勢教學",
        "互動教學",
    ]:
        assert forbidden not in combined, f"禁止加入多餘手勢教學或圓點暫停機制: {forbidden}"

    # 懸浮窗單擊狀態區僅能切換收合／展開（toggleExpand），不應直接停止引擎
    assert "layoutControlPanel?.setOnClickListener" not in overlay_svc


# =========================================================================
# 2. 計時器強制退回、Container Bypass 與 ForceBringGameToFront 驗證
# =========================================================================

def test_accessibility_force_escape_ad_package_and_reject_reason():
    """驗證 safeNavigateBack(forceEscapeAdPackage=true) 與 lastBackRejectReason 診斷追蹤"""
    svc = read_repo_file("app/src/main/java/com/linewalk/autoad/service/AutoAdAccessibilityService.kt")

    assert "var lastBackRejectReason: String = \"\"" in svc
    assert "private set" in svc
    assert "fun safeNavigateBack(\n        minIntervalMs: Long = 2000L,\n        allowInGame: Boolean = false,\n        forceEscapeAdPackage: Boolean = false\n    ): Boolean" in svc
    assert "AD_CONTAINER_PROTECT(" in svc
    assert "GAME_PROTECT(" in svc
    assert "SYSTEM_GLOBAL_ACTION_FAILED(" in svc
    assert "[計時強退]" in svc
    assert "if (!forceEscapeAdPackage)" in svc
    assert "ScreenViewportCalibrator.getStatusBarSafeHeight(maxW, maxH)" in svc


def test_ad_closer_watchdog_escalation_and_container_bypass_math():
    """驗證 AdCloserHandler Watchdog 緩衝秒數與 2 次強制返回升級為 ForceBringGameToFront"""
    ad_closer = read_repo_file("app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt")

    assert "data class ForceBringGameToFront(val elapsedSec: Int, val foregroundPkg: String, val reason: String) : AdStepResult()" in ad_closer
    assert "fun getEffectiveWatchdogLimitSec(baseLimitSec: Int): Int" in ad_closer
    assert "if (elapsedSec >= config.maxAdWaitSeconds && now - lastBackActionTimeMs >= 2000L)" in ad_closer
    assert "if (watchdogForceBackAttempts >= 2)" in ad_closer
    assert "forceEscapeAdPackage = true" in ad_closer
    assert "logThrottledBackReject(service, \"計時強制返回鍵[第${watchdogForceBackAttempts}次]\")" in ad_closer

    # 行為模擬：getEffectiveWatchdogLimitSec
    def sim_effective_limit(base_limit_sec: int, was_ad_loading: bool) -> int:
        safe_base = max(20, base_limit_sec)
        return safe_base + 15 if was_ad_loading else safe_base

    assert sim_effective_limit(65, False) == 65
    assert sim_effective_limit(65, True) == 80
    assert sim_effective_limit(10, False) == 20
    assert sim_effective_limit(40, True) == 55


def test_ad_closer_redirect_and_high_confidence_blacklist_fixes():
    """驗證 Meta/Sponsored 左上角幾何 X 探索與 isGame=false / 高可信黑名單豁免"""
    ad_closer = read_repo_file("app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt")

    # redirectCount 必須在 > 0 且 !isGame 才判定已重試過
    assert "if (redirectCount > 0 && !isGame && !isMetaWebPreview && elapsedSec >= 42" in ad_closer
    assert "if (redirectCount > 0) {" in ad_closer
    assert "Sponsored頂部左上角X" in ad_closer
    assert "sponsoredTopLeftProbeCount < 1" in ad_closer
    assert "leftCornerRedirectCount < 2 && !disableLeftCornerForCurrentAd" in ad_closer

    # 高可信目標不應因同一座標重試而被誤入點擊黑名單；非高可信須達 >= 4 次
    assert "private fun isHighConfidenceAdTarget(label: String?): Boolean" in ad_closer
    assert "if (!isHighConfidence && nearbyClicks >= 4)" in ad_closer
    assert "markRecentRedirect" in ad_closer
    assert "3500L" in ad_closer

    # 行為模擬：getBlacklistRadiusPx(sw) 跨解析度比例縮放
    def sim_blacklist_radius(sw: int) -> float:
        return max(28.0, sw * 0.060)

    assert abs(sim_blacklist_radius(1080) - 64.8) < 1e-6
    assert sim_blacklist_radius(400) == 28.0
    assert abs(sim_blacklist_radius(1440) - 86.4) < 1e-6


# =========================================================================
# 3. 引擎診斷快照 ([狀態Snap])、外部緩衝與匯出報告驗證
# =========================================================================

def test_engine_timer_escape_diagnostics_and_clean_stop():
    """驗證 AutomationEngine 計時強退、[狀態Snap]、OCR 摘要、平穩停止與外部跳轉緩衝"""
    engine = read_repo_file("app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt")
    config = read_repo_file("app/src/main/java/com/linewalk/autoad/config/AppConfig.kt")

    # AppConfig 必須提供版本套件清單與查詢輔助
    assert "var lastDetectedGamePackage: String?" in config
    assert "val GAME_PACKAGE_PRIORITY_ORDER = listOf(" in config
    assert "fun getEnabledTaskNames(): List<String>" in config
    assert "fun isBrowserPackage(pkg: String?): Boolean" in config
    assert "fun isPlayStoreOrBrowserPackage(pkg: String?): Boolean" in config
    assert "set(value) = prefs.edit().putInt(KEY_MAX_AD_WAIT_SEC, value.coerceIn(20, 180)).apply()" in config

    # Coroutine 平穩取消與日誌
    assert "catch (e: kotlinx.coroutines.CancellationException)" in engine
    assert "[控制] 使用者手動暫停巡邏（工作階段平穩停止）" in engine
    assert "[子視窗] 領獎確認逾時（無有效目標或已領畢），退出子彈窗繼續巡邏" in engine

    # handleExternalAppGuard 合法略過約 2.0s 緩衝 + resolvePreferredGamePackage
    assert "val lastClickedLabel = adCloserHandler.getLastClickedAdLabel()" in engine
    assert "if (elapsedMs < 2000L)" in engine
    assert "保留約 2.0s 獎勵註冊緩衝" in engine
    assert "resolvePreferredGamePackage(service)" in engine

    # handleAdWatching 計時強退與 ForceBringGameToFront
    assert "val timerLimitSec = adCloserHandler.getEffectiveWatchdogLimitSec(config.maxAdWaitSeconds)" in engine
    assert "service.safeNavigateBack(minIntervalMs = 300L, allowInGame = true, forceEscapeAdPackage = true)" in engine
    assert "is AdStepResult.ForceBringGameToFront ->" in engine
    assert "[計時強退]" in engine
    assert "buildPhoneStateSnapshot(\"AD_WATCHDOG_TIMEOUT(" in engine
    assert "buildPhoneStateSnapshot(\"FORCE_BRING_FRONT(" in engine

    # 座標格式與清除日誌、擴充診斷標頭
    assert "[點擊] 點擊關閉/略過按鈕: ${res.sourceLabel} @ ($cx, $cy | ${nx}%, ${ny}%)" in engine
    assert "fun clearFullLogs()" in engine
    assert "_fullSessionLogs.clear()" in engine
    assert "螢幕規格：" in engine
    assert "核心設定：" in engine
    assert "工作階段摘要：" in engine
    assert "《LINE 散步趣助手》系統診斷日誌" in engine
    assert "應用版本：" in engine
    assert "裝置型號：" in engine
    assert "系統版本：" in engine


def test_stagnation_ocr_digest_and_rect_format_simulation():
    """數值與字串模擬：驗證 formatStagnationOcrDigest 上限 6 筆、單筆 16 字截斷與 formatRectForLog 格式"""
    def sim_format_digest(items, sw, sh, max_items=6):
        if not items or max_items <= 0:
            return ""
        cleaned = []
        for text, (l, t, r, b) in items:
            c = text.replace("\n", " ").replace("\r", " ").strip()
            if c:
                cleaned.append((c, l, t, r, b))
        parts = []
        for c, l, t, r, b in cleaned[:max_items]:
            trunc = c[:15] + "…" if len(c) > 16 else c
            cx = (l + r) // 2
            cy = (t + b) // 2
            nx = int(cx * 100.0 / sw) if sw > 0 else 0
            ny = int(cy * 100.0 / sh) if sh > 0 else 0
            parts.append(f'"{trunc}"@{nx}%,{ny}%')
        return " ; ".join(parts)

    sample = [
        ("立即購買超值優惠方案限時搶購中不要錯過", (100, 200, 900, 300)),
        ("  \n ", (0, 0, 10, 10)),
        ("安裝", (400, 1800, 680, 1900)),
        ("item3", (10, 10, 30, 30)),
        ("item4", (10, 10, 30, 30)),
        ("item5", (10, 10, 30, 30)),
        ("item6", (10, 10, 30, 30)),
        ("item7_should_be_dropped", (10, 10, 30, 30)),
    ]
    digest = sim_format_digest(sample, 1080, 2400, max_items=6)
    segs = digest.split(" ; ")
    assert len(segs) == 6
    assert segs[0].startswith('"立即購買超值優惠方案限時搶購中…"')
    assert "item7_should_be_dropped" not in digest

    def sim_format_rect(l, t, r, b, sw, sh):
        cx = (l + r) // 2
        cy = (t + b) // 2
        nx = f"{(cx * 100.0 / sw):.1f}" if sw > 0 else "0.0"
        ny = f"{(cy * 100.0 / sh):.1f}" if sh > 0 else "0.0"
        return f"({l},{t}-{r},{b}|c={cx},{cy}|{nx}%,{ny}%)"

    assert sim_format_rect(900, 100, 1020, 220, 1080, 2400) == "(900,100-1020,220|c=960,160|88.9%,6.7%)"


# =========================================================================
# 4. 廣告誘餌關鍵字過濾與 GameTask 安全防護驗證
# =========================================================================

def test_ad_bait_promo_keywords_and_gametask_filtering():
    """驗證 AD_BAIT_PROMO_KEYWORDS（「開始遊戲」等）已串聯至全 OCR／節點／任務過濾鏈"""
    ocr = read_repo_file("app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt")
    node_ins = read_repo_file("app/src/main/java/com/linewalk/autoad/vision/NodeInspector.kt")
    game_task = read_repo_file("app/src/main/java/com/linewalk/autoad/tasks/GameTask.kt")
    bingo_task = read_repo_file("app/src/main/java/com/linewalk/autoad/tasks/BingoTask.kt")

    for kw in ["開始遊戲", "開始遊玩", "輕鬆賺", "立即玩", "免費試玩"]:
        assert f'"{kw}"' in ocr, f"OcrRecognizer.AD_BAIT_PROMO_KEYWORDS 必須包含 {kw}"

    assert "OcrRecognizer.AD_BAIT_PROMO_KEYWORDS" in node_ins
    assert "OcrRecognizer.AD_BAIT_PROMO_KEYWORDS" in game_task
    assert "OcrRecognizer.AD_BAIT_PROMO_KEYWORDS" in bingo_task
    assert "COMMON_GAME_AD_EXCLUDES" in game_task
    assert "isButtonColorfulAndActive(" in game_task
    assert "cy <= 0.365f" in game_task or "0.365f" in game_task


# =========================================================================
# 5. ReceiveGiftTask 綠色膠囊像素守門與已領取排除模擬
# =========================================================================

def test_receive_gift_green_capsule_gate_simulation():
    """合成像素與 OCR 模擬：驗證「已領取」或無綠框膠囊的禮物列絕不被誤點"""
    rg = read_repo_file("app/src/main/java/com/linewalk/autoad/tasks/ReceiveGiftTask.kt")
    assert "fun hasActiveGreenClaimCapsule(screenBitmap: Bitmap, targetRect: Rect): Boolean" in rg
    assert "private fun isRowAlreadyClaimedByOcr(" in rg
    assert "!isRowAlreadyClaimedByOcr(expanded.centerY(), ocrItems, vp)" in rg
    assert "hasActiveGreenClaimCapsule(screenBitmap, expanded)" in rg

    # 像素模擬函式（對應 ReceiveGiftTask.hasActiveGreenClaimCapsule 核心演算）
    def sim_has_green_capsule(pixels, w, h, rect_ltrb):
        rl, rt, rr, rb = rect_ltrb
        min_rx = int(w * 0.60)
        max_rx = int(w * 0.96)
        pad_x = max(2, int(w * 0.015))
        pad_y = max(2, int((rb - rt) * 0.30))
        sl = min(w - 1, max(0, max(rl - pad_x, min_rx)))
        sr = max(sl + 1, min(w, min(rr + pad_x, max_rx)))
        st = min(h - 1, max(0, rt - pad_y))
        sb = max(st + 1, min(h, rb + pad_y))
        sw_box, sh_box = sr - sl, sb - st
        if sw_box <= 2 or sh_box <= 2:
            return False
        step = max(1, min(sw_box, sh_box) // 48)
        green_cnt = 0
        total = 0
        min_gx, max_gx = 10**9, -(10**9)
        for y in range(st, sb, step):
            for x in range(sl, sr, step):
                r, g, b = pixels(x, y)
                if g > 135 and g > r + 25 and g > b + 25:
                    green_cnt += 1
                    min_gx = min(min_gx, x)
                    max_gx = max(max_gx, x)
                total += 1
        if total == 0 or green_cnt == 0:
            return False
        min_samples = max(4, total // 50)
        min_span_w = max(6, int(sw_box * 0.20))
        return green_cnt >= min_samples and (max_gx - min_gx) >= min_span_w

    # 案例 A：已領完禮物列（按鈕區為灰白色，RGB=(210,210,210)）-> 必為 False
    assert sim_has_green_capsule(lambda x, y: (210, 210, 210), 1080, 2400, (760, 800, 960, 880)) is False

    # 案例 B：有效 LINE 亮綠色膠囊按鈕 (RGB=(6,199,85)) 覆蓋 (770..940, 810..870) -> 必為 True
    def active_btn_pixels(x, y):
        if 770 <= x <= 940 and 810 <= y <= 870:
            return (6, 199, 85)
        return (255, 255, 255)

    assert sim_has_green_capsule(active_btn_pixels, 1080, 2400, (760, 800, 960, 880)) is True

    # 案例 C：寬度僅 2px 的零星綠色雜訊像素 -> 不足 minGreenSpanW，必為 False
    def noise_pixels(x, y):
        if 820 <= x <= 822 and 810 <= y <= 870:
            return (6, 199, 85)
        return (245, 245, 245)

    assert sim_has_green_capsule(noise_pixels, 1080, 2400, (760, 800, 960, 880)) is False


# =========================================================================
# 6. ScreenViewportCalibrator 多比例連續插值與左右黑邊模擬
# =========================================================================

def test_screen_viewport_calibrator_interpolation_and_letterbox():
    """數值模擬：驗證 1.33f..2.45f 分段線性插值、狀態列安全高度與左右黑邊 mapContentX"""
    cal = read_repo_file("app/src/main/java/com/linewalk/autoad/vision/ScreenViewportCalibrator.kt")
    assert "val effectiveLeftX: Int = 0" in cal
    assert "val effectiveRightX: Int = screenWidth" in cal
    assert "fun mapContentX(ratio: Float): Int" in cal
    assert "fun getStatusBarSafeHeight(sw: Int, sh: Int, screenBitmap: Bitmap? = null): Int" in cal
    assert "fun interpolateSubDialogCloseYRatio(effectiveAspect: Float): Float" in cal

    # 對應 ScreenViewportCalibrator.interpolatePiecewise 數學演算
    def interp(x, anchors):
        if x <= anchors[0][0]:
            return anchors[0][1]
        if x >= anchors[-1][0]:
            return anchors[-1][1]
        for (x0, y0), (x1, y1) in zip(anchors, anchors[1:]):
            if x <= x1:
                t = max(0.0, min(1.0, (x - x0) / (x1 - x0)))
                return y0 + (y1 - y0) * t
        return anchors[-1][1]

    close_anchors = [
        (1.33, 0.068),
        (1.778, 0.078),
        (1.95, 0.100),
        (2.15, 0.115),
        (2.444, 0.121),
        (2.45, 0.121),
    ]
    # 驗證錨點與單調遞增性（16:9 到 22:9 之間連續平滑過渡）
    prev = 0.0
    for aspect in [1.33, 1.50, 1.778, 1.85, 1.95, 2.05, 2.15, 2.22, 2.35, 2.444, 2.45, 2.60]:
        val = interp(aspect, close_anchors)
        assert val >= prev - 1e-9, f"插值應單調非減: aspect={aspect}, val={val}, prev={prev}"
        assert 0.068 <= val <= 0.121
        prev = val
    assert abs(interp(1.33, close_anchors) - 0.068) < 1e-6
    assert abs(interp(1.778, close_anchors) - 0.078) < 1e-6
    assert abs(interp(2.444, close_anchors) - 0.121) < 1e-6

    # 驗證摺疊機展開含左右黑邊（總寬 2176, 實際遊戲內容 544..1632 -> contentWidth=1088）
    left_x, right_x = 544, 1632
    content_w = max(1, right_x - left_x)
    map_x = lambda ratio: max(0, min(2175, int(left_x + content_w * ratio)))
    assert map_x(0.50) == 1088
    assert 544 <= map_x(0.875) <= 1632
    assert map_x(0.875) == 1496  # 若未扣黑邊會誤落在 1904（黑邊外）

    # 驗證 MissionTask、FriendGiftTask、TemplateMatcher 皆已接入校準器新 API
    mission = read_repo_file("app/src/main/java/com/linewalk/autoad/tasks/MissionTask.kt")
    friend_gift = read_repo_file("app/src/main/java/com/linewalk/autoad/tasks/FriendGiftTask.kt")
    tmpl_matcher = read_repo_file("app/src/main/java/com/linewalk/autoad/vision/TemplateMatcher.kt")
    assert "vp.mapContentX(xRatio)" in mission
    assert "private fun buildBottomNavTabRect(vp: ScreenViewportCalibrator.ViewportMetrics, xRatio: Float): Rect" in friend_gift
    assert "ScreenViewportCalibrator.getStatusBarSafeHeight(sw, sh, screenBitmap)" in tmpl_matcher


# =========================================================================
# 7. UI 設定控制項、懸浮窗、版號與 0 Emoji／純繁中稽核
# =========================================================================

def test_ui_controls_overlay_and_release_metadata():
    """驗證 UI 計時器滑桿（40~90s）、三段速度（1000/1200/1500）、清除日誌與版號 113 / 1.15.80"""
    main_act = read_repo_file("app/src/main/java/com/linewalk/autoad/ui/MainActivity.kt")
    overlay = read_repo_file("app/src/main/java/com/linewalk/autoad/ui/FloatingOverlayService.kt")
    layout = read_repo_file("app/src/main/res/layout/activity_main.xml")
    gradle = read_repo_file("app/build.gradle.kts")
    workflow = read_repo_file(".github/workflows/build-apk.yml")

    # MainActivity & layout 控制項綁定
    assert "MIN_AD_WAIT_SECONDS = 40" in main_act
    assert "MAX_AD_WAIT_SECONDS = 90" in main_act
    for view_id in [
        "seekBarMaxAdWait",
        "btnDecreaseAdWait",
        "btnIncreaseAdWait",
        "btnSpeedFast",
        "btnSpeedNormal",
        "btnSpeedEco",
        "cbFilterErrorStatusLogs",
        "btnClearLogs",
        "cardUpdateBanner",
    ]:
        assert f'android:id="@+id/{view_id}"' in layout, f"activity_main.xml 缺少 {view_id}"
        assert view_id in main_act, f"MainActivity.kt 未綁定 {view_id}"

    assert "1000L" in main_act and "1200L" in main_act and "1500L" in main_act
    assert "filterImportantLogs" in main_act
    assert "clearFullLogs()" in main_act
    # cardUpdateBanner 預設維持 gone
    banner_idx = layout.find('android:id="@+id/cardUpdateBanner"')
    assert 'android:visibility="gone"' in layout[banner_idx:banner_idx + 450]

    # 懸浮窗安全起始高度與計時進度文字
    assert "coerceAtLeast(dpToPx(96))" in overlay
    assert "[廣告監控]" in overlay
    assert "/ ${maxAdWaitSec}s" in overlay

    # 版本資訊 113 / 1.15.80 與 RELEASE_NOTES.md
    assert "versionCode = 113" in gradle
    assert 'versionName = "1.15.80"' in gradle
    assert "v1.15.80" in workflow

    release_notes = read_repo_file("RELEASE_NOTES.md")
    assert "v1.15.80" in release_notes
    assert 'versionCode 113, versionName "1.15.80"' in release_notes
    assert "## 版本資訊 (歷史版本: v1.15.79)" in release_notes


def test_v1_15_80_zero_emoji_and_traditional_chinese_only():
    """稽核 v1.15.80 所有異動檔案 0 Emoji，且所有新增程式碼／UI／發布說明／測試均 100% 繁體中文"""
    import subprocess

    files_to_audit = [
        "RELEASE_NOTES.md",
        "app/build.gradle.kts",
        ".github/workflows/build-apk.yml",
        "app/src/main/AndroidManifest.xml",
        "app/src/main/res/layout/activity_main.xml",
        "app/src/main/java/com/linewalk/autoad/config/AppConfig.kt",
        "app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt",
        "app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt",
        "app/src/main/java/com/linewalk/autoad/service/AutoAdAccessibilityService.kt",
        "app/src/main/java/com/linewalk/autoad/tasks/BingoTask.kt",
        "app/src/main/java/com/linewalk/autoad/tasks/FriendGiftTask.kt",
        "app/src/main/java/com/linewalk/autoad/tasks/GameTask.kt",
        "app/src/main/java/com/linewalk/autoad/tasks/MissionTask.kt",
        "app/src/main/java/com/linewalk/autoad/tasks/ReceiveGiftTask.kt",
        "app/src/main/java/com/linewalk/autoad/ui/FloatingOverlayService.kt",
        "app/src/main/java/com/linewalk/autoad/ui/MainActivity.kt",
        "app/src/main/java/com/linewalk/autoad/vision/NodeInspector.kt",
        "app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt",
        "app/src/main/java/com/linewalk/autoad/vision/ScreenViewportCalibrator.kt",
        "app/src/main/java/com/linewalk/autoad/vision/TemplateMatcher.kt",
        "tests_empirical/test_v1_15_80_timer_escape_diagnostics_and_viewport.py",
    ]
    for rel in files_to_audit:
        content = read_repo_file(rel)
        emoji_hits = m32.check_emojis_in_text(content, rel)
        assert len(emoji_hits) == 0, f"{rel} 發現禁用 Emoji: {emoji_hits}"

    # UI / 設定 / 版號 / 發布說明 / 測試檔案全文必須 100% 繁體中文
    full_trad_files = [
        "RELEASE_NOTES.md",
        "app/build.gradle.kts",
        ".github/workflows/build-apk.yml",
        "app/src/main/AndroidManifest.xml",
        "app/src/main/res/layout/activity_main.xml",
        "app/src/main/java/com/linewalk/autoad/config/AppConfig.kt",
        "app/src/main/java/com/linewalk/autoad/service/AutoAdAccessibilityService.kt",
        "app/src/main/java/com/linewalk/autoad/ui/FloatingOverlayService.kt",
        "app/src/main/java/com/linewalk/autoad/ui/MainActivity.kt",
        "tests_empirical/test_v1_15_80_timer_escape_diagnostics_and_viewport.py",
    ]
    for rel in full_trad_files:
        content = read_repo_file(rel)
        simp_hits = m32.check_simplified_chinese_in_text(content, rel)
        assert len(simp_hits) == 0, f"{rel} 發現簡體中文字元: {simp_hits}"

    # v1.15.80 所有 git diff 新增行亦必須 100% 繁體中文、無任何簡體殘留
    diff_out = subprocess.check_output(
        ["git", "diff", "--unified=0", "--", "RELEASE_NOTES.md", "app/src/main/", "app/build.gradle.kts", ".github/workflows/"],
        cwd=ROOT,
    ).decode("utf-8", errors="replace")
    added_text = "\n".join(
        line[1:] for line in diff_out.splitlines() if line.startswith("+") and not line.startswith("+++")
    )
    diff_simp = m32.check_simplified_chinese_in_text(added_text, "v1.15.80_git_diff_added_lines")
    assert len(diff_simp) == 0, f"v1.15.80 新增程式碼發現簡體中文字元: {diff_simp}"
