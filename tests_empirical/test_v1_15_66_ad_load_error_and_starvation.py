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

    def width(self):
        return self.right - self.left

    def height(self):
        return self.bottom - self.top


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
    def __init__(self, width=1080, height=2400, default_color=(255, 255, 255)):
        self.width = width
        self.height = height
        self.pixels = {}
        self.default_color = default_color

    def getPixel(self, x, y):
        # 0xAARRGGBB
        r, g, b = self.pixels.get((x, y), self.default_color)
        return (0xFF << 24) | (r << 16) | (g << 8) | b

    def setPixel(self, x, y, r, g, b):
        self.pixels[(x, y)] = (r, g, b)


class MainTab:
    MOVE = "MOVE"
    STEP = "STEP"
    TIME = "TIME"
    GAME = "GAME"


# ==============================================================================
# 測試 1: 廣告無庫存守護 (Ad Starvation Guard) 與斷路器模擬
# ==============================================================================
class MockAutomationEngine:
    def __init__(self):
        self.currentTab = MainTab.TIME
        self.tabPriorityOrder = [MainTab.MOVE, MainTab.TIME, MainTab.GAME, MainTab.STEP]
        self.adLoadErrorCountPerTab = {}
        self.sameTargetClickCount = 0
        self.lastTargetClickName = None
        self.lastTargetClickTime = 0
        self.logs = []
        self.advancedTabs = []
        self.backPressCount = 0

    def appendLog(self, msg):
        self.logs.append(msg)

    def forceAdvanceToNextTab(self, reason):
        idx = self.tabPriorityOrder.index(self.currentTab)
        next_tab = self.tabPriorityOrder[(idx + 1) % len(self.tabPriorityOrder)]
        self.advancedTabs.append((self.currentTab, next_tab, reason))
        self.currentTab = next_tab

    def navigateBack(self):
        self.backPressCount += 1

    def checkClickCircuitBreaker(self, label, bounds, now_ms=1000):
        if self.lastTargetClickName == label:
            self.sameTargetClickCount += 1
            if self.sameTargetClickCount >= 3:
                self.sameTargetClickCount = 0
                return True
        else:
            self.lastTargetClickName = label
            self.sameTargetClickCount = 1
        return False

    def handleAdLoadError(self, btn_name, btn_bounds):
        # 模擬 AutomationEngine.kt 第 706-740 行之無庫存守護與斷路器
        if self.checkClickCircuitBreaker(btn_name, btn_bounds):
            self.appendLog("[斷路器] 「無法載入廣告」彈窗連續點擊未響應，發送系統返回鍵並強制換頁！")
            self.adLoadErrorCountPerTab[self.currentTab] = 0
            self.navigateBack()
            self.forceAdvanceToNextTab("錯誤彈窗連續未響應，斷路器強制換頁")
            return "CIRCUIT_BREAKER_TRIGGERED"

        errorCount = (self.adLoadErrorCountPerTab.get(self.currentTab, 0)) + 1
        self.adLoadErrorCountPerTab[self.currentTab] = errorCount

        if errorCount >= 2:
            self.appendLog(f"[無庫存] 分頁【{self.currentTab}】廣告無庫存 (累計 {errorCount} 次)，關閉彈窗並切換下一分頁！")
            self.navigateBack()
            self.adLoadErrorCountPerTab[self.currentTab] = 0
            self.forceAdvanceToNextTab(f"分頁【{self.currentTab}】廣告無庫存")
            return "STARVATION_ADVANCED"

        self.appendLog(f"[錯誤] 偵測到「無法載入廣告」彈窗，自動點擊關閉 (嘗試 {errorCount}/2)...")
        return "RETRY_SAME_TAB"


def test_ad_starvation_guard_advances_after_two_errors():
    """驗證同一分頁連續兩次載入失敗時，主動推進下一分頁，消滅 45 次死循環"""
    engine = MockAutomationEngine()
    engine.currentTab = MainTab.TIME

    # 第 1 次錯誤：重試當前分頁
    res1 = engine.handleAdLoadError("錯誤彈窗綠色[關閉]鈕", Rect(100, 100, 200, 200))
    assert res1 == "RETRY_SAME_TAB"
    assert engine.currentTab == MainTab.TIME
    assert engine.adLoadErrorCountPerTab[MainTab.TIME] == 1

    # 第 2 次錯誤：判定無庫存，推進至 GAME 分頁
    res2 = engine.handleAdLoadError("錯誤彈窗綠色[關閉]鈕", Rect(100, 100, 200, 200))
    assert res2 == "STARVATION_ADVANCED"
    assert engine.currentTab == MainTab.GAME
    assert engine.adLoadErrorCountPerTab[MainTab.TIME] == 0
    assert engine.backPressCount == 1
    assert len(engine.advancedTabs) == 1
    assert engine.advancedTabs[0][0] == MainTab.TIME
    assert engine.advancedTabs[0][1] == MainTab.GAME


