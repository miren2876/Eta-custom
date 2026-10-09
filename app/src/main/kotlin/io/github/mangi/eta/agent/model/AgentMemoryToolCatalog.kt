package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.repository.AgentMemoryStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 声明持久记忆工具。存储分三层：
 * - 用户画像 `memory/USER.md`（稳定偏好、关系、长期事实，每轮注入）
 * - 工作记忆 `memory/MEMORY.md`（档案清单 + 当前进度，每轮注入）
 * - 档案 `memory/files/<name>.md`（一档一主题，按需读取）
 * - 归档 `memory/MEMORY-archive.md`（冷档案迁入，可在记忆页还原）
 */
internal object AgentMemoryToolCatalog {
    fun appendTo(tools: JSONArray, writable: Boolean = true) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "memory_get",
                    description = "Read persistent memory. The user profile (USER.md) and working memory (MEMORY.md), " +
                        "plus the archive index, are already injected every turn, so call this only to inspect details. " +
                        "archive=<name>: read one archive file (memory/files/<name>.md). " +
                        "query=<text>: relevance search across ALL archive files — returns matching archives with their " +
                        "hit lines (each hit also bumps that archive's reference count). " +
                        "With neither argument: return the full core memory plus the archive index (use it to refresh after edits).",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "archive",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 120)
                                        .put("description", "Name of one archive file to read (see the archive index)."),
                                )
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put(
                                            "description",
                                            "Search text; scored per line across all archives, results ranked by relevance.",
                                        ),
                                ),
                        ),
                ),
            )
        if (!writable) return
        tools
            .put(
                AgentToolSchema.function(
                    name = "memory_write",
                    description = "Write persistent memory. " +
                        "archive=<name>: create or update one archive file (mode=append adds a paragraph, " +
                        "mode=replace overwrites the file) — prefer one archive per topic. " +
                        "target=profile: overwrite USER.md, the user profile (durable preferences, relationships, ≤5 KB). " +
                        "target=working: overwrite MEMORY.md, the working memory (archive index + current progress, ≤10 KB); " +
                        "it is injected every turn, keep it short and keep one `- name` line per archive. " +
                        "Both target writes replace that whole file, so include the complete new text. " +
                        "Never store secrets, credentials, verification codes or transient requests. " +
                        "Near-duplicate archive appends are rejected with MEMORY_DUPLICATE plus a hint to merge.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "archive",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 120)
                                        .put("description", "Archive file name to create or update."),
                                )
                                .put(
                                    "target",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("profile").put("working"))
                                        .put(
                                            "description",
                                            "File to overwrite when archive is not used: " +
                                                "profile = USER.md (user profile), working = MEMORY.md (working memory).",
                                        ),
                                )
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("append").put("replace"))
                                        .put("description", "For archive writes: append (default) adds, replace overwrites."),
                                )
                                .put(
                                    "content",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", AgentMemoryStore.MAX_WRITE_CONTENT_CHARS)
                                        .put("description", "Markdown to write, at most 3500 characters."),
                                ),
                        )
                        .put("required", JSONArray().put("content")),
                ),
            )
    }
}
