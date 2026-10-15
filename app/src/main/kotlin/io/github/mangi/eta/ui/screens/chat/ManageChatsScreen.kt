package io.github.mangi.eta.ui.screens.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.fillMaxHeight

private val ManageRowHorizontalPadding = 16.dp
private val ManageRowVerticalPadding = 12.dp
private val ManageRowCornerRadius = 10.dp
private val ManageRowIconSize = 20.dp
private val ManageRowMinHeight = 56.dp

/** 左滑露出的三个操作（重命名 / 归档 / 删除）的宽度。 */
private val ManageSwipeRevealWidth = 228.dp
private val ManageSwipeActionWidth = 76.dp

/** 归档筛选三档。 */
private enum class ManageArchiveFilter(val labelRes: Int) {
    All(R.string.manage_filter_all),
    Active(R.string.manage_group_active),
    Archived(R.string.drawer_archived_chip),
}

private fun Set<String>.toggleSelection(id: String): Set<String> =
    if (id in this) this - id else this + id

/**
 * 「管理聊天」页：顶部两行筛选（文件夹 / 归档状态），列表只显示筛选结果；
 * 左滑露出重命名 / 归档 / 删除，长按进入多选做批量操作。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ManageChatsScreen(
    conversations: List<ConversationSummaryUi>,
    folders: List<ConversationFolderUi>,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onTogglePin: (ConversationSummaryUi) -> Unit,
    onRename: (ConversationSummaryUi) -> Unit,
    onDelete: (ConversationSummaryUi) -> Unit,
    onToggleArchive: (ConversationSummaryUi) -> Unit,
    onArchiveSelected: (Set<String>, Boolean) -> Unit,
    onPinSelected: (Set<String>, Boolean) -> Unit,
    onMoveSelected: (Set<String>, String?) -> Unit,
    onDeleteSelected: (Set<String>) -> Unit,
) {
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var moveTargets by remember { mutableStateOf<Set<String>?>(null) }
    var folderFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var archiveFilterName by rememberSaveable { mutableStateOf(ManageArchiveFilter.All.name) }
    val archiveFilter = ManageArchiveFilter.valueOf(archiveFilterName)
    val haptics = LocalHapticFeedback.current

    // 列表数据可能带上重复项，先去重再建 key：LazyColumn 的 key 重复会直接崩。
    val unique = remember(conversations) { conversations.distinctBy { it.id } }
    val visible = unique.filter { conversation ->
        val folderOk = folderFilter == null || conversation.folderId == folderFilter
        val archiveOk = when (archiveFilter) {
            ManageArchiveFilter.All -> true
            ManageArchiveFilter.Active -> !conversation.isArchived
            ManageArchiveFilter.Archived -> conversation.isArchived
        }
        folderOk && archiveOk
    }

    val byId = unique.associateBy { it.id }
    val selecting = selection.isNotEmpty()
    val allArchived = selecting && selection.all { byId[it]?.isArchived == true }
    val allPinned = selecting && selection.all { byId[it]?.isPinned == true }

    Box(modifier = Modifier.fillMaxSize()) {
        MiuixScaffoldPage(
            title = stringResource(R.string.drawer_manage_chats),
            onBack = { if (selecting) selection = emptySet() else onBack() },
        ) {
            stickyHeader(key = "manage-filters") {
                ManageFilterBar(
                    folders = folders,
                    folderFilter = folderFilter,
                    archiveFilter = archiveFilter,
                    onFolderFilter = { folderFilter = it },
                    onArchiveFilter = { archiveFilterName = it.name },
                )
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.conversation_empty),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    )
                }
            } else {
                items(visible, key = { "row-${it.id}" }, contentType = { "row" }) { conversation ->
                    ManageChatRow(
                        conversation = conversation,
                        selecting = selecting,
                        selected = conversation.id in selection,
                        onClick = {
                            if (selecting) selection = selection.toggleSelection(conversation.id)
                            else onOpenConversation(conversation.id)
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            selection = selection.toggleSelection(conversation.id)
                        },
                        onTogglePin = { onTogglePin(conversation) },
                        onRename = { onRename(conversation) },
                        onToggleArchive = { onToggleArchive(conversation) },
                        onDelete = { onDelete(conversation) },
                    )
                }
            }
        }

        if (selecting) {
            ManageSelectionBar(
                count = selection.size,
                allSelected = selection.size == visible.size,
                allArchived = allArchived,
                allPinned = allPinned,
                onToggleSelectAll = {
                    selection = if (selection.size == visible.size) {
                        emptySet()
                    } else {
                        visible.map { it.id }.toSet()
                    }
                },
                onArchive = {
                    onArchiveSelected(selection, !allArchived)
                    selection = emptySet()
                },
                onPin = {
                    onPinSelected(selection, !allPinned)
                    selection = emptySet()
                },
                onMove = { moveTargets = selection },
                onDelete = {
                    onDeleteSelected(selection)
                    selection = emptySet()
                },
            )
        }
    }

    moveTargets?.let { targets ->
        ManageFolderPicker(
            folders = folders,
            onDismiss = { moveTargets = null },
            onPick = { folderId ->
                onMoveSelected(targets, folderId)
                moveTargets = null
                selection = emptySet()
            },
        )
    }
}

/** 顶部筛选：第一行文件夹，第二行归档状态；两行都可横向滚动。 */
@Composable
private fun ManageFilterBar(
    folders: List<ConversationFolderUi>,
    folderFilter: String?,
    archiveFilter: ManageArchiveFilter,
    onFolderFilter: (String?) -> Unit,
    onArchiveFilter: (ManageArchiveFilter) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .padding(vertical = 8.dp),
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = ManageRowHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "folder-all") {
                ManageFilterChip(
                    label = stringResource(R.string.manage_filter_all),
                    selected = folderFilter == null,
                    onClick = { onFolderFilter(null) },
                )
            }
            item(key = "folder-unfiled") {
                ManageFilterChip(
                    label = stringResource(R.string.drawer_chats_chip),
                    selected = folderFilter == "",
                    onClick = { onFolderFilter("") },
                )
            }
            items(folders, key = { "folder-${it.id}" }) { folder ->
                ManageFilterChip(
                    label = folder.name,
                    selected = folderFilter == folder.id,
                    onClick = { onFolderFilter(folder.id) },
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = ManageRowHorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ManageArchiveFilter.entries.forEach { option ->
                item(key = "archive-${option.name}") {
                    ManageFilterChip(
                        label = stringResource(option.labelRes),
                        selected = archiveFilter == option,
                        onClick = { onArchiveFilter(option) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ManageFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
        style = MiuixTheme.textStyles.footnote1,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHighest,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** 多选时的底部操作栏：归档/取消归档、置顶/取消置顶、移动、删除。 */
@Composable
private fun BoxScope.ManageSelectionBar(
    count: Int,
    allSelected: Boolean,
    allArchived: Boolean,
    allPinned: Boolean,
    onToggleSelectAll: () -> Unit,
    onArchive: () -> Unit,
    onPin: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.manage_selected_count, count),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    if (allSelected) R.string.action_clear_selection else R.string.action_select_all,
                ),
                color = MiuixTheme.colorScheme.primary,
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onToggleSelectAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ManageSelectionAction(
                label = stringResource(
                    if (allArchived) R.string.action_unarchive_conversation else R.string.action_archive_conversation,
                ),
                icon = Icons.Rounded.Inventory,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                onClick = onArchive,
            )
            ManageSelectionAction(
                label = stringResource(
                    if (allPinned) R.string.action_unpin_conversation else R.string.action_pin_conversation,
                ),
                icon = Icons.Rounded.PushPin,
                tint = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                onClick = onPin,
            )
            ManageSelectionAction(
                label = stringResource(R.string.action_move_short),
                icon = Icons.AutoMirrored.Rounded.DriveFileMove,
                tint = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                onClick = onMove,
            )
            ManageSelectionAction(
                label = stringResource(R.string.action_delete),
                icon = Icons.Rounded.Delete,
                tint = MiuixTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
                onClick = onDelete,
            )
        }
    }
}

/** 多选栏里的一个操作：图标在上、文字在下，四个等宽排开（文字短，不换行）。 */
@Composable
private fun ManageSelectionAction(
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(ManageRowIconSize),
            tint = tint,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = tint,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 批量移动时选目标文件夹；第一项是「聊天」= 移出文件夹。 */
@Composable
private fun ManageFolderPicker(
    folders: List<ConversationFolderUi>,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    EtaWindowDialog(
        show = true,
        title = stringResource(R.string.action_move_to_folder),
        onDismissRequest = onDismiss,
    ) {
        Column {
            ManageFolderOption(stringResource(R.string.drawer_chats_chip)) { onPick(null) }
            folders.forEach { folder ->
                ManageFolderOption(folder.name) { onPick(folder.id) }
            }
        }
    }
}

@Composable
private fun ManageFolderOption(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ManageRowCornerRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = ManageRowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ManageChatRow(
    conversation: ConversationSummaryUi,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offsetX = remember(conversation.id) { Animatable(0f) }
    val revealPx = with(LocalDensity.current) { ManageSwipeRevealWidth.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ManageRowHorizontalPadding)
            .clip(RoundedCornerShape(ManageRowCornerRadius)),
    ) {
        // 背景：左滑露出的三个操作
        Row(
            modifier = Modifier
                .matchParentSize()
                .background(MiuixTheme.colorScheme.surfaceContainerHigh),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            ManageSwipeAction(
                label = stringResource(R.string.action_rename),
                icon = Icons.Rounded.Edit,
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                onClick = {
                    scope.launch { offsetX.animateTo(0f) }
                    onRename()
                },
            )
            ManageSwipeAction(
                label = stringResource(
                    if (conversation.isArchived) {
                        R.string.action_unarchive_conversation
                    } else {
                        R.string.action_archive_conversation
                    },
                ),
                icon = Icons.Rounded.Inventory,
                tint = MiuixTheme.colorScheme.primary,
                onClick = {
                    scope.launch { offsetX.animateTo(0f) }
                    onToggleArchive()
                },
            )
            ManageSwipeAction(
                label = stringResource(R.string.action_delete),
                icon = Icons.Rounded.Delete,
                tint = MiuixTheme.colorScheme.error,
                onClick = {
                    scope.launch { offsetX.animateTo(0f) }
                    onDelete()
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .clip(RoundedCornerShape(ManageRowCornerRadius))
                .background(MiuixTheme.colorScheme.surface)
                .then(
                    // 多选时不再左滑，避免和选中手势打架。
                    if (selecting) {
                        Modifier
                    } else {
                        Modifier.draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                scope.launch {
                                    offsetX.snapTo((offsetX.value + delta).coerceIn(-revealPx, 0f))
                                }
                            },
                            onDragStopped = {
                                scope.launch {
                                    if (offsetX.value <= -revealPx / 2f) offsetX.animateTo(-revealPx)
                                    else offsetX.animateTo(0f)
                                }
                            },
                        )
                    },
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = ManageRowVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Icon(
                    imageVector = if (selected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = null,
                    modifier = Modifier.size(ManageRowIconSize),
                    tint = if (selected) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantActions
                    },
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = conversation.title.ifBlank { conversation.preview },
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!selecting) {
                IconButton(onClick = onTogglePin) {
                    Icon(
                        imageVector = if (conversation.isPinned) {
                            Icons.Rounded.PushPin
                        } else {
                            Icons.Outlined.PushPin
                        },
                        contentDescription = stringResource(
                            if (conversation.isPinned) {
                                R.string.action_unpin_conversation
                            } else {
                                R.string.action_pin_conversation
                            },
                        ),
                        modifier = Modifier.size(ManageRowIconSize),
                        tint = if (conversation.isPinned) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ManageSwipeAction(
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(ManageSwipeActionWidth)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(ManageRowIconSize),
            tint = tint,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = tint,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
