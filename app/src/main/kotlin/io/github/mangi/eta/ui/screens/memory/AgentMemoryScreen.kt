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
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import io.github.mangi.eta.ui.model.AgentMemoryFileUi
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
    var showUnarchiveDialog by remember { mutableStateOf(false) }
    // 章节分区默认展开（最常看），归档分区默认收起。
    var sectionsExpanded by remember { mutableStateOf(true) }
    var archiveExpanded by remember { mutableStateOf(false) }
    var selectionScope by remember { mutableStateOf<MemorySelection?>(null) }
    var sectionSelection by remember { mutableStateOf(emptySet<String>()) }
    var archiveSelection by remember { mutableStateOf(emptySet<String>()) }
    // 「建议归档」区块：候选默认全勾上，确认归档走同一套动作。
    var suggestedSelection by remember(state.archiveCandidates) {
        mutableStateOf(state.archiveCandidates.map { it.rawTitle }.toSet())
    }
    var pendingArchiveTitles by remember { mutableStateOf(emptyList<String>()) }
    // 离开记忆页（含退出设置）时收起「撤销归档」入口。
    DisposableEffect(Unit) {
        onDispose { onAction(AgentMemoryAction.ClearUndo) }
    }
    val exitSelection = {
        selectionScope = null
        sectionSelection = emptySet()
        archiveSelection = emptySet()
    }
    // MEMORY.md 编辑器默认收起：页面主体留给章节列表，点标题行再展开编辑。
    var editorExpanded by remember { mutableStateOf(false) }
    // 核心记忆（独立文件）同样是折叠编辑器。
    var profileExpanded by remember { mutableStateOf(false) }
    var workingExpanded by remember { mutableStateOf(false) }
    var showClearProfileDialog by remember { mutableStateOf(false) }
    var showClearWorkingDialog by remember { mutableStateOf(false) }

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

                item(key = "profile-title") { EtaPreferenceGroupTitle(stringResource(R.string.memory_profile_title)) }
                item(key = "profile-card") {
                    CoreEditorCard(
                        title = stringResource(R.string.memory_profile_title),
                        summary = stringResource(R.string.memory_profile_summary),
                        draft = state.profileDraft,
                        saved = state.profileSaved,
                        expanded = profileExpanded,
                        enabled = !state.isLoading && !state.isSavingCore,
                        saving = state.isSavingCore,
                        onToggle = { profileExpanded = !profileExpanded },
                        onDraftChange = { onAction(AgentMemoryAction.ProfileDraftChanged(it)) },
                        onClear = { showClearProfileDialog = true },
                        onSave = { onAction(AgentMemoryAction.SaveProfile) },
                    )
                }

                item(key = "working-title") { EtaPreferenceGroupTitle(stringResource(R.string.memory_working_title)) }
                item(key = "working-card") {
                    CoreEditorCard(
                        title = stringResource(R.string.memory_working_title),
                        summary = stringResource(R.string.memory_working_summary),
                        draft = state.workingDraft,
                        saved = state.workingSaved,
                        expanded = workingExpanded,
                        enabled = !state.isLoading && !state.isSavingCore,
                        saving = state.isSavingCore,
                        onToggle = { workingExpanded = !workingExpanded },
                        onDraftChange = { onAction(AgentMemoryAction.WorkingDraftChanged(it)) },
                        onClear = { showClearWorkingDialog = true },
                        onSave = { onAction(AgentMemoryAction.SaveWorking) },
                    )
                }

                item(key = "files-title") { EtaPreferenceGroupTitle(stringResource(R.string.memory_files_title)) }
                item(key = "files-card") {
                    EtaPreferenceGroup(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        if (state.files.isEmpty()) {
                            SectionHint(text = stringResource(R.string.memory_files_empty))
                        } else {
                            state.files.forEachIndexed { index, file ->
                                if (index > 0) EtaPreferenceDivider(hasLeading = true)
                                val expanded = state.expandedFileName == file.name
                                FileRow(
                                    file = file,
                                    expanded = expanded,
                                    onClick = {
                                        if (expanded) {
                                            onAction(AgentMemoryAction.CollapseFile)
                                        } else {
                                            onAction(AgentMemoryAction.ExpandFile(file.name))
                                        }
                                    },
                                )
                                if (expanded) SectionBody(text = state.expandedFileText)
                            }
                        }
                    }
                }
                if (state.lastArchivedTitles.isNotEmpty()) {
                    item(key = "undo-card") {
                        EtaPreferenceGroup(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.memory_archive_undo_hint,
                                        state.lastArchivedTitles.size,
                                    ),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.body2,
                                    modifier = Modifier.weight(1f),
                                )
                                EtaTextButton(
                                    text = stringResource(R.string.memory_archive_undo),
                                    enabled = !state.isArchiving,
                                    onClick = { onAction(AgentMemoryAction.UndoArchive) },
                                )
                            }
                        }
                    }
                }

                item(key = "sections-title") { EtaPreferenceGroupTitle(stringResource(R.string.memory_sections_title)) }
                item(key = "sections-card") {
                    EtaPreferenceGroup(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        ExpandableHeader(
                            summary = stringResource(
                                R.string.memory_capacity_sections_summary,
                                state.sections.size,
                                state.coldSectionCount,
                            ),
                            expanded = sectionsExpanded,
                            onToggle = { sectionsExpanded = !sectionsExpanded },
                        )
                        if (sectionsExpanded) {
                            EtaPreferenceDivider(hasLeading = false)
                            if (state.sections.isEmpty()) {
                                SectionHint(text = stringResource(R.string.memory_sections_empty))
                            } else {
                                state.sections.forEachIndexed { index, section ->
                                    if (index > 0) EtaPreferenceDivider(hasLeading = true)
                                    val selecting = selectionScope == MemorySelection.Sections
                                    val expanded = !selecting && state.expandedSectionTitle == section.rawTitle
                                    SectionRow(
                                        section = section,
                                        selected = if (selecting) section.rawTitle in sectionSelection else null,
                                        expanded = if (selecting) null else expanded,
                                        onClick = {
                                            if (selecting) {
                                                sectionSelection = sectionSelection.toggle(section.rawTitle)
                                            } else if (expanded) {
                                                onAction(AgentMemoryAction.CollapseSection)
                                            } else {
                                                onAction(AgentMemoryAction.ExpandSection(section.rawTitle))
                                            }
                                        },
                                        onLongClick = {
                                            selectionScope = MemorySelection.Sections
                                            sectionSelection = setOf(section.rawTitle)
                                        },
                                    )
                                    if (expanded) SectionBody(text = state.expandedSectionText)
                                }
                            }
                            if (selectionScope == MemorySelection.Sections) {
                                EtaPreferenceDivider(hasLeading = false)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    EtaTextButton(
                                        text = stringResource(R.string.memory_selection_cancel),
                                        onClick = { exitSelection() },
                                        modifier = Modifier.weight(1f),
                                    )
                                    EtaTextButton(
                                        text = stringResource(R.string.memory_archive_selected, sectionSelection.size),
                                        enabled = sectionSelection.isNotEmpty() && !state.isArchiving,
                                        onClick = {
                                            pendingArchiveTitles = sectionSelection.toList()
                                            showArchiveDialog = true
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            } else {
                                SectionHint(text = stringResource(R.string.memory_section_select_hint))
                            }
                        }
                    }
                }

                if (state.archiveCandidates.isNotEmpty()) {
                    item(key = "suggest-title") {
                        EtaPreferenceGroupTitle(stringResource(R.string.memory_archive_suggest_title))
                    }
                    item(key = "suggest-card") {
                        EtaPreferenceGroup(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                        ) {
                            state.archiveCandidates.forEachIndexed { index, section ->
                                if (index > 0) EtaPreferenceDivider(hasLeading = true)
                                SectionRow(
                                    section = section,
                                    selected = section.rawTitle in suggestedSelection,
                                    expanded = null,
                                    onClick = {
                                        suggestedSelection = suggestedSelection.toggle(section.rawTitle)
                                    },
                                )
                            }
                            EtaPreferenceDivider(hasLeading = false)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                EtaTextButton(
                                    text = stringResource(R.string.memory_archive_selected, suggestedSelection.size),
                                    enabled = suggestedSelection.isNotEmpty() && !state.isArchiving,
                                    onClick = {
                                        pendingArchiveTitles = suggestedSelection.toList()
                                        showArchiveDialog = true
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                EtaTextButton(
                                    text = stringResource(R.string.memory_archive_all),
                                    enabled = !state.isArchiving,
                                    onClick = {
                                        val all = state.archiveCandidates.map { it.rawTitle }
                                        suggestedSelection = all.toSet()
                                        pendingArchiveTitles = all
                                        showArchiveDialog = true
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                item(key = "archive-title") { EtaPreferenceGroupTitle(stringResource(R.string.memory_capacity_archive_title)) }
                item(key = "archive-card") {
                    EtaPreferenceGroup(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        ExpandableHeader(
                            summary = if (state.archiveSections > 0) {
                                stringResource(
                                    R.string.memory_capacity_archive_summary,
                                    state.archiveSections,
                                    formatBytes(state.archiveBytes),
                                )
                            } else {
                                stringResource(R.string.memory_archive_empty)
                            },
                            expanded = archiveExpanded,
                            onToggle = { archiveExpanded = !archiveExpanded },
                        )
                        if (archiveExpanded) {
                            EtaPreferenceDivider(hasLeading = false)
                            if (state.archiveList.isEmpty()) {
                                SectionHint(text = stringResource(R.string.memory_archive_empty))
                            } else {
                                state.archiveList.forEachIndexed { index, section ->
                                    if (index > 0) EtaPreferenceDivider(hasLeading = true)
                                    val selecting = selectionScope == MemorySelection.Archive
                                    SectionRow(
                                        section = section,
                                        selected = if (selecting) section.rawTitle in archiveSelection else null,
                                        expanded = null,
                                        onClick = {
                                            if (selecting) {
                                                archiveSelection = archiveSelection.toggle(section.rawTitle)
                                            }
                                        },
                                        onLongClick = {
                                            selectionScope = MemorySelection.Archive
                                            archiveSelection = setOf(section.rawTitle)
                                        },
                                    )
                                }
                            }
                            if (selectionScope == MemorySelection.Archive) {
                                EtaPreferenceDivider(hasLeading = false)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    EtaTextButton(
                                        text = stringResource(R.string.memory_selection_cancel),
                                        onClick = { exitSelection() },
                                        modifier = Modifier.weight(1f),
                                    )
                                    EtaTextButton(
                                        text = stringResource(R.string.memory_unarchive_selected, archiveSelection.size),
                                        enabled = archiveSelection.isNotEmpty() && !state.isArchiving,
                                        onClick = { showUnarchiveDialog = true },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            } else if (state.archiveList.isNotEmpty()) {
                                SectionHint(text = stringResource(R.string.memory_archive_select_hint))
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
                EtaPreferenceGroupTitle("MEMORY.md")
                EtaCard(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editorExpanded = !editorExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "MEMORY.md",
                                    color = MiuixTheme.colorScheme.onSurface,
                                    style = MiuixTheme.textStyles.body1,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (state.hasUnsavedChanges) stringResource(R.string.memory_unsaved_changes) else "",
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.footnote1,
                                )
                            }
                            Text(
                                text = "${formatBytes(state.draftBytes)} / ${formatBytes(state.maxBytes)}",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.footnote1,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (editorExpanded) {
                                    Icons.Rounded.KeyboardArrowUp
                                } else {
                                    Icons.Rounded.KeyboardArrowDown
                                },
                                contentDescription = stringResource(
                                    if (editorExpanded) {
                                        R.string.memory_editor_collapse
                                    } else {
                                        R.string.memory_editor_expand
                                    },
                                ),
                                modifier = Modifier.size(20.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        if (editorExpanded) {
                            Spacer(modifier = Modifier.height(12.dp))
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

    if (showClearProfileDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_profile_clear_title),
            summary = stringResource(R.string.memory_profile_clear_message),
            onDismissRequest = { showClearProfileDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_clear),
                destructive = true,
                confirmEnabled = !state.isSavingCore,
                onCancel = { showClearProfileDialog = false },
                onConfirm = {
                    showClearProfileDialog = false
                    onAction(AgentMemoryAction.ClearProfile)
                },
            )
        }
    }

    if (showClearWorkingDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_working_clear_title),
            summary = stringResource(R.string.memory_working_clear_message),
            onDismissRequest = { showClearWorkingDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_clear),
                destructive = true,
                confirmEnabled = !state.isSavingCore,
                onCancel = { showClearWorkingDialog = false },
                onConfirm = {
                    showClearWorkingDialog = false
                    onAction(AgentMemoryAction.ClearWorking)
                },
            )
        }
    }

    if (showArchiveDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_archive_confirm_title),
            summary = stringResource(R.string.memory_archive_confirm_message, pendingArchiveTitles.size),
            onDismissRequest = { showArchiveDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_archive_selected, pendingArchiveTitles.size),
                confirmEnabled = pendingArchiveTitles.isNotEmpty() && !state.isArchiving,
                onCancel = { showArchiveDialog = false },
                onConfirm = {
                    showArchiveDialog = false
                    onAction(AgentMemoryAction.ArchiveSections(pendingArchiveTitles))
                    exitSelection()
                },
            )
        }
    }

    if (showUnarchiveDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_unarchive_confirm_title),
            summary = stringResource(R.string.memory_unarchive_confirm_message, archiveSelection.size),
            onDismissRequest = { showUnarchiveDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_unarchive_selected, archiveSelection.size),
                confirmEnabled = archiveSelection.isNotEmpty() && !state.isArchiving,
                onCancel = { showUnarchiveDialog = false },
                onConfirm = {
                    showUnarchiveDialog = false
                    onAction(AgentMemoryAction.UnarchiveSections(archiveSelection.toList()))
                    exitSelection()
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
    expanded: Boolean?,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
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
            .then(
                when {
                    onClick != null && onLongClick != null -> Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )

                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (selected) MiuixTheme.colorScheme.primary else Color.Transparent)
                    .border(
                        width = 1.5.dp,
                        color = if (selected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.White,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
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
        if (expanded != null) {
            Icon(
                imageVector = if (expanded) {
                    Icons.Rounded.KeyboardArrowUp
                } else {
                    Icons.Rounded.KeyboardArrowDown
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 展开后的章节正文：只读、等宽字体，便于看 Markdown 结构。 */
@Composable
private fun SectionBody(text: String) {
    Text(
        text = text.ifBlank { "…" },
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    )
}

private fun formatBytes(bytes: Int): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "%.2f MiB".format(bytes / (1_024.0 * 1_024.0))
}

private fun formatNumber(value: Int): String = NumberFormat.getIntegerInstance().format(value)

/** 分区标题行：点一下展开 / 收起该分区内容。 */
@Composable
private fun ExpandableHeader(
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = summary,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) {
                Icons.Rounded.KeyboardArrowUp
            } else {
                Icons.Rounded.KeyboardArrowDown
            },
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 区块内的小字提示。 */
@Composable
private fun SectionHint(text: String) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** 章节列表与归档列表各自的多选范围。 */
private enum class MemorySelection { Sections, Archive }

private fun Set<String>.toggle(title: String): Set<String> = if (title in this) this - title else this + title

/** 核心记忆的一节（用户画像 / 工作记忆）：折叠 + 编辑 + 保存。 */
@Composable
private fun CoreEditorCard(
    title: String,
    summary: String,
    draft: String,
    saved: String,
    expanded: Boolean,
    enabled: Boolean,
    saving: Boolean,
    onToggle: () -> Unit,
    onDraftChange: (String) -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
) {
    EtaPreferenceGroup(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (draft != saved) stringResource(R.string.memory_unsaved_changes) else summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                )
            }
            Text(
                text = formatBytes(draft.toByteArray(Charsets.UTF_8).size),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) {
                    Icons.Rounded.KeyboardArrowUp
                } else {
                    Icons.Rounded.KeyboardArrowDown
                },
                contentDescription = stringResource(
                    if (expanded) R.string.memory_editor_collapse else R.string.memory_editor_expand,
                ),
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (expanded) {
            EtaPreferenceDivider(hasLeading = false)
            Column(modifier = Modifier.padding(16.dp)) {
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    label = title,
                    useLabelAsPlaceholder = true,
                    enabled = enabled,
                    minLines = 5,
                    maxLines = 12,
                    textStyle = MiuixTheme.textStyles.body2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EtaTextButton(
                        text = stringResource(R.string.ui_clear_84fcd7),
                        enabled = !saving && draft.isNotEmpty(),
                        onClick = onClear,
                        modifier = Modifier.weight(1f),
                    )
                    EtaTextButton(
                        text = if (saving) {
                            stringResource(R.string.memory_saving)
                        } else {
                            stringResource(R.string.memory_save)
                        },
                        enabled = !saving && draft != saved,
                        onClick = onSave,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

/** 记忆档案行：点一下展开看内容。 */
@Composable
private fun FileRow(
    file: AgentMemoryFileUi,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.memory_file_meta, file.lineCount, formatBytes(file.byteSize)),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
            )
        }
        Icon(
            imageVector = if (expanded) {
                Icons.Rounded.KeyboardArrowUp
            } else {
                Icons.Rounded.KeyboardArrowDown
            },
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
