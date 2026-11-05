package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class AgentMemoryScoringTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test fun tokenizeSplitsLatinWordsAndCjkBigrams() {
        val tokens = AgentMemoryScoring.tokenize("记忆功能 Memory Bank")

        assertTrue(tokens.contains("memory"))
        assertTrue(tokens.contains("bank"))
        assertTrue(tokens.contains("记忆"))
        assertTrue(tokens.contains("忆功"))
        assertTrue(tokens.contains("功能"))
        assertTrue(tokens.contains("记忆功能"))
        assertFalse(tokens.any { it == "memorybank" })
    }

    @Test fun tokenizeKeepsShortCjkWord() {
        assertEquals(listOf("记忆"), AgentMemoryScoring.tokenize("记忆"))
    }

    @Test fun tokenizeOfBlankQueryIsEmpty() {
        assertTrue(AgentMemoryScoring.tokenize("   ").isEmpty())
    }

    @Test fun scoreLinesKeepsOnlyMatchingLines() {
        val lines = listOf("# 记忆", "无关内容", "记忆检索要按相关度排序")

        val scored = AgentMemoryScoring.scoreLines(lines, "记忆 排序")

        assertEquals(listOf(0, 2), scored.map { it.index })
        assertTrue(scored.all { it.hits > 0 })
        assertTrue(scored.all { it.score > 0.0 })
    }

    @Test fun scoreLinesReturnsNothingWithoutTokens() {
        assertTrue(AgentMemoryScoring.scoreLines(listOf("记忆"), "  ").isEmpty())
    }

    @Test fun headingLineOutranksPlainLine() {
        val lines = listOf("## 记忆", "记忆")

        val scored = AgentMemoryScoring.scoreLines(lines, "记忆")

        assertEquals(2, scored.size)
        assertTrue(scored[0].score > scored[1].score)
    }

    @Test fun sectionsSplitHeadingsAndKeepLeadingBlock() {
        val lines = listOf(
            "引导说明",
            "# 核心记忆",
            "记住用户偏好",
            "## 项目 A",
            "细节",
        )

        val sections = AgentMemoryScoring.sections(lines)

        assertEquals(3, sections.size)
        assertEquals("", sections[0].title)
        assertEquals(0, sections[0].startIndex)
        assertEquals(1, sections[0].endIndex)
        assertEquals("# 核心记忆", sections[1].title)
        assertEquals(1, sections[1].level)
        assertEquals(3, sections[1].endIndex)
        assertEquals("## 项目 A", sections[2].title)
        assertEquals(2, sections[2].level)
        assertEquals(5, sections[2].endIndex)
        assertEquals("项目 A", sections[2].displayTitle)
    }

    @Test fun sectionsSkipEmptyHeadingBlocks() {
        val lines = listOf("# 一", "## 二", "内容")

        val sections = AgentMemoryScoring.sections(lines)

        assertEquals(2, sections.size)
        assertEquals("## 二", sections[1].title)
        assertEquals(3, sections[1].endIndex)
    }

    @Test fun scoreSectionsRanksHitsByScore() {
        val lines = listOf(
            "# 标题",
            "记忆检索相关度排序",
            "## 无关章节",
            "天气不错",
            "## 弱命中章节",
            "记忆条目",
        )
        val sections = AgentMemoryScoring.sections(lines)
        val lineScores = AgentMemoryScoring.scoreLines(lines, "记忆检索相关度排序")

        val scored = AgentMemoryScoring.scoreSections(sections, lineScores)

        assertEquals(2, scored.size)
        assertEquals("## 弱命中章节", scored.last().section.title)
        assertTrue(scored.first().score >= scored.last().score)
    }

    @Test fun similarityIsOneForIdenticalText() {
        assertEquals(1.0, AgentMemoryScoring.similarity("记忆检索要按相关度排序", "记忆检索要按相关度排序"), 0.0001)
    }

    @Test fun similarityIsHighForNearDuplicates() {
        val score = AgentMemoryScoring.similarity("记忆检索要按相关度排序", "记忆检索按相关度排序")

        assertTrue("expected high similarity, got $score", score > 0.5)
    }

    @Test fun similarityIsLowForUnrelatedText() {
        val score = AgentMemoryScoring.similarity("今天天气不错适合出门", "记忆检索要按相关度排序")

        assertTrue("expected low similarity, got $score", score < 0.2)
    }

    @Test fun normalizeDropsWhitespaceAndPunctuation() {
        assertEquals("记忆检索", AgentMemoryScoring.normalize(" 记忆、检索。"))
    }

    @Test fun coldSectionsSkipTopLevelAndCoreMemory() {
        val now = 300 * day
        val sections = listOf(
            MemorySectionRange("# 核心记忆", 1, 0, 1),
            MemorySectionRange("# 其他一级", 1, 1, 2),
            MemorySectionRange("## 冷章节", 2, 2, 3),
        )
        val usage = mapOf(
            "# 核心记忆" to AgentMemoryUsageEntry(hitCount = 0, lastHitAtMillis = 0L, writtenAtMillis = 0L),
            "## 冷章节" to AgentMemoryUsageEntry(hitCount = 0, lastHitAtMillis = now - 100 * day, writtenAtMillis = now - 200 * day),
        )

        val cold = AgentMemoryScoring.coldSections(sections, usage, now, 60 * day, 30 * day)

        assertEquals(listOf("## 冷章节"), cold.map { it.title })
    }

    @Test fun coldSectionsIgnoreSectionsWithoutUsageRecord() {
        val now = 100 * day
        val sections = listOf(MemorySectionRange("## 无记录", 2, 0, 1))

        val cold = AgentMemoryScoring.coldSections(sections, emptyMap(), now, 60 * day, 30 * day)

        assertTrue(cold.isEmpty())
    }

    @Test fun coldSectionsRespectNeverUsedGracePeriod() {
        val now = 100 * day
        val sections = listOf(
            MemorySectionRange("## 写过没用过", 2, 0, 1),
            MemorySectionRange("## 刚写过没用过", 2, 1, 2),
        )
        val usage = mapOf(
            "## 写过没用过" to AgentMemoryUsageEntry(writtenAtMillis = now - 40 * day),
            "## 刚写过没用过" to AgentMemoryUsageEntry(writtenAtMillis = now - 5 * day),
        )

        val cold = AgentMemoryScoring.coldSections(sections, usage, now, 60 * day, 30 * day)

        assertEquals(listOf("## 写过没用过"), cold.map { it.title })
    }

    @Test fun coldSectionsKeepRecentlyUsedSections() {
        val now = 100 * day
        val sections = listOf(MemorySectionRange("## 热章节", 2, 0, 1))
        val usage = mapOf(
            "## 热章节" to AgentMemoryUsageEntry(hitCount = 3, lastHitAtMillis = now - day, writtenAtMillis = now - 200 * day),
        )

        assertTrue(AgentMemoryScoring.coldSections(sections, usage, now, 60 * day, 30 * day).isEmpty())
    }

    @Test fun lengthPenaltyShrinksWithLongerSections() {
        assertTrue(AgentMemoryScoring.lengthPenalty(10) > AgentMemoryScoring.lengthPenalty(400))
        assertEquals(1.0, AgentMemoryScoring.lengthPenalty(0), 0.0001)
    }

    @Test fun stripArchiveStampRestoresOriginalHeading() {
        val archived = "## 提问卡片（归档于 2026-10-09）\n- 正文\n\n### 子节\n"

        val restored = AgentMemoryScoring.stripArchiveStamp(archived)

        assertEquals("## 提问卡片\n- 正文\n\n### 子节\n", restored)
    }

    @Test fun stripArchiveStampLeavesPlainHeadingsAlone() {
        assertEquals("## 普通标题\n正文", AgentMemoryScoring.stripArchiveStamp("## 普通标题\n正文"))
    }

    @Test fun entryLinesParseImportanceAndFallBackToDefault() {
        assertEquals(MemoryEntryLine(4, "记忆结构改造完成"), AgentMemoryScoring.entryLine("- (4) 记忆结构改造完成"))
        assertEquals(MemoryEntryLine(3, "没写重要度"), AgentMemoryScoring.entryLine("- 没写重要度"))
        assertEquals(MemoryEntryLine(5, "星号也可以"), AgentMemoryScoring.entryLine("* (5) 星号也可以"))
        // 越界重要度夹到 1–5
        assertEquals(5, AgentMemoryScoring.entryLine("- (9) 越界")?.importance)
        assertEquals(1, AgentMemoryScoring.entryLine("- (0) 越界")?.importance)
        assertNull(AgentMemoryScoring.entryLine("## 标题不是条目"))
        assertNull(AgentMemoryScoring.entryLine("裸文本没有前缀"))
        assertNull(AgentMemoryScoring.entryLine("- "))
    }

    @Test fun normalizeEntryInputStripsEveryPrefixVariant() {
        assertEquals(3 to "事实", AgentMemoryScoring.normalizeEntryInput("- (3) 事实"))
        assertEquals(4 to "事实", AgentMemoryScoring.normalizeEntryInput("(4) 事实"))
        assertEquals(4 to "事实", AgentMemoryScoring.normalizeEntryInput("（4）事实"))
        assertEquals(5 to "事实", AgentMemoryScoring.normalizeEntryInput("* (5) 事实"))
        assertEquals(null to "事实", AgentMemoryScoring.normalizeEntryInput("- 事实"))
        assertEquals(null to "事实", AgentMemoryScoring.normalizeEntryInput("事实"))
        // 重复标记（补丁 31 只剥一层时写下的 `- (4) (4) xxx`）要一次剥干净
        assertEquals(4 to "事实", AgentMemoryScoring.normalizeEntryInput("- (4) (4) 事实"))
        assertEquals(4 to "事实", AgentMemoryScoring.normalizeEntryInput("(4) (4) 事实"))
        // 越界括号不当重要度，正文原样保留
        assertEquals(null to "(6) 事实", AgentMemoryScoring.normalizeEntryInput("(6) 事实"))
        assertEquals(null to "(2026-10-09) 事实", AgentMemoryScoring.normalizeEntryInput("(2026-10-09) 事实"))
        assertNull(AgentMemoryScoring.normalizeEntryInput(""))
        assertNull(AgentMemoryScoring.normalizeEntryInput("- (3) "))
    }

    @Test fun entryKeyIgnoresCaseSpacingAndPunctuation() {
        assertEquals(
            AgentMemoryScoring.entryKey("Memory Bank"),
            AgentMemoryScoring.entryKey("memory  bank"),
        )
    }

    @Test fun effectiveImportanceFollowsMnemonFormula() {
        val now = 1_700_000_000_000L
        // 刚写入、无引用：EI 等于基础权重
        assertEquals(1.0, AgentMemoryScoring.effectiveImportance(5, 0, now, now), 1e-9)
        assertEquals(0.5, AgentMemoryScoring.effectiveImportance(3, 0, now, now), 1e-9)
        // 30 天半衰期
        assertEquals(0.5, AgentMemoryScoring.effectiveImportance(5, 0, now - 30 * day, now), 1e-6)
        // 引用 3 次 → 访问因子 ln(4)
        assertEquals(ln(4.0), AgentMemoryScoring.effectiveImportance(5, 3, now, now), 1e-9)
        // 没有时间记录时不衰减
        assertEquals(1.0, AgentMemoryScoring.effectiveImportance(5, 0, 0L, now), 1e-9)
    }
}
