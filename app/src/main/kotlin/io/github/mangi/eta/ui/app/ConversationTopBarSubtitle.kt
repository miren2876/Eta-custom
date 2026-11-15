package io.github.mangi.eta.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi

/**
 * 首页顶栏副标题：运行中显示当前动作（正在执行 · 工具 / 思考中 / 正在执行），
 * 空闲时显示消息数与最近更新时间。空会话返回空串：顶栏只显示一行，副标题也不挂点击。
 */
@Composable
internal fun conversationTopBarSubtitle(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean,
    timeLabel: String,
): String {
    val runningTool = messages.lastOrNull { message ->
        message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running
    } as? ToolActivityMessageUi
    if (runningTool != null) {
        return stringResource(R.string.top_bar_status_running_tool, toolDisplayName(runningTool.toolName))
    }
    if (messages.any { it is ThinkingMessageUi && it.isStreaming }) {
        return stringResource(R.string.top_bar_status_thinking)
    }
    if (isStreaming) return stringResource(R.string.top_bar_status_running)
    val messageCount = messages.count { it is UserMessageUi || it is AgentMessageUi }
    if (messageCount == 0) return ""
    return stringResource(R.string.top_bar_status_idle, messageCount, timeLabel)
}
