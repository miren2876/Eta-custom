package io.github.mangi.eta.ui.screens.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxProcessInfo
import io.github.mangi.eta.agent.terminal.LinuxProcessSampler
import io.github.mangi.eta.agent.terminal.ProcessUsage
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.detachedProcesses
import io.github.mangi.eta.agent.terminal.retainLivePids
import io.github.mangi.eta.ui.app.ConversationTimeLabels
import io.github.mangi.eta.ui.app.ScriptLibraryStore
import io.github.mangi.eta.ui.app.ScriptUi
import io.github.mangi.eta.ui.app.displayName
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.EtaMonospace
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.ansiPlainText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.collectAsState

/**
 * 后台任务页：运行中的脚本任务、游离进程与已保存脚本。
 *
 * 进程数据来自 [LinuxProcessSampler] 的常驻采样循环：页面只持一个 1 秒租约并订阅快照，
 * 不再自己起 su 轮询；脚本的运行状态仍由 [ScriptLibraryStore] 每轮从任务记录现查。
 */
@Composable
internal fun ScriptManagerScreen(
    store: ScriptLibraryStore,
    onBack: () -> Unit,
) {
    val state by store.uiState.collectAsState()
    val snapshot by LinuxProcessSampler.snapshot.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var editor by remember { mutableStateOf<ScriptDraft?>(null) }
    var actionsFor by remember { mutableStateOf<ScriptUi?>(null) }
    var stopTarget by remember { mutableStateOf<ScriptUi?>(null) }
    var deleteTarget by remember { mutableStateOf<ScriptUi?>(null) }
    var logsTaskId by remember { mutableStateOf<String?>(null) }
    // 点过的游离进程先从列表里拿掉，等下一轮快照校正，保证「点完立刻消失」。
    var pendingKills by remember { mutableStateOf(setOf<Int>()) }
    // 多选：只对游离进程开放，脚本行不参与。
    var selectedPids by remember { mutableStateOf(setOf<Int>()) }
    var detailTarget by remember { mutableStateOf<LinuxProcessInfo?>(null) }
    var stopProcessTarget by remember { mutableStateOf<LinuxProcessInfo?>(null) }
    var bulkKillConfirm by remember { mutableStateOf(false) }

    val runningScripts = state.scripts.filter { it.running }
    val savedScripts = state.scripts.filterNot { it.running }
    val scriptRootIds = runningScripts.mapNotNull { it.pid }
    val detachedList = snapshot
        ?.let { detachedProcesses(it.processes, scriptRootIds) }
        .orEmpty()
        .filterNot { it.pid in pendingKills }

    // 页面可见期间持有 1 秒采样租约；离开即释放，不留常驻 root 进程。
    DisposableEffect(Unit) {
        val lease = LinuxProcessSampler.acquire(1_000L)
        onDispose {
            lease.release()
            // 离开页面后不再需要 Android 环境脚本的进程树，通知侧只关心 rootfs。
            LinuxProcessSampler.setExtraRootPids(emptySet())
        }
    }

    // 只在本页可见时轮询；离开页面这一协程随组合取消，不留后台定时器。
    LaunchedEffect(Unit) {
        while (isActive) {
            store.refresh()
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    // 进程真的从快照里消失后，清掉对应的乐观隐藏标记（没杀掉的话下一轮会重新出现）。
    LaunchedEffect(snapshot) {
        val live = snapshot?.processes?.mapTo(mutableSetOf()) { it.pid }.orEmpty()
        if (pendingKills.isNotEmpty()) pendingKills = pendingKills.filter { it in live }.toSet()
        // 进程已经消失就取消选中，避免 pid 复用后误杀。
        if (selectedPids.isNotEmpty()) selectedPids = retainLivePids(selectedPids, live)
    }

    // 多选模式下返回键先退出多选，不直接离开页面。
    BackHandler(enabled = selectedPids.isNotEmpty()) { selectedPids = emptySet() }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_scripts),
        onBack = onBack,
        actions = {
            RowIconAction(
                icon = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.scripts_new),
                onClick = { editor = ScriptDraft() },
            )
        },
    ) {
        state.notice?.let { notice ->
            item(key = "notice") {
                EtaPreferenceGroup {
                    EtaPreference(
                        title = notice,
                        titleColor = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(
                            color = MiuixTheme.colorScheme.error,
                        ),
                        onClick = store::dismissNotice,
                    )
                }
            }
        }
        val running = runningScripts
        val saved = savedScripts
        val detached = detachedList
        if (!state.loaded) {
            item(key = "loading") {
                EtaPreferenceGroup {
                    EtaPreference(
                        title = stringResource(R.string.tasks_loading),
                        startAction = { InfiniteProgressIndicator(size = 16.dp) },
                    )
                }
            }
        }
        if (state.loaded && state.scripts.isEmpty() && detached.isEmpty()) {
            item(key = "empty") {
                EtaPreferenceGroup {
                    EtaPreference(
                        title = stringResource(R.string.scripts_empty),
                        onClick = { editor = ScriptDraft() },
                    )
                }
            }
        }
        if (running.isNotEmpty() || detached.isNotEmpty()) {
            item(key = "running-title") {
                EtaPreferenceGroupTitle(stringResource(R.string.scripts_group_running))
            }
            if (running.isNotEmpty()) {
                item(key = "running-scripts") {
                    EtaPreferenceGroup {
                        running.forEachIndexed { index, script ->
                            if (index > 0) EtaPreferenceDivider()
                            ScriptRow(
                                script = script,
                                now = now,
                                onStart = { store.startScript(script.id) },
                                onStop = { stopTarget = script },
                                onLogs = script.taskId?.let { taskId -> { logsTaskId = taskId } },
                                onClick = { actionsFor = script },
                            )
                        }
                    }
                }
            }
            if (detached.isNotEmpty()) {
                item(key = "running-processes-title") {
                    EtaPreferenceGroupTitle(stringResource(R.string.tasks_group_detached))
                }
                if (selectedPids.isNotEmpty()) {
                    item(key = "multi-select-toolbar") {
                        EtaPreferenceGroup {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.tasks_selected_count, selectedPids.size),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                Spacer(Modifier.weight(1f))
                                EtaChoiceChip(
                                    text = stringResource(R.string.tasks_select_all),
                                    selected = false,
                                    onClick = { selectedPids = detached.map { it.pid }.toSet() },
                                )
                                EtaChoiceChip(
                                    text = stringResource(R.string.tasks_kill_selected),
                                    selected = false,
                                    onClick = { bulkKillConfirm = true },
                                )
                                EtaChoiceChip(
                                    text = stringResource(R.string.tasks_done),
                                    selected = false,
                                    onClick = { selectedPids = emptySet() },
                                )
                            }
                        }
                    }
                }
                item(key = "running-processes") {
                    EtaPreferenceGroup {
                        detached.forEachIndexed { index, process ->
                            if (index > 0) EtaPreferenceDivider()
                            DetachedProcessRow(
                                process = process,
                                selected = process.pid in selectedPids,
                                multiSelect = selectedPids.isNotEmpty(),
                                onToggleSelect = {
                                    selectedPids = if (process.pid in selectedPids) {
                                        selectedPids - process.pid
                                    } else {
                                        selectedPids + process.pid
                                    }
                                },
                                onLongPress = { selectedPids = selectedPids + process.pid },
                                onOpenDetail = { detailTarget = process },
                                onRequestStop = { stopProcessTarget = process },
                            )
                        }
                    }
                }
            }
        }
        if (saved.isNotEmpty()) {
            item(key = "saved-title") {
                EtaPreferenceGroupTitle(stringResource(R.string.scripts_group_saved))
            }
            item(key = "saved") {
                EtaPreferenceGroup {
                    saved.forEachIndexed { index, script ->
                        if (index > 0) EtaPreferenceDivider()
                        ScriptRow(
                            script = script,
                            now = now,
                            onStart = { store.startScript(script.id) },
                            onStop = { stopTarget = script },
                            onLogs = script.taskId?.let { taskId -> { logsTaskId = taskId } },
                            onClick = { actionsFor = script },
                        )
                    }
                }
            }
        }
    }

    editor?.let { draft ->
        ScriptEditorDialog(
            draft = draft,
            onDismiss = { editor = null },
            onSave = { name, command, environment, cwd ->
                if (draft.id == null) {
                    store.createScript(name, command, environment, cwd)
                } else {
                    store.updateScript(draft.id, name, command, environment, cwd)
                }
                editor = null
            },
        )
    }

    actionsFor?.let { script ->
        ScriptActionsDialog(
            script = script,
            onDismiss = { actionsFor = null },
            onStart = { store.startScript(script.id); actionsFor = null },
            onStop = { actionsFor = null; stopTarget = script },
            onLogs = { actionsFor = null; logsTaskId = script.taskId },
            onEdit = {
                actionsFor = null
                editor = ScriptDraft(
                    id = script.id,
                    name = script.name,
                    command = script.command,
                    environment = script.environment,
                    cwd = script.cwd,
                    running = script.running,
                )
            },
            onDelete = { actionsFor = null; deleteTarget = script },
        )
    }

    stopTarget?.let { script ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.scripts_stop_title),
            summary = stringResource(R.string.scripts_stop_message, script.name),
            onDismissRequest = { stopTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.terminal_stop),
                destructive = true,
                onCancel = { stopTarget = null },
                onConfirm = {
                    store.stopScript(script.id)
                    stopTarget = null
                },
            )
        }
    }

    deleteTarget?.let { script ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.action_delete),
            summary = if (script.running) {
                stringResource(R.string.scripts_delete_running_message)
            } else {
                stringResource(R.string.scripts_delete_message)
            },
            onDismissRequest = { deleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { deleteTarget = null },
                onConfirm = {
                    store.deleteScript(script.id)
                    deleteTarget = null
                },
            )
        }
    }

    detailTarget?.let { process ->
        ProcessDetailDialog(
            process = process,
            sampledAt = snapshot?.sampledAt,
            onDismiss = { detailTarget = null },
            onRequestStop = {
                detailTarget = null
                stopProcessTarget = process
            },
        )
    }

    stopProcessTarget?.let { process ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.tasks_kill_confirm_title),
            summary = stringResource(R.string.tasks_kill_confirm_summary, process.pid, process.usage),
            onDismissRequest = { stopProcessTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.tasks_process_kill),
                destructive = true,
                onCancel = { stopProcessTarget = null },
                onConfirm = {
                    pendingKills = pendingKills + process.pid
                    store.killDetachedProcess(process.pid)
                    selectedPids = selectedPids - process.pid
                    stopProcessTarget = null
                },
            )
        }
    }

    if (bulkKillConfirm) {
        val targets = detachedList.filter { it.pid in selectedPids }
        val names = targets.take(3).joinToString("、") { it.usage }
        val summary = if (targets.isEmpty()) {
            ""
        } else {
            stringResource(R.string.tasks_kill_selected_confirm, targets.size, names)
        }
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.tasks_kill_selected),
            summary = summary,
            onDismissRequest = { bulkKillConfirm = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.tasks_kill_selected),
                destructive = true,
                onCancel = { bulkKillConfirm = false },
                onConfirm = {
                    val pids = targets.map { it.pid }
                    pendingKills = pendingKills + pids
                    pids.forEach(store::killDetachedProcess)
                    selectedPids = emptySet()
                    bulkKillConfirm = false
                },
            )
        }
    }

    logsTaskId?.let { taskId ->
        ScriptLogsDialog(store = store, taskId = taskId, onDismiss = { logsTaskId = null })
    }
}

