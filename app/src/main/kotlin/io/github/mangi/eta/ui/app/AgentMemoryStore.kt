package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.memory.AgentMemoryModelTasks
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryScoring
import io.github.mangi.eta.data.repository.AgentMemorySection
import io.github.mangi.eta.data.repository.AgentMemoryStore
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentMemoryEntryUi
import io.github.mangi.eta.ui.model.AgentMemoryFileUi
import io.github.mangi.eta.ui.model.AgentMemorySectionUi
import io.github.mangi.eta.ui.model.AgentMemoryTidyPreview
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 记忆页的读取、编辑与保存状态；与会话和 Agent run 无共享状态。 */
internal class AgentMemoryStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    var memoryState by mutableStateOf(AgentMemoryUiState())
        private set

    fun refreshMemory() {
        memoryState = memoryState.copy(isLoading = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = AgentMemoryRepository.snapshot()
                val enabled = AgentMemoryRepository.isEnabled()
                val contextWindow = RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                val sections = AgentMemoryRepository.sections()
                val candidates = AgentMemoryRepository.archiveCandidates()
                val archivedSections = AgentMemoryRepository.archiveSections()
                val core = AgentMemoryRepository.coreSnapshot()
                val profile = AgentMemoryRepository.coreProfile()
                val working = AgentMemoryRepository.coreWorking()
                val entries = AgentMemoryRepository.entries()
                val files = AgentMemoryRepository.files()
                val archive = AgentMemoryRepository.archiveSnapshot()
                AgentMemorySnapshotBundle(
                    snapshot = snapshot,
                    enabled = enabled,
                    coreBudget = AgentMemoryContextBuilder.coreBudgetChars(contextWindow),
                    coreBytes = core.byteSize,
                    sections = sections,
                    candidates = candidates,
                    archivedSections = archivedSections,
                    core = core,
                    profile = profile,
                    working = working,
                    entries = entries,
                    files = files,
                    archive = archive,
                )
            }.fold(
                onSuccess = { bundle ->
                    val now = System.currentTimeMillis()
                    val coldTitles = bundle.candidates.map { it.title }.toSet()
                    withContext(Dispatchers.Main) {
                        memoryState = AgentMemoryUiState(
                            enabled = bundle.enabled,
                            isLoading = false,
                            draft = bundle.snapshot.content,
                            savedContent = bundle.snapshot.content,
                            draftBytes = bundle.snapshot.byteSize,
                            coreBudgetChars = bundle.coreBudget,
                            coreBytes = bundle.coreBytes,
                            profileDraft = bundle.profile,
                            profileSaved = bundle.profile,
                            workingDraft = bundle.working,
                            workingSaved = bundle.working,
                            entries = bundle.entries.map { entry ->
                                val reference = if (entry.lastHitAtMillis > 0L) {
                                    entry.lastHitAtMillis
                                } else {
                                    entry.writtenAtMillis
                                }
                                AgentMemoryEntryUi(
                                    text = entry.text,
                                    importance = entry.importance,
                                    hitCount = entry.hitCount,
                                    idleDays = if (reference > 0L) ((now - reference) / DAY_MILLIS).toInt() else null,
                                    eiLabel = String.format(Locale.US, "%.2f", entry.effectiveImportance),
                                )
                            },
                            files = bundle.files.map { file ->
                                val reference = if (file.lastHitAtMillis > 0L) {
                                    file.lastHitAtMillis
                                } else {
                                    file.writtenAtMillis
                                }
                                AgentMemoryFileUi(
                                    name = file.name,
                                    lineCount = file.lineCount,
                                    byteSize = file.byteSize,
                                    hitCount = file.hitCount,
                                    idleDays = ((now - reference) / DAY_MILLIS).toInt(),
                                    cold = file.cold,
                                )
                            },
                            sections = bundle.sections.map { it.toUi(now, coldTitles) },
                            archiveCandidates = bundle.candidates.map { it.toUi(now, coldTitles) },
                            archiveBytes = bundle.archive.byteSize,
                            archiveSections = bundle.archive.sectionCount,
                            archiveList = bundle.archivedSections.map { it.toUi(now, coldTitles) },
                            // 刷新时保留「撤销归档」入口，只有离开记忆页才清掉。
                            lastArchivedTitles = memoryState.lastArchivedTitles,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_load_failed") {
                        "Agent memory UI load failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isLoading = false,
                            notice = appContext.getString(R.string.state_ui_failed_to_read_memory_please_try_again_later_caeaa6),
                        )
                    }
                },
            )
        }
    }

    fun updateMemoryDraft(content: String) {
        memoryState = memoryState.copy(
            draft = content,
            draftBytes = content.toByteArray(Charsets.UTF_8).size,
            notice = null,
        )
    }

    fun updateProfileDraft(content: String) {
        memoryState = memoryState.copy(profileDraft = content, notice = null)
    }

    fun updateWorkingDraft(content: String) {
        memoryState = memoryState.copy(workingDraft = content, notice = null)
    }

    /** 保存用户画像（核心记忆第一节）。 */
    fun saveProfile() = writeCorePart(profile = true, content = memoryState.profileDraft)

    /** 保存工作记忆（核心记忆第二节）。 */
    fun saveWorking() = writeCorePart(profile = false, content = memoryState.workingDraft)

    fun clearProfile() = writeCorePart(profile = true, content = "")

    fun clearWorking() = writeCorePart(profile = false, content = "")

    private fun writeCorePart(profile: Boolean, content: String) {
        if (memoryState.isSavingCore) return
        memoryState = memoryState.copy(isSavingCore = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (profile) {
                    AgentMemoryRepository.replaceCoreProfile(content)
                } else {
                    AgentMemoryRepository.replaceCoreWorking(content)
                }
            }.fold(
                onSuccess = {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isSavingCore = false,
                            notice = appContext.getString(
                                if (content.isEmpty()) {
                                    R.string.state_ui_memory_core_cleared
                                } else {
                                    R.string.state_ui_memory_core_saved
                                },
                            ),
                        )
                    }
                    refreshMemory()
                    if (!profile) maybeCompressWorking()
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_core_write_failed") {
                        "Agent core memory write failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isSavingCore = false,
                            notice = throwable.message
                                ?: appContext.getString(R.string.state_ui_failed_to_read_memory_please_try_again_later_caeaa6),
                        )
                    }
                },
            )
        }
    }

    /** 展开档案时按需读取内容。 */
    fun expandFile(name: String) {
        if (name.isBlank()) return
        // 先落文件名，档案页立刻有标题；正文随后异步补上。
        if (memoryState.expandedFileName != name) {
            memoryState = memoryState.copy(expandedFileName = name)
        }
        scope.launch(Dispatchers.IO) {
            val text = runCatching { AgentMemoryRepository.fileText(name) }.getOrDefault("")
            withContext(Dispatchers.Main) {
                memoryState = memoryState.copy(
                    expandedFileName = name,
                    expandedFileText = text,
                    fileDraft = text,
                    fileSaved = text,
                )
            }
        }
    }

    fun collapseFile() {
        memoryState = memoryState.copy(expandedFileName = null, expandedFileText = "")
    }

    /**
     * 把选中的档案合并成一个：用当前模型合并正文，写回第一个档案名，
     * 其余档案文件删除，并同步「工作记忆」里的档案清单。
     */
    fun mergeFiles(names: List<String>) {
        if (names.size < 2 || memoryState.isMerging) return
        val target = names.first()
        val others = names.filter { it != target }
        memoryState = memoryState.copy(isMerging = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val blocks = names.map { name -> AgentMemoryRepository.fileText(name) }
                    .filter { it.isNotBlank() }
                val merged = AgentMemoryModelTasks().mergeFiles(blocks)
                AgentMemoryRepository.writeFile(target, merged, append = false)
                others.forEach { name -> AgentMemoryRepository.deleteFile(name) }
                AgentMemoryRepository.syncWorkingIndex(removed = others, added = target)
            }.fold(
                onSuccess = {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isMerging = false,
                            expandedFileName = null,
                            notice = appContext.getString(R.string.state_ui_memory_merged, names.size),
                        )
                    }
                    maybeCompressWorking()
                    refreshMemory()
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_merge_failed") {
                        "Agent memory merge failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isMerging = false,
                            notice = throwable.message
                                ?: appContext.getString(R.string.state_ui_memory_merge_failed),
                        )
                    }
                },
            )
        }
    }

    /** 工作记忆超过上限（10 KiB）时自动调用模型压缩；失败就保持原样并提示。 */
    private fun maybeCompressWorking() {
        scope.launch(Dispatchers.IO) {
            val working = runCatching { AgentMemoryRepository.coreWorking() }.getOrNull().orEmpty()
            val bytes = working.toByteArray(Charsets.UTF_8).size
            if (bytes <= AgentMemoryStore.WORKING_MAX_BYTES) return@launch
            if (memoryState.isSavingCore) return@launch
            val maxChars = (working.length.toLong() * AgentMemoryStore.WORKING_MAX_BYTES / bytes).toInt()
                .coerceAtLeast(200)
            runCatching {
                val compressed = AgentMemoryModelTasks().compressWorking(working, maxChars)
                AgentMemoryRepository.replaceCoreWorking(compressed)
            }.fold(
                onSuccess = {
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            notice = appContext.getString(R.string.state_ui_memory_working_compressed),
                        )
                    }
                    refreshMemory()
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_working_compress_failed") {
                        "Agent working memory compress failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            notice = throwable.message
                                ?: appContext.getString(R.string.state_ui_memory_working_compress_failed),
                        )
                    }
                },
            )
        }
    }

    /** 把选中的档案归档（移入 memory/archive/ 并删除档案文件）。 */
    fun archiveFiles(names: List<String>) {
        if (names.isEmpty() || memoryState.isArchiving) return
        memoryState = memoryState.copy(isArchiving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.archiveFiles(names) }
                .fold(
                    onSuccess = { count ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isArchiving = false,
                                expandedFileName = null,
                                lastArchivedTitles = names,
                                notice = appContext.getString(R.string.state_ui_memory_archived, count),
                            )
                        }
                        refreshMemory()
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_archive_file_failed") {
                            "Agent memory archive file failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isArchiving = false,
                                notice = throwable.message
                                    ?: appContext.getString(R.string.state_ui_memory_archive_failed),
                            )
                        }
                    },
                )
        }
    }

    fun updateFileDraft(content: String) {
        memoryState = memoryState.copy(fileDraft = content, notice = null)
    }

    /** 保存当前展开的档案。 */
    fun saveFile() {
        val name = memoryState.expandedFileName ?: return
        if (memoryState.isSavingFile) return
        val content = memoryState.fileDraft
        memoryState = memoryState.copy(isSavingFile = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.writeFile(name, content, append = false) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSavingFile = false,
                                fileSaved = content,
                                notice = appContext.getString(R.string.state_ui_memory_file_saved),
                            )
                        }
                        refreshMemory()
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_file_save_failed") {
                            "Agent memory file save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSavingFile = false,
                                notice = throwable.message
                                    ?: appContext.getString(R.string.state_ui_failed_to_read_memory_please_try_again_later_caeaa6),
                            )
                        }
                    },
                )
        }
    }

    /** 整理档案：先让模型算一份更紧凑的正文，结果只作为预览，确认后才覆盖。 */
    fun tidyFile() {
        val name = memoryState.expandedFileName ?: return
        if (memoryState.isTidying) return
        val content = memoryState.fileDraft
        if (content.isBlank()) return
        memoryState = memoryState.copy(isTidying = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryModelTasks().tidyFile(content) }
                .fold(
                    onSuccess = { tidy ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isTidying = false,
                                tidyPreview = AgentMemoryTidyPreview(name = name, before = content, after = tidy),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_tidy_failed") {
                            "Agent memory tidy failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isTidying = false,
                                notice = throwable.message
                                    ?: appContext.getString(R.string.state_ui_memory_tidy_failed),
                            )
                        }
                    },
                )
        }
    }

    /** 用户确认整理：把预览正文写回档案。 */
    fun confirmTidy() {
        val preview = memoryState.tidyPreview ?: return
        if (memoryState.isSavingFile) return
        memoryState = memoryState.copy(isSavingFile = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.writeFile(preview.name, preview.after, append = false) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSavingFile = false,
                                tidyPreview = null,
                                fileDraft = preview.after,
                                fileSaved = preview.after,
                                expandedFileText = preview.after,
                                notice = appContext.getString(R.string.state_ui_memory_tidy_done),
                            )
                        }
                        refreshMemory()
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_tidy_save_failed") {
                            "Agent memory tidy save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSavingFile = false,
                                notice = throwable.message
                                    ?: appContext.getString(R.string.state_ui_memory_tidy_failed),
                            )
                        }
                    },
                )
        }
    }

    fun discardTidy() {
        memoryState = memoryState.copy(tidyPreview = null)
    }

    /**
     * 拆分档案：选中的章节各写成一个新档案，原档案删掉这些节。
     * parts 是「新档案名 to 原始标题行」；先全部校验再写，避免留下半个结果。
     */
    fun splitFile(parts: List<Pair<String, String>>) {
        val name = memoryState.expandedFileName ?: return
        if (parts.isEmpty() || memoryState.isSavingFile) return
        // 章节列表是按当前草稿算的，这里也用草稿，避免未保存时找不到章节。
        val source = memoryState.fileDraft
        memoryState = memoryState.copy(isSavingFile = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val lines = if (source.isEmpty()) emptyList() else source.split('\n')
                val ranges = AgentMemoryScoring.sections(lines)
                val picked = parts.map { (newName, rawTitle) ->
                    val range = ranges.firstOrNull { it.title == rawTitle }
                        ?: throw IllegalStateException("找不到章节：$rawTitle")
                    val body = lines.subList(range.startIndex + 1, range.endIndex)
                        .joinToString("\n")
                        .trim('\n')
                    if (body.isBlank()) throw IllegalStateException("章节没有正文：$rawTitle")
                    Triple(newName, body, range)
                }
                val drop = picked.flatMap { (_, _, range) -> range.startIndex until range.endIndex }.toSet()
                val remaining = lines.filterIndexed { index, _ -> index !in drop }
                    .joinToString("\n")
                    .trim('\n')
                if (remaining.isBlank()) throw IllegalStateException("拆分后原档案会变空，已取消")
                picked.forEach { (newName, body, _) ->
                    AgentMemoryRepository.writeFile(newName, body, append = false)
                }
                AgentMemoryRepository.writeFile(name, remaining, append = false)
                AgentMemoryRepository.syncWorkingIndex(removed = emptyList(), added = picked.first().first)
                remaining
            }.fold(
                onSuccess = { remaining ->
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isSavingFile = false,
                            expandedFileText = remaining,
                            fileDraft = remaining,
                            fileSaved = remaining,
                            notice = appContext.getString(R.string.state_ui_memory_split_done, parts.size),
                        )
                    }
                    refreshMemory()
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_split_failed") {
                        "Agent memory split failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isSavingFile = false,
                            notice = throwable.message
                                ?: appContext.getString(R.string.state_ui_memory_split_failed),
                        )
                    }
                },
            )
        }
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.setEnabled(enabled) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(enabled = enabled, notice = null)
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_toggle_failed") {
                            "Agent memory setting update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(notice = appContext.getString(R.string.state_ui_memory_switch_failed_to_save_83b5d6))
                        }
                    },
                )
        }
    }

    fun saveMemory() {
        if (!memoryState.canSave) return
        val target = memoryState.draft
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll(target) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                savedContent = snapshot.content,
                                draft = if (memoryState.draft == target) {
                                    snapshot.content
                                } else {
                                    memoryState.draft
                                },
                                draftBytes = memoryState.draft.toByteArray(Charsets.UTF_8).size,
                                notice = appContext.getString(R.string.state_ui_memory_saved_a2c61c),
                            )
                        }
                        refreshMemory()
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_save_failed") {
                            "Agent memory UI save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_save_failed_1f501e),
                            )
                        }
                    },
                )
        }
    }

    fun clearMemory() {
        if (memoryState.isSaving) return
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll("") }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = "",
                                savedContent = "",
                                draftBytes = 0,
                                notice = appContext.getString(R.string.state_ui_memory_cleared_b415bb),
                            )
                        }
                        refreshMemory()
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_clear_failed") {
                            "Agent memory UI clear failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_clearing_failed_7f0aba),
                            )
                        }
                    },
                )
        }
    }

    /** 归档冷章节：原文移入 memory/archive/，可整体恢复。 */
    fun archiveSections(titles: List<String>) {
        if (titles.isEmpty() || memoryState.isArchiving) return
        memoryState = memoryState.copy(isArchiving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.archive(titles) }
                .fold(
                    onSuccess = { result ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isArchiving = false,
                                draft = result.snapshot.content,
                                savedContent = result.snapshot.content,
                                draftBytes = result.snapshot.byteSize,
                                lastArchivedTitles = result.archivedTitles,
                                notice = appContext.getString(
                                    R.string.state_ui_memory_archived,
                                    result.archivedTitles.size,
                                ),
                            )
                        }
                        refreshMemory()
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_archive_failed") {
                            "Agent memory archive failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isArchiving = false,
                                notice = throwable.message
                                    ?: appContext.getString(R.string.state_ui_memory_archive_failed),
                            )
                        }
                    },
                )
        }
    }

    /** 取消归档：把选中的归档档案移回 memory/files/。 */
    fun unarchiveSections(titles: List<String>) {
        if (titles.isEmpty() || memoryState.isArchiving) return
        memoryState = memoryState.copy(isArchiving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.unarchive(titles) }
                .fold(
                    onSuccess = { count ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isArchiving = false,
                                notice = appContext.getString(R.string.state_ui_memory_unarchived, count),
                            )
                        }
                        refreshMemory()
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_unarchive_failed") {
                            "Agent memory unarchive failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isArchiving = false,
                                notice = throwable.message
                                    ?: appContext.getString(R.string.state_ui_memory_archive_failed),
                            )
                        }
                    },
                )
        }
    }

    /** 撤销刚归档的章节：移回正文；这是一次性入口，点过就收起。 */
    fun undoArchive() {
        val titles = memoryState.lastArchivedTitles
        if (titles.isEmpty()) return
        unarchiveSections(titles)
        clearUndo()
    }

    /** 离开记忆页时收起撤销入口。 */
    fun clearUndo() {
        if (memoryState.lastArchivedTitles.isEmpty()) return
        memoryState = memoryState.copy(lastArchivedTitles = emptyList())
    }

    fun restoreArchive() {
        if (memoryState.isArchiving) return
        memoryState = memoryState.copy(isArchiving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.restoreArchive() }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isArchiving = false,
                                notice = appContext.getString(R.string.state_ui_memory_archive_restored),
                            )
                        }
                        refreshMemory()
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_restore_failed") {
                            "Agent memory archive restore failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isArchiving = false,
                                notice = throwable.message
                                    ?: appContext.getString(R.string.state_ui_memory_archive_restore_failed),
                            )
                        }
                    },
                )
        }
    }

    /** 点击章节行时按需读取该节正文（失败就给空文本，页面照常展开）。 */
    fun expandSection(title: String) {
        if (title.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val text = runCatching { AgentMemoryRepository.sectionText(title) }.getOrDefault("")
            withContext(Dispatchers.Main) {
                memoryState = memoryState.copy(expandedSectionTitle = title, expandedSectionText = text)
            }
        }
    }

    fun collapseSection() {
        memoryState = memoryState.copy(expandedSectionTitle = null, expandedSectionText = "")
    }

    fun dismissMemoryNotice() {
        memoryState = memoryState.copy(notice = null)
    }

    private data class AgentMemorySnapshotBundle(
        val snapshot: io.github.mangi.eta.data.repository.AgentMemorySnapshot,
        val enabled: Boolean,
        val coreBudget: Int,
        val coreBytes: Int,
        val sections: List<AgentMemorySection>,
        val candidates: List<AgentMemorySection>,
        val archivedSections: List<AgentMemorySection>,
        val core: io.github.mangi.eta.data.repository.AgentMemorySnapshot,
        val profile: String,
        val working: String,
        val entries: List<io.github.mangi.eta.data.repository.AgentMemoryEntry>,
        val files: List<io.github.mangi.eta.data.repository.AgentMemoryFileInfo>,
        val archive: io.github.mangi.eta.data.repository.AgentMemoryArchiveSnapshot,
    )

    private fun AgentMemorySection.toUi(nowMillis: Long, coldTitles: Set<String>): AgentMemorySectionUi {
        val lastTouched = maxOf(lastHitAtMillis, writtenAtMillis)
        val idleDays = if (lastTouched <= 0L) {
            null
        } else {
            ((nowMillis - lastTouched) / DAY_MILLIS).toInt()
        }
        return AgentMemorySectionUi(
            title = displayTitle,
            rawTitle = title,
            lines = lineCount,
            hitCount = hitCount,
            idleDays = idleDays,
            cold = title in coldTitles,
            preview = preview,
        )
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
