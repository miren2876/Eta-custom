package io.github.mangi.eta.ui.screens.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
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
import io.github.mangi.eta.data.repository.AgentMemoryStore
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentMemoryEntryUi
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.squircle.squircleSurface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.RowScope
import io.github.mangi.eta.ui.components.StatusError

@Composable
internal fun AgentMemoryScreen(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
) {
    var showArchiveDialog by remember { mutableStateOf(false) }
    var showUnarchiveDialog by remember { mutableStateOf(false) }
    // 章节分区默认展开（最常看），归档分区默认收起。
    var sectionsExpanded by remember { mutableStateOf(true) }
    var archiveExpanded by remember { mutableStateOf(false) }
    var selectionScope by remember { mutableStateOf<MemorySelection?>(null) }
    var sectionSelection by remember { mutableStateOf(emptySet<String>()) }
    var archiveSelection by remember { mutableStateOf(emptySet<String>()) }
    var fileSelection by remember { mutableStateOf(emptySet<String>()) }
    var pendingArchiveFileNames by remember { mutableStateOf(emptyList<String>()) }
    var showArchiveFilesDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    // 「建议归档」区块：候选默认全勾上，确认归档走同一套动作。
    var suggestedSelection by remember(state.coldFiles) {
        mutableStateOf(state.coldFiles.map { it.name }.toSet())
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
    // 核心记忆（独立文件）同样是折叠编辑器。
    var profileExpanded by remember { mutableStateOf(false) }
    var workingExpanded by remember { mutableStateOf(false) }
    var filesExpanded by remember { mutableStateOf(false) }
    var entriesExpanded by remember { mutableStateOf(false) }
    // 三个长列表各自分页，页码互不影响，进出页面保持。
    var entriesPage by rememberSaveable { mutableIntStateOf(0) }
    var filesPage by rememberSaveable { mutableIntStateOf(0) }
    var archivePage by rememberSaveable { mutableIntStateOf(0) }
    val entriesPageCount = memoryPageCount(state.entries.size)
    val filesPageCount = memoryPageCount(state.files.size)
    val archivePageCount = memoryPageCount(state.archiveList.size)
    // 刷新后页数变少时把页码夹回最后一页，避免停在空白页。
    LaunchedEffect(entriesPageCount) {
        if (entriesPage > entriesPageCount - 1) entriesPage = entriesPageCount - 1
    }
    LaunchedEffect(filesPageCount) {
        if (filesPage > filesPageCount - 1) filesPage = filesPageCount - 1
    }
    LaunchedEffect(archivePageCount) {
        if (archivePage > archivePageCount - 1) archivePage = archivePageCount - 1
    }
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
                    }
                }

                item(key = "profile-title") { EtaPreferenceGroupTitle(stringResource(R.string.memory_profile_title)) }
                item(key = "profile-card") {
                    CoreEditorCard(
                        title = stringResource(R.string.memory_profile_title),
                        summary = stringResource(R.string.memory_profile_summary),
                        draft = state.profileDraft,
                        saved = state.profileSaved,
                        limitBytes = AgentMemoryStore.PROFILE_MAX_BYTES,
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
                        limitBytes = AgentMemoryStore.WORKING_MAX_BYTES,
                        expanded = workingExpanded,
                        enabled = !state.isLoading && !state.isSavingCore,
                        saving = state.isSavingCore,
                        onToggle = { workingExpanded = !workingExpanded },
                        onDraftChange = { onAction(AgentMemoryAction.WorkingDraftChanged(it)) },
                        onClear = { showClearWorkingDialog = true },
                        onSave = { onAction(AgentMemoryAction.SaveWorking) },
                    )
                }

                item(key = "capacity") {
                    // 压缩器每次跑完会改这个状态；跟着它一起刷新容量卡片。
                    val compactionPending = io.github.mangi.eta.agent.runtime.AgentMemoryCompactor
                        .pendingDecision.collectAsState().value
                    val usage = remember(state.entries.size, state.files.size, compactionPending) {
                        runCatching { io.github.mangi.eta.agent.runtime.AgentMemoryCompactor.usage() }.getOrNull()
                    }
                    if (usage != null) {
                        val context = LocalContext.current
                        val resources = LocalResources.current
                        EtaPreferenceGroup(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                        ) {
                            EtaPreference(
                                title = stringResource(R.string.memory_compact_title),
                                summary = stringResource(
                                    R.string.memory_compact_summary,
                                    formatBytes(usage.profileBytes),
                                    formatBytes(usage.entryBytes),
                                    formatBytes(usage.budgetChars),
                                    (usage.ratio * 100).toInt(),
                                ),
                                titleColor = when {
                                    usage.skippedCount > 0 -> top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(
                                        color = MiuixTheme.colorScheme.error,
                                    )
                                    usage.ratio >= 0.9f -> top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                    else -> top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor()
                                },
                            )
                            if (usage.totalEntryCount > 0 && usage.entryCount == 0) {
                                EtaPreferenceDivider()
                                SectionHint(text = stringResource(R.string.memory_compact_profile_full))
                            } else if (usage.skippedCount > 0) {
                                EtaPreferenceDivider()
                                SectionHint(
                                    text = stringResource(R.string.memory_compact_skipped, usage.skippedCount),
                                )
                            } else if (usage.ratio >= 0.9f) {
                                EtaPreferenceDivider()
                                SectionHint(
                                    text = stringResource(
                                        R.string.memory_compact_warning,
                                        (usage.ratio * 100).toInt(),
                                    ),
                                )
                            }
                            if (compactionPending) {
                                EtaPreferenceDivider()
                                SectionHint(text = stringResource(R.string.memory_compact_pending_text))
                            }
                            EtaPreferenceDivider()
                            EtaPreference(
                                title = stringResource(R.string.memory_compact_now),
                                onClick = {
                                    val started = io.github.mangi.eta.agent.runtime.AgentMemoryCompactor.request(
                                        reason = "manual",
                                        manual = true,
                                    )
                                    val message = resources.getString(
                                        when {
                                            started -> R.string.memory_compact_started
                                            !usage.needsCompaction -> R.string.memory_compact_not_needed
                                            else -> R.string.memory_compact_throttled
                                        },
                                    )
                                    android.widget.Toast.makeText(
                                        context,
                                        message,
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    }
                }

                item(key = "entries-title") { EtaPreferenceGroupTitle(stringResource(R.string.memory_entries_title)) }
                item(key = "entries-card") {
                    EtaPreferenceGroup(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        ExpandableHeader(
                            title = stringResource(R.string.memory_entries_title),
                            summary = stringResource(R.string.memory_entries_summary, state.entries.size),
                            expanded = entriesExpanded,
                            onToggle = { entriesExpanded = !entriesExpanded },
                        )
                        if (entriesExpanded) {
                            EtaPreferenceDivider(hasLeading = false)
                            if (state.entries.isEmpty()) {
                                SectionHint(text = stringResource(R.string.memory_entries_empty))
                            } else {
                                val page = entriesPage.coerceIn(0, entriesPageCount - 1)
                                state.entries.drop(page * MEMORY_PAGE_SIZE).take(MEMORY_PAGE_SIZE)
                                    .forEachIndexed { index, entry ->
                                        if (page * MEMORY_PAGE_SIZE + index > 0) {
                                            EtaPreferenceDivider(hasLeading = true)
                                        }
                                        EntryRow(entry = entry)
                                    }
                                MemoryPagerBar(
                                    page = page,
                                    pageCount = entriesPageCount,
                                    onPrev = { entriesPage = page - 1 },
                                    onNext = { entriesPage = page + 1 },
                                )
                            }
                        }
                    }
                }

                item(key = "files-title") { EtaPreferenceGroupTitle(stringResource(R.string.memory_files_title)) }
                item(key = "files-card") {
                    EtaPreferenceGroup(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        ExpandableHeader(
                            title = stringResource(R.string.memory_files_title),
                            summary = stringResource(
                                R.string.memory_files_summary,
                                state.files.size,
                                state.coldFileCount,
                            ),
                            expanded = filesExpanded,
                            onToggle = { filesExpanded = !filesExpanded },
                            trailing = "${formatBytes(state.files.sumOf { it.byteSize })} / " +
                                formatBytes(AgentMemoryStore.FILE_MAX_BYTES),
                        )
                        if (filesExpanded) {
                            EtaPreferenceDivider(hasLeading = false)
                            if (state.files.isEmpty()) {
                                SectionHint(text = stringResource(R.string.memory_files_empty))
                            } else {
                                val page = filesPage.coerceIn(0, filesPageCount - 1)
                                state.files.drop(page * MEMORY_PAGE_SIZE).take(MEMORY_PAGE_SIZE)
                                    .forEachIndexed { index, file ->
                                    if (page * MEMORY_PAGE_SIZE + index > 0) EtaPreferenceDivider(hasLeading = true)
                                    val selecting = selectionScope == MemorySelection.Files
                                    FileRow(
                                        file = file,
                                        selected = if (selecting) file.name in fileSelection else null,
                                        onClick = {
                                            if (selecting) {
                                                fileSelection = fileSelection.toggle(file.name)
                                            } else {
                                                onAction(AgentMemoryAction.ExpandFile(file.name))
                                            }
                                        },
                                        onLongClick = {
                                            selectionScope = MemorySelection.Files
                                            fileSelection = setOf(file.name)
                                        },
                                    )
                                }
                                MemoryPagerBar(
                                    page = page,
                                    pageCount = filesPageCount,
                                    onPrev = { filesPage = page - 1 },
                                    onNext = { filesPage = page + 1 },
                                )
                            }
                            if (selectionScope == MemorySelection.Files) {
                                EtaPreferenceDivider(hasLeading = false)
                                MemorySelectionActionsRow(
                                    cancel = {
                                        MemoryActionChip(
                                            text = stringResource(R.string.memory_selection_cancel),
                                            onClick = { exitSelection() },
                                            plain = true,
                                        )
                                    },
                                ) {
                                    MemoryActionChip(
                                        text = stringResource(R.string.memory_archive_selected, fileSelection.size),
                                        modifier = Modifier.weight(1f),
                                        enabled = fileSelection.isNotEmpty() && !state.isArchiving,
                                        danger = true,
                                        onClick = {
                                            pendingArchiveFileNames = fileSelection.toList()
                                            showArchiveFilesDialog = true
                                        },
                                    )
                                    MemoryActionChip(
                                        text = stringResource(R.string.memory_merge_selected, fileSelection.size),
                                        modifier = Modifier.weight(1f),
                                        enabled = fileSelection.size >= 2 && !state.isMerging,
                                        emphasized = true,
                                        onClick = { showMergeDialog = true },
                                    )
                                }
                            } else if (state.files.isNotEmpty()) {
                                SectionHint(text = stringResource(R.string.memory_files_select_hint))
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

                if (state.coldFiles.isNotEmpty()) {
                    item(key = "suggest-title") {
                        EtaPreferenceGroupTitle(stringResource(R.string.memory_archive_suggest_title))
                    }
                    item(key = "suggest-card") {
                        EtaPreferenceGroup(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                        ) {
                            state.coldFiles.forEachIndexed { index, file ->
                                if (index > 0) EtaPreferenceDivider(hasLeading = true)
                                FileRow(
                                    file = file,
                                    selected = file.name in suggestedSelection,
                                    onClick = {
                                        suggestedSelection = suggestedSelection.toggle(file.name)
                                    },
                                )
                            }
                            EtaPreferenceDivider(hasLeading = false)
                            MemorySelectionActionsRow {
                                MemoryActionChip(
                                    text = stringResource(R.string.memory_archive_selected, suggestedSelection.size),
                                    modifier = Modifier.weight(1f),
                                    enabled = suggestedSelection.isNotEmpty() && !state.isArchiving,
                                    danger = true,
                                    onClick = {
                                        pendingArchiveFileNames = suggestedSelection.toList()
                                        showArchiveFilesDialog = true
                                    },
                                )
                                MemoryActionChip(
                                    text = stringResource(R.string.memory_archive_all),
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.isArchiving,
                                    danger = true,
                                    onClick = {
                                        val all = state.coldFiles.map { it.name }
                                        suggestedSelection = all.toSet()
                                        pendingArchiveFileNames = all
                                        showArchiveFilesDialog = true
                                    },
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
                            title = stringResource(R.string.memory_archive_files_title),
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
                                val page = archivePage.coerceIn(0, archivePageCount - 1)
                                state.archiveList.drop(page * MEMORY_PAGE_SIZE).take(MEMORY_PAGE_SIZE)
                                    .forEachIndexed { index, section ->
                                    if (page * MEMORY_PAGE_SIZE + index > 0) EtaPreferenceDivider(hasLeading = true)
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
                                MemoryPagerBar(
                                    page = page,
                                    pageCount = archivePageCount,
                                    onPrev = { archivePage = page - 1 },
                                    onNext = { archivePage = page + 1 },
                                )
                            }
                            if (selectionScope == MemorySelection.Archive) {
                                EtaPreferenceDivider(hasLeading = false)
                                MemorySelectionActionsRow(
                                    cancel = {
                                        MemoryActionChip(
                                            text = stringResource(R.string.memory_selection_cancel),
                                            onClick = { exitSelection() },
                                            plain = true,
                                        )
                                    },
                                ) {
                                    MemoryActionChip(
                                        text = stringResource(R.string.memory_unarchive_selected, archiveSelection.size),
                                        modifier = Modifier.weight(1f),
                                        enabled = archiveSelection.isNotEmpty() && !state.isArchiving,
                                        danger = true,
                                        onClick = { showUnarchiveDialog = true },
                                    )
                                }
                            } else if (state.archiveList.isNotEmpty()) {
                                SectionHint(text = stringResource(R.string.memory_archive_select_hint))
                            }
                        }
                    }
                }
            }

        }
    }

    if (showMergeDialog) {
        val mergeNames = state.files.filter { it.name in fileSelection }.map { it.name }
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_merge_confirm_title),
            summary = stringResource(
                R.string.memory_merge_confirm_message,
                mergeNames.size,
                mergeNames.firstOrNull().orEmpty(),
            ),
            onDismissRequest = { showMergeDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_merge_selected, mergeNames.size),
                confirmEnabled = mergeNames.size >= 2 && !state.isMerging,
                onCancel = { showMergeDialog = false },
                onConfirm = {
                    showMergeDialog = false
                    onAction(AgentMemoryAction.MergeFiles(mergeNames))
                    exitSelection()
                },
            )
        }
    }

    if (showArchiveFilesDialog) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.memory_archive_files_confirm_title),
            summary = stringResource(R.string.memory_archive_files_confirm_message, pendingArchiveFileNames.size),
            onDismissRequest = { showArchiveFilesDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_archive_selected, pendingArchiveFileNames.size),
                confirmEnabled = pendingArchiveFileNames.isNotEmpty() && !state.isArchiving,
                onCancel = { showArchiveFilesDialog = false },
                onConfirm = {
                    showArchiveFilesDialog = false
                    onAction(AgentMemoryAction.ArchiveFiles(pendingArchiveFileNames))
                    exitSelection()
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

internal fun formatBytes(bytes: Int): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "%.2f MiB".format(bytes / (1_024.0 * 1_024.0))
}

private fun formatNumber(value: Int): String = NumberFormat.getIntegerInstance().format(value)

/** 分区标题行：点一下展开 / 收起该分区内容。 */
/** 分区标题：两行布局（标题 + 说明），右侧可带占用与展开箭头。 */
@Composable
private fun ExpandableHeader(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: String? = null,
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
                text = summary,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
            )
        }
        if (trailing != null) {
            Text(
                text = trailing,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
            )
            Spacer(modifier = Modifier.width(4.dp))
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

/** 工作记忆条目：正文 + 重要度 / EI / 引用次数 / 距今时间。 */
@Composable
private fun EntryRow(entry: AgentMemoryEntryUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = entry.text,
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body2,
        )
        Text(
            text = listOfNotNull(
                stringResource(R.string.memory_entry_meta, entry.importance, entry.eiLabel),
                if (entry.hitCount > 0) stringResource(R.string.memory_entry_hits, entry.hitCount) else null,
                entry.idleDays?.takeIf { days -> days > 0 }?.let { days ->
                    stringResource(R.string.memory_entry_idle_days, days)
                },
            ).joinToString(" · "),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 章节列表与归档列表各自的多选范围。 */
private enum class MemorySelection { Sections, Archive, Files }

private fun Set<String>.toggle(title: String): Set<String> = if (title in this) this - title else this + title

/** 核心记忆的一节（用户画像 / 工作记忆）：折叠 + 编辑 + 保存。 */
@Composable
private fun CoreEditorCard(
    title: String,
    summary: String,
    draft: String,
    saved: String,
    limitBytes: Int,
    expanded: Boolean,
    enabled: Boolean,
    saving: Boolean,
    onToggle: () -> Unit,
    onDraftChange: (String) -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
) {
    var editing by rememberSaveable(title) { mutableStateOf(false) }
    val bytes = draft.toByteArray(Charsets.UTF_8).size
    val overLimit = bytes > limitBytes
    val changed = draft != saved
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
                    text = if (changed) stringResource(R.string.memory_unsaved_changes) else summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                )
            }
            Text(
                text = "${formatBytes(bytes)} / ${formatBytes(limitBytes)}",
                color = if (overLimit) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MemoryDocumentBodyHeight),
            ) {
                if (editing) {
                    MemoryEditor(
                        value = draft,
                        enabled = enabled,
                        placeholder = summary,
                        onValueChange = onDraftChange,
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (draft.isNotEmpty()) {
                            MemoryBodyAction(
                                text = stringResource(R.string.ui_clear_84fcd7),
                                enabled = !saving,
                                onClick = onClear,
                            )
                        }
                        MemoryBodyAction(
                            text = stringResource(R.string.memory_mode_preview),
                            enabled = true,
                            onClick = { editing = false },
                        )
                    }
                } else {
                    MemoryPreview(
                        content = draft,
                        placeholder = summary,
                        onStartEditing = { if (enabled) editing = true },
                    )
                }
            }
            MemorySaveBar(
                visible = changed || saving || overLimit,
                overLimit = overLimit,
                saving = saving,
                canSave = enabled && changed && !overLimit,
                onDiscard = { onDraftChange(saved) },
                onSave = onSave,
            )
        }
    }
}

