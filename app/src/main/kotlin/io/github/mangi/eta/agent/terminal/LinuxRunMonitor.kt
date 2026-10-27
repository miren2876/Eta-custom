package io.github.mangi.eta.agent.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
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
 * 只保留一条通知（ID 1108）：收起态是系统标准样式（进程数 + 合计 CPU/内存），
 * 展开态用 [R.layout.notification_linux_run_expanded] 的自定义 RemoteViews，
 * 每行一个进程并带独立的「结束」按钮——InboxStyle 的明细行不能点击，
 * 所以这里不用系统样式。
 *
 * 进程数据来自 [LinuxProcessSampler] 的常驻采样循环：开关打开时持有一个 4 秒租约，
 * 每次渲染只读最新快照，不再自己扫 /proc。
 */
internal object LinuxRunMonitor {

    /** 通知栏动作：结束 Linux 环境里的全部进程。 */
    const val ACTION_EXIT_LINUX = "io.github.mangi.eta.action.EXIT_LINUX"

    /** 通知展开态每行的「结束」按钮：结束单个进程。 */
    const val ACTION_STOP_LINUX_PROCESS = "io.github.mangi.eta.action.STOP_LINUX_PROCESS"

    /** 单进程结束动作的 pid 参数。 */
    const val EXTRA_PID = "pid"

    private const val CHANNEL = "eta_linux_run"
    private const val NOTIFICATION_ID = 1108
    private const val MAX_EXPANDED_ROWS = 6
    private const val NOTIFY_INTERVAL_MS = 4_000L
    private const val RECHECK_INTERVAL_MS = 5_000L
    private const val EXIT_TIMEOUT_SECONDS = 5L
    private const val STOPPED_PID_TTL_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var appContext: Context? = null

    /** 刚被用户结束、但快照还没跟上的 pid：渲染时先隐藏，最多 15 秒。 */
    private val stoppedPids = mutableMapOf<Int, Long>()

    private val rowIds = intArrayOf(
        R.id.linux_run_row_0, R.id.linux_run_row_1, R.id.linux_run_row_2,
        R.id.linux_run_row_3, R.id.linux_run_row_4, R.id.linux_run_row_5,
    )
    private val titleIds = intArrayOf(
        R.id.linux_run_row_0_title, R.id.linux_run_row_1_title, R.id.linux_run_row_2_title,
        R.id.linux_run_row_3_title, R.id.linux_run_row_4_title, R.id.linux_run_row_5_title,
    )
    private val metaIds = intArrayOf(
        R.id.linux_run_row_0_meta, R.id.linux_run_row_1_meta, R.id.linux_run_row_2_meta,
        R.id.linux_run_row_3_meta, R.id.linux_run_row_4_meta, R.id.linux_run_row_5_meta,
    )
    private val stopIds = intArrayOf(
        R.id.linux_run_row_0_stop, R.id.linux_run_row_1_stop, R.id.linux_run_row_2_stop,
        R.id.linux_run_row_3_stop, R.id.linux_run_row_4_stop, R.id.linux_run_row_5_stop,
    )

    /** 判断 pid 是否属于某个脚本任务时用；构造需要 Context，所以延迟创建。 */
    private val supervisor: DetachedTaskSupervisor? by lazy {
        val context = appContext ?: return@lazy null
        DetachedTaskSupervisor(
            logger = AndroidAgentLogger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(context),
            linuxRootfsPathProvider = { environment ->
                environment.linuxDistribution?.let { distribution ->
                    LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
                }
            },
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
        )
    }

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
                    LinuxProcessSampler.snapshot.value?.let { snapshot -> render(snapshot.processes, snapshot.sampledAt) }
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

    /**
     * 结束单个进程（通知展开态每行的「结束」）。
     *
     * 若 pid 属于某个正在运行的脚本任务，走任务停止路径，避免只 kill 子进程、
     * 任务记录还停在「运行中」。结束后立刻重绘一次通知，不等下一轮采样。
     */
    fun requestStopProcess(pid: Int) {
        if (pid <= 1) return
        synchronized(stoppedPids) { stoppedPids[pid] = System.currentTimeMillis() }
        renderLatest()
        scope.launch {
            val task = runCatching { findScriptTask(pid) }.getOrNull()
            val ok = if (task != null) {
                runCatching { supervisor?.stop(task.id) == true }.getOrDefault(false)
            } else {
                LinuxProcessSampler.killProcess(pid)
            }
            AndroidAgentLogger.info("Linux run stop process pid=$pid scriptTask=${task?.id} ok=$ok")
            renderLatest()
        }
    }

