package com.linewalk.autoad.vision

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

data class MatchedNode(
    val label: String,
    val bounds: Rect,
    val viewId: String?,
    val text: String?,
    val contentDesc: String?
)

/**
 * 第一層辨識：無障礙 UI 節點樹掃描器 (NodeInspector)。
 * 零耗電、毫秒級反應，專門抓取原生廣告 SDK (AdMob, AppLovin, Unity, Mintegral, Vungle 等)
 * 暴露在節點樹上的關閉按鈕或遊戲內原生按鈕。
 */
class NodeInspector {

    /**
     * 在無障礙節點樹中搜尋廣告的「關閉 / 略過 / X」按鈕。
     */
    fun findAdCloseNode(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        blacklistedCoord: ((Int, Int) -> Boolean)? = null
    ): MatchedNode? {
        if (root == null) return null

        val candidates = mutableListOf<MatchedNode>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            try {
                if (node.isVisibleToUser) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)

                    // 過濾掉無效尺寸或佔據超過畫面 35% 寬高的全螢幕廣告背景節點
                    val validSize = rect.width() in 16..(screenWidth * 0.35f).toInt() &&
                        rect.height() in 16..(screenHeight * 0.18f).toInt()

                    if (validSize) {
                        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                        val text = node.text?.toString()?.trim().orEmpty()
                        val desc = node.contentDescription?.toString()?.trim().orEmpty()

                        val idMatch = AD_CLOSE_VIEW_IDS.any { viewId.contains(it) }
                        val exactSymbolMatch = text in EXACT_CLOSE_SYMBOLS || desc in EXACT_CLOSE_SYMBOLS
                        val keywordMatch = AD_CLOSE_TEXT_KEYWORDS.any {
                            text.contains(it, ignoreCase = true) || desc.contains(it, ignoreCase = true)
                        }
                        // 若文字顯示「12秒後可略過」代表還在倒數，不可點擊
                        val isCountingDown = COUNTDOWN_HINTS.any {
                            text.contains(it) || desc.contains(it)
                        }

                        if (!isCountingDown && (idMatch || exactSymbolMatch || keywordMatch)) {
                            val cx = rect.centerX()
                            val cy = rect.centerY()
                            if (blacklistedCoord?.invoke(cx, cy) != true) {
                                candidates.add(
                                    MatchedNode(
                                        label = "Node[id=${viewId.substringAfterLast('/')}, txt=$text, desc=$desc]",
                                        bounds = rect,
                                        viewId = viewId,
                                        text = text,
                                        contentDesc = desc
                                    )
                                )
                            }
                        }
                    }
                }

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            } catch (_: Exception) {
                // 忽略回收或跨程序節點例外
            }
        }

        // 優先選擇位於畫面頂部 25% 區域 (左上或右上角) 的關閉按鈕
        return candidates.sortedBy { node ->
            val inTopCorner = node.bounds.centerY() < screenHeight * 0.25f &&
                (node.bounds.centerX() > screenWidth * 0.72f || node.bounds.centerX() < screenWidth * 0.28f)
            if (inTopCorner) 0 else 1
        }.firstOrNull()
    }

    /**
     * 在無障礙節點樹中尋找包含特定關鍵字的按鈕（如「看廣告」、「領取」、「確認」）。
     */
    fun findNodeByKeywords(
        root: AccessibilityNodeInfo?,
        keywords: List<String>,
        screenWidth: Int,
        screenHeight: Int
    ): MatchedNode? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            try {
                if (node.isVisibleToUser) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    val validSize = rect.width() in 24..(screenWidth * 0.9f).toInt() &&
                        rect.height() in 20..(screenHeight * 0.25f).toInt()

                    if (validSize) {
                        val text = node.text?.toString()?.trim().orEmpty()
                        val desc = node.contentDescription?.toString()?.trim().orEmpty()
                        val matchedKw = keywords.firstOrNull { kw ->
                            text.contains(kw, ignoreCase = true) || desc.contains(kw, ignoreCase = true)
                        }
                        if (matchedKw != null) {
                            return MatchedNode(
                                label = "NodeKw[$matchedKw]",
                                bounds = rect,
                                viewId = node.viewIdResourceName,
                                text = text,
                                contentDesc = desc
                            )
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    companion object {
        private val AD_CLOSE_VIEW_IDS = listOf(
            "close",
            "skip",
            "dismiss",
            "btn_close",
            "ad_close",
            "iv_close",
            "close_btn",
            "close_button",
            "mbridge_windwv_close",
            "tt_video_ad_close",
            "ia_tv_close_button",
            "al_skip",
            "applovin",
            "vungle_close"
        )

        private val EXACT_CLOSE_SYMBOLS = setOf(
            "X", "x", "✕", "✖", "×", "╳", ">>", "≫", "＞"
        )

        private val AD_CLOSE_TEXT_KEYWORDS = listOf(
            "關閉廣告", "略過廣告", "跳過廣告", "關閉", "略過", "跳過", "Skip", "Close", "Dismiss"
        )

        private val COUNTDOWN_HINTS = listOf(
            "秒後", "還有", "seconds", "sec remaining"
        )
    }
}
