package io.github.mangi.eta.agent.terminal

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptLibraryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `脚本库保存后能原样读回`() {
        val library = ScriptLibrary(File(temporaryFolder.root, "scripts.json"))
        val script = ScriptLibrary.newScript("测试", "  sleep 300  ", TerminalEnvironment.DEBIAN, "")
        val saved = script.copy(lastTaskId = "dm_1", updatedAt = 42L, lastResult = "stopped", lastFinishedAt = 7L)

        assertTrue(library.save(listOf(saved)))
        assertEquals(listOf(saved), library.load())
        assertEquals("sleep 300", library.load().single().command)
        assertEquals("root", library.load().single().identity)
    }

    @Test
    fun `旧记录缺新字段时按默认值读取`() {
        val legacy = JSONObject()
            .put("id", "sc_old")
            .put("name", "old")
            .put("command", "sleep 1")
            .put("environment", "alpine")
            .put("cwd", "/data/local/tmp/eta")
            .put("identity", "root")
            .put("created_at", 1L)
            .put("updated_at", 2L)
        val script = legacy.toScriptDefinition()

        assertEquals(TerminalEnvironment.ALPINE, script.environment)
        assertNull(script.lastTaskId)
        assertNull(script.lastResult)
        assertNull(script.lastFinishedAt)
    }

    @Test
    fun `占用采样按进程树差值算 CPU`() {
        // 2 个进程的树，1.0 秒里 utime+stime 一共涨了 200 tick，即 2 个核满载。
        val output = """
            USAGE 100 1 2 8192 500 700
            USAGE 200 0 1 4096 1 1
            ELAPSED 100.00 101.00
        """.trimIndent()

        val usage = parseScriptUsage(output)

        assertEquals(1, usage.size)
        val tree = requireNotNull(usage[100L])
        assertEquals(2, tree.processCount)
        assertEquals(8192L * 1024, tree.rssBytes)
        assertEquals(200.0, tree.cpuPercent, 0.01)
        assertNull(usage[200L])
    }

    @Test
    fun `采样间隔不可用时返回空`() {
        assertTrue(parseScriptUsage("USAGE 100 1 2 8192 500 700").isEmpty())
        assertTrue(parseScriptUsage("").isEmpty())
    }
}
