package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

internal data class AgentMemorySnapshot(
    val content: String,
    val revision: String,
    val byteSize: Int,
    val lineCount: Int,
)

/** 章节视图：供检索结果、用量统计与归档候选共用。 */
internal data class AgentMemorySection(
    val title: String,
    val level: Int,
    val startLine: Int,
    val endLine: Int,
    val lineCount: Int,
    val hitCount: Int = 0,
    val lastHitAtMillis: Long = 0L,
    val writtenAtMillis: Long = 0L,
    val score: Double = 0.0,
    val matchedLines: Int = 0,
    val preview: String = "",
) {
    val displayTitle: String get() = title.trimStart('#').trim()
}

internal data class AgentMemoryReadResult(
    val snapshot: AgentMemorySnapshot,
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val hasMore: Boolean,
    val matchedLines: Int,
    val sections: List<AgentMemorySection> = emptyList(),
)

/** 章节的引用统计；持久化在 memory/usage.json，不影响 MEMORY.md 的 revision。 */
internal data class AgentMemoryUsageEntry(
    val hitCount: Int = 0,
    val lastHitAtMillis: Long = 0L,
    val writtenAtMillis: Long = 0L,
)

internal data class AgentMemoryUsage(
    val sections: Map<String, AgentMemoryUsageEntry> = emptyMap(),
) {
    fun entry(title: String): AgentMemoryUsageEntry? = sections[title]
}

internal data class AgentMemoryArchiveSnapshot(
    val content: String,
    val byteSize: Int,
    val sectionCount: Int,
)

internal data class AgentMemoryArchiveResult(
    val snapshot: AgentMemorySnapshot,
    val archivedTitles: List<String>,
    val archiveByteSize: Int,
)

internal enum class AgentMemoryReadMode {
    /** 按行分页读取。 */
    Range,

    /** 打分排序的命中片段（默认，等价于原 query 行为但按相关度排序）。 */
    Excerpts,

    /** 只返回命中的章节摘要，便于先粗后细。 */
    Sections,

    /** 按标题读取整节。 */
    Section,
}

internal sealed interface AgentMemoryMutation {
    val revision: String

    data class ReplaceRange(
        override val revision: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
    ) : AgentMemoryMutation

    data class Append(
        override val revision: String,
        val content: String,
    ) : AgentMemoryMutation

    data class Clear(
        override val revision: String,
    ) : AgentMemoryMutation
}

internal sealed interface AgentMemoryWriteResult {
    data class Success(
        val snapshot: AgentMemorySnapshot,
        val notice: String? = null,
    ) : AgentMemoryWriteResult

    /** 疑似重复：未写入，请调用方改用 replace_range 或指定已有章节。 */
    data class Rejected(
        val snapshot: AgentMemorySnapshot,
        val notice: String,
    ) : AgentMemoryWriteResult

