package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.PendingFileReferenceUi

/**
 * 中途插话的文件引用说明。
 *
 * 图片走原生通道：UI 把 `PendingImageUi` 交给 `AgentRuntimeClient.interjectRun`，
 * Runtime 侧物化成模型可用引用后直接挂到补充消息的 content 上。
 * **文件引用没有对应的原生通道**，所以这里把绝对路径写进插话文本，模型按需 `read_file`。
 */
internal object AgentInterjectionAttachments {
    /** 返回文件引用的附加说明；没有可用文件时返回空串。 */
    fun describeFiles(files: List<PendingFileReferenceUi>): String {
        val parts = files.mapNotNull { pending ->
            pending.reference.absolutePath.trim().takeIf { it.isNotEmpty() }?.let { path ->
                "文件：$path（需要时用 read_file 读取）"
            }
        }
        return if (parts.isEmpty()) "" else "（附件：${parts.joinToString("；")}）"
    }
}
