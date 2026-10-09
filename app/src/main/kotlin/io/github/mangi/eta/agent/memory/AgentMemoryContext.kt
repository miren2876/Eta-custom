package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySection
import io.github.mangi.eta.data.repository.AgentMemorySnapshot

internal data class AgentMemoryContext(
    val enabled: Boolean,
    val revision: String,
    val byteSize: Int,
    val coreContent: String,
    val coreTruncated: Boolean,
    val headingIndex: String,
    val coreBudgetChars: Int,
) {
    companion object {
        val DISABLED = AgentMemoryContext(
            enabled = false,
            revision = "",
            byteSize = 0,
            coreContent = "",
            coreTruncated = false,
            headingIndex = "",
            coreBudgetChars = 0,
        )
    }
}

internal object AgentMemoryContextBuilder {
    fun empty(contextWindow: Int?): AgentMemoryContext = build(
        snapshot = AgentMemorySnapshot(
            content = "",
            revision = EMPTY_SHA256,
            byteSize = 0,
            lineCount = 0,
        ),
        contextWindow = contextWindow,
    )

    fun build(
        snapshot: AgentMemorySnapshot,
        contextWindow: Int?,
        sections: List<AgentMemorySection> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
        core: String = "",
    ): AgentMemoryContext {
        val coreBudget = coreBudgetChars(contextWindow)
        val headings = headingIndex(snapshot, sections, nowMillis)
        return AgentMemoryContext(
            enabled = true,
            revision = snapshot.revision,
            byteSize = snapshot.byteSize,
            coreContent = core.take(coreBudget),
            coreTruncated = core.length > coreBudget,
            headingIndex = headings,
            coreBudgetChars = coreBudget,
        )
    }

    /**
     * 章节索引：带上条目数、引用次数与最近使用时间；长期未引用的章节折叠成一段清单，
     * 既保留可检索的标题（用 memory_get mode=section 仍能取到），又不占用常驻预算。
     */
    private fun headingIndex(
        snapshot: AgentMemorySnapshot,
        sections: List<AgentMemorySection>,
        nowMillis: Long,
    ): String {
        if (sections.isEmpty()) return plainHeadingIndex(snapshot.content)
        val warm = mutableListOf<String>()
        val cold = mutableListOf<String>()
        sections.forEach { section ->
            if (section.lineCount <= 0) return@forEach
            val lastTouched = maxOf(section.lastHitAtMillis, section.writtenAtMillis)
            val idleDays = if (lastTouched <= 0L) null else (nowMillis - lastTouched) / DAY_MILLIS
            val isCold = idleDays != null && idleDays >= COLD_INDEX_DAYS
            if (isCold) {
                cold += "- ${section.displayTitle}（未用 $idleDays 天）"
            } else {
                warm += buildString {
                    append(section.title)
                    append(" · ").append(section.lineCount).append(" 行")
                    if (section.hitCount > 0) append(" · 引用 ").append(section.hitCount).append(" 次")
                    if (idleDays != null && idleDays > 0) append(" · ").append(idleDays).append(" 天前")
                }
            }
        }
        val index = buildString {
            append(warm.joinToString("\n"))
            if (cold.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("## 冷章节（").append(cold.size).append(" 个，超过 ")
                append(COLD_INDEX_DAYS).append(" 天未引用，需要时用 memory_get mode=section 读取）")
                cold.forEach { line -> append('\n').append(line) }
            }
        }
        return index.take(MAX_HEADING_INDEX_CHARS)
    }

    private fun plainHeadingIndex(content: String): String = content.lineSequence()
        .filter { line -> HEADING.matches(line.trimEnd()) }
        .joinToString("\n")
        .take(MAX_HEADING_INDEX_CHARS)

    fun coreBudgetChars(contextWindow: Int?): Int {
        val resolvedWindow = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
        return (resolvedWindow / CONTEXT_WINDOW_DIVISOR)
            .coerceIn(MIN_CORE_CHARS, MAX_CORE_CHARS)
    }

    internal fun extractCore(content: String): String {
        if (content.isEmpty()) return ""
        val lines = content.split('\n')
        val start = lines.indexOfFirst { it.trim() == CORE_HEADING }
        if (start < 0) return ""
        val end = ((start + 1) until lines.size)
            .firstOrNull { index -> lines[index].startsWith("# ") }
            ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    /** 核心记忆区的 UTF-8 字节数；记忆页用它显示常驻注入占比。 */
    fun coreSectionBytes(content: String): Int =
        extractCore(content).toByteArray(Charsets.UTF_8).size

    private val HEADING = Regex("^#{1,2}\\s+.+$")
    private const val CORE_HEADING = "# 核心记忆"
    /** 窗口未知时用于按比例分配记忆、世界书等注入预算的保守基准，不参与压缩判定。 */
    const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val CONTEXT_WINDOW_DIVISOR = 16
    private const val MIN_CORE_CHARS = 4_000
    private const val MAX_CORE_CHARS = 32_000
    private const val MAX_HEADING_INDEX_CHARS = 4_000
    private const val COLD_INDEX_DAYS = 60L
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
