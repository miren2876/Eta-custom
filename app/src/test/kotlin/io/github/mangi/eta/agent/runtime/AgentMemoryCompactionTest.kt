package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.tool.AgentMemoryWritePolicy
import io.github.mangi.eta.agent.tool.memoryWriteDenied
import io.github.mangi.eta.data.repository.AgentMemoryCoreUsage
import io.github.mangi.eta.data.repository.workingGrowDenied
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryCompactionTest {

    private fun usage(
        profile: Int,
        entry: Int,
        count: Int,
        total: Int,
        skipped: Int,
        budget: Int = 15_000,
    ) = AgentMemoryCoreUsage(
        profileBytes = profile,
        profileChars = profile,
        entryBytes = entry,
        entryCount = count,
        totalEntryCount = total,
        skippedCount = skipped,
        budgetChars = budget,
    )

    @Test
    fun `有条目被挤掉就需要压缩`() {
        assertTrue(usage(1_000, 14_000, 10, 12, 2).needsCompaction)
    }

    @Test
    fun `已用超过九成需要压缩`() {
        // 14_000 / 15_000 ≈ 0.93
        assertTrue(usage(2_000, 12_000, 20, 20, 0).needsCompaction)
        // 11_000 / 15_000 ≈ 0.73
        assertFalse(usage(2_000, 9_000, 15, 15, 0).needsCompaction)
    }

    @Test
    fun `工作记忆文件超 10 KiB 也要压缩`() {
        // 投影只用了 60%，但文件本身已经超过 10 KiB：新增条目会被拒，必须压。
        val over = AgentMemoryCoreUsage(
            profileBytes = 800,
            profileChars = 400,
            entryBytes = 4_400,
            entryCount = 20,
            totalEntryCount = 20,
            skippedCount = 0,
            budgetChars = 8_000,
            workingBytes = 10_500,
        )
        assertTrue(over.workingOverLimit)
        assertTrue(over.needsCompaction)
        assertFalse(over.copy(workingBytes = 9_000).needsCompaction)
    }

    @Test
    fun `ratio 按预算计算`() {
        assertEquals(0.5f, usage(1_000, 6_500, 5, 5, 0).ratio, 0.01f)
    }

    @Test
    fun `skippedCount 由总条数与注入条数推出`() {
        val skipped = usage(0, 0, 3, 5, 2)
        assertEquals(2, skipped.skippedCount)
        assertEquals(5, skipped.totalEntryCount)
    }

    @Test
    fun `压缩提示词允许删除条目与重写画像`() {
        val prompt = AgentMemoryCompactor.buildCompactionPrompt(usage(4_000, 12_000, 30, 35, 5))
        assertTrue(prompt.contains("允许删除条目"))
        assertTrue(prompt.contains("允许重写用户画像"))
        assertTrue(prompt.contains("80%"))
        assertTrue(prompt.contains("ask_user"))
        // 后台 run 的提问上限写进提示词，模型要知道超时后不许再删。
        assertTrue(prompt.contains("90 秒"))
        assertTrue(prompt.contains("不要再删除任何条目"))
    }

    @Test
    fun `压缩提示词与空闲整理相反`() {
        val prompt = AgentMemoryCompactor.buildCompactionPrompt(usage(4_000, 12_000, 30, 35, 5))
        assertFalse(prompt.contains("不要删除任何东西"))
        assertTrue(prompt.contains("禁止 replace"))
    }

    @Test
    fun `压缩 run 前缀可识别`() {
        assertTrue(AgentMemoryCompactor.isCompactionRun("memory-compact:abc"))
        assertFalse(AgentMemoryCompactor.isCompactionRun("memory-review:abc"))
        assertFalse(AgentMemoryCompactor.isCompactionRun("run-1"))
    }

    @Test
    fun `工作记忆只拒绝变大且超限的写入`() {
        val limit = 10 * 1024
        assertTrue(workingGrowDenied(limit + 1, limit, limit))
        // 删除或改写变短永远放行，即使文件已经超限。
        assertFalse(workingGrowDenied(limit + 1, limit + 200, limit))
        // 没超限的增长照常写入。
        assertFalse(workingGrowDenied(limit, limit - 10, limit))
    }

    @Test
    fun `压缩提示词说明工作记忆硬上限`() {
        val prompt = AgentMemoryCompactor.buildCompactionPrompt(usage(4_000, 12_000, 30, 35, 5))
        assertTrue(prompt.contains("10 KiB"))
        assertTrue(prompt.contains("工作记忆条目"))
    }

    @Test
    fun `COMPACT 允许改画像与删条目但拒绝整份覆盖工作记忆`() {
        assertNull(memoryWriteDenied(AgentMemoryWritePolicy.COMPACT, "profile", archive = false))
        assertNull(memoryWriteDenied(AgentMemoryWritePolicy.COMPACT, "entry", archive = false))
        assertNull(memoryWriteDenied(AgentMemoryWritePolicy.COMPACT, null, archive = false))
        assertNull(memoryWriteDenied(AgentMemoryWritePolicy.COMPACT, null, archive = true))
        val denied = memoryWriteDenied(AgentMemoryWritePolicy.COMPACT, "working", archive = false)
        assertNotNull(denied)
        assertEquals("MEMORY_COMPACT_WORKING_DENIED", denied?.first)
    }

    @Test
    fun `REVIEW 仍然只允许写条目与档案`() {
        assertNull(memoryWriteDenied(AgentMemoryWritePolicy.REVIEW, "entry", archive = false))
        assertEquals(
            "MEMORY_REVIEW_TARGET_DENIED",
            memoryWriteDenied(AgentMemoryWritePolicy.REVIEW, "profile", archive = false)?.first,
        )
        assertEquals(
            "MEMORY_REVIEW_TARGET_DENIED",
            memoryWriteDenied(AgentMemoryWritePolicy.REVIEW, "working", archive = false)?.first,
        )
    }

    @Test
    fun `FULL 不受整理策略限制`() {
        assertNull(memoryWriteDenied(AgentMemoryWritePolicy.FULL, "working", archive = false))
        assertNull(memoryWriteDenied(AgentMemoryWritePolicy.FULL, "profile", archive = false))
    }
}
