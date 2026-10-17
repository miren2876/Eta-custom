package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.repository.AgentMemoryStore

/** 记忆页展示用的章节信息（含引用统计）。 */
@Immutable
data class AgentMemorySectionUi(
    /** 显示用标题，已去掉 `#` 前缀。 */
    val title: String,
    /** 操作（如归档）用的原始标题行，含 `#` 前缀。 */
    val rawTitle: String = "",
    val lines: Int,
    val hitCount: Int,
    /** 距离最近一次引用/写入的天数；null 表示从未引用。 */
    val idleDays: Int?,
    val cold: Boolean,
    val preview: String = "",
)

@Immutable
data class AgentMemoryFileUi(
    val name: String,
    val lineCount: Int,
    val byteSize: Int,
    val hitCount: Int = 0,
    /** 距离最近一次引用（或写入）的天数。 */
    val idleDays: Int? = null,
    val cold: Boolean = false,
)

/** 工作记忆条目：一行 `- (重要度) 内容`，附 sidecar 统计与 EI。 */
@Immutable
data class AgentMemoryEntryUi(
    val text: String,
    val importance: Int,
    val hitCount: Int = 0,
    /** 距离最近一次使用（命中或写入）的天数；没有记录为 null。 */
    val idleDays: Int? = null,
    /** 有效重要度（EI），保留两位小数。 */
    val eiLabel: String,
)

/** 整理档案的预览：模型返回的正文与前后统计，用户确认后才写回。 */
@Immutable
data class AgentMemoryTidyPreview(
    val name: String,
    val before: String,
    val after: String,
) {
    val beforeBytes: Int get() = before.toByteArray(Charsets.UTF_8).size
    val afterBytes: Int get() = after.toByteArray(Charsets.UTF_8).size
    val beforeLines: Int get() = if (before.isEmpty()) 0 else before.split('\n').size
    val afterLines: Int get() = if (after.isEmpty()) 0 else after.split('\n').size
}

@Immutable
data class AgentMemoryUiState(
    val enabled: Boolean = true,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isArchiving: Boolean = false,
    val draft: String = "",
    val savedContent: String = "",
    val draftBytes: Int = 0,
    val maxBytes: Int = AgentMemoryStore.MAX_FILE_BYTES,
    val coreBudgetChars: Int = 8_000,
    val coreBytes: Int = 0,
    /** 核心记忆：用户画像与工作记忆各自的草稿 / 已保存内容。 */
    val profileDraft: String = "",
    val profileSaved: String = "",
    val workingDraft: String = "",
    val workingSaved: String = "",
    val isSavingCore: Boolean = false,
    /** 工作记忆条目（按 EI 降序）。 */
    val entries: List<AgentMemoryEntryUi> = emptyList(),
    /** 记忆档案清单（memory/files/）。 */
    val files: List<AgentMemoryFileUi> = emptyList(),
    val expandedFileName: String? = null,
    val expandedFileText: String = "",
    /** 整理中的档案：算完先给预览，用户确认后才覆盖。 */
    val isTidying: Boolean = false,
    val tidyPreview: AgentMemoryTidyPreview? = null,
    val fileDraft: String = "",
    val fileSaved: String = "",
    val isSavingFile: Boolean = false,
    val isMerging: Boolean = false,
    val sections: List<AgentMemorySectionUi> = emptyList(),
    val archiveCandidates: List<AgentMemorySectionUi> = emptyList(),
    val archiveBytes: Int = 0,
    val archiveSections: Int = 0,
    val archiveList: List<AgentMemorySectionUi> = emptyList(),
    /** 刚归档的档案名，用于「撤销归档」的一次性入口；离开记忆页即清空。 */
    val lastArchivedTitles: List<String> = emptyList(),
    /** 当前展开的章节（原始标题行）；null 表示没有展开。 */
    val expandedSectionTitle: String? = null,
    val expandedSectionText: String = "",
    val notice: String? = null,
) {
    val hasUnsavedChanges: Boolean get() = draft != savedContent
    val canSave: Boolean
        get() = !isLoading && !isSaving && hasUnsavedChanges &&
            draftBytes <= maxBytes
    val coldSectionCount: Int get() = sections.count { it.cold }
    val coldFileCount: Int get() = files.count { it.cold }
    val coldFiles: List<AgentMemoryFileUi> get() = files.filter { it.cold }
}
