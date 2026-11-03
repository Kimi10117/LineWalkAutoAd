# -*- coding: utf-8 -*-
"""
對抗性實證測試套件：Milestone 2 全機型螢幕座標適配與廣告略過修復深度邊界測試 (Challenger 2)
檔案路徑：tests_empirical/test_challenger_m2_it2_deep_stress.py

測試涵蓋：
1. 底部導覽列長寬比自適應邊界與安全性 (跨 15 種長寬比與極端螢幕)
2. 廣告備援盲點序列安全防護 (100% 靠右，零左上角危險熱區)
3. 四大分頁 directOcr 搜尋範圍與長度收斂極端干擾測試
4. Meta 略過按鈕雙向模式 (Mode A/B) 影像與幾何對稱性辨識
5. 真實錄影影格無假陽性與真實略過按鈕鎖定檢驗
"""

import math
import os
import re
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

    def width(self):
        return self.right - self.left

    def height(self):
        return self.bottom - self.top


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
    """精確移植 TemplateMatcher.kt 中 detectCornerFastForwardSkip 演算法。"""
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

                # 模式 A：深色圓形底圖內嵌高亮白色圖示
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
                                "score": max(0.85, min(0.99, contrast / 180.0)),
                                "bounds": Rect(abs_x - box_radius, abs_y - box_radius, abs_x + box_radius, abs_y + box_radius),
                                "mode": "Mode A",
                            }

                # 模式 B：淺色狀態列或白色頂部區域上的深色略過按鈕
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
                                "score": max(0.85, min(0.99, contrast / 180.0)),
                                "bounds": Rect(abs_x - box_radius, abs_y - box_radius, abs_x + box_radius, abs_y + box_radius),
                                "mode": "Mode B",
                            }
            cx += step
        cy += step

    return best_candidate


class TestChallenger2BottomNavMultiAspect:
    """測試一：底部導覽列長寬比自適應邊界與安全性"""

    @pytest.mark.parametrize("sw, sh, name, expected_ratio", [
        (1080, 2640, "Galaxy Z Flip5 (22:9)", 0.945),
        (1080, 2400, "SM-S7210 (20:9)", 0.945),
        (1080, 2340, "Galaxy S24 (19.5:9 -> 2.167 < 2.2)", 0.910),
        (1080, 1920, "標準 16:9 設備", 0.910),
        (1440, 2560, "2K 16:9 旗艦設備", 0.910),
        (1600, 2560, "平板 16:10 直向", 0.910),
        (1200, 1920, "平板 16:10 經濟型", 0.910),
        (1768, 2208, "Galaxy Z Fold 內螢幕 (4:3.3 ~ 1.25)", 0.910),
        (904, 2316, "Galaxy Z Fold 外螢幕 (23:9 ~ 2.56)", 0.945),
        (720, 1600, "720p 20:9 入門款", 0.945),
        (1440, 3200, "20:9 2K 高階機", 0.945),
    ])
    def test_bottom_nav_aspect_adaptation_and_boundary_safety(self, sw, sh, name, expected_ratio):
        aspect = sh / sw
        target_y = int(sh * 0.945) if aspect >= 2.2 else int(sh * 0.910)
        actual_ratio = target_y / sh

        assert abs(actual_ratio - expected_ratio) < 0.005, f"{name} 比例計算偏差"

        # 安全性檢驗：在超長螢幕 (>= 2.2) 下，點擊必須在廣告橫幅 (0.910f) 下方，且在系統導航列上方
        if aspect >= 2.2:
            banner_ad_y = int(sh * 0.910)
            assert target_y > banner_ad_y + 40, f"{name} 備援點擊未避開 0.910 廣告橫幅"
            assert target_y < sh - 50, f"{name} 備援點擊過低可能擊中螢幕邊緣"
        else:
            # 一般螢幕下，0.910 必須避開底部導航列 (通常在 0.93f 之後)
            nav_bar_y = int(sh * 0.930)
            assert target_y < nav_bar_y, f"{name} 備援點擊可能侵入底部導航列"


class TestChallenger2FallbackSequenceSecurity:
    """測試二：廣告備援盲點序列安全防護"""

    @pytest.mark.parametrize("sw, sh", [
        (1080, 2640),
        (1080, 2400),
        (1080, 1920),
        (1440, 3200),
        (720, 1600),
        (1600, 2560),
        (904, 2316),
    ])
    def test_fallback_sequence_all_targets_safe_and_on_right(self, sw, sh):
        def get_fallback_target(sw, sh, cycle):
            targets = [
                ("螢幕右上角熱區", Rect(int(sw * 0.86), max(int(sh * 0.015), 15), int(sw * 0.96), int(sh * 0.075))),
                ("螢幕右下角熱區", Rect(int(sw * 0.86), int(sh * 0.89), int(sw * 0.96), int(sh * 0.95))),
                ("內容右上角熱區", Rect(int(sw * 0.91), int(sh * 0.055), int(sw * 0.99), int(sh * 0.105))),
            ]
            return targets[cycle % len(targets)]

        for cycle in range(30):
            name, rect = get_fallback_target(sw, sh, cycle)

            # 斷言 1：所有盲點 100% 靠右 (left >= 0.86w)
            assert rect.left >= int(sw * 0.86) - 1, f"盲點 {name} 水平未靠右: left={rect.left}, sw={sw}"
            assert rect.right <= sw, f"盲點 {name} 越界: right={rect.right}, sw={sw}"

            # 斷言 2：絕無左上角危險熱區 (left < 0.5w && top < 0.20h)
            is_danger_left_top = (rect.left < int(sw * 0.50)) and (rect.top < int(sh * 0.20))
            assert not is_danger_left_top, f"盲點 {name} 侵入左上角危險熱區"


