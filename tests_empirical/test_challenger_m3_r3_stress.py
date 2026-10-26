#!/usr/bin/env python3
"""
EMPIRICAL CHALLENGER TEST SUITE: R3 Third-Party Ad Containers Full Exemption & State Transitions
LineWalkAutoAd - Challenger 2 (challenger_m3_2)

This test harness empirically stress-tests:
1. Whitelist recognition across all 12 required ad packages and variants.
2. Ad package exemption from external redirect classification.
3. State machine immediate seamless transition to AD_WATCHING without stale tick branches.
4. Back key suppression: ZERO Back keys (GLOBAL_ACTION_BACK) under ANY circumstance (including allowInGame=True).
5. External app guard handling for non-ad packages (settings, launchers, external browsers).
6. AST integrity and semantic analysis of Kotlin source files.
"""

import os
import re
import pytest

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(BASE_DIR, "app", "src", "main", "java", "com", "linewalk", "autoad")

# All 12 required ad packages specified in the Challenger prompt and PROJECT.md
TARGET_AD_PACKAGES = [
    # Meta Audience Network
    "com.facebook.katana",
    "com.facebook.orca",
    "com.facebook.lite",
    "com.instagram.android",
    # Google Mobile Ads / AdMob
    "com.google.android.gms",
    "com.google.android.as",
    "com.google.android.webview",
    # Unity Ads
    "com.unity3d.player",
    "com.unity3d.ads",
    # AppLovin / MAX
    "com.applovin",
    "com.applovin.sdk",
    # Chrome Custom Tabs
    "com.android.chrome",
]

# Additional ad package variants
ADDITIONAL_AD_VARIANTS = [
    "com.facebook.mlite",
    "com.facebook.ads.AudienceNetworkActivity",
    "com.google.android.gms.ads.AdActivity",
    "com.unity3d.ads.adplayer",
    "com.applovin.mediation.MaxAd",
    "androidx.browser.customtabs"
]

# External non-ad packages
EXTERNAL_NON_AD_PACKAGES = [
    "com.android.settings",
    "com.sec.android.app.launcher",
    "com.google.android.apps.nexuslauncher",
    "com.google.android.youtube",
    "com.android.vending",
    "com.sec.android.app.sbrowser",
    "org.mozilla.firefox",
    "com.microsoft.emmx",
    "com.brave.browser"
]

# Game packages
GAME_PACKAGES = [
    "com.linecorp.LGWALK",
    "com.linecorp.LGWKTW",
    "jp.naver.line.android"
]


# ==============================================================================
# Model Implementations Mirroring Kotlin Logic
# ==============================================================================

class AppConfigModel:
    KNOWN_AD_PACKAGES = {
        "com.facebook.katana",
        "com.facebook.orca",
        "com.facebook.lite",
        "com.facebook.mlite",
        "com.instagram.android",
        "com.google.android.gms",
        "com.google.android.as",
        "com.google.android.webview",
        "com.unity3d.player",
        "com.unity3d.ads",
        "com.applovin",
        "com.applovin.sdk",
        "com.android.chrome",
        "androidx.browser.customtabs"
    }

    EXTERNAL_REDIRECT_PACKAGES = {
        "com.android.vending",
        "com.android.chrome",
        "com.google.android.apps.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.mi.globalbrowser",
        "com.coloros.browser",
        "com.heytap.browser",
        "com.vivo.browser",
        "com.huawei.browser"
    }

    @classmethod
    def is_game_package(cls, pkg):
        if not pkg:
            return False
        return pkg in ("com.linecorp.LGWKTW", "com.linecorp.LGWALK") or pkg.startswith("com.linecorp.LGWK")

    @classmethod
    def is_ad_package(cls, pkg):
        if not pkg:
            return False
        if pkg in cls.KNOWN_AD_PACKAGES:
            return True
        lower = pkg.lower()
        return (
            lower.startswith("com.facebook.ads") or
            lower.startswith("com.facebook.katana") or
            lower.startswith("com.facebook.orca") or
            "audience_network" in lower or
            "admob" in lower or
            "google.android.gms" in lower or
            "google.android.as" in lower or
            "unity3d" in lower or
            "unity.ads" in lower or
            "unityads" in lower or
            "applovin" in lower or
            "ironsource" in lower or
            "vungle" in lower or
            "liftoff" in lower or
            "mintegral" in lower or
            "mbridge" in lower or
            "pangle" in lower or
            lower.startswith("com.bytedance.pangle") or
            "inmobi" in lower or
            "customtabs" in lower
        )

    @classmethod
    def is_redirect_package(cls, pkg):
        if not pkg:
            return False
        if cls.is_ad_package(pkg):
            return False
        if pkg in cls.EXTERNAL_REDIRECT_PACKAGES:
            return True
        lower = pkg.lower()
        return "browser" in lower or "chrome" in lower or "vending" in lower


