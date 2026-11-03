"""
Milestone 3 獨立挑戰者 2 (challenger_m3_2) 深度對抗性審計測試
1. 全域零 Emoji 嚴格 Unicode 區間掃描 (Commit 68e0534 diff, commit message, RELEASE_NOTES.md, 及全量 Kotlin 源碼)
2. 繁體中文純潔度檢驗 (檢查是否混雜純簡體字元)
"""

import os
import subprocess
import unicodedata
import pytest

REPO_ROOT = r"C:\Users\kimic\.gemini\antigravity\scratch\LineWalkAutoAd"
COMMIT_HASH = "68e0534"

# 嚴格禁止的 Emoji 及圖形符號 Unicode 區間
EMOJI_RANGES = [
    (0x1F600, 0x1F64F, "Emoticons"),
    (0x1F300, 0x1F5FF, "Misc Symbols and Pictographs"),
    (0x1F680, 0x1F6FF, "Transport and Map"),
    (0x1F700, 0x1F77F, "Alchemical Symbols"),
    (0x1F780, 0x1F7FF, "Geometric Shapes Extended"),
    (0x1F800, 0x1F8FF, "Supplemental Arrows-C"),
    (0x1F900, 0x1F9FF, "Supplemental Symbols and Pictographs"),
    (0x1FA00, 0x1FA6F, "Chess Symbols"),
    (0x1FA70, 0x1FAFF, "Symbols and Pictographs Extended-A"),
    (0x1F000, 0x1F02F, "Mahjong Tiles"),
    (0x1F0A0, 0x1F0FF, "Playing Cards"),
    (0x1F1E0, 0x1F1FF, "Regional Indicator Symbols (Flags)"),
    (0x2300, 0x23FF, "Misc Technical"),
    (0x2600, 0x26FF, "Misc Symbols"),
    (0x2700, 0x27BF, "Dingbats"),
    (0x2B50, 0x2B55, "Stars and circles"),
    (0xFE00, 0xFE0F, "Variation Selectors"),
    (0x1F3FB, 0x1F3FF, "Emoji Skin Tones"),
    (0x200D, 0x200D, "Zero Width Joiner"),
]

# 合法常規排版符號白名單 (乘號、叉號)
ALLOWED_EXCEPTIONS = {
    0x00D7: "MULTIPLICATION SIGN (\u00d7)",
    0x2715: "MULTIPLICATION X (\u2715)",
    0x2716: "HEAVY MULTIPLICATION X (\u2716)",
}

# 常見非繁體、純簡體字元集合 (Traditional Chinese Purity Check)
# 收集簡化字中與繁體字形有明確區別且在繁體中文環境中視為簡體字之字元
SIMPLIFIED_ONLY_CHARS = set(
    "个这为与对们发经动关连现进开报认证检测试链码门闭选项题终类统阶段单节标签视频录图"
    "获奖励数据执行结果状态线程调试显示隐藏错误异常处理页面导航栏按钮点击弹窗确认取消继续"
    "跳转广告倒计时秒数优化修复规范标准严紧张扩缩宽坚导览侧顶底边距离范围扫描轮廓识别"
    "算法模式参数阈值缓冲存储闪退崩溃挂起锁死循环愈恢复归还仅构级别约束战胜负责审核评判"
    "决裁断备适配环设置语写读输专案则权胜负质阶尽网联设拟触频库释"
)
# 排除在繁簡中通用的字元 (如: 告, 查, 按, 秒, 航, 窗, 延, 存, 模, 式, 法, 案, 根, 置, 境, 數等繁體字形)
# 精確定義：字元本身必須為簡體字形，對應繁體為另一字形
SIMPLIFIED_MAPPING = {
    '个': '個', '这': '這', '为': '為', '与': '與', '对': '對', '们': '們',
    '发': '發', '经': '經', '动': '動', '关': '關', '连': '連', '现': '現',
    '进': '進', '开': '開', '报': '報', '认': '認', '证': '證', '检': '檢',
    '测': '測', '试': '試', '链': '鏈', '码': '碼', '门': '門', '闭': '閉',
    '选': '選', '项': '項', '题': '題', '终': '終', '类': '類', '统': '統',
    '阶': '階', '段': '段', '单': '單', '节': '節', '标': '標', '签': '籤/簽',
    '视': '視', '频': '頻', '录': '錄', '图': '圖', '获': '獲', '奖': '獎',
    '励': '勵', '数': '數', '据': '據', '执': '執', '态': '態', '线': '線',
    '调': '調', '显': '顯', '隐': '隱', '错': '錯', '误': '誤', '异': '異',
    '处': '處', '页': '頁', '栏': '欄', '钮': '鈕', '点': '點', '击': '擊',
    '弹': '彈', '确': '確', '续': '續', '广': '廣', '计': '計', '时': '時',
    '优': '優', '复': '復/複', '范': '範', '准': '準', '备': '備', '适': '適',
    '严': '嚴', '紧': '緊', '张': '張', '扩': '擴', '缩': '縮', '宽': '寬',
    '坚': '堅', '导': '導', '览': '覽', '侧': '側', '顶': '頂', '边': '邊',
    '离': '離', '围': '圍', '扫': '掃', '轮': '輪', '廓': '廓', '识': '識',
    '别': '別', '参': '參', '阈': '閾', '缓': '緩', '冲': '衝', '储': '儲',
    '闪': '閃', '溃': '潰', '挂': '掛', '锁': '鎖', '环': '環', '愈': '癒',
    '归': '歸', '还': '還', '仅': '僅', '构': '構', '级': '級', '约': '約',
    '责': '責', '审': '審', '核': '核', '评': '評', '决': '決', '裁': '裁',
    '断': '斷', '尽': '盡', '网': '網', '联': '聯', '设': '設', '拟': '擬',
    '触': '觸', '释': '釋', '质': '質', '样': '樣', '体': '體', '总': '總',
    '无': '無', '专': '專', '则': '則', '权': '權', '胜': '勝', '负': '負',
}
PURE_SIMPLIFIED_CHARS = set(SIMPLIFIED_MAPPING.keys()) - {'段', '核'}  # '段', '核' 繁簡同形