/** 正文区右上角的小动作：回到预览、清空。 */
@Composable
internal fun MemoryBodyAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.disabledOnSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 记忆档案行：点一下展开看内容。 */
@Composable
private fun FileRow(
    file: AgentMemoryFileUi,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    onLongClick != null -> Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )

                    else -> Modifier.clickable(onClick = onClick)
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
                text = file.name,
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(stringResource(R.string.memory_file_lines, file.lineCount))
                    append(" · ")
                    append(
                        if (file.hitCount > 0) {
                            stringResource(R.string.memory_section_hits, file.hitCount)
                        } else {
                            stringResource(R.string.memory_section_never_used)
                        },
                    )
                    file.idleDays?.takeIf { it > 0 }?.let { days ->
                        append(" · ").append(stringResource(R.string.memory_section_idle_days, days))
                    }
                },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
            )
        }
        if (selected == null) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** 文档正文区在列表里固定高度，预览与编辑都在这块区域内滚动。 */
internal val MemoryDocumentBodyHeight = 260.dp

/** 预览态：Markdown 渲染，双击切到编辑（单击留给后续可能的展开等操作）。 */
@Composable
internal fun MemoryPreview(content: String, placeholder: String, onStartEditing: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(onStartEditing) {
                detectTapGestures(onDoubleTap = { onStartEditing() })
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (content.isBlank()) {
            Text(
                text = placeholder,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            StaticMarkdown(content = content, tone = MarkdownTone.Answer, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 编辑态：无边框整页编辑，与卡片同色；点到空白处也能把光标落进正文。 */
@Composable
internal fun MemoryEditor(
    value: String,
    enabled: Boolean,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MemoryTextStyle(),
                color = colors.onSurfaceVariantSummary,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = MemoryTextStyle().copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
    }
}

@Composable
private fun MemoryTextStyle() = MiuixTheme.textStyles.body2.copy(lineHeight = 22.sp)


/** 有未保存修改或超限时才出现：左侧说明状态，右侧放弃与保存。 */
@Composable
internal fun MemorySaveBar(
    visible: Boolean,
    overLimit: Boolean,
    saving: Boolean,
    canSave: Boolean,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (overLimit) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                            CircleShape,
                        ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (overLimit) R.string.memory_over_limit else R.string.memory_unsaved_changes,
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (overLimit) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                )
            }
            EtaTextButton(
                text = stringResource(R.string.memory_discard),
                enabled = !saving,
                onClick = onDiscard,
            )
            EtaTextButton(
                text = stringResource(if (saving) R.string.memory_saving else R.string.memory_save),
                enabled = canSave,
                onClick = onSave,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}


/** 批量操作条上的胶囊按钮：按内容自适应宽度，文字永不折行。 */
@Composable
internal fun MemoryActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    danger: Boolean = false,
    plain: Boolean = false,
) {
    val colors = MiuixTheme.colorScheme
    val contentColor = when {
        !enabled -> colors.disabledOnSurface
        danger -> StatusError
        emphasized -> colors.primary
        else -> colors.onSurfaceVariantSummary
    }
    val shape = Modifier
        .clip(RoundedCornerShape(18.dp))
        .background(if (!plain && (emphasized || danger)) colors.surfaceContainerHigh else Color.Transparent)
        .clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = LocalIndication.current,
            onClick = onClick,
        )
    Box(
        // plain：取消这类次要动作只留文字，不占胶囊尺寸；等分模式下由调用方传 weight。
        modifier = modifier.then(
            if (plain) {
                shape.padding(horizontal = 4.dp).heightIn(min = 40.dp)
            } else {
                shape.fillMaxWidth().padding(horizontal = 10.dp).height(36.dp)
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MiuixTheme.textStyles.body2,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 记忆页批量操作条：横向可滚动，按钮按内容宽度排列，窄屏左右滑动看全。 */
@Composable
internal fun MemorySelectionActionsRow(
    cancel: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    // 注意：不能再套 horizontalScroll —— 无界宽度下子项的 weight 不生效，等分会失效。
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (cancel != null) {
            Box(modifier = Modifier.align(Alignment.Start)) { cancel() }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}


/** 记忆页长列表每页条数。 */
private const val MEMORY_PAGE_SIZE = 10

private fun memoryPageCount(size: Int): Int =
    if (size <= 0) 1 else (size + MEMORY_PAGE_SIZE - 1) / MEMORY_PAGE_SIZE

/** 三个长列表共用的页脚：上一页 / 第 N / M 页 / 下一页，只有一页时不显示。 */
@Composable
internal fun MemoryPagerBar(
    page: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    if (pageCount <= 1) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MemoryActionChip(
            text = stringResource(R.string.memory_pager_prev),
            onClick = onPrev,
            enabled = page > 0,
            plain = true,
        )
        Text(
            text = stringResource(R.string.memory_pager_status, page + 1, pageCount),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1,
            maxLines = 1,
            softWrap = false,
        )
        MemoryActionChip(
            text = stringResource(R.string.memory_pager_next),
            onClick = onNext,
            enabled = page < pageCount - 1,
            plain = true,
        )
    }
}
