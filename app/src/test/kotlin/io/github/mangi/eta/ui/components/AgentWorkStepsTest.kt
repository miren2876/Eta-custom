package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkStepsTest {

    @Test
    fun consecutiveSameToolCallsFormOneGroup() {
        val steps = listOf(
            tool("t1", "read_file"),
            tool("t2", "read_file"),
            tool("t3", "read_file"),
            tool("t4", "run_command"),
        ).toWorkSteps()

        assertEquals(2, steps.size)
        val group = steps[0] as AgentWorkStep.ToolGroup
        assertEquals(listOf("t1", "t2", "t3"), group.calls.map { it.id })
        assertTrue(steps[1] is AgentWorkStep.Single)
    }

    @Test
    fun thinkingBreaksToolGroup() {
        val steps = listOf(
            tool("t1", "read_file"),
            ThinkingMessageUi(id = "k1", content = "想", isStreaming = false),
            tool("t2", "read_file"),
        ).toWorkSteps()

        assertEquals(listOf("t1", "k1", "t2"), steps.map { it.key })
    }

    @Test
    fun browserCallsStayIndividual() {
        val steps = listOf(tool("b1", "browser_use"), tool("b2", "browser_use")).toWorkSteps()

        assertEquals(2, steps.size)
        assertTrue(steps.all { it is AgentWorkStep.Single })
    }

    @Test
    fun skillReadsMergeIntoOneNodePerSkill() {
        val steps = listOf(
            tool("t1", "read_file"),
            tool("t2", "read_file"),
            tool("s1", "skills_read", argumentsSummary = "技能 · ponytail"),
            tool("s2", "skills_read_resource", argumentsSummary = "技能 · ponytail"),
            tool("s3", "skills_read", argumentsSummary = "技能 · memory-keeper"),
            tool("l1", "skills_list", argumentsSummary = "查看技能列表"),
        ).toWorkSteps()

        // 同一技能的正文与资源读取合成一条；浏览技能仍是普通工具行。
        assertEquals(4, steps.size)
        assertEquals(listOf("t1", "t2"), (steps[0] as AgentWorkStep.ToolGroup).calls.map { it.id })
        val skill = steps[1] as AgentWorkStep.Skill
        assertEquals("技能 · ponytail", skill.title)
        assertEquals(listOf("s1", "s2"), skill.calls.map { it.id })
        assertEquals(1, skill.resourceCount)
        assertEquals("技能 · memory-keeper", (steps[2] as AgentWorkStep.Skill).title)
        assertEquals("l1", (steps[3] as AgentWorkStep.Single).message.id)
    }

    @Test
    fun groupKeyStaysStableWhileCallsAppend() {
        val first = listOf(tool("t1", "read_file"), tool("t2", "read_file")).toWorkSteps()
        val next = listOf(tool("t1", "read_file"), tool("t2", "read_file"), tool("t3", "read_file")).toWorkSteps()

        assertEquals(first.single().key, next.single().key)
    }

    @Test
    fun terminalOutcomeUsesLastOutputLine() {
        val message = tool("t1", "run_command", resultSummary = "执行完成\nline 1\nline 2\n…")

        assertEquals("line 2", message.outcomeLine())
    }

    @Test
    fun trivialSuccessHasNoOutcome() {
        assertNull(tool("t1", "observe_screen", resultSummary = "完成").outcomeLine())
        assertEquals("2 张图片", tool("t2", "observe_screen", resultSummary = "完成 · 2 张图片").outcomeLine())
    }

    @Test
    fun failureOutcomeStripsPrefixAndCode() {
        val message = tool(
            "t1",
            "tap",
            status = ToolActivityStatusUi.Failed,
            resultSummary = "失败 · 找不到元素 · code=E_NOT_FOUND",
        )

        assertEquals("找不到元素", message.outcomeLine())
    }

    @Test
    fun failedTerminalFallsBackToOutput() {
        val message = tool(
            "t1",
            "run_command",
            status = ToolActivityStatusUi.Failed,
            resultSummary = "失败 · 退出码 1\nno such file",
        )

        assertEquals("退出码 1", message.outcomeLine())
    }

    @Test
    fun runningToolHasNoOutcome() {
        assertNull(tool("t1", "read_file", status = ToolActivityStatusUi.Running, resultSummary = "x").outcomeLine())
    }

    private fun tool(
        id: String,
        name: String,
        status: ToolActivityStatusUi = ToolActivityStatusUi.Success,
        argumentsSummary: String = name,
        resultSummary: String? = null,
    ) = ToolActivityMessageUi(
        id = id,
        toolName = name,
        status = status,
        argumentsSummary = argumentsSummary,
        resultSummary = resultSummary,
    )
}