    data class Conflict(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
}

internal class AgentMemoryException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** 单一 MEMORY.md 的有界、原子文件存储；附带用量统计与可逆归档。 */
internal class AgentMemoryStore(
    rootDir: File,
) {
    private val memoryDir = File(rootDir, DIRECTORY_NAME)
    private val atomicFile = AtomicFile(File(memoryDir, FILE_NAME))
    private val archiveFile = AtomicFile(File(memoryDir, ARCHIVE_FILE_NAME))
    private val usageFile = AtomicFile(File(memoryDir, USAGE_FILE_NAME))
    private val lock = Any()

    fun snapshot(): AgentMemorySnapshot = synchronized(lock) {
        snapshotLocked()
    }

    fun read(
        query: String? = null,
        startLine: Int = DEFAULT_START_LINE,
        maxChars: Int = DEFAULT_READ_CHARS,
        mode: AgentMemoryReadMode = AgentMemoryReadMode.Range,
        section: String? = null,
        recordUsage: Boolean = true,
    ): AgentMemoryReadResult = synchronized(lock) {
        val snapshot = snapshotLocked()
        val boundedChars = maxChars.coerceIn(MIN_READ_CHARS, MAX_READ_CHARS)
        val resolved = resolveMode(mode, query, section)
        val result = when (resolved) {
            AgentMemoryReadMode.Section -> readSection(snapshot, section.orEmpty(), boundedChars)
            AgentMemoryReadMode.Sections -> searchSections(snapshot, query.orEmpty().trim(), boundedChars)
            AgentMemoryReadMode.Excerpts -> searchExcerpts(snapshot, query.orEmpty().trim(), boundedChars)
            AgentMemoryReadMode.Range -> page(snapshot, startLine, boundedChars)
        }
        if (recordUsage && result.sections.isNotEmpty()) {
            recordHitsLocked(result.sections.map { it.title })
        }
        result
    }

    /** 当前章节视图（含用量统计）。 */
    fun sections(): List<AgentMemorySection> = synchronized(lock) {
        sectionsOf(snapshotLocked(), usageLocked())
    }

    fun usage(): AgentMemoryUsage = synchronized(lock) { usageLocked() }

    /** 长期未被引用的二级章节，供记忆页给出归档建议。 */
    fun archiveCandidates(
        nowMillis: Long = System.currentTimeMillis(),
        minIdleDays: Long = DEFAULT_COLD_IDLE_DAYS,
        neverUsedGraceDays: Long = DEFAULT_NEVER_USED_GRACE_DAYS,
    ): List<AgentMemorySection> = synchronized(lock) {
        val snapshot = snapshotLocked()
        val usage = usageLocked()
        val lines = snapshot.content.memoryLines()
        val ranges = AgentMemoryScoring.sections(lines)
        val cold = AgentMemoryScoring.coldSections(
            sections = ranges,
            usage = usage.sections,
            nowMillis = nowMillis,
            minIdleMillis = minIdleDays * DAY_MILLIS,
            neverUsedGraceMillis = neverUsedGraceDays * DAY_MILLIS,
        ).toSet()
        sectionsOf(snapshot, usage).filter { section ->
            cold.any { range ->
                range.startIndex == section.startLine - 1 && range.title == section.title
            }
        }
    }

    fun archiveSnapshot(): AgentMemoryArchiveSnapshot = synchronized(lock) {
        val content = readText(archiveFile)
        AgentMemoryArchiveSnapshot(
            content = content,
            byteSize = content.toByteArray(Charsets.UTF_8).size,
            sectionCount = AgentMemoryScoring.sections(content.memoryLines()).size,
        )
    }

    /** 把指定章节移入 MEMORY-archive.md；正文其余部分保持不变。 */
    fun archive(titles: List<String>): AgentMemoryArchiveResult = synchronized(lock) {
        if (titles.isEmpty()) {
            throw AgentMemoryException("MEMORY_ARCHIVE_EMPTY", "没有需要归档的章节")
        }
        val snapshot = snapshotLocked()
        val lines = snapshot.content.memoryLines()
        val ranges = AgentMemoryScoring.sections(lines)
        val selected = ranges.filter { it.title in titles }
        if (selected.isEmpty()) {
            throw AgentMemoryException("MEMORY_ARCHIVE_MISSING", "要归档的章节已不存在，请刷新后重试")
        }
        val removed = HashSet<Int>()
        val archivedBlocks = mutableListOf<String>()
        val stamp = archiveStamp()
        selected.forEach { range ->
            for (index in range.startIndex until range.endIndex) removed += index
            val body = lines.subList(range.startIndex, range.endIndex)
                .joinToString("\n")
                .trimEnd('\n')
            archivedBlocks += buildString {
                append("## ").append(range.displayTitle).append("（归档于 ").append(stamp).append("）")
                val bodyWithoutHeading = body.substringAfter('\n', "")
                if (bodyWithoutHeading.isNotBlank()) append('\n').append(bodyWithoutHeading)
            }
        }
        val remaining = lines.filterIndexed { index, _ -> index !in removed }
            .joinToString("\n")
            .trimEnd('\n')
        val archiveContent = buildString {
            val existing = readText(archiveFile).trimEnd('\n')
            if (existing.isNotEmpty()) append(existing).append("\n\n")
            append(archivedBlocks.joinToString("\n\n"))
            append('\n')
        }
        if (archiveContent.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            throw AgentMemoryException("MEMORY_ARCHIVE_TOO_LARGE", "归档文件将超过 1 MiB，请先清理归档")
        }
        writeLocked(remaining)
        writeText(archiveFile, archiveContent)
        val refreshedUsage = usageLocked().sections.filterKeys { title -> title !in titles }
        writeUsageLocked(AgentMemoryUsage(refreshedUsage))
        val updated = snapshotOf(remaining)
        AgentMemoryArchiveResult(
            snapshot = updated,
            archivedTitles = selected.map { it.displayTitle },
            archiveByteSize = archiveContent.toByteArray(Charsets.UTF_8).size,
        )
    }

    /** 把归档内容整体追加回 MEMORY.md，并清空归档文件（用户显式操作，可逆）。 */
    fun restoreArchive(): AgentMemorySnapshot = synchronized(lock) {
        val archived = readText(archiveFile).trimEnd('\n')
        if (archived.isBlank()) {
            throw AgentMemoryException("MEMORY_ARCHIVE_EMPTY", "归档是空的")
        }
        val snapshot = snapshotLocked()
        val merged = if (snapshot.content.isBlank()) {
            archived
        } else {
            snapshot.content.trimEnd('\n') + "\n\n" + archived
        }
        writeLocked(merged)
        writeText(archiveFile, "")
        writeUsageLocked(AgentMemoryUsage())
        snapshotOf(merged)
    }

    /** 备份恢复时整体写回归档文件；空内容即清空归档。 */
    fun replaceArchive(content: String): AgentMemoryArchiveSnapshot = synchronized(lock) {
        writeText(archiveFile, content)
        val text = readText(archiveFile)
        AgentMemoryArchiveSnapshot(
            content = text,
            byteSize = text.toByteArray(Charsets.UTF_8).size,
            sectionCount = AgentMemoryScoring.sections(text.memoryLines()).size,
        )
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (mutation.revision != current.revision) {
            return@synchronized AgentMemoryWriteResult.Conflict(current)
        }
        if (mutation is AgentMemoryMutation.Append) {
            duplicateNotice(current, mutation.content)?.let { notice ->
                return@synchronized AgentMemoryWriteResult.Rejected(current, notice)
            }
        }
        val updated = when (mutation) {
            is AgentMemoryMutation.ReplaceRange -> replaceRange(current, mutation)
            is AgentMemoryMutation.Append -> append(current, mutation.content)
            is AgentMemoryMutation.Clear -> ""
        }
        writeLocked(updated)
        val refreshed = snapshotOf(updated)
        syncUsageLocked(refreshed)
        val notice = when (mutation) {
            is AgentMemoryMutation.Append -> similarSectionNotice(current, mutation.content)
            else -> null
        }
        AgentMemoryWriteResult.Success(refreshed, notice)
    }

    fun replaceAll(content: String): AgentMemorySnapshot = synchronized(lock) {
        writeLocked(content)
        val refreshed = snapshotOf(content)
        syncUsageLocked(refreshed)
        refreshed
    }

    fun replaceAllIfRevision(content: String, revision: String): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (current.revision != revision) return@synchronized AgentMemoryWriteResult.Conflict(current)
        writeLocked(content)
        val refreshed = snapshotOf(content)
        syncUsageLocked(refreshed)
        AgentMemoryWriteResult.Success(refreshed)
    }

