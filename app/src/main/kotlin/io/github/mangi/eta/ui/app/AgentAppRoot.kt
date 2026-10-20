package io.github.mangi.eta.ui.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.AppearanceSettingsScreen
import io.github.mangi.eta.ui.SettingsScreen
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.model.AgentHomeAction
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSystemEnhanceAction
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.conversationTopBarTitle
import io.github.mangi.eta.ui.model.LOCAL_NETWORK_PERMISSION_ITEM_ID
import io.github.mangi.eta.ui.model.PermissionHealthAction
import io.github.mangi.eta.ui.model.roleplayChatUi
import io.github.mangi.eta.ui.navigation.AgentNavigator
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.pages.providers.CommunityCatalogProviderScreen
import io.github.mangi.eta.ui.pages.providers.CommunityCatalogScreen
import io.github.mangi.eta.ui.pages.providers.ModelProviderDetailScreen
import io.github.mangi.eta.ui.pages.providers.ModelProviderListScreen
import io.github.mangi.eta.ui.screens.backup.DataBackupScreen
import io.github.mangi.eta.ui.screens.browser.AgentBrowserScreen
import io.github.mangi.eta.ui.screens.chat.ManageChatsScreen
import io.github.mangi.eta.ui.screens.characters.CharacterDetailScreen
import io.github.mangi.eta.ui.screens.characters.CharacterEditorScreen
import io.github.mangi.eta.ui.screens.characters.CharacterLibraryScreen
import io.github.mangi.eta.ui.screens.characters.CharacterMemoryScreen
import io.github.mangi.eta.ui.screens.characters.CharacterPersonaScreen
import io.github.mangi.eta.ui.screens.enhance.SystemEnhanceScreen
import io.github.mangi.eta.ui.screens.home.AgentHomeScreen
import io.github.mangi.eta.ui.screens.mcp.McpServerDetailScreen
import io.github.mangi.eta.ui.screens.mcp.McpServersScreen
import io.github.mangi.eta.ui.screens.memory.AgentMemoryScreen
import io.github.mangi.eta.ui.screens.permissions.PermissionHealthScreen
import io.github.mangi.eta.ui.screens.skills.AgentSkillsScreen
import io.github.mangi.eta.ui.screens.terminal.LinuxEnvironmentScreen
import io.github.mangi.eta.ui.screens.terminal.LinuxFilesScreen
import io.github.mangi.eta.ui.screens.terminal.SharedFoldersScreen
import io.github.mangi.eta.ui.screens.terminal.TerminalEntryScreen
import io.github.mangi.eta.ui.screens.terminal.ScriptManagerScreen
import io.github.mangi.eta.ui.screens.terminal.WorkspaceScreen
import io.github.mangi.eta.ui.screens.tools.AgentToolsScreen
import io.github.mangi.eta.ui.screens.tools.WebToolsSettingsScreen
import io.github.mangi.eta.ui.voice.SpeechOssScreen
import io.github.mangi.eta.ui.voice.SpeechRecognitionScreen
import io.github.mangi.eta.ui.voice.SpeechSettingsScreen
import io.github.mangi.eta.ui.voice.SpeechSynthesisScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Agent App 根组件：持有本地导航栈，并把 Screen actions 交给 [AgentAppState]。
 */
