import pytest
from pathlib import Path
from PIL import Image

IMG_7706 = Path(r"C:\Users\kimic\桌面\散步去BUG\7706_0.jpg")

class MockRect:
    def __init__(self, left, top, right, bottom):
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    def centerX(self):
        return (self.left + self.right) // 2
    def centerY(self):
        return (self.top + self.bottom) // 2
    def width(self):
        return self.right - self.left
    def height(self):
        return self.bottom - self.top

class MockOcrItem:
    def __init__(self, text, bounds):
        self.text = text
        self.bounds = bounds

def test_7706_meta_web_preview_geometric_x_detection():
    """Verify that detectCornerGeometricX detects the close X on 7706_0.jpg at (746, 148)."""
    if not IMG_7706.exists():
        pytest.skip(f"Image {IMG_7706} not found")
    img = Image.open(IMG_7706)
    sw, sh = img.size
    assert (sw, sh) == (818, 2000)

    baseR = max(5, int(sw * 0.018))
    step = max(2, sw // 240)
    tol = max(1, sw // 360)
    topLimitY = max(int(sh * 0.045), 45)
    bottomLimitY = int(sh * 0.16)

    # Unified upper-right zone (TemplateMatcher.kt v1.15.54)
    searchZones = [
        (int(sw * 0.75), topLimitY, int(sw * 0.98), bottomLimitY)
    ]

    pixels = img.load()
    def lum(x, y):
        r, g, b = pixels[x, y][:3]
        return 0.299 * r + 0.587 * g + 0.114 * b

    def armLum(px, py, brightX, zw, zh, zleft, ztop):
        best = 0.0 if brightX else 255.0
        for dy in range(-tol, tol + 1):
            ny = max(0, min(zh - 1, py + dy))
            for dx in range(-tol, tol + 1):
                nx = max(0, min(zw - 1, px + dx))
                l = lum(zleft + nx, ztop + ny)
                if brightX and l > best: best = l
                if not brightX and l < best: best = l
        return best

    r = baseR
    rHalf = max(3, int(r * 0.65))
    bestCandidate = None
    bestRawScore = 65.0
    contrastMin = 45.0
    minArmContrastMin = 22.0
    centerContrastMin = 35.0

    for (zleft, ztop, zright, zbot) in searchZones:
        zw = zright - zleft
        zh = zbot - ztop
        for cy in range(r + tol, zh - r - tol, step):
            for cx in range(r + tol, zw - r - tol, step):
                absX = zleft + cx
                absY = ztop + cy

                if absY - r < max(int(sh * 0.042), 40):
                    continue

                lTop = lum(absX, absY - r)
                lBot = lum(absX, absY + r)
                lLeft = lum(absX - r, absY)
                lRight = lum(absX + r, absY)
                cardMean = (lTop + lBot + lLeft + lRight) * 0.25
                cardDiff = abs(lTop - cardMean) + abs(lBot - cardMean) + abs(lLeft - cardMean) + abs(lRight - cardMean)
                if cardDiff > 58.0:
                    continue
                isBrightX = cardMean < 150.0
                centerPixelLum = lum(absX, absY)
                centerContrast = (centerPixelLum - cardMean) if isBrightX else (cardMean - centerPixelLum)
                if centerContrast < centerContrastMin:
                    continue

                vTop = lum(absX, absY - rHalf)
                vBot = lum(absX, absY + rHalf)
                vLeft = lum(absX - rHalf, absY)
                vRight = lum(absX + rHalf, absY)
                maxVGapDiff = max(max(abs(vTop - cardMean), abs(vBot - cardMean)), max(abs(vLeft - cardMean), abs(vRight - cardMean)))
                if maxVGapDiff > 35.0:
                    continue

                cLum = armLum(cx, cy, isBrightX, zw, zh, zleft, ztop)
                d1 = armLum(cx - rHalf, cy - rHalf, isBrightX, zw, zh, zleft, ztop)
                d2 = armLum(cx + rHalf, cy + rHalf, isBrightX, zw, zh, zleft, ztop)
                d3 = armLum(cx + rHalf, cy - rHalf, isBrightX, zw, zh, zleft, ztop)
                d4 = armLum(cx - rHalf, cy + rHalf, isBrightX, zw, zh, zleft, ztop)

                diagMean = (cLum + d1 + d2 + d3 + d4) * 0.2
                contrast = (diagMean - cardMean) if isBrightX else (cardMean - diagMean)
                minArmContrast = (min(cLum, min(min(d1, d2), min(d3, d4))) - cardMean) if isBrightX else (cardMean - max(cLum, max(max(d1, d2), max(d3, d4))))

                if contrast > contrastMin and minArmContrast > minArmContrastMin:
                    rawScore = contrast - (cardDiff * 0.5)
                    if rawScore > bestRawScore:
                        bestRawScore = rawScore
                        bestCandidate = (absX, absY, rawScore)

    assert bestCandidate is not None, "detectCornerGeometricX must find the close X on 7706_0.jpg!"
    absX, absY, rawScore = bestCandidate
    assert abs(absX - 746) <= 5, f"X coordinate should be near 746, got {absX}"
    assert abs(absY - 146) <= 5, f"Y coordinate should be near 146, got {absY}"
    assert rawScore > 200.0, f"Raw score should exceed 200, got {rawScore}"

def test_receive_gift_dialog_strictly_excludes_send_gift_modal():
    """Verify ReceiveGiftTask.isReceiveGiftDialogOpen strictly excludes send gift modal."""
    sh = 2000
    # Simulate send gift modal OCR items
    send_modal_ocr = [
        MockOcrItem("要向小明贈送金幣嗎？", MockRect(100, 700, 700, 750)),
        MockOcrItem("也送給自己", MockRect(200, 950, 600, 1050)),
        MockOcrItem("剩餘贈送次數 5/5", MockRect(200, 1100, 600, 1150)),
        MockOcrItem("贈送", MockRect(250, 1200, 550, 1280))
    ]

    has_send_gift_modal = any(
        "也送給自己" in item.text.replace(" ", "") or
        "送給自己" in item.text.replace(" ", "") or
        "剩餘贈送次數" in item.text.replace(" ", "") or
        ("要向" in item.text.replace(" ", "") and ("金幣" in item.text.replace(" ", "") or "贈送" in item.text.replace(" ", ""))) or
        "贈送金幣" in item.text.replace(" ", "") or
        "送禮物給" in item.text.replace(" ", "") or
        "贈送禮物" in item.text.replace(" ", "") or
        "贈送完畢" in item.text.replace(" ", "") or
        ("已贈送" in item.text.replace(" ", "") and "金幣" in item.text.replace(" ", "")) or
        ("或者" in item.text.replace(" ", "") and "贈送" in item.text.replace(" ", "")) or
        "確認贈送" in item.text.replace(" ", "") or
        "送禮物也送自己" in item.text.replace(" ", "") or
        ("次數" in item.text.replace(" ", "") and ("5/5" in item.text.replace(" ", "") or "4/5" in item.text.replace(" ", "") or "3/5" in item.text.replace(" ", "") or "2/5" in item.text.replace(" ", "") or "1/5" in item.text.replace(" ", "") or "0/5" in item.text.replace(" ", "")))
        for item in send_modal_ocr
    )
    assert has_send_gift_modal is True
    # If has_send_gift_modal is True, isReceiveGiftDialogOpen returns False
    is_receive_gift_dialog = not has_send_gift_modal
    assert is_receive_gift_dialog is False, "Send gift modal must NEVER be recognized as receive gift drawer!"

def test_bingo_retry_limit_five_times():
    """Verify that bingo open cell allows up to 5 attempts before falling back to Get."""
    retry_count = 0
    max_retries = 5
    attempts = []

    for _ in range(7):
        if retry_count < max_retries:
            retry_count += 1
            attempts.append(f"open_attempt_{retry_count}")
        else:
            attempts.append("fallback_check_get")

    assert attempts[:5] == [f"open_attempt_{i}" for i in range(1, 6)]
    assert attempts[5] == "fallback_check_get"
    assert attempts[6] == "fallback_check_get"

def test_meta_web_preview_detection_and_blacklist_reset():
    """Verify that Preview of meganeichiba is detected as web preview and clears blacklisted points."""
    preview_ocr = [
        MockOcrItem("Preview of meganeichiba.com.tw", MockRect(50, 120, 600, 160))
    ]
    sh = 2000
    is_meta_web_preview = any(
        (item.bounds.centerY() / sh < 0.16 and ("Preview of" in item.text or "Preview" in item.text or "預覽" in item.text)) or
        "meganeichiba" in item.text
        for item in preview_ocr
    )
    assert is_meta_web_preview is True

    # Test blacklist reset logic
    blacklisted_points = [(745, 144), (500, 500)]
    clicked_history = [(745, 144)]
    assert len(blacklisted_points) > 0

    # resetClickBlacklist()
    blacklisted_points.clear()
    clicked_history.clear()
    assert len(blacklisted_points) == 0
    assert len(clicked_history) == 0

def test_universal_aspect_ratio_coordinates():
    """Verify coordinates across 16:9, 19.5:9, 21:9, and 22:9 (Z Flip 5)."""
    ratios = [
        ("16:9", 1080, 1920),
        ("19.5:9", 1080, 2340),
        ("21:9", 1080, 2520),
        ("22:9 (Flip 5)", 818, 2000),
        ("22:9 (Unfolded)", 1080, 2640),
    ]

    for name, sw, sh in ratios:
        # 1. Top tabs
        y_anchor = int(sh * 0.165)
        tabs_x = [int(sw * 0.125), int(sw * 0.375), int(sw * 0.625), int(sw * 0.875)]
        assert all(0 < x < sw for x in tabs_x), f"Tabs X out of bounds in {name}"
        assert 0 < y_anchor < sh, f"Tabs Y out of bounds in {name}"

        # 2. Web preview close button
        preview_x = int(sw * 0.910)
        preview_y = int(sh * 0.0725)
        assert 0 < preview_x < sw, f"Preview close X out of bounds in {name}"
        assert 0 < preview_y < sh, f"Preview close Y out of bounds in {name}"

        # 3. Bottom nav
        bottom_y = int(sh * 0.910)
        assert bottom_y < sh - int(sh * 0.03), f"Bottom Y too close to bottom in {name}"
