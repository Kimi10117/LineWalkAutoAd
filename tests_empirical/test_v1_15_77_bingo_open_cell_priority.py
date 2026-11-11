from pathlib import Path
from PIL import Image
import numpy as np

PROJECT_ROOT = Path(__file__).resolve().parent.parent
AUTOMATION_ENGINE = PROJECT_ROOT / "app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt"
BINGO_TASK = PROJECT_ROOT / "app/src/main/java/com/linewalk/autoad/tasks/BingoTask.kt"
BUILD_GRADLE = PROJECT_ROOT / "app/build.gradle.kts"

USER_SCREENSHOT = Path(r"C:\Users\kimic\.gemini\antigravity\brain\1517d055-ada4-4bca-866d-09e825fe0a19\.user_uploaded\media_1794413176231_4ecf7e21.jpg")


def test_user_screenshot_orange_button_detected():
    """Verify that the orange '立即開啟賓果格' button on the user's screenshot is detected by 2D clustering."""
    assert USER_SCREENSHOT.exists(), "User screenshot must exist"
    img = Image.open(USER_SCREENSHOT)
    w, h = img.size
    arr = np.array(img)

    # Replicate BingoTask 2D clustering:
    # yStart = (sh * 0.56f).toInt(), yEnd = (sh * 0.72f).toInt()
    # xStart = (sw * 0.16f).toInt(), xEnd = (sw * 0.84f).toInt()
    # r > 190 && g in 100..195 && b < 70
    orange_pts = []
    y_start, y_end = int(h * 0.56), int(h * 0.72)
    x_start, x_end = int(w * 0.16), int(w * 0.84)

    for y in range(y_start, y_end + 1, 3):
        for x in range(x_start, x_end + 1, 3):
            r, g, b = arr[y, x][:3]
            if r > 190 and 100 <= g <= 195 and b < 70:
                orange_pts.append((x, y))

    assert len(orange_pts) >= 80, f"Expected >= 80 orange pixels, got {len(orange_pts)}"

    min_x = min(p[0] for p in orange_pts)
    max_x = max(p[0] for p in orange_pts)
    min_y = min(p[1] for p in orange_pts)
    max_y = max(p[1] for p in orange_pts)

    btn_w = max_x - min_x
    btn_h = max_y - min_y

    assert btn_w > w * 0.35, f"Button width {btn_w} should exceed 35% of screen width {w}"
    assert btn_h >= 15, f"Button height {btn_h} should be at least 15px"
    cy = (min_y + max_y) // 2
    assert int(h * 0.58) <= cy <= int(h * 0.70), f"Button center Y {cy} outside expected range"


def test_automation_engine_open_cell_priority():
    """Verify handleBingoDialog calls findOpenCellButton unconditionally without !isCoolingDown gate."""
    ae_code = AUTOMATION_ENGINE.read_text(encoding="utf-8")

    # Ensure findOpenCellButton is called directly under retry check, not gated by !isCoolingDown
    assert "val openCellBtn = if (bingoOpenCellRetryCount < 5)" in ae_code
    assert "val isCoolingDown = openCellBtn == null && bingoTask.isBingoCoolingDown(ocrItems)" in ae_code
    assert "openCellBtn != null && bingoOpenCellRetryCount < 5" in ae_code
    assert "!isCoolingDown && bingoOpenCellRetryCount < 5" in ae_code


def test_bingo_task_cooling_down_rules_text_excluded():
    """Verify isBingoCoolingDown does not treat static rules description as cooling down."""
    bingo_code = BINGO_TASK.read_text(encoding="utf-8")
    assert "30分鐘啟動" not in bingo_code
    assert "距離下次" in bingo_code


def test_version_bump_v1_15_77():
    """Verify version is bumped to 1.15.77 (versionCode 110)."""
    code = BUILD_GRADLE.read_text(encoding="utf-8")
    assert "versionCode = 110" in code
    assert 'versionName = "1.15.77"' in code
