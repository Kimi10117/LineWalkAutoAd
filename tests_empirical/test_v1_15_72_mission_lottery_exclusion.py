"""
v1.15.72 回歸測試：
1. 任務視窗與抽抽樂中獎結果互斥防護（排除任務、賓果、好友子彈窗被 A-1 劫持）
2. LotteryTask.isLotteryWinResultScreen 嚴格否定排除與中獎抬頭正規化（刪除泛用獎字與孤立 x1 誤判）
3. 抽抽樂結果視窗關閉連續失敗斷路器 (lotteryResultCloseAttempts >= 6 自動重設並發送 BACK)
4. 任務視窗重複開啟防死鎖斷路器 (missionOpenCycleCount >= 6 自動推進至下一階段)
5. 抽抽樂跨階段生命週期暫存狀態清理 (resetLotterySessionState)
6. 版本號 (105 / 1.15.72)、0 Emoji 與繁體中文審計
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


def build_src() -> str:
    return (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# 1. 抽抽樂結果畫面判定之任務彈窗嚴格否定排除與中獎正規化
# ---------------------------------------------------------------------------

def test_lottery_task_excludes_mission_and_other_subdialogs():
    src = lottery_src()
    # 必須包含任務子視窗否定排除
    assert "val isMissionDialog = ocrItems.any {" in src
    assert 'inTopHeader && t.contains("任務")' in src
    assert 't.contains("特別") || t.contains("每日") || t.contains("每週")' in src
    assert "if (isMissionDialog) {" in src
    assert "return false" in src

    # 必須包含賓果與好友面板否定排除
    assert "val isBingoOrFriendDialog = ocrItems.any {" in src
    assert 't.contains("每日賓果")' in src
    assert 't.contains("好友清單")' in src


def test_lottery_task_removes_generic_reward_keyword_matching():
    src = lottery_src()
    idx_start = src.index("fun isLotteryWinResultScreen(")
    idx_end = src.index("fun hasSolidGreenCapsuleInResultZone(", idx_start)
    win_screen_code = src[idx_start:idx_end]

    # 嚴格禁止泛用「獎」字比對（避免「在首頁領取獎勵1次」命中）
    assert 't.contains("獎") && !t.contains("獎項")' not in win_screen_code

    # 必須採用具體中獎正則與 BIG/枚金幣中獎/中獎囉
    assert 'Regex("""([1-9]|10|特|頭)獎""").containsMatchIn(t)' in win_screen_code
    assert 't.contains("BIG")' in win_screen_code
    assert 't.contains("枚金幣中獎")' in win_screen_code


def test_lottery_task_prohibits_lone_x1_without_rank_header():
    src = lottery_src()
    idx_start = src.index("fun isLotteryWinResultScreen(")
    idx_end = src.index("fun hasSolidGreenCapsuleInResultZone(", idx_start)
    win_screen_code = src[idx_start:idx_end]

    # 底端 fallback 不允許孤立的 x1 / 1張 在無中獎抬頭下判定為中獎結果
    assert "hasRankHeader && item.bounds.centerY() in (sh * 0.50f).toInt()..(sh * 0.78f).toInt()" in win_screen_code


def test_lottery_ad_button_excludes_mission_claim_buttons():
    src = lottery_src()
    idx_start = src.index("fun findLotteryRewardAdButton(")
    ad_button_code = src[idx_start:]

    # OCR 備援策略 B 必須排除含「任務」「挑戰」「領取」之項目
    assert '!t.contains("任務") && !t.contains("挑戰") && !t.contains("領取")' in ad_button_code


# ---------------------------------------------------------------------------
# 2. AutomationEngine 頂部巡邏互斥與斷路器防護
# ---------------------------------------------------------------------------

def test_engine_has_subdialog_mutual_exclusion_in_a1():
    src = engine_src()
    idx_a1 = src.index("// A-1. 【抽抽樂中獎結果彈窗最高優先權與重疊 Bug 處理】")
    idx_a0 = src.index("// A0. 檢查是否跳出 LINE 內部宣傳促銷全螢幕廣告")
    a1_code = src[idx_a1:idx_a0]

    # 必須檢查其他子視窗
    assert "isOtherSubDialogOpenForLottery" in a1_code
    assert "missionTask.isMissionDialogOpen(ocrItems, sh, screenBitmap)" in a1_code
    assert "bingoTask.isBingoDialogOpen(ocrItems, sh)" in a1_code
    assert "!isOtherSubDialogOpenForLottery && lotteryTask.isLotteryWinResultScreen(ocrItems, sh, screenBitmap)" in a1_code


def test_engine_has_lottery_result_close_circuit_breaker():
    src = engine_src()
    idx_start = src.index("if (hasLotteryResultContext && hasClickedLotteryAdReward) {")
    idx_end = src.index("// 若不在結果彈窗中，重設相關暫存狀態", idx_start)
    close_code = src[idx_start:idx_end]

    # 連續嘗試關閉達 6 次啟動斷路器，重設旗標並發送 BACK 鍵
    assert "lotteryResultCloseAttempts >= 6" in close_code
    assert "appendLog(\"[抽獎斷路器] 抽抽樂結果視窗關閉連續嘗試達" in close_code
    assert "hasClickedLotteryAdReward = false" in close_code
    assert "AccessibilityService.GLOBAL_ACTION_BACK" in close_code


def test_engine_has_mission_reopen_circuit_breaker():
    src = engine_src()
    # 變數宣告
    assert "internal var missionOpenCycleCount = 0" in src

    # 在 PatrolPhase.MISSION 中檢查計數
    idx_mission = src.index("PatrolPhase.MISSION -> {")
    idx_friend = src.index("PatrolPhase.FRIEND_GIFT -> {", idx_mission)
    mission_code = src[idx_mission:idx_friend]

    assert "missionOpenCycleCount++" in mission_code
    assert "missionOpenCycleCount >= 6" in mission_code
    assert "[任務斷路器] 任務視窗單輪重複開啟達" in mission_code
    assert "isMissionCompleted = true" in mission_code


def test_engine_cleans_lottery_session_state_on_phase_change():
    src = engine_src()
    # 必須存在狀態重設方法
    assert "internal fun resetLotterySessionState() {" in src
    assert "hasClickedLotteryAdReward = false" in src
    assert "lotteryResultRewardWaitTicks = 0" in src
    assert "lotteryResultCloseAttempts = 0" in src

    # 在 getNextPhase 推進時重設
    idx_next_phase = src.index("private fun getNextPhase(after: PatrolPhase): PatrolPhase {")
    assert "if (after == PatrolPhase.LOTTERY) {" in src[idx_next_phase:idx_next_phase + 400]
    assert "resetLotterySessionState()" in src[idx_next_phase:idx_next_phase + 400]


# ---------------------------------------------------------------------------
# 3. 版本號、0 Emoji 與繁體中文審計
# ---------------------------------------------------------------------------

def test_version_code_and_name_updated():
    gradle = build_src()
    assert "versionCode = 105" in gradle
    assert 'versionName = "1.15.72"' in gradle


def test_zero_emoji_in_source_code():
    # 驗證所有修改之原始碼與測試檔案絕無 Emoji
    emoji_pattern = re.compile(
        r"[\U0001F600-\U0001F64F"  # emoticons
        r"\U0001F300-\U0001F5FF"  # symbols & pictographs
        r"\U0001F680-\U0001F6FF"  # transport & map
        r"\U0001F1E0-\U0001F1FF"  # flags
        r"\U00002702-\U000027B0"
        r"\U0001F900-\U0001F9FF"
        r"\U0001FA70-\U0001FAFF]"
    )
    for p in [SRC / "tasks/LotteryTask.kt", SRC / "engine/AutomationEngine.kt", SRC / "tasks/MissionTask.kt"]:
        text = p.read_text(encoding="utf-8")
        matches = emoji_pattern.findall(text)
        assert not matches, f"Found emoji in {p.name}: {matches}"
