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
)

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
    /** 记忆档案清单（memory/files/）。 */
    val files: List<AgentMemoryFileUi> = emptyList(),
    val expandedFileName: String? = null,
    val expandedFileText: String = "",
    val fileDraft: String = "",
    val fileSaved: String = "",
    val isSavingFile: Boolean = false,
    val sections: List<AgentMemorySectionUi> = emptyList(),
    val archiveCandidates: List<AgentMemorySectionUi> = emptyList(),
    val archiveBytes: Int = 0,
    val archiveSections: Int = 0,
    val archiveList: List<AgentMemorySectionUi> = emptyList(),
    /** 刚归档的章节（显示名），用于「撤销归档」的一次性入口；离开记忆页即清空。 */
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
}
