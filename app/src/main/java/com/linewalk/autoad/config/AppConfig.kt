package com.linewalk.autoad.config

import android.content.Context
import android.content.SharedPreferences

/**
 * 儲存與管理《LINE 散步趣》自動看廣告助手的所有設定項目。
 */
class AppConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 優先任務：步數獎勵（水瓶集滿看廣告領金幣） */
    var enableStepBottleTask: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_STEP_BOTTLE, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_STEP_BOTTLE, value).apply()

    /** 移動距離獎勵（火車/交通工具計量條集滿看廣告領金幣） */
    var enableMoveGaugeTask: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_MOVE_GAUGE, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_MOVE_GAUGE, value).apply()

    /** 放置/時間獎勵（角色定時產出看廣告領金幣） */
    var enableIdleRewardTask: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_IDLE_REWARD, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_IDLE_REWARD, value).apply()

    /** 遊戲分頁獎勵 / 幸運扭蛋 / 刮刮樂 */
    var enableGachaTask: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_GACHA, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_GACHA, value).apply()

    /** 當彈窗沒有「看廣告」按鈕（例如廣告次數用盡或僅有一般領取）時，是否自動點一般領取 */
    var claimNormalWhenNoAd: Boolean
        get() = prefs.getBoolean(KEY_CLAIM_NORMAL_NO_AD, true)
        set(value) = prefs.edit().putBoolean(KEY_CLAIM_NORMAL_NO_AD, value).apply()

    /** 主循環掃描間隔毫秒數 (預設 2200ms) */
    var loopIntervalMs: Long
        get() = prefs.getLong(KEY_LOOP_INTERVAL_MS, 2200L)
        set(value) = prefs.edit().putLong(KEY_LOOP_INTERVAL_MS, value.coerceIn(1200L, 10000L)).apply()

    /** 廣告單次最長等待秒數，超過則觸發防卡死返回鍵 (預設 65 秒) */
    var maxAdWaitSeconds: Int
        get() = prefs.getInt(KEY_MAX_AD_WAIT_SEC, 65)
        set(value) = prefs.edit().putInt(KEY_MAX_AD_WAIT_SEC, value.coerceIn(25, 180)).apply()

    /** 圖像模板匹配相似度門檻 (0.70f ~ 0.95f，預設 0.80f) */
    var templateMatchThreshold: Float
        get() = prefs.getFloat(KEY_MATCH_THRESHOLD, 0.80f)
        set(value) = prefs.edit().putFloat(KEY_MATCH_THRESHOLD, value.coerceIn(0.65f, 0.98f)).apply()

    /** 目標遊戲 Package Name 清單（支援台版/日版 LINE 散步趣） */
    var targetGamePackages: Set<String>
        get() = prefs.getStringSet(KEY_TARGET_PACKAGES, DEFAULT_GAME_PACKAGES) ?: DEFAULT_GAME_PACKAGES
        set(value) = prefs.edit().putStringSet(KEY_TARGET_PACKAGES, value).apply()

    companion object {
        private const val PREFS_NAME = "line_walk_auto_ad_prefs"
        private const val KEY_ENABLE_STEP_BOTTLE = "enable_step_bottle"
        private const val KEY_ENABLE_MOVE_GAUGE = "enable_move_gauge"
        private const val KEY_ENABLE_IDLE_REWARD = "enable_idle_reward"
        private const val KEY_ENABLE_GACHA = "enable_game_tab_v15"
        private const val KEY_CLAIM_NORMAL_NO_AD = "claim_normal_no_ad"
        private const val KEY_LOOP_INTERVAL_MS = "loop_interval_ms"
        private const val KEY_MAX_AD_WAIT_SEC = "max_ad_wait_sec"
        private const val KEY_MATCH_THRESHOLD = "match_threshold"
        private const val KEY_TARGET_PACKAGES = "target_game_packages"

        val DEFAULT_GAME_PACKAGES = setOf(
            "com.linecorp.LGWALK",     // LINE 散步趣 / LINE WALK 主套件名
            "jp.naver.line.android"    // 若從 LINE 主程式內嵌開啟時的備援識別
        )

        /** 已知外部跳轉黑名單（誤點廣告跳轉時需立即按 BACK 返回） */
        val EXTERNAL_REDIRECT_PACKAGES = setOf(
            "com.android.vending",             // Google Play 商店
            "com.android.chrome",              // Chrome 瀏覽器
            "com.sec.android.app.sbrowser",    // 三星瀏覽器
            "org.mozilla.firefox",             // Firefox
            "com.microsoft.emmx"               // Edge
        )
    }
}
