# -*- coding: utf-8 -*-
"""
對抗性驗證測試套件：Milestone 2 螢幕適配與廣告略過經驗實測
檔案路徑：tests_empirical/test_challenger_m2_adversarial_m2_screen_adaptation.py

驗證範圍：
1. 對抗性場景一：Galaxy Z Flip5 SM-F731N (22:9) 略過按鈕 (426, 32) 判定與 momo 促銷標籤過濾性檢驗。
2. 對抗性場景二：廣告超時狀態下 getNextFallbackTarget 盲點序列杜絕左上角熱區實測。
3. 對抗性場景三：跨長寬比 (22:9, 20:9, 16:9, 平板 16:10) 底部導覽列錨點與四大分頁辨識穩定性。
"""

import math
import os
from pathlib import Path
import pytest
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = REPO_ROOT / "app/src/main/java/com/linewalk/autoad"
FRAMES_DIR = REPO_ROOT / ".agents/teamwork/explorer_survey_2/frames"


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


def run_kotlin_fast_forward_skip_algorithm(img, left_limit_ratio=0.72, top_limit_ratio=0.015):
    """
    精確移植 TemplateMatcher.kt 中 detectCornerFastForwardSkip 演算法。
    """
    sw, sh = img.size
    top_limit_y = max(int(sh * top_limit_ratio), 20)
    bottom_limit_y = int(sh * 0.115)
    left_limit_x = int(sw * left_limit_ratio)
    right_limit_x = int(sw * 0.965)

    r_inner = max(4, int(sw * 0.024))
    r_outer = max(7, int(sw * 0.042))
    step = max(2, sw // 200)

    zw = right_limit_x - left_limit_x
    zh = bottom_limit_y - top_limit_y
    if zw <= r_outer * 2 or zh <= r_outer * 2:
        return None

    crop = img.crop((left_limit_x, top_limit_y, right_limit_x, bottom_limit_y)).convert("RGB")
    crop_pixels = list(crop.getdata())

    def lum(x, y):
        r, g, b = crop_pixels[y * zw + x]
        return 0.299 * r + 0.587 * g + 0.114 * b

    best_score = 0.0
    best_candidate = None

    cy = r_inner
    while cy <= zh - r_inner:
        cx = r_inner
        while cx <= zw - r_inner:
            abs_x = left_limit_x + cx
            abs_y = top_limit_y + cy

            ring_samples = []
            ring_sum = 0.0
            ring_max = 0.0
            ring_min = 255.0
            for i in range(12):
                angle = i * (math.pi / 6.0)
                px = max(0, min(zw - 1, int(cx + r_outer * math.cos(angle))))
                py = max(0, min(zh - 1, int(cy + r_outer * math.sin(angle))))
                l = lum(px, py)
                ring_samples.append(l)
                ring_sum += l
                if l > ring_max:
                    ring_max = l
                if l < ring_min:
                    ring_min = l
            ring_mean = ring_sum / 12.0
            ring_diff = ring_max - ring_min

            # 圓形對稱性與均勻度檢驗：排除長條狀促銷橫幅
            max_sym_diff = max(abs(ring_samples[i] - ring_samples[i + 6]) for i in range(6))
            horiz_mean = (ring_samples[0] + ring_samples[6]) / 2.0
            vert_mean = (ring_samples[3] + ring_samples[9]) / 2.0
            hv_diff = abs(horiz_mean - vert_mean)

            is_symmetric = (ring_diff < 35.0) and (max_sym_diff < 30.0) and (hv_diff < 20.0)

            if is_symmetric:
                center_l = lum(cx, cy)
                inner_max = 0.0
                inner_min = 255.0
                dark_inner_count = 0
                total_inner = 0

                dy = -r_inner
                while dy <= r_inner:
                    dx = -r_inner
                    while dx <= r_inner:
                        if dx * dx + dy * dy <= r_inner * r_inner:
                            px = max(0, min(zw - 1, cx + dx))
                            py = max(0, min(zh - 1, cy + dy))
                            l = lum(px, py)
                            total_inner += 1
                            if l < 235.0:
                                dark_inner_count += 1
                            if l > inner_max:
                                inner_max = l
                            if l < inner_min:
                                inner_min = l
                        dx += 2
                    dy += 2

                # 模式 A：深色圓形底圖內嵌高亮白色圖示 (如黑色半透明圓鈕)
                if ring_mean < 115.0 and ring_max < 135.0 and (center_l - ring_mean > 35.0 or center_l > 150.0):
                    contrast = inner_max - ring_mean
                    if inner_max > 175.0 and contrast > 85.0:
                        if contrast > best_score:
                            best_score = contrast
                            box_radius = r_outer + 4
                            best_candidate = {
                                "abs_x": abs_x,
                                "abs_y": abs_y,
                                "contrast": contrast,
                                "score": min(0.99, max(0.85, contrast / 180.0)),
                                "bounds": Rect(abs_x - box_radius, abs_y - box_radius, abs_x + box_radius, abs_y + box_radius),
                                "ring_mean": ring_mean,
                                "inner_max": inner_max,
                            }

                # 模式 B：淺色狀態列或白色頂部區域上的深色/高反差略過按鈕 (如 Galaxy Z Flip5 等)
                if ring_mean > 200.0 and (ring_mean - center_l > 35.0 or center_l < 200.0):
                    contrast = ring_mean - inner_min
                    if inner_min < 160.0 and contrast > 85.0:
                        fill_ratio = dark_inner_count / max(1, total_inner)
                        quality_score = contrast * (0.5 + 0.5 * fill_ratio)
                        if quality_score > best_score:
                            best_score = quality_score
                            box_radius = r_outer + 4
                            best_candidate = {
                                "abs_x": abs_x,
                                "abs_y": abs_y,
                                "contrast": contrast,
                                "score": min(0.99, max(0.85, contrast / 180.0)),
                                "bounds": Rect(abs_x - box_radius, abs_y - box_radius, abs_x + box_radius, abs_y + box_radius),
                                "ring_mean": ring_mean,
                                "inner_min": inner_min,
                            }

            cx += step
        cy += step

    return best_candidate


# ==============================================================================
# 對抗性挑戰一：略過按鈕判定與 momo 促銷標籤過濾性實測
# ==============================================================================

class TestChallengerFastForwardSkipAdversarial:
    """實證檢驗 22:9 螢幕 Meta 廣告略過按鈕能否命中，以及促銷圖文是否被過濾。"""

    def test_adv_frame25_momo_tag_false_positive_reproduction(self):
        """
        實證檢驗：在真實錄影影格 frame_25.jpg 上，
        修復後的 detectCornerFastForwardSkip 演算法精確鎖定真實略過按鈕 (426, 32)，
        且堅決過濾 momo 促銷標籤 (407, 82) 與 (462, 81)！
        """
        frame_path = FRAMES_DIR / "frame_25.jpg"
        assert frame_path.exists(), f"影格檔案必須存在: {frame_path}"

        img = Image.open(frame_path)
        result = run_kotlin_fast_forward_skip_algorithm(img, left_limit_ratio=0.72, top_limit_ratio=0.015)

        # 斷言演算法回傳了候選結果 (非 None)
        assert result is not None, "演算法在 frame_25 上產生了命中"

        # 檢驗回傳的座標：精確命中略過按鈕 (426, 32)，而非促銷標籤 (407, 82)
        hit_x = result["abs_x"]
        hit_y = result["abs_y"]

        # 驗證促銷文案標籤區間 (y in 75..95, x in 390..470) 堅決被過濾
        is_hit_momo_promo = (390 <= hit_x <= 470) and (75 <= hit_y <= 95)
        assert is_hit_momo_promo is False, f"實證發現：演算法誤命中促銷標籤 ({hit_x}, {hit_y})"

        # 檢驗真實略過按鈕 (426, 32) 成功命中，誤差 <= 8px
        is_hit_real_button = (abs(hit_x - 426) <= 8) and (abs(hit_y - 32) <= 8)
        assert is_hit_real_button is True, f"實證發現：真實略過按鈕 (426, 32) 未被精確命中 (實際座標: {hit_x}, {hit_y})"

    def test_adv_real_skip_button_ring_rejection_root_cause(self):
        """
        自適應雙向對比實證分析：驗證 (426, 32) 處於白底狀態列區域 (ringMean ~ 254.6)，
        修復後之演算法支援模式 B (ringMean > 200f)，成功自適應識別白底上的略過按鈕。
        """
        frame_path = FRAMES_DIR / "frame_25.jpg"
        img = Image.open(frame_path)
        sw, sh = img.size

        r_outer = max(7, int(sw * 0.042))
        cx, cy = 426, 32

        ring_sum = 0.0
        for i in range(12):
            angle = i * (math.pi / 6.0)
            px = max(0, min(sw - 1, int(cx + r_outer * math.cos(angle))))
            py = max(0, min(sh - 1, int(cy + r_outer * math.sin(angle))))
            r, g, b = img.getpixel((px, py))
            l = 0.299 * r + 0.587 * g + 0.114 * b
            ring_sum += l
        ring_mean = ring_sum / 12.0

        # 外環亮度實測高達 250+ (純白背景)
        assert ring_mean > 240.0, f"按鈕位於白底邊界，外環亮度實測為 {ring_mean} (預期 > 240)"
        
        # 驗證演算法成功於白底狀態列模式 (Mode B: ringMean > 200f) 下辨識出該按鈕
        result = run_kotlin_fast_forward_skip_algorithm(img, left_limit_ratio=0.72, top_limit_ratio=0.015)
        assert result is not None, "演算法成功識別白底上的略過按鈕"
        assert abs(result["abs_x"] - 426) <= 8, f"X 座標誤差應 <= 8px (實測: {result['abs_x']})"
        assert abs(result["abs_y"] - 32) <= 8, f"Y 座標誤差應 <= 8px (實測: {result['abs_y']})"


# ==============================================================================
# 對抗性挑戰二：廣告備援盲點序列安全實測 (杜絕開啟三星瀏覽器)
# ==============================================================================

class TestChallengerAdCloserFallbackSecurity:
    """實證檢驗 getNextFallbackTarget 連續 100 次循環與跨比例是否絕不觸發左上角危險熱區。"""

    def simulate_get_next_fallback_target(self, cycle, sw, sh):
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
        return targets[cycle % len(targets)]

    @pytest.mark.parametrize("sw,sh,desc", [
        (1080, 2640, "22:9 Galaxy Z Flip5"),
        (1080, 2400, "20:9 SM-S7210"),
        (1080, 1920, "16:9 標準手機"),
        (1600, 2560, "16:10 平板直向"),
        (1440, 3200, "20:9 2K 高解析度"),
        (720, 1600, "20:9 720p 經濟型"),
    ])
    def test_adv_fallback_targets_never_contain_left_top_danger_zone(self, sw, sh, desc):
        """連續 100 次調用，斷言 100% 盲點絕不座落於 X < 0.5f 且 Y < 0.2f 之左上角危險熱區。"""
        for cycle in range(100):
            label, rect = self.simulate_get_next_fallback_target(cycle, sw, sh)
            norm_cx = rect.centerX() / float(sw)
            norm_cy = rect.centerY() / float(sh)

            # 嚴格禁止左上半部盲點
            assert not (norm_cx < 0.50 and norm_cy < 0.20), (
                f"[{desc}] 第 {cycle} 次盲點 [{label}] 座落於左上角熱區 (cx={norm_cx:.3f}, cy={norm_cy:.3f})，"
                "極可能觸發廣告主專頁頭像開啟三星瀏覽器！"
            )

            # 所有盲點水平座標必須靠右 (X >= 0.86w)
            assert rect.left >= int(sw * 0.86), f"[{desc}] 盲點 [{label}] 左界小於 0.86w"

    def test_adv_ast_ad_closer_removed_left_top_from_fallback_targets(self):
        """靜態語意契約斷言：確認 AdCloserHandler.kt 之 getNextFallbackTarget 實體已移除左上角熱區加入。"""
        src = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")
        # 尋找 getNextFallbackTarget 函式本體
        start_idx = src.find("fun getNextFallbackTarget")
        assert start_idx != -1, "必須包含 getNextFallbackTarget 函式"
        end_idx = src.find("private suspend fun recordAndClick", start_idx)
        func_body = src[start_idx:end_idx]

        # 斷言函式內部無 targets.add("螢幕左上角熱區"...)
        assert 'targets.add("螢幕左上角熱區"' not in func_body, (
            "getNextFallbackTarget 內部仍含有 targets.add(\"螢幕左上角熱區\"...)"
        )


# ==============================================================================
# 對抗性挑戰三：跨螢幕比例 (22:9, 20:9, 16:9, 平板) 底部導覽列與四大分頁辨識穩定性
# ==============================================================================

class TestChallengerAspectAdaptationStability:
    """實證檢驗底部導覽列自適應錨定與四大分頁象限之幾何抗干擾能力。"""

    def simulate_get_bottom_friends_tab_rect(self, sw, sh, ocr_items=None):
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

    def simulate_get_tab_rect(self, tab, sw, sh, ocr_items):
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

    @pytest.mark.parametrize("sw,sh,expected_ratio,desc", [
        (1080, 2640, 0.945, "22:9 Galaxy Z Flip5 (2.44f >= 2.2f)"),
        (1080, 2400, 0.945, "20:9 SM-S7210 (2.22f >= 2.2f)"),
        (1080, 1920, 0.910, "16:9 標準手機 (1.78f < 2.2f)"),
        (1600, 2560, 0.910, "16:10 平板直向 (1.60f < 2.2f)"),
    ])
    def test_adv_bottom_nav_aspect_adaptation_precision(self, sw, sh, expected_ratio, desc):
        """驗證不同長寬比下備援 Y 錨定點精確度"""
        rect = self.simulate_get_bottom_friends_tab_rect(sw, sh, [])
        actual_ratio = rect.centerY() / float(sh)
        assert abs(actual_ratio - expected_ratio) < 0.005, (
            f"[{desc}] 底部導覽列備援 Y 軸比率為 {actual_ratio:.4f}，非預期之 {expected_ratio}"
        )

    def test_adv_four_tabs_adversarial_promo_and_banner_rejection(self):
        """對抗性 OCR 干擾注入：確認宣傳橫幅長文字與非頂部區域文字 100% 被過濾。"""
        sw, sh = 1080, 2640
        adversarial_items = [
            # 干擾項 1: 包含「遊戲」但為 10 字長廣告詞，位於 0.12f
            OcrItem("限時熱門遊戲等你來玩", cy=0.12, cx=0.50, sw=sw, sh=sh),
            # 干擾項 2: 包含「時間」但為 10 字長宣傳詞，位於 0.10f
            OcrItem("倒數時間結束即刻領取", cy=0.10, cx=0.50, sw=sw, sh=sh),
            # 干擾項 3: 包含「步數」但長度 5 字
            OcrItem("今日步數統計", cy=0.11, cx=0.50, sw=sw, sh=sh),
            # 干擾項 4: 包含「遊戲」且長度 <= 4，但位於下方活動區 (cy=0.28f)
            OcrItem("小遊戲", cy=0.28, cx=0.50, sw=sw, sh=sh),
            # 正確項目：僅「首頁」出現在頂部
            OcrItem("首頁", cy=0.11, cx=0.125, sw=sw, sh=sh),
        ]

        # 1. 檢驗動態 Y 錨定：只採計「首頁」(cy=0.11)，未受干擾項 1~4 拉偏
        rect_move = self.simulate_get_tab_rect(MainTab.MOVE, sw, sh, adversarial_items)
        assert abs(rect_move.centerY() / float(sh) - 0.11) < 0.02, "動態 Y 錨定被干擾文字拉偏"

        # 2. 檢驗「遊戲」分頁：干擾項「限時熱門遊戲等你來玩」與「小遊戲」被過濾，
        # 回退至標準遊戲象限 0.875f，而非被拉往中央 0.50f
        rect_game = self.simulate_get_tab_rect(MainTab.GAME, sw, sh, adversarial_items)
        assert abs(rect_game.centerX() / float(sw) - 0.875) < 0.02, (
            "遊戲分頁未能排除長廣告文案干擾，水平中心被拉離 0.875f 象限！"
        )
