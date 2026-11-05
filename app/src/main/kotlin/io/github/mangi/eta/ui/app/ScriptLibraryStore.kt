package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTaskStatus
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.ScriptDefinition
import io.github.mangi.eta.agent.terminal.ScriptLibrary
import io.github.mangi.eta.agent.terminal.LinuxProcessSampler
import io.github.mangi.eta.agent.terminal.ScriptRuntimeUsage
import io.github.mangi.eta.agent.terminal.aggregateProcessTrees
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.commandStatus
import io.github.mangi.eta.agent.terminal.isLinux
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.components.ansiPlainText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
internal data class ScriptUi(
    val id: String,
    val name: String,
    val command: String,
    val environment: TerminalEnvironment,
    val cwd: String,
    val running: Boolean = false,
    val taskId: String? = null,
    val startedAt: Long? = null,
    /** 运行中任务的宿主 pid；游离进程判定与进程树聚合都用它。 */
    val pid: Int? = null,
    val lastFinishedAt: Long? = null,
    /** success / failed / stopped；没有记录时为 null。 */
    val lastResult: String? = null,
    /** 读不到占用时为 null，UI 显示「不可用」，不显示 0。 */
    val usage: ScriptRuntimeUsage? = null,
)

@Immutable
internal data class ScriptManagerUiState(
    val scripts: List<ScriptUi> = emptyList(),
    val loaded: Boolean = false,
    val notice: String? = null,
)

/**
 * 后台任务页的状态所有者：脚本库（文件）只存定义，运行状态每轮从 [DetachedTaskSupervisor] 现查，
 * 占用取 [LinuxProcessSampler] 的最近一份快照（与通知共用同一个采样循环）。页面负责驱动轮询。
 */