    private fun resolveMode(
        mode: AgentMemoryReadMode,
        query: String?,
        section: String?,
    ): AgentMemoryReadMode = when {
        section != null && section.isNotBlank() -> AgentMemoryReadMode.Section
        mode != AgentMemoryReadMode.Range -> mode
        !query.isNullOrBlank() -> AgentMemoryReadMode.Excerpts
        else -> AgentMemoryReadMode.Range
    }

    private fun snapshotLocked(): AgentMemorySnapshot {
        val file = atomicFile.baseFile
        if (!file.exists()) return snapshotOf("")
        val bytes = try {
            atomicFile.openRead().use { it.readBytes() }
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_READ_FAILED",
                message = "无法读取记忆文件",
                cause = throwable,
            )
        }
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件超过 1 MiB 安全上限",
            )
        }
        val content = bytes.toString(Charsets.UTF_8)
        return snapshotOf(content, bytes)
    }

    private fun writeLocked(content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件不能超过 1 MiB UTF-8 字节",
            )
        }
        if (!memoryDir.exists() && !memoryDir.mkdirs() && !memoryDir.isDirectory) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法创建记忆目录",
            )
        }
        writeText(atomicFile, content)
    }

    private fun writeText(file: AtomicFile, content: String) {
        if (!memoryDir.exists() && !memoryDir.mkdirs() && !memoryDir.isDirectory) {
            throw AgentMemoryException("MEMORY_WRITE_FAILED", "无法创建记忆目录")
        }
        val output = try {
            file.startWrite()
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法开始写入记忆文件",
                cause = throwable,
            )
        }
        try {
            output.write(content.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (throwable: Throwable) {
            file.failWrite(output)
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法保存记忆文件",
                cause = throwable,
            )
        }
    }

    private fun readText(file: AtomicFile): String {
        val target = file.baseFile
        if (!target.exists()) return ""
        return try {
            file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_READ_FAILED",
                message = "无法读取记忆文件",
                cause = throwable,
            )
        }
    }

    private fun replaceRange(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.ReplaceRange,
    ): String {
        val lines = snapshot.content.memoryLines().toMutableList()
        if (
            mutation.startLine < 1 ||
            mutation.endLine < mutation.startLine ||
            mutation.endLine > lines.size
        ) {
            throw AgentMemoryException(
                code = "MEMORY_RANGE_INVALID",
                message = "替换行范围无效；请重新读取记忆后再试",
            )
        }
        val replacement = mutation.content.memoryLines()
        lines.subList(mutation.startLine - 1, mutation.endLine).clear()
        if (replacement.isNotEmpty()) {
            lines.addAll(mutation.startLine - 1, replacement)
        }
        return lines.joinToString("\n")
    }

    private fun append(snapshot: AgentMemorySnapshot, content: String): String {
        if (content.isEmpty()) return snapshot.content
        if (snapshot.content.isEmpty()) return content
        return snapshot.content.trimEnd('\n') + "\n" + content
    }

    private fun page(
        snapshot: AgentMemorySnapshot,
        requestedStartLine: Int,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        if (lines.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val startIndex = (requestedStartLine - 1).coerceIn(0, lines.size)
        if (startIndex >= lines.size) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val output = StringBuilder()
        var endIndex = startIndex
        while (endIndex < lines.size) {
            val rendered = "${endIndex + 1}: ${lines[endIndex]}"
            val separatorLength = if (output.isEmpty()) 0 else 1
            if (output.isNotEmpty() && output.length + separatorLength + rendered.length > maxChars) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(rendered.take(maxChars - output.length))
            endIndex++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = startIndex + 1,
            endLine = endIndex,
            hasMore = endIndex < lines.size,
            matchedLines = endIndex - startIndex,
        )
    }

    /** 打分排序的命中片段；命中行以 `n*:` 标记，仍保留行号便于 replace_range。 */
    private fun searchExcerpts(
        snapshot: AgentMemorySnapshot,
        query: String,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        if (query.isBlank() || lines.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0, emptyList())
        }
        val scored = AgentMemoryScoring.scoreLines(lines, query)
        if (scored.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0, emptyList())
        }
        val matchedIndices = scored.map { it.index }.toSet()
        val usage = usageLocked()
        val sections = AgentMemoryScoring.scoreSections(
            sections = AgentMemoryScoring.sections(lines),
            lineScores = scored,
        )
        // 按相关度决定片段顺序：先取分数最高的命中行，再带上它的上下文行。
        val orderedSegments = scored
            .sortedByDescending { it.score }
            .map { hit ->
                val from = (hit.index - AgentMemoryScoring.CONTEXT_LINES).coerceAtLeast(0)
                val to = (hit.index + AgentMemoryScoring.CONTEXT_LINES).coerceAtMost(lines.lastIndex)
                from..to
            }
        val output = StringBuilder()
        val emitted = mutableSetOf<Int>()
        var rendered = 0
        orderedSegments.forEach { segment ->
            val fresh = segment.filter { it !in emitted }
            if (fresh.isEmpty()) return@forEach
            val block = fresh.joinToString("\n") { index ->
                val marker = if (index in matchedIndices) "*" else " "
                "${index + 1}$marker: ${lines[index]}"
            }
            val gap = if (output.isEmpty()) "" else "\n…\n"
            if (output.length + gap.length + block.length > maxChars) {
                if (output.isEmpty()) output.append(block.take(maxChars))
                return@forEach
            }
            output.append(gap).append(block)
            emitted += fresh
            rendered += fresh.size
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = emitted.minOrNull()?.plus(1),
            endLine = emitted.maxOrNull()?.plus(1),
            hasMore = emitted.size < matchedIndices.size,
            matchedLines = matchedIndices.size,
            sections = sections.map { it.toUi(snapshot, usage) },
        )
    }

    /** 只返回命中的章节摘要：章节名 + 命中行数 + 分数 + 预览。 */
    private fun searchSections(
        snapshot: AgentMemorySnapshot,
        query: String,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        if (query.isBlank() || lines.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0, emptyList())
        }
        val scored = AgentMemoryScoring.scoreLines(lines, query)
        val ranges = AgentMemoryScoring.sections(lines)
        val sectionScores = AgentMemoryScoring.scoreSections(
            sections = ranges,
            lineScores = scored,
        )
        if (sectionScores.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0, emptyList())
        }
        val usage = usageLocked()
        val topLineBySection = scored.groupBy { line ->
            ranges.firstOrNull { line.index in it.startIndex until it.endIndex }?.title
        }
        val output = StringBuilder()
        val views = mutableListOf<AgentMemorySection>()
        sectionScores.forEach { entry ->
            val title = entry.section.displayTitle
            val previewLine = topLineBySection[entry.section.title]
                ?.maxByOrNull { it.score }
                ?.let { lines[it.index].trim().take(PREVIEW_CHARS) }
                .orEmpty()
            val line = "§ $title · 命中 ${entry.matchedLines} 行 · 分数 ${"%.2f".format(entry.score)}" +
                if (previewLine.isNotBlank()) "\n  $previewLine" else ""
            if (output.isNotEmpty() && output.length + line.length + 1 > maxChars) return@forEach
            if (output.isNotEmpty()) output.append('\n')
            output.append(line)
            views += entry.toUi(snapshot, usage)
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = null,
            endLine = null,
            hasMore = views.size < sectionScores.size,
            matchedLines = scored.size,
            sections = views,
        )
    }

    /** 按标题读取整节（标题做去 #、忽略大小写与标点后比较，支持部分匹配）。 */
    private fun readSection(
        snapshot: AgentMemorySnapshot,
        requested: String,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        val sections = AgentMemoryScoring.sections(lines)
        val target = normalizeTitle(requested)
        val section = sections.firstOrNull { normalizeTitle(it.displayTitle) == target }
            ?: sections.firstOrNull { normalizeTitle(it.displayTitle).contains(target) }
            ?: return AgentMemoryReadResult(snapshot, "", null, null, false, 0, emptyList())
        val output = StringBuilder()
        var endIndex = section.startIndex
        while (endIndex < section.endIndex) {
            val rendered = "${endIndex + 1}: ${lines[endIndex]}"
            if (output.isNotEmpty() && output.length + 1 + rendered.length > maxChars) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(rendered.take(maxChars - output.length))
            endIndex++
            if (output.length >= maxChars) break
        }
        val view = section.toUi(snapshot, usageLocked()).copy(
            matchedLines = endIndex - section.startIndex,
            preview = lines.getOrNull(section.startIndex + 1)?.trim()?.take(PREVIEW_CHARS).orEmpty(),
        )
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = section.startIndex + 1,
            endLine = endIndex,
            hasMore = endIndex < section.endIndex,
            matchedLines = endIndex - section.startIndex,
            sections = listOf(view),
        )
    }

    private fun sectionsOf(snapshot: AgentMemorySnapshot, usage: AgentMemoryUsage): List<AgentMemorySection> {
        val lines = snapshot.content.memoryLines()
        return AgentMemoryScoring.sections(lines).map { range ->
            range.toUi(snapshot, usage).copy(
                preview = lines.drop(range.startIndex + 1)
                    .takeWhile { !it.trimStart().startsWith("#") }
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.take(PREVIEW_CHARS)
                    .orEmpty(),
            )
        }
    }

    private fun MemorySectionRange.toUi(
        snapshot: AgentMemorySnapshot,
        usage: AgentMemoryUsage,
    ): AgentMemorySection {
        val entry = usage.sections[title]
        return AgentMemorySection(
            title = title,
            level = level,
            startLine = startIndex + 1,
            endLine = endIndex,
            lineCount = lineCount,
            hitCount = entry?.hitCount ?: 0,
            lastHitAtMillis = entry?.lastHitAtMillis ?: 0L,
            writtenAtMillis = entry?.writtenAtMillis ?: 0L,
        )
    }

    private fun MemorySectionScore.toUi(
        snapshot: AgentMemorySnapshot,
        usage: AgentMemoryUsage,
    ): AgentMemorySection = section.toUi(snapshot, usage).copy(
        score = score,
        matchedLines = matchedLines,
    )

    /** 写入前的重复检测：完全/近似重复直接拒绝，并给出替代建议。 */
    private fun duplicateNotice(snapshot: AgentMemorySnapshot, content: String): String? {
        val incoming = content.trim()
        if (incoming.isBlank()) return null
        val lines = snapshot.content.memoryLines()
        val sections = AgentMemoryScoring.sections(lines)
        val incomingTitle = normalizeTitle(firstHeading(incoming))
        if (incomingTitle.isNotEmpty()) {
            val similarHeading = sections.firstOrNull { section ->
                AgentMemoryScoring.similarity(section.displayTitle, firstHeading(incoming)) >= HEADING_DUPLICATE_THRESHOLD
            }
            if (similarHeading != null) {
                return "未写入：已有相似章节「${similarHeading.displayTitle}」(第 ${similarHeading.startIndex + 1} 行起)。" +
                    "请改用 memory_get 读取该节后用 mode=replace_range 合并，避免章节碎片化。"
            }
        }
        val best = sections.mapNotNull { section ->
            val body = lines.subList(section.startIndex, section.endIndex).joinToString("\n")
            val score = AgentMemoryScoring.similarity(body, incoming)
            if (score >= DUPLICATE_SKIP_THRESHOLD) section to score else null
        }.maxByOrNull { it.second }
        if (best != null) {
            val section = best.first
            return "未写入：内容与「${section.displayTitle}」(第 ${section.startIndex + 1} 行起) 相似度 " +
                "${"%.2f".format(best.second)}，疑似重复。请读取该节后用 mode=replace_range 更新，而不是追加新条目。"
        }
        return null
    }

    /** 写入后提示：与某节中等相似，提醒下次合并（不阻塞本次写入）。 */
    private fun similarSectionNotice(snapshot: AgentMemorySnapshot, content: String): String? {
        val incoming = content.trim()
        if (incoming.isBlank()) return null
        val lines = snapshot.content.memoryLines()
        val sections = AgentMemoryScoring.sections(lines)
        val best = sections.mapNotNull { section ->
            val body = lines.subList(section.startIndex, section.endIndex).joinToString("\n")
            val score = AgentMemoryScoring.similarity(body, incoming)
            if (score >= DUPLICATE_WARN_THRESHOLD) section to score else null
        }.maxByOrNull { it.second } ?: return null
        return "提示：本次内容与「${best.first.displayTitle}」相似度 ${"%.2f".format(best.second)}，" +
            "下次建议改用 replace_range 合并到该节。"
    }

    private fun firstHeading(content: String): String =
        content.lineSequence().firstOrNull { it.trimStart().startsWith("#") }
            ?.trimStart('#')
            ?.trim()
            .orEmpty()

    private fun normalizeTitle(title: String): String = AgentMemoryScoring.normalize(title)

    private fun usageLocked(): AgentMemoryUsage {
        val raw = readText(usageFile)
        if (raw.isBlank()) return AgentMemoryUsage()
        return runCatching {
            val json = JSONObject(raw)
            val sections = json.optJSONObject(FIELD_SECTIONS) ?: return@runCatching AgentMemoryUsage()
            val map = mutableMapOf<String, AgentMemoryUsageEntry>()
            sections.keys().forEach { key ->
                val entry = sections.optJSONObject(key) ?: return@forEach
                map[key] = AgentMemoryUsageEntry(
                    hitCount = entry.optInt(FIELD_HIT_COUNT, 0),
                    lastHitAtMillis = entry.optLong(FIELD_LAST_HIT, 0L),
                    writtenAtMillis = entry.optLong(FIELD_WRITTEN_AT, 0L),
                )
            }
            AgentMemoryUsage(map)
        }.getOrDefault(AgentMemoryUsage())
    }

    private fun writeUsageLocked(usage: AgentMemoryUsage) {
        runCatching {
            val sections = JSONObject()
            usage.sections.forEach { (title, entry) ->
                sections.put(
                    title,
                    JSONObject()
                        .put(FIELD_HIT_COUNT, entry.hitCount)
                        .put(FIELD_LAST_HIT, entry.lastHitAtMillis)
                        .put(FIELD_WRITTEN_AT, entry.writtenAtMillis),
                )
            }
            val json = JSONObject()
                .put(FIELD_VERSION, USAGE_SCHEMA_VERSION)
                .put(FIELD_SECTIONS, sections)
            writeText(usageFile, json.toString())
        }
    }

    private fun recordHitsLocked(titles: List<String>) {
        if (titles.isEmpty()) return
        val now = System.currentTimeMillis()
        val current = usageLocked().sections.toMutableMap()
        titles.forEach { title ->
            val previous = current[title] ?: AgentMemoryUsageEntry()
            current[title] = previous.copy(
                hitCount = previous.hitCount + 1,
                lastHitAtMillis = now,
            )
        }
        writeUsageLocked(AgentMemoryUsage(current))
    }

    /** 写入后对齐统计：新章节补写入时间，消失的章节丢弃统计。 */
    private fun syncUsageLocked(snapshot: AgentMemorySnapshot) {
        val now = System.currentTimeMillis()
        val titles = AgentMemoryScoring.sections(snapshot.content.memoryLines()).map { it.title }
        val current = usageLocked().sections
        val next = mutableMapOf<String, AgentMemoryUsageEntry>()
        titles.forEach { title ->
            val previous = current[title]
            next[title] = if (previous == null) {
                AgentMemoryUsageEntry(writtenAtMillis = now)
            } else {
                previous.copy(writtenAtMillis = now)
            }
        }
        writeUsageLocked(AgentMemoryUsage(next))
    }

    private fun snapshotOf(
        content: String,
        bytes: ByteArray = content.toByteArray(Charsets.UTF_8),
    ): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = sha256(bytes),
        byteSize = bytes.size,
        lineCount = content.memoryLines().size,
    )

    private fun String.memoryLines(): List<String> =
        if (isEmpty()) emptyList() else split('\n')

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    private fun archiveStamp(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    companion object {
        const val MAX_FILE_BYTES = 1024 * 1024
        const val DEFAULT_READ_CHARS = 12_000
        const val MAX_READ_CHARS = 32_000
        const val MIN_READ_CHARS = 1
        const val MAX_WRITE_CONTENT_CHARS = 3_500
        const val DEFAULT_COLD_IDLE_DAYS = 60L
        const val DEFAULT_NEVER_USED_GRACE_DAYS = 30L
        const val PREVIEW_CHARS = 120
        private const val DIRECTORY_NAME = "memory"
        private const val FILE_NAME = "MEMORY.md"
        private const val ARCHIVE_FILE_NAME = "MEMORY-archive.md"
        private const val USAGE_FILE_NAME = "usage.json"
        private const val DEFAULT_START_LINE = 1
        private const val DUPLICATE_SKIP_THRESHOLD = 0.80
        private const val DUPLICATE_WARN_THRESHOLD = 0.50
        private const val HEADING_DUPLICATE_THRESHOLD = 0.70
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val USAGE_SCHEMA_VERSION = 1
        private const val FIELD_VERSION = "version"
        private const val FIELD_SECTIONS = "sections"
        private const val FIELD_HIT_COUNT = "hitCount"
        private const val FIELD_LAST_HIT = "lastHitAt"
        private const val FIELD_WRITTEN_AT = "writtenAt"
    }
}

