package io.github.mangi.eta.ui.screens.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

private val ManageRowHorizontalPadding = 16.dp
private val ManageRowVerticalPadding = 12.dp
private val ManageRowCornerRadius = 10.dp
private val ManageRowIconSize = 20.dp

/** 左滑露出的三个操作（重命名 / 归档 / 删除）的宽度。 */
private val ManageSwipeRevealWidth = 228.dp
private val ManageSwipeActionWidth = 76.dp

private data class ManageGroup(
    val id: String,
    val title: String,
    val conversations: List<ConversationSummaryUi>,
)

private fun Set<String>.toggleSelection(id: String): Set<String> =
    if (id in this) this - id else this + id

/**
 * 「管理聊天」页：一级按文件夹分组（含未归类的「聊天」），组内再分「未归档 / 已归档」；
 * 左滑露出重命名 / 归档 / 删除，长按进入多选做批量操作。
 */
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
    val haptics = LocalHapticFeedback.current
    val unfiledLabel = stringResource(R.string.drawer_chats_chip)

    val groups = buildList {
        val unfiled = conversations.filter { it.folderId.isBlank() }
        if (unfiled.isNotEmpty()) add(ManageGroup("", unfiledLabel, unfiled))
        folders.forEach { folder ->
            val items = conversations.filter { it.folderId == folder.id }
            if (items.isNotEmpty()) add(ManageGroup(folder.id, folder.name, items))
        }
    }
    val byId = conversations.associateBy { it.id }
    val selecting = selection.isNotEmpty()
    val allArchived = selecting && selection.all { byId[it]?.isArchived == true }
    val allPinned = selecting && selection.all { byId[it]?.isPinned == true }

    fun LazyListScope.groupRows(group: ManageGroup) {
        val active = group.conversations.filter { !it.isArchived }
        val archived = group.conversations.filter { it.isArchived }
        if (active.isNotEmpty()) {
            if (archived.isNotEmpty()) {
                item(key = "sub-active-${group.id}", contentType = "subtitle") {
                    ManageSubTitle(stringResource(R.string.manage_group_active))
                }
            }
            items(active, key = { it.id }, contentType = { "row" }) { conversation ->
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
        if (archived.isNotEmpty()) {
            item(key = "sub-archived-${group.id}", contentType = "subtitle") {
                ManageSubTitle(stringResource(R.string.drawer_archived_chip))
            }
            items(active, key = { it.id }, contentType = { "row" }) { conversation ->
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

    Box(modifier = Modifier.fillMaxSize()) {
        MiuixScaffoldPage(
            title = stringResource(R.string.drawer_manage_chats),
            onBack = { if (selecting) selection = emptySet() else onBack() },
        ) {
            if (groups.isEmpty()) {
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
                groups.forEach { group ->
                    item(key = "group-${group.id}", contentType = "group") { ManageGroupTitle(group.title) }
                    groupRows(group)
                }
            }
        }

        if (selecting) {
            ManageSelectionBar(
                count = selection.size,
                allArchived = allArchived,
                allPinned = allPinned,
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

@Composable
private fun ManageGroupTitle(title: String) {
    Text(
        text = title,
        color = MiuixTheme.colorScheme.onSurface,
        style = MiuixTheme.textStyles.body1,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun ManageSubTitle(title: String) {
    Text(
        text = title,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp),
    )
}

/** 多选时的底部操作栏：归档/取消归档、置顶/取消置顶、移动、删除。 */
@Composable
private fun BoxScope.ManageSelectionBar(
    count: Int,
    allArchived: Boolean,
    allPinned: Boolean,
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
        Text(
            text = stringResource(R.string.manage_selected_count, count),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EtaTextButton(
                text = stringResource(
                    if (allArchived) R.string.action_unarchive_conversation else R.string.action_archive_conversation,
                ),
                onClick = onArchive,
                modifier = Modifier.weight(1f),
            )
            EtaTextButton(
                text = stringResource(
                    if (allPinned) R.string.action_unpin_conversation else R.string.action_pin_conversation,
                ),
                onClick = onPin,
                modifier = Modifier.weight(1f),
            )
            EtaTextButton(
                text = stringResource(R.string.action_move_to_folder),
                onClick = onMove,
                modifier = Modifier.weight(1f),
            )
            EtaTextButton(
                text = stringResource(R.string.action_delete),
                onClick = onDelete,
                modifier = Modifier.weight(1f),
            )
        }
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
            .combinedClickable(onClick = onClick)
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
                .fillMaxWidth()
                .height(ManageRowMinHeight)
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
            .fillMaxSize()
            .combinedClickable(onClick = onClick),
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

private val ManageRowMinHeight = 56.dp
