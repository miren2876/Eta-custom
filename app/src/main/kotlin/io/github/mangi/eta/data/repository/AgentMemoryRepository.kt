package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
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

/** 档案检索命中的一行。 */
internal data class AgentMemoryLineHit(
    val lineNumber: Int,
    val line: String,
    val score: Double,
)

/** 一个档案的检索命中结果。 */
internal data class AgentMemoryFileHit(
    val name: String,
    val lineCount: Int,
    val totalScore: Double,
    val hits: List<AgentMemoryLineHit>,
    /** 命中来自归档目录（归档档案不出现在档案索引里）。 */
    val archived: Boolean = false,
)

/** 一个记忆档案（memory/files/<name>.md）的元信息。 */
internal data class AgentMemoryFileInfo(
    val name: String,
    val lineCount: Int,
    val byteSize: Int,
    val hitCount: Int = 0,
    val lastHitAtMillis: Long = 0L,
    val writtenAtMillis: Long = 0L,
    /** 长期未引用（或被引用后长期未再命中）。 */
    val cold: Boolean = false,
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

/** 工作记忆条目：一行 `- (重要度) 内容`，附 sidecar 统计与算出的有效重要度。 */
internal data class AgentMemoryEntry(
    val text: String,
    val importance: Int,
    val hitCount: Int,
    val lastHitAtMillis: Long,
    val writtenAtMillis: Long,
    val effectiveImportance: Double,
)

/** 条目检索命中。 */
internal data class AgentMemoryEntryHit(
    val entry: AgentMemoryEntry,
    val score: Double,
)

/** 条目统计的 sidecar（memory/working.json）：与档案的 usage.json 分开，互不干扰。 */
internal data class AgentMemoryEntryUsage(
    val entries: Map<String, AgentMemoryUsageEntry> = emptyMap(),
    /** 旧版「每行一个档案名」的清单行是否已清理过（一次性迁移标记）。 */
    val migrated: Boolean = false,
)

/** 章节的引用统计；持久化在 memory/usage.json，不影响 MEMORY.md 的 revision。 */
/**
 * 核心记忆（画像 + 工作记忆条目）的容量占用。
 * entryBytes 是实际注入的条目字符数，skippedCount 是被预算挤掉、这一轮没有注入的条目数。
 */
internal data class AgentMemoryCoreUsage(
    /** 画像的 UTF-8 字节数（5 KB 文件上限按字节算）。 */
    val profileBytes: Int,
    /** 画像注入到投影里的字符数；预算按字符算，不能和字节混加。 */
    val profileChars: Int = 0,
    /** 已注入条目的字符数。 */
    val entryBytes: Int,
    val entryCount: Int,
    val totalEntryCount: Int,
    val skippedCount: Int,
    val budgetChars: Int,
    /** 工作记忆文件自身的字节数（文件写入上限按字节算）。 */
    val workingBytes: Int = 0,
) {
    /** 投影已用字符数 = 画像字符 + 条目字符。 */
    val usedChars: Int get() = profileChars + entryBytes
    val ratio: Float get() = if (budgetChars <= 0) 0f else usedChars.toFloat() / budgetChars

    /** 工作记忆文件已经超过写入上限：新增条目会被拒绝，必须靠压缩解决。 */
    val workingOverLimit: Boolean get() = workingBytes > AgentMemoryStore.WORKING_MAX_BYTES

    /** 需要压缩整理：条目被挤掉、已用超过预算 90%，或工作记忆文件自己撑爆了。 */
    val needsCompaction: Boolean get() = skippedCount > 0 || ratio >= 0.9f || workingOverLimit
}

/**
 * 工作记忆硬上限判定：只有「新内容变大且超过上限」才拒绝。
 * 删除条目、改写变短永远放行，否则已经超限的文件会连自我修复都做不到。
 */
internal fun workingGrowDenied(newBytes: Int, previousBytes: Int, limitBytes: Int): Boolean =
    newBytes > limitBytes && newBytes > previousBytes

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

/** 归档目录里的一个档案（memory/archive/<名字>.md）在迁移与备份文本里的中间形态。 */
internal data class AgentMemoryArchivedEntry(
    val name: String,
    val body: String,
    val archivedAtMillis: Long,
)

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
    /** 旧版单文件归档（memory/MEMORY-archive.md）：只在迁移时读写。 */
    private val legacyArchiveFile = AtomicFile(File(memoryDir, ARCHIVE_FILE_NAME))
    private val usageFile = AtomicFile(File(memoryDir, USAGE_FILE_NAME))
    /** 工作记忆条目的统计 sidecar（引用次数 / 写入时间）。 */
    private val entryFile = AtomicFile(File(memoryDir, ENTRY_FILE_NAME))
    /** 旧结构文件：画像 + 工作记忆同在一个 CORE.md，只在迁移时读写。 */
    private val coreFile = AtomicFile(File(memoryDir, LEGACY_CORE_FILE_NAME))
    private val userFile = AtomicFile(File(memoryDir, USER_FILE_NAME))
    private val workingFile = AtomicFile(File(memoryDir, FILE_NAME))
    private val filesDir = File(memoryDir, FILES_DIR_NAME)
    /** 归档目录（memory/archive/）：一个归档档案一个文件。 */
    private val archiveDir = File(memoryDir, ARCHIVE_DIR_NAME)
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
        // 建议归档 = 长期未引用的档案文件；MEMORY.md 现在是工作记忆，不再按章节判断。
        files()
            .filter { it.cold }
            .map { file ->
                AgentMemorySection(
                    title = file.name,
                    level = 2,
                    startLine = 0,
                    endLine = file.lineCount,
                    lineCount = file.lineCount,
                    hitCount = file.hitCount,
                    lastHitAtMillis = file.lastHitAtMillis,
                    writtenAtMillis = file.writtenAtMillis,
                    preview = filePreview(file.name),
                )
            }
    }

    /** 档案正文的第一行非空文本，用作记忆页的预览。 */
    private fun filePreview(name: String): String {
        val file = fileFor(name) ?: return ""
        if (!file.exists()) return ""
        return file.readText().memoryLines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(PREVIEW_CHARS)
            .orEmpty()
    }

    /** 归档内容汇总：把 memory/archive/ 下的独立档案拼成一份文本（备份与体积统计用）。 */
    fun archiveSnapshot(): AgentMemoryArchiveSnapshot = synchronized(lock) { archiveSnapshotLocked() }

    private fun archiveSnapshotLocked(): AgentMemoryArchiveSnapshot {
        val files = archivedFilesLocked()
        val content = mergedArchiveText(files)
        return AgentMemoryArchiveSnapshot(
            content = content,
            byteSize = content.toByteArray(Charsets.UTF_8).size,
            sectionCount = files.size,
        )
    }

    /** 归档目录下的档案文件，最近归档的在前。 */
    private fun archivedFilesLocked(): List<File> =
        archiveDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
            ?.sortedWith(compareByDescending<File> { archivedAt(it) }.thenBy { it.name })
            .orEmpty()

    /** 把归档档案拼成旧版单文件格式（`## 名字（归档于 …）` 章节），备份与外发共用。 */
    private fun mergedArchiveText(files: List<File>): String = files.joinToString("\n\n") { file ->
        val body = file.readText().trimEnd('\n')
        buildString {
            append("## ").append(file.name.removeSuffix(FILE_SUFFIX))
                .append("（归档于 ").append(stampOf(archivedAt(file))).append("）")
            if (body.isNotBlank()) append('\n').append(body)
        }
    }

    /** 归档落盘：正文原样保存，归档时间记在文件 mtime 上，不往正文里塞元信息。 */
    private fun writeArchived(target: File, content: String, archivedAtMillis: Long): Boolean {
        archiveDir.mkdirs()
        val trimmed = content.trimEnd('\n')
        return runCatching {
            target.writeText(if (trimmed.isEmpty()) "" else "$trimmed\n")
            target.setLastModified(archivedAtMillis)
        }.isSuccess
    }

    private fun archivedAt(file: File): Long = file.lastModified().takeIf { it > 0L } ?: 0L

    private fun stampOf(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(if (millis > 0L) millis else System.currentTimeMillis()))

    /** 把指定章节各写成一个 memory/archive/<标题>.md；正文其余部分保持不变。 */
    fun archive(titles: List<String>): AgentMemoryArchiveResult = synchronized(lock) {
        if (titles.isEmpty()) {
            throw AgentMemoryException("MEMORY_ARCHIVE_EMPTY", "没有需要归档的章节")
        }
        val snapshot = snapshotLocked()
        val lines = snapshot.content.memoryLines()
        val ranges = AgentMemoryScoring.sections(lines)
        // 调用方可能传原始标题（含 `#`）或去掉前缀的显示名，两种都接受。
        val wanted = titles.toSet()
        val selected = ranges.filter { it.title in wanted || it.displayTitle in wanted }
        if (selected.isEmpty()) {
            throw AgentMemoryException("MEMORY_ARCHIVE_MISSING", "要归档的章节已不存在，请刷新后重试")
        }
        val removed = HashSet<Int>()
        val archivedTitles = mutableListOf<String>()
        val now = System.currentTimeMillis()
        selected.forEach { range ->
            for (index in range.startIndex until range.endIndex) removed += index
            val name = range.displayTitle.ifBlank { "未命名" }
            val body = lines.subList(range.startIndex + 1, range.endIndex)
                .joinToString("\n")
                .trim('\n')
            val target = archivedFileFor(name) ?: return@forEach
            writeArchived(target, body, now)
            archivedTitles += name
        }
        val remaining = lines.filterIndexed { index, _ -> index !in removed }
            .joinToString("\n")
            .trimEnd('\n')
        writeLocked(remaining)
        val refreshedUsage = usageLocked().sections.filterKeys { title -> title !in titles }
        writeUsageLocked(AgentMemoryUsage(refreshedUsage))
        val updated = snapshotOf(remaining)
        AgentMemoryArchiveResult(
            snapshot = updated,
            archivedTitles = archivedTitles,
            archiveByteSize = archiveSnapshotLocked().byteSize,
        )
    }

    /** 取消全部归档：把 archive/ 下的档案逐个移回 files/，返回还原数量。 */
    fun restoreArchive(): Int = synchronized(lock) {
        val names = archivedFilesLocked().map { file -> file.name.removeSuffix(FILE_SUFFIX) }
        if (names.isEmpty()) {
            throw AgentMemoryException("MEMORY_ARCHIVE_EMPTY", "归档是空的")
        }
        unarchiveLocked(names)
    }

    /** 记忆档案清单：memory/files/ 下的独立文件。 */
    fun files(): List<AgentMemoryFileInfo> = synchronized(lock) {
        val usage = usageLocked().sections
        val now = System.currentTimeMillis()
        filesDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
            ?.sortedBy { it.name }
            ?.map { file ->
                val text = file.readText()
                val name = file.name.removeSuffix(FILE_SUFFIX)
                val entry = usage[name]
                val writtenAt = file.lastModified().takeIf { it > 0L } ?: now
                val lastHit = entry?.lastHitAtMillis ?: 0L
                val idleSince = if (lastHit > 0L) lastHit else writtenAt
                val idleMillis = (now - idleSince).coerceAtLeast(0L)
                val cold = if (lastHit > 0L) {
                    idleMillis >= DEFAULT_COLD_IDLE_DAYS * DAY_MILLIS
                } else {
                    idleMillis >= DEFAULT_NEVER_USED_GRACE_DAYS * DAY_MILLIS
                }
                AgentMemoryFileInfo(
                    name = name,
                    lineCount = text.memoryLines().size,
                    byteSize = text.toByteArray(Charsets.UTF_8).size,
                    hitCount = entry?.hitCount ?: 0,
                    lastHitAtMillis = lastHit,
                    writtenAtMillis = writtenAt,
                    cold = cold,
                )
            }
            .orEmpty()
    }

    /** 删除一个档案文件（同时清掉它的用量记录）；返回是否真的删掉了。 */
    fun deleteFile(name: String): Boolean = synchronized(lock) {
        val file = fileFor(name) ?: return@synchronized false
        val deleted = file.exists() && file.delete()
        if (deleted) {
            val remaining = usageLocked().sections.filterKeys { it != name }
            writeUsageLocked(AgentMemoryUsage(remaining))
        }
        deleted
    }

    /**
     * 档案变动后同步工作记忆：新版工作记忆不再逐条列档案（清单由 `files/` 实时生成），
     * 这里只清理旧版遗留的「- 档案名」行。added 保留在签名里以兼容调用方。
     */
    fun syncWorkingIndex(removed: List<String>, added: String?): AgentMemorySnapshot = synchronized(lock) {
        val keys = removed.map { name -> AgentMemoryScoring.entryKey(name) }.toSet()
        val lines = readText(workingFile).memoryLines()
        var current = lines
        if (keys.isNotEmpty()) {
            current = lines.filterNot { line ->
                val parsed = AgentMemoryScoring.entryLine(line) ?: return@filterNot false
                AgentMemoryScoring.entryKey(parsed.text) in keys
            }
        }
        // added：合并 / 拆分后的新档案名补一行索引，已有的不重复加。
        val addedName = added?.trim().orEmpty()
        if (addedName.isNotEmpty()) {
            val addedKey = AgentMemoryScoring.entryKey(addedName)
            val exists = current.any { line ->
                val parsed = AgentMemoryScoring.entryLine(line) ?: return@any false
                AgentMemoryScoring.entryKey(parsed.text).contains(addedKey)
            }
            if (!exists) current = current + "- $addedName"
        }
        if (current.size != lines.size || current != lines) {
            val merged = current.joinToString("\n").trimEnd('\n')
            writeText(workingFile, if (merged.isEmpty()) "" else "$merged\n")
        }
        coreSnapshotLocked()
    }

    /** 工作记忆条目，按有效重要度（EI）降序。 */
    fun entries(): List<AgentMemoryEntry> = synchronized(lock) { entriesLocked() }

    private fun entriesLocked(nowMillis: Long = System.currentTimeMillis()): List<AgentMemoryEntry> {
        val stats = entryUsageLocked().entries
        return readText(workingFile).memoryLines()
            .mapNotNull { line -> AgentMemoryScoring.entryLine(line) }
            .map { parsed ->
                val stat = stats[AgentMemoryScoring.entryKey(parsed.text)]
                val hits = stat?.hitCount ?: 0
                val lastHit = stat?.lastHitAtMillis ?: 0L
                val writtenAt = stat?.writtenAtMillis ?: 0L
                AgentMemoryEntry(
                    text = parsed.text,
                    importance = parsed.importance,
                    hitCount = hits,
                    lastHitAtMillis = lastHit,
                    writtenAtMillis = writtenAt,
                    effectiveImportance = AgentMemoryScoring.effectiveImportance(
                        importance = parsed.importance,
                        hitCount = hits,
                        lastTouchedMillis = maxOf(lastHit, writtenAt),
                        nowMillis = nowMillis,
                    ),
                )
            }
            .sortedWith(compareByDescending<AgentMemoryEntry> { it.effectiveImportance }.thenBy { it.text })
    }

    /** 在活跃条目里按相关度检索（命中会累加引用次数）。 */
    fun searchEntries(query: String, maxEntries: Int = 5): List<AgentMemoryEntryHit> = synchronized(lock) {
        if (AgentMemoryScoring.tokenize(query).isEmpty()) return@synchronized emptyList()
        entriesLocked()
            .mapNotNull { entry ->
                val score = AgentMemoryScoring.scoreLines(listOf(entry.text), query).firstOrNull()?.score
                if (score == null) null else AgentMemoryEntryHit(entry, score)
            }
            .sortedByDescending { it.score }
            .take(maxEntries)
    }

    /**
     * 写一条工作记忆条目。
     *
     * - `match` 为空：追加一条 `- (重要度) 内容`
     * - `match` 非空：替换匹配到的第一条（归一化后包含匹配）；`content` 为空表示删除该条
     */
    fun writeEntry(content: String, importance: Int?, match: String?): AgentMemorySnapshot = synchronized(lock) {
        // 容错：模型可能整行复制条目（`- (3) 事实` / `(3) 事实` / 双重标记），统一剥掉前缀。
        val normalized = AgentMemoryScoring.normalizeEntryInput(content)
        val text = normalized?.second.orEmpty()
        val entryImportance = importance ?: normalized?.first
        if (text.length > MAX_ENTRY_CHARS) {
            throw AgentMemoryException(
                "MEMORY_ENTRY_TOO_LONG",
                "单条条目请控制在 $MAX_ENTRY_CHARS 字符内；长内容写档案（archive=<名字>）",
            )
        }
        // 条目池是一行一条：丢掉空行（含末尾换行 split 出的空串），顺带把历史遗留的空行清掉。
        val lines = readText(workingFile).memoryLines().filter { it.isNotBlank() }.toMutableList()
        val usage = entryUsageLocked()
        val stats = usage.entries.toMutableMap()
        val matchKey = match?.let { value -> AgentMemoryScoring.entryKey(value) }
        if (matchKey == null) {
            if (text.isEmpty()) throw AgentMemoryException("MEMORY_ENTRY_EMPTY", "条目内容不能为空")
            val value = (entryImportance ?: AgentMemoryScoring.DEFAULT_ENTRY_IMPORTANCE).coerceIn(1, 5)
            lines += "- ($value) $text"
            val key = AgentMemoryScoring.entryKey(text)
            stats[key] = registerEntryWritten(stats, key)
        } else {
            val index = lines.indexOfFirst { line ->
                val parsed = AgentMemoryScoring.entryLine(line) ?: return@indexOfFirst false
                AgentMemoryScoring.entryKey(parsed.text).contains(matchKey)
            }
            if (index < 0) {
                throw AgentMemoryException(
                    "MEMORY_ENTRY_MISSING",
                    "没有匹配到要改写的条目，先用 memory_get 看一下当前条目",
                )
            }
            val previous = AgentMemoryScoring.entryLine(lines[index])
            val oldKey = previous?.let { parsed -> AgentMemoryScoring.entryKey(parsed.text) }
            if (text.isEmpty()) {
                lines.removeAt(index)
                if (oldKey != null) stats.remove(oldKey)
            } else {
                val value = (entryImportance ?: previous?.importance ?: AgentMemoryScoring.DEFAULT_ENTRY_IMPORTANCE)
                    .coerceIn(1, 5)
                lines[index] = "- ($value) $text"
                val newKey = AgentMemoryScoring.entryKey(text)
                if (oldKey != null && oldKey != newKey) stats.remove(oldKey)?.let { stats[newKey] = it }
                stats[newKey] = registerEntryWritten(stats, newKey)
            }
        }
        val merged = lines.joinToString("\n").trimEnd('\n')
        val nextBytes = (if (merged.isEmpty()) "" else "$merged\n").toByteArray(Charsets.UTF_8).size
        val previousBytes = readText(workingFile).toByteArray(Charsets.UTF_8).size
        if (workingGrowDenied(nextBytes, previousBytes, WORKING_MAX_BYTES)) {
            throw AgentMemoryException(
                "MEMORY_WORKING_FULL",
                "工作记忆已达 10 KiB 上限，这次写入会让它继续变大；先删掉或合并几条低价值条目" +
                    "（memory_write target=entry match=\"<片段>\" content=\"\"）再写",
            )
        }
        writeText(workingFile, if (merged.isEmpty()) "" else "$merged\n")
        writeEntryUsageLocked(usage.copy(entries = stats))
        coreSnapshotLocked()
    }

    /** 读条目时记一次命中（EI 的访问因子）。 */
    fun recordEntryHit(text: String) = synchronized(lock) {
        val key = AgentMemoryScoring.entryKey(text)
        if (key.isBlank()) return@synchronized
        val usage = entryUsageLocked()
        val stats = usage.entries.toMutableMap()
        val previous = stats[key] ?: AgentMemoryUsageEntry()
        stats[key] = previous.copy(
            hitCount = previous.hitCount + 1,
            lastHitAtMillis = System.currentTimeMillis(),
        )
        writeEntryUsageLocked(usage.copy(entries = stats))
    }

    /**
     * 一次性迁移：旧版工作记忆里逐条列出的档案清单行（`- 档案名`）不再需要，
     * 档案清单由 `files/` 实时生成。
     */
    fun migrateWorkingEntriesIfNeeded(): Boolean = synchronized(lock) {
        val usage = entryUsageLocked()
        if (usage.migrated) return@synchronized false
        val names = files().map { file -> AgentMemoryScoring.entryKey(file.name) }.toSet()
        val lines = readText(workingFile).memoryLines()
        val kept = lines.filterNot { line ->
            val parsed = AgentMemoryScoring.entryLine(line) ?: return@filterNot false
            AgentMemoryScoring.entryKey(parsed.text) in names
        }
        if (kept.size != lines.size) {
            val merged = kept.joinToString("\n").trimEnd('\n')
            writeText(workingFile, if (merged.isEmpty()) "" else "$merged\n")
        }
        writeEntryUsageLocked(usage.copy(migrated = true))
        kept.size != lines.size
    }

    /**
     * 核心记忆的容量占用：与 [coreProjection] 用同一份预算算法，
     * 供容量检测、记忆页展示与压缩整理判断。
     */
    fun coreUsage(budgetChars: Int): AgentMemoryCoreUsage = synchronized(lock) {
        val split = coreSplitLocked(budgetChars)
        AgentMemoryCoreUsage(
            profileBytes = split.profile.toByteArray(Charsets.UTF_8).size,
            profileChars = split.profile.length,
            entryBytes = split.usedChars,
            entryCount = split.injected.size,
            totalEntryCount = split.injected.size + split.skipped.size,
            skippedCount = split.skipped.size,
            budgetChars = budgetChars,
            workingBytes = readText(workingFile).toByteArray(Charsets.UTF_8).size,
        )
    }

    /** 被预算挤掉、这一轮没有注入的条目；压缩整理用它在提示词里给出候选。 */
    fun coreSkippedEntries(budgetChars: Int): List<AgentMemoryEntry> = synchronized(lock) {
        coreSplitLocked(budgetChars).skipped
    }

    /** 与 [coreProjection] 同一套预算：把条目分成「已注入」与「被挤掉」两批。 */
    private fun coreSplitLocked(budgetChars: Int): CoreSplit {
        val profile = readText(userFile).trimEnd('\n')
        val files = files()
        val head = buildString {
            append(PROFILE_HEADING).append('\n')
            if (profile.isNotEmpty()) append(profile).append('\n')
            append('\n').append(WORKING_HEADING).append('\n')
        }
        val tail = buildString {
            append('\n').append(ARCHIVE_HEADING).append('\n')
            if (files.isEmpty()) {
                append("暂无档案。")
            } else {
                append("共 ").append(files.size).append(" 个档案")
                val warm = files.count { file -> !file.cold }
                if (warm in 1 until files.size) append("（").append(warm).append(" 个活跃）")
                append("；细节用 memory_get query=<关键词> 或 archive=<名字> 读取。")
            }
        }
        val room = (budgetChars - head.length - tail.length).coerceAtLeast(0)
        var used = 0
        val injected = mutableListOf<AgentMemoryEntry>()
        val skipped = mutableListOf<AgentMemoryEntry>()
        entriesLocked().forEach { entry ->
            val line = "- (${entry.importance}) ${entry.text}"
            if (used + line.length + 1 > room) {
                skipped += entry
            } else {
                used += line.length + 1
                injected += entry
            }
        }
        return CoreSplit(profile, used, injected, skipped)
    }

    private data class CoreSplit(
        val profile: String,
        val usedChars: Int,
        val injected: List<AgentMemoryEntry>,
        val skipped: List<AgentMemoryEntry>,
    )

    /**
     * 注入用投影：用户画像 + 按 EI 降序的活跃条目（不超过 budgetChars）+ 一行档案统计。
     * 超预算只截断条目，画像与档案行始终保留。
     */
    fun coreProjection(budgetChars: Int): String = synchronized(lock) {
        val profile = readText(userFile).trimEnd('\n')
        val files = files()
        val head = buildString {
            append(PROFILE_HEADING).append('\n')
            if (profile.isNotEmpty()) append(profile).append('\n')
            append('\n').append(WORKING_HEADING).append('\n')
        }
        val tail = buildString {
            append('\n').append(ARCHIVE_HEADING).append('\n')
            if (files.isEmpty()) {
                append("暂无档案。")
            } else {
                append("共 ").append(files.size).append(" 个档案")
                val warm = files.count { file -> !file.cold }
                if (warm in 1 until files.size) append("（").append(warm).append(" 个活跃）")
                append("；细节用 memory_get query=<关键词> 或 archive=<名字> 读取。")
            }
        }
        val room = (budgetChars - head.length - tail.length).coerceAtLeast(0)
        val selected = mutableListOf<String>()
        var used = 0
        var skipped = 0
        entriesLocked().forEach { entry ->
            val line = "- (${entry.importance}) ${entry.text}"
            if (used + line.length + 1 > room) {
                skipped++
                return@forEach
            }
            selected += line
            used += line.length + 1
        }
        buildString {
            append(head)
            if (selected.isEmpty()) {
                append("（暂无活跃条目；用 memory_write target=entry 记一条）")
            } else {
                append(selected.joinToString("\n"))
                if (skipped > 0) {
                    append("\n（另有 ").append(skipped).append(" 条低 EI 条目未注入，需要时用 memory_get query= 检索）")
                }
            }
            append(tail)
        }.trimEnd('\n')
    }

    /** 登记条目首次写入时间（已有记录不动）。 */
    private fun registerEntryWritten(
        stats: MutableMap<String, AgentMemoryUsageEntry>,
        key: String,
    ): AgentMemoryUsageEntry {
        val previous = stats[key] ?: AgentMemoryUsageEntry()
        return if (previous.writtenAtMillis > 0L) {
            previous
        } else {
            previous.copy(writtenAtMillis = System.currentTimeMillis())
        }
    }

    private fun entryUsageLocked(): AgentMemoryEntryUsage {
        val raw = readText(entryFile)
        if (raw.isBlank()) return AgentMemoryEntryUsage()
        return runCatching {
            val json = JSONObject(raw)
            val stats = mutableMapOf<String, AgentMemoryUsageEntry>()
            json.optJSONObject(FIELD_ENTRIES)?.let { statsJson ->
                statsJson.keys().forEach { key ->
                    val entry = statsJson.optJSONObject(key) ?: return@forEach
                    stats[key] = AgentMemoryUsageEntry(
                        hitCount = entry.optInt(FIELD_HIT_COUNT, 0),
                        lastHitAtMillis = entry.optLong(FIELD_LAST_HIT, 0L),
                        writtenAtMillis = entry.optLong(FIELD_WRITTEN_AT, 0L),
                    )
                }
            }
            AgentMemoryEntryUsage(
                entries = stats,
                migrated = json.optBoolean(FIELD_ENTRIES_MIGRATED, false),
            )
        }.getOrDefault(AgentMemoryEntryUsage())
    }

    private fun writeEntryUsageLocked(usage: AgentMemoryEntryUsage) {
        runCatching {
            val stats = JSONObject()
            usage.entries.forEach { (key, entry) ->
                stats.put(
                    key,
                    JSONObject()
                        .put(FIELD_HIT_COUNT, entry.hitCount)
                        .put(FIELD_LAST_HIT, entry.lastHitAtMillis)
                        .put(FIELD_WRITTEN_AT, entry.writtenAtMillis),
                )
            }
            val json = JSONObject()
                .put(FIELD_VERSION, USAGE_SCHEMA_VERSION)
                .put(FIELD_ENTRIES_MIGRATED, usage.migrated)
                .put(FIELD_ENTRIES, stats)
            writeText(entryFile, json.toString())
        }
    }

    /**
     * 在所有档案里按相关度检索：先逐档案打分，再取每个档案的命中行，
     * 按档案总分排序返回。用于 `memory_get query=<文本>`。
     */
    fun searchFiles(
        query: String,
        maxFiles: Int = 5,
        maxLinesPerFile: Int = 6,
    ): List<AgentMemoryFileHit> = synchronized(lock) {
        if (AgentMemoryScoring.tokenize(query).isEmpty()) return@synchronized emptyList()
        listOf(filesDir to false, archiveDir to true)
            .flatMap { (dir, archived) ->
                dir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
                    ?.map { it to archived }
                    .orEmpty()
            }
            .mapNotNull { (file, archived) ->
                val lines = file.readText().memoryLines()
                val scored = AgentMemoryScoring.scoreLines(lines, query)
                if (scored.isEmpty()) return@mapNotNull null
                AgentMemoryFileHit(
                    name = file.name.removeSuffix(FILE_SUFFIX),
                    lineCount = lines.size,
                    totalScore = scored.sumOf { it.score },
                    archived = archived,
                    hits = scored.sortedByDescending { it.score }
                        .take(maxLinesPerFile)
                        .sortedBy { it.index }
                        .map { hit ->
                            AgentMemoryLineHit(
                                lineNumber = hit.index + 1,
                                line = lines.getOrElse(hit.index) { "" },
                                score = hit.score,
                            )
                        },
                )
            }
            .sortedByDescending { it.totalScore }
            .take(maxFiles)
    }

    /** 档案清单（供工具与注入共用的简短索引）。 */
    fun fileIndexText(): String = files().joinToString("\n") { file ->
        buildString {
            append("- ").append(file.name)
            append("（").append(file.lineCount).append(" 行")
            if (file.hitCount > 0) append("，引用 ").append(file.hitCount).append(" 次")
            append("）")
        }
    }

    /** 读档案时记一次命中，供「引用次数 / 冷档案」使用。 */
    fun recordFileHit(name: String) = synchronized(lock) {
        if (name.isBlank()) return@synchronized
        val current = usageLocked().sections.toMutableMap()
        val previous = current[name] ?: AgentMemoryUsageEntry()
        current[name] = previous.copy(
            hitCount = previous.hitCount + 1,
            lastHitAtMillis = System.currentTimeMillis(),
        )
        writeUsageLocked(AgentMemoryUsage(current))
    }
    /** 档案写入后登记首次写入时间（已有记录不动），供冷档案判定使用。 */
    fun recordFileWritten(name: String) = synchronized(lock) {
        if (name.isBlank()) return@synchronized
        val current = usageLocked().sections.toMutableMap()
        val previous = current[name] ?: AgentMemoryUsageEntry()
        if (previous.writtenAtMillis > 0L) return@synchronized
        current[name] = previous.copy(writtenAtMillis = System.currentTimeMillis())
        writeUsageLocked(AgentMemoryUsage(current))
    }

    /** 读某个档案的全文。 */
    fun fileText(name: String): String = synchronized(lock) {
        // 归档档案不出现在索引里，但按名字仍然读得到。
        val file = fileFor(name)?.takeIf { it.exists() }
            ?: archivedFileFor(name)?.takeIf { it.exists() }
            ?: return@synchronized ""
        file.readText()
    }

    /** 写某个档案：append=true 追加，否则整体替换（不存在就创建）。 */
    fun writeFile(name: String, content: String, append: Boolean): AgentMemoryFileInfo = synchronized(lock) {
        val file = fileFor(name) ?: throw AgentMemoryException("MEMORY_FILE_NAME_INVALID", "档案名不合法")
        val merged = if (append && file.exists()) {
            val existing = file.readText().trimEnd('\n')
            if (existing.isEmpty()) content.trim() else existing + "\n\n" + content.trim()
        } else {
            content.trim()
        }
        if (merged.toByteArray(Charsets.UTF_8).size > FILE_MAX_BYTES) {
            throw AgentMemoryException("MEMORY_TOO_LARGE", "档案超过 10 MiB 上限")
        }
        filesDir.mkdirs()
        file.writeText(if (merged.isEmpty()) "" else "$merged\n")
        recordFileWritten(name.trim())
        AgentMemoryFileInfo(
            name = name.trim(),
            lineCount = merged.memoryLines().size,
            byteSize = merged.toByteArray(Charsets.UTF_8).size,
        )
    }

    /**
     * 唯一匹配替换：`oldText` 必须恰好出现一次，否则拒绝。
     * 给「修正已有档案」用——比整体覆盖安全，也比纯追加干净。
     */
    fun editFile(name: String, oldText: String, content: String): AgentMemoryFileInfo = synchronized(lock) {
        val file = fileFor(name) ?: throw AgentMemoryException("MEMORY_FILE_NAME_INVALID", "档案名不合法")
        if (!file.exists()) {
            throw AgentMemoryException("MEMORY_ARCHIVE_MISSING", "档案不存在：${name.trim()}")
        }
        if (oldText.isBlank()) {
            throw AgentMemoryException("MEMORY_ARGUMENT_INVALID", "old_text 不能为空")
        }
        val existing = file.readText()
        val first = existing.indexOf(oldText)
        if (first < 0) {
            throw AgentMemoryException("MEMORY_MATCH_MISSING", "档案里找不到 old_text")
        }
        if (existing.indexOf(oldText, first + oldText.length) >= 0) {
            throw AgentMemoryException("MEMORY_MATCH_AMBIGUOUS", "old_text 在档案里出现多次，请给更长的片段")
        }
        val updated = existing.replaceRange(first, first + oldText.length, content.trim()).trimEnd('\n')
        if (updated.toByteArray(Charsets.UTF_8).size > FILE_MAX_BYTES) {
            throw AgentMemoryException("MEMORY_TOO_LARGE", "档案超过 10 MiB 上限")
        }
        file.writeText(if (updated.isEmpty()) "" else "$updated\n")
        recordFileWritten(name.trim())
        AgentMemoryFileInfo(
            name = name.trim(),
            lineCount = updated.memoryLines().size,
            byteSize = updated.toByteArray(Charsets.UTF_8).size,
        )
    }

    /** 档案名 → 文件名（目录分隔符与 `..` 替换掉，避免越出档案目录）。 */
    /** 档案名校验：与内部写出同一套规则，非法返回 null。 */
    fun safeFileName(name: String): String? = synchronized(lock) { sanitizeName(name) }

    private fun sanitizeName(name: String): String? {
        val safe = name.trim()
            .replace('/', '_')
            .replace('\\', '_')
            .replace("..", "_")
        return safe.ifBlank { null }
    }

    private fun fileFor(name: String): File? =
        sanitizeName(name)?.let { safe -> File(filesDir, safe + FILE_SUFFIX) }

    /** 归档目录里的同名档案文件。 */
    private fun archivedFileFor(name: String): File? =
        sanitizeName(name)?.let { safe -> File(archiveDir, safe + FILE_SUFFIX) }

    /** 用户画像（USER.md）。 */
    fun coreProfile(): String = synchronized(lock) { readText(userFile).trimEnd('\n') }

    /** 工作记忆（MEMORY.md：档案索引 + 当前进度）。 */
    fun coreWorking(): String = synchronized(lock) { readText(workingFile).trimEnd('\n') }

    fun replaceCoreProfile(text: String): AgentMemorySnapshot = synchronized(lock) {
        writeProfile(text)
        coreSnapshotLocked()
    }

    fun replaceCoreWorking(text: String): AgentMemorySnapshot = synchronized(lock) {
        writeWorking(text)
        coreSnapshotLocked()
    }

    /** 兼容旧备份：把「用户画像 + 工作记忆」两节文本分别写回两个文件。 */
    fun replaceCoreLegacy(text: String): AgentMemorySnapshot = synchronized(lock) {
        val (profile, working) = splitCore(text)
        writeProfile(profile)
        writeWorking(working)
        coreSnapshotLocked()
    }

    private fun writeProfile(text: String) {
        val trimmed = text.trim()
        if (trimmed.toByteArray(Charsets.UTF_8).size > PROFILE_MAX_BYTES) {
            throw AgentMemoryException("MEMORY_PROFILE_TOO_LARGE", "用户画像超过 5 KB，请先精简")
        }
        writeText(userFile, if (trimmed.isEmpty()) "" else "$trimmed\n")
    }

    private fun writeWorking(text: String) {
        val trimmed = text.trim()
        if (trimmed.toByteArray(Charsets.UTF_8).size > WORKING_HARD_MAX_BYTES) {
            throw AgentMemoryException("MEMORY_WORKING_TOO_LARGE", "工作记忆超过 64 KB，请精简或压缩后再写入")
        }
        writeText(workingFile, if (trimmed.isEmpty()) "" else "$trimmed\n")
    }

    /** 解析 CORE.md 的两节；旧格式（没有标题）整体算用户画像。 */
    private fun splitCore(content: String): Pair<String, String> {
        val lines = content.memoryLines()
        val profileStart = lines.indexOfFirst { it.trim() == PROFILE_HEADING }
        val workingStart = lines.indexOfFirst { it.trim() == WORKING_HEADING }
        if (profileStart < 0 && workingStart < 0) return content.trim() to ""
        val profile = if (profileStart >= 0) {
            val end = if (workingStart > profileStart) workingStart else lines.size
            lines.subList(profileStart + 1, end).joinToString("\n").trim()
        } else {
            ""
        }
        val working = if (workingStart >= 0) {
            lines.subList(workingStart + 1, lines.size).joinToString("\n").trim()
        } else {
            ""
        }
        return profile to working
    }

    /** 拼出旧结构 CORE.md 的文本（画像 + 工作记忆），只用于迁移中间态。 */
    private fun legacyCoreText(profile: String, working: String): String {
        val content = buildString {
            append(PROFILE_HEADING).append('\n')
            if (profile.isNotEmpty()) append(profile).append('\n')
            append('\n').append(WORKING_HEADING).append('\n')
            if (working.isNotEmpty()) append(working).append('\n')
        }.trimEnd('\n')
        return "$content\n"
    }

    /**
     * 首次使用时把 MEMORY.md 里的每个 `##` 章节拆成 memory/files/<标题>.md，
     * 档案清单补进核心记忆的「工作记忆」，原文件另存为 MEMORY.md.bak 后清空。
     */
    /** 把旧的 CORE.md（用户画像 + 工作记忆两节）拆成 USER.md 与 MEMORY.md，随后删掉 CORE.md。 */
    fun migrateCoreFilesIfNeeded(): Boolean = synchronized(lock) {
        val legacyFile = coreFile.baseFile
        if (!legacyFile.exists()) return@synchronized false
        val (profile, working) = splitCore(readText(coreFile))
        if (readText(userFile).isBlank() && profile.isNotBlank()) writeText(userFile, "$profile\n")
        if (readText(workingFile).isBlank() && working.isNotBlank()) writeText(workingFile, "$working\n")
        runCatching { legacyFile.delete() }
        true
    }

    fun migrateFilesIfNeeded(): Boolean = synchronized(lock) {
        // 核心记忆已拆成 USER.md + MEMORY.md 后，MEMORY.md 就是工作记忆，不再当作旧档案正文。
        if (userFile.baseFile.exists()) return@synchronized false
        val snapshot = snapshotLocked()
        if (snapshot.content.isBlank()) return@synchronized false
        if (filesDir.listFiles()?.any { it.isFile } == true) return@synchronized false
        val lines = snapshot.content.memoryLines()
        val ranges = AgentMemoryScoring.sections(lines).filter { it.level == 2 }
        // 至少两个 `##` 章节才说明这是旧的档案正文；工作记忆清单不会被误拆。
        if (ranges.size < 2) return@synchronized false
        filesDir.mkdirs()
        val index = mutableListOf<String>()
        ranges.forEach { range ->
            val name = range.displayTitle.ifBlank { "未命名" }
            val file = fileFor(name) ?: return@forEach
            val body = lines.subList(range.startIndex + 1, range.endIndex).joinToString("\n").trim()
            file.writeText(if (body.isEmpty()) "" else "$body\n")
            index += "- $name"
        }
        writeText(AtomicFile(File(memoryDir, "$FILE_NAME.bak")), snapshot.content)
        // 档案已拆完：MEMORY.md 从此不再读写，直接删掉，只留 .bak 以便回滚。
        writeLocked("")
        runCatching { atomicFile.baseFile.delete() }
        val (profile, working) = splitCore(readText(coreFile))
        val merged = if (working.isBlank()) {
            index.joinToString("\n")
        } else {
            working + "\n" + index.joinToString("\n")
        }
        runCatching { writeText(coreFile, legacyCoreText(profile, merged)) }
        syncUsageLocked(snapshotOf(""))
        true
    }

    /**
     * 旧版单文件归档 MEMORY-archive.md → memory/archive/<名字>.md（一档一文件），
     * 原文件另存为 MEMORY-archive.md.bak 后删除；拆不出章节时保持原样，避免丢内容。
     */
    fun migrateArchiveIfNeeded(): Boolean = synchronized(lock) {
        val legacy = legacyArchiveFile.baseFile
        if (!legacy.exists()) return@synchronized false
        val content = readText(legacyArchiveFile)
        if (content.isBlank()) {
            runCatching { legacy.delete() }
            return@synchronized true
        }
        val entries = splitArchiveSections(content)
        if (entries.isEmpty()) return@synchronized false
        val fallbackMillis = legacy.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        entries.forEach { entry ->
            val target = archivedFileFor(entry.name) ?: return@forEach
            if (!target.exists()) {
                writeArchived(target, entry.body, entry.archivedAtMillis.takeIf { it > 0L } ?: fallbackMillis)
            }
        }
        writeText(AtomicFile(File(memoryDir, "$ARCHIVE_FILE_NAME.bak")), content)
        runCatching { legacy.delete() }
        true
    }

    /** 把 `## 名字（归档于 2026-10-09）` 章节文本拆成一条条归档记录（旧文件与备份文本共用）。 */
    private fun splitArchiveSections(content: String): List<AgentMemoryArchivedEntry> {
        val result = mutableListOf<AgentMemoryArchivedEntry>()
        var name: String? = null
        var archivedAtMillis = 0L
        var body = mutableListOf<String>()
        fun flush() {
            val current = name
            if (current != null) {
                result += AgentMemoryArchivedEntry(current, body.joinToString("\n").trim('\n'), archivedAtMillis)
            }
            name = null
            archivedAtMillis = 0L
            body = mutableListOf()
        }
        content.memoryLines().forEach { line ->
            val heading = archiveHeading(line)
            if (heading == null) {
                if (name != null) body += line
            } else {
                flush()
                name = heading.first
                archivedAtMillis = heading.second
            }
        }
        flush()
        return result
    }

    /** 只认带归档戳的标题行，避免把正文里的普通标题当成新档案拆开。 */
    private fun archiveHeading(line: String): Pair<String, Long>? {
        val match = ARCHIVE_STAMP_LINE.matchEntire(line.trim()) ?: return null
        if (match.groupValues[1].length < 2) return null
        val name = match.groupValues[2].trim()
        if (name.isBlank()) return null
        val millis = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .apply { isLenient = false }
                .parse(match.groupValues[3])
                ?.time
        }.getOrNull() ?: 0L
        return name to millis
    }

    /** 把选中的档案移入 memory/archive/（一档一文件），并删除原档案文件。 */
    fun archiveFiles(names: List<String>): Int = synchronized(lock) {
        if (names.isEmpty()) return@synchronized 0
        val now = System.currentTimeMillis()
        var moved = 0
        names.forEach { name ->
            val source = fileFor(name) ?: return@forEach
            if (!source.exists()) return@forEach
            val target = archivedFileFor(name) ?: return@forEach
            if (writeArchived(target, source.readText(), now)) {
                source.delete()
                moved++
            }
        }
        if (moved == 0) {
            throw AgentMemoryException("MEMORY_ARCHIVE_MISSING", "选中的档案已不存在，请刷新后重试")
        }
        moved
    }

    /** 整体替换全部档案（备份恢复用）：先清空目录再写回，并重置用量记录。 */
    fun replaceAllFiles(files: Map<String, String>): Int = synchronized(lock) {
        filesDir.mkdirs()
        filesDir.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
        var written = 0
        files.forEach { (name, content) ->
            val file = fileFor(name) ?: return@forEach
            file.writeText(if (content.isBlank()) "" else content.trimEnd('\n') + "\n")
            recordFileWritten(name)
            written++
        }
        writeUsageLocked(AgentMemoryUsage())
        written
    }

    /** 核心记忆快照：用户画像（USER.md）+ 工作记忆（MEMORY.md）合并后的文本，用于注入与展示。 */
    fun coreSnapshot(): AgentMemorySnapshot = synchronized(lock) { coreSnapshotLocked() }

    private fun coreSnapshotLocked(): AgentMemorySnapshot = snapshotOf(coreTextLocked())

    private fun coreTextLocked(): String {
        val profile = readText(userFile).trimEnd('\n')
        val working = readText(workingFile).trimEnd('\n')
        return buildString {
            // USER.md 自身通常已带这个标题；无条件再拼一次会让注入和记忆页出现两个「## 用户画像」。
            if (!profile.startsWith(PROFILE_HEADING)) append(PROFILE_HEADING).append('\n')
            if (profile.isNotEmpty()) append(profile).append('\n')
            append('\n').append(WORKING_HEADING).append('\n')
            if (working.isNotEmpty()) append(working)
        }.trimEnd('\n')
    }

    /**
     * 首次使用时把 MEMORY.md 里的 `# 核心记忆` 节搬到独立文件：
     * 有内容就写进 CORE.md 并把该节从 MEMORY.md 删掉，返回是否发生了迁移。
     */
    fun migrateCoreIfNeeded(): Boolean = synchronized(lock) {
        if (readText(coreFile).isNotBlank()) return@synchronized false
        val snapshot = snapshotLocked()
        val core = AgentMemoryContextBuilder.extractCore(snapshot.content)
        if (core.isBlank()) return@synchronized false
        val body = core.split('\n').drop(1).joinToString("\n").trim('\n')
        writeText(coreFile, if (body.isBlank()) "" else "$body\n")
        val remaining = removeCoreSection(snapshot.content)
        writeLocked(remaining)
        syncUsageLocked(snapshotOf(remaining))
        true
    }

    /** 删掉 `# 核心记忆` 整节（含标题行），其余内容保持原样。 */
    private fun removeCoreSection(content: String): String {
        if (content.isEmpty()) return content
        val lines = content.split('\n')
        val start = lines.indexOfFirst { it.trim() == CORE_HEADING_LINE }
        if (start < 0) return content
        val end = ((start + 1) until lines.size).firstOrNull { lines[it].startsWith("# ") } ?: lines.size
        return lines.filterIndexed { index, _ -> index < start || index >= end }
            .joinToString("\n")
            .trim('\n')
    }

    /** 归档清单：memory/archive/ 下的独立档案文件。 */
    fun archiveSections(): List<AgentMemorySection> = synchronized(lock) {
        val usage = usageLocked().sections
        archivedFilesLocked().map { file ->
            val lines = file.readText().memoryLines()
            val name = file.name.removeSuffix(FILE_SUFFIX)
            val entry = usage[name]
            AgentMemorySection(
                title = name,
                level = 2,
                startLine = 0,
                endLine = lines.size,
                lineCount = lines.size,
                hitCount = entry?.hitCount ?: 0,
                lastHitAtMillis = entry?.lastHitAtMillis ?: 0L,
                writtenAtMillis = archivedAt(file),
                preview = lines.firstOrNull { it.isNotBlank() }?.trim()?.take(PREVIEW_CHARS).orEmpty(),
            )
        }
    }

    /** 取消归档：把 memory/archive/<名字>.md 移回 memory/files/。 */
    fun unarchive(titles: List<String>): Int = synchronized(lock) {
        if (titles.isEmpty()) {
            throw AgentMemoryException("MEMORY_ARCHIVE_EMPTY", "没有选中要取消归档的档案")
        }
        val names = titles.map { title ->
            AgentMemoryScoring.stripArchiveStamp(title).trimStart('#').trim()
        }.filter { it.isNotBlank() }
        unarchiveLocked(names)
    }

    /** 逐个把归档档案移回档案目录；一个都没命中时报 MEMORY_ARCHIVE_MISSING。 */
    private fun unarchiveLocked(names: List<String>): Int {
        filesDir.mkdirs()
        var restored = 0
        names.forEach { name ->
            val source = archivedFileFor(name) ?: return@forEach
            if (!source.exists()) return@forEach
            val target = fileFor(name) ?: return@forEach
            val content = source.readText().trimEnd('\n')
            target.writeText(if (content.isBlank()) "" else "$content\n")
            runCatching { target.setLastModified(System.currentTimeMillis()) }
            source.delete()
            recordFileWritten(name)
            restored++
        }
        if (restored == 0) {
            throw AgentMemoryException("MEMORY_ARCHIVE_MISSING", "选中的档案已不在归档里，请刷新后重试")
        }
        return restored
    }

    /** 读取某一节的完整文本（含标题行），供记忆页点击展开。 */
    fun sectionText(title: String): String = synchronized(lock) {
        val lines = snapshotLocked().content.memoryLines()
        val range = AgentMemoryScoring.sections(lines)
            .firstOrNull { it.title == title || it.displayTitle == title }
            ?: return@synchronized ""
        lines.subList(range.startIndex, range.endIndex).joinToString("\n")
    }

    /** 备份恢复时整体写回归档目录（按 `## 名字（归档于 …）` 拆成一档一文件）；空内容即清空归档。 */
    fun replaceArchive(content: String): AgentMemoryArchiveSnapshot = synchronized(lock) {
        archiveDir.mkdirs()
        archiveDir.listFiles()?.forEach { file -> if (file.isFile) file.delete() }
        val now = System.currentTimeMillis()
        splitArchiveSections(content).forEach { entry ->
            val target = archivedFileFor(entry.name) ?: return@forEach
            writeArchived(target, entry.body, entry.archivedAtMillis.takeIf { it > 0L } ?: now)
        }
        archiveSnapshotLocked()
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
            next[title] = when {
                previous == null -> AgentMemoryUsageEntry(writtenAtMillis = now)
                // 只在首次获知时补写入时间；不能用「最后一次写入文件」刷新已有章节，
                // 否则长期未被引用的老章节永远等不到归档提示。
                previous.writtenAtMillis <= 0L -> previous.copy(writtenAtMillis = now)
                else -> previous
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




    companion object {
        const val MAX_FILE_BYTES = 1024 * 1024

        /** 单个档案文件的上限；归档文件不限制。 */
        const val FILE_MAX_BYTES = 10 * 1024 * 1024
        const val DEFAULT_READ_CHARS = 12_000
        const val MAX_READ_CHARS = 32_000
        const val MIN_READ_CHARS = 1
        const val MAX_WRITE_CONTENT_CHARS = 3_500
        /** 单条工作记忆条目的字符上限：条目要能一眼扫完，长内容进档案。 */
        const val MAX_ENTRY_CHARS = 500
        const val DEFAULT_COLD_IDLE_DAYS = 60L
        const val DEFAULT_NEVER_USED_GRACE_DAYS = 30L
        const val PREVIEW_CHARS = 120
        private const val DIRECTORY_NAME = "memory"
        private const val FILE_NAME = "MEMORY.md"
        /** 归档目录名：一个归档档案一个文件。 */
        private const val ARCHIVE_DIR_NAME = "archive"

        /** 旧版单文件归档名；现在只在迁移时读。 */
        private const val ARCHIVE_FILE_NAME = "MEMORY-archive.md"

        private const val LEGACY_CORE_FILE_NAME = "CORE.md"
        private const val USER_FILE_NAME = "USER.md"
        private const val CORE_HEADING_LINE = "# 核心记忆"
        private const val FILES_DIR_NAME = "files"
        private const val FILE_SUFFIX = ".md"
        private const val PROFILE_HEADING = "## 用户画像"
        private const val WORKING_HEADING = "## 工作记忆"
        const val PROFILE_MAX_BYTES = 5 * 1024
        /** 工作记忆上限：记忆页标红并手动压缩；模型追加条目超过它会被拒绝。 */
        const val WORKING_MAX_BYTES = 10 * 1024

        /** 工作记忆硬上限：压缩失败时也拒绝写入，避免常驻注入被撑爆。 */
        const val WORKING_HARD_MAX_BYTES = 64 * 1024
        private const val USAGE_FILE_NAME = "usage.json"
        /** 工作记忆条目的统计 sidecar。 */
        private const val ENTRY_FILE_NAME = "working.json"
        private const val FIELD_ENTRIES = "entries"
        private const val FIELD_ENTRIES_MIGRATED = "entries_migrated"
        private const val ARCHIVE_HEADING = "## 档案"
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

        /** 旧格式归档标题：`## 名字（归档于 2026-10-09）`；只认这种带戳的标题。 */
        private val ARCHIVE_STAMP_LINE = Regex("^(#{2,6})\\s+(.*?)（归档于 ([^）]*)）$")

    }
}

internal object AgentMemoryRepository {
    @Volatile
    private lateinit var store: AgentMemoryStore

    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = AgentMemoryStore(context.applicationContext.filesDir)
            // 首次运行把 MEMORY.md 里的「# 核心记忆」节搬到独立文件；失败不影响启动。
            runCatching { store.migrateCoreIfNeeded() }
            // 再把每个 `##` 章节拆成独立档案，并把清单写进「工作记忆」。
            runCatching { store.migrateFilesIfNeeded() }
            // 最后把 CORE.md（画像 + 工作记忆）拆成 USER.md 与 MEMORY.md，两者每轮注入。
            runCatching { store.migrateCoreFilesIfNeeded() }
            // 工作记忆改为「条目池」后，清掉旧版逐条列档案的清单行（只做一次）。
            runCatching { store.migrateWorkingEntriesIfNeeded() }
            // 旧版单文件归档 MEMORY-archive.md 拆成 memory/archive/ 下的一档一文件。
            runCatching { store.migrateArchiveIfNeeded() }

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

    fun restoreArchive(): Int {
        ensureInitialized()
        return store.restoreArchive()
    }

    fun archiveFiles(names: List<String>): Int {
        ensureInitialized()
        return store.archiveFiles(names)
    }

    /** 档案名校验：与内部写出同一套规则，非法返回 null。 */
    fun safeFileName(name: String): String? {
        ensureInitialized()
        return store.safeFileName(name)
    }

    fun files(): List<AgentMemoryFileInfo> {
        ensureInitialized()
        return store.files()
    }

    fun searchFiles(query: String, maxFiles: Int = 5, maxLinesPerFile: Int = 6): List<AgentMemoryFileHit> {
        ensureInitialized()
        return store.searchFiles(query, maxFiles, maxLinesPerFile)
    }

    fun fileIndexText(): String {
        ensureInitialized()
        return store.fileIndexText()
    }

    fun fileText(name: String): String {
        ensureInitialized()
        return store.fileText(name)
    }

    fun writeFile(name: String, content: String, append: Boolean): AgentMemoryFileInfo {
        ensureInitialized()
        return store.writeFile(name, content, append)
    }

    /** 唯一匹配替换（mode=edit）：old_text 必须恰好出现一次。 */
    fun editFile(name: String, oldText: String, content: String): AgentMemoryFileInfo {
        ensureInitialized()
        return store.editFile(name, oldText, content)
    }

    fun coreProfile(): String {
        ensureInitialized()
        return store.coreProfile()
    }

    /** 被预算挤掉、这一轮没有注入的条目（压缩整理的候选清单）。 */
    fun coreSkippedEntries(budgetChars: Int): List<AgentMemoryEntry> {
        ensureInitialized()
        return store.coreSkippedEntries(budgetChars)
    }

    fun coreWorking(): String {
        ensureInitialized()
        return store.coreWorking()
    }

    fun replaceCoreProfile(text: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.replaceCoreProfile(text)
    }

    fun replaceCoreWorking(text: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.replaceCoreWorking(text)
    }

    fun migrateFilesIfNeeded(): Boolean {
        ensureInitialized()
        return store.migrateFilesIfNeeded()
    }

    fun replaceAllFiles(files: Map<String, String>): Int {
        ensureInitialized()
        return store.replaceAllFiles(files)
    }

    fun coreSnapshot(): AgentMemorySnapshot {
        ensureInitialized()
        return store.coreSnapshot()
    }

    /** 兼容旧备份：`## 用户画像` + `## 工作记忆` 两节文本，分别写回两个文件。 */
    fun replaceCoreLegacy(content: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.replaceCoreLegacy(content)
    }

    fun migrateCoreFilesIfNeeded(): Boolean {
        ensureInitialized()
        return store.migrateCoreFilesIfNeeded()
    }

    /** 工作记忆条目（按有效重要度降序）。 */
    fun entries(): List<AgentMemoryEntry> {
        ensureInitialized()
        return store.entries()
    }

    fun searchEntries(query: String, maxEntries: Int = 5): List<AgentMemoryEntryHit> {
        ensureInitialized()
        return store.searchEntries(query, maxEntries)
    }

    fun writeEntry(content: String, importance: Int?, match: String?): AgentMemorySnapshot {
        ensureInitialized()
        return store.writeEntry(content, importance, match)
    }

    fun recordEntryHit(text: String) {
        ensureInitialized()
        store.recordEntryHit(text)
    }

    /** 注入投影：画像 + 按 EI 排序的活跃条目 + 一行档案统计。 */
    fun coreProjection(budgetChars: Int): String {
        ensureInitialized()
        return store.coreProjection(budgetChars)
    }

    /** 核心记忆容量占用（与 coreProjection 同一份预算算法）。 */
    fun coreUsage(budgetChars: Int): AgentMemoryCoreUsage {
        ensureInitialized()
        return store.coreUsage(budgetChars)
    }

    fun migrateCoreIfNeeded(): Boolean {
        ensureInitialized()
        return store.migrateCoreIfNeeded()
    }

    fun archiveSections(): List<AgentMemorySection> {
        ensureInitialized()
        return store.archiveSections()
    }

    fun unarchive(titles: List<String>): Int {
        ensureInitialized()
        return store.unarchive(titles)
    }

    fun deleteFile(name: String): Boolean {
        ensureInitialized()
        return store.deleteFile(name)
    }

    fun syncWorkingIndex(removed: List<String>, added: String?): AgentMemorySnapshot {
        ensureInitialized()
        return store.syncWorkingIndex(removed, added)
    }

    fun recordFileHit(name: String) {
        ensureInitialized()
        store.recordFileHit(name)
    }

    fun sectionText(title: String): String {
        ensureInitialized()
        return store.sectionText(title)
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
