import pytest
import os
import re

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
    def __init__(self, text, bounds=None, cy=0.5, cx=0.5, sw=1080, sh=2400):
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

    def getPixel(self, x, y):
        return 0xFF000000


def simulate_current_find_center_reward_modal_close_button(bitmap, items):
    """
    精確模擬目前 OcrRecognizer.kt (行 183-370) 之 findCenterRewardModalCloseButton 實作。
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

    # 00A. 嚴格一票否決：若畫面為【遊戲】分頁（街機遊戲畫面、遊戲挑戰清單）
    # 目前現狀：無條件一票否決，未受 hasExplicitTicketOrCoinModalTitle 保護
    is_game_tab_screen = any(
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

    # 00B. 嚴格一票否決：若處於抽抽樂中獎結果畫面或抽抽樂主面板
    has_explicit_ticket_or_coin_modal_title = any(
        0.20 <= (item.bounds.centerY() / float(sh)) <= 0.42 and any(
            kw in item.text.replace(" ", "")
            for kw in ["張票券！", "張票券!", "獲得15張", "獲得10張", "枚金幣！", "枚金幣!"]
        )
        for item in items
    )
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


def simulate_current_is_line_walk_main_screen_visible(screen_bitmap, items, is_dimmed_center_modal_open_val=False):
    """
    精確模擬目前 OcrRecognizer.kt (行 780-915) 之 isLineWalkMainScreenVisible 實作。
    """
    sh = screen_bitmap.height
    sw = screen_bitmap.width

    # 1. 遊戲分頁一票否決
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

    modal_close_btn = simulate_current_find_center_reward_modal_close_button(screen_bitmap, items)

    if ((not is_game_tab_screen and (modal_close_btn is not None or is_dimmed_center_modal_open_val)) or
        (is_game_tab_screen and modal_close_btn is not None)
    ):
        return False

    # 4. 主畫面特徵判定
    has_top_tabs = False
    has_game_page = False
    has_upper_stage = False
    has_top_coins = False
    has_bottom_nav = False
    top_tab_count = 0

    for item in items:
        t = item.text.replace(" ", "")
        cy = item.bounds.centerY() / float(sh)

        if 0.08 <= cy <= 0.18 and len(t) <= 3 and t in ["首頁", "步數", "時間", "遊戲", "移動"]:
            top_tab_count += 1

        if any(kw in t for kw in ["LINEPOINTS", "簡單賺金幣", "秘寶挖挖哇", "數獨達人", "接龍高手", "玩LINE"]):
            has_game_page = True

        if cy < 0.58 and any(kw in t for kw in ["距離下次", "今天的步數", "獲得票券", "獲得金幣", "達成條件", "速度"]):
            has_upper_stage = True

        if cy < 0.10 and any(c.isdigit() for c in t):
            has_top_coins = True

        if cy > 0.85 and (
            t == "集點" or
            ("好友" in t and "邀請" not in t and "被" not in t and "名單" not in t and len(t) <= 4) or
            ("使用" in t and "條款" not in t and len(t) <= 4) or
            ("精選" in t and len(t) <= 4)
        ):
            has_bottom_nav = True

    has_top_tabs = (top_tab_count >= 2)
    return has_top_tabs or has_game_page or (has_bottom_nav and (top_tab_count >= 1 or has_upper_stage)) or (has_top_coins and has_upper_stage)


def test_f1_scenario_1_game_tab_not_dimmed_vetoed():
    """
    實證驗證場景 1：遊戲分頁暗色邊框與數獨/接龍白色卡片，在無關閉按鈕時 100% 回傳 true。
    """
    bitmap = MockBitmap()
    game_tab_items = [
        OcrItem("遊戲", cy=0.12),
        OcrItem("LINE POINTS", cy=0.15),
        OcrItem("秘寶挖挖哇", cy=0.40),
        OcrItem("數獨達人", cy=0.60),
        OcrItem("接龍高手", cy=0.75),
        OcrItem("開始遊玩", cy=0.82),
        OcrItem("集點", cy=0.92),
    ]
    # 即便街機暗色邊框觸發 is_dimmed_center_modal_open_val=True
    is_visible = simulate_current_is_line_walk_main_screen_visible(
        bitmap, game_tab_items, is_dimmed_center_modal_open_val=True
    )
    assert is_visible is True, "無關閉按鈕時，遊戲分頁必須回傳 true，不可被 isDimmedCenterModalOpen 否決"


def test_f1_scenario_2_game_tab_real_modal_defect_detection():
    """
    實證驗證場景 2：遊戲分頁中真正跳出中央領獎彈窗（包含「關閉」按鈕與獎勵文字「獲得15張票券！」）。
    檢驗目前代碼是否存在缺陷：
    在 OcrRecognizer.kt 行 201-215 中，isGameTabScreen 未被 !hasExplicitTicketOrCoinModalTitle 保護，
    導致即便畫面上有明確的「獲得15張票券！」與「關閉」按鈕，findCenterRewardModalCloseButton 依然回傳 null，
    使得 isLineWalkMainScreenVisible 誤判為 true（死鎖在主畫面），無法關閉彈窗。
    """
    bitmap = MockBitmap()
    modal_and_game_items = [
        OcrItem("遊戲", cy=0.12),
        OcrItem("獲得15張票券！", cy=0.32, cx=0.50),
        OcrItem("關閉", cy=0.68, cx=0.50),
        OcrItem("數獨達人", cy=0.85, cx=0.50),
    ]

    close_btn = simulate_current_find_center_reward_modal_close_button(bitmap, modal_and_game_items)
    is_visible = simulate_current_is_line_walk_main_screen_visible(
        bitmap, modal_and_game_items, is_dimmed_center_modal_open_val=True
    )

    # 檢驗缺陷行為：
    # 目前代碼中 close_btn 為 None，is_visible 為 True
    # 這是過度一票否決造成的嚴重缺陷！
    has_defect = (close_btn is None) and (is_visible is True)
    assert has_defect, "實證重現缺陷：當遊戲分頁跳出中央領獎彈窗時，findCenterRewardModalCloseButton 誤回傳 None 且 isLineWalkMainScreenVisible 誤回傳 True！"


def test_f1_scenario_2_fixed_specification_oracle():
    """
    實證驗證預期規範（修復後之預期正確行為）：
    當修正 00A 為受 !hasExplicitTicketOrCoinModalTitle 保護後，
    close_btn 必須成功辨識，且 isLineWalkMainScreenVisible 必須回傳 False。
    """
    def fixed_find_center_reward_modal_close_button(bitmap, items):
        sw = bitmap.width
        sh = bitmap.height

        # 領獎彈窗標題優先判定
        has_explicit_ticket_or_coin_modal_title = any(
            0.20 <= (item.bounds.centerY() / float(sh)) <= 0.42 and any(
                kw in item.text.replace(" ", "")
                for kw in ["張票券！", "張票券!", "獲得15張", "獲得10張", "枚金幣！", "枚金幣!"]
            )
            for item in items
        )

        # 00A. 嚴格一票否決：僅在無明確領獎標題時方一票否決
        is_game_tab_screen = (not has_explicit_ticket_or_coin_modal_title) and any(
            any(
                kw in item.text.replace(" ", "")
                for kw in [
                    "秘寶挖挖哇", "數獨達人", "接龍高手", "POINTSGAME",
                    "簡單賺金幣", "輕鬆賺", "開始遊玩",
                ]
            )
            for item in items
        )
        if is_game_tab_screen:
            return None

        for item in items:
            cx = item.bounds.centerX()
            cy = item.bounds.centerY()
            norm_y = cy / float(sh)
            norm_x = cx / float(sw)
            if not (0.60 <= norm_y <= 0.78 and 0.20 <= norm_x <= 0.80):
                continue
            t = item.text.strip()
            is_chinese_close = any(kw in t for kw in ["關閉", "確認", "確定", "收下"])
            if is_chinese_close and len(t) <= 6:
                return ("領獎完成[關閉]OCR", item.bounds)
        return None

    def fixed_is_line_walk_main_screen_visible(screen_bitmap, items, is_dimmed_val=False):
        is_game_tab_screen = any(
            any(
                kw in item.text.replace(" ", "")
                for kw in ["秘寶挖挖哇", "數獨達人", "接龍高手", "POINTSGAME", "LINEPOINTS", "簡單賺金幣", "輕鬆賺", "開始遊玩", "玩LINE"]
            )
            for item in items
        )
        modal_close_btn = fixed_find_center_reward_modal_close_button(screen_bitmap, items)
        if ((not is_game_tab_screen and (modal_close_btn is not None or is_dimmed_val)) or
            (is_game_tab_screen and modal_close_btn is not None)
        ):
            return False
        return True

    bitmap = MockBitmap()
    modal_and_game_items = [
        OcrItem("遊戲", cy=0.12),
        OcrItem("獲得15張票券！", cy=0.32, cx=0.50),
        OcrItem("關閉", cy=0.68, cx=0.50),
        OcrItem("數獨達人", cy=0.85, cx=0.50),
    ]

    close_btn = fixed_find_center_reward_modal_close_button(bitmap, modal_and_game_items)
    is_visible = fixed_is_line_walk_main_screen_visible(bitmap, modal_and_game_items, is_dimmed_val=True)

    assert close_btn is not None, "正確規格下必須找到關閉按鈕"
    assert is_visible is False, "正確規格下必須判定不在主畫面"
