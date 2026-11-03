import os
from pathlib import Path
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = REPO_ROOT / "app/src/main/java/com/linewalk/autoad"


class Rect:
    def __init__(self, left, top, right, bottom):
        self.left = int(left)
        self.top = int(top)
        self.right = int(right)
        self.bottom = int(bottom)

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


class MainTab:
    MOVE = "MOVE"
    STEP = "STEP"
    TIME = "TIME"
    GAME = "GAME"

    @staticmethod
    def get_x_ratio(tab):
        return {
            MainTab.MOVE: 0.125,
            MainTab.STEP: 0.375,
            MainTab.TIME: 0.625,
            MainTab.GAME: 0.875,
        }[tab]


# ==============================================================================
# Feature F3: 22:9 螢幕 Meta 廣告快轉略過按鈕搜尋窗適配 (TemplateMatcher.kt)
# ==============================================================================

class TestF3FastForwardSkipWindowAdaptation:
    """驗證 Feature F3: detectCornerFastForwardSkip 搜尋窗放寬與 22:9 螢幕適配。"""

    def test_f3_ast_template_matcher_window_bounds(self):
        """靜態代碼契約：確認 TemplateMatcher.kt 搜尋窗口放寬至 72%..96.5% 與 1.5%..11.5%"""
        matcher_code = (SRC_DIR / "vision/TemplateMatcher.kt").read_text(encoding="utf-8")

        assert "val topLimitY = max((sh * 0.015f).toInt(), 20)" in matcher_code
        assert "val bottomLimitY = (sh * 0.115f).toInt()" in matcher_code
        assert "val leftLimitX = (sw * 0.72f).toInt()" in matcher_code
        assert "val rightLimitX = (sw * 0.965f).toInt()" in matcher_code

    @pytest.mark.parametrize("sw,sh,desc", [
        (1080, 1920, "16:9 標準螢幕"),
        (1080, 2400, "20:9 常見長螢幕"),
        (1080, 2640, "22:9 Galaxy Z Flip5 SM-F731N"),
    ])
    def test_f3_search_window_dimensions_across_aspect_ratios(self, sw, sh, desc):
        """跨長寬比搜尋窗尺寸檢驗：確保搜尋窗範圍有效且足夠容納略過按鈕半徑"""
        top_limit_y = max(int(sh * 0.015), 20)
        bottom_limit_y = int(sh * 0.115)
        left_limit_x = int(sw * 0.72)
        right_limit_x = int(sw * 0.965)

        zw = right_limit_x - left_limit_x
        zh = bottom_limit_y - top_limit_y

        r_outer = max(7, int(sw * 0.042))
        assert zw > r_outer * 2, f"[{desc}] 搜尋窗寬度不足以容納外環半徑"
        assert zh > r_outer * 2, f"[{desc}] 搜尋窗高度不足以容納外環半徑"
        assert left_limit_x >= 0 and right_limit_x <= sw, f"[{desc}] 水平界限超出螢幕"
        assert top_limit_y >= 0 and bottom_limit_y <= sh, f"[{desc}] 垂直界限超出螢幕"

    def test_f3_galaxy_z_flip5_real_button_inclusion(self):
        """驗證 Galaxy Z Flip5 (1080x2640) 錄影中位於 (426, 32) (換算全解析度 x=0.813f, y=0.025f) 之按鈕被涵蓋"""
        sw = 1080
        sh = 2640
        btn_x = int(sw * 0.813)  # 878 px
        btn_y = int(sh * 0.025)  # 66 px

        # 舊版搜尋窗門檻 (會排除真實按鈕)
        old_left = int(sw * 0.84)
        old_top = max(int(sh * 0.045), 45)
        assert btn_x < old_left, "舊版 leftLimitX 應排除此按鈕"
        assert btn_y < old_top, "舊版 topLimitY 應排除此按鈕"

        # 新版搜尋窗門檻 (成功納入真實按鈕)
        new_left = int(sw * 0.72)
        new_top = max(int(sh * 0.015), 20)
        new_right = int(sw * 0.965)
        new_bottom = int(sh * 0.115)

        assert new_left <= btn_x <= new_right, "新版搜尋窗水平範圍必須涵蓋略過按鈕"
        assert new_top <= btn_y <= new_bottom, "新版搜尋窗垂直範圍必須涵蓋略過按鈕"

    def test_f3_empirical_frame25_real_button_match_and_momo_rejection(self):
        """實證經驗檢驗：在 Galaxy Z Flip5 真實錄影影格 frame_25.jpg 上精確命中 (426, 32) 且排除促銷標籤"""
        from PIL import Image
        from tests_empirical.test_challenger_m2_adversarial_m2_screen_adaptation import run_kotlin_fast_forward_skip_algorithm
        frame_path = REPO_ROOT / ".agents/teamwork/explorer_survey_2/frames/frame_25.jpg"
        assert frame_path.exists(), f"影格檔案必須存在: {frame_path}"

        img = Image.open(frame_path)
        result = run_kotlin_fast_forward_skip_algorithm(img, left_limit_ratio=0.72, top_limit_ratio=0.015)
        assert result is not None, "演算法必須在 frame_25 上回傳候選略過按鈕"

        hit_x = result["abs_x"]
        hit_y = result["abs_y"]

        # 檢驗促銷文案標籤區間 (y in 75..95, x in 390..470) 堅決被過濾
        is_hit_momo_promo = (390 <= hit_x <= 470) and (75 <= hit_y <= 95)
        assert is_hit_momo_promo is False, f"演算法誤命中促銷標籤 ({hit_x}, {hit_y})"

        # 檢驗真實略過按鈕 (426, 32) 成功命中，誤差 <= 8px
        is_hit_real_button = (abs(hit_x - 426) <= 8) and (abs(hit_y - 32) <= 8)
        assert is_hit_real_button is True, f"真實略過按鈕 (426, 32) 未被精確命中 (實際座標: {hit_x}, {hit_y})"