private data class ScriptDraft(
    val id: String? = null,
    val name: String = "",
    val command: String = "",
    val environment: TerminalEnvironment = TerminalEnvironment.ANDROID,
    val cwd: String = "",
    val running: Boolean = false,
)

@Composable
private fun DetachedProcessRow(
    process: LinuxProcessInfo,
    selected: Boolean,
    multiSelect: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
    onOpenDetail: () -> Unit,
    onRequestStop: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val meta = buildString {
        append("PID ")
        append(process.pid)
        process.cpuPercent?.let { append(" · CPU ").append("%.1f%%".format(it)) }
        append(" · ")
        append("%.1f MB".format(process.memoryMb))
    }
    val command = ProcessUsage.commandSummary(process.command, maxChars = 56)
    val cwd = process.cwd
    EtaPreference(
        modifier = if (selected) {
            Modifier.background(colors.primary.copy(alpha = 0.12f))
        } else {
            Modifier
        },
        content = {
            Text(
                text = process.usage,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = command,
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = EtaMonospace),
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = if (cwd.isNullOrBlank()) meta else "$cwd · $meta",
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        },
        startAction = { StatusDot(color = if (selected) colors.primary else StatusSuccess) },
        endActions = {
            RowIconAction(
                icon = Icons.Rounded.Stop,
                contentDescription = stringResource(R.string.tasks_process_kill),
                tint = colors.error,
                onClick = onRequestStop,
            )
        },
        onClickLabel = stringResource(R.string.tasks_process_row_action),
        onLongClickLabel = stringResource(R.string.tasks_process_long_press),
        onClick = if (multiSelect) onToggleSelect else onOpenDetail,
        onLongClick = onLongPress,
    )
}