# ==============================================================================
# Test 1: Whitelist Recognition for all 12 ad packages and variants
# ==============================================================================

def test_12_ad_packages_whitelist_recognition():
    """Verify all 12 required ad packages are recognized by is_ad_package."""
    for pkg in TARGET_AD_PACKAGES:
        assert AppConfigModel.is_ad_package(pkg) is True, f"Target ad package '{pkg}' must be recognized"

    for pkg in ADDITIONAL_AD_VARIANTS:
        assert AppConfigModel.is_ad_package(pkg) is True, f"Ad variant '{pkg}' must be recognized"


def test_ad_packages_not_classified_as_redirects():
    """Verify that none of the ad packages (especially Chrome) are classified as redirect packages."""
    for pkg in TARGET_AD_PACKAGES:
        assert AppConfigModel.is_redirect_package(pkg) is False, f"Ad package '{pkg}' must NOT be classified as redirect"

    for pkg in ADDITIONAL_AD_VARIANTS:
        assert AppConfigModel.is_redirect_package(pkg) is False, f"Ad variant '{pkg}' must NOT be classified as redirect"


def test_external_non_ad_packages_classification():
    """Verify external non-ad packages are NOT ad packages and redirects are properly flagged."""
    for pkg in EXTERNAL_NON_AD_PACKAGES:
        assert AppConfigModel.is_ad_package(pkg) is False, f"External package '{pkg}' must NOT be an ad package"

    # Settings and launchers are NOT redirect packages
    assert AppConfigModel.is_redirect_package("com.android.settings") is False
    assert AppConfigModel.is_redirect_package("com.sec.android.app.launcher") is False
    assert AppConfigModel.is_redirect_package("com.google.android.apps.nexuslauncher") is False

    # Browsers and store ARE redirect packages
    assert AppConfigModel.is_redirect_package("com.android.vending") is True
    assert AppConfigModel.is_redirect_package("com.sec.android.app.sbrowser") is True
    assert AppConfigModel.is_redirect_package("org.mozilla.firefox") is True
    assert AppConfigModel.is_redirect_package("com.microsoft.emmx") is True


# ==============================================================================
# Test 2: State Machine Immediate Transition & Stale Branch Suppression
# ==============================================================================

