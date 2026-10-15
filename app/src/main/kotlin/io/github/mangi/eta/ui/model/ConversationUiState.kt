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
    /** 「已归档」入口：为 true 时只显示归档会话，且忽略文件夹分组。 */
    val showingArchived: Boolean = false,
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
internal fun List<ConversationSummaryUi>.filterForFolder(
    selectedFolderId: String?,
    showingArchived: Boolean = false,
): List<ConversationSummaryUi> = filter { summary ->
    if (summary.isArchived != showingArchived) return@filter false
    // 归档视图同样只看当前文件夹：每个文件夹底部各有自己的归档入口。
    if (selectedFolderId == null) summary.folderId.isBlank() else summary.folderId == selectedFolderId
}

/** 聊天页顶栏标题的最大显示字数（含结尾省略号）。 */
internal const val CONVERSATION_TITLE_MAX_CHARS = 12

/**
 * 顶栏显示的会话标题：超长时截断并补省略号，保证标题栏高度和右侧按钮不被挤压。
 */
internal fun conversationTopBarTitle(
    title: String,
    maxChars: Int = CONVERSATION_TITLE_MAX_CHARS,
): String {
    val trimmed = title.trim()
    val limit = maxChars.coerceAtLeast(1)
    if (trimmed.length <= limit) return trimmed
    return trimmed.take(limit - 1) + "…"
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
    val characterAvatarPath: String? = null,
    /** 已归档：默认列表不显示，只在侧栏「已归档」入口里出现。 */
    val isArchived: Boolean = false,
)

@Immutable
enum class ConversationModeUi {
    Chat,
    PhoneAgent,
    Terminal,
    Automation,
}