# ==============================================================================
# Feature F4: 廣告備援盲點優先級安全重構 (AdCloserHandler.kt)
# ==============================================================================

class TestF4AdCloserFallbackRefactor:
    """驗證 Feature F4: 廣告備援盲點優先級安全重構，徹底移除左上角危險熱區。"""

    def test_f4_ast_ad_closer_fallback_contract(self):
        """靜態代碼契約：確認 AdCloserHandler.kt 包含三大安全盲點熱區且移除左上角盲點"""
        closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

        assert "螢幕右上角熱區" in closer_code
        assert "螢幕右下角熱區" in closer_code
        assert "內容右上角熱區" in closer_code
        assert "val (label, targetRect) = getNextFallbackTarget(sw, sh, ocrItems)" in closer_code

    def simulate_get_next_fallback_targets(self, sw, sh):
        """模擬 AdCloserHandler.kt 中 getNextFallbackTarget 的序列產出"""
        targets = [
            ("螢幕右上角熱區", Rect(
                int(sw * 0.86),
                max(int(sh * 0.015), 15),
                int(sw * 0.96),
                int(sh * 0.075)
            )),
            ("螢幕右下角熱區", Rect(
                int(sw * 0.86),
                int(sh * 0.89),
                int(sw * 0.96),
                int(sh * 0.95)
            )),
            ("內容右上角熱區", Rect(
                int(sw * 0.91),
                int(sh * 0.055),
                int(sw * 0.99),
                int(sh * 0.105)
            )),
        ]
        return targets

    @pytest.mark.parametrize("sw,sh,desc", [
        (1080, 1920, "16:9 標準螢幕"),
        (1080, 2400, "20:9 常見長螢幕"),
        (1080, 2640, "22:9 Galaxy Z Flip5 SM-F731N"),
    ])
    def test_f4_fallback_priority_sequence_and_danger_zone_exclusion(self, sw, sh, desc):
        """驗證備援盲點序列與座標中心點，確保 100% 避開左上角危險專頁頭像熱區"""
        targets = self.simulate_get_next_fallback_targets(sw, sh)

        assert len(targets) == 3, f"[{desc}] 盲點數量必須為 3 個安全目標"

        # 第 1 優先級：右上角 (0.91w, 0.045h)
        t1_label, t1_rect = targets[0]
        assert t1_label == "螢幕右上角熱區"
        assert abs(t1_rect.centerX() / sw - 0.91) < 0.015, f"[{desc}] 第 1 優先級 X 軸中心非 0.91w"
        assert abs(t1_rect.centerY() / sh - 0.045) < 0.015, f"[{desc}] 第 1 優先級 Y 軸中心非 0.045h"

        # 第 2 優先級：右下角 (0.91w, 0.92h)
        t2_label, t2_rect = targets[1]
        assert t2_label == "螢幕右下角熱區"
        assert abs(t2_rect.centerX() / sw - 0.91) < 0.015, f"[{desc}] 第 2 優先級 X 軸中心非 0.91w"
        assert abs(t2_rect.centerY() / sh - 0.92) < 0.015, f"[{desc}] 第 2 優先級 Y 軸中心非 0.92h"

        # 第 3 優先級：右上角更偏角 (0.95w, 0.08h)
        t3_label, t3_rect = targets[2]
        assert t3_label == "內容右上角熱區"
        assert abs(t3_rect.centerX() / sw - 0.95) < 0.015, f"[{desc}] 第 3 優先級 X 軸中心非 0.95w"
        assert abs(t3_rect.centerY() / sh - 0.08) < 0.015, f"[{desc}] 第 3 優先級 Y 軸中心非 0.08h"

        # 嚴格禁止任何盲點接觸左上角危險熱區 (x in 0.035..0.140, y in 0.048..0.095)
        for label, rect in targets:
            assert rect.left >= int(sw * 0.50), f"[{desc}] {label} 絕不可落在螢幕左半側"
            assert not (rect.left <= int(sw * 0.140) and rect.top <= int(sh * 0.095)), \
                f"[{desc}] {label} 誤觸發左上角廣告主專頁頭像"


