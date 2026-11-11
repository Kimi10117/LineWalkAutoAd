import os
import re
import pytest
from PIL import Image
import numpy as np

WORKSPACE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def test_version_bump_and_backwards_compatibility():
    gradle_path = os.path.join(WORKSPACE_ROOT, "app", "build.gradle.kts")
    with open(gradle_path, "r", encoding="utf-8") as f:
        content = f.read()

    assert "versionCode = 111" in content, "versionCode must be 111"
    assert 'versionName = "1.15.78"' in content, "versionName must be 1.15.78"
    assert "versionCode = 110" in content, "Backward compatibility comment for versionCode 110 must exist"
    assert 'versionName = "1.15.77"' in content, "Backward compatibility comment for versionName 1.15.77 must exist"

def test_ocr_recognizer_has_simplified_chinese_keywords():
    ocr_path = os.path.join(WORKSPACE_ROOT, "app", "src", "main", "java", "com", "linewalk", "autoad", "vision", "OcrRecognizer.kt")
    with open(ocr_path, "r", encoding="utf-8") as f:
        content = f.read()

    assert '"关闭"' in content, "AD_CLOSE_OCR_KEYWORDS must contain simplified 关闭"
    assert '"关闭广告"' in content, "AD_CLOSE_OCR_KEYWORDS must contain simplified 关闭广告"
    assert '"跳过"' in content, "AD_CLOSE_OCR_KEYWORDS must contain simplified 跳过"
    assert "findBottomBarCloseButton" in content, "findBottomBarCloseButton method must be implemented"

def test_bottom_bar_close_button_simulation():
    # Simulate findBottomBarCloseButton logic
    def simulate_find_bottom_bar_close(items, sw, sh):
        target = None
        for item in items:
            cx = (item["left"] + item["right"]) / 2.0 / sw
            cy = (item["top"] + item["bottom"]) / 2.0 / sh
            t = item["text"].replace(" ", "")
            in_bottom_zone = 0.04 <= cx <= 0.52 and 0.76 <= cy <= 0.92
            has_close = ("关闭" in t) or ("關閉" in t) or ("close" in t.lower())
            has_jump = any(k in t for k in ["開啟", "开启", "下載", "下载", "安裝", "安装", "前往"])
            if in_bottom_zone and has_close and not has_jump:
                target = item
                break
        return target

    sw, sh = 1080, 2640
    # Case 1: He Jiding close button
    items_positive = [
        {"text": "何基鼎台股盤後解析", "left": 100, "top": 200, "right": 800, "bottom": 300},
        {"text": "关闭 | OK", "left": 60, "top": int(sh * 0.82), "right": 450, "bottom": int(sh * 0.88)},
        {"text": "瞭解詳情", "left": 550, "top": int(sh * 0.82), "right": 950, "bottom": int(sh * 0.88)}
    ]
    res = simulate_find_bottom_bar_close(items_positive, sw, sh)
    assert res is not None, "Must find the bottom close button"
    assert "关闭" in res["text"]

    # Case 2: Jump button with '前往'
    items_negative = [
        {"text": "前往關閉下載頁面", "left": 60, "top": int(sh * 0.82), "right": 450, "bottom": int(sh * 0.88)},
    ]
    res_neg = simulate_find_bottom_bar_close(items_negative, sw, sh)
    assert res_neg is None, "Must exclude jump buttons"

