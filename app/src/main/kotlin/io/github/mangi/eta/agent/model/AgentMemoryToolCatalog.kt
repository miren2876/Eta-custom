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
                    description = "Read persistent memory. The user profile (USER.md), the working-memory entries " +
                        "(MEMORY.md, ranked by effective importance) and one line of archive stats are already injected " +
                        "every turn, so call this only to inspect details. " +
                        "archive=<name>: read one archive file (memory/files/<name>.md). " +
                        "query=<text>: relevance search across ALL archive files and working-memory entries — returns " +
                        "matching archives with their hit lines and matching entries (each hit also bumps its reference count). " +
                        "With neither argument: return the profile, every entry with EI and reference counts, and the archive index.",
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
                        "target=entry: append ONE working-memory line `- (importance 1-5) fact` — entries are injected " +
                        "every turn and ranked by effective importance (EI = importance × access × 30-day decay), " +
                        "so keep one fact per entry and put long details in an archive instead; " +
                        "add match=<片段> to rewrite the matching entry (content=\"\" deletes it). " +
                        "archive=<name>: create or update one archive file (mode=append adds a paragraph, " +
                        "mode=replace overwrites the file) — one archive per topic, then add an entry pointing at it. " +
                        "target=profile: overwrite USER.md, the user profile (durable preferences, relationships, ≤5 KB). " +
                        "target=working: overwrite MEMORY.md wholesale (rarely needed; prefer target=entry). " +
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
                                        .put("enum", JSONArray().put("entry").put("profile").put("working"))
                                        .put(
                                            "description",
                                            "entry = add or rewrite one working-memory entry (default); " +
                                                "profile = overwrite USER.md; working = overwrite MEMORY.md.",
                                        ),
                                )
                                .put(
                                    "importance",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", 5)
                                        .put(
                                            "description",
                                            "Entry importance 1-5 (default 3); higher keeps the entry injected longer.",
                                        ),
                                )
                                .put(
                                    "match",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put(
                                            "description",
                                            "For target=entry: rewrite the entry containing this text; " +
                                                "content=\"\" removes that entry.",
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
                                        .put(
                                            "description",
                                            "Text to write: one entry (≤500 characters, one fact), a whole file, " +
                                                "or archive markdown.",
                                        ),
                                ),
                        )
                        .put("required", JSONArray().put("content")),
                ),
            )
    }
}