# ==============================================================================
# Feature F5: 22:9 超長螢幕底部導覽列動態 Y 座標適配 (FriendGiftTask.kt)
# ==============================================================================

class TestF5BottomNavDynamicAspectAdaptation:
    """驗證 Feature F5: FriendGiftTask.kt 底部導覽列依長寬比動態適配 Y 座標。"""

    def test_f5_ast_friend_gift_task_dynamic_aspect(self):
        """靜態代碼契約：確認 FriendGiftTask.kt 包含 aspect >= 2.2f 與 0.945f 動態錨定"""
        task_code = (SRC_DIR / "tasks/FriendGiftTask.kt").read_text(encoding="utf-8")

        assert "val aspect = sh.toFloat() / sw.toFloat()" in task_code
        assert "aspect >= 2.2f" in task_code
        assert "(sh * 0.945f).toInt()" in task_code
        assert "(sh * 0.910f).toInt()" in task_code

    def simulate_get_bottom_friends_tab_rect(self, sw, sh, ocr_items=None):
        """精確模擬 FriendGiftTask.kt 中 getBottomFriendsTabRect 之動態計算邏輯"""
        ocr_items = ocr_items or []
        ocr_match = next((
            item for item in ocr_items
            if (item.bounds.centerY() / float(sh)) > 0.85
            and 0.20 <= (item.bounds.centerX() / float(sw)) <= 0.55
            and "好友" in item.text.replace(" ", "")
            and "邀請" not in item.text
            and "被" not in item.text
            and "名單" not in item.text
            and len(item.text.replace(" ", "")) <= 4
        ), None)
        if ocr_match:
            return ocr_match.bounds

        target_x = int(sw * 0.375)
        aspect = float(sh) / float(sw)
        target_y = int(sh * 0.945) if aspect >= 2.2 else int(sh * 0.910)
        half_w = int(sw * 0.08)
        half_h = max(24, int(sh * 0.020))
        return Rect(target_x - half_w, target_y - half_h, target_x + half_w, target_y + half_h)

    def simulate_get_bottom_main_tab_rect(self, sw, sh, ocr_items=None):
        """精確模擬 FriendGiftTask.kt 中 getBottomMainTabRect 之動態計算邏輯"""
        ocr_items = ocr_items or []
        ocr_match = next((
            item for item in ocr_items
            if (item.bounds.centerY() / float(sh)) > 0.85
            and (item.bounds.centerX() / float(sw)) < 0.30
            and ("集點" in item.text.replace(" ", "") and len(item.text.replace(" ", "")) <= 4)
        ), None)
        if ocr_match:
            return ocr_match.bounds

        target_x = int(sw * 0.125)
        aspect = float(sh) / float(sw)
        target_y = int(sh * 0.945) if aspect >= 2.2 else int(sh * 0.910)
        half_w = int(sw * 0.08)
        half_h = max(24, int(sh * 0.020))
        return Rect(target_x - half_w, target_y - half_h, target_x + half_w, target_y + half_h)

    def test_f5_standard_16_9_aspect_ratio_maintains_0_910(self):
        """16:9 (1080x1920, aspect=1.778 < 2.2f) 維持 Y=0.910f 基準"""
        sw, sh = 1080, 1920
        rect_friends = self.simulate_get_bottom_friends_tab_rect(sw, sh)
        rect_main = self.simulate_get_bottom_main_tab_rect(sw, sh)

        expected_y = int(sh * 0.910)
        assert rect_friends.centerY() == expected_y
        assert rect_main.centerY() == expected_y
        assert rect_friends.centerX() == int(sw * 0.375)
        assert rect_main.centerX() == int(sw * 0.125)

    def test_f5_tall_20_9_aspect_ratio_adapts_to_0_945(self):
        """20:9 (1080x2400, aspect=2.222 >= 2.2f) 自動適配至 Y=0.945f 避開上方橫幅"""
        sw, sh = 1080, 2400
        rect_friends = self.simulate_get_bottom_friends_tab_rect(sw, sh)
        rect_main = self.simulate_get_bottom_main_tab_rect(sw, sh)

        expected_y = int(sh * 0.945)
        assert rect_friends.centerY() == expected_y
        assert rect_main.centerY() == expected_y

    def test_f5_galaxy_z_flip5_22_9_avoids_banner_ad(self):
        """22:9 (1080x2640, aspect=2.444 >= 2.2f) 適配至 Y=0.945f，比舊版 0.910f 降低 92px 避開廣告橫幅"""
        sw, sh = 1080, 2640
        rect_friends = self.simulate_get_bottom_friends_tab_rect(sw, sh)

        old_y = int(sh * 0.910)  # 2402 px
        new_y = int(sh * 0.945)  # 2494 px
        diff_px = new_y - old_y

        assert rect_friends.centerY() == new_y
        assert diff_px >= 90, f"新座標應比舊座標向下平移至少 90px (實測平移 {diff_px}px)"
        # 導覽列實際位於 0.935f..0.975f (2468..2574 px)
        assert int(sh * 0.935) <= rect_friends.centerY() <= int(sh * 0.975)


