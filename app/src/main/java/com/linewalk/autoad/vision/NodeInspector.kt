package com.linewalk.autoad.vision

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.ui.FloatingOverlayService
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
 * 自動避開懸浮窗本身與手機頂部中央動態島 (dynamicSpot) 區域。
 */
class NodeInspector {

    /**
     * 在無障礙節點樹中搜尋廣告或推銷彈窗的「關閉 / 略過 / 跳過 / X」按鈕。
     */
    fun findAdCloseNode(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        blacklistedCoord: ((Int, Int) -> Boolean)? = null,
        isFullScreenAd: Boolean = false
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
                    val cx = rect.centerX()
                    val cy = rect.centerY()

                    val nodePkg = node.packageName?.toString().orEmpty()
                    // 嚴格拒絕來自外部非廣告/非遊戲應用（如 Discord、SystemUI 通知橫幅、Google Play）的任何節點！
                    val isAllowedPackage = nodePkg.isEmpty() ||
                        AppConfig.isAdPackage(nodePkg) ||
                        AppConfig.isGamePackage(nodePkg) ||
                        nodePkg == "jp.naver.line.android"
                    if (!isAllowedPackage) continue

                    // 嚴格排除頂部狀態列與中央通知橫幅 (中央通知禁區 y < 8% sh，邊角狀態列 y < 4% sh)
                    val inTopNoticeZone = cx in (screenWidth * 0.18f).toInt()..(screenWidth * 0.82f).toInt() &&
                        cy < screenHeight * 0.08f
                    if (inTopNoticeZone || cy < max(screenHeight * 0.040f, 40f)) continue

                    // 排除懸浮窗本身、頂部中間 dynamicSpot 動態島
                    // 僅在非全螢幕廣告狀態 (如主畫面彈窗) 排除首頁橫幅廣告區間 (y: 12% ~ 36%)，全螢幕廣告時放行中間內容區的略過按鈕！
                    val inOverlay = FloatingOverlayService.isInsideOverlay(cx, cy)
                    val inDynamicIsland = cy < screenHeight * 0.045f &&
                        cx in (screenWidth * 0.28f).toInt()..(screenWidth * 0.72f).toInt()
                    val inAdBannerZone = !isFullScreenAd && cy in (screenHeight * 0.12f).toInt()..(screenHeight * 0.36f).toInt()

                    val validSize = rect.width() in 16..(screenWidth * 0.45f).toInt() &&
                        rect.height() in 16..(screenHeight * 0.14f).toInt()

                    if (!inOverlay && !inDynamicIsland && !inAdBannerZone && validSize) {
                        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                        val text = node.text?.toString()?.trim().orEmpty()
                        val desc = node.contentDescription?.toString()?.trim().orEmpty()

                        val idMatch = AD_CLOSE_VIEW_IDS.any { viewId.contains(it) } ||
                            viewId.contains("skip") || viewId.contains("close")
                        val exactSymbolMatch = text in EXACT_CLOSE_SYMBOLS || desc in EXACT_CLOSE_SYMBOLS
                        val keywordMatch = AD_CLOSE_TEXT_KEYWORDS.any {
                            text.equals(it, ignoreCase = true) ||
                                desc.equals(it, ignoreCase = true) ||
                                text.contains(it, ignoreCase = true) ||
                                desc.contains(it, ignoreCase = true)
                        }
                        val isCountingDown = COUNTDOWN_HINTS.any {
                            text.contains(it) || desc.contains(it)
                        }
                        val isExcluded = EXCLUDE_TEXTS.any {
                            text.contains(it) || desc.contains(it)
                        } || viewId.contains("adjoe")

                        if (!isCountingDown && !isExcluded && (idMatch || exactSymbolMatch || keywordMatch)) {
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
            }
        }

        // 第一優先選擇明確的「略過/跳過」按鈕，其次為頂部角落 (X)
        return candidates.sortedBy { node ->
            val cy = node.bounds.centerY()
            val cx = node.bounds.centerX()
            val inTopCorner = cy < screenHeight * 0.25f &&
                (cx > screenWidth * 0.72f || cx < screenWidth * 0.28f)
            val isExplicitSkip = (node.text?.contains("略過") == true) ||
                (node.text?.contains("跳過") == true) ||
                (node.text?.contains("略过") == true) ||
                (node.text?.contains("跳过") == true) ||
                (node.text?.contains("Skip", ignoreCase = true) == true) ||
                (node.text?.contains(">>") == true) ||
                (node.text?.contains("▶") == true) ||
                (node.contentDesc?.contains("略過") == true) ||
                (node.contentDesc?.contains("跳過") == true) ||
                (node.contentDesc?.contains("略过") == true) ||
                (node.contentDesc?.contains("跳过") == true) ||
                (node.contentDesc?.contains("Skip", ignoreCase = true) == true) ||
                (node.contentDesc?.contains("Close", ignoreCase = true) == true) ||
                (node.contentDesc?.contains("關閉") == true) ||
                (node.contentDesc?.contains("关闭") == true) ||
                (node.viewId?.contains("skip", ignoreCase = true) == true)
            when {
                isExplicitSkip -> 0
                inTopCorner -> 1
                else -> 2
            }
        }.firstOrNull()
    }

