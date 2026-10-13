package io.github.mangi.eta.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.display.VirtualScreenProfile
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.display.VirtualScreenViewerActivity
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 溢出菜单里的「打开虚拟屏」入口；虚拟屏未启用时返回 null（调用方据此不显示该项）。
 * 打开查看器后，会话创建与目标应用启动放到后台，全程走 display 隔离路由。
 */
@Composable
internal fun rememberVirtualScreenMenuAction(): (() -> Unit)? {
    val context = LocalContext.current
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = Settings())
    if (!settings.virtualScreenEnabled) return null
    val scope = rememberCoroutineScope()
    return {
        runCatching { context.startActivity(Intent(context, VirtualScreenViewerActivity::class.java)) }
        scope.launch {
            if (!openVirtualScreen(context)) {
                Toast.makeText(context, context.getString(R.string.virtual_screen_manual_failed), Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** 拉起查看器并准备手动会话；component 非空时直接启动该应用。 */
internal suspend fun openVirtualScreen(context: Context, component: String? = null): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            var created = false
            if (!VirtualScreenSession.isActive()) {
                val base = VirtualScreenProfile.from(context)
                // 手动会话默认降一档分辨率/密度：编解码开销随像素下降，滑动明显更跟手。
                val result = JSONObject(
                    VirtualScreenSession.execute(
                        context, MANUAL_OWNER, JSONObject()
                            .put("action", "create").put("width", (base.width * MANUAL_SCALE).roundToInt())
                            .put("height", (base.height * MANUAL_SCALE).roundToInt())
                            .put("density", (base.density * MANUAL_SCALE).roundToInt())
                    ).content
                )
                require(result.optBoolean("ok")) { result.optString("code") }
                created = true
            }
            val info = VirtualScreenSession.state.value.display ?: error("NO_VIRTUAL_SCREEN")
            if (!component.isNullOrBlank()) {
                val launched = JSONObject(VirtualScreenSession.launchForViewer(context, info.sessionId, component).content)
                require(launched.optBoolean("ok")) { launched.optString("code") }
            } else if (created) {
                val home = VirtualScreenSession.homeForViewer(context, info.sessionId, "APPS")
            }
            true
        }.getOrDefault(false)
    }


@Composable
private fun ShortcutIcon(bitmap: android.graphics.Bitmap, modifier: Modifier) {
    Image(bitmap.asImageBitmap(), contentDescription = null, modifier = modifier)
}

/** 顶栏胶囊样式：与聊天页状态胶囊一致（等价 03 层的 StatusCapsule）。 */
@Composable
internal fun StatusCapsule(text: String, modifier: Modifier = Modifier, description: String = text) {
    Text(text,
        modifier = modifier.background(MiuixTheme.colorScheme.surfaceContainer, CircleShape)
            .semantics { contentDescription = description }.padding(horizontal = 8.dp, vertical = 5.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1, fontSize = 11.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** 手动会话的 owner；与 Agent run 的 owner 区分，互不抢锁。 */
internal const val MANUAL_OWNER = "manual"

/** 应用菜单与顶栏直通车的收藏上限。 */
internal const val MAX_VIRTUAL_SCREEN_FAVORITES = 3

/** 手动会话分辨率/密度缩放：等比缩小以降低每帧编解码开销。 */
private const val MANUAL_SCALE = 0.72f

private data class AppShortcut(
    val label: String,
    val component: String,
    val icon: android.graphics.Bitmap?,
)

/** 取包名对应的启动入口、显示名与图标；没有可启动入口就跳过。 */
private fun appShortcut(context: Context, packageName: String): AppShortcut? {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
    val resolved = runCatching { context.packageManager.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
    val info = resolved.firstOrNull() ?: return null
    val label = runCatching {
        info.loadLabel(context.packageManager).toString().take(8).ifBlank { packageName }
    }.getOrDefault(packageName)
    val icon = runCatching {
        info.loadIcon(context.packageManager)
            .toBitmap(48, 48)
    }.getOrNull()
    return AppShortcut(label, info.activityInfo.packageName + "/" + info.activityInfo.name, icon)
}
