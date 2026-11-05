package io.github.mangi.eta.ui.screens.memory

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.AgentMemoryScoring
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentMemorySplitPartUi
import io.github.mangi.eta.ui.model.AgentMemoryTidyPreview
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text

/** 单个记忆档案的独立页面：信息、正文编辑、整理 / 拆分 / 合并 / 归档。 */
@Composable
internal fun MemoryArchiveScreen(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
) {
    val name = state.expandedFileName.orEmpty()
    val file = state.files.firstOrNull { it.name == name }
    val others = state.files.filter { it.name != name }
    var editing by rememberSaveable(name) { mutableStateOf(false) }
    var showArchiveConfirm by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showMerge by remember { mutableStateOf(false) }
    var mergeTarget by remember { mutableStateOf<String?>(null) }
    var splitSelection by remember(name) { mutableStateOf(emptySet<String>()) }
    var splitNames by remember(name) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val changed = state.fileDraft != state.fileSaved
    val preview = state.tidyPreview

    MiuixScaffoldPage(
        title = name,
        onBack = { onAction(AgentMemoryAction.NavigateBack) },
    ) {
        item(key = "info-title") { EtaPreferenceGroupTitle(stringResource(R.string.memory_archive_info_title)) }
        item(key = "info") {
            EtaPreferenceGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = buildString {
                            append(stringResource(R.string.memory_file_lines, file?.lineCount ?: 0))
                            append(" · ")
                            append(formatBytes(file?.byteSize ?: 0))
                            append(" · ")
                            append(
                                if ((file?.hitCount ?: 0) > 0) {
                                    stringResource(R.string.memory_section_hits, file?.hitCount ?: 0)
                                } else {
                                    stringResource(R.string.memory_section_never_used)
                                },
                            )
                            file?.idleDays?.takeIf { it > 0 }?.let { days ->
                                append(" · ").append(stringResource(R.string.memory_section_idle_days, days))
                            }
                            if (file?.cold == true) {
                                append(" · ").append(stringResource(R.string.memory_archive_info_cold))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote1,
                    )
                    if (file?.cold == true) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.memory_archive_info_cold),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                }
            }
        }

        item(key = "body-title") { EtaPreferenceGroupTitle(stringResource(R.string.memory_archive_body_title)) }
        item(key = "body") {
            EtaPreferenceGroup {
                Box(modifier = Modifier.fillMaxWidth().height(MemoryDocumentBodyHeight)) {
                    if (editing) {
                        MemoryEditor(
                            value = state.fileDraft,
                            enabled = !state.isSavingFile && !state.isTidying,
                            placeholder = name,
                            onValueChange = { onAction(AgentMemoryAction.FileDraftChanged(it)) },
                        )
                        Row(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                            MemoryBodyAction(
                                text = stringResource(R.string.memory_mode_preview),
                                enabled = true,
                                onClick = { editing = false },
                            )
                        }
                    } else {
                        MemoryPreview(
                            content = state.fileDraft,
                            placeholder = name,
                            onStartEditing = { if (!state.isSavingFile) editing = true },
                        )
                    }
                }
                MemorySaveBar(
                    visible = changed || state.isSavingFile,
                    overLimit = false,
                    saving = state.isSavingFile,
                    canSave = changed && !state.isSavingFile,
                    onDiscard = { onAction(AgentMemoryAction.FileDraftChanged(state.fileSaved)) },
                    onSave = { onAction(AgentMemoryAction.SaveFile) },
                )
                if (state.fileDraft.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        EtaTextButton(
                            text = stringResource(R.string.memory_clear),
                            enabled = !state.isSavingFile,
                            onClick = { showClearConfirm = true },
                        )
                    }
                }
            }
        }

        item(key = "actions-title") { EtaPreferenceGroupTitle(stringResource(R.string.memory_archive_actions_title)) }
        item(key = "actions") {
            EtaPreferenceGroup {
                ArchiveActionRow(
                    title = stringResource(R.string.memory_archive_action_tidy),
                    summary = stringResource(R.string.memory_archive_action_tidy_summary),
                    isFirst = true,
                    enabled = !state.isTidying && state.fileDraft.isNotBlank(),
                    onClick = { onAction(AgentMemoryAction.TidyFile) },
                )
                EtaPreferenceDivider(hasLeading = true)
                ArchiveActionRow(
                    title = stringResource(R.string.memory_archive_action_split),
                    summary = if (state.isSplitting) {
                        stringResource(R.string.memory_archive_split_running)
                    } else {
                        stringResource(R.string.memory_archive_action_split_summary)
                    },
                    enabled = !state.isSplitting && state.fileDraft.isNotBlank(),
                    onClick = { onAction(AgentMemoryAction.SplitFile(name)) },
                )
                EtaPreferenceDivider(hasLeading = true)
                ArchiveActionRow(
                    title = stringResource(R.string.memory_archive_action_merge),
                    summary = stringResource(R.string.memory_archive_action_merge_summary),
                    enabled = others.isNotEmpty() && !state.isMerging,
                    onClick = { showMerge = true },
                )
                EtaPreferenceDivider(hasLeading = true)
                ArchiveActionRow(
                    title = stringResource(R.string.memory_archive_action_archive),
                    summary = stringResource(R.string.memory_archive_action_archive_summary),
                    isLast = true,
                    enabled = !state.isArchiving,
                    destructive = true,
                    onClick = { showArchiveConfirm = true },
                )
            }
        }
    }

    if (preview != null) {
        TidyReviewDialog(
            preview = preview,
            saving = state.isSavingFile,
            onCancel = { onAction(AgentMemoryAction.TidyDiscarded) },
            onConfirm = { onAction(AgentMemoryAction.TidyConfirmed) },
        )
    }

    if (showClearConfirm) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_archive_clear_title),
            summary = stringResource(R.string.memory_archive_clear_message),
            onDismissRequest = { showClearConfirm = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_clear),
                destructive = true,
                onCancel = { showClearConfirm = false },
                onConfirm = {
                    showClearConfirm = false
                    onAction(AgentMemoryAction.FileDraftChanged(""))
                },
            )
        }
    }

    if (showArchiveConfirm) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_archive_files_confirm_title),
            summary = stringResource(R.string.memory_archive_files_confirm_message, 1),
            onDismissRequest = { showArchiveConfirm = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_archive_action_archive),
                destructive = true,
                confirmEnabled = !state.isArchiving,
                onCancel = { showArchiveConfirm = false },
                onConfirm = {
                    showArchiveConfirm = false
                    onAction(AgentMemoryAction.ArchiveFiles(listOf(name)))
                },
            )
        }
    }

    if (showMerge) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_archive_merge_title),
            summary = null,
            onDismissRequest = { showMerge = false },
        ) {
            Column {
                others.forEach { other ->
                    val picked = mergeTarget == other.name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { mergeTarget = if (picked) null else other.name }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = other.name,
                            modifier = Modifier.weight(1f),
                            color = if (picked) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                            style = MiuixTheme.textStyles.body2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.memory_file_lines, other.lineCount),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                MiuixDialogActions(
                    confirmText = stringResource(R.string.memory_merge_selected, 2),
                    confirmEnabled = mergeTarget != null && !state.isMerging,
                    onCancel = { showMerge = false },
                    onConfirm = {
                        val target = mergeTarget ?: return@MiuixDialogActions
                        showMerge = false
                        onAction(AgentMemoryAction.MergeFiles(listOf(target, name)))
                    },
                )
            }
        }
    }

    state.splitPreview?.let { parts ->
        SplitReviewDialog(
            parts = parts,
            deleteOriginal = state.splitDeleteOriginal,
            saving = state.isSavingFile,
            onRename = { index, value -> onAction(AgentMemoryAction.SplitRename(index, value)) },
            onToggle = { index -> onAction(AgentMemoryAction.SplitToggle(index)) },
            onDeleteOriginalChange = { onAction(AgentMemoryAction.SplitDeleteOriginal(it)) },
            onCancel = { onAction(AgentMemoryAction.SplitDiscarded) },
            onConfirm = { onAction(AgentMemoryAction.SplitConfirmed) },
        )
    }

    state.notice?.let { notice ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.ui_memory_b55ff5),
            summary = notice,
            onDismissRequest = { onAction(AgentMemoryAction.DismissNotice) },
        ) {
            EtaTextButton(
                text = stringResource(R.string.ui_knew_cb63c6),
                onClick = { onAction(AgentMemoryAction.DismissNotice) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 整理预览：先给统计与正文对比，确认后才写回。 */
@Composable
private fun TidyReviewDialog(
    preview: AgentMemoryTidyPreview,
    saving: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    var showAfter by rememberSaveable(preview.name) { mutableStateOf(true) }
    EtaWindowDialog(
        show = true,
        title = stringResource(R.string.memory_archive_tidy_title),
        summary = stringResource(
            R.string.memory_archive_tidy_summary,
            preview.beforeLines,
            formatBytes(preview.beforeBytes),
            preview.afterLines,
            formatBytes(preview.afterBytes),
        ),
        onDismissRequest = onCancel,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EtaTextButton(
                    text = stringResource(R.string.memory_archive_tidy_before),
                    enabled = true,
                    onClick = { showAfter = false },
                    modifier = Modifier.weight(1f),
                )
                EtaTextButton(
                    text = stringResource(R.string.memory_archive_tidy_after),
                    enabled = true,
                    onClick = { showAfter = true },
                    modifier = Modifier.weight(1f),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MemoryDocumentBodyHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = if (showAfter) preview.after else preview.before,
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.footnote1,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            MiuixDialogActions(
                confirmText = stringResource(if (saving) R.string.memory_saving else R.string.memory_archive_tidy_confirm),
                confirmEnabled = !saving,
                onCancel = onCancel,
                onConfirm = onConfirm,
            )
        }
    }
}

@Composable
private fun ArchiveActionRow(
    title: String,
    summary: String,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    EtaPreferenceGroupItem(isFirst = isFirst, isLast = isLast, hasLeading = true) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = when {
                        !enabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                        destructive -> MiuixTheme.colorScheme.error
                        else -> MiuixTheme.colorScheme.onSurface
                    },
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun SplitNameField(value: String, onValueChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .padding(start = 8.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.memory_archive_split_name_hint),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                        )
                    }
                    inner()
                }
            },
        )
    }
}



