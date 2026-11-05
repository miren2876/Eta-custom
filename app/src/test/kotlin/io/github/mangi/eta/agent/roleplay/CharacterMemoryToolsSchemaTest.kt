package io.github.mangi.eta.agent.roleplay

import io.github.mangi.eta.data.repository.AgentMemoryStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 角色记忆工具的 schema 必须和执行器读的参数一致：执行器读 `revision` +
 * `mode=replace_range/append/clear`，而真实记忆工具在记忆改造后已经改成
 * `target`/`archive` 那一套 —— 两者不能互相克隆。
 */
class CharacterMemoryToolsSchemaTest {
    private fun functions(): List<JSONObject> {
        val tools = JSONArray().also(CharacterMemoryTools::appendSchemas)
        return (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
    }

    private fun JSONObject.properties(): JSONObject =
        getJSONObject("parameters").getJSONObject("properties")

    @Test
    fun writeSchemaKeepsTheRevisionAndModeContractTheExecutorReads() {
        val write = functions().single { it.getString("name") == CharacterMemoryTools.WRITE }
        val required = write.getJSONObject("parameters").getJSONArray("required")
        assertEquals(
            listOf("mode", "revision"),
            (0 until required.length()).map { required.getString(it) },
        )
        val modes = write.properties().getJSONObject("mode").getJSONArray("enum")
        assertEquals(
            listOf("replace_range", "append", "clear"),
            (0 until modes.length()).map { modes.getString(it) },
        )
        assertEquals(64, write.properties().getJSONObject("revision").getInt("minLength"))
        assertEquals(
            AgentMemoryStore.MAX_WRITE_CONTENT_CHARS,
            write.properties().getJSONObject("content").getInt("maxLength"),
        )
    }

    @Test
    fun readSchemaExposesBoundedPagesAndNothingElse() {
        val read = functions().single { it.getString("name") == CharacterMemoryTools.GET }
        assertEquals(
            setOf("query", "start_line", "max_chars"),
            read.properties().keys().asSequence().toSet(),
        )
        assertEquals(
            AgentMemoryStore.MAX_READ_CHARS,
            read.properties().getJSONObject("max_chars").getInt("maximum"),
        )
        assertEquals(
            setOf(CharacterMemoryTools.GET, CharacterMemoryTools.WRITE),
            functions().map { it.getString("name") }.toSet(),
        )
    }
}
