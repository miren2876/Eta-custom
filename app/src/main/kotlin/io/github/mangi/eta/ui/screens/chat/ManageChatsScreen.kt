package io.github.mangi.eta.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PushPin
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val ManageRowHorizontalPadding = 16.dp
private val ManageRowVerticalPadding = 14.dp
private val ManageRowCornerRadius = 16.dp
private val ManageSwipeRevealWidth = 96.dp
private val ManageSwipeTriggerWidth = 64.dp
private val ManageRowIconSize = 20.dp

/**
 * 「管理聊天」页：左滑删除、点右侧图钉切换置顶、长按重命名。
 *
 * 与侧栏一致按会话当前顺序展示；顺序由调用方给出，页面只负责交互。
 */
@Composable
internal fun ManageChatsScreen(
    conversations: List<ConversationSummaryUi>,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onTogglePin: (ConversationSummaryUi) -> Unit,
    onRename: (ConversationSummaryUi) -> Unit,
    onDelete: (ConversationSummaryUi) -> Unit,
) {
    MiuixScaffoldPage(
        title = stringResource(R.string.drawer_manage_chats),
        onBack = onBack,
    ) {
        if (conversations.isEmpty()) {
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
            items(
                items = conversations,
                key = { it.id },
            ) { conversation ->
                ManageChatRow(
                    conversation = conversation,
                    onClick = { onOpenConversation(conversation.id) },
                    onTogglePin = { onTogglePin(conversation) },
                    onRename = { onRename(conversation) },
                    onDelete = { onDelete(conversation) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ManageChatRow(
    conversation: ConversationSummaryUi,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offsetX = remember(conversation.id) { Animatable(0f) }
    val revealPx = with(LocalDensity.current) { ManageSwipeRevealWidth.toPx() }
    val triggerPx = with(LocalDensity.current) { ManageSwipeTriggerWidth.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ManageRowHorizontalPadding)
            .clip(RoundedCornerShape(ManageRowCornerRadius)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = stringResource(R.string.action_delete),
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = null,
                modifier = Modifier.size(ManageRowIconSize),
                tint = MiuixTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .clip(RoundedCornerShape(ManageRowCornerRadius))
                .background(MiuixTheme.colorScheme.surface)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            offsetX.snapTo((offsetX.value + delta).coerceIn(-revealPx, 0f))
                        }
                    },
                    onDragStopped = {
                        if (offsetX.value <= -triggerPx) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            offsetX.snapTo(0f)
                            onDelete()
                        } else {
                            offsetX.animateTo(0f)
                        }
                    },
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRename()
                    },
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = ManageRowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = conversation.title.ifBlank { conversation.preview },
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
