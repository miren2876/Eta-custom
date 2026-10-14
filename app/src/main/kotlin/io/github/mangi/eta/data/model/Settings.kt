package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val memoryEnabled: Boolean = true,
    val virtualScreenEnabled: Boolean = false,
    val virtualScreenOffEnabled: Boolean = false,
    val virtualScreenFallbackEnabled: Boolean = false,
    val virtualScreenAutoRestartApps: Boolean = false,
    val virtualScreenIdleTimeoutMinutes: Int = 20,
    val virtualScreenFrameRate: Int = 60,
    val virtualScreenFloatWindowEnabled: Boolean = false,
    val virtualScreenFloatEdgeCollapseEnabled: Boolean = true,
    val virtualScreenFloatWindowWidthDp: Int = 0,
    val virtualScreenFloatWindowHeightDp: Int = 0,
    val virtualScreenFloatWindowAlpha: Float = 0.85f,
    val virtualScreenFloatWindowPosX: Int = 16,
    val virtualScreenFloatWindowPosY: Int = 120,
    /** 球贴在左侧(0)还是右侧(1)；重启后小窗按它回到同一侧。 */
    val virtualScreenFloatWindowBubbleSide: Int = 0,
    /** 球的纵向位置；-1 表示还没落过位，按当前窗口位置推算。 */
    val virtualScreenFloatWindowBubbleY: Int = -1,
    /** 打开 Eta 后悬浮胶囊常驻显示：没有任务时也留在屏幕上，点它看运行中会话。 */
    val overlayCapsulePinned: Boolean = false,
    val appearance: AppearanceSettings = AppearanceSettings(),
)

internal object VirtualScreenIdleTimeout {
    val options = listOf(10, 20, 60, 0)
    fun normalize(minutes: Int): Int = minutes.takeIf { it in options } ?: 20
}

/** 虚拟屏出帧目标帧率；越低越省电，越高越跟手。 */
internal object VirtualScreenFrameRate {
    val options = listOf(60, 75, 90)
    fun normalize(value: Int): Int = value.takeIf { it in options } ?: 60
    /** Root 进程 JPEG 编码间隔（毫秒）。 */
    fun intervalMs(value: Int): Int = (1000 / normalize(value)).coerceIn(8, 66)
    /** 帧率越高，单帧 JPEG 质量略降，避免编码跟不上导致卡顿。 */
    fun jpegQuality(value: Int): Int = if (normalize(value) >= 75) 60 else 72
}
