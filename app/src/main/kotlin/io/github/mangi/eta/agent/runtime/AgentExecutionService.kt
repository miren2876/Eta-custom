package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxRunMonitor
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
        if (intent?.action == ACTION_STOP) {
            stopTasks()
        } else if (intent?.action == LinuxRunMonitor.ACTION_EXIT_LINUX) {
            // 结束 Linux 环境里的全部进程；没有其他前台任务时顺带收回本服务。
            LinuxRunMonitor.exit()
            if (!foregroundActive) stopSelf(startId)
        } else {
            ensureForeground()
            refreshNotification()
        }
        return START_NOT_STICKY
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
            // 执行通知已经收起，保留的虚拟屏改由自己的常驻通知承载入口。
            io.github.mangi.eta.agent.display.VirtualScreenNotification.refresh(this)
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private fun notification(): Notification {
        val status = runStatus
        val activePrompt = io.github.mangi.eta.agent.prompt.AgentUserPrompt.current()
        // 有待回答的提问时，点通知直接回到聊天页（那里有完整卡片）；否则维持原有目标。
        val openTarget = when {
            activePrompt != null -> MainActivity::class.java
            io.github.mangi.eta.agent.display.VirtualScreenSession.isActive() ->
                io.github.mangi.eta.agent.display.VirtualScreenViewerActivity::class.java
            else -> MainActivity::class.java
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, openTarget),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val taskCount = leases.count()
        // 没有 run 但有保留的虚拟屏会话：只有这一条通知时用虚拟屏文案。
        val virtualScreenOnly = status == null &&
            io.github.mangi.eta.agent.display.VirtualScreenSession.isActive()
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (virtualScreenOnly) getString(R.string.virtual_screen_notification_title)
                else getString(R.string.execution_title),
            )
            .setContentText(
                when {
                    activePrompt != null -> activePrompt.questions.firstOrNull()?.question
                        ?.take(120) ?: getString(R.string.user_prompt_chip)
                    virtualScreenOnly -> getString(R.string.virtual_screen_notification_content)
                    status != null -> status.localizedText(resources)
                    else -> resources.getQuantityString(R.plurals.execution_summary, taskCount, taskCount)
                },
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        // 提问挂起时不在系统里放选项按钮：展开卡片只显示题干，点正文回到 App 用卡片作答。
        builder.addAction(Notification.Action.Builder(null, getString(R.string.execution_stop), stop).build())
        // 只有 Agent run 在跑时才申请提升为 Live Update；终端会话、Linux 启动等长驻任务不属于"正在进行、需要持续关注"的活动。
        // 门槛取 Baklava(Android 16, 36.0)：ColorOS 16/17 的流体云直接对接原生 Live Updates API，
        // 36.0 设备同样支持提升（上游原先要求 QPR1，会把 36.0 的设备整体排除）。
        if ((status != null || activePrompt != null) && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA) {
            builder
                .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                .setShortCriticalText(
                    if (activePrompt != null) getString(R.string.user_prompt_chip) else status!!.shortText(),
                )
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
        else -> getString(R.string.overlay_reasoning)
    }

    companion object {
        private const val CHANNEL = "eta_execution"
        private const val NOTIFICATION_ID = 1107
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_USER_EXECUTION"
        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null
        @Volatile private var runStatus: AgentOverlayStatus? = null
        private var publishedStatusText: String? = null

        /**
         * Agent run 的当前状态，供通知与状态栏胶囊展示；null 表示没有 Agent run 在跑。
         * 只在显示文字变化时刷新通知，避免流式事件把通知更新频率推到系统限流。
         */
        fun updateRunStatus(context: Context, status: AgentOverlayStatus?) {
            mainHandler.post {
                val activeChanged = (runStatus != null) != (status != null)
                runStatus = status
                if (activeChanged) {
                    // 运行期间执行通知已指向查看页，结束后才单独显示保留的虚拟屏。
                    io.github.mangi.eta.agent.display.VirtualScreenNotification.refresh(context.applicationContext)
                }
                val text = status?.localizedText(context.resources)
                if (text == publishedStatusText) return@post
                publishedStatusText = text
                instance?.takeIf { it.foregroundActive }?.refreshNotification()
            }
        }

        /** 提问挂起或作答后刷新执行通知（流体云文案与选项按钮）。 */
        fun refreshRunNotification() {
            mainHandler.post { instance?.refreshNotification() }
        }

        /** 是否有 Agent run 正在执行；用于避免与虚拟屏保留通知重复显示。 */
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
            val acquired = leases.acquire(id, allowBoundFallback, onStop)
            if (id.startsWith("run:")) {
                mainHandler.post {
                    io.github.mangi.eta.agent.display.VirtualScreenNotification
                        .refresh(context.applicationContext)
                }
            }
            if (!acquired) return true
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
            mainHandler.post {
                instance?.refreshNotification()
                if (id.startsWith("run:")) {
                    instance?.applicationContext?.let {
                        io.github.mangi.eta.agent.display.VirtualScreenNotification.refresh(it)
                    }
                }
            }
        }
    }
}
