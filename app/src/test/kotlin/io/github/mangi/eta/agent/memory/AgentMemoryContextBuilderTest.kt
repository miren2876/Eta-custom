package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemoryFileInfo
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryContextBuilderTest {
    @Test
    fun coreBudgetTracksWindowWithSafeUnknownFallback() {
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(null))
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(128_000))
        // 256 000 / 16 = 16 000，但常驻预算上限是 15 000 字符。
        assertEquals(15_000, AgentMemoryContextBuilder.coreBudgetChars(256_000))
        assertEquals(15_000, AgentMemoryContextBuilder.coreBudgetChars(1_000_000))
        assertEquals(4_000, AgentMemoryContextBuilder.coreBudgetChars(16_000))
    }

    @Test
    fun injectedCoreIsTheProfilePlusWorkingMemoryAndIndexListsFiles() {
        val core = "## 用户画像\n长期偏好\n\n## 工作记忆\n- 项目"
        val now = 1_700_000_000_000L
        val dayMillis = 24L * 60 * 60 * 1000
        val files = listOf(
            AgentMemoryFileInfo(
                name = "项目",
                lineCount = 12,
                byteSize = 480,
                hitCount = 3,
                lastHitAtMillis = now - 2 * dayMillis,
                writtenAtMillis = now - 9 * dayMillis,
            ),
            AgentMemoryFileInfo(
                name = "旧事",
                lineCount = 5,
                byteSize = 200,
                writtenAtMillis = now - 200 * dayMillis,
                cold = true,
            ),
        )

        val context = AgentMemoryContextBuilder.build(
            snapshot = snapshot("被忽略的文件正文"),
            contextWindow = 128_000,
            core = core,
            files = files,
            nowMillis = now,
        )

        assertEquals(core, context.coreContent)
        assertFalse(context.coreTruncated)
        assertTrue(context.headingIndex.contains("- 项目（12 行 / 480 字节 · 引用 3 次 · 2 天前）"))
        assertTrue(context.headingIndex.contains("## 冷档案（1 个"))
        assertTrue(context.headingIndex.contains("- 旧事（未用 200 天）"))
    }

    @Test
    fun oversizedCoreIsTruncatedWithoutDroppingRevision() {
        val core = "## 用户画像\n" + "a".repeat(10_000)
        val snapshot = snapshot(core)
        val context = AgentMemoryContextBuilder.build(snapshot, null, core = core)

        assertEquals(8_000, context.coreContent.length)
        assertTrue(context.coreTruncated)
        assertEquals(snapshot.revision, context.revision)
    }

    /** 角色记忆没有核心文件，退回单文件的章节索引。 */
    @Test
    fun characterMemoryWithoutCoreFallsBackToHeadingIndex() {
        val context = AgentMemoryContextBuilder.build(
            snapshot("# 剧情\n只应按需读取的细节"),
            256_000,
        )

        assertEquals("", context.coreContent)
        assertEquals("# 剧情", context.headingIndex)
    }

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
