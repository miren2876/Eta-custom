package io.github.mangi.eta.agent.terminal

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条脚本定义。脚本库只存定义，运行状态每次都从 [DetachedTaskSupervisor] 现查：
 * 任务记录会被 prune 清理，而「脚本」和「一次运行」不是同一件事。
 */
internal data class ScriptDefinition(
    val id: String,
    val name: String,
    val command: String,
    val environment: TerminalEnvironment,
    val cwd: String,
    val identity: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastTaskId: String? = null,
    /** 停止会连任务记录一起删掉，这里留最后一次结果供列表展示。 */
    val lastResult: String? = null,
    val lastFinishedAt: Long? = null,
)

internal fun ScriptDefinition.toScriptJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("command", command)
    .put("environment", environment.wireName)
    .put("cwd", cwd)
    .put("identity", identity)
    .put("created_at", createdAt)
    .put("updated_at", updatedAt)
    .put("last_task_id", lastTaskId ?: JSONObject.NULL)
    .put("last_result", lastResult ?: JSONObject.NULL)
    .put("last_finished_at", lastFinishedAt ?: JSONObject.NULL)

internal fun JSONObject.toScriptDefinition(): ScriptDefinition = ScriptDefinition(
    id = getString("id"),
    name = optString("name"),
    command = getString("command"),
    environment = when (optString("environment")) {
        TerminalEnvironment.ALPINE.wireName -> TerminalEnvironment.ALPINE
        TerminalEnvironment.DEBIAN.wireName -> TerminalEnvironment.DEBIAN
        else -> TerminalEnvironment.ANDROID
    },
    cwd = optString("cwd"),
    identity = optString("identity").ifBlank { "root" },
    createdAt = optLong("created_at", 0),
    updatedAt = optLong("updated_at", 0),
    lastTaskId = optString("last_task_id").takeIf { it.isNotBlank() && it != "null" },
    lastResult = optString("last_result").takeIf { it.isNotBlank() && it != "null" },
    lastFinishedAt = if (isNull("last_finished_at")) null else optLong("last_finished_at"),
)

/** 脚本库：Eta 私有目录里的一个 JSON 文件，读-改-写经同一把锁串行，写入走临时文件替换。 */
internal class ScriptLibrary(private val file: File) {

    companion object {
        fun defaultFile(context: Context): File = File(context.filesDir, "scripts.json")

        fun newScript(name: String, command: String, environment: TerminalEnvironment, cwd: String): ScriptDefinition {
            val now = System.currentTimeMillis()
            return ScriptDefinition(
                id = "sc_" + UUID.randomUUID().toString().take(8),
                name = name.trim().ifBlank { command.lineSequence().firstOrNull().orEmpty().trim().take(24) },
                command = command.trim(),
                environment = environment,
                cwd = cwd.trim(),
                identity = "root",
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    @Synchronized
    fun load(): List<ScriptDefinition> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { index -> array.getJSONObject(index).toScriptDefinition() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(scripts: List<ScriptDefinition>): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        scripts.forEach { array.put(it.toScriptJson()) }
        val text = array.toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) file.writeText(text)
        true
    }.getOrDefault(false)
}
