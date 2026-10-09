package io.github.mangi.eta.data.repository

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** MEMORY.md 里一个章节的行范围（0-based、endIndex 开区间）。 */
internal data class MemorySectionRange(
    val title: String,
    val level: Int,
    val startIndex: Int,
    val endIndex: Int,
) {
    val lineCount: Int get() = endIndex - startIndex
    val displayTitle: String get() = title.trimStart('#').trim()
}

/** 工作记忆的一行条目：`- (4) 内容`；缺省重要度 3。 */
internal data class MemoryEntryLine(
    val importance: Int,
    val text: String,
)

internal data class MemoryLineScore(
    val index: Int,
    val score: Double,
    val hits: Int,
)

internal data class MemorySectionScore(
    val section: MemorySectionRange,
    val score: Double,
    val matchedLines: Int,
    val preview: String,
)

/**
 * 记忆检索的纯逻辑：分词、打分、章节切分、相似度与冷章节挑选。
 *
 * 这里不碰文件系统，便于用普通 JVM 单测覆盖（本机跑不了 Robolectric）。
 */
internal object AgentMemoryScoring {
    /** 命中行前后附带多少行上下文。 */
    const val CONTEXT_LINES = 1

    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val CJK = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]")
    private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
    private val PUNCTUATION = Regex("[\\s\\p{Punct}·、。，！？；：（）「」『』【】《》—…]+")

    /**
     * 查询分词：拉丁/数字按词，CJK 连写额外拆成 2-gram。
     *
     * 这样「记忆 建议」这类多词查询、以及「记忆功能改进建议」这类连写查询都能命中，
     * 而不是像原先那样要求整串包含。
     */
    fun tokenize(query: String): List<String> {
        val tokens = linkedSetOf<String>()
        WORD.findAll(query.lowercase()).forEach { match ->
            val token = match.value
            if (token.isBlank()) return@forEach
            tokens += token
            if (token.length > 2 && CJK.containsMatchIn(token)) {
                for (index in 0..token.length - 2) {
                    tokens += token.substring(index, index + 2)
                }
            }
        }
        return tokens.toList()
    }

    /** 去掉空白与标点，用于整串命中判断与相似度计算。 */
    fun normalize(text: String): String = PUNCTUATION.replace(text.lowercase(), "")

    /**
     * 逐行打分：命中词数加权（长词权重更高）+ 整串命中加分 + 标题行加分，
     * 再按行长度做轻微归一化，避免长段落天然占优。
     */
    fun scoreLines(lines: List<String>, query: String): List<MemoryLineScore> {
        val phrase = normalize(query)
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        return lines.mapIndexed { index, line ->
            val haystack = line.lowercase()
            val normalizedLine = normalize(line)
            var score = 0.0
            var hits = 0
            tokens.forEach { token ->
                val occurrences = countOccurrences(haystack, token)
                if (occurrences > 0) {
                    hits++
                    score += token.length.coerceAtMost(6) * (1.0 + ln(1.0 + occurrences))
                }
            }
            if (score > 0.0) {
                if (phrase.isNotBlank() && normalizedLine.contains(phrase)) score += 6.0
                if (line.trimStart().startsWith("#")) score *= 1.6
                score /= (1.0 + line.length / 400.0)
            }
            MemoryLineScore(index = index, score = score, hits = hits)
        }.filter { it.score > 0.0 }
    }

    /** 按 `#`/`##` 标题切分章节；标题之前的散行归入一个无标题的引导段。 */
    fun sections(lines: List<String>): List<MemorySectionRange> {
        val result = mutableListOf<MemorySectionRange>()
        var currentTitle: String? = null
        var currentLevel = 0
        var currentStart = 0
        lines.forEachIndexed { index, line ->
            val match = HEADING.matchEntire(line.trim())
            if (match != null) {
                if (index > currentStart) {
                    result += MemorySectionRange(
                        title = currentTitle.orEmpty(),
                        level = currentLevel,
                        startIndex = currentStart,
                        endIndex = index,
                    )
                } else if (currentTitle != null) {
                    // 连续两行标题：上一节为空，直接跳过不去制造空章节。
                }
                currentTitle = line.trim()
                currentLevel = match.groupValues[1].length
                currentStart = index
            }
        }
        if (currentStart < lines.size) {
            result += MemorySectionRange(
                title = currentTitle.orEmpty(),
                level = currentLevel,
                startIndex = currentStart,
                endIndex = lines.size,
            )
        }
        return result.filter { it.lineCount > 0 }
    }

    /** 把行分数聚合成章节分数；命中章节按分数降序。 */
    fun scoreSections(
        sections: List<MemorySectionRange>,
        lineScores: List<MemoryLineScore>,
    ): List<MemorySectionScore> {
        if (sections.isEmpty() || lineScores.isEmpty()) return emptyList()
        val byIndex = lineScores.associateBy { it.index }
        return sections.mapNotNull { section ->
            var score = 0.0
            var matched = 0
            var firstHitLine: Int? = null
            for (index in section.startIndex until section.endIndex) {
                val lineScore = byIndex[index] ?: continue
                score += lineScore.score
                matched++
                if (firstHitLine == null && index > section.startIndex) firstHitLine = index
            }
            if (matched == 0) return@mapNotNull null
            MemorySectionScore(
                section = section,
                score = score,
                matchedLines = matched,
                preview = "",
            )
        }.sortedByDescending { it.score }
    }

    /**
     * 字符 n-gram Jaccard 相似度，用于写入去重与章节重名提示。
     *
     * 归一化后按 3-gram 集合比较，近似重复的段落会得到高分。
     */
    fun similarity(a: String, b: String, gram: Int = 3): Double {
        val left = grams(normalize(a), gram)
        val right = grams(normalize(b), gram)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.count { it in right }
        val union = left.size + right.size - intersection
        return if (union <= 0) 0.0 else intersection.toDouble() / union.toDouble()
    }

    /**
     * 挑选可归档的冷章节：跳过一级标题与 `# 核心记忆`，
     * 只保留「长期未被命中」或「从未命中且写入已久」的二级章节。
     */
    fun coldSections(
        sections: List<MemorySectionRange>,
        usage: Map<String, AgentMemoryUsageEntry>,
        nowMillis: Long,
        minIdleMillis: Long,
        neverUsedGraceMillis: Long,
    ): List<MemorySectionRange> = sections.filter { section ->
        if (section.level <= 1) return@filter false
        if (normalize(section.displayTitle) == normalize(CORE_SECTION_TITLE)) return@filter false
        // sidecar 里没有记录的章节不做判断：宁可漏掉，也不要把刚写入的内容建议归档。
        val entry = usage[section.title] ?: return@filter false
        if (entry.lastHitAtMillis <= 0L) {
            // 写过但从未被引用：写入时间要超过宽限期才算冷。
            isIdle(entry.writtenAtMillis, nowMillis, neverUsedGraceMillis)
        } else {
            val lastTouched = maxOf(entry.lastHitAtMillis, entry.writtenAtMillis)
            isIdle(lastTouched, nowMillis, minIdleMillis)
        }
    }

    // ---- 工作记忆条目（memory/MEMORY.md 的 `- (重要度) 内容` 行） ----

    /** 条目缺省基础重要度。 */
    const val DEFAULT_ENTRY_IMPORTANCE = 3

    /** 条目衰减半衰期：30 天。 */
    const val ENTRY_HALF_LIFE_DAYS = 30.0

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000L
    private val ENTRY = Regex("^[-*]\\s+(?:\\((\\d)\\)\\s*)?(.*\\S)$")

    /** 解析一行条目；不是条目时返回 null。 */
    fun entryLine(line: String): MemoryEntryLine? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val match = ENTRY.matchEntire(trimmed) ?: return null
        val importance = match.groupValues[1].toIntOrNull()?.coerceIn(1, 5)
            ?: DEFAULT_ENTRY_IMPORTANCE
        val text = match.groupValues[2].trim()
        if (text.isEmpty()) return null
        return MemoryEntryLine(importance = importance, text = text)
    }

    /** 条目的 sidecar key：归一化后比较，避免空格/标点差异导致统计丢失。 */
    fun entryKey(text: String): String = normalize(text)

    /** 基础权重：重要度 5 → 1.0、4 → 0.8、3 → 0.5、2 → 0.3、1 → 0.15。 */
    fun baseWeight(importance: Int): Double = when (importance.coerceIn(1, 5)) {
        5 -> 1.0
        4 -> 0.8
        3 -> 0.5
        2 -> 0.3
        else -> 0.15
    }

    /**
     * 有效重要度 EI = 基础权重 × 访问因子 × 衰减因子 × 连接因子。
     *
     * 访问因子 max(1, ln(1 + 引用次数))；衰减因子 30 天半衰期；
     * 连接因子暂无关系边（1.0 + 0.1 × 边数，最多 +0.5），保留参数以便后续接 link。
     */
    fun effectiveImportance(
        importance: Int,
        hitCount: Int,
        lastTouchedMillis: Long,
        nowMillis: Long,
        edgeCount: Int = 0,
    ): Double {
        val access = maxOf(1.0, ln(1.0 + hitCount.coerceAtLeast(0)))
        val decay = if (lastTouchedMillis <= 0L) {
            1.0
        } else {
            val days = (nowMillis - lastTouchedMillis).coerceAtLeast(0L).toDouble() / DAY_MILLIS
            0.5.pow(days / ENTRY_HALF_LIFE_DAYS)
        }
        val edges = 1.0 + 0.1 * minOf(edgeCount, 5)
        return baseWeight(importance) * access * decay * edges
    }

    const val CORE_SECTION_TITLE = "核心记忆"

    private fun isIdle(referenceMillis: Long, nowMillis: Long, thresholdMillis: Long): Boolean =
        referenceMillis > 0L && nowMillis - referenceMillis >= thresholdMillis

    private fun grams(text: String, gram: Int): Set<String> {
        if (text.isEmpty()) return emptySet()
        if (text.length <= gram) return setOf(text)
        val result = HashSet<String>(text.length)
        for (index in 0..text.length - gram) {
            result += text.substring(index, index + gram)
        }
        return result
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val found = haystack.indexOf(needle, from)
            if (found < 0) break
            count++
            from = found + needle.length
        }
        return count
    }

    private val ARCHIVE_STAMP = Regex("^(#{1,6})\\s+(.*?)（归档于 [^）]*）$")

    /** 去掉归档标记后缀：`## 标题（归档于 2026-10-09）` → `## 标题`。 */
    fun stripArchiveStamp(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            val match = ARCHIVE_STAMP.matchEntire(line.trim())
            if (match == null) line else "${match.groupValues[1]} ${match.groupValues[2]}"
        }

    /** 章节长度对分数的轻微惩罚，供章节排序使用（避免超长章节仅靠体量占优）。 */
    fun lengthPenalty(lineCount: Int): Double = 1.0 / sqrt(1.0 + lineCount / 40.0)
}