def check_emojis_in_text(text: str, source_name: str = ""):
    """檢查文字中是否存在 Emoji 或非法圖形符號"""
    violations = []
    for line_no, line in enumerate(text.splitlines(), start=1):
        for col_no, ch in enumerate(line, start=1):
            cp = ord(ch)
            if cp in ALLOWED_EXCEPTIONS:
                continue
            for start, end, range_name in EMOJI_RANGES:
                if start <= cp <= end:
                    violations.append({
                        "source": source_name,
                        "line": line_no,
                        "col": col_no,
                        "char": ch,
                        "codepoint": f"U+{cp:04X}",
                        "range": range_name,
                        "context": line.strip()
                    })
                    break
    return violations


def check_simplified_chinese_in_text(text: str, source_name: str = ""):
    """檢查文字中是否存在純簡體字元"""
    violations = []
    for line_no, line in enumerate(text.splitlines(), start=1):
        # 忽略 diff 中被刪除的行 (以 - 開頭且不是 ---)
        if line.startswith("-") and not line.startswith("---"):
            continue
        for col_no, ch in enumerate(line, start=1):
            if ch in PURE_SIMPLIFIED_CHARS:
                traditional_counterpart = SIMPLIFIED_MAPPING.get(ch, "未知")
                violations.append({
                    "source": source_name,
                    "line": line_no,
                    "col": col_no,
                    "char": ch,
                    "expected_traditional": traditional_counterpart,
                    "context": line.strip()
                })
    return violations


