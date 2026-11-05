package io.github.mangi.eta.agent.roleplay

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import io.github.mangi.eta.data.repository.AgentMemoryException
import io.github.mangi.eta.data.repository.AgentMemoryMutation
import io.github.mangi.eta.data.repository.AgentMemoryStore
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.CharacterMemoryRepository
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 角色作用域由 Runtime 绑定，模型参数不能选择其他角色或现实记忆。 */
internal class CharacterMemoryTools(
    private val context: Context,
    private val characterId: String,
    private val enabled: () -> Boolean,
) {
    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (!enabled()) return result(error("MEMORY_DISABLED", "记忆已在设置中关闭"))
        return try {
            val args = JSONObject(call.argumentsJson)
            val value = when (call.name) {
                GET -> {
                    val read = CharacterMemoryRepository.read(
                        context, characterId,
                        query = args.optString("query").takeIf(String::isNotBlank),
                        startLine = args.optInt("start_line", 1),
                        maxChars = args.optInt("max_chars", 12_000),
                    )
                    JSONObject().put("ok", true).put("revision", read.snapshot.revision)
                        .put("bytes", read.snapshot.byteSize).put("line_count", read.snapshot.lineCount)
                        .put("start_line", read.startLine ?: JSONObject.NULL)
                        .put("end_line", read.endLine ?: JSONObject.NULL)
                        .put("matched_lines", read.matchedLines).put("has_more", read.hasMore)
                        .put("content", read.content)
                }
                WRITE -> {
                    val revision = args.getString("revision")
                    val mutation = when (args.getString("mode")) {
                        "replace_range" -> AgentMemoryMutation.ReplaceRange(
                            revision, args.getInt("start_line"), args.getInt("end_line"), args.getString("content"),
                        )
                        "append" -> AgentMemoryMutation.Append(revision, args.getString("content"))
                        "clear" -> AgentMemoryMutation.Clear(revision)
                        else -> return result(error("INVALID_MEMORY_MODE", "角色记忆写入模式无效"))
                    }
                    when (val written = CharacterMemoryRepository.mutate(context, characterId, mutation)) {
                        is AgentMemoryWriteResult.Success -> JSONObject().put("ok", true)
                            .put("revision", written.snapshot.revision).put("bytes", written.snapshot.byteSize)
                            .put("line_count", written.snapshot.lineCount)
                            .apply { written.notice?.let { notice -> put("notice", notice) } }
                        is AgentMemoryWriteResult.Rejected -> error("MEMORY_DUPLICATE", written.notice)
                            .put("revision", written.snapshot.revision)
                        is AgentMemoryWriteResult.Conflict -> error(
                            "MEMORY_CONFLICT", "剧情记忆已发生变化，请先调用 character_memory_get 获取最新内容",
                        ).put("revision", written.snapshot.revision)
                    }
                }
                else -> error("UNKNOWN_TOOL", "未知角色记忆工具")
            }
            result(value)
        } catch (failure: AgentMemoryException) {
            result(error(failure.code, failure.message ?: "角色记忆操作失败"))
        } catch (_: JSONException) {
            result(error("INVALID_TOOL_ARGUMENTS", "角色记忆工具参数无效"))
        }
    }

    private fun result(value: JSONObject) = AgentModelClient.ToolResult(value.toString(), sensitive = true)

    private fun error(code: String, message: String) =
        JSONObject().put("ok", false).put("code", code).put("message", message)

    companion object {
        const val GET = "character_memory_get"
        const val WRITE = "character_memory_write"
        val NAMES = setOf(GET, WRITE)

        /**
         * 角色记忆是独立的一套参数面（revision + mode=replace_range/append/clear），
         * 不能再从真实记忆工具的 schema 克隆：记忆改造后两者已经不同形。
         */
        fun appendSchemas(tools: JSONArray) {
            tools
                .put(
                    AgentToolSchema.function(
                        name = GET,
                        description = "Read this character's persistent story memory. It contains fictional events, " +
                            "relationships, and scene continuity shared by this character's conversations. " +
                            "Use query or bounded pages. This does not read the user's real-world MEMORY.md.",
                        parameters = JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put(
                                        "query",
                                        JSONObject()
                                            .put("type", "string")
                                            .put("maxLength", 500)
                                            .put("description", "Optional search text; matching lines come back with their context."),
                                    )
                                    .put(
                                        "start_line",
                                        JSONObject()
                                            .put("type", "integer")
                                            .put("minimum", 1)
                                            .put("description", "First line to read, 1-based; default 1."),
                                    )
                                    .put(
                                        "max_chars",
                                        JSONObject()
                                            .put("type", "integer")
                                            .put("minimum", 1)
                                            .put("maximum", AgentMemoryStore.MAX_READ_CHARS)
                                            .put("description", "Maximum characters returned, default 12000."),
                                    ),
                            ),
                    ),
                )
                .put(
                    AgentToolSchema.function(
                        name = WRITE,
                        description = "Atomically update this character's persistent story memory with fictional events, " +
                            "relationships, and scene continuity. Keep '# 核心记忆' concise and update stale facts instead of " +
                            "duplicating them. Never put story facts in real-world MEMORY.md or treat fictional actions as " +
                            "completed device operations. Use the revision from character_memory_get or the run-start " +
                            "character memory.",
                        parameters = JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put(
                                        "revision",
                                        JSONObject()
                                            .put("type", "string")
                                            .put("minLength", 64)
                                            .put("maxLength", 64)
                                            .put(
                                                "description",
                                                "Exact SHA-256 revision from character_memory_get or the run-start character memory.",
                                            ),
                                    )
                                    .put(
                                        "mode",
                                        JSONObject()
                                            .put("type", "string")
                                            .put(
                                                "enum",
                                                JSONArray().put("replace_range").put("append").put("clear"),
                                            )
                                            .put(
                                                "description",
                                                "replace_range edits inclusive 1-based lines; append adds a distinct section; clear removes all story memory.",
                                            ),
                                    )
                                    .put(
                                        "start_line",
                                        JSONObject()
                                            .put("type", "integer")
                                            .put("minimum", 1)
                                            .put("description", "Required for replace_range; inclusive 1-based first line."),
                                    )
                                    .put(
                                        "end_line",
                                        JSONObject()
                                            .put("type", "integer")
                                            .put("minimum", 1)
                                            .put("description", "Required for replace_range; inclusive 1-based last line."),
                                    )
                                    .put(
                                        "content",
                                        JSONObject()
                                            .put("type", "string")
                                            .put("maxLength", AgentMemoryStore.MAX_WRITE_CONTENT_CHARS)
                                            .put("description", "Replacement or appended Markdown; empty content deletes a replace_range."),
                                    ),
                            )
                            .put("required", JSONArray().put("mode").put("revision")),
                    ),
                )
        }
    }
}
