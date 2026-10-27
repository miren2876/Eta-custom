package io.github.mangi.eta.agent.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Linux 工具环境的运行状态监视。
 *
 * 进程数据不再由这里自己去扫 /proc：采样交给 [LinuxProcessSampler] 的常驻循环，
 * 这里只在通知开关打开时持有一个租约，按 4 秒的节拍读最新快照并渲染通知。
 *
 * 通知形态按进程数分两档：1-5 个进程时每个进程一条独立通知（同一 group + 汇总），
 * 超过 5 个只发一条 InboxStyle 汇总，避免通知栏被刷屏。
 *
 * 开关关闭时不采样、不显示通知（保持原版行为）。
 */
internal object LinuxRunMonitor {

    /** 通知栏动作：结束 Linux 环境里的全部进程。 */
    const val ACTION_EXIT_LINUX = "io.github.mangi.eta.action.EXIT_LINUX"

    private const val CHANNEL = "eta_linux_run"

    /** 汇总通知固定 ID；单进程通知用 [NOTIFICATION_ID] + pid，稳定且可回收。 */
    private const val NOTIFICATION_ID = 1108
    private const val MAX_SINGLE_NOTIFICATIONS = 5
    private const val NOTIFY_INTERVAL_MS = 4_000L
    private const val RECHECK_INTERVAL_MS = 5_000L
    private const val EXIT_TIMEOUT_SECONDS = 5L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var appContext: Context? = null

    /** 当前已经发出去、需要在进程消失时 cancel 的通知 ID。 */
    private val activeIds = mutableSetOf<Int>()

    fun start(context: Context) {
        appContext = context.applicationContext
        if (job?.isActive == true) return
        job = scope.launch { watchLoop() }
    }

