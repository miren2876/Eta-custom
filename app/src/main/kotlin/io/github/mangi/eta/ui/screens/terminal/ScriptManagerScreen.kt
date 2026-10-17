package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
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
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.collectAsState

/** 管理脚本页：脚本库的增删改与启停，运行中的脚本每 2 秒刷新一次进程树占用。 */
@Composable
internal fun ScriptManagerScreen(
    store: ScriptLibraryStore,
    onBack: () -> Unit,
) {
    val state by store.uiState.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var editor by remember { mutableStateOf<ScriptDraft?>(null) }
    var actionsFor by remember { mutableStateOf<ScriptUi?>(null) }
    var stopTarget by remember { mutableStateOf<ScriptUi?>(null) }
    var deleteTarget by remember { mutableStateOf<ScriptUi?>(null) }
    var logsTaskId by remember { mutableStateOf<String?>(null) }

    // 只在本页可见时轮询；离开页面这一协程随组合取消，不留后台定时器。
    LaunchedEffect(Unit) {
        while (isActive) {
            store.refresh()
            now = System.currentTimeMillis()
            delay(2_000)
        }
    }

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
        val running = state.scripts.filter { it.running }
        val saved = state.scripts.filterNot { it.running }
        if (state.loaded && state.scripts.isEmpty()) {
            item(key = "empty") {
                EtaPreferenceGroup {
                    EtaPreference(
                        title = stringResource(R.string.scripts_empty),
                        onClick = { editor = ScriptDraft() },
                    )
                }
            }
        }
        if (running.isNotEmpty()) {
            item(key = "running-title") {
                EtaPreferenceGroupTitle(stringResource(R.string.scripts_group_running))
            }
            item(key = "running") {
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