# ==============================================================================
# Feature F6: 四大分頁 directOcr 搜尋範圍與字數收斂 (AutomationEngine.kt)
# ==============================================================================

class TestF6TopTabsOcrRangeAndLengthConvergence:
    """驗證 Feature F6: getTabRect 中 matchedTabs 與 directOcr 範圍收斂至 0.07f..0.16f 且 length <= 4。"""

    def test_f6_ast_automation_engine_top_tabs_contract(self):
        """靜態代碼契約：確認 AutomationEngine.kt 包含 cy in 0.07f..0.16f && t.length <= 4"""
        ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")

        assert "cy in 0.07f..0.16f && t.length <= 4" in ae_code
        assert "MainTab.GAME -> listOf(\"遊戲\")" in ae_code
        assert "val directOcr = matchedTabs.firstOrNull" in ae_code

    def simulate_get_tab_rect(self, tab, sw, sh, ocr_items):
        """精確模擬 AutomationEngine.kt 中 getTabRect 之收斂辨識與對齊邏輯"""
        matched_tabs = [
            item for item in ocr_items
            if (0.07 <= (item.bounds.centerY() / float(sh)) <= 0.16)
            and len(item.text.replace(" ", "")) <= 4
            and any(k in item.text for k in ["首頁", "移動", "步數", "時間", "遊戲"])
        ]

        if matched_tabs:
            dynamic_y_anchor = int(sum(item.bounds.centerY() for item in matched_tabs) / len(matched_tabs))
        else:
            dynamic_y_anchor = int(sh * 0.125)

        tab_keywords = {
            MainTab.MOVE: ["首頁", "移動"],
            MainTab.STEP: ["步數"],
            MainTab.TIME: ["時間"],
            MainTab.GAME: ["遊戲"],
        }[tab]

        direct_ocr = next((
            item for item in matched_tabs
            if (0.07 <= (item.bounds.centerY() / float(sh)) <= 0.16)
            and len(item.text.replace(" ", "")) <= 4
            and any(kw in item.text.replace(" ", "") for kw in tab_keywords)
        ), None)

        target_center_x = int(sw * MainTab.get_x_ratio(tab))
        half_w = int(sw * 0.10)
        half_h = max(24, int(sh * 0.018))

        if direct_ocr is not None:
            pad_x = max(24, int(sw * 0.04))
            return Rect(
                max(0, direct_ocr.bounds.left - pad_x),
                max(0, dynamic_y_anchor - half_h),
                min(sw, direct_ocr.bounds.right + pad_x),
                min(sh, dynamic_y_anchor + half_h)
            )
        else:
            return Rect(
                max(0, target_center_x - half_w),
                max(0, dynamic_y_anchor - half_h),
                min(sw, target_center_x + half_w),
                min(sh, dynamic_y_anchor + half_h)
            )

    def test_f6_valid_tab_titles_accurately_recognized(self):
        """標準四大分頁短文字精確識別並對齊"""
        sw, sh = 1080, 2640
        items = [
            OcrItem("首頁", cy=0.10, cx=0.125, sw=sw, sh=sh),
            OcrItem("步數", cy=0.10, cx=0.375, sw=sw, sh=sh),
            OcrItem("時間", cy=0.10, cx=0.625, sw=sw, sh=sh),
            OcrItem("遊戲", cy=0.10, cx=0.875, sw=sw, sh=sh),
        ]

        for tab in [MainTab.MOVE, MainTab.STEP, MainTab.TIME, MainTab.GAME]:
            rect = self.simulate_get_tab_rect(tab, sw, sh, items)
            expected_x = int(sw * MainTab.get_x_ratio(tab))
            assert abs(rect.centerX() - expected_x) <= 24

    def test_f6_banner_and_promo_ad_text_strictly_rejected(self):
        """長廣告文案或超出垂直範圍之文字被嚴格排除，不干擾分頁基準與點擊"""
        sw, sh = 1080, 2640
        bait_items = [
            # 誘騙文案 1：包含「遊戲」但長度超過 4
            OcrItem("限時熱門遊戲等你來玩", cy=0.12, cx=0.50, sw=sw, sh=sh),
            # 誘騙文案 2：包含「時間」但長度超過 4
            OcrItem("倒數時間結束即刻領取", cy=0.11, cx=0.50, sw=sw, sh=sh),
            # 誘騙文案 3：包含「遊戲」且長度 <= 4，但位於下方廣告區塊 (cy=0.25 > 0.16)
            OcrItem("小遊戲", cy=0.25, cx=0.50, sw=sw, sh=sh),
        ]

        # 遊戲分頁若無有效短標題，應優雅降級為水平象限預設中心 (0.875f)，而非被誘騙文案拉向中心或下方
        rect_game = self.simulate_get_tab_rect(MainTab.GAME, sw, sh, bait_items)
        assert rect_game.centerX() == int(sw * 0.875)
        assert rect_game.centerY() == int(sh * 0.125)

    @pytest.mark.parametrize("sw,sh,desc", [
        (1080, 1920, "16:9 標準螢幕"),
        (1080, 2400, "20:9 常見長螢幕"),
        (1080, 2640, "22:9 Galaxy Z Flip5 SM-F731N"),
    ])
    def test_f6_horizontal_quadrants_non_overlapping_all_resolutions(self, sw, sh, desc):
        """全機型四等分象限不重疊斷言：確保任何長寬比下四大分頁熱區皆完全獨立"""
        r_move = self.simulate_get_tab_rect(MainTab.MOVE, sw, sh, [])
        r_step = self.simulate_get_tab_rect(MainTab.STEP, sw, sh, [])
        r_time = self.simulate_get_tab_rect(MainTab.TIME, sw, sh, [])
        r_game = self.simulate_get_tab_rect(MainTab.GAME, sw, sh, [])

        assert r_move.right < r_step.left, f"[{desc}] 首頁與步數熱區重疊"
        assert r_step.right < r_time.left, f"[{desc}] 步數與時間熱區重疊"
        assert r_time.right < r_game.left, f"[{desc}] 時間與遊戲熱區重疊"
        assert r_game.right <= sw, f"[{desc}] 遊戲熱區超出螢幕右邊界"