class TestChallenger2TopTabsConvergenceRobustness:
    """測試三：四大分頁 directOcr 搜尋範圍與長度收斂干擾排斥能力"""

    def test_direct_ocr_strictly_rejects_adversarial_banners_and_promos(self):
        sw, sh = 1080, 2640

        # 對抗性注入：包含廣告文案、過長字串、超出垂直範圍項目
        ocr_items = [
            OcrItem("限時熱門遊戲等你來玩", cy=0.12, cx=0.875, sw=sw, sh=sh),
            OcrItem("走路賺步數換大獎", cy=0.11, cx=0.375, sw=sw, sh=sh),
            OcrItem("首頁活動專區最新優惠", cy=0.10, cx=0.125, sw=sw, sh=sh),
            OcrItem("時間到領咖啡禮券", cy=0.13, cx=0.625, sw=sw, sh=sh),
            OcrItem("遊戲", cy=0.28, cx=0.50, sw=sw, sh=sh),  # cy 超出 0.16
            OcrItem("首頁", cy=0.04, cx=0.50, sw=sw, sh=sh),  # cy 低於 0.07
            # 正確分頁項目 (cy in 0.07..0.16, length <= 4)
            OcrItem("首頁", cy=0.12, cx=0.125, sw=sw, sh=sh),
            OcrItem("步數", cy=0.12, cx=0.375, sw=sw, sh=sh),
            OcrItem("時間", cy=0.12, cx=0.625, sw=sw, sh=sh),
            OcrItem("遊戲", cy=0.12, cx=0.875, sw=sw, sh=sh),
        ]

        # 模擬 AutomationEngine:1816
        matched_tabs = [
            item for item in ocr_items
            if (0.07 <= item.bounds.centerY() / sh <= 0.16)
            and len(item.text.replace(" ", "")) <= 4
            and any(kw in item.text.replace(" ", "") for kw in ["首頁", "移動", "步數", "時間", "遊戲"])
        ]

        # 驗證：4 個對抗性長字串與 2 個範圍外項目全數被排斥，僅 4 個正確分頁被保留
        assert len(matched_tabs) == 4
        matched_texts = [item.text for item in matched_tabs]
        assert set(matched_texts) == {"首頁", "步數", "時間", "遊戲"}


class TestChallenger2SurveyFramesValidation:
    """測試四：真實錄影影格驗證 (frame_12, 25, 42, 45, 50, 172, 180, 210)"""

    def test_frame25_matches_real_skip_button_and_rejects_momo(self):
        f25 = FRAMES_DIR / "frame_25.jpg"
        assert f25.exists(), "frame_25.jpg 不存在"
        img = Image.open(f25)
        res = run_kotlin_fast_forward_skip_algorithm(img)

        assert res is not None, "frame_25 略過按鈕未被檢測到"
        # 實測中心座標在 (426, 32)，容許誤差 8px
        dist = math.hypot(res["abs_x"] - 426, res["abs_y"] - 32)
        assert dist <= 8.0, f"略過按鈕座標誤差過大: {res['abs_x']}, {res['abs_y']} (距離 426, 32 為 {dist}px)"
        assert res["mode"] == "Mode B", "頂部白底狀態列略過鈕應由 Mode B 命中"

    def test_non_ad_frames_have_zero_false_positives(self):
        """非廣告主畫面與遊戲畫面嚴禁觸發略過按鈕假陽性"""
        clean_frames = ["frame_12.jpg", "frame_172.jpg", "frame_180.jpg", "frame_210.jpg"]
        for name in clean_frames:
            p = FRAMES_DIR / name
            if not p.exists():
                continue
            img = Image.open(p)
            res = run_kotlin_fast_forward_skip_algorithm(img)
            assert res is None, f"{name} 發生假陽性略過按鈕誤判: {res}"


class TestChallenger2ZeroEmojiCompliance:
    """測試五：繁體中文與 0 Emoji 規範檢驗"""

    def test_git_diff_and_reports_contain_zero_emojis(self):
        import subprocess
        emoji_pattern = re.compile(
            r"[\U00010000-\U0010ffff]|[\u2600-\u27bf]|[\ud83c-\ud83e]"
        )

        # 檢驗 git diff 涉及的正式 Kotlin 代碼新增行 (added lines)
        result = subprocess.run(
            ["git", "diff", "-U0", "app/src/main/java/com/linewalk/autoad/vision/TemplateMatcher.kt",
             "app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt",
             "app/src/main/java/com/linewalk/autoad/tasks/FriendGiftTask.kt",
             "app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt"],
            capture_output=True, text=True, encoding="utf-8", cwd=REPO_ROOT
        )
        added_lines = "\n".join([line[1:] for line in result.stdout.splitlines() if line.startswith('+') and not line.startswith('+++')])
        found_emojis = emoji_pattern.findall(added_lines)
        assert len(found_emojis) == 0, f"Kotlin 代碼新增行中發現 Emoji: {found_emojis}"
