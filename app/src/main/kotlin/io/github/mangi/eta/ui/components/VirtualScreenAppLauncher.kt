package io.github.mangi.eta.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast

/** 虚拟屏独立应用的包名；手机操作走它的 MCP 服务，Eta 只保留入口。 */
private const val VIRTUAL_SCREEN_PACKAGE = "io.github.lejw0925.virtualscreen"

/**
 * 打开「虚拟屏」独立应用。
 *
 * Eta 不再内置虚拟显示：手机操作由该应用提供的 MCP 工具完成，
 * 这里只负责把用户带到它的界面（授权、后端选择、MCP 开关都在那边）。
 */
internal fun openVirtualScreenApp(context: Context): Boolean {
    val launch = context.packageManager.getLaunchIntentForPackage(VIRTUAL_SCREEN_PACKAGE)
        ?: run {
            Toast.makeText(context, "未安装「虚拟屏」应用", Toast.LENGTH_SHORT).show()
            return false
        }
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(launch) }.isSuccess
}

/** 顶栏「···」菜单里的虚拟屏入口：点击直接打开独立应用。 */
@androidx.compose.runtime.Composable
internal fun rememberOpenVirtualScreenAction(): (() -> Unit)? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) { { openVirtualScreenApp(context) } }
}
