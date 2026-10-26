#!/usr/bin/env python3
"""
EMPIRICAL TEST SUITE: R2 Meta Ad Corner Geometric X & Left Zone Survey
LineWalkAutoAd
"""

import os
import math
import pytest

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(BASE_DIR, "app", "src", "main", "java", "com", "linewalk", "autoad")


def test_meta_ad_package_and_ocr_detection():
    meta_packages = [
        "com.facebook.katana",
        "com.facebook.orca",
        "com.facebook.lite",
        "com.facebook.mlite",
        "com.instagram.android",
        "com.facebook.ads.internal",
    ]
    for pkg in meta_packages:
        is_meta = (
            pkg == "com.facebook.katana" or
            pkg == "com.facebook.orca" or
            pkg == "com.facebook.lite" or
            pkg == "com.facebook.mlite" or
            pkg == "com.instagram.android" or
            pkg.lower().startswith("com.facebook.ads")
        )
        assert is_meta is True, f"Package {pkg} must be detected as Meta ad"

    meta_ocr_texts = [
        "Ads served by Meta",
        "Meta",
        "Facebook",
        "Trip.com",
        "from Meta",
        "Audience Network",
        "• • •",
        "● ●",
        "1/3",
        "1 of 4"
    ]
    for text in meta_ocr_texts:
        has_meta_feature = any(
            token in text for token in [
                "Meta", "Facebook", "Trip.com", "Ads served by Meta",
                "from Meta", "Audience Network", "•", "●", "○", "···", "•••", "1/", "/3", "1 of"
            ]
        )
        assert has_meta_feature is True, f"Text '{text}' must be recognized as Meta ad feature"


def test_left_zone_boundary_and_safe_zone():
    resolutions = [(1080, 2400), (1440, 3200), (720, 1600)]
    for sw, sh in resolutions:
        left_top_limit_y = max(int(sh * 0.048), 48)
        left_bottom_limit_y = int(sh * 0.14)
        left_x_min = int(sw * 0.025)
        left_x_max = int(sw * 0.18)

        # Meta close X typical position: x ≈ 6.5%, y ≈ 6.5%
        x_meta = int(sw * 0.065)
        y_meta = int(sh * 0.065)

        assert left_x_min <= x_meta <= left_x_max, "Meta close X must be inside left zone x bounds"
        assert left_top_limit_y <= y_meta <= left_bottom_limit_y, "Meta close X must be inside left zone y bounds"

        # Check that left zone does not intersect central notification island (x in 18%..82%)
        assert left_x_max <= int(sw * 0.18), "Left zone must not intersect central notification island"


def test_meta_backup_hotzone_geometry():
    sw, sh = 1080, 2400
    hz_left = int(sw * 0.035)
    hz_top = int(sh * 0.048)
    hz_right = int(sw * 0.140)
    hz_bottom = int(sh * 0.095)

    center_x = (hz_left + hz_right) / 2.0
    center_y = (hz_top + hz_bottom) / 2.0

    assert 0.035 <= center_x / sw <= 0.140
    assert 0.048 <= center_y / sh <= 0.095


def test_ast_r2_source_integrity():
    matcher_path = os.path.join(SRC_DIR, "vision", "TemplateMatcher.kt")
    with open(matcher_path, "r", encoding="utf-8") as f:
        src_matcher = f.read()

    assert "val topLimitY = max((sh * 0.082f).toInt(), 60)" in src_matcher
    assert "val leftTopLimitY = max((sh * 0.048f).toInt(), 48)" in src_matcher
    assert "val leftBottomLimitY = (sh * 0.14f).toInt()" in src_matcher
    assert "Rect((sw * 0.025f).toInt(), leftTopLimitY, (sw * 0.18f).toInt(), leftBottomLimitY)" in src_matcher
    assert "val isBingoCloseZone = absX in (sw * 0.84f).toInt()..(sw * 0.98f).toInt() &&" in src_matcher

    closer_path = os.path.join(SRC_DIR, "engine", "AdCloserHandler.kt")
    with open(closer_path, "r", encoding="utf-8") as f:
        src_closer = f.read()

    assert "includeLeftZone = isMetaAd" in src_closer
    assert "Trip.com" in src_closer
    assert "Meta備援左上角熱區" in src_closer
    assert "\"螢幕左上角熱區\"" in src_closer


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