def test_trip_com_geometric_x_simulation():
    img_path = r"C:\Users\kimic\.gemini\antigravity\brain\e232dbfa-92dc-4e8d-aba9-96ef68275342\.user_uploaded\media_1794419016880_33eac5cf.jpg"
    if not os.path.exists(img_path):
        pytest.skip("Test image not available in local environment")

    img = Image.open(img_path)
    sw, sh = img.size
    r = max(5, int(sw * 0.018))
    rHalf = max(3, int(r * 0.65))
    step = max(2, sw // 240)
    tol = max(1, sw // 360)

    leftTopLimitY = max(int(sh * 0.014), 18)
    leftBottomLimitY = int(sh * 0.160)
    zone = (int(sw * 0.020), leftTopLimitY, int(sw * 0.195), leftBottomLimitY)
    zw = zone[2] - zone[0]
    zh = zone[3] - zone[1]

    gray = np.array(img.convert("L"))
    zone_pixels = gray[zone[1]:zone[3], zone[0]:zone[2]]

    def lum(x, y):
        return float(zone_pixels[y, x])

    def armLum(px, py, brightX):
        best = 0.0 if brightX else 255.0
        for dy in range(-tol, tol + 1):
            ny = max(0, min(zh - 1, py + dy))
            for dx in range(-tol, tol + 1):
                nx = max(0, min(zw - 1, px + dx))
                l = lum(nx, ny)
                if brightX and l > best: best = l
                if not brightX and l < best: best = l
        return best

    matches = []
    for cy in range(r + tol, zh - r - tol, step):
        for cx in range(r + tol, zw - r - tol, step):
            absX = zone[0] + cx
            absY = zone[1] + cy
            if absY - r < max(int(sh * 0.014), 16):
                continue
            lTop = lum(cx, cy - r)
            lBot = lum(cx, cy + r)
            lLeft = lum(cx - r, cy)
            lRight = lum(cx + r, cy)
            cardMean = (lTop + lBot + lLeft + lRight) * 0.25
            cardDiff = abs(lTop - cardMean) + abs(lBot - cardMean) + abs(lLeft - cardMean) + abs(lRight - cardMean)
            if cardDiff <= 58.0:
                isBrightX = cardMean < 150.0
                centerPixelLum = lum(cx, cy)
                centerContrast = (centerPixelLum - cardMean) if isBrightX else (cardMean - centerPixelLum)
                if centerContrast < 28.0:
                    continue
                vTop = lum(cx, cy - rHalf)
                vBot = lum(cx, cy + rHalf)
                vLeft = lum(cx - rHalf, cy)
                vRight = lum(cx + rHalf, cy)
                maxVGapDiff = max(max(abs(vTop - cardMean), abs(vBot - cardMean)), max(abs(vLeft - cardMean), abs(vRight - cardMean)))
                if maxVGapDiff > 35.0:
                    continue
                cLum = armLum(cx, cy, isBrightX)
                d1 = armLum(cx - rHalf, cy - rHalf, isBrightX)
                d2 = armLum(cx + rHalf, cy + rHalf, isBrightX)
                d3 = armLum(cx + rHalf, cy - rHalf, isBrightX)
                d4 = armLum(cx - rHalf, cy + rHalf, isBrightX)
                diagMean = (cLum + d1 + d2 + d3 + d4) * 0.2
                contrast = (diagMean - cardMean) if isBrightX else (cardMean - diagMean)
                minArmContrast = (min(cLum, min(min(d1, d2), min(d3, d4))) - cardMean) if isBrightX else (cardMean - max(cLum, max(max(d1, d2), max(d3, d4))))
                if contrast > 40.0 and minArmContrast > 20.0:
                    rawScore = contrast - (cardDiff * 0.5)
                    matches.append((absX, absY, rawScore))

    assert len(matches) > 0, "Geometric X detector must find the Trip.com gray X"
    best = max(matches, key=lambda m: m[2])
    # Verify coordinates are in left top corner
    assert 0.05 <= best[0] / sw <= 0.12, f"X ratio {best[0] / sw} must be within 0.05..0.12"
    assert 0.05 <= best[1] / sh <= 0.09, f"Y ratio {best[1] / sh} must be within 0.05..0.09"

def test_ad_closer_handler_left_corner_modifications():
    handler_path = os.path.join(WORKSPACE_ROOT, "app", "src", "main", "java", "com", "linewalk", "autoad", "engine", "AdCloserHandler.kt")
    with open(handler_path, "r", encoding="utf-8") as f:
        content = f.read()

    assert "leftCornerRedirectCount" in content, "Must track leftCornerRedirectCount"
    assert "leftCornerRedirectCount >= 2" in content, "Must require 2 redirects before permanent disable"
    assert "findBottomBarCloseButton" in content, "Must call findBottomBarCloseButton in processAdTick"
    assert "googlePlayPillClickCount > 0 && elapsedSec >= 6" in content, "Reveal swipe must be restricted to playable ads"
    assert "isLeftCloseZone" in content, "Must have isLeftCloseZone protection in isInvalidOrBlacklisted"

def test_systemui_filter_in_accessibility_service():
    service_path = os.path.join(WORKSPACE_ROOT, "app", "src", "main", "java", "com", "linewalk", "autoad", "service", "AutoAdAccessibilityService.kt")
    with open(service_path, "r", encoding="utf-8") as f:
        content = f.read()

    assert 'pkg != "com.android.systemui"' in content, "onAccessibilityEvent must filter com.android.systemui"

def test_launcher_immediate_recovery():
    engine_path = os.path.join(WORKSPACE_ROOT, "app", "src", "main", "java", "com", "linewalk", "autoad", "engine", "AutomationEngine.kt")
    with open(engine_path, "r", encoding="utf-8") as f:
        content = f.read()

    assert "isLauncher" in content, "handleExternalAppGuard must check isLauncher"
    assert 'externalPkg == "com.sec.android.app.launcher"' in content, "Must handle Samsung launcher"
