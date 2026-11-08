"""
v1.15.71 回歸測試：
1. 抽抽樂 8 獎結果畫面與背景廣告重疊 Bug（重疊時只點底部關閉，背後無廣告點 x1 獲得）
2. 酷澎 Coupang 廣告 Node[txt=✖] 連點 1135 次死循環斷路器與圓形 (X) 備援
3. 糖果傳奇「秒後可獲獎勵」提早點 X 防護與中斷確認彈窗優先點 [繼續]
4. 三星瀏覽器 (sbrowser) 極速拉回散步趣
5. 版本號、0 Emoji 與繁體中文審計
"""
import re
from pathlib import Path
import pytest

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/linewalk/autoad"


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def lottery_src() -> str:
    return (SRC / "tasks/LotteryTask.kt").read_text(encoding="utf-8")


def engine_src() -> str:
    return (SRC / "engine/AutomationEngine.kt").read_text(encoding="utf-8")


def closer_src() -> str:
    return (SRC / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")


def ocr_src() -> str:
    return (SRC / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# 1. 抽抽樂結果畫面判定與背景廣告重疊 Bug
# ---------------------------------------------------------------------------

def test_lottery_task_removes_in_house_promo_veto_for_win_screen():
    src = lottery_src()
    # 抽抽樂 8 獎中間配圖寫著「完成任務 即可獲得 大量金幣!!」，不能否決中獎彈窗
    # 確認 hasMainLotteryPanelFeatures 中不再排除「完成任務」、「即可獲得」、「大量金幣」
    idx_main = src.index("val hasMainLotteryPanelFeatures =")
    idx_end = src.index("return ocrItems.any { item ->", idx_main)
    main_section = src[idx_main:idx_end]
    assert 't.contains("完成任務")' not in main_section
    assert 't.contains("即可獲得")' not in main_section
    assert 't.contains("大量金幣")' not in main_section


def test_lottery_task_detects_background_ad_overlap():
    src = lottery_src()
    assert "fun hasBackgroundAdBehindLotteryResult(ocrItems: List<OcrItem>, sh: Int = 0): Boolean" in src
    # 必須包含判斷背景廣告的特徵關鍵字
    assert "還沒有邀請好友" in src
    assert "實在太可惜了" in src


def test_engine_handles_lottery_overlap_bug_priority():
    src = engine_src()
    # 在 handleInGameScan 頂端，A-1 處理中獎彈窗在 A0 之前
    idx_a1 = src.index("// A-1.")
    idx_a0 = src.index("// A0.")
    idx_b = src.index("// B.")
    assert idx_a1 < idx_a0 < idx_b

    # 散步趣重疊 Bug 處理邏輯：只能點擊中獎彈窗底部的 [關閉] 按鈕
    body = src[idx_a1:idx_a0]
    assert "lotteryTask.hasBackgroundAdBehindLotteryResult(ocrItems, sh)" in body
    assert "findLotteryResultCloseButton" in body
    assert "依規則優先點擊前景彈窗底部的 [關閉] 按鈕" in body

    # 正常中獎（背後無廣告）：交由 handleLotteryDialog 處理
    assert "handleLotteryDialog" in body

    # A0 必須有 !lotteryTask.isLotteryWinResultScreen 守護，防止誤點背景右上角 (X)
    body_a0 = src[idx_a0:idx_b]
    assert "!lotteryTask.isLotteryWinResultScreen(ocrItems, sh, screenBitmap)" in body_a0


# ---------------------------------------------------------------------------
# 2. 酷澎 Coupang 廣告 Node[txt=✖] 連點斷路器
# ---------------------------------------------------------------------------

def test_engine_has_close_node_circuit_breaker():
    src = engine_src()
    # 斷路器追蹤變數
    assert "internal var closeNodeConsecutiveClicks = 0" in src
    assert "internal var lastCloseNodeLabel: String? = null" in src

    # D2 區塊處理
    idx_d2 = src.index("// D2.")
    idx_d3 = src.index("// D3.")
    body_d2 = src[idx_d2:idx_d3]

    assert "closeNodeConsecutiveClicks >= 3" in body_d2
    # Coupang 圓形 (X) 備援點擊
    assert "isCoupang" in body_d2
    assert "coupangAltX" in body_d2
    # 連續 4 次返回，連續 5 次強制拉回
    assert "service.safeNavigateBack(allowInGame = true)" in body_d2
    assert 'service.bringAppToFront("com.linecorp.LGWKTW")' in body_d2


# ---------------------------------------------------------------------------
# 3. 糖果傳奇「秒後可獲獎勵」防早點與確認彈窗優先點 [繼續]
# ---------------------------------------------------------------------------

def test_ocr_recognizes_reward_pending_countdown():
    src = ocr_src()
    assert "fun detectRewardPendingCountdownSeconds(items: List<OcrItem>, screenHeight: Int): Int?" in src
    # 驗證正則表示式匹配能力
    regex = re.compile(r"(\d{1,2})\s*秒(?:後)?(?:可獲|發放|獲得|可獲得)?(?:獎勵)?")
    m1 = regex.search("56 秒後可獲獎勵".replace(" ", ""))
    m2 = regex.search("5秒後發放獎勵".replace(" ", ""))
    m3 = regex.search("12秒可獲獎勵".replace(" ", ""))
    assert m1 is not None and m1.group(1) == "56"
    assert m2 is not None and m2.group(1) == "5"
    assert m3 is not None and m3.group(1) == "12"


def test_ad_closer_handler_countdown_guard_precedence():
    src = closer_src()
    # 倒數計時檢查必須在步驟 3B，先於步驟 4「無障礙節點」
    idx_3b = src.index("// 3B.")
    idx_4 = src.index("// 4.")
    assert idx_3b < idx_4
    body_3b = src[idx_3b:idx_4]
    assert "detectRewardPendingCountdownSeconds(ocrItems, sh)" in body_3b
    assert "AdStepResult.StillCountingDown" in body_3b


def test_dialog_dismiss_excludes_anti_abandon_title():
    src_ocr = ocr_src()
    src_engine = engine_src()
    # 排除關鍵字中包含關閉廣告、無法獲得
    assert '"關閉廣告"' in src_ocr
    assert '"無法獲得"' in src_ocr
    assert '"放棄獎勵"' in src_ocr

    # D0 區塊優先點 [繼續]
    idx_d0 = src_engine.index("// D0.")
    idx_d1 = src_engine.index("// D1.")
    body_d0 = src_engine[idx_d0:idx_d1]
    assert "繼續" in body_d0
    assert "已點擊 [繼續]，恢復廣告播放中..." in body_d0


# ---------------------------------------------------------------------------
# 4. 三星瀏覽器極速拉回
# ---------------------------------------------------------------------------

def test_sbrowser_fast_return():
    src = engine_src()
    assert "com.sec.android.app.sbrowser" in src
    assert "externalAppTicks >= 2 && isBrowserOrStore" in src
    assert 'val targetPkg = config.targetGamePackages.firstOrNull { it != "jp.naver.line.android" } ?: "com.linecorp.LGWKTW"' in src


# ---------------------------------------------------------------------------
# 5. 版本號、0 Emoji 與繁體中文審計
# ---------------------------------------------------------------------------

def test_version_bump_v1_15_71():
    gradle = read("app/build.gradle.kts")
    assert 'versionCode = 104' in gradle
    assert 'versionName = "1.15.71"' in gradle

    workflow = read(".github/workflows/build-apk.yml")
    assert 'v1.15.71' in workflow

    release_notes = read("RELEASE_NOTES.md")
    assert 'v1.15.71' in release_notes
    assert 'versionCode 104' in release_notes


EMOJI_PATTERN = re.compile(
    "[\U00010000-\U0010ffff]|[\u2600-\u27bf]|[\u2300-\u23ff]|[\u2b50-\u2b55]"
)


@pytest.mark.parametrize(
    "rel_path",
    [
        "app/build.gradle.kts",
        "app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt",
        "app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt",
        "app/src/main/java/com/linewalk/autoad/tasks/LotteryTask.kt",
        "app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt",
        ".github/workflows/build-apk.yml",
        "RELEASE_NOTES.md",
    ],
)
def test_zero_emoji_audit(rel_path: str):
    text = read(rel_path)
    matches = EMOJI_PATTERN.findall(text)
    # 過濾特殊數學符號或幾何字符（如 ✖）如果包含在內
    real_emojis = [m for m in matches if ord(m) > 0x1F000 or ord(m) in range(0x1F600, 0x1F650)]
    assert len(real_emojis) == 0, f"{rel_path} 發現 Emoji: {real_emojis}"


@pytest.mark.parametrize(
    "rel_path",
    [
        "RELEASE_NOTES.md",
        ".github/workflows/build-apk.yml",
    ],
)
def test_traditional_chinese_audit(rel_path: str):
    text = read(rel_path)
    # 確保不含常見簡體字
    for s in ["点击", "设置"]:
        assert s not in text, f"{rel_path} 發現簡體字: {s}"
