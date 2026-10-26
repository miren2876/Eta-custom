package io.github.mangi.eta.agent.display

import android.view.Surface
import java.util.UUID

/**
 * 直通出帧（A′）：把本机 Surface 交给 root helper，当虚拟屏的输出面。
 * 画面由 SurfaceFlinger 直接合成进本 Surface，不再走截屏→JPEG→base64→解码。
 */
internal object VirtualScreenHostSurfaces {
    const val AUTHORITY = "io.github.mangi.xiaoaiwindow.hostsurface"

    /** 两个宿主面的标识：直通面同一时刻只归其中一个。 */
    const val VIEWER_HOST = "viewer"
    const val FLOAT_HOST = "float"

    val token: String = UUID.randomUUID().toString()

    private val slots = LinkedHashMap<String, Surface>()

    @Volatile
    var activeHost: String? = null
        private set

    /** 最近一次真正下发过的 (host, surface) 指纹，避免重复下发。 */
    @Volatile
    private var sentKey: String? = null

    /** 活动 host 变化（含置空）时回调，由持有会话的一侧去下发指令。 */
    @Volatile
    var onActiveChanged: (() -> Unit)? = null

    /** helper 是否已用宿主面（直通）建屏：create 响应 direct=true 才允许界面切直通。 */
    @Volatile
    var live: Boolean = false

    /**
     * 直通出帧总开关（默认关）。
     *
     * 关掉时不上报宿主面，helper 取不到面就退回整帧管线。原因是硬限制：
     * 一个虚拟屏只有一个直通输出面，小窗与查看页同时存在时必有一个拿不到画面；
     * 而整帧管线两个预览都能显示。等触摸/直通链路都验证稳了再打开。
     */
    @Volatile
    var enabled: Boolean = false

    fun attach(host: String, surface: Surface?) {
        if (!enabled) return
        synchronized(slots) {
            if (surface == null) {
                slots.remove(host)
            } else {
                slots[host] = surface
            }
            activeHost = pickActive()
        }
        onActiveChanged?.invoke()
    }

    /** 查看页在前台时优先把直通面给它；否则优先小窗（小窗是常驻预览）。 */
    private fun pickActive(): String? {
        if (VirtualScreenFrameGate.viewerForeground && slots.containsKey(VIEWER_HOST)) return VIEWER_HOST
        if (slots.containsKey(FLOAT_HOST)) return FLOAT_HOST
        return slots.keys.lastOrNull()
    }

    /** 前后台切换后重选活动面：直通面只能有一个，另一个必须留在整帧管线。 */
    fun refreshActive() {
        val next: String?
        val changed: Boolean
        synchronized(slots) {
            next = pickActive()
            changed = next != activeHost
            activeHost = next
        }
        if (changed) {
            onActiveChanged?.invoke()
        }
    }

    fun currentHost(): String? = synchronized(slots) { activeHost }

    fun currentSurface(): Surface? = synchronized(slots) { activeHost?.let { slots[it] } }

    /** (host, surface 代次) 指纹：同一次交接不重复下发。 */
    fun key(): String = "$token:${currentHost().orEmpty()}:${currentSurface()?.hashCode() ?: 0}"

    fun markSent(value: String) {
        sentKey = value
    }

    fun alreadySent(): Boolean = sentKey == key()

    fun resetSent() {
        sentKey = null
    }
}