@Composable
fun AgentAppRoot(
    assistantConversationKey: String? = null,
    requestedConversationId: String? = null,
    /** 侧栏（会话抽屉）开合变化时通知宿主，宿主据此收起浮层卡片。 */
    onConversationPaneChanged: (Boolean) -> Unit = {},
    onRequestedConversationOpened: () -> Unit = {},
    requestedArchivedConversation: ArchivedConversationRequest? = null,
    onArchivedConversationOpened: () -> Unit = {},
    openSpeechSettings: Boolean = false,
    openTerminal: Boolean = false,
    onTerminalOpened: () -> Unit = {},
    onSpeechSettingsOpened: () -> Unit = {},
    onAssistantConversationOpened: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    // 用户回到 Eta：清掉悬浮胶囊上的「已完成」未读提示。
    LaunchedEffect(Unit) {
        runCatching {
            context.startService(
                android.content.Intent(
                    context,
                    io.github.mangi.eta.agent.runtime.AgentRuntimeService::class.java,
                ).setAction(io.github.mangi.eta.agent.runtime.AgentRuntimeService.ACTION_CLEAR_READ),
            )
        }
    }
    val resources = LocalResources.current
    val uiScope = rememberCoroutineScope()
    val backStack = rememberNavBackStack<AppRoute>(AppRoute.Home)
    LaunchedEffect(openSpeechSettings) {
        if (openSpeechSettings) {
            if (backStack.lastOrNull() != AppRoute.SpeechSettings) backStack.add(AppRoute.SpeechSettings)
            onSpeechSettingsOpened()
        }
    }
    LaunchedEffect(openTerminal) {
        if (openTerminal) {
            if (backStack.lastOrNull() != AppRoute.Terminal) backStack.add(AppRoute.Terminal)
            onTerminalOpened()
        }
    }
    val navigator = remember(backStack) { AgentNavigator(backStack) }
    var navigationResetKey by rememberSaveable { mutableIntStateOf(0) }
    val appViewModel = viewModel<AgentAppViewModel>()
    val agentState = appViewModel.state
    val runtimeActive by AgentRuntimeService.keepScreenOn.collectAsState()
    val view = LocalView.current
    val keepScreenOn = backStack.lastOrNull() == AppRoute.Home &&
        agentState.homeState.isStreaming && runtimeActive
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    val skills = appViewModel.skills
    val memory = appViewModel.memory
    val permissionHealth = appViewModel.permissionHealth
    val toolsState = remember { buildToolsState(context) }
    val characterStore = viewModel<CharacterLibraryViewModel>().store
    val communityCatalogStore = viewModel<CommunityCatalogViewModel>().store
    val requestExecutionNotifications = rememberExecutionNotificationRequest()
    val requestLocalNetworkPermission = rememberLocalNetworkPermissionRequest(permissionHealth::refreshPermissionHealth)
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionHealth.refreshPermissionHealth()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                RootAccess.refresh(context)
                appViewModel.refreshKimiWeb()
                permissionHealth.refreshPermissionHealth()
                agentState.refreshRuntimeResults()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var conversationPaneOpen by rememberSaveable { mutableStateOf(false) }
    var conversationRenameTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationDeleteTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationMoveTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationExportTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var messageDeleteTarget by remember { mutableStateOf<MessageMutationTarget?>(null) }
    var messageRegenerateTarget by remember { mutableStateOf<MessageMutationTarget?>(null) }
    val conversationExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val target = conversationExportTarget
        conversationExportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        uiScope.launch {
            try {
                val markdown = agentState.exportConversationMarkdown(target.id)
                    ?: error(resources.getString(R.string.conversation_export_failed))
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(resources.getString(R.string.conversation_export_failed))
                withContext(Dispatchers.IO) {
                    output.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
                }
                Toast.makeText(
                    context,
                    resources.getString(R.string.conversation_exported),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                Toast.makeText(
                    context,
                    resources.getString(R.string.conversation_export_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    LaunchedEffect(requestedConversationId) {
        val conversationId = requestedConversationId ?: return@LaunchedEffect
        agentState.selectConversation(conversationId)
        conversationPaneOpen = false
        navigator.popToHome()
        onRequestedConversationOpened()
    }

    LaunchedEffect(requestedArchivedConversation) {
        val request = requestedArchivedConversation ?: return@LaunchedEffect
        // 外部入口的结果要先导入归档才有会话；找不到时停在当前界面，不切到无关的会话。
        if (agentState.openArchivedConversation(request.source, request.conversationKey)) {
            conversationPaneOpen = false
            navigator.popToHome()
        }
        onArchivedConversationOpened()
    }

    LaunchedEffect(assistantConversationKey) {
        val conversationKey = assistantConversationKey ?: return@LaunchedEffect
        val opened = agentState.openAssistantConversation(conversationKey)
        if (opened) {
            conversationPaneOpen = false
            // 接管落到主聊天舞台：与主界面同一页面、同一侧边对话列表，不再开独立对话页。
            navigator.popToHome()
        }
        onAssistantConversationOpened(opened)
    }

    fun pushRoute(
        route: AppRoute,
        restoreConversationPaneOnBack: Boolean = conversationPaneOpen,
    ) {
        conversationPaneOpen = restoreConversationPaneOnBack
        navigator.push(route)
    }

    fun popRoute() {
        if (!navigator.pop()) {
            (context as? Activity)?.finish()
        }
    }

    fun selectConversation(conversationId: String) {
        focusManager.clearFocus()
        agentState.selectConversation(conversationId)
        conversationPaneOpen = false
    }

    fun createConversation() {
        focusManager.clearFocus()
        agentState.createConversation()
        conversationPaneOpen = false
    }

    // 顶栏标题双击 → 回到第一条消息：这里只累加请求号，聊天区自己决定是否响应。
    var chatScrollToTopRequest by remember { mutableIntStateOf(0) }
    // 顶栏副标题单击 → 定位到最近一轮工作过程，同样只累加请求号。
    var chatWorkProcessRequest by remember { mutableIntStateOf(0) }

    // 聊天页顶栏左侧显示当前会话标题；草稿（未选中会话）时留空。
    val paneUiState = agentState.conversationPaneState
    val selectedConversation = paneUiState.historyConversations
        .firstOrNull { it.id == paneUiState.selectedConversationId }
    val conversationPaneTitle = conversationTopBarTitle(selectedConversation?.title.orEmpty())
    val conversationPaneSubtitle = conversationTopBarSubtitle(
        messages = agentState.homeState.messages,
        isStreaming = agentState.homeState.isStreaming,
        timeLabel = selectedConversation?.timeLabel.orEmpty(),
    )

    @Composable
    fun RoutedShell(
        route: AppRoute,
        content: @Composable () -> Unit,
    ) {
        AgentAppShell(
            currentRoute = route,
            isCurrentRoute = backStack.lastOrNull() == route,
            conversationPaneState = paneUiState,
            isConversationPaneOpen = conversationPaneOpen,
            homeConversationTitle = conversationPaneTitle,
            homeConversationSubtitle = conversationPaneSubtitle,
            onConversationSubtitleClick = { chatWorkProcessRequest += 1 },
            onConversationTitleDoubleTap = {
                AndroidAgentLogger.info("Chat top bar title double tap")
                chatScrollToTopRequest += 1
            },
            onBack = { popRoute() },
            onOpenConversationPane = { conversationPaneOpen = true },
            onDismissConversationPane = { conversationPaneOpen = false },
            onSearchConversations = { query -> agentState.updateSearchQuery(query) },
            onNewConversation = { createConversation() },
            onOpenTerminal = { pushRoute(AppRoute.Terminal) },
            onOpenScripts = { pushRoute(AppRoute.Scripts) },
            onLaunchKimiWeb = {
                requestExecutionNotifications()
                if (appViewModel.kimiWebState.phase != KimiWebPhase.NOT_INSTALLED) {
                    appViewModel.launchKimiWeb { result ->
                        if (result is KimiWebLaunchResult.Failed) {
                            Toast.makeText(
                                context,
                                result.message(context),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                } else {
                    pushRoute(AppRoute.LinuxEnvironment)
                }
            },
            kimiWebLabel = appViewModel.kimiWebState.actionLabel(context),
            canStopKimiWeb = appViewModel.kimiWebState.canStop,
            onStopKimiWeb = appViewModel::stopKimiWeb,
            onRefreshKimiWeb = appViewModel::refreshKimiWeb,
            onOpenBrowser = { pushRoute(AppRoute.Browser) },
            onSelectConversation = { conversationId -> selectConversation(conversationId) },
            onConversationRename = { conversation ->
                conversationRenameTarget = conversation
            },
            onConversationExport = { conversation ->
                conversationExportTarget = conversation
                conversationExportLauncher.launch(
                    ConversationMarkdownExporter.defaultFileName(
                        title = conversation.title.ifBlank { conversation.preview },
                        fallback = resources.getString(R.string.conversation_export_default_name),
                    ),
                )
            },
            onConversationDelete = { conversation ->
                conversationDeleteTarget = conversation
            },
            onConversationMoveToFolder = { conversation ->
                conversationMoveTarget = conversation
            },
            onConversationTogglePin = { conversation ->
                agentState.toggleConversationPinned(conversation.id)
            },
            onConversationToggleArchive = { conversation ->
                agentState.setConversationArchived(conversation.id, !conversation.isArchived)
            },
            onSelectArchive = { showing -> agentState.selectArchiveView(showing) },
            onOpenManageChats = { pushRoute(AppRoute.ManageChats) },
            onSelectFolder = { folderId -> agentState.selectFolder(folderId) },
            onCreateFolder = { name -> agentState.createFolder(name) },
            onRenameFolder = { folderId, name -> agentState.renameFolder(folderId, name) },
            onDeleteFolder = { folderId -> agentState.deleteFolder(folderId) },
            onOpenTools = { pushRoute(AppRoute.Tools) },
            onOpenSkills = { pushRoute(AppRoute.Skills) },
            onOpenCharacters = { pushRoute(AppRoute.Characters) },
            onOpenPermissions = { pushRoute(AppRoute.Permissions) },
            onOpenSettings = { pushRoute(AppRoute.Settings) },
            onOpenModelProviders = { pushRoute(AppRoute.ModelProviders) },
            homeCharacter = if (route is AppRoute.Home) agentState.homeState.roleplayChatUi() else null,
            onOpenCharacter = {
                agentState.homeState.roleplay?.let { pushRoute(AppRoute.CharacterDetail(it.characterId)) }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                content()
            }
        }
    }

    val swipeBackDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        NavSwipeDirection.RightToLeft
    } else {
        NavSwipeDirection.LeftToRight
    }
    val swipeDismiss = swipeBackDirection.takeIf {
        LocalAppearanceSettings.current.swipeDismissEnabled
    }
    key(navigationResetKey) {
        NavDisplay(
            backStack = backStack,
            onBack = { popRoute() },
            effects = NavDisplayEffects(
                cornerClipRadius = rememberNavSystemCornerRadius(),
            ),
        ) {
            entry<AppRoute.Home>(swipeDismiss = swipeDismiss) {
                RoutedShell(route = AppRoute.Home) {
                    AgentHomeScreen(
                        state = agentState.homeState,
                        modelPickerState = agentState.modelPickerState,
                        conversationKey = agentState.conversationPaneState.selectedConversationId,
                        onAction = { action ->
                            when (action) {
                                is AgentHomeAction.ReasoningEffortChanged ->
                                    agentState.updateReasoningEffort(action.effort)
                                AgentHomeAction.CompactContext -> agentState.compactCurrentContext()
                                AgentHomeAction.LoadEarlierMessages -> agentState.loadEarlierMessages()
                                is AgentHomeAction.HistoryViewportChanged -> agentState.updateHistoryViewport(action.atLatest)
                                AgentHomeAction.RetryConversationLoad -> agentState.retryConversationLoad()
                                is AgentHomeAction.ModelSelected -> agentState.selectModel(action.modelId)
                                is AgentHomeAction.SubmitMessage -> { requestExecutionNotifications(); agentState.sendCurrentMessage(action.text) }
                                is AgentHomeAction.InterruptAndSubmitMessage -> { requestExecutionNotifications(); agentState.interruptAndSendCurrentMessage(action.text) }
                                is AgentHomeAction.SteerQueuedMessage -> { requestExecutionNotifications(); agentState.steerQueuedMessage(action.id) }
                                is AgentHomeAction.RecallQueuedMessage -> agentState.recallQueuedMessage(action.id, action.currentDraftText)
                                is AgentHomeAction.RemoveQueuedMessage -> agentState.removeQueuedMessage(action.id)
                                AgentHomeAction.StopRun -> agentState.stopCurrentRun()
                                is AgentHomeAction.ImageAttached -> agentState.attachImage(action.uri)
                                is AgentHomeAction.RemoveImage -> agentState.removePendingImage(action.id)
                                is AgentHomeAction.FilesAttached -> agentState.attachFiles(action.uris)
                                is AgentHomeAction.FolderAttached -> agentState.attachFolder(action.uri)
                                is AgentHomeAction.FilePathAttached -> agentState.attachFilePath(action.path)
                                is AgentHomeAction.RemoveFileReference ->
                                    agentState.removePendingFileReference(action.id)
                                is AgentHomeAction.EditMessage -> agentState.beginMessageEdit(action.id)
                                AgentHomeAction.CancelMessageEdit -> agentState.cancelMessageEdit()
                                is AgentHomeAction.DeleteMessage -> {
                                    agentState.messageRevisionImpact(action.id)?.let { impact ->
                                        messageDeleteTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                is AgentHomeAction.RegenerateMessage -> {
                                    val impact = agentState.messageRevisionImpact(action.id)
                                    if (agentState.homeState.roleplay != null || impact?.laterTurnCount == 0) {
                                        agentState.regenerateMessage(action.id)
                                    } else if (impact != null) {
                                        messageRegenerateTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                is AgentHomeAction.SelectReplyCandidate -> agentState.selectReplyCandidate(action.id, action.index)
                                AgentHomeAction.OpenTools -> pushRoute(AppRoute.Tools)
                                AgentHomeAction.OpenSkills -> pushRoute(AppRoute.Skills)
                                AgentHomeAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                                AgentHomeAction.OpenSystemEnhance -> pushRoute(AppRoute.SystemEnhance)
                                AgentHomeAction.OpenSettings -> pushRoute(AppRoute.Settings)
                                AgentHomeAction.OpenBrowser -> pushRoute(AppRoute.Browser)
                                AgentHomeAction.ExpandRunTrace -> Unit
                            }
                        },
                        isLoadingConversation = agentState.isLoadingConversation,
                        conversationLoadFailed = agentState.conversationLoadFailed,
                        historyLoadState = agentState.historyLoadState,
                        isDrawerOpen = conversationPaneOpen,
                        scrollToTopRequest = chatScrollToTopRequest,
                        workProcessRequest = chatWorkProcessRequest,
                    )
                }
            }
            entry<AppRoute.ManageChats>(swipeDismiss = null) {
                ManageChatsScreen(
                    conversations = agentState.conversationPaneState.historyConversations,
                    folders = agentState.conversationPaneState.folders,
                    onBack = ::popRoute,
                    onOpenConversation = { conversationId ->
                        popRoute()
                        selectConversation(conversationId)
                    },
                    onTogglePin = { conversation -> agentState.toggleConversationPinned(conversation.id) },
                    onRename = { conversation -> conversationRenameTarget = conversation },
                    onDelete = { conversation -> conversationDeleteTarget = conversation },
                    onToggleArchive = { conversation ->
                        agentState.setConversationArchived(conversation.id, !conversation.isArchived)
                    },
                    onArchiveSelected = { ids, archived -> agentState.archiveConversations(ids, archived) },
                    onPinSelected = { ids, pinned -> agentState.pinConversations(ids, pinned) },
                    onMoveSelected = { ids, folderId -> agentState.moveConversationsToFolder(ids, folderId) },
                    onDeleteSelected = { ids -> agentState.deleteConversations(ids) },
                )
            }
            entry<AppRoute.Browser>(swipeDismiss = swipeDismiss) {
                RoutedShell(route = AppRoute.Browser) {
                    AgentBrowserScreen()
                }
            }
            entry<AppRoute.Terminal>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) { requestExecutionNotifications() }
                RoutedShell(route = AppRoute.Terminal) {
                    TerminalEntryScreen(
                        terminalStore = appViewModel.terminalStore,
                        consoleStore = appViewModel.consoleStore,
                        onOpenEnvironment = { pushRoute(AppRoute.LinuxEnvironment) },
                    )
                }
            }
            entry<AppRoute.Tools>(swipeDismiss = swipeDismiss) {
                AgentToolsScreen(
                    state = toolsState,
                    onAction = { action ->
                        when (action) {
                            AgentToolsAction.NavigateBack -> popRoute()
                            AgentToolsAction.OpenBrowser -> pushRoute(AppRoute.Browser)
                            AgentToolsAction.OpenWebToolsSettings -> pushRoute(AppRoute.WebToolsSettings)
                            AgentToolsAction.OpenEnhancements -> pushRoute(AppRoute.SystemEnhance)
                            AgentToolsAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                        }
                    },
                )
            }
            entry<AppRoute.WebToolsSettings>(swipeDismiss = swipeDismiss) {
                WebToolsSettingsScreen(onBack = ::popRoute)
            entry<AppRoute.Scripts>(swipeDismiss = swipeDismiss) {
                ScriptManagerScreen(store = appViewModel.scriptStore, onBack = ::popRoute)
            }
            entry<AppRoute.Skills>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) {
                    skills.refreshSkills()
                }
                AgentSkillsScreen(
                    state = skills.skillsState,
                    onAction = { action ->
                        when (action) {
                            AgentSkillsAction.NavigateBack -> popRoute()
                            is AgentSkillsAction.ImportZip -> skills.importSkillZip(action.uri)
                            AgentSkillsAction.ConfirmZipReplacement -> skills.confirmSkillZipReplacement()
                            AgentSkillsAction.CancelZipReplacement -> skills.cancelSkillZipReplacement()
                            AgentSkillsAction.DismissNotice -> skills.dismissSkillNotice()
                            is AgentSkillsAction.ToggleSkill -> skills.toggleSkill(action.skillId, action.enabled)
                            is AgentSkillsAction.DeleteSkill -> skills.deleteSkill(action.skillId)
                            is AgentSkillsAction.ReinstallBuiltin -> skills.reinstallBuiltin(action.skillId)
                        }
                    },
                )
            }
            entry<AppRoute.Characters>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(backStack.lastOrNull() == AppRoute.Characters) {
                    if (backStack.lastOrNull() == AppRoute.Characters) characterStore.loadLibrary()
                }
                CharacterLibraryScreen(characterStore, { if (navigator.current() == AppRoute.Characters) pushRoute(it) }, ::popRoute)
            }
            entry<AppRoute.CharacterDetail>(swipeDismiss = swipeDismiss) { route ->
                LaunchedEffect(route.characterId, backStack.lastOrNull() == route) {
                    if (backStack.lastOrNull() == route) characterStore.loadDetail(route.characterId)
                }
                CharacterDetailScreen(route.characterId, characterStore, { if (navigator.current() == route) pushRoute(it) }, ::popRoute) { binding, greeting ->
                    if (navigator.current() == route) {
                        agentState.startCharacterConversation(binding, greeting)
                        conversationPaneOpen = false
                        navigator.popToHome()
                        // 开始新故事直接呈现首页；重置导航呈现态，避免多层退栈扫过角色列表。
                        navigationResetKey++
                    }
                }
            }
            entry<AppRoute.CharacterEditor>(swipeDismiss = swipeDismiss) { route ->
                LaunchedEffect(route.characterId, backStack.lastOrNull() == route) {
                    if (backStack.lastOrNull() == route) characterStore.loadEditor(route.characterId)
                }
                CharacterEditorScreen(route.characterId, characterStore, ::popRoute) { id ->
                    if (navigator.current() == route) navigator.replace(AppRoute.CharacterDetail(id))
                }
            }
            entry<AppRoute.CharacterPersona>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(backStack.lastOrNull() == AppRoute.CharacterPersona) {
                    if (backStack.lastOrNull() == AppRoute.CharacterPersona) characterStore.loadPersona()
                }
                CharacterPersonaScreen(characterStore) {
                    if (navigator.current() == AppRoute.CharacterPersona) popRoute()
                }
            }
            entry<AppRoute.CharacterMemory>(swipeDismiss = swipeDismiss) { route ->
                LaunchedEffect(route.characterId, backStack.lastOrNull() == route) {
                    if (backStack.lastOrNull() == route) characterStore.loadMemory(route.characterId)
                }
                CharacterMemoryScreen(route.characterId, characterStore, ::popRoute)
            }
            entry<AppRoute.Permissions>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) {
                    permissionHealth.refreshPermissionHealth()
                }
                PermissionHealthScreen(
                    state = permissionHealth.permissionHealthState,
                    onAction = { action ->
                        when (action) {
                            PermissionHealthAction.NavigateBack -> popRoute()
                            is PermissionHealthAction.OpenItemAction -> {
                                when (action.itemId) {
                                    LOCAL_NETWORK_PERMISSION_ITEM_ID -> requestLocalNetworkPermission()
                                    "calendar" -> locationPermissionLauncher.launch(io.github.mangi.eta.agent.device.CalendarPermissions.requested)
                                    "notification_policy" -> context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                                    "accessibility" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                        }
                                    }
                                    "overlay" -> {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                            )
                                        }
                                    }
                                    "background" -> {
                                        if (RootAccess.isGranted && Build.MANUFACTURER.lowercase() in setOf("oppo", "realme", "oneplus")) {
                                            uiScope.launch(Dispatchers.IO) {
                                                BoundedRootCommandExecutor(AndroidAgentLogger).use {
                                                    it.execute(
                                                        "am start --user current -n " +
                                                            "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerControlActivity " +
                                                            "--es title Eta --es pkgName io.github.mangi.eta --es drainType APP",
                                                    )
                                                }
                                            }
                                        } else {
                                            runCatching {
                                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                            }
                                        }
                                    }
                                    "app_list" -> {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                            )
                                        }
                                    }
                                    "location" -> {
                                        when (DeviceLocationProvider.accessState(context)) {
                                            DeviceLocationProvider.AccessState.DENIED -> {
                                                locationPermissionLauncher.launch(
                                                    arrayOf(
                                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                                    )
                                                )
                                            }
                                            DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(
                                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                            Uri.parse("package:${context.packageName}")
                                                        )
                                                    )
                                                }
                                            }
                                            DeviceLocationProvider.AccessState.DISABLED -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                                    )
                                                }
                                            }
                                            DeviceLocationProvider.AccessState.AVAILABLE -> {
                                                permissionHealth.refreshPermissionHealth()
                                            }
                                        }
                                    }
                                    "notification_history" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                        }
                                    }
                                    "usage_access" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                        }
                                    }
                                    "notifications" -> {
                                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                    }
                                    "root" -> pushRoute(AppRoute.SystemEnhance)
                                }
                            }
                        }
                    },
                )
            }
            entry<AppRoute.SystemEnhance>(swipeDismiss = swipeDismiss) {
                SystemEnhanceScreen(
                    onAction = { action ->
                        when (action) {
                            AgentSystemEnhanceAction.NavigateBack -> popRoute()
                            AgentSystemEnhanceAction.RequestRoot -> { RootAccess.request(context) }
                            AgentSystemEnhanceAction.RefreshRoot -> { RootAccess.refresh(context) }
                        }
                    },
                )
            }
            entry<AppRoute.Workspace>(swipeDismiss = swipeDismiss) {
                WorkspaceScreen(onBack = ::popRoute)
            }
            entry<AppRoute.Settings>(swipeDismiss = swipeDismiss) {
                SettingsScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.SpeechSettings>(swipeDismiss = swipeDismiss) {
                SpeechSettingsScreen(
                    onBack = ::popRoute,
                    onNavigate = { route -> pushRoute(route) },
                )
            }
            entry<AppRoute.SpeechRecognition>(swipeDismiss = swipeDismiss) {
                SpeechRecognitionScreen(
                    onBack = ::popRoute,
                    onOpenOss = { pushRoute(AppRoute.SpeechOss) },
                )
            }
            entry<AppRoute.SpeechSynthesis>(swipeDismiss = swipeDismiss) {
                SpeechSynthesisScreen(onBack = ::popRoute)
            }
            entry<AppRoute.SpeechOss>(swipeDismiss = swipeDismiss) {
                SpeechOssScreen(onBack = ::popRoute)
            }
            entry<AppRoute.AppearanceSettings>(swipeDismiss = swipeDismiss) {
                AppearanceSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.DataBackup>(swipeDismiss = swipeDismiss) {
                DataBackupScreen(
                    context = context,
                    onBack = ::popRoute,
                    onExport = agentState::exportBackup,
                    onImport = agentState::importBackup,
                )
            }
            entry<AppRoute.Memory>(swipeDismiss = swipeDismiss) {
                LaunchedEffect(Unit) {
                    memory.refreshMemory()
                }
                AgentMemoryScreen(
                    state = memory.memoryState,
                    onAction = { action ->
                        when (action) {
                            AgentMemoryAction.NavigateBack -> {
                                memory.collapseFile()
                                popRoute()
                            }
                            is AgentMemoryAction.ToggleEnabled -> memory.setMemoryEnabled(action.enabled)
                            is AgentMemoryAction.DraftChanged -> memory.updateMemoryDraft(action.content)
                            AgentMemoryAction.Save -> memory.saveMemory()
                            AgentMemoryAction.Clear -> memory.clearMemory()
                            AgentMemoryAction.DismissNotice -> memory.dismissMemoryNotice()
                            is AgentMemoryAction.ArchiveSections -> memory.archiveSections(action.titles)
                            AgentMemoryAction.RestoreArchive -> memory.restoreArchive()
                            is AgentMemoryAction.UnarchiveSections -> memory.unarchiveSections(action.titles)
                            AgentMemoryAction.UndoArchive -> memory.undoArchive()
                            AgentMemoryAction.ClearUndo -> memory.clearUndo()
                            is AgentMemoryAction.ProfileDraftChanged -> memory.updateProfileDraft(action.content)
                            is AgentMemoryAction.WorkingDraftChanged -> memory.updateWorkingDraft(action.content)
                            AgentMemoryAction.SaveProfile -> memory.saveProfile()
                            AgentMemoryAction.SaveWorking -> memory.saveWorking()
                            AgentMemoryAction.ClearProfile -> memory.clearProfile()
                            AgentMemoryAction.ClearWorking -> memory.clearWorking()
                            is AgentMemoryAction.ExpandFile -> {
                                memory.expandFile(action.name)
                                pushRoute(AppRoute.MemoryArchive(action.name))
                            }
                            AgentMemoryAction.CollapseFile -> memory.collapseFile()
                            is AgentMemoryAction.ArchiveFiles -> memory.archiveFiles(action.names)
                            is AgentMemoryAction.FileDraftChanged -> memory.updateFileDraft(action.content)
                            AgentMemoryAction.SaveFile -> memory.saveFile()
                            is AgentMemoryAction.MergeFiles -> memory.mergeFiles(action.names)
                            is AgentMemoryAction.ExpandSection -> memory.expandSection(action.title)
                            AgentMemoryAction.CollapseSection -> memory.collapseSection()
                            else -> Unit
                        }
                    },
                )
            }
            entry<AppRoute.MemoryArchive>(swipeDismiss = swipeDismiss) { route ->
                LaunchedEffect(route.name) {
                    if (memory.memoryState.expandedFileName != route.name) memory.expandFile(route.name)
                }
                io.github.mangi.eta.ui.screens.memory.MemoryArchiveScreen(
                    state = memory.memoryState,
                    onAction = { action ->
                        when (action) {
                            AgentMemoryAction.NavigateBack -> popRoute()
                            AgentMemoryAction.DismissNotice -> memory.dismissMemoryNotice()
                            AgentMemoryAction.CollapseFile -> {
                                memory.collapseFile()
                                popRoute()
                            }
                            is AgentMemoryAction.FileDraftChanged -> memory.updateFileDraft(action.content)
                            AgentMemoryAction.SaveFile -> memory.saveFile()
                            AgentMemoryAction.TidyFile -> memory.tidyFile()
                            AgentMemoryAction.TidyConfirmed -> memory.confirmTidy()
                            AgentMemoryAction.TidyDiscarded -> memory.discardTidy()
                            is AgentMemoryAction.SplitFile -> memory.splitFile(action.name)
                            is AgentMemoryAction.SplitRename -> memory.renameSplitPart(action.index, action.name)
                            is AgentMemoryAction.SplitToggle -> memory.toggleSplitPart(action.index)
                            is AgentMemoryAction.SplitDeleteOriginal -> memory.setSplitDeleteOriginal(action.enabled)
                            AgentMemoryAction.SplitConfirmed -> memory.confirmSplit()
                            AgentMemoryAction.SplitDiscarded -> memory.discardSplit()
                            is AgentMemoryAction.ArchiveFiles -> {
                                memory.archiveFiles(action.names)
                                popRoute()
                            }
                            else -> Unit
                        }
                    },
                )
            }
            entry<AppRoute.VirtualScreen>(swipeDismiss = swipeDismiss) {
                io.github.mangi.eta.ui.screens.tasks.VirtualScreenSettingsScreen(onBack = { popRoute() })
            }
            entry<AppRoute.LinuxEnvironment>(swipeDismiss = swipeDismiss) {
                LinuxEnvironmentScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.SharedFolders>(swipeDismiss = swipeDismiss) {
                SharedFoldersScreen(
                    context = context,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.LinuxFiles>(swipeDismiss = swipeDismiss) { route ->
                LinuxFilesScreen(
                    context = context,
                    distribution = route.distribution,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ModelProviders>(swipeDismiss = swipeDismiss) {
                ModelProviderListScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.CommunityCatalog>(swipeDismiss = swipeDismiss) {
                CommunityCatalogScreen(
                    store = communityCatalogStore,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.CommunityCatalogProvider>(swipeDismiss = swipeDismiss) { route ->
                CommunityCatalogProviderScreen(
                    catalogId = route.catalogId,
                    store = communityCatalogStore,
                    onImported = { providerId -> navigator.replace(AppRoute.ModelProviderDetail(providerId)) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.McpServers>(swipeDismiss = swipeDismiss) {
                McpServersScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.McpServerDetail>(swipeDismiss = swipeDismiss) { route ->
                McpServerDetailScreen(
                    serverId = route.serverId,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ModelProviderDetail>(swipeDismiss = swipeDismiss) { route ->
                ModelProviderDetailScreen(
                    providerId = route.providerId,
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.ModelProviderNew>(swipeDismiss = swipeDismiss) { route ->
                ModelProviderDetailScreen(
                    newType = route.providerType,
                    onBack = ::popRoute
                )
            }
        }
    }

    characterStore.notice?.let { notice ->
        WindowDialog(
            show = true,
            title = "角色",
            summary = notice,
            cornerRadius = DialogDefaults.CornerRadius,
            onDismissRequest = characterStore::dismissNotice,
        ) {
            top.yukonga.miuix.kmp.basic.TextButton(
                text = "知道了", onClick = characterStore::dismissNotice, modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    conversationMoveTarget?.let { conversation ->
        WindowDialog(
            show = true,
            cornerRadius = DialogDefaults.CornerRadius,
            title = stringResource(R.string.action_move_to_folder),
            onDismissRequest = { conversationMoveTarget = null },
        ) {
            Column {
                MoveToFolderRow(
                    label = stringResource(R.string.move_to_folder_unfiled),
                    selected = conversation.folderId.isBlank(),
                    onClick = {
                        agentState.moveConversationToFolder(conversation.id, null)
                        conversationMoveTarget = null
                    },
                )
                agentState.conversationPaneState.folders.forEach { folder ->
                    MoveToFolderRow(
                        label = folder.name,
                        selected = conversation.folderId == folder.id,
                        onClick = {
                            agentState.moveConversationToFolder(conversation.id, folder.id)
                            conversationMoveTarget = null
                        },
                    )
                }
            }
        }
    }

    conversationRenameTarget?.let { conversation ->
        var renameInput by remember(conversation.id) { mutableStateOf(conversation.title) }
        WindowDialog(
            show = true,
            cornerRadius = DialogDefaults.CornerRadius,
            title = stringResource(R.string.conversation_rename_title),
            onDismissRequest = { conversationRenameTarget = null },
        ) {
            Column {
                TextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = stringResource(R.string.conversation_rename_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MiuixDialogActions(
                    confirmText = stringResource(R.string.action_save),
                    confirmEnabled = renameInput.isNotBlank(),
                    onCancel = { conversationRenameTarget = null },
                    onConfirm = {
                        agentState.renameConversation(conversation.id, renameInput)
                        conversationRenameTarget = null
                    },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }

    conversationDeleteTarget?.let { conversation ->
        WindowDialog(
            show = true,
            cornerRadius = DialogDefaults.CornerRadius,
            title = stringResource(R.string.conversation_delete_title),
            summary = stringResource(R.string.conversation_delete_message),
            onDismissRequest = { conversationDeleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { conversationDeleteTarget = null },
                onConfirm = {
                    agentState.deleteConversation(conversation.id)
                    conversationDeleteTarget = null
                },
            )
        }
    }

    messageDeleteTarget?.let { target ->
        WindowDialog(
            show = true,
            cornerRadius = DialogDefaults.CornerRadius,
            title = stringResource(R.string.conversation_delete_message_title),
            summary = if (target.laterTurnCount == 0) {
                stringResource(R.string.conversation_delete_message_body)
            } else {
                pluralStringResource(
                    R.plurals.conversation_delete_later_turns,
                    target.laterTurnCount,
                    target.laterTurnCount,
                )
            },
            onDismissRequest = { messageDeleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { messageDeleteTarget = null },
                onConfirm = {
                    agentState.deleteMessageTurn(target.messageId)
                    messageDeleteTarget = null
                },
            )
        }
    }

    messageRegenerateTarget?.let { target ->
        WindowDialog(
            show = true,
            cornerRadius = DialogDefaults.CornerRadius,
            title = stringResource(R.string.conversation_regenerate_title),
            summary = if (target.laterTurnCount == 0) {
                stringResource(R.string.conversation_regenerate_current_turn)
            } else {
                pluralStringResource(
                    R.plurals.conversation_regenerate_later_turns,
                    target.laterTurnCount,
                    target.laterTurnCount,
                )
            },
            onDismissRequest = { messageRegenerateTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_regenerate),
                destructive = true,
                onCancel = { messageRegenerateTarget = null },
                onConfirm = {
                    agentState.regenerateMessage(target.messageId)
                    messageRegenerateTarget = null
                },
            )
        }
    }
}

@Composable
private fun MoveToFolderRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.body1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

private data class MessageMutationTarget(
    val messageId: String,
    val laterTurnCount: Int,
)
