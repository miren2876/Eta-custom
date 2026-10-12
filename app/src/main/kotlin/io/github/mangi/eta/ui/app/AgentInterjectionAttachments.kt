package io.github.mangi.eta.ui.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 中途插话的附件暂存。
 *
 * runtime 的插话通道只收文本，所以带附件的插话这样处理：把图片落到应用私有目录，
 * 再把路径写进插话文本，模型在当前这一轮按需用 `read_image` / `read_file` 读取。
 * 文件引用本来就有绝对路径，直接用。
 */
internal object AgentInterjectionAttachments {
    private const val DIR_NAME = "interjection-attachments"

    /** 返回附加说明（形如 `（附件：图片：/path（需要时用 read_image 查看））`）；没有可用附件时返回空串。 */
    suspend fun describe(
        context: Context,
        images: List<PendingImageUi>,
        files: List<PendingFileReferenceUi>,
    ): String = withContext(Dispatchers.IO) {
        val parts = mutableListOf<String>()
        images.forEachIndexed { index, image ->
            stagedImagePath(context, image, index)?.let { path ->
                parts += "图片：$path（需要时用 read_image 查看）"
            }
        }
        files.forEach { pending ->
            val path = pending.reference.absolutePath.trim()
            if (path.isNotEmpty()) {
                parts += "文件：$path（需要时用 read_file 读取）"
            }
        }
        if (parts.isEmpty()) "" else "（附件：${parts.joinToString("；")}）"
    }

    /** 图片 → 可读路径：本地路径直接用，data URL 解码落盘，content URI 复制落盘，远端地址只能给 URL。 */
    private fun stagedImagePath(context: Context, image: PendingImageUi, index: Int): String? {
        val uri = image.uri.trim()
        localPathOf(uri)?.let { path -> if (File(path).exists()) return path }

        val dataUrl = image.dataUrl.trim()
        if (dataUrl.startsWith("data:")) {
            runCatching {
                val bytes = Base64.decode(dataUrl.substringAfter(',', ""), Base64.DEFAULT)
                val file = targetFile(context, index, extensionFor(image.mimeType))
                file.writeBytes(bytes)
                return file.absolutePath
            }
        }

        if (uri.startsWith("content://")) {
            runCatching {
                val file = targetFile(context, index, extensionFor(image.mimeType))
                val opened = context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
                if (opened) return file.absolutePath
            }
        }

        // 远端 URL：没法本地化，只能把地址交给模型。
        return uri.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun localPathOf(uri: String): String? = when {
        uri.startsWith("file://") -> Uri.parse(uri).path
        uri.startsWith("/") -> uri
        else -> null
    }

    private fun targetFile(context: Context, index: Int, extension: String): File {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        return File(dir, "attach-${System.currentTimeMillis()}-$index.$extension")
    }

    private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        else -> "jpg"
    }
}
