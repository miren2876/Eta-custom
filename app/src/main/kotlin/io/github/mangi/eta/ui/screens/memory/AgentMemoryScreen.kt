package io.github.mangi.eta.ui.screens.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentMemorySectionUi
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import java.text.NumberFormat
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun AgentMemoryScreen(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var showArchiveDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var selectedTitles by remember(state.archiveCandidates) {
        mutableStateOf(state.archiveCandidates.map { it.title }.toSet())
    }

    MiuixScaffold(
        title = stringResource(R.string.ui_memory_b55ff5),
        onBack = { onAction(AgentMemoryAction.NavigateBack) },
    ) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            // 状态区与编辑器滚动分离。weight fill=false：内容少时只占自身高度，避免中部空档；
            // 空间不足（键盘弹出、横屏）时压缩为可滚动区域，编辑器保持完整可见
            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = sidePadding,
                    end = sidePadding,
                ),
                overscrollEffect = null,
            ) {
                item(key = "status-title") { EtaPreferenceGroupTitle(stringResource(R.string.ui_memory_b55ff5)) }
                item(key = "status-card") {
                    EtaPreferenceGroup(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        EtaSwitchPreference(
                            title = stringResource(R.string.ui_enable_memory_4b69b7),
                            summary = stringResource(R.string.ui_after_closing_no_memory_will_be_injected_and_the_mod_db3d23),
                            checked = state.enabled,
                            enabled = !state.isLoading,
                            onCheckedChange = { onAction(AgentMemoryAction.ToggleEnabled(it)) },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaPreference(
                            title = stringResource(R.string.ui_core_memory_injection_budget_48b5d5),
                            summary = stringResource(R.string.memory_budget_summary, formatNumber(state.coreBudgetChars)),
                        )
                    }
                }

                item(key = "capacity-title") { EtaPreferenceGroupTitle(stringResource(R.string.memory_capacity_title)) }
                item(key = "capacity-card") {
                    EtaPreferenceGroup(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        EtaPreference(
                            title = stringResource(R.string.memory_capacity_total_title),
                            summary = stringResource(
                                R.string.memory_capacity_total_summary,
                                formatBytes(state.draftBytes),
                                formatBytes(state.maxBytes),
                            ),
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaPreference(
                            title = stringResource(R.string.memory_capacity_core_title),
                            summary = if (state.coreBytes > 0) {
                                stringResource(R.string.memory_capacity_core_summary, formatBytes(state.coreBytes))
                            } else {
                                stringResource(R.string.memory_capacity_core_absent)
                            },
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaPreference(
                            title = stringResource(R.string.memory_capacity_sections_title),
                            summary = stringResource(
                                R.string.memory_capacity_sections_summary,
                                state.sections.size,
                                state.coldSectionCount,
                            ),
                        )
                        EtaPreferenceDivider(hasLeading = false)
                        EtaPreference(
                            title = stringResource(R.string.memory_capacity_archive_title),
                            summary = if (state.archiveSections > 0) {
                                stringResource(
                                    R.string.memory_capacity_archive_summary,
                                    state.archiveSections,
                                    formatBytes(state.archiveBytes),
                                )
                            } else {
                                stringResource(R.string.memory_archive_empty)
                            },
                        )
                    }
                }

                if (state.archiveCandidates.isNotEmpty()) {
                    item(key = "archive-title") {
                        EtaPreferenceGroupTitle(stringResource(R.string.memory_archive_suggest_title))
                    }
                    item(key = "archive-card") {
                        EtaPreferenceGroup(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            state.archiveCandidates.forEachIndexed { index, section ->
                                if (index > 0) EtaPreferenceDivider(hasLeading = false)
                                SectionRow(
                                    section = section,
                                    selected = section.title in selectedTitles,
                                    onClick = {
                                        selectedTitles = if (section.title in selectedTitles) {
                                            selectedTitles - section.title
                                        } else {
                                            selectedTitles + section.title
                                        }
                                    },
                                )
                            }
                        }
                    }
                    item(key = "archive-hint") {
                        Text(
                            text = stringResource(R.string.memory_archive_suggest_summary),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
                }

                if (state.sections.isNotEmpty()) {
                    item(key = "sections-title") {
                        EtaPreferenceGroupTitle(stringResource(R.string.memory_sections_title))
                    }
                    item(key = "sections-card") {
                        EtaPreferenceGroup(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                        ) {
                            state.sections.forEachIndexed { index, section ->
                                if (index > 0) EtaPreferenceDivider(hasLeading = false)
                                SectionRow(section = section, selected = null, onClick = null)
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = sidePadding)
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                if (state.archiveCandidates.isNotEmpty() || state.archiveSections > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.archiveCandidates.isNotEmpty()) {
                            EtaTextButton(
                                text = stringResource(R.string.memory_archive_selected, selectedTitles.size),
                                enabled = selectedTitles.isNotEmpty() && !state.isArchiving,
                                onClick = { showArchiveDialog = true },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (state.archiveSections > 0) {
                            EtaTextButton(
                                text = stringResource(R.string.memory_archive_restore),
                                enabled = !state.isArchiving,
                                onClick = { showRestoreDialog = true },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                EtaPreferenceGroupTitle("MEMORY.md")
                EtaCard(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        TextField(
                            value = state.draft,
                            onValueChange = { onAction(AgentMemoryAction.DraftChanged(it)) },
                            label = stringResource(R.string.ui_core_memory_user_name_long_term_preferences_aa6ff9),
                            useLabelAsPlaceholder = true,
                            enabled = !state.isLoading && !state.isSaving,
                            minLines = 6,
                            maxLines = 12,
                            textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val overLimit = state.draftBytes > state.maxBytes
                            Text(
                                text = when {
                                    overLimit -> stringResource(R.string.memory_over_limit)
                                    state.hasUnsavedChanges -> stringResource(R.string.memory_unsaved_changes)
                                    else -> ""
                                },
                                color = if (overLimit) {
                                    MiuixTheme.colorScheme.error
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                                style = MiuixTheme.textStyles.footnote1,
                            )
                            Text(
                                text = "${formatBytes(state.draftBytes)} / 1 MiB",
                                color = if (overLimit) {
                                    MiuixTheme.colorScheme.error
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                                style = MiuixTheme.textStyles.footnote1,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            EtaTextButton(
                                text = stringResource(R.string.ui_clear_84fcd7),
                                enabled = !state.isLoading && !state.isSaving && state.draft.isNotEmpty(),
                                onClick = { showClearDialog = true },
                                modifier = Modifier.weight(1f),
                            )
                            EtaTextButton(
                                text = if (state.isSaving) stringResource(R.string.memory_saving) else stringResource(R.string.memory_save),
                                enabled = state.canSave,
                                onClick = { onAction(AgentMemoryAction.Save) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.ui_clear_all_memory_a43bd3),
            summary = stringResource(R.string.ui_the_entire_contents_of_memory_md_will_be_deleted_and_83a8ac),
            onDismissRequest = { showClearDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_clear),
                destructive = true,
                confirmEnabled = !state.isSaving,
                onCancel = { showClearDialog = false },
                onConfirm = {
                    showClearDialog = false
                    onAction(AgentMemoryAction.Clear)
                },
            )
        }
    }

    if (showArchiveDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_archive_confirm_title),
            summary = stringResource(R.string.memory_archive_confirm_message, selectedTitles.size),
            onDismissRequest = { showArchiveDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_archive_selected, selectedTitles.size),
                confirmEnabled = selectedTitles.isNotEmpty() && !state.isArchiving,
                onCancel = { showArchiveDialog = false },
                onConfirm = {
                    showArchiveDialog = false
                    onAction(AgentMemoryAction.ArchiveSections(selectedTitles.toList()))
                },
            )
        }
    }

    if (showRestoreDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_restore_confirm_title),
            summary = stringResource(R.string.memory_restore_confirm_message),
            onDismissRequest = { showRestoreDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_archive_restore),
                confirmEnabled = !state.isArchiving,
                onCancel = { showRestoreDialog = false },
                onConfirm = {
                    showRestoreDialog = false
                    onAction(AgentMemoryAction.RestoreArchive)
                },
            )
        }
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

@Composable
private fun SectionRow(
    section: AgentMemorySectionUi,
    selected: Boolean?,
    onClick: (() -> Unit)?,
) {
    val usageLabel = when {
        section.hitCount > 0 -> stringResource(R.string.memory_section_hits, section.hitCount)
        else -> stringResource(R.string.memory_section_never_used)
    }
    val idleLabel = section.idleDays?.takeIf { it > 0 }
        ?.let { days -> " · " + stringResource(R.string.memory_section_idle_days, days) }
        .orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.title,
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.memory_section_meta, section.lines, usageLabel) + idleLabel,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected == true) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

private fun formatBytes(bytes: Int): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "%.2f MiB".format(bytes / (1_024.0 * 1_024.0))
}

private fun formatNumber(value: Int): String = NumberFormat.getIntegerInstance().format(value)