    private suspend fun watchLoop() {
        while (currentCoroutineContext().isActive) {
            val enabled = Prefs.isEnabled(Prefs.Keys.AGENT_LINUX_RUN_NOTIFICATION) && RootAccess.isGranted
            if (!enabled) {
                cancelAll()
                delay(RECHECK_INTERVAL_MS)
                continue
            }
            val lease = LinuxProcessSampler.acquire(NOTIFY_INTERVAL_MS)
            try {
                while (currentCoroutineContext().isActive) {
                    val stillEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_LINUX_RUN_NOTIFICATION) && RootAccess.isGranted
                    if (!stillEnabled) break
                    LinuxProcessSampler.snapshot.value?.let { snapshot -> render(snapshot.processes) }
                    delay(NOTIFY_INTERVAL_MS)
                }
            } finally {
                lease.release()
            }
            cancelAll()
            delay(RECHECK_INTERVAL_MS)
        }
    }

    /**
     * 「退出」入口：立刻收起通知，再在后台线程结束 Linux 进程。
     *
     * 调用方（Service.onStartCommand）在主线程上，绝不能同步等 root shell：
     * 之前就是这样调用阻塞版实现的，su 起得慢时整个 App 会冻住，只能强行停止。
     */
    fun requestExit() {
        cancelAll()
        scope.launch {
            val killed = runCatching { exitBlocking() }.getOrDefault(0)
            AndroidAgentLogger.info("Linux run exit: killed=$killed")
        }
    }

    /** 同步结束 Linux 进程；只能在后台线程调用。带超时，避免 root shell 卡死线程。 */
    private fun exitBlocking(): Int {
        val process = runCatching {
            ProcessBuilder("su", "-c", exitScript).redirectErrorStream(true).start()
        }.getOrNull() ?: return 0
        val output = runCatching {
            process.inputStream.bufferedReader().use { reader ->
                val text = reader.readText()
                if (!process.waitFor(EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
                text
            }
        }.getOrNull().orEmpty()
        return output.trim().toIntOrNull() ?: 0
    }

    private val exitScript: String
        get() = """
            n=0
            for p in /proc/[0-9]*; do
              t=${'$'}(readlink "${'$'}p/exe" 2>/dev/null) || continue
              case "${'$'}t" in *terminal/*rootfs*)
                if kill -9 "${'$'}{p#/proc/}" 2>/dev/null; then n=${'$'}((n+1)); fi
              ;;
              esac
            done
            echo "${'$'}n"
        """.trimIndent()

    private fun render(processes: List<LinuxProcessInfo>) {
        val context = appContext ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (processes.isEmpty()) {
            cancelAll()
            return
        }
        ensureChannel(context, manager)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exit = PendingIntent.getService(
            context, 1,
            Intent(context, AgentExecutionService::class.java).setAction(ACTION_EXIT_LINUX),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val rendered = mutableSetOf<Int>()
        if (processes.size <= MAX_SINGLE_NOTIFICATIONS) {
            processes.forEach { process ->
                val id = NOTIFICATION_ID + process.pid
                rendered += id
                runCatching { manager.notify(id, singleNotification(context, process, open)) }
            }
            rendered += NOTIFICATION_ID
            runCatching { manager.notify(NOTIFICATION_ID, summaryNotification(context, processes.size, open, exit)) }
        } else {
            rendered += NOTIFICATION_ID
            runCatching { manager.notify(NOTIFICATION_ID, inboxNotification(context, processes, open, exit)) }
        }
        synchronized(activeIds) {
            val stale = activeIds - rendered
            stale.forEach { runCatching { manager.cancel(it) } }
            activeIds.clear()
            activeIds += rendered
        }
    }

    private fun singleNotification(
        context: Context,
        process: LinuxProcessInfo,
        open: PendingIntent,
    ): Notification {
        val title = process.usage.ifBlank { ProcessUsage.executableName(process.command) }
        val text = context.getString(
            R.string.linux_run_single_line,
            process.usage,
            process.cpuPercent?.let { "%.1f".format(it) } ?: "—",
            "%.1f".format(process.memoryMb),
        )
        return notificationBuilder(context)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(ProcessUsage.commandSummary(process.command, maxChars = 60))
            .setContentIntent(open)
            .setGroup(CHANNEL)
            .build()
    }

    private fun summaryNotification(
        context: Context,
        count: Int,
        open: PendingIntent,
        exit: PendingIntent,
    ): Notification = notificationBuilder(context)
        .setContentTitle(context.getString(R.string.linux_run_group_summary))
        .setContentText(context.getString(R.string.linux_run_summary, count))
        .setContentIntent(open)
        .setGroup(CHANNEL)
        .setGroupSummary(true)
        .addAction(exitAction(context, exit))
        .build()

    private fun inboxNotification(
        context: Context,
        processes: List<LinuxProcessInfo>,
        open: PendingIntent,
        exit: PendingIntent,
    ): Notification {
        val style = Notification.InboxStyle()
        processes.take(MAX_SINGLE_NOTIFICATIONS).forEach { process ->
            style.addLine(
                context.getString(
                    R.string.linux_run_process_line,
                    process.usage,
                    process.pid,
                    process.cpuPercent?.let { "%.1f".format(it) } ?: "—",
                    "%.1f".format(process.memoryMb),
                ),
            )
        }
        val rest = processes.size - MAX_SINGLE_NOTIFICATIONS
        if (rest > 0) style.addLine(context.getString(R.string.linux_run_more, rest))
        return notificationBuilder(context)
            .setContentTitle(context.getString(R.string.linux_run_group_summary))
            .setContentText(context.getString(R.string.linux_run_summary, processes.size))
            .setStyle(style)
            .setContentIntent(open)
            .setGroup(CHANNEL)
            .setGroupSummary(true)
            .addAction(exitAction(context, exit))
            .build()
    }

    private fun notificationBuilder(context: Context): Notification.Builder =
        Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

    private fun exitAction(context: Context, exit: PendingIntent): Notification.Action =
        Notification.Action.Builder(null, context.getString(R.string.linux_run_exit), exit).build()

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.linux_run_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun cancelAll() {
        val context = appContext ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        synchronized(activeIds) {
            (activeIds + NOTIFICATION_ID).forEach { runCatching { manager.cancel(it) } }
            activeIds.clear()
        }
    }
}