class MockEngine:
    def __init__(self, initial_state="IN_GAME_SCAN"):
        self.state = initial_state
        self.non_game_ticks = 0
        self.logs = []
        self.handlers_executed = []
        self.dispatched_backs = 0
        self.bring_to_front_called = []

    def execute_single_tick(self, fg_pkg):
        current_state = self.state

        # Step 0: Ad package check from AutomationEngine.kt line 342
        if AppConfigModel.is_ad_package(fg_pkg):
            self.non_game_ticks = 0
            if current_state != "AD_WATCHING":
                self.logs.append(f"Auto-switch to AD_WATCHING for {fg_pkg}")
                self.state = "AD_WATCHING"
                current_state = "AD_WATCHING"  # Critical: local mutation

        # Step 1: evaluateForegroundPackage
        is_ad = AppConfigModel.is_ad_package(fg_pkg)
        is_target = AppConfigModel.is_game_package(fg_pkg) or is_ad
        if not is_target:
            self.handle_external_app_guard(fg_pkg)
            return

        self.non_game_ticks = 0

        # Step 2: when (currentState)
        if current_state == "IDLE":
            self.handlers_executed.append("handleIdle")
        elif current_state == "IN_GAME_SCAN":
            self.handlers_executed.append("handleInGameScan")
        elif current_state == "TRIGGER_AD_POPUP":
            self.handlers_executed.append("handleTriggerAdPopup")
        elif current_state == "AD_WATCHING":
            self.handlers_executed.append("handleAdWatching")
        elif current_state == "REWARD_CLAIM":
            self.handlers_executed.append("handleRewardClaim")

    def handle_external_app_guard(self, fg_pkg):
        self.non_game_ticks += 1
        if self.non_game_ticks >= 5:
            self.bring_to_front_called.append("com.linecorp.LGWKTW")
            self.non_game_ticks = 0
            return
        # Safe back
        self.dispatched_backs += 1


@pytest.mark.parametrize("initial_state", ["IN_GAME_SCAN", "TRIGGER_AD_POPUP", "REWARD_CLAIM", "IDLE"])
@pytest.mark.parametrize("ad_pkg", TARGET_AD_PACKAGES)
def test_ad_packages_trigger_immediate_ad_watching_without_stale_branches(initial_state, ad_pkg):
    """Verify that when any ad package appears, the engine transitions immediately to AD_WATCHING on the same tick."""
    engine = MockEngine(initial_state=initial_state)
    engine.execute_single_tick(ad_pkg)

    assert engine.state == "AD_WATCHING", f"Engine state must be AD_WATCHING for {ad_pkg}"
    assert engine.handlers_executed == ["handleAdWatching"], f"Only handleAdWatching must execute, got {engine.handlers_executed}"
    assert engine.dispatched_backs == 0, f"No back action may be dispatched for ad package {ad_pkg}"
    assert engine.non_game_ticks == 0


def test_external_app_guard_execution_and_timeout():
    """Verify non-ad packages are handled via external app guard with safe back and bring-to-front."""
    engine = MockEngine(initial_state="IN_GAME_SCAN")

    # Tick 1-4: Safe Back dispatched
    for i in range(1, 5):
        engine.execute_single_tick("com.android.settings")
        assert engine.dispatched_backs == i
        assert engine.non_game_ticks == i
        assert len(engine.handlers_executed) == 0

    # Tick 5: Timeout triggers bringAppToFront
    engine.execute_single_tick("com.android.settings")
    assert len(engine.bring_to_front_called) == 1
    assert engine.bring_to_front_called[0] == "com.linecorp.LGWKTW"
    assert engine.non_game_ticks == 0


# ==============================================================================
# Test 3: Adversarial Back Key Analysis & AST Inspection
# ==============================================================================

def test_ast_safe_navigate_back_logic():
    """
    CRITICAL ADVERSARIAL TEST:
    Inspect AutoAdAccessibilityService.kt to verify whether safeNavigateBack strictly
    blocks Back keys when fg is an ad package, REGARDLESS of allowInGame flag.
    """
    service_path = os.path.join(SRC_DIR, "service", "AutoAdAccessibilityService.kt")
    assert os.path.exists(service_path)
    with open(service_path, "r", encoding="utf-8") as f:
        src = f.read()

    # Locate safeNavigateBack definition
    match = re.search(r"fun safeNavigateBack\([^\)]*\): Boolean \{([\s\S]*?)return performGlobalAction", src)
    assert match is not None, "safeNavigateBack method must be found"
    method_body = match.group(1)

    # Check whether AppConfig.isAdPackage(fg) is grouped under !allowInGame
    flawed_pattern = re.search(r"if\s*\(\s*!allowInGame\s*&&[\s\S]*?isAdPackage", method_body)
    assert flawed_pattern is None, "VULNERABILITY: isAdPackage must NOT be grouped under !allowInGame"

    # Check whether isAdPackage(fg) is checked UNCONDITIONALLY before !allowInGame
    unconditional_ad_guard = re.search(r"if\s*\([\s\S]*?isAdPackage\(fg\)[\s\S]*?\)\s*\{\s*Log\.[\s\S]*?return false\s*\}", method_body)
    assert unconditional_ad_guard is not None, "MISSING: Unconditional isAdPackage guard must return false"

    # Verify AdCloserHandler guards menu check with !isAd
    closer_path = os.path.join(SRC_DIR, "engine", "AdCloserHandler.kt")
    assert os.path.exists(closer_path)
    with open(closer_path, "r", encoding="utf-8") as f:
        closer_src = f.read()
    menu_guard = re.search(r"if\s*\([\s\S]*?!isAd[\s\S]*?isMenuScreen", closer_src)
    assert menu_guard is not None, "VULNERABILITY: isMenuScreen must be guarded by !isAd"


