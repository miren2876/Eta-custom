package io.github.mangi.eta.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Group
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.OptionalModelPickerContent
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 记忆整理用哪个模型：默认跟随当前模型，也可以单独指定。
 *
 * 结构与「会话标题模型」一致：二级设置页 + 底部模型选择器，
 * 因为覆盖层必须在 Scaffold 的 overlay 槽里才不会被页面背景盖住。
 */
@Composable
internal fun MemoryTaskModelSettingsScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val primaryProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val primaryModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    val memoryTaskModelId by RuntimeConfigRepository.memoryTaskModelIdFlow().collectAsState(initial = null)

    val pickerState = remember(providers, primaryProviderId, primaryModelId) {
        AgentModelPickerProjector.project(providers, primaryProviderId, primaryModelId)
    }
    val resolvedCustom = remember(providers, memoryTaskModelId) {
        providers.enabledModel(memoryTaskModelId)
    }
    val primaryModelName = pickerState.selectedModel?.displayName
    val followTitle = stringResource(R.string.memory_task_model_follow)
    val followSubtitle = primaryModelName?.let {
        stringResource(R.string.memory_task_model_follow_with_name, it)
    } ?: followTitle
    val modelSummary = when {
        resolvedCustom != null -> "${resolvedCustom.provider.name} / ${resolvedCustom.model.displayName}"
        !primaryModelName.isNullOrBlank() ->
            stringResource(R.string.memory_task_model_follow_with_name, primaryModelName)
        else -> followTitle
    }

    var showModelSheet by remember { mutableStateOf(false) }
    val onSelectModel: (String?) -> Unit = { modelId ->
        showModelSheet = false
        scope.launch { RuntimeConfigRepository.setMemoryTaskModelId(modelId) }
    }

    val pageTitle = stringResource(R.string.memory_task_model_title)
    val modelTitle = stringResource(R.string.memory_task_model_title)
    val noteText = stringResource(R.string.memory_task_model_note)

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = pageTitle,
                onBack = onBack,
                overlay = {
                    val dark = CouiTheme.isDark
                    Dialog(
                        visible = showModelSheet,
                        onDismissRequest = { showModelSheet = false },
                        dark = dark,
                        title = modelTitle,
                        confirmAction = CouiDialogAction(
                            text = stringResource(R.string.action_close),
                            onClick = { showModelSheet = false },
                        ),
                        placement = CouiDialogPlacement.Bottom,
                        material = rememberCouiDialogMaterial(dark),
                        backdrop = rememberCouiWindowBackdrop(),
                    ) {
                        OptionalModelPickerContent(
                            pickerState = pickerState,
                            selectedSecondaryModelId = resolvedCustom?.model?.id,
                            followTitle = followTitle,
                            followSubtitle = followSubtitle,
                            onSelectModel = onSelectModel,
                        )
                    }
                },
            ) {
                Group(title = stringResource(R.string.memory_task_model_group)) {
                    CouiPreference(
                        title = modelTitle,
                        summary = modelSummary,
                        onClick = { showModelSheet = true },
                    )
                    Divider()
                }
                Footer(text = noteText)
                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = pageTitle,
                onBack = onBack,
                overlay = {
                    OverlayBottomSheet(
                        show = showModelSheet,
                        title = modelTitle,
                        onDismissRequest = { showModelSheet = false },
                        insideMargin = DpSize(0.dp, 0.dp),
                    ) {
                        OptionalModelPickerContent(
                            pickerState = pickerState,
                            selectedSecondaryModelId = resolvedCustom?.model?.id,
                            followTitle = followTitle,
                            followSubtitle = followSubtitle,
                            onSelectModel = onSelectModel,
                        )
                    }
                },
            ) {
                item(key = "memory_task_model_group") {
                    SmallTitle(stringResource(R.string.memory_task_model_group))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = modelTitle,
                            summary = modelSummary,
                            onClick = { showModelSheet = true },
                        )
                    }
                    Text(
                        text = noteText,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
                    )
                }
            }
        }
    }
}