    /**
     * 在無障礙節點樹中尋找包含特定關鍵字的按鈕（如「看廣告」、「領取」、「跳過」）。
     */
    fun findNodeByKeywords(
        root: AccessibilityNodeInfo?,
        keywords: List<String>,
        screenWidth: Int,
        screenHeight: Int,
        excludeKeywords: List<String> = emptyList()
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
                    val cx = rect.centerX()
                    val cy = rect.centerY()

                    val validSize = rect.width() in 24..(screenWidth * 0.9f).toInt() &&
                        rect.height() in 20..(screenHeight * 0.16f).toInt()

                    if (!FloatingOverlayService.isInsideOverlay(cx, cy) && validSize) {
                        val text = node.text?.toString()?.trim().orEmpty()
                        val desc = node.contentDescription?.toString()?.trim().orEmpty()
                        val isExcluded = excludeKeywords.any { ex ->
                            text.contains(ex, ignoreCase = true) || desc.contains(ex, ignoreCase = true)
                        }
                        if (!isExcluded) {
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
            "btn_close",
            "ad_close",
            "iv_close",
            "close_btn",
            "close_button",
            "mbridge_windwv_close",
            "tt_video_ad_close",
            "ia_tv_close_button",
            "al_skip",
            "al_btn_close",
            "al_close",
            "vungle_close",
            "interstitial_close",
            "ksad_video_close",
            "skip_button",
            "btn_skip",
            "skip_ad",
            "video_ad_skip",
            "exo_skip",
            "ad_skip",
            "action_skip"
        )

        private val EXACT_CLOSE_SYMBOLS = setOf(
            "X", "x", "✕", "✖", "×", "╳"
        )

        private val AD_CLOSE_TEXT_KEYWORDS = listOf(
            "略過廣告", "跳過廣告", "關閉廣告", "離開廣告", "退出廣告", "廣告略過",
            "略過", "跳過", "關閉", "離開", "退出", "Skip Ad", "Skip ad", "Skip Ads",
            "Skip", "Next Ad", "Next ad", "NextAd", "Next", "下一則廣告", "下一個廣告", "下一則", "下一個",
            "Close", "スキップ", "広告をスキップ", "閉じる"
        )

        private val COUNTDOWN_HINTS = listOf(
            "秒後", "還有", "seconds", "sec remaining"
        )

        private val EXCLUDE_TEXTS = listOf(
            "同意並繼續", "隱私權", "條款", "不看廣告",
            "開始遊玩", "遊玩", "輕鬆賺", "輕鬆", "adjoe", "Sponsored", "line points game"
        )
    }
}
