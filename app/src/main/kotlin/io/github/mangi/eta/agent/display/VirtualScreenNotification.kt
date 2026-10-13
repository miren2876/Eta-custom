package io.github.mangi.eta.agent.display

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType

/**
 * 保留的虚拟屏会话在通知栏的入口。
 *
 * Agent run 进行期间不单独显示：执行通知（前台服务必需）此时已指向查看页，
 * 两条「正在运行」并存会被误认为重复。run 结束后屏幕仍然保留，这时才显示本通知。
 *
 * 通知使用固定 ID，避免旧会话的残留通知与当前会话并存。
 */
internal object VirtualScreenNotification {
    private const val CHANNEL = "eta_virtual_screen"
    private const val ID = 1110

    /** 会话或运行状态变化后重新判定是否需要显示。 */
    fun refresh(context: Context) {
        val sessionId = VirtualScreenSession.currentSessionId()
        if (sessionId == null || AgentExecutionService.isRunActive()) {
            cancel(context)
            return
        }
        show(context, sessionId)
    }

    fun show(context: Context, sessionId: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, context.getString(R.string.virtual_screen_notification_channel), NotificationManager.IMPORTANCE_LOW,
            ))
            val open = PendingIntent.getActivity(context, 0,
                Intent(context, VirtualScreenViewerActivity::class.java)
                    .setData(Uri.parse("eta-virtual-screen://view/$sessionId"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(ID, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.virtual_screen_notification_title))
                .setContentText(context.getString(R.string.virtual_screen_notification_content))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build())
        }.onFailure { AndroidAgentLogger.warn("Virtual screen notification failed: type=${it.safeLogType()}") }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    /** 按会话取消：本实现用固定 ID，多会话不会并存，sessionId 只作兼容参数。 */
    fun cancel(context: Context, sessionId: String) {
        cancel(context)
    }
}