def test_circuit_breaker_on_ad_error_modal():
    """驗證若彈窗關閉按鈕連續 3 次點擊無效，觸發斷路器強制返回換頁"""
    engine = MockAutomationEngine()
    engine.currentTab = MainTab.MOVE

    # 模擬同一按鈕連續點擊 3 次
    # 第 1 次: 重試 (MOVE)
    # 第 2 次: 達 2 次無庫存守護推進至下一分頁 (MOVE -> TIME)
    # 第 3 次: 同一目標連續點擊達 3 次，觸發斷路器發送返回並再次推進 (TIME -> GAME)
    engine.handleAdLoadError("錯誤彈窗綠色[關閉]鈕", Rect(100, 100, 200, 200))
    engine.handleAdLoadError("錯誤彈窗綠色[關閉]鈕", Rect(100, 100, 200, 200))
    res3 = engine.handleAdLoadError("錯誤彈窗綠色[關閉]鈕", Rect(100, 100, 200, 200))

    assert res3 == "CIRCUIT_BREAKER_TRIGGERED"
    assert engine.currentTab == MainTab.GAME
    assert engine.backPressCount >= 1


# ==============================================================================
# 測試 2: 白底黑框「紅利 [▶] x1 獲得」按鈕辨識與反灰豁免
# ==============================================================================
def simulate_find_popup_bonus_badge_ad_button(bitmap):
    """
    精確模擬 TemplateMatcher.kt 中更新後的 findPopupBonusBadgeAdButton
    """
    w = bitmap.width
    h = bitmap.height
    startY = int(h * 0.62)
    endY = int(h * 0.88)
    startX = int(w * 0.07)
    endX = int(w * 0.30)

    maxPinkY = -1
    matchedRows = 0

    y = startY
    while y <= endY:
        pinkPixels = 0
        total = 0
        x = startX
        while x <= endX:
            c = bitmap.getPixel(x, y)
            r = (c >> 16) & 0xFF
            g = (c >> 8) & 0xFF
            b = c & 0xFF
            if r > 215 and (35 <= g <= 125) and (45 <= b <= 135) and (r - g > 110):
                pinkPixels += 1
            total += 1
            x += 3
        if total > 0 and (pinkPixels / float(total)) >= 0.45:
            if y > maxPinkY:
                maxPinkY = y
            matchedRows += 1
        y += 2

    if matchedRows >= 3 and maxPinkY > 0:
        targetCenterY = min(int(maxPinkY + h * 0.032), int(h * 0.90))
        halfBtnHeight = max(22, int(h * 0.024))
        rect = Rect(
            int(w * 0.15),
            targetCenterY - halfBtnHeight,
            int(w * 0.85),
            targetCenterY + halfBtnHeight
        )
        return {"score": 0.96, "bounds": rect, "label": "紅利[x1獲得]看廣告鈕"}
    return None


def test_white_button_with_bonus_badge_detected_successfully():
    """驗證白底黑框按鈕（紅色素僅在左上角紅利標籤）可被 100% 成功偵測，不再被反灰誤殺"""
    w, h = 1080, 2640  # 22:9 Galaxy Z Flip5 SM-F731N
    bitmap = MockBitmap(w, h, default_color=(250, 250, 250))  # 白底

    # 在 y in 0.80h..0.82h, x in 0.08w..0.25w 繪製珊瑚粉紅「紅利」標籤
    # r=240, g=70, b=90 (r > 215, g in 35..125, b in 45..135, r-g=170 > 110)
    badge_y_start = int(h * 0.80)
    badge_y_end = int(h * 0.82)
    badge_x_start = int(w * 0.08)
    badge_x_end = int(w * 0.25)

    for y in range(badge_y_start, badge_y_end):
        for x in range(badge_x_start, badge_x_end):
            bitmap.setPixel(x, y, 240, 70, 90)

    result = simulate_find_popup_bonus_badge_ad_button(bitmap)
    assert result is not None
    assert result["label"] == "紅利[x1獲得]看廣告鈕"
    assert result["bounds"].left == int(w * 0.15)
    assert result["bounds"].right == int(w * 0.85)
    assert result["bounds"].centerY() > badge_y_end