/** 拆分方案确认：逐份可改名、可取消勾选；确认后才写盘。 */
@Composable
private fun SplitReviewDialog(
    parts: List<AgentMemorySplitPartUi>,
    deleteOriginal: Boolean,
    saving: Boolean,
    onRename: (Int, String) -> Unit,
    onToggle: (Int) -> Unit,
    onDeleteOriginalChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val enabledCount = parts.count { it.enabled }
    EtaWindowDialog(
        show = true,
        title = stringResource(R.string.memory_archive_split_title),
        summary = stringResource(R.string.memory_archive_split_plan, enabledCount, parts.size),
        onDismissRequest = onCancel,
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(MemoryDocumentBodyHeight)) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    parts.forEachIndexed { index, part ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(index) }
                                .padding(start = 16.dp, end = 16.dp, top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (part.enabled) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
                                },
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.memory_file_lines, part.lineCount) +
                                    " · " + part.summary.take(40),
                                modifier = Modifier.weight(1f),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.footnote1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        SplitNameField(
                            value = part.name,
                            onValueChange = { onRename(index, it) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDeleteOriginalChange(!deleteOriginal) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (deleteOriginal) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
                    },
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.memory_archive_split_delete_original),
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.footnote1,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            MiuixDialogActions(
                confirmText = stringResource(if (saving) R.string.memory_saving else R.string.memory_archive_split_confirm),
                confirmEnabled = enabledCount > 0 && !saving,
                onCancel = onCancel,
                onConfirm = onConfirm,
            )
        }
    }
}