    /** 向上找 pid 所属的脚本任务根进程（最多 16 层）。 */
    private fun findScriptTask(pid: Int): DetachedTask? {
        val supervisor = supervisor ?: return null
        val running = supervisor.list().filter { it.running }
        if (running.isEmpty()) return null
        val parents = LinuxProcessSampler.snapshot.value
            ?.processes
            ?.associate { it.pid to it.ppid }
            .orEmpty()
        var current = pid
        var depth = 0
        while (depth < 16 && current > 1) {
            running.firstOrNull { it.task.pid.toInt() == current }?.let { return it.task }
            current = parents[current] ?: return null
            depth++
        }
        return null
    }

    private fun renderLatest() {
        val snapshot = LinuxProcessSampler.snapshot.value ?: return
        render(snapshot.processes, snapshot.sampledAt)
    }

    private fun render(processes: List<LinuxProcessInfo>, sampledAt: Long) {
        val context = appContext ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val visible = synchronized(stoppedPids) {
            stoppedPids.entries.removeAll { now - it.value > STOPPED_PID_TTL_MS }
            processes.filterNot { it.pid in stoppedPids }
        }
        if (visible.isEmpty()) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel(context, manager)
        // 点通知直达「后台任务」页；ACTION_OPEN_TASKS 由 MainActivity 的启动请求链路消费。
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TASKS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exit = PendingIntent.getService(
            context, 1,
            Intent(context, AgentExecutionService::class.java).setAction(ACTION_EXIT_LINUX),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cpuSum = visible.mapNotNull { it.cpuPercent }.takeIf { it.isNotEmpty() }?.sum()
        val memoryMb = visible.sumOf { it.rssKb } / 1024.0
        val summary = if (cpuSum != null) {
            context.getString(
                R.string.linux_run_summary_line,
                visible.size,
                "%.1f%%".format(cpuSum),
                "%.1f MB".format(memoryMb),
            )
        } else {
            context.getString(
                R.string.linux_run_summary_line_no_cpu,
                visible.size,
                "%.1f MB".format(memoryMb),
            )
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.linux_run_group_summary))
            .setContentText(summary)
            .setContentIntent(open)
            .setCustomBigContentView(expandedViews(context, visible, sampledAt, open))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.linux_run_exit), exit).build())
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun expandedViews(
        context: Context,
        processes: List<LinuxProcessInfo>,
        sampledAt: Long,
        open: PendingIntent,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_linux_run_expanded)
        val single = processes.size == 1
        val overflow = processes.size > MAX_EXPANDED_ROWS
        // 超过 6 个时最后一行留给「还有 N 个」。
        val processRows = if (overflow) MAX_EXPANDED_ROWS - 1 else minOf(processes.size, MAX_EXPANDED_ROWS)
        for (index in 0 until MAX_EXPANDED_ROWS) {
            when {
                index < processRows -> {
                    val process = processes[index]
                    views.setViewVisibility(rowIds[index], View.VISIBLE)
                    views.setTextViewText(titleIds[index], process.usage.ifBlank { ProcessUsage.executableName(process.command) })
                    views.setTextViewText(metaIds[index], metaLine(process, single))
                    views.setViewVisibility(stopIds[index], View.VISIBLE)
                    views.setOnClickPendingIntent(stopIds[index], stopPendingIntent(context, process.pid))
                    views.setOnClickPendingIntent(rowIds[index], open)
                }
                index == processRows && overflow -> {
                    views.setViewVisibility(rowIds[index], View.VISIBLE)
                    views.setTextViewText(
                        titleIds[index],
                        context.getString(R.string.linux_run_more, processes.size - processRows),
                    )
                    views.setTextViewText(metaIds[index], "")
                    views.setViewVisibility(stopIds[index], View.GONE)
                    views.setOnClickPendingIntent(rowIds[index], open)
                }
                else -> {
                    views.setViewVisibility(rowIds[index], View.GONE)
                }
            }
        }
        return views
    }

    /** 单进程时展开行给命令摘要与 cwd，多进程时给 PID/CPU/内存。 */
    private fun metaLine(process: LinuxProcessInfo, single: Boolean): String =
        if (single) {
            buildString {
                append(ProcessUsage.commandSummary(process.command, maxChars = 80))
                process.cwd?.let { cwd ->
                    append(" · ")
                    append(cwd)
                }
            }
        } else {
            buildString {
                append("PID ")
                append(process.pid)
                process.cpuPercent?.let { append(" · CPU ").append("%.1f%%".format(it)) }
                append(" · ")
                append("%.1f MB".format(process.memoryMb))
            }
        }

    private fun stopPendingIntent(context: Context, pid: Int): PendingIntent =
        PendingIntent.getService(
            context,
            pid,
            Intent(context, AgentExecutionService::class.java)
                .setAction(ACTION_STOP_LINUX_PROCESS)
                .putExtra(EXTRA_PID, pid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
        runCatching { manager.cancel(NOTIFICATION_ID) }
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
}
