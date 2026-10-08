package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class ConversationPaneUiState(
    val conversations: List<ConversationSummaryUi>,
    val selectedConversationId: String?,
    val searchQuery: String,
    val folders: List<ConversationFolderUi> = emptyList(),
    /** null 表示「聊天」分组（未归类会话）；否则只显示该文件夹内的会话。 */
    val selectedFolderId: String? = null,
    /** 全部会话（不受文件夹与搜索过滤），供管理聊天页使用。 */
    val historyConversations: List<ConversationSummaryUi> = emptyList(),
)

/** 侧栏会话文件夹；会话按 folderId 归属，未归类会话的 folderId 为空。 */
@Immutable
data class ConversationFolderUi(
    val id: String,
    val name: String,
    val sortIndex: Int = 0,
)

/** 「聊天」分组只显示未归类会话，文件夹分组只显示该文件夹的会话。 */
internal fun List<ConversationSummaryUi>.filterForFolder(selectedFolderId: String?): List<ConversationSummaryUi> =
    if (selectedFolderId == null) {
        filter { it.folderId.isBlank() }
    } else {
        filter { it.folderId == selectedFolderId }
    }

@Immutable
data class ConversationSummaryUi(
    val id: String,
    val title: String,
    val preview: String,
    val timeLabel: String,
    val updatedAtMillis: Long = 0L,
    val mode: ConversationModeUi,
    val isPinned: Boolean = false,
    val isActiveRun: Boolean = false,
    val characterName: String? = null,
    val folderId: String = "",
)

@Immutable
enum class ConversationModeUi {
    Chat,
    PhoneAgent,
    Terminal,
    Automation,
}
