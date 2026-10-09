package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemorySection
import io.github.mangi.eta.data.repository.AgentMemoryStore
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentMemorySectionUi
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                            coreDraft = bundle.core.content,
                            coreSaved = bundle.core.content,
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

    fun updateCoreDraft(content: String) {
        memoryState = memoryState.copy(coreDraft = content, notice = null)
    }

    /** 保存核心记忆（独立文件 CORE.md）。 */
    fun saveCore() {
        if (memoryState.isSavingCore) return
        val content = memoryState.coreDraft
        memoryState = memoryState.copy(isSavingCore = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceCore(content) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSavingCore = false,
                                coreSaved = snapshot.content,
                                coreBytes = snapshot.byteSize,
                                notice = appContext.getString(R.string.state_ui_memory_core_saved),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_core_save_failed") {
                            "Agent core memory save failed: type=${throwable.safeLogType()}"
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

    /** 清空核心记忆文件。 */
    fun clearCore() {
        if (memoryState.isSavingCore) return
        memoryState = memoryState.copy(isSavingCore = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceCore("") }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSavingCore = false,
                                coreDraft = snapshot.content,
                                coreSaved = snapshot.content,
                                coreBytes = snapshot.byteSize,
                                notice = appContext.getString(R.string.state_ui_memory_core_cleared),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_core_clear_failed") {
                            "Agent core memory clear failed: type=${throwable.safeLogType()}"
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

    /** 归档冷章节：正文移除、原文移入 MEMORY-archive.md，可整体恢复。 */
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

    /** 取消归档：把选中的归档章节移回 MEMORY.md 末尾。 */
    fun unarchiveSections(titles: List<String>) {
        if (titles.isEmpty() || memoryState.isArchiving) return
        memoryState = memoryState.copy(isArchiving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.unarchive(titles) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isArchiving = false,
                                draft = snapshot.content,
                                savedContent = snapshot.content,
                                draftBytes = snapshot.byteSize,
                                notice = appContext.getString(R.string.state_ui_memory_unarchived, titles.size),
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
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isArchiving = false,
                                draft = snapshot.content,
                                savedContent = snapshot.content,
                                draftBytes = snapshot.byteSize,
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