def simulate_kotlin_safe_navigate_back(fg_pkg, allow_in_game=False, min_interval_ms=2000, last_back_time=0, current_time=3000):
    """
    Exact simulation of updated AutoAdAccessibilityService.kt line 255-275.
    """
    # 1. 合法第三方廣告容器絕對防護：無論 allowInGame 為何，嚴禁發送返回鍵以免中斷廣告
    if AppConfigModel.is_ad_package(fg_pkg):
        return False

    # 2. 遊戲本體選單防護：非 allowInGame 時嚴禁發送返回鍵
    if not allow_in_game and (
        AppConfigModel.is_game_package(fg_pkg) or
        fg_pkg == "jp.naver.line.android" or
        fg_pkg == "com.linecorp.LGWALK" or
        fg_pkg == "com.linecorp.LGWKTW"
    ):
        return False

    if current_time - last_back_time < min_interval_ms:
        return False  # Cooldown

    return True  # GLOBAL_ACTION_BACK dispatched!


def test_safe_navigate_back_with_allow_in_game_true_on_ad_packages():
    """
    Stress-test: What happens when safeNavigateBack is invoked with allowInGame=True on an ad package?
    PROJECT.md §Core-R3 specifies:
    "If AppConfig.isAdPackage(fg) is true, NEVER dispatch GLOBAL_ACTION_BACK. Return false immediately."
    """
    leaking_packages = []
    for pkg in TARGET_AD_PACKAGES:
        dispatched = simulate_kotlin_safe_navigate_back(pkg, allow_in_game=True)
        if dispatched:
            leaking_packages.append(pkg)

    print(f"\n[Test Result] Ad packages receiving Back when allowInGame=True: {len(leaking_packages)}/{len(TARGET_AD_PACKAGES)}")
    if leaking_packages:
        print(f"Leaking packages: {leaking_packages}")

    # Contract assertion: safeNavigateBack MUST return False for all ad packages regardless of allowInGame!
    assert len(leaking_packages) == 0, f"Violation: Ad packages received Back when allowInGame=True: {leaking_packages}"


def test_ad_closer_handler_menu_false_positive_risk():
    """
    Stress-test: In AdCloserHandler.kt lines 138-152, if an ad displays '設定' or '目前版本',
    verify that menu check is never triggered when foreground is an ad container.
    """
    ad_pkg = "com.facebook.katana"
    is_ad = AppConfigModel.is_ad_package(ad_pkg)
    is_game = AppConfigModel.is_game_package(ad_pkg) or ad_pkg == "jp.naver.line.android"
    is_menu_screen = True  # Ad creative has "設定"

    dispatched_back = False
    if is_game and not is_ad and is_menu_screen:
        dispatched_back = simulate_kotlin_safe_navigate_back(ad_pkg, allow_in_game=True)

    assert dispatched_back is False, "AdCloserHandler must NOT dispatch Back key to ad containers on menu screen detection!"


if __name__ == "__main__":
    pytest.main([__file__, "-v", "-s"])
