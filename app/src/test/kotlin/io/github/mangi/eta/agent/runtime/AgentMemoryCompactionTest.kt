package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.data.repository.AgentMemoryCoreUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `ratio 按预算计算`() {
        assertEquals(0.5f, usage(1_000, 6_500, 5, 5, 0).ratio, 0.01f)
    }

    @Test
    fun `压缩提示词允许删除条目与重写画像`() {
        val prompt = AgentMemoryCompactor.buildCompactionPrompt(usage(4_000, 12_000, 30, 35, 5))
        assertTrue(prompt.contains("允许删除条目"))
        assertTrue(prompt.contains("允许重写用户画像"))
        assertTrue(prompt.contains("80%"))
        assertTrue(prompt.contains("ask_user"))
    }

    @Test
    fun `压缩 run 前缀可识别`() {
        assertTrue(AgentMemoryCompactor.isCompactionRun("memory-compact:abc"))
        assertFalse(AgentMemoryCompactor.isCompactionRun("memory-review:abc"))
        assertFalse(AgentMemoryCompactor.isCompactionRun("run-1"))
    }
}
