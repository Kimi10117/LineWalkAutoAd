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


def run_find_center_reward_modal_close_button(bitmap, items):
    """
    精確依據 OcrRecognizer.kt 最新代碼實作模擬 findCenterRewardModalCloseButton。
    包含 00A 領獎標題豁免與反向一票否決。
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

    # 明確領獎標題提前計算（cy in 0.20f..0.42f）
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

    # 00C. 嚴格一票否決：每日賓果與任務視窗
    is_other_sub_dialog = (not has_explicit_ticket_or_coin_modal_title) and any(
        (0.08 <= (item.bounds.centerY() / float(sh)) <= 0.22 and ("每日賓果" in item.text.replace(" ", "") or "今日賓果" in item.text.replace(" ", "") or "任務" in item.text.replace(" ", ""))) or
        any(kw in item.text.replace(" ", "") for kw in ["距離下次抽選", "開啟賓果格", "特別任務"])
        for item in items
    )
    if is_other_sub_dialog:
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


def run_is_line_walk_main_screen_visible(bitmap, items, is_dimmed_center_modal_open_val=False):
    """
    精確依據 OcrRecognizer.kt 最新代碼實作模擬 isLineWalkMainScreenVisible。
    """
    sh = bitmap.height
    sw = bitmap.width

    is_game_tab_screen = any(
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

    modal_close_btn = run_find_center_reward_modal_close_button(bitmap, items)

    if (
        (not is_game_tab_screen and (modal_close_btn is not None or is_dimmed_center_modal_open_val))
        or (is_game_tab_screen and modal_close_btn is not None)
    ):
        return False

    return True


# -----------------------------------------------------------------------------
# 對抗性檢驗測試套件 (Empirical Adversarial Test Suite for M1 Iteration 2)
# -----------------------------------------------------------------------------

def test_adv_kotlin_source_code_contract_inspection():
    """
    對抗檢驗 1：靜態源碼契約深度檢查，直接剖析 OcrRecognizer.kt 實際程式碼結構。
    確認 Worker 3 的修復確實在源碼層面將 hasExplicitTicketOrCoinModalTitle 置於 00A 之前。
    """
    ocr_file = os.path.join(SRC_DIR, "vision", "OcrRecognizer.kt")
    assert os.path.exists(ocr_file), "OcrRecognizer.kt 檔案必須存在"

    with open(ocr_file, "r", encoding="utf-8") as f:
        code = f.read()

    # 1. 檢查 hasExplicitTicketOrCoinModalTitle 出現在 00A 之前
    idx_has_title = code.find("val hasExplicitTicketOrCoinModalTitle = items.any")
    idx_rule_00a = code.find("// 00A. 嚴格一票否決")
    idx_game_tab_in_00a = code.find("val isGameTabScreen = !hasExplicitTicketOrCoinModalTitle && items.any")

    assert idx_has_title != -1, "必須在 OcrRecognizer.kt 定義 hasExplicitTicketOrCoinModalTitle"
    assert idx_rule_00a != -1, "必須存在 00A 規則註解"
    assert idx_game_tab_in_00a != -1, "00A 必須使用 !hasExplicitTicketOrCoinModalTitle 保護 isGameTabScreen"
    assert idx_has_title < idx_rule_00a, "hasExplicitTicketOrCoinModalTitle 必須在 00A 規則之前計算"
    assert idx_has_title < idx_game_tab_in_00a, "hasExplicitTicketOrCoinModalTitle 必須在 isGameTabScreen 定義之前"

    # 2. 檢查 isLineWalkMainScreenVisible 具備遊戲分頁專屬包裹式判定
    idx_main_screen = code.find("fun isLineWalkMainScreenVisible")
    assert idx_main_screen != -1, "isLineWalkMainScreenVisible 函數必須存在"
    main_code = code[idx_main_screen:]

    assert "!isGameTabScreen && (findCenterRewardModalCloseButton(screenBitmap, items) != null || isDimmedCenterModalOpen(screenBitmap))" in main_code
    assert "isGameTabScreen && findCenterRewardModalCloseButton(screenBitmap, items) != null" in main_code


def test_adv_reverse_boundary_strict_veto_without_reward_title():
    """
    對抗檢驗 2：反向邊界嚴密性（一票否決不可退步）。
    當畫面為遊戲分頁但【無領獎標題】時：
    即使畫面上剛好在關閉熱區（y=0.68, x=0.50）出現『關閉』或『確認』或遊戲按鈕，
    00A 規則必須堅定執行一票否決，在函數開頭直接 return null，絕不可誤點遊戲卡片！
    同時 isLineWalkMainScreenVisible 必須回傳 True（主畫面有效），即便背景為深色街機。
    """
    for resolution in [(1080, 2400), (1080, 2640), (1080, 1920), (1440, 3120)]:
        sw, sh = resolution
        bitmap = MockBitmap(sw, sh)

        # 構造惡意對抗資料：遊戲分頁中，某個遊戲卡片剛好帶有「關閉」或「確認」文字且落在 y=0.68
        malicious_game_items = [
            OcrItem("遊戲", cy=0.12, sw=sw, sh=sh),
            OcrItem("LINE POINTS", cy=0.15, sw=sw, sh=sh),
            OcrItem("數獨達人", cy=0.45, sw=sw, sh=sh),
            OcrItem("關閉", cy=0.68, cx=0.50, sw=sw, sh=sh),  # 位於熱區的誘騙按鈕
            OcrItem("接龍高手", cy=0.75, sw=sw, sh=sh),
            OcrItem("開始遊玩", cy=0.85, sw=sw, sh=sh),
        ]

        close_btn = run_find_center_reward_modal_close_button(bitmap, malicious_game_items)
        assert close_btn is None, f"解析度 {resolution} 下無領獎標題時，00A 必須一票否決回傳 None，絕不誤點遊戲卡片"

        is_main = run_is_line_walk_main_screen_visible(bitmap, malicious_game_items, is_dimmed_center_modal_open_val=True)
        assert is_main is True, f"解析度 {resolution} 下無領獎標題時，主畫面判定必須回傳 True，不被遮罩阻擋"


def test_adv_modal_title_variations_stress():
    """
    對抗檢驗 3：領獎彈窗標題多樣性與 OCR 容錯壓力測試。
    測試各種票券數、金幣數、全半形驚嘆號、空格干擾、標點截斷情境：
    在遊戲分頁背景存在（含數獨達人、接龍高手）的狀況下，
    00A 豁免機制必須 100% 生效，成功抓取關閉按鈕並判定主畫面為 False。
    """
    test_titles = [
        "獲得15張票券！",
        "獲得15張票券!",
        "獲得 15 張 票券 ！",
        "獲得10張票券！",
        "獲得10張票券!",
        "獲得 10 張 票券 !",
        "獲得15張",       # 驚嘆號遺漏
        "獲得10張",       # 驚嘆號遺漏
        "獲得5張票券！",
        "獲得1張票券!",
        "獲得20張票券！",
        "獲得60枚金幣！",
        "獲得60枚金幣!",
        "獲得 60 枚 金幣 ！",
        "100枚金幣！",
        "50枚金幣!",
    ]

    button_variants = [
        "關閉",
        "確認",
        "確定",
        "收下",
        "OK",
        "[OK]",
        " OK ",
    ]

    bitmap = MockBitmap(1080, 2400)

    for i, title in enumerate(test_titles):
        btn = button_variants[i % len(button_variants)]
        items = [
            OcrItem("遊戲", cy=0.12),
            OcrItem("數獨達人", cy=0.85),
            OcrItem("接龍高手", cy=0.90),
            OcrItem(title, cy=0.32, cx=0.50),
            OcrItem(btn, cy=0.68, cx=0.50),
        ]

        close_btn = run_find_center_reward_modal_close_button(bitmap, items)
        assert close_btn is not None, f"標題 [{title}] 搭配按鈕 [{btn}] 時，必須成功豁免 00A 並識別關閉按鈕"
        assert close_btn[0] == "領獎完成[關閉]OCR"

        is_main = run_is_line_walk_main_screen_visible(bitmap, items, is_dimmed_center_modal_open_val=True)
        assert is_main is False, f"標題 [{title}] 搭配按鈕 [{btn}] 時，主畫面判定必須為 False，引導關閉彈窗"


def test_adv_title_vertical_boundary_stress():
    """
    對抗檢驗 4：領獎標題 Y 軸區間邊界 (cy in 0.20f..0.42f) 極限對抗。
    - cy = 0.19f：超出區間上方（屬於頂部分頁或頂部狀態），不應觸發彈窗豁免。
    - cy = 0.20f：臨界區間下界，必須觸發彈窗豁免。
    - cy = 0.31f：標準彈窗中心，必須觸發彈窗豁免。
    - cy = 0.42f：臨界區間上界，必須觸發彈窗豁免。
    - cy = 0.43f：超出區間下方，不應觸發彈窗豁免。
    """
    bitmap = MockBitmap(1080, 2400)

    # 1. 區間內部 (0.20f, 0.31f, 0.42f)
    for valid_cy in [0.20, 0.31, 0.42]:
        items = [
            OcrItem("遊戲", cy=0.12),
            OcrItem("數獨達人", cy=0.85),
            OcrItem("獲得15張票券！", cy=valid_cy, cx=0.50),
            OcrItem("關閉", cy=0.68, cx=0.50),
        ]
        close_btn = run_find_center_reward_modal_close_button(bitmap, items)
        assert close_btn is not None, f"cy={valid_cy} 位於有效區間內，必須成功豁免 00A"
        is_main = run_is_line_walk_main_screen_visible(bitmap, items, is_dimmed_center_modal_open_val=True)
        assert is_main is False, f"cy={valid_cy} 位於有效區間內，主畫面判定必須為 False"

    # 2. 區間外部 (0.19f, 0.43f)
    for invalid_cy in [0.19, 0.43]:
        items = [
            OcrItem("遊戲", cy=0.12),
            OcrItem("數獨達人", cy=0.85),
            OcrItem("獲得15張票券！", cy=invalid_cy, cx=0.50),
            OcrItem("關閉", cy=0.68, cx=0.50),
        ]
        close_btn = run_find_center_reward_modal_close_button(bitmap, items)
        assert close_btn is None, f"cy={invalid_cy} 超出區間，00A 否決必須依然成立回傳 None"
        is_main = run_is_line_walk_main_screen_visible(bitmap, items, is_dimmed_center_modal_open_val=True)
        assert is_main is True, f"cy={invalid_cy} 超出區間，主畫面判定必須回傳 True"


def test_adv_multi_game_keywords_concurrency_stress():
    """
    對抗檢驗 5：多重遊戲關鍵字並存極限情境。
    當畫面上同時包含全部 7+ 個遊戲關鍵字時，豁免邏輯依然能正確運作。
    """
    bitmap = MockBitmap(1080, 2400)
    all_game_kws = [
        "秘寶挖挖哇", "數獨達人", "接龍高手", "POINTSGAME",
        "簡單賺金幣", "輕鬆賺", "開始遊玩", "玩LINE", "LINEPOINTS"
    ]

    base_items = [OcrItem(kw, cy=0.50 + i * 0.04) for i, kw in enumerate(all_game_kws)]

    # 情境 A：無彈窗標題，堅決一票否決
    close_btn_a = run_find_center_reward_modal_close_button(bitmap, base_items)
    assert close_btn_a is None
    assert run_is_line_walk_main_screen_visible(bitmap, base_items, is_dimmed_center_modal_open_val=True) is True

    # 情境 B：疊加領獎彈窗，順利豁免並放行關閉
    modal_items = base_items + [
        OcrItem("獲得15張票券！", cy=0.32, cx=0.50),
        OcrItem("關閉", cy=0.68, cx=0.50),
    ]
    close_btn_b = run_find_center_reward_modal_close_button(bitmap, modal_items)
    assert close_btn_b is not None
    assert run_is_line_walk_main_screen_visible(bitmap, modal_items, is_dimmed_center_modal_open_val=True) is False


def test_adv_device_aspect_ratio_adaptability():
    """
    對抗檢驗 6：全機型螢幕長寬比適配檢驗。
    包含超長 22:9 (Galaxy Z Flip5 SM-F731N, 1080x2640)、20:9 (1080x2400)、16:9 (1080x1920)。
    驗證領獎彈窗偵測在不同解析度下的座標正規化運算均能保持 100% 精準。
    """
    device_resolutions = {
        "Galaxy_Z_Flip5_22_9": (1080, 2640),
        "Standard_Phone_20_9": (1080, 2400),
        "Legacy_Phone_16_9": (1080, 1920),
        "Tablet_16_10": (1200, 1920),
    }

    for device_name, (sw, sh) in device_resolutions.items():
        bitmap = MockBitmap(sw, sh)
        items = [
            OcrItem("遊戲", cy=0.12, sw=sw, sh=sh),
            OcrItem("數獨達人", cy=0.88, sw=sw, sh=sh),
            OcrItem("獲得15張票券！", cy=0.30, cx=0.50, sw=sw, sh=sh),
            OcrItem("關閉", cy=0.68, cx=0.50, sw=sw, sh=sh),
        ]

        close_btn = run_find_center_reward_modal_close_button(bitmap, items)
        assert close_btn is not None, f"設備 {device_name} 必須能正確識別關閉按鈕"
        assert close_btn[1].centerY() == int(0.68 * sh)

        is_main = run_is_line_walk_main_screen_visible(bitmap, items, is_dimmed_center_modal_open_val=True)
        assert is_main is False, f"設備 {device_name} 必須判定不在主畫面"
