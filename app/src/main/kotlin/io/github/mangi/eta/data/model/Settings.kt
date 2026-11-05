package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val memoryEnabled: Boolean = true,
    /** 打开 Eta 后悬浮胶囊常驻显示：没有任务时也留在屏幕上，点它看运行中会话。 */
    val overlayCapsulePinned: Boolean = false,
    /** 常驻悬浮球上次拖到的位置；-1 表示还没拖过，默认贴右侧。 */
    val overlayCapsulePosX: Int = -1,
    val overlayCapsulePosY: Int = -1,
    val appearance: AppearanceSettings = AppearanceSettings(),
)

