package io.github.mangi.eta.agent.runtime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.agent.terminal.LinuxRunMonitor
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.localizedText
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity
import java.util.concurrent.atomic.AtomicLong

/** 只在用户任务存活期间持有前台执行生命周期；进程被系统停止后不重放任务。 */
internal class AgentExecutionService : Service() {
    private val stopQueue = ExecutionStopQueue { failure ->
        AndroidAgentLogger.warn("Execution task stop failed: type=${failure.safeLogType()}")
    }
    private val owner = ownerSequence.incrementAndGet()
    private var foregroundActive = false
    @Volatile private var startRejected = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        leases.attachOwner(owner)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.execution_channel), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(REVIEW_CHANNEL, getString(R.string.memory_review_channel), NotificationManager.IMPORTANCE_LOW),
        )
        ensureForeground()
    }

    private fun ensureForeground() {
        if (foregroundActive || startRejected) return
        leases.attachOwner(owner)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            foregroundActive = true
        } catch (failure: RuntimeException) {
            startRejected = true
            AndroidAgentLogger.warn("Execution service foreground failed: type=${failure.safeLogType()}")
            stopTasks(startFailed = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTasks()
            // Linux 运行通知的两个动作：只处理动作本身，不把服务当前台常驻。
            LinuxRunMonitor.ACTION_EXIT_LINUX -> {
                LinuxRunMonitor.requestExit()
                stopSelfIfIdle(startId)
            }
            LinuxRunMonitor.ACTION_STOP_LINUX_PROCESS -> {
                LinuxRunMonitor.requestStopProcess(intent?.getIntExtra(LinuxRunMonitor.EXTRA_PID, 0) ?: 0)
                stopSelfIfIdle(startId)
            }
            else -> {
                ensureForeground()
                refreshNotification()
            }
        }
        return START_NOT_STICKY
    }

    /** 没有正在跑的任务时，动作处理完就让服务退出，避免留下空服务。 */
    private fun stopSelfIfIdle(startId: Int) {
        if (activeRunCount == 0 && leases.count() == 0) stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        // 销毁时同样收回本服务拥有的任务。回收在独立有界工作线程上完成，不阻塞 Main。
        stopQueue.close(leases.drainOwner(owner))
        super.onDestroy()
    }

    private fun stopTasks(startFailed: Boolean = false) {
        val callbacks = leases.drain(startFailed)
        stopQueue.submit(callbacks) {
            mainHandler.post { if (instance === this) refreshNotification() }
        }
    }

    private fun refreshNotification() {
        if (leases.closeOwnerIfIdle(owner)) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private fun notification(): Notification {
        val status = runStatus
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val taskCount = if (activeRunCount > 0) activeRunCount else leases.count()
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.execution_title))
            .setContentText(executionText(this, status, taskCount))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.execution_stop), stop).build())
        // 只有 Agent run 在跑时才申请提升为 Live Update；终端会话、Linux 启动等长驻任务不属于"正在进行、需要持续关注"的活动。
        // 设备是 Android 16（SDK_INT_FULL = 36.0）：BAKLAVA_1(36.1) 永远不满足，
        // 等于整段流体云提升被跳过。这里按 BAKLAVA 判断，lint 库标的是 36.1，故抑制 NewApi。
        @SuppressLint("NewApi")
        if (status != null && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA) {
            builder
                .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                .setShortCriticalText(status.shortText())
                .setRequestPromotedOngoing(true)
        }
        return builder.build()
    }

    /** 状态栏胶囊约 7 个字符可完整显示；更长时只取动作，工具名留在展开态。 */
    private fun AgentOverlayStatus.shortText(): String = when (this) {
        is AgentOverlayStatus.RunningTool, is AgentOverlayStatus.HostedToolRunning -> getString(R.string.execution_chip_running)
        AgentOverlayStatus.Paused -> getString(R.string.overlay_paused)
        AgentOverlayStatus.Stopping -> getString(R.string.overlay_stopping)
        AgentOverlayStatus.GeneratingAnswer -> getString(R.string.execution_chip_answering)
        AgentOverlayStatus.MemoryReview -> getString(R.string.execution_chip_memory_review)
        else -> getString(R.string.overlay_reasoning)
    }

    companion object {
        private const val CHANNEL = "eta_execution"
        private const val REVIEW_CHANNEL = "eta_memory_review"
        private const val REVIEW_RESULT_NOTIFICATION_ID = 1109
        private const val COMPACT_RESULT_NOTIFICATION_ID = 1110
        private const val COMPACT_PENDING_NOTIFICATION_ID = 1111
        private const val REVIEW_RESULT_TIMEOUT_MS = 30_000L
        private const val NOTIFICATION_ID = 1107
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_USER_EXECUTION"
        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null
        private var runStatus: AgentOverlayStatus? = null
        private var activeRunCount = 0
        private var publishedStatusText: String? = null

        /**
         * 记忆整理跑完后的短提醒：让用户看得见刚才发生了什么。
         * 做成 ongoing + 提升为 Live Update（流体云胶囊），30 秒后自动消失。
         */
        @SuppressLint("NewApi")
        fun postMemoryReviewResult(context: Context, text: String) {
            runCatching {
                val builder = Notification.Builder(context, REVIEW_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.memory_review_done_title))
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setTimeoutAfter(REVIEW_RESULT_TIMEOUT_MS)
                if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA) {
                    builder
                        .setOngoing(true)
                        .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                        .setShortCriticalText(context.getString(R.string.memory_review_chip_done))
                        .setRequestPromotedOngoing(true)
                }
                context.getSystemService(NotificationManager::class.java)
                    .notify(REVIEW_RESULT_NOTIFICATION_ID, builder.build())
            }.onFailure { failure ->
                AndroidAgentLogger.warnThrottled("memory_review_notify_failed") {
                    "Memory review notification failed: type=${failure.safeLogType()}"
                }
            }
        }

        /**
         * 记忆压缩跑完后的短提醒（与整理通知共用通道，文案区分「已压缩」）。
         */
        fun postMemoryCompactionResult(context: Context, text: String) {
            postMemoryCompactionNotice(
                context = context,
                notificationId = COMPACT_RESULT_NOTIFICATION_ID,
                title = context.getString(R.string.memory_compact_done_title),
                chip = context.getString(R.string.memory_compact_chip_done),
                text = text,
                timeoutMs = REVIEW_RESULT_TIMEOUT_MS,
            )
        }

        /**
         * 压不动、等用户拍板：点通知直达记忆页。不自动消失，直到用户处理或下一次压缩成功。
         */
        fun postMemoryCompactionPending(context: Context, text: String) {
            postMemoryCompactionNotice(
                context = context,
                notificationId = COMPACT_PENDING_NOTIFICATION_ID,
                title = context.getString(R.string.memory_compact_pending_title),
                chip = context.getString(R.string.memory_compact_chip_pending),
                text = text,
                timeoutMs = 0L,
            )
        }

        @SuppressLint("NewApi")
        private fun postMemoryCompactionNotice(
            context: Context,
            notificationId: Int,
            title: String,
            chip: String,
            text: String,
            timeoutMs: Long,
        ) {
            runCatching {
                val open = PendingIntent.getActivity(
                    context,
                    notificationId,
                    Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_MEMORY),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val builder = Notification.Builder(context, REVIEW_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                if (timeoutMs > 0L) builder.setTimeoutAfter(timeoutMs)
                if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA) {
                    builder
                        .setOngoing(true)
                        .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                        .setShortCriticalText(chip)
                        .setRequestPromotedOngoing(true)
                }
                context.getSystemService(NotificationManager::class.java).notify(notificationId, builder.build())
            }.onFailure { failure ->
                AndroidAgentLogger.warnThrottled("memory_compact_notify_failed") {
                    "Memory compaction notification failed: type=${failure.safeLogType()}"
                }
            }
        }

        /**
         * Agent run 的当前状态，供通知与状态栏胶囊展示；null 表示没有 Agent run 在跑。
         * 只在显示文字变化时刷新通知，避免流式事件把通知更新频率推到系统限流。
         */
        fun updateRunStatus(context: Context, status: AgentOverlayStatus?, activeRunCount: Int) {
            mainHandler.post {
                runStatus = status
                this.activeRunCount = activeRunCount
                val count = if (activeRunCount > 0) activeRunCount else leases.count()
                val text = executionText(context, status, count)
                if (text == publishedStatusText) return@post
                publishedStatusText = text
                instance?.takeIf { it.foregroundActive }?.refreshNotification()
            }
        }

        private fun executionText(context: Context, status: AgentOverlayStatus?, taskCount: Int): String {
            val summary = context.resources.getQuantityString(R.plurals.execution_summary, taskCount, taskCount)
            return when {
                status == null -> summary
                taskCount <= 1 -> status.localizedText(context.resources)
                else -> "$summary · ${status.localizedText(context.resources)}"
            }
        }

        /** 提问挂起或作答后刷新执行通知（流体云文案与选项按钮）。 */
        fun refreshRunNotification() {
            mainHandler.post { instance?.refreshNotification() }
        }

        /** 是否有 Agent run 正在执行。 */
        fun isRunActive(): Boolean =
            runStatus != null || leases.hasPrefix("run:") || instance?.foregroundActive == true

        /** 必须从有效的用户入口取得引用，再创建会话或子进程；失败时调用方不启动任务。 */
        fun acquire(
            context: Context,
            id: String,
            allowBoundFallback: Boolean = false,
            onStop: () -> Unit,
        ): Boolean {
            if (instance?.startRejected == true) return false
            if (!leases.acquire(id, allowBoundFallback, onStop)) return true
            return try {
                context.applicationContext.startForegroundService(Intent(context, AgentExecutionService::class.java))
                true
            } catch (failure: RuntimeException) {
                leases.release(id)
                AndroidAgentLogger.warn("Execution service start rejected: type=${failure.safeLogType()}")
                false
            }
        }

        fun release(id: String) {
            leases.release(id)
            mainHandler.post { instance?.refreshNotification() }
        }
    }
}
