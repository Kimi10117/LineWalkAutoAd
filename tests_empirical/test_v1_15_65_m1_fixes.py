import os
import re
import pytest

SRC_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "com", "linewalk", "autoad")


class Rect:
    def __init__(self, left, top, right, bottom):
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom

    def centerX(self):
        return (self.left + self.right) // 2

    def centerY(self):
        return (self.top + self.bottom) // 2


class OcrItem:
    def __init__(self, text, cy=0.5, cx=0.5, bounds=None, sw=1080, sh=2400):
        self.text = text
        self.bounds_centerY = cy
        if bounds is not None:
            self.bounds = bounds
        else:
            w = 200
            h = 60
            center_x = int(cx * sw)
            center_y = int(cy * sh)
            self.bounds = Rect(center_x - w // 2, center_y - h // 2, center_x + w // 2, center_y + h // 2)


class MockBitmap:
    def __init__(self, width=1080, height=2400):
        self.width = width
        self.height = height


class MainTab:
    MOVE = "MOVE"
    STEP = "STEP"
    TIME = "TIME"
    GAME = "GAME"


class EngineState:
    IDLE = "IDLE"
    IN_GAME_SCAN = "IN_GAME_SCAN"
    TRIGGER_AD_POPUP = "TRIGGER_AD_POPUP"
    AD_WATCHING = "AD_WATCHING"
    REWARD_CLAIM = "REWARD_CLAIM"


class EngineUiStatus:
    def __init__(
        self,
        is_running=True,
        state=EngineState.IN_GAME_SCAN,
        current_round=1,
        current_tab=MainTab.MOVE,
        status_detail="",
        overlay_safe_detail="",
        ads_completed_count=0,
        tasks_triggered_count=0,
        recent_logs=None,
    ):
        self.isRunning = is_running
        self.state = state
        self.currentRound = current_round
        self.currentTab = current_tab
        self.statusDetail = status_detail
        self.overlaySafeDetail = overlay_safe_detail
        self.adsCompletedCount = ads_completed_count
        self.tasksTriggeredCount = tasks_triggered_count
        self.recentLogs = recent_logs or []


def simulate_find_center_reward_modal_close_button(bitmap, items):
    """
    精確模擬 OcrRecognizer.kt 中 findCenterRewardModalCloseButton 之 00A 豁免與關閉按鈕判定。
    """
    sw = bitmap.width
    sh = bitmap.height

    # 00. 嚴格一票否決：下半部領獎彈窗
    is_bottom_reward_sheet = any(
        (0.85 <= (item.bounds.centerY() / float(sh)) <= 0.98 and ("取消" in item.text)) or
        (0.70 <= (item.bounds.centerY() / float(sh)) <= 0.92 and ("跳過廣告" in item.text or "紅利" in item.text))
        for item in items
    )
    if is_bottom_reward_sheet:
        return None

    # 明確領獎標題提前計算
    has_explicit_ticket_or_coin_modal_title = any(
        0.20 <= (item.bounds.centerY() / float(sh)) <= 0.42 and any(
            kw in item.text.replace(" ", "")
            for kw in ["張票券！", "張票券!", "獲得15張", "獲得10張", "枚金幣！", "枚金幣!"]
        )
        for item in items
    )

    # 00A. 嚴格一票否決：若畫面為【遊戲】分頁且無明確領獎標題，絕非中央領獎彈窗！
    is_game_tab_screen = (not has_explicit_ticket_or_coin_modal_title) and any(
        any(
            kw in item.text.replace(" ", "")
            for kw in [
                "秘寶挖挖哇",
                "數獨達人",
                "接龍高手",
                "POINTSGAME",
                "簡單賺金幣",
                "輕鬆賺",
                "開始遊玩",
            ]
        )
        for item in items
    )
    if is_game_tab_screen:
        return None

    # 00B. 嚴格一票否決：抽抽樂
    is_lottery_panel_or_result = (not has_explicit_ticket_or_coin_modal_title) and any(
        any(
            kw in item.text.replace(" ", "")
            for kw in [
                "中獎囉", "中獎", "分享給好友", "查看獎項", "獎項一覽",
                "完成任務", "即可獲得", "大量金幣", "5張抽", "50張抽", "回復還需"
            ]
        )
        for item in items
    )
    if is_lottery_panel_or_result:
        return None

    # 1. OCR 搜尋彈窗下半部真正的白底領獎彈窗範圍 (y = 0.60..0.78, x = 0.20..0.80)
    for item in items:
        cx = item.bounds.centerX()
        cy = item.bounds.centerY()
        norm_y = cy / float(sh)
        norm_x = cx / float(sw)
        if not (0.60 <= norm_y <= 0.78 and 0.20 <= norm_x <= 0.80):
            continue
        t = item.text.strip()
        is_exact_ok = bool(re.match(r"^\s*\[?\s*OK\s*\]?\s*$", t, re.IGNORECASE)) or t.upper() == "OK"
        is_chinese_close = any(kw in t for kw in ["關閉", "確認", "確定", "收下"])
        if (is_exact_ok or is_chinese_close) and len(t) <= 6:
            return ("領獎完成[關閉]OCR", item.bounds)

    return None


def simulate_is_line_walk_main_screen_visible(items, is_dimmed_modal=False, modal_close_btn=None, bitmap=None):
    """
    模擬 OcrRecognizer.kt 之 isLineWalkMainScreenVisible 核心判定邏輯。
    若未傳入 modal_close_btn 且畫面包含遊戲分頁特徵，會透過 simulate_find_center_reward_modal_close_button 檢測真實彈窗關閉按鈕。
    包含遊戲分頁對 isDimmedCenterModalOpen 之包裹式一票否決與領獎彈窗放行。
    """
    if bitmap is None:
        bitmap = MockBitmap()

    if modal_close_btn is None:
        detected = simulate_find_center_reward_modal_close_button(bitmap, items)
        if detected is not None:
            modal_close_btn = detected[0]

    is_game_tab = any(
        any(
            kw in item.text.replace(" ", "")
            for kw in [
                "秘寶挖挖哇",
                "數獨達人",
                "接龍高手",
                "POINTSGAME",
                "LINEPOINTS",
                "簡單賺金幣",
                "輕鬆賺",
                "開始遊玩",
                "玩LINE",
            ]
        )
        for item in items
    )

    # 1. 中央領獎彈窗判定（含遊戲分頁包裹式一票否決與領獎彈窗放行）
    if (
        (not is_game_tab and (modal_close_btn is not None or is_dimmed_modal))
        or (is_game_tab and modal_close_btn is not None)
    ):
        return False

    return True


def simulate_format_active_status_text(status: EngineUiStatus) -> str:
    """
    模擬 FloatingOverlayService.kt 中 formatActiveStatusText 之格式化邏輯。
    """
    if not status.isRunning:
        return "散步趣助手 · 待命"

    detail = status.statusDetail
    safe_detail = status.overlaySafeDetail

    if "外部保護" in detail or "外部保護" in safe_detail or "防護" in detail:
        return "[外部保護] 安全退出中"

    if "每日賓果" in detail or "每日賓果" in safe_detail or "賓果" in safe_detail:
        if "開格" in detail or "開格" in safe_detail:
            return "[每日賓果] 開格中"
        elif "連線" in detail or "Get" in detail or "Get" in safe_detail:
            return "[每日賓果] 領取Get"
        else:
            return "[每日賓果] 執行中"

    if "抽抽樂" in detail or "抽抽樂" in safe_detail or "抽獎" in detail or "抽獎" in safe_detail:
        return "[抽抽樂] 開啟中"

    if "任務" in detail or "任務" in safe_detail:
        return "[任務] 領取中"

    if "好友" in detail or "好友" in safe_detail or "送禮" in detail or "送禮" in safe_detail:
        return "[好友送禮] 贈送中"

    if "收禮" in detail or "收禮" in safe_detail or "接收禮物" in detail or "接收禮物" in safe_detail:
        return "[接收禮物] 領取中"

    if status.state == EngineState.AD_WATCHING:
        sec_match = re.search(r"\((\d+)s?\)", safe_detail) or re.search(r"\((\d+)s?\)", detail)
        if sec_match:
            return f"[廣告監控] 播放中 ({sec_match.group(1)}s)"
        else:
            return "[廣告監控] 播放中"

    if "冷卻" in detail or "冷卻" in safe_detail:
        return "[巡邏中] 冷卻等待中"

    if "首頁" in detail or "移動" in detail or "首頁" in safe_detail or "移動" in safe_detail:
        return "[巡邏中] 首頁"

    if "步數" in detail or "步數" in safe_detail:
        return "[巡邏中] 步數"

    if "時間" in detail or "放置" in detail or "時間" in safe_detail or "放置" in safe_detail:
        return "[巡邏中] 時間"

    if "遊戲" in detail or "遊戲" in safe_detail:
        return "[巡邏中] 遊戲"

    if status.state == EngineState.IN_GAME_SCAN:
        if status.currentTab == MainTab.MOVE:
            return "[巡邏中] 首頁"
        elif status.currentTab == MainTab.STEP:
            return "[巡邏中] 步數"
        elif status.currentTab == MainTab.TIME:
            return "[巡邏中] 時間"
        elif status.currentTab == MainTab.GAME:
            return "[巡邏中] 遊戲"

    return f"{status.state} ({status.adsCompletedCount})"


def test_m1_f1_game_tab_dimmed_modal_veto():
    """驗證包含遊戲分頁特徵時，isLineWalkMainScreenVisible 一票否決 isDimmedCenterModalOpen 假陽性。"""
    # 場景一：遊戲分頁且深色背景觸發遮罩假陽性 (is_dimmed_modal=True)，但無領獎彈窗關閉按鈕
    game_items = [
        OcrItem("數獨達人"),
        OcrItem("接龍高手"),
        OcrItem("開始遊玩"),
    ]
    assert simulate_is_line_walk_main_screen_visible(game_items, is_dimmed_modal=True, modal_close_btn=None) is True, \
        "遊戲分頁應一票否決 isDimmedCenterModalOpen 假陽性，回傳 true"

    # 場景二：遊戲分頁且確實存在中央領獎彈窗關閉鈕
    assert simulate_is_line_walk_main_screen_visible(game_items, is_dimmed_modal=True, modal_close_btn="關閉") is False, \
        "遊戲分頁存在真正領獎關閉鈕時，應回傳 false"

    # 場景三：非遊戲分頁（如首頁），若觸發遮罩判定，應回傳 false
    normal_items = [
        OcrItem("首頁"),
        OcrItem("步數"),
    ]
    assert simulate_is_line_walk_main_screen_visible(normal_items, is_dimmed_modal=True, modal_close_btn=None) is False, \
        "非遊戲分頁若觸發遮罩判定，應正常回傳 false"


def test_m1_f2_floating_overlay_tab_tracking():
    """驗證當 currentTab = MainTab.GAME 且 detail 無關鍵字時，formatActiveStatusText 精確輸出 [巡邏中] 遊戲。"""
    # 輪詢中之通用 detail（無任何分頁關鍵字）
    polling_detail = "[巡邏] 正在搜尋可領取目標或關閉按鈕... (已完成 3 次)"
    polling_safe = "[巡邏] 搜尋畫面目標中..."

    # 狀態 1：遊戲分頁輪詢中
    status_game = EngineUiStatus(
        is_running=True,
        state=EngineState.IN_GAME_SCAN,
        current_tab=MainTab.GAME,
        status_detail=polling_detail,
        overlay_safe_detail=polling_safe,
    )
    assert simulate_format_active_status_text(status_game) == "[巡邏中] 遊戲", \
        "遊戲分頁輪詢時，懸浮窗必須顯示 [巡邏中] 遊戲，絕不可退步為 [巡邏中] 首頁"

    # 狀態 2：時間分頁輪詢中
    status_time = EngineUiStatus(
        is_running=True,
        state=EngineState.IN_GAME_SCAN,
        current_tab=MainTab.TIME,
        status_detail=polling_detail,
        overlay_safe_detail=polling_safe,
    )
    assert simulate_format_active_status_text(status_time) == "[巡邏中] 時間"

    # 狀態 3：步數分頁輪詢中
    status_step = EngineUiStatus(
        is_running=True,
        state=EngineState.IN_GAME_SCAN,
        current_tab=MainTab.STEP,
        status_detail=polling_detail,
        overlay_safe_detail=polling_safe,
    )
    assert simulate_format_active_status_text(status_step) == "[巡邏中] 步數"

    # 狀態 4：首頁分頁輪詢中
    status_move = EngineUiStatus(
        is_running=True,
        state=EngineState.IN_GAME_SCAN,
        current_tab=MainTab.MOVE,
        status_detail=polling_detail,
        overlay_safe_detail=polling_safe,
    )
    assert simulate_format_active_status_text(status_move) == "[巡邏中] 首頁"


def test_m1_static_contract_compatibility():
    """靜態代碼契約檢查，確保既有 test_v1_15_59 契約字串與 Milestone 1 新增防護結構完整相容。"""
    ocr_file = os.path.join(SRC_DIR, "vision", "OcrRecognizer.kt")
    with open(ocr_file, "r", encoding="utf-8") as f:
        ocr_code = f.read()

    # 1. 確保 test_v1_15_59 契約字串完整保留
    assert (
        "findCenterRewardModalCloseButton(screenBitmap, items) != null || isDimmedCenterModalOpen(screenBitmap)"
        in ocr_code
    ), "必須完整保留 test_v1_15_59 契約字串"

    # 2. 確保 isGameTabScreen 包裹式一票否決結構存在，且 00A 具備領獎標題豁免保護
    assert "val isGameTabScreen = items.any" in ocr_code
    assert "!isGameTabScreen && (findCenterRewardModalCloseButton" in ocr_code
    assert "isGameTabScreen && findCenterRewardModalCloseButton" in ocr_code
    assert "val hasExplicitTicketOrCoinModalTitle = items.any" in ocr_code
    assert "val isGameTabScreen = !hasExplicitTicketOrCoinModalTitle && items.any" in ocr_code

    # 3. 確保 AutomationEngine 具備分頁守護與自癒推進常數與方法
    engine_file = os.path.join(SRC_DIR, "engine", "AutomationEngine.kt")
    with open(engine_file, "r", encoding="utf-8") as f:
        engine_code = f.read()

    assert "const val TAB_STAGNATION_THRESHOLD = 15" in engine_code
    assert "const val NON_MAIN_IDLE_HEARTBEAT_TICKS = 6" in engine_code
    assert "const val NON_MAIN_IDLE_FALLBACK_TICKS = 6" in engine_code
    assert "const val NON_MAIN_IDLE_ADVANCE_TICKS = 15" in engine_code
    assert "const val NON_MAIN_IDLE_SAFETY_BACK_TICKS = 20" in engine_code
    assert "fun triggerTabSelfHealingAdvancement" in engine_code
    assert "nonMainScreenIdleTicks" in engine_code
    assert "tabStagnationTicks" in engine_code
    assert "val currentTab: MainTab = MainTab.MOVE" in engine_code

    # 4. 確保 FloatingOverlayService 移除寫死首頁並依 status.currentTab 動態映射
    overlay_file = os.path.join(SRC_DIR, "ui", "FloatingOverlayService.kt")
    with open(overlay_file, "r", encoding="utf-8") as f:
        overlay_code = f.read()

    assert "when (status.currentTab)" in overlay_code
    assert 'com.linewalk.autoad.engine.MainTab.GAME -> "[巡邏中] 遊戲"' in overlay_code
    assert 'status.state == com.linewalk.autoad.engine.EngineState.IN_GAME_SCAN -> "[巡邏中] 首頁"' not in overlay_code


def test_m1_f1_game_tab_00a_veto_without_reward_title():
    """
    驗證當遊戲分頁無明確領獎標題時：
    00A 否決規則生效，findCenterRewardModalCloseButton 回傳 None（杜絕空點數獨達人卡片）；
    且 isLineWalkMainScreenVisible 判定在主畫面（回傳 True），不被暗色背景假陽性遮罩干擾。
    """
    bitmap = MockBitmap()
    game_screen_items = [
        OcrItem("遊戲", cy=0.12),
        OcrItem("LINE POINTS", cy=0.15),
        OcrItem("秘寶挖挖哇", cy=0.40),
        OcrItem("數獨達人", cy=0.60),
        OcrItem("接龍高手", cy=0.75),
        OcrItem("開始遊玩", cy=0.82),
        OcrItem("集點", cy=0.92),
    ]

    close_btn = simulate_find_center_reward_modal_close_button(bitmap, game_screen_items)
    assert close_btn is None, "無領獎標題時，00A 否決必須生效，回傳 None"

    is_main_visible = simulate_is_line_walk_main_screen_visible(game_screen_items, is_dimmed_modal=True)
    assert is_main_visible is True, "無領獎標題時，遊戲分頁主畫面判定必須回傳 True"


def test_m1_f1_game_tab_00a_exemption_with_reward_modal():
    """
    驗證當遊戲分頁前景彈出領獎彈窗時：
    00A 豁免生效，findCenterRewardModalCloseButton 精確定位關閉按鈕；
    且 isLineWalkMainScreenVisible 判定不在主畫面（回傳 False），解除彈窗死鎖。
    """
    bitmap = MockBitmap()
    modal_and_game_items = [
        OcrItem("遊戲", cy=0.12),
        OcrItem("獲得15張票券！", cy=0.32, cx=0.50),
        OcrItem("關閉", cy=0.68, cx=0.50),
        OcrItem("數獨達人", cy=0.85, cx=0.50),
        OcrItem("接龍高手", cy=0.92, cx=0.50),
    ]

    close_btn = simulate_find_center_reward_modal_close_button(bitmap, modal_and_game_items)
    assert close_btn is not None, "存在明確領獎標題時，00A 豁免必須生效，回傳關閉按鈕"
    assert close_btn[0] == "領獎完成[關閉]OCR"
    assert close_btn[1].centerY() == int(0.68 * 2400)

    is_main_visible = simulate_is_line_walk_main_screen_visible(modal_and_game_items, is_dimmed_modal=True)
    assert is_main_visible is False, "存在領獎彈窗時，主畫面判定必須回傳 False，引導執行關閉彈窗"


def test_m1_f1_game_tab_00a_exemption_multi_reward_variants():
    """
    驗證不同領獎標題與按鈕文字（獲得10張、60枚金幣、OK、確認）均能正確觸發 00A 豁免。
    """
    bitmap = MockBitmap()
    variants = [
        ("獲得10張票券！", "確認"),
        ("獲得15張票券!", "確定"),
        ("獲得60枚金幣！", "收下"),
        ("100枚金幣!", "OK"),
    ]
    for title_text, btn_text in variants:
        items = [
            OcrItem("遊戲", cy=0.12),
            OcrItem(title_text, cy=0.30, cx=0.50),
            OcrItem(btn_text, cy=0.70, cx=0.50),
            OcrItem("開始遊玩", cy=0.88, cx=0.50),
        ]
        close_btn = simulate_find_center_reward_modal_close_button(bitmap, items)
        assert close_btn is not None, f"標題 {title_text} 與按鈕 {btn_text} 必須成功辨識關閉按鈕"
        is_main_visible = simulate_is_line_walk_main_screen_visible(items, is_dimmed_modal=True)
        assert is_main_visible is False, f"標題 {title_text} 必須判定不在主畫面"