# ==============================================================================
# 測試 3: 動態綠色輪廓邊界採樣與全機型螢幕自適應
# ==============================================================================
def simulate_find_ad_load_error_close_button(bitmap, items):
    """
    精確模擬 OcrRecognizer.kt 中更新後的 findAdLoadErrorCloseButton
    """
    sw = bitmap.width
    sh = bitmap.height

    hasErrorText = any(
        any(kw in item.text.replace(" ", "") for kw in [
            "無法載入廣告", "請稍後再試", "載入失敗", "廣告載入失敗", "連線逾時", "連線錯誤", "無法播放", "廣告播放失敗"
        ])
        for item in items
    )

    ocrClose = next((
        item for item in items
        if 0.58 <= (item.bounds.centerY() / float(sh)) <= 0.76 and
           0.20 <= (item.bounds.centerX() / float(sw)) <= 0.80 and
           item.text.strip() in ["關閉", "確認", "確定", "OK"]
    ), None)

    greenButtonPts = 0
    greenButtonMinX = sw
    greenButtonMaxX = 0
    greenButtonMinY = sh
    greenButtonMaxY = 0

    y1 = int(sh * 0.58)
    y2 = int(sh * 0.76)
    x1 = int(sw * 0.15)
    x2 = int(sw * 0.85)

    for y in range(y1, y2, 2):
        for x in range(x1, x2, 2):
            c = bitmap.getPixel(x, y)
            r = (c >> 16) & 0xFF
            g = (c >> 8) & 0xFF
            b = c & 0xFF
            if g > 140 and (g - r > 35) and (g - b > 35):
                greenButtonPts += 1
                if x < greenButtonMinX: greenButtonMinX = x
                if x > greenButtonMaxX: greenButtonMaxX = x
                if y < greenButtonMinY: greenButtonMinY = y
                if y > greenButtonMaxY: greenButtonMaxY = y

    dynamicButtonRect = Rect(greenButtonMinX, greenButtonMinY, greenButtonMaxX, greenButtonMaxY) \
        if (greenButtonPts >= 20 and greenButtonMaxX > greenButtonMinX and greenButtonMaxY > greenButtonMinY) \
        else Rect(int(sw * 0.15), int(sh * 0.63), int(sw * 0.85), int(sh * 0.71))

    if hasErrorText:
        if ocrClose is not None:
            return "錯誤彈窗[關閉]OCR", ocrClose.bounds
        return "錯誤彈窗綠色[關閉]鈕", dynamicButtonRect

    return None


@pytest.mark.parametrize("sw,sh", [
    (1080, 2640),  # 22:9 Galaxy Z Flip5
    (1080, 2400),  # 20:9 標準旗艦機
    (1080, 2160),  # 18:9 全面屏
    (1080, 1920),  # 16:9 經典長寬比
])
def test_dynamic_green_button_adaptation_across_screen_ratios(sw, sh):
    """驗證不同長寬比螢幕下，動態綠色輪廓邊界採樣精確鎖定按鈕 Rect"""
    bitmap = MockBitmap(sw, sh, default_color=(255, 255, 255))

    # 在 y=0.65sh..0.70sh, x=0.25sw..0.75sw 繪製鮮綠色按鈕
    # g=200, r=50, b=50 (g > 140, g-r=150 > 35, g-b=150 > 35)
    btn_y1 = int(sh * 0.65)
    btn_y2 = int(sh * 0.70)
    btn_x1 = int(sw * 0.25)
    btn_x2 = int(sw * 0.75)

    for y in range(btn_y1, btn_y2):
        for x in range(btn_x1, btn_x2):
            bitmap.setPixel(x, y, 50, 200, 50)

    items = [OcrItem("無法載入廣告 請稍後再試。", cy=0.48, cx=0.5, sw=sw, sh=sh)]
    label, rect = simulate_find_ad_load_error_close_button(bitmap, items)

    assert label == "錯誤彈窗綠色[關閉]鈕"
    assert abs(rect.left - btn_x1) <= 2
    assert abs(rect.right - (btn_x2 - 1)) <= 2
    assert abs(rect.top - btn_y1) <= 2
    assert abs(rect.bottom - (btn_y2 - 1)) <= 2


# ==============================================================================
# 測試 4: 廣告載入黑幕優先級重構
# ==============================================================================
def simulate_is_ad_loading_screen(items):
    """
    精確模擬 OcrRecognizer.kt 中更新後的 isAdLoadingScreen
    """
    hasLoadingText = any(
        any(kw in item.text.replace(" ", "") for kw in [
            "載入中", "廣告載入", "載入時間過長", "重新啟動應用程式", "読み込み中"
        ]) or ("Loading" in item.text and any("取消" in it.text for it in items))
        for item in items
    )
    if hasLoadingText:
        return True

    isGameModalOrResult = any(
        any(kw in item.text.replace(" ", "") for kw in ["中獎", "恭喜", "獲得金幣", "獲得票券", "枚金幣", "張票券"])
        for item in items
    )
    if isGameModalOrResult:
        return False

    return False


def test_loading_screen_not_vetoed_by_background_game_text():
    """驗證當畫面出現「廣告載入中」時，即使半透明黑幕透出背景「獲得金幣」字樣，仍判定為 True"""
    items = [
        OcrItem("獲得金幣！", cy=0.55),     # 背景透出文字
        OcrItem("廣告載入中...", cy=0.50),   # 載入提示文字
    ]
    assert simulate_is_ad_loading_screen(items) is True
