package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.prompt.AgentUserPrompt
import io.github.mangi.eta.agent.prompt.AgentUserPromptAnswer
import io.github.mangi.eta.agent.prompt.AgentUserPromptOption
import io.github.mangi.eta.agent.prompt.AgentUserPromptRequest
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 运行中向用户提问的卡片。
 *
 * 模型调用 ask_user 后这里渲染：每题可单选或多选，允许自定义文字补充，
 * 多题时用翻页显示「下一题」，也可以跳过。提交或全部跳过后结果作为工具返回值交还给模型。
 *
 * 调用方负责挑选属于当前会话的提问（聊天页在输入框的位置渲染本组件）。
 */
@Composable
internal fun AgentUserPromptCardContent(request: AgentUserPromptRequest, modifier: Modifier) {
    val total = request.questions.size
    var page by remember(request.token) { mutableIntStateOf(0) }
    var collapsed by remember(request.token) { mutableStateOf(false) }
    val selections = remember(request.token) { mutableStateMapOf<String, Set<String>>() }
    val customs = remember(request.token) { mutableStateMapOf<String, String>() }
    val safePage = page.coerceIn(0, (total - 1).coerceAtLeast(0))
    val question = request.questions.getOrNull(safePage) ?: return
    val selected = selections[question.id].orEmpty()
    val isLast = safePage >= total - 1

    fun answers(): List<AgentUserPromptAnswer> = request.questions.map { item ->
        val picked = selections[item.id].orEmpty()
        val custom = customs[item.id].orEmpty().trim()
        AgentUserPromptAnswer(
            id = item.id,
            selected = picked.toList(),
            custom = custom,
            skipped = picked.isEmpty() && custom.isEmpty(),
        )
    }

    fun finish() = AgentUserPrompt.resolve(request.token, answers())

    fun toggle(optionId: String) {
        val current = selections[question.id].orEmpty()
        selections[question.id] = when {
            !question.multi -> if (optionId in current) emptySet() else setOf(optionId)
            optionId in current -> current - optionId
            else -> current + optionId
        }
    }

    fun skipCurrent() {
        selections.remove(question.id)
        customs.remove(question.id)
        if (isLast) finish() else page = safePage + 1
    }

    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MiuixTheme.colorScheme.surface)
            .border(0.5.dp, MiuixTheme.colorScheme.outline, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            question.label?.let { label ->
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { collapsed = !collapsed }) {
                Icon(
                    imageVector = if (collapsed) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (collapsed) R.string.user_prompt_expand else R.string.user_prompt_collapse,
                    ),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = { finish() }) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.user_prompt_close),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Text(
            text = question.question,
            style = MiuixTheme.textStyles.title3,
            color = MiuixTheme.colorScheme.onSurface,
        )

        if (!collapsed) {
            if (question.multi) {
                Text(
                    text = stringResource(R.string.user_prompt_multi_hint),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
            Column(
                // 只让选项区域滚动：题干、输入框和底部按钮保持固定
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                question.options.forEachIndexed { index, option ->
                    OptionRow(
                        index = index,
                        option = option,
                        selected = option.id in selected,
                        onClick = { toggle(option.id) },
                    )
                }
            }
            if (question.allowCustom) {
                CustomAnswerField(
                    value = customs[question.id].orEmpty(),
                    hint = question.customPlaceholder ?: stringResource(R.string.user_prompt_custom_hint),
                    onValueChange = { text -> customs[question.id] = text.take(600) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = safePage > 0, onClick = { page = safePage - 1 }) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowLeft,
                        contentDescription = null,
                        tint = if (safePage > 0) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.disabledOnSurface
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.user_prompt_progress, safePage + 1, total),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                IconButton(enabled = !isLast, onClick = { page = safePage + 1 }) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = if (isLast) {
                            MiuixTheme.colorScheme.disabledOnSurface
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.user_prompt_skip),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .border(0.5.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                        .clickable { skipCurrent() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        if (isLast) R.string.user_prompt_submit else R.string.user_prompt_next,
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(MiuixTheme.colorScheme.primary)
                        .clickable { if (isLast) finish() else page = safePage + 1 }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun OptionRow(
    index: Int,
    option: AgentUserPromptOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val background = if (selected) {
        MiuixTheme.colorScheme.secondaryContainer
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .then(if (selected) Modifier.border(0.5.dp, MiuixTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (index + 1).toString(),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = option.title,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (option.recommended) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.user_prompt_recommended),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MiuixTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            option.description?.let { description ->
                Text(
                    text = description,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun CustomAnswerField(
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .border(0.5.dp, MiuixTheme.colorScheme.outline, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Edit,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = hint,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Spacer(Modifier.height(0.dp))
}
