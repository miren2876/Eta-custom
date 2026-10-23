package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi

/** 工作过程卡片内的一个时间线节点：单条消息，或连续调用同一工具形成的分组。 */
@Immutable
internal sealed interface AgentWorkStep {
    val key: String

    data class Single(val message: AgentChatMessageUi) : AgentWorkStep {
        override val key: String = message.id
    }

    data class ToolGroup(
        val toolName: String,
        val calls: List<ToolActivityMessageUi>,
    ) : AgentWorkStep {
        override val key: String = "group-${calls.first().id}"
        val running: Boolean get() = calls.any { it.status == ToolActivityStatusUi.Running }
        val failedCount: Int get() = calls.count { it.status == ToolActivityStatusUi.Failed }
    }

    /** 技能读取：同一轮同一技能的正文与资源读取合成一条，后来的调用只更新这条节点。 */
    data class Skill(
        val title: String,
        val calls: List<ToolActivityMessageUi>,
    ) : AgentWorkStep {
        override val key: String = "skill-${calls.first().id}"
        val running: Boolean get() = calls.any { it.status == ToolActivityStatusUi.Running }
        val failure: ToolActivityMessageUi? get() = calls.firstOrNull { it.status == ToolActivityStatusUi.Failed }
        val resourceCount: Int get() = calls.count { it.toolName == SKILL_RESOURCE_TOOL }
    }
}

internal fun ToolActivityMessageUi.isSkillRead(): Boolean = toolName in SKILL_READ_TOOLS

/** 技能读取的标题：formatter 写成「技能 · <id>」；摘要为空时由节点侧退回工具名。 */
internal fun ToolActivityMessageUi.skillTitle(): String = argumentsSummary

/**
 * Agent 常连续读取多个文件或多次观察屏幕；同名工具连续出现时合并为一个节点，
 * 时间线只保留“做了什么”的层级，细节按需展开。浏览器条目携带实时预览，保持独立。
 */
internal fun List<AgentChatMessageUi>.toWorkSteps(): List<AgentWorkStep> = buildList {
    val run = mutableListOf<ToolActivityMessageUi>()
    // 同一轮同一技能只占一条：记下它在列表里的位置，后续调用就地更新。
    val skills = mutableMapOf<String, Int>()

    fun flushRun() {
        when (run.size) {
            0 -> Unit
            1 -> add(AgentWorkStep.Single(run.single()))
            else -> add(AgentWorkStep.ToolGroup(toolName = run.first().toolName, calls = run.toList()))
        }
        run.clear()
    }

    this@toWorkSteps.forEach { message ->
        if (message is ToolActivityMessageUi && message.isSkillRead()) {
            flushRun()
            val title = message.skillTitle()
            val existing = skills[title]
            if (existing == null) {
                skills[title] = size
                add(AgentWorkStep.Skill(title = title, calls = listOf(message)))
            } else {
                val step = get(existing) as AgentWorkStep.Skill
                set(existing, step.copy(calls = step.calls + message))
            }
            return@forEach
        }
        val groupable = message is ToolActivityMessageUi && message.toolName !in UNGROUPED_TOOLS
        if (groupable && run.isNotEmpty() && run.first().toolName != (message as ToolActivityMessageUi).toolName) {
            flushRun()
        }
        if (groupable) {
            run += message as ToolActivityMessageUi
        } else {
            flushRun()
            add(AgentWorkStep.Single(message))
        }
    }
    flushRun()
}

private val UNGROUPED_TOOLS = setOf("browser_use")
private val SKILL_READ_TOOLS = setOf("skills_read", SKILL_RESOURCE_TOOL)
private const val SKILL_RESOURCE_TOOL = "skills_read_resource"

/** 折叠后的工作过程摘要所需计数。 */
internal data class AgentWorkSummary(
    val thinkingCount: Int,
    val toolCount: Int,
    val failedCount: Int,
)

internal fun List<AgentChatMessageUi>.workSummary(): AgentWorkSummary = AgentWorkSummary(
    thinkingCount = count { it is ThinkingMessageUi },
    toolCount = count { it is ToolActivityMessageUi },
    failedCount = count { it is ToolActivityMessageUi && it.status == ToolActivityStatusUi.Failed },
)

/**
 * 折叠行下方的一行结果。成功的常态结果（如“完成”）不占行；终端输出取最后一行，
 * 因为命令的结论通常在末尾；失败去掉重复的“失败”前缀与日志用的 code= 尾巴。
 */
internal fun ToolActivityMessageUi.outcomeLine(): String? {
    val summary = resultSummary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lines = summary.lines().map(String::trim).filter(String::isNotEmpty)
    val head = lines.firstOrNull() ?: return null
    return when (status) {
        ToolActivityStatusUi.Running -> null
        ToolActivityStatusUi.Failed, ToolActivityStatusUi.Unknown -> {
            val detail = lines.drop(1).lastOrNull { it != TRUNCATION_MARK }
            head.removePrefix(FAILURE_PREFIX)
                .substringBefore(FAILURE_CODE_SEPARATOR)
                .takeIf { it.isNotBlank() && it != FAILURE_LABEL }
                ?: detail
        }
        ToolActivityStatusUi.Success -> {
            val output = lines.drop(1).lastOrNull { it != TRUNCATION_MARK }
            when {
                output != null -> output
                head in TRIVIAL_SUCCESS -> null
                else -> head.removePrefix(SUCCESS_PREFIX).takeIf { it.isNotBlank() }
            }
        }
    }
}

// 与 Runtime 侧结果摘要的固定格式对应。
private const val FAILURE_LABEL = "失败"
private const val FAILURE_PREFIX = "失败 · "
private const val FAILURE_CODE_SEPARATOR = " · code="
private const val SUCCESS_PREFIX = "完成 · "
private const val TRUNCATION_MARK = "…"
private val TRIVIAL_SUCCESS = setOf("完成", "执行完成")
