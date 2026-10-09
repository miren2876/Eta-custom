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
    val sections: List<AgentMemorySectionUi> = emptyList(),
    val archiveCandidates: List<AgentMemorySectionUi> = emptyList(),
    val archiveBytes: Int = 0,
    val archiveSections: Int = 0,
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
