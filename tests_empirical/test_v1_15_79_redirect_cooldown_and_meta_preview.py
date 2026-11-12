# -*- coding: utf-8 -*-
import os
import re
import pytest

WORKSPACE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def test_version_bump_and_backwards_compatibility():
    gradle_path = os.path.join(WORKSPACE_ROOT, "app", "build.gradle.kts")
    with open(gradle_path, "r", encoding="utf-8") as f:
        content = f.read()

    assert "versionCode = 112" in content, "versionCode must be 112"
    assert 'versionName = "1.15.79"' in content, "versionName must be 1.15.79"
    assert "versionCode = 111" in content, "Backward compatibility comment for versionCode 111 must exist"
    assert 'versionName = "1.15.78"' in content, "Backward compatibility comment for versionName 1.15.78 must exist"

def test_mark_recent_redirect_no_blacklist_clear_and_threshold_three():
    handler_path = os.path.join(WORKSPACE_ROOT, "app", "src", "main", "java", "com", "linewalk", "autoad", "engine", "AdCloserHandler.kt")
    with open(handler_path, "r", encoding="utf-8") as f:
        content = f.read()

    idx_redirect = content.index("fun markRecentRedirect(")
    idx_next_fun = content.index("fun isLeftCornerDisabledForCurrentAd()", idx_redirect)
    redirect_body = content[idx_redirect:idx_next_fun]

    # 1. 絕不可在重定向時清空黑名單
    assert "resetClickBlacklist()" not in redirect_body, "markRecentRedirect must not clear blacklists"

    # 2. 必須記錄重定向返回時間與座標
    assert "lastRedirectReturnTimeMs = System.currentTimeMillis()" in redirect_body
    assert "redirectPointCounts.add(pt)" in redirect_body

    # 3. 依使用者指導，僅累積連跳達 3 次才進入節流黑名單
    assert "nearbyRedirects >= 3" in redirect_body
    assert "blacklistedPoints.add(pt)" in redirect_body

def test_redirect_cooldown_buffer_in_is_invalid_or_blacklisted():
    handler_path = os.path.join(WORKSPACE_ROOT, "app", "src", "main", "java", "com", "linewalk", "autoad", "engine", "AdCloserHandler.kt")
    with open(handler_path, "r", encoding="utf-8") as f:
        content = f.read()

    idx_func = content.index("fun isInvalidOrBlacklisted(")
    idx_end = content.index("suspend fun processAdTick(", idx_func)
    func_body = content[idx_func:idx_end]

    # 必須包含 3500ms 重定向冷卻節流緩衝
    assert "now - lastRedirectReturnTimeMs < 3500L" in func_body
    assert "isRecentRedirectPt" in func_body
    assert "redirectPointCounts.any" in func_body

def test_meta_web_preview_includes_left_zone_and_removes_blind_click():
    handler_path = os.path.join(WORKSPACE_ROOT, "app", "src", "main", "java", "com", "linewalk", "autoad", "engine", "AdCloserHandler.kt")
    with open(handler_path, "r", encoding="utf-8") as f:
        content = f.read()

    idx_meta = content.index("// 4D. 【Meta 網頁預覽 (Web Preview) 秒速關閉】")
    idx_next_step = content.index("// 6. 第一層辨識：檢查無障礙節點樹", idx_meta)
    meta_body = content[idx_meta:idx_next_step]

    # 1. 必須開啟左上角掃描以命中 Trip.com 等左上角 X
    assert "includeLeftZone = !disableLeftCornerForCurrentAd" in meta_body

    # 2. 必須徹底廢除右上角盲點熱區
    assert "Meta預覽關閉(右上角熱區)" not in meta_body
    assert "previewCloseRect" not in meta_body

    # 3. 無幾何 X 時必須回退至安全返回鍵
    assert "Meta預覽安全返回鍵" in meta_body
    assert "service.safeNavigateBack(allowInGame = false)" in meta_body

def test_promo_corner_close_failure_prevents_launcher_exit():
    engine_path = os.path.join(WORKSPACE_ROOT, "app", "src", "main", "java", "com", "linewalk", "autoad", "engine", "AutomationEngine.kt")
    with open(engine_path, "r", encoding="utf-8") as f:
        content = f.read()

    idx_target = content.index("geoXConsecutiveClicks >= 3")
    sub_code = content[idx_target:idx_target + 300]

    # 當前處於遊戲主畫面時，嚴格禁止發送返回鍵以免退出散步趣主頁至桌面
    assert "allowInGame = !isGameScreen" in sub_code