internal class ScriptLibraryStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val library = ScriptLibrary(ScriptLibrary.defaultFile(appContext))
    private val libraryLock = Any()
    private val supervisor = DetachedTaskSupervisor(
        logger = AndroidAgentLogger,
        recordsFile = DetachedTaskSupervisor.defaultRecordsFile(appContext),
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
            }
        },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
    private val _uiState = MutableStateFlow(ScriptManagerUiState())
    val uiState: StateFlow<ScriptManagerUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var pendingRefresh = false

    /**
     * 页面可见时每轮调用。
     *
     * 旧实现里占用采样要 sleep 1 秒，上一轮没跑完就把整轮丢掉，实际节拍被拉到 3 秒以上；
     * 现在占用直接取常驻采样器的最近快照，重读一次只需读库文件与任务记录，
     * 上一轮没跑完时记一个标志，跑完立刻补一轮，不再丢刷新。
     */
    fun refresh() {
        if (refreshJob?.isActive == true) {
            pendingRefresh = true
            return
        }
        refreshJob = scope.launch {
            do {
                pendingRefresh = false
                reload()
            } while (pendingRefresh)
        }
    }

    /** 动作之后立刻反映结果：打断正在跑的轮询，重新读一遍。 */
    private fun refreshNow() {
        refreshJob?.cancel()
        refreshJob = scope.launch { reload() }
    }

    /** 结束一个游离进程；列表先由页面乐观隐藏，等采样快照校正。 */
    fun killDetachedProcess(pid: Int) {
        scope.launch {
            withContext(Dispatchers.IO) { LinuxProcessSampler.killProcess(pid) }
        }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    fun createScript(name: String, command: String, environment: TerminalEnvironment, cwd: String) {
        if (command.isBlank()) return
        scope.launch {
            val script = ScriptLibrary.newScript(name, command, environment, cwd.ifBlank { defaultCwd(environment) })
            val saved = withContext(Dispatchers.IO) { mutate { it + script } }
            if (!saved) notice(R.string.scripts_save_failed)
            refreshNow()
        }
    }

    fun updateScript(id: String, name: String, command: String, environment: TerminalEnvironment, cwd: String) {
        if (command.isBlank()) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                mutate { scripts ->
                    scripts.map { script ->
                        if (script.id != id) script else script.copy(
                            name = name.trim().ifBlank { script.name },
                            command = command.trim(),
                            environment = environment,
                            cwd = cwd.trim().ifBlank { defaultCwd(environment) },
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                }
            }
            if (!saved) notice(R.string.scripts_save_failed)
            refreshNow()
        }
    }

    fun startScript(id: String) {
        scope.launch {
            val script = withContext(Dispatchers.IO) { library.load() }.firstOrNull { it.id == id } ?: return@launch
            val result = withContext(Dispatchers.IO) {
                supervisor.start(
                    command = script.command,
                    cwd = script.cwd.ifBlank { defaultCwd(script.environment) },
                    identity = script.identity,
                    environment = script.environment,
                    description = script.name,
                )
            }
            when (result) {
                is DaemonStartResult.Started -> withContext(Dispatchers.IO) {
                    mutate { scripts ->
                        scripts.map {
                            if (it.id == id) it.copy(lastTaskId = result.task.id, updatedAt = System.currentTimeMillis()) else it
                        }
                    }
                }
                is DaemonStartResult.Failed -> notice(R.string.scripts_start_failed, result.message)
            }
            refreshNow()
        }
    }

    fun stopScript(id: String) {
        scope.launch {
            val stopped = withContext(Dispatchers.IO) { stopTask(id) }
            if (!stopped) notice(R.string.scripts_stop_failed)
            refreshNow()
        }
    }

    /** 删除前先停止（记录会被 stop 一起清掉）；只删脚本库条目，不动别的任务记录。 */
    fun deleteScript(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                stopTask(id)
                mutate { scripts -> scripts.filterNot { it.id == id } }
            }
            refreshNow()
        }
    }

    suspend fun readLogs(taskId: String): String = withContext(Dispatchers.IO) {
        val result = supervisor.readLogs(taskId)
        when {
            result.ok && result.text.isBlank() -> appContext.getString(R.string.terminal_daemon_logs_empty)
            result.ok -> ansiPlainText(result.text.trimEnd())
            else -> result.message.ifBlank { appContext.getString(R.string.terminal_daemon_logs_empty) }
        }
    }

    /** 停止当前关联的任务；没有在跑的直接算成功。 */
    private fun stopTask(id: String): Boolean {
        val script = library.load().firstOrNull { it.id == id } ?: return false
        val statuses = supervisor.list()
        val taskId = resolveTask(script, statuses, statuses.filter { it.running })?.task?.id ?: return true
        if (!supervisor.stop(taskId)) return false
        mutate { scripts ->
            scripts.map {
                if (it.id != id) it else it.copy(
                    lastTaskId = null,
                    lastResult = "stopped",
                    lastFinishedAt = System.currentTimeMillis(),
                )
            }
        }
        return true
    }

    private fun mutate(transform: (List<ScriptDefinition>) -> List<ScriptDefinition>): Boolean =
        synchronized(libraryLock) { library.save(transform(library.load())) }

    private fun notice(resId: Int, vararg args: Any?) {
        val text = appContext.getString(resId, *args)
        _uiState.update { it.copy(notice = text) }
    }

    private suspend fun reload() {
        val scripts = withContext(Dispatchers.IO) { library.load() }
        val statuses = withContext(Dispatchers.IO) { supervisor.list() }
        val running = statuses.filter { it.running }
        val resolved = scripts.map { script -> script to resolveTask(script, statuses, running) }
        val rootPids = resolved.mapNotNull { (_, status) ->
            status?.takeIf { it.running }?.task?.pid?.toInt()
        }
        // Android 环境的脚本任务不在 rootfs 内，告诉采样器把它们的进程树也带上。
        LinuxProcessSampler.setExtraRootPids(rootPids)
        val snapshot = LinuxProcessSampler.snapshot.value
        val usage = if (snapshot == null) {
            emptyMap()
        } else {
            aggregateProcessTrees(snapshot.processes, rootPids)
        }
        _uiState.update { state ->
            state.copy(
                scripts = resolved.map { (script, status) -> toScriptUi(script, status, usage) },
                loaded = true,
            )
        }
    }

    /** 优先按 lastTaskId 认任务；记录已被清理时用 command + environment 兜底匹配最近一条。 */
    private fun resolveTask(
        script: ScriptDefinition,
        statuses: List<DetachedTaskStatus>,
        running: List<DetachedTaskStatus>,
    ): DetachedTaskStatus? = script.lastTaskId?.let { id -> statuses.firstOrNull { it.task.id == id } }
        ?: running
            .filter { it.task.command == script.command && it.task.environment == script.environment }
            .maxByOrNull { it.task.startedAt }

    private fun toScriptUi(
        script: ScriptDefinition,
        status: DetachedTaskStatus?,
        usage: Map<Int, ScriptRuntimeUsage>,
    ): ScriptUi {
        val running = status?.running == true
        val task = status?.task
        return ScriptUi(
            id = script.id,
            name = script.name,
            command = script.command,
            environment = script.environment,
            cwd = script.cwd,
            running = running,
            taskId = task?.id,
            startedAt = task?.takeIf { running }?.startedAt,
            pid = task?.takeIf { running }?.pid?.toInt(),
            lastFinishedAt = task?.completedAt ?: script.lastFinishedAt,
            lastResult = when {
                running -> null
                // 守护任务的记录不会自己写 completedAt，进程没了就按「已结束」显示。
                task != null -> when (task.commandStatus) {
                    "completed" -> "success"
                    "failed" -> "failed"
                    else -> "stopped"
                }
                else -> script.lastResult
            },
            usage = task?.takeIf { running }?.let { usage[it.pid.toInt()] },
        )
    }

    private fun defaultCwd(environment: TerminalEnvironment): String =
        if (environment.isLinux) TerminalRuntime.LINUX_WORKSPACE_PATH else TerminalRuntime.ROOT_WORKSPACE_PATH
}
