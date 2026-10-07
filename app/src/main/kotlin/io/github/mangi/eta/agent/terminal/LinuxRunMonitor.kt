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
import io.github.mangi.eta.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Linux 工具环境的运行状态监视。
 *
 * Linux rootfs 里的进程（控制台会话、一次性命令、守护任务，以及脱离会话的后台进程）
 * 没有统一的应用内登记点，所以在 root 可用时用一个常驻的探测循环读取 /proc，得到真实数量：
 * 有进程时在通知栏显示「Linux 正在运行」，并提供「退出」直接结束这些进程。
 *
 * 开关关闭时不启动探测循环，也不显示通知（保持原版行为）。
 */
internal object LinuxRunMonitor {

    /** 通知栏动作：结束 Linux 环境里的全部进程。 */
    const val ACTION_EXIT_LINUX = "io.github.mangi.eta.action.EXIT_LINUX"

    private const val CHANNEL = "eta_linux_run"
    private const val NOTIFICATION_ID = 1108
    private const val POLL_INTERVAL_SECONDS = 15

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var appContext: Context? = null

    /** 只统计 Linux rootfs 内的进程；进程若属于其他 Linux 发行版目录同样命中。 */
    private val rootfsPattern = "*terminal/*rootfs*"

    private val watchScript: String
        get() = """
            while true; do
              n=0
              for p in /proc/[0-9]*; do
                t=${'$'}(readlink "${'$'}p/exe" 2>/dev/null) || continue
                case "${'$'}t" in $rootfsPattern) n=${'$'}((n+1));; esac
              done
              echo "${'$'}n"
              sleep $POLL_INTERVAL_SECONDS
            done
        """.trimIndent()

    private val exitScript: String
        get() = """
            n=0
            for p in /proc/[0-9]*; do
              t=${'$'}(readlink "${'$'}p/exe" 2>/dev/null) || continue
              case "${'$'}t" in $rootfsPattern)
                if kill -9 "${'$'}{p#/proc/}" 2>/dev/null; then n=${'$'}((n+1)); fi
              ;;
              esac
            done
            echo "${'$'}n"
        """.trimIndent()

    /** App 进程启动时调用一次；开关关闭时循环只做轻量等待。 */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (job?.isActive == true) return
        job = scope.launch { watchLoop() }
    }

    /** 结束 Linux 环境里的全部进程，并立即收起通知；返回结束的进程数。 */
    fun exit(): Int {
        val killed = runCatching { runRootScript(exitScript)?.trim()?.toIntOrNull() }.getOrNull() ?: 0
        updateNotification(0)
        return killed
    }

    private suspend fun watchLoop() {
        while (true) {
            if (!Prefs.isEnabled(Prefs.Keys.AGENT_LINUX_RUN_NOTIFICATION) || !RootAccess.isGranted) {
                // 关闭或没有 root 时不探测；先收起可能残留的通知。
                updateNotification(0)
                delay(RECHECK_INTERVAL_MS)
                continue
            }
            val process = runCatching {
                ProcessBuilder("su", "-c", watchScript).redirectErrorStream(true).start()
            }.getOrNull()
            if (process == null) {
                delay(RESTART_INTERVAL_MS)
                continue
            }
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    val count = line.trim().toIntOrNull() ?: return@forEachLine
                    updateNotification(count)
                }
            }
            runCatching { process.waitFor() }
            delay(RESTART_INTERVAL_MS)
        }
    }

    private fun runRootScript(script: String): String? = runCatching {
        val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output
    }.getOrNull()

    private fun updateNotification(count: Int) {
        val context = appContext ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (count <= 0) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.linux_run_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exit = PendingIntent.getService(
            context, 1,
            Intent(context, AgentExecutionService::class.java).setAction(ACTION_EXIT_LINUX),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.linux_run_title))
            .setContentText(context.getString(R.string.linux_run_summary, count))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.linux_run_exit), exit).build())
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private const val RECHECK_INTERVAL_MS = 5_000L
    private const val RESTART_INTERVAL_MS = 3_000L
}