@Composable
private fun ProcessDetailDialog(
    process: LinuxProcessInfo,
    sampledAt: Long?,
    onDismiss: () -> Unit,
    onRequestStop: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val timeLabel = sampledAt?.let {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
    }
    EtaWindowDialog(show = true, title = process.usage, onDismissRequest = onDismiss) {
        Column {
            Text(
                text = process.command,
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = EtaMonospace),
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState())
                    .squircleBackground(colors.surface, 10.dp)
                    .padding(10.dp),
            )
            Text(
                text = stringResource(R.string.tasks_process_detail_cwd, process.cwd ?: "—"),
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                text = stringResource(
                    R.string.tasks_process_detail_meta,
                    process.pid,
                    process.ppid,
                    process.cpuPercent?.let { "%.1f%%".format(it) } ?: "—",
                    "%.1f MB".format(process.memoryMb),
                ),
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (timeLabel != null) {
                Text(
                    text = stringResource(R.string.tasks_process_detail_snapshot, timeLabel),
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.tasks_process_kill),
                destructive = true,
                modifier = Modifier.padding(top = 16.dp),
                onCancel = onDismiss,
                onConfirm = onRequestStop,
            )
        }
    }
}

@Composable
private fun ScriptRow(
    script: ScriptUi,
    now: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onLogs: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val summaryColor = colors.onSurfaceVariantSummary
    EtaPreference(
        content = {
            Text(
                text = script.name,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = script.command.lineSequence().firstOrNull().orEmpty(),
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = EtaMonospace),
                color = summaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            // 用途是启发式推断，用中性词展示，并排放在命令下面。
            Text(
                text = ProcessUsage.describe(script.command),
                style = MiuixTheme.textStyles.footnote2,
                color = summaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = scriptMeta(script, now),
                style = MiuixTheme.textStyles.footnote2,
                color = summaryColor,
                modifier = Modifier.padding(top = 2.dp),
            )
        },
        startAction = { StatusDot(color = scriptStatusColor(script)) },
        endActions = {
            onLogs?.let { open ->
                RowIconAction(
                    icon = Icons.AutoMirrored.Rounded.Article,
                    contentDescription = stringResource(R.string.terminal_daemon_view_logs),
                    onClick = open,
                )
            }
            if (script.running) {
                RowIconAction(
                    icon = Icons.Rounded.Stop,
                    contentDescription = stringResource(R.string.terminal_stop),
                    tint = colors.error,
                    onClick = onStop,
                )
            } else {
                RowIconAction(
                    icon = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.scripts_start),
                    tint = colors.primary,
                    onClick = onStart,
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun scriptStatusColor(script: ScriptUi) = when {
    script.running -> StatusSuccess
    script.lastResult == "failed" -> MiuixTheme.colorScheme.error
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
}

/** 运行中显示时长与占用；已停止显示环境、最近运行与上次结果。 */
@Composable
private fun scriptMeta(script: ScriptUi, now: Long): String {
    val environment = script.environment.displayName
    if (script.running) {
        val usage = script.usage
        val uptime = formatUptime(now - (script.startedAt ?: now))
        val cpu = usage?.let { "%.1f%%".format(it.cpuPercent) }
            ?: stringResource(R.string.scripts_unavailable)
        val memory = usage?.let { "%.1f MB".format(it.rssBytes / 1024.0 / 1024.0) }
            ?: stringResource(R.string.scripts_unavailable)
        val count = usage?.processCount?.let { stringResource(R.string.scripts_processes, it) }
            ?: stringResource(R.string.scripts_unavailable)
        return buildString {
            append(environment)
            append(" · ")
            append(stringResource(R.string.scripts_uptime, uptime))
            append(" · ")
            append(stringResource(R.string.scripts_cpu, cpu))
            append(" · ")
            append(stringResource(R.string.scripts_memory, memory))
            append(" · ")
            append(count)
        }
    }
    val lastRun = script.lastFinishedAt?.takeIf { it > 0 }
        ?.let {
            stringResource(
                R.string.scripts_last_run,
                ConversationTimeLabels.label(
                    timestampMillis = it,
                    yesterdayLabel = stringResource(R.string.time_yesterday),
                    recentLabel = stringResource(R.string.time_recent),
                ),
            )
        }
        ?: stringResource(R.string.scripts_never_run)
    val result = when (script.lastResult) {
        "success" -> stringResource(R.string.scripts_result_success)
        "failed" -> stringResource(R.string.scripts_result_failed)
        "stopped" -> stringResource(R.string.scripts_result_stopped)
        else -> null
    }
    return buildString {
        append(environment)
        append(" · ")
        append(lastRun)
        if (result != null) {
            append(" · ")
            append(result)
        }
    }
}

private fun formatUptime(millis: Long): String {
    val total = (millis.coerceAtLeast(0) / 1000)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

@Composable
private fun ScriptActionsDialog(
    script: ScriptUi,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onLogs: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    EtaWindowDialog(show = true, title = script.name, onDismissRequest = onDismiss) {
        Column {
            Text(
                text = script.command,
                style = MiuixTheme.textStyles.footnote2.copy(fontFamily = EtaMonospace),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            EtaPreferenceGroup(modifier = Modifier.padding(0.dp)) {
                if (script.running) {
                    EtaPreference(
                        title = stringResource(R.string.terminal_stop),
                        titleColor = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(
                            color = MiuixTheme.colorScheme.error,
                        ),
                        onClick = onStop,
                    )
                } else {
                    EtaPreference(title = stringResource(R.string.scripts_start), onClick = onStart)
                }
                if (script.taskId != null) {
                    EtaPreferenceDivider()
                    EtaPreference(title = stringResource(R.string.terminal_daemon_view_logs), onClick = onLogs)
                }
                EtaPreferenceDivider()
                EtaPreference(title = stringResource(R.string.scripts_edit), onClick = onEdit)
                EtaPreferenceDivider()
                EtaPreference(
                    title = stringResource(R.string.action_delete),
                    titleColor = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.error,
                    ),
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun ScriptEditorDialog(
    draft: ScriptDraft,
    onDismiss: () -> Unit,
    onSave: (name: String, command: String, environment: TerminalEnvironment, cwd: String) -> Unit,
) {
    var name by remember { mutableStateOf(draft.name) }
    var command by remember { mutableStateOf(draft.command) }
    var environment by remember { mutableStateOf(draft.environment) }
    var cwd by remember { mutableStateOf(draft.cwd) }
    var commandMissing by remember { mutableStateOf(false) }

    EtaWindowDialog(
        show = true,
        title = stringResource(if (draft.id == null) R.string.scripts_new else R.string.scripts_edit),
        onDismissRequest = onDismiss,
    ) {
        Column {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.scripts_name_label),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = command,
                onValueChange = {
                    command = it
                    commandMissing = false
                },
                label = stringResource(R.string.scripts_command_label),
                singleLine = false,
                minLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Text(
                text = stringResource(R.string.scripts_environment_label),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TerminalEnvironment.entries.forEach { candidate ->
                    EtaChoiceChip(
                        text = candidate.displayName,
                        selected = candidate == environment,
                        onClick = { environment = candidate },
                    )
                }
            }
            TextField(
                value = cwd,
                onValueChange = { cwd = it },
                label = stringResource(R.string.scripts_cwd_label),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            if (commandMissing) {
                Text(
                    text = stringResource(R.string.scripts_command_required),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (draft.running) {
                Text(
                    text = stringResource(R.string.scripts_restart_hint),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.scripts_save),
                // 按钮行自带 top padding：输入框以及其上方的错误/重启提示都靠它与按钮拉开距离。
                modifier = Modifier.padding(top = 16.dp),
                onCancel = onDismiss,
                onConfirm = {
                    if (command.isBlank()) commandMissing = true else onSave(name, command, environment, cwd)
                },
            )
        }
    }
}

@Composable
private fun ScriptLogsDialog(
    store: ScriptLibraryStore,
    taskId: String,
    onDismiss: () -> Unit,
) {
    var logs by remember(taskId) { mutableStateOf<String?>(null) }
    LaunchedEffect(taskId) { logs = store.readLogs(taskId) }
    EtaWindowDialog(
        show = true,
        title = stringResource(R.string.terminal_daemon_view_logs),
        onDismissRequest = onDismiss,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .squircleBackground(MiuixTheme.colorScheme.surface, 10.dp)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            val text = logs
            if (text == null) {
                InfiniteProgressIndicator(size = 14.dp)
            } else {
                Text(
                    text = ansiPlainText(text),
                    style = MiuixTheme.textStyles.footnote2.copy(fontFamily = EtaMonospace),
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                )
            }
        }
    }
}
