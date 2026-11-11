package com.linewalk.autoad.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 應用程式版本更新檢查器 (UpdateChecker)：
 * - 每次 App 啟動時於背景非同步檢查 GitHub Releases 官方 API
 * - 智慧語意化版本號比對 (支援跨大版本、中版本與補丁版號比較)
 * - 取得最新版 APK 直接下載連結與更新說明
 */
object UpdateChecker {

    private const val GITHUB_LATEST_RELEASE_URL =
        "https://api.github.com/repos/Kimi10117/LineWalkAutoAd/releases/latest"
    private const val GITHUB_LATEST_TAG_URL =
        "https://api.github.com/repos/Kimi10117/LineWalkAutoAd/releases/tags/latest-apk"
    private const val DEFAULT_FALLBACK_APK_URL =
        "https://github.com/Kimi10117/LineWalkAutoAd/releases/download/latest-apk/LineWalkAutoAd.apk"

    // 檢查頻率保護：2 分鐘內不重複發送網路請求
    private var lastCheckTimeMs: Long = 0L
    private var cachedUpdateInfo: UpdateInfo? = null
    private const val CACHE_DURATION_MS = 2 * 60 * 1000L

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val downloadUrl: String,
        val releaseTitle: String = "",
        val releaseNotes: String = ""
    )

    /**
     * 比對遠端版本號是否高於目前版本號
     * 範例：isNewerVersion("v1.15.75", "1.15.74") -> true
     */
    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val rClean = remoteVersion.trim().trimStart('v', 'V')
        val cClean = currentVersion.trim().trimStart('v', 'V')
        if (rClean.isEmpty() || cClean.isEmpty()) return false
        if (rClean == cClean) return false

        val rParts = rClean.split('.').map { it.toIntOrNull() ?: 0 }
        val cParts = cClean.split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(rParts.size, cParts.size)

        for (i in 0 until maxLen) {
            val rNum = rParts.getOrElse(i) { 0 }
            val cNum = cParts.getOrElse(i) { 0 }
            if (rNum > cNum) return true
            if (rNum < cNum) return false
        }
        return false
    }

    /**
     * 於背景非同步查詢最新版本資訊
     */
    suspend fun checkForUpdate(
        currentVersion: String,
        forceRefresh: Boolean = false
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedUpdateInfo != null && (now - lastCheckTimeMs < CACHE_DURATION_MS)) {
            return@withContext cachedUpdateInfo
        }

        val jsonString = fetchJsonFromUrl(GITHUB_LATEST_RELEASE_URL)
            ?: fetchJsonFromUrl(GITHUB_LATEST_TAG_URL)
            ?: return@withContext null

        try {
            val root = JSONObject(jsonString)
            val tagName = root.optString("tag_name", "").trim()
            val releaseTitle = root.optString("name", "").trim()
            val releaseNotes = root.optString("body", "").trim()

            var directApkUrl = ""
            val assets = root.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    val downloadUrl = asset.optString("browser_download_url", "")
                    if (name.endsWith(".apk", ignoreCase = true) || name.contains("LineWalkAutoAd", ignoreCase = true)) {
                        directApkUrl = downloadUrl
                        break
                    }
                }
            }

            if (directApkUrl.isEmpty()) {
                directApkUrl = if (tagName.isNotEmpty()) {
                    "https://github.com/Kimi10117/LineWalkAutoAd/releases/download/$tagName/LineWalkAutoAd.apk"
                } else {
                    DEFAULT_FALLBACK_APK_URL
                }
            }

            val hasUpdate = isNewerVersion(tagName, currentVersion)
            val info = UpdateInfo(
                hasUpdate = hasUpdate,
                latestVersion = if (tagName.startsWith("v", ignoreCase = true)) tagName else "v$tagName",
                downloadUrl = directApkUrl,
                releaseTitle = releaseTitle,
                releaseNotes = releaseNotes
            )

            lastCheckTimeMs = now
            cachedUpdateInfo = info
            info
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchJsonFromUrl(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "LineWalkAutoAd-Android")

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                val sb = java.lang.StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                sb.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
