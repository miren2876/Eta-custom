package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.model.ReasoningEffort

sealed interface AgentHomeAction {
    data object CompactContext : AgentHomeAction
    data class ReasoningEffortChanged(val effort: ReasoningEffort) : AgentHomeAction
    data class ModelSelected(val modelId: String) : AgentHomeAction
    data class SubmitMessage(val text: String) : AgentHomeAction
    data object StopRun : AgentHomeAction
    data class ImageAttached(val uri: String) : AgentHomeAction
    data class RemoveImage(val id: String) : AgentHomeAction
    data class FilesAttached(val uris: List<String>) : AgentHomeAction
    data class FolderAttached(val uri: String) : AgentHomeAction
    data class FilePathAttached(val path: String) : AgentHomeAction
    data class RemoveFileReference(val id: String) : AgentHomeAction
    data class EditMessage(val id: String) : AgentHomeAction
    data object CancelMessageEdit : AgentHomeAction
    data class DeleteMessage(val id: String) : AgentHomeAction
    data class RegenerateMessage(val id: String) : AgentHomeAction
    data class SelectReplyCandidate(val id: String, val index: Int) : AgentHomeAction
    data object OpenTools : AgentHomeAction
    data object OpenSkills : AgentHomeAction
    data object OpenPermissions : AgentHomeAction
    data object OpenSystemEnhance : AgentHomeAction
    data object OpenSettings : AgentHomeAction
    data object OpenBrowser : AgentHomeAction
    data object ExpandRunTrace : AgentHomeAction
}

sealed interface PermissionHealthAction {
    data class OpenItemAction(val itemId: String) : PermissionHealthAction
    data object NavigateBack : PermissionHealthAction
}

sealed interface AgentToolsAction {
    data object OpenEnhancements : AgentToolsAction
    data object OpenPermissions : AgentToolsAction
    data object NavigateBack : AgentToolsAction
    data object OpenBrowser : AgentToolsAction
}

sealed interface AgentSkillsAction {
    data object NavigateBack : AgentSkillsAction
    data class ImportZip(val uri: String) : AgentSkillsAction
    data object ConfirmZipReplacement : AgentSkillsAction
    data object CancelZipReplacement : AgentSkillsAction
    data object DismissNotice : AgentSkillsAction
    data class ToggleSkill(val skillId: String, val enabled: Boolean) : AgentSkillsAction
    data class DeleteSkill(val skillId: String) : AgentSkillsAction
    data class ReinstallBuiltin(val skillId: String) : AgentSkillsAction
}

sealed interface AgentSystemEnhanceAction {
    data object NavigateBack : AgentSystemEnhanceAction
    data object RequestRoot : AgentSystemEnhanceAction
    data object RefreshRoot : AgentSystemEnhanceAction
}

sealed interface AgentMemoryAction {
    data object NavigateBack : AgentMemoryAction
    data class ToggleEnabled(val enabled: Boolean) : AgentMemoryAction
    data class DraftChanged(val content: String) : AgentMemoryAction
    data object Save : AgentMemoryAction
    data object Clear : AgentMemoryAction
    data object DismissNotice : AgentMemoryAction

    /** 把选中的冷章节移入 memory/archive/（一档一文件）。 */
    data class ArchiveSections(val titles: List<String>) : AgentMemoryAction

    /** 把归档档案全部移回档案目录。 */
    data object RestoreArchive : AgentMemoryAction

    /** 把选中的归档档案移回档案目录（取消归档）。 */
    data class UnarchiveSections(val titles: List<String>) : AgentMemoryAction

    /** 撤销刚刚这一次归档（把这次归档的档案移回档案目录）。 */
    data object UndoArchive : AgentMemoryAction

    /** 离开记忆页时清掉撤销入口。 */
    data object ClearUndo : AgentMemoryAction

    /** 核心记忆：用户画像 / 工作记忆各自的编辑与保存。 */
    data class ProfileDraftChanged(val content: String) : AgentMemoryAction

    data class WorkingDraftChanged(val content: String) : AgentMemoryAction

    data object SaveProfile : AgentMemoryAction

    data object SaveWorking : AgentMemoryAction

    data object ClearProfile : AgentMemoryAction

    data object ClearWorking : AgentMemoryAction

    /** 展开 / 收起一个记忆档案（按需读取内容）。 */
    data class ExpandFile(val name: String) : AgentMemoryAction

    data object CollapseFile : AgentMemoryAction

    /** 档案归档与编辑保存。 */
    data class ArchiveFiles(val names: List<String>) : AgentMemoryAction

    data class FileDraftChanged(val content: String) : AgentMemoryAction

    data object SaveFile : AgentMemoryAction

    /** 把选中的档案合并成一个（用当前模型），并同步工作记忆。 */
    data class MergeFiles(val names: List<String>) : AgentMemoryAction

    /** 展开某一节的正文（点击章节行）。 */
    data class ExpandSection(val title: String) : AgentMemoryAction

    /** 收起已展开的章节正文。 */
    data object CollapseSection : AgentMemoryAction
}