internal object AgentMemoryRepository {
    @Volatile
    private lateinit var store: AgentMemoryStore

    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = AgentMemoryStore(context.applicationContext.filesDir)
        }
    }

    fun snapshot(): AgentMemorySnapshot {
        ensureInitialized()
        return store.snapshot()
    }

    fun read(
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
        mode: AgentMemoryReadMode = AgentMemoryReadMode.Range,
        section: String? = null,
        recordUsage: Boolean = true,
    ): AgentMemoryReadResult {
        ensureInitialized()
        return store.read(query, startLine, maxChars, mode, section, recordUsage)
    }

    fun sections(): List<AgentMemorySection> {
        ensureInitialized()
        return store.sections()
    }

    fun usage(): AgentMemoryUsage {
        ensureInitialized()
        return store.usage()
    }

    fun archiveCandidates(
        nowMillis: Long = System.currentTimeMillis(),
        minIdleDays: Long = AgentMemoryStore.DEFAULT_COLD_IDLE_DAYS,
        neverUsedGraceDays: Long = AgentMemoryStore.DEFAULT_NEVER_USED_GRACE_DAYS,
    ): List<AgentMemorySection> {
        ensureInitialized()
        return store.archiveCandidates(nowMillis, minIdleDays, neverUsedGraceDays)
    }

    fun archiveSnapshot(): AgentMemoryArchiveSnapshot {
        ensureInitialized()
        return store.archiveSnapshot()
    }

    fun archive(titles: List<String>): AgentMemoryArchiveResult {
        ensureInitialized()
        return store.archive(titles)
    }

    fun restoreArchive(): AgentMemorySnapshot {
        ensureInitialized()
        return store.restoreArchive()
    }

    fun replaceArchive(content: String): AgentMemoryArchiveSnapshot {
        ensureInitialized()
        return store.replaceArchive(content)
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult {
        ensureInitialized()
        return store.mutate(mutation)
    }

    fun replaceAll(content: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.replaceAll(content)
    }

    fun enabledFlow(): Flow<Boolean> = SettingsDataStore.memoryEnabledFlow()

    suspend fun isEnabled(): Boolean = SettingsDataStore.settings().memoryEnabled

    suspend fun setEnabled(enabled: Boolean) = SettingsDataStore.setMemoryEnabled(enabled)

    private fun ensureInitialized() {
        check(::store.isInitialized) {
            "AgentMemoryRepository.init(context) must be called in Application.onCreate()"
        }
    }
}
