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

    /** 每日賓果（自動開啟賓果格與領取連線獎勵） */
    var enableBingoTask: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_BINGO, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_BINGO, value).apply()

    /** 抽抽樂（優先50抽 / 次選免費抽，嚴禁點5張） */
    var enableLotteryTask: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_LOTTERY, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_LOTTERY, value).apply()

    /** 任務（自動檢查特別/每日/每週任務並領取） */
    var enableDailyMissionTask: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_DAILY_MISSION, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_DAILY_MISSION, value).apply()

    /** 好友排行榜送禮（自動送所有好友禮物，優先送禮也送自己） */
    var enableFriendGiftTask: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_FRIEND_GIFT, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_FRIEND_GIFT, value).apply()

    /** 當彈窗沒有「看廣告」按鈕（例如廣告次數用盡或僅有一般領取）時，是否自動點一般領取 */
    var claimNormalWhenNoAd: Boolean
        get() = prefs.getBoolean(KEY_CLAIM_NORMAL_NO_AD, true)
        set(value) = prefs.edit().putBoolean(KEY_CLAIM_NORMAL_NO_AD, value).apply()

    /** 主循環掃描間隔毫秒數 (預設 1200ms) */
    var loopIntervalMs: Long
        get() = prefs.getLong(KEY_LOOP_INTERVAL_MS, 1200L).coerceIn(1000L, 1800L)
        set(value) = prefs.edit().putLong(KEY_LOOP_INTERVAL_MS, value.coerceIn(1000L, 1800L)).apply()

    /** 廣告單次最長等待秒數，超過則觸發防卡死返回鍵 (預設 48 秒，容許 30~35 秒完整影音播放與 5 秒結算卡片) */
    var maxAdWaitSeconds: Int
        get() = prefs.getInt(KEY_MAX_AD_WAIT_SEC, 48)
        set(value) = prefs.edit().putInt(KEY_MAX_AD_WAIT_SEC, value.coerceIn(20, 180)).apply()

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
        private const val KEY_ENABLE_BINGO = "enable_bingo_v18"
        private const val KEY_ENABLE_LOTTERY = "enable_lottery_v18"
        private const val KEY_ENABLE_DAILY_MISSION = "enable_daily_mission_v18"
        private const val KEY_ENABLE_FRIEND_GIFT = "enable_friend_gift_v18"
        private const val KEY_CLAIM_NORMAL_NO_AD = "claim_normal_no_ad"
        private const val KEY_LOOP_INTERVAL_MS = "loop_interval_ms"
        private const val KEY_MAX_AD_WAIT_SEC = "max_ad_wait_sec"
        private const val KEY_MATCH_THRESHOLD = "match_threshold"
        private const val KEY_TARGET_PACKAGES = "target_game_packages"

        val DEFAULT_GAME_PACKAGES = setOf(
            "com.linecorp.LGWKTW",     // LINE 散步趣 (台灣版 Google Play)
            "com.linecorp.LGWALK",     // LINE 散步趣 / LINE WALK 主套件名 (日版/國際版)
            "jp.naver.line.android"    // 若從 LINE 主程式內嵌開啟時的備援識別
        )

        fun isGamePackage(pkg: String?): Boolean {
            if (pkg.isNullOrBlank()) return false
            return pkg == "com.linecorp.LGWKTW" ||
                   pkg == "com.linecorp.LGWALK" ||
                   pkg.startsWith("com.linecorp.LGWK")
        }

        /** 已知外部跳轉黑名單（誤點廣告跳轉時需立即按 BACK 返回） */
        val EXTERNAL_REDIRECT_PACKAGES = setOf(
            "com.android.vending",             // Google Play 商店
            "com.android.chrome",              // Chrome 瀏覽器
            "com.google.android.apps.chrome",  // Chrome 應用
            "com.sec.android.app.sbrowser",    // 三星瀏覽器
            "org.mozilla.firefox",             // Firefox
            "com.microsoft.emmx",              // Edge
            "com.brave.browser",               // Brave
            "com.opera.browser",               // Opera
            "com.opera.mini.native",           // Opera Mini
            "com.mi.globalbrowser",            // 小米瀏覽器
            "com.coloros.browser",             // OPPO 瀏覽器
            "com.heytap.browser",              // Heytap 瀏覽器
            "com.vivo.browser",                // Vivo 瀏覽器
            "com.huawei.browser"               // 華為瀏覽器
        )

        fun isRedirectPackage(pkg: String?): Boolean {
            if (pkg.isNullOrBlank()) return false
            if (isAdPackage(pkg)) return false
            if (pkg in EXTERNAL_REDIRECT_PACKAGES) return true
            val lower = pkg.lowercase()
            return lower.contains("browser") ||
                lower.contains("chrome") ||
                lower.contains("vending")
        }

        /**
         * 已知第三方官方廣告服務提供套件（合法前景廣告容器，全面豁免外部應用保護）
         */
        val KNOWN_AD_PACKAGES = setOf(
            // Meta Audience Network
            "com.facebook.katana",             // Facebook App 廣告容器
            "com.facebook.orca",               // Messenger 廣告容器
            "com.facebook.lite",               // Facebook Lite 廣告容器
            "com.facebook.mlite",              // Messenger Lite 廣告容器
            "com.instagram.android",           // Instagram 廣告容器
            // Google Mobile Ads / AdMob
            "com.google.android.gms",          // Google Play 服務廣告 Activity (AdActivity)
            "com.google.android.as",           // Android 系統廣告服務
            "com.google.android.webview",       // WebView 廣告容器
            // Unity Ads
            "com.unity3d.player",              // Unity Player 廣告容器
            "com.unity3d.ads",                 // Unity Ads
            // AppLovin / MAX
            "com.applovin",                    // AppLovin
            "com.applovin.sdk",                // AppLovin SDK
            // Chrome Custom Tabs 廣告容器
            "com.android.chrome",              // Chrome Custom Tabs 廣告容器
            "androidx.browser.customtabs"      // Jetpack Custom Tabs 模組標識
        )

        fun isAdPackage(pkg: String?): Boolean {
            if (pkg.isNullOrBlank()) return false
            if (pkg in KNOWN_AD_PACKAGES) return true
            val lower = pkg.lowercase()
            return lower.startsWith("com.facebook.ads") ||
                lower.startsWith("com.facebook.katana") ||
                lower.startsWith("com.facebook.orca") ||
                lower.contains("audience_network") ||
                lower.contains("admob") ||
                lower.contains("google.android.gms") ||
                lower.contains("google.android.as") ||
                lower.contains("unity3d") ||
                lower.contains("unity.ads") ||
                lower.contains("unityads") ||
                lower.contains("applovin") ||
                lower.contains("ironsource") ||
                lower.contains("vungle") ||
                lower.contains("liftoff") ||
                lower.contains("mintegral") ||
                lower.contains("mbridge") ||
                lower.contains("pangle") ||
                lower.startsWith("com.bytedance.pangle") ||
                lower.contains("inmobi") ||
                lower.contains("customtabs")
        }
    }
}