class TestMilestone3EmojiAndChineseAudit:
    """Milestone 3 深度對抗性驗證：零 Emoji 與繁體中文純潔度"""

    def test_git_commit_message_zero_emoji(self):
        """驗證最新 Git commit 68e0534 提交訊息嚴格零 Emoji"""
        cmd = ["git", "-C", REPO_ROOT, "log", "-1", "--pretty=%B", COMMIT_HASH]
        res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
        assert res.returncode == 0, f"Git log failed: {res.stderr}"
        commit_msg = res.stdout

        violations = check_emojis_in_text(commit_msg, f"Commit {COMMIT_HASH} Message")
        assert len(violations) == 0, f"在 Commit 訊息中發現 Emoji: {violations}"

    def test_git_commit_message_traditional_chinese_purity(self):
        """驗證最新 Git commit 68e0534 提交訊息 100% 繁體中文，無簡體中文混雜"""
        cmd = ["git", "-C", REPO_ROOT, "log", "-1", "--pretty=%B", COMMIT_HASH]
        res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
        assert res.returncode == 0, f"Git log failed: {res.stderr}"
        commit_msg = res.stdout

        violations = check_simplified_chinese_in_text(commit_msg, f"Commit {COMMIT_HASH} Message")
        assert len(violations) == 0, f"在 Commit 訊息中發現簡體字: {violations}"

    def test_release_notes_zero_emoji(self):
        """驗證專案根目錄 RELEASE_NOTES.md 嚴格零 Emoji"""
        rel_notes_path = os.path.join(REPO_ROOT, "RELEASE_NOTES.md")
        assert os.path.exists(rel_notes_path), "RELEASE_NOTES.md 不存在"
        with open(rel_notes_path, "r", encoding="utf-8") as f:
            content = f.read()

        violations = check_emojis_in_text(content, "RELEASE_NOTES.md")
        assert len(violations) == 0, f"在 RELEASE_NOTES.md 中發現 Emoji: {violations}"

    def test_release_notes_traditional_chinese_purity(self):
        """驗證專案根目錄 RELEASE_NOTES.md 100% 繁體中文，無簡體中文混雜"""
        rel_notes_path = os.path.join(REPO_ROOT, "RELEASE_NOTES.md")
        with open(rel_notes_path, "r", encoding="utf-8") as f:
            content = f.read()

        violations = check_simplified_chinese_in_text(content, "RELEASE_NOTES.md")
        assert len(violations) == 0, f"在 RELEASE_NOTES.md 中發現簡體字: {violations}"

    def test_commit_diff_zero_emoji_in_added_lines(self):
        """驗證最新 Git 提交 68e0534 之 diff 新增/修改行嚴格零 Emoji"""
        cmd = ["git", "-C", REPO_ROOT, "show", COMMIT_HASH]
        res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
        assert res.returncode == 0, f"Git show failed: {res.stderr}"
        diff_text = res.stdout

        # 僅檢查新增/修改行 (+)
        added_lines = []
        for line in diff_text.splitlines():
            if line.startswith("+") and not line.startswith("+++"):
                added_lines.append(line[1:])
        added_content = "\n".join(added_lines)

        violations = check_emojis_in_text(added_content, f"Commit {COMMIT_HASH} Added Lines")
        assert len(violations) == 0, f"在 Commit diff 新增行中發現 Emoji: {violations}"

    def test_all_modified_kotlin_files_zero_emoji(self):
        """驗證本次 Milestone 所修改之核心 Kotlin 檔案全域無任何 Emoji"""
        kotlin_files = [
            "app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt",
            "app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt",
            "app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt",
            "app/src/main/java/com/linewalk/autoad/vision/TemplateMatcher.kt",
            "app/src/main/java/com/linewalk/autoad/tasks/FriendGiftTask.kt",
            "app/src/main/java/com/linewalk/autoad/tasks/LotteryTask.kt",
            "app/src/main/java/com/linewalk/autoad/ui/FloatingOverlayService.kt",
            "app/src/main/java/com/linewalk/autoad/ui/MainActivity.kt",
        ]

        all_violations = []
        for rel_path in kotlin_files:
            abs_path = os.path.join(REPO_ROOT, rel_path)
            assert os.path.exists(abs_path), f"Kotlin 檔案不存在: {rel_path}"
            with open(abs_path, "r", encoding="utf-8") as f:
                content = f.read()
            violations = check_emojis_in_text(content, rel_path)
            all_violations.extend(violations)

        assert len(all_violations) == 0, f"在 Kotlin 檔案中發現 Emoji 殘留: {all_violations}"

    def test_all_kotlin_files_in_repository_zero_emoji(self):
        """全面對抗性擴展：全掃描 app/src/main/java 下所有 Kotlin 原始碼，確保全專案零 Emoji"""
        java_root = os.path.join(REPO_ROOT, "app", "src", "main", "java")
        all_violations = []
        scanned_count = 0

        for root, _, files in os.walk(java_root):
            for file in files:
                if file.endswith(".kt"):
                    scanned_count += 1
                    path = os.path.join(root, file)
                    rel_path = os.path.relpath(path, REPO_ROOT)
                    with open(path, "r", encoding="utf-8", errors="replace") as f:
                        content = f.read()
                    violations = check_emojis_in_text(content, rel_path)
                    all_violations.extend(violations)

        assert scanned_count > 0, "未找到任何 Kotlin 原始碼檔案"
        assert len(all_violations) == 0, f"在全專案 Kotlin 源碼中發現 Emoji: {all_violations}"

    def test_layout_xml_and_readme_zero_emoji(self):
        """驗證 activity_main.xml、README.md、PROJECT.md 零 Emoji"""
        extra_files = [
            "app/src/main/res/layout/activity_main.xml",
            "README.md",
            "PROJECT.md",
        ]

        all_violations = []
        for rel_path in extra_files:
            abs_path = os.path.join(REPO_ROOT, rel_path)
            if not os.path.exists(abs_path):
                continue
            with open(abs_path, "r", encoding="utf-8") as f:
                content = f.read()
            violations = check_emojis_in_text(content, rel_path)
            all_violations.extend(violations)

        assert len(all_violations) == 0, f"在資源/文件檔案中發現 Emoji: {all_violations}"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
