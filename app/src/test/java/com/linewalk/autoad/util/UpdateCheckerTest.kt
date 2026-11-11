package com.linewalk.autoad.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UpdateChecker 單元測試：
 * 驗證語意化版本號比對邏輯在各類格式（帶 v、不帶 v、跨補丁版本、跨次版本、重大更新）下的正確性。
 */
class UpdateCheckerTest {

    @Test
    fun testIsNewerVersion_patchUpgrade() {
        // v1.15.75 > v1.15.74
        assertTrue(UpdateChecker.isNewerVersion("v1.15.75", "1.15.74"))
        assertTrue(UpdateChecker.isNewerVersion("1.15.75", "v1.15.74"))
    }

    @Test
    fun testIsNewerVersion_minorUpgrade() {
        // v1.16.0 > v1.15.74
        assertTrue(UpdateChecker.isNewerVersion("v1.16.0", "1.15.74"))
    }

    @Test
    fun testIsNewerVersion_majorUpgrade() {
        // v2.0.0 > v1.15.74
        assertTrue(UpdateChecker.isNewerVersion("v2.0.0", "1.15.74"))
    }

    @Test
    fun testIsNewerVersion_sameVersion() {
        // v1.15.74 == v1.15.74
        assertFalse(UpdateChecker.isNewerVersion("v1.15.74", "1.15.74"))
        assertFalse(UpdateChecker.isNewerVersion("1.15.74", "v1.15.74"))
    }

    @Test
    fun testIsNewerVersion_olderVersion() {
        // v1.15.73 < v1.15.74
        assertFalse(UpdateChecker.isNewerVersion("v1.15.73", "1.15.74"))
        assertFalse(UpdateChecker.isNewerVersion("1.14.99", "1.15.74"))
    }

    @Test
    fun testIsNewerVersion_emptyOrInvalid() {
        assertFalse(UpdateChecker.isNewerVersion("", "1.15.74"))
        assertFalse(UpdateChecker.isNewerVersion("v1.15.75", ""))
    }
}
