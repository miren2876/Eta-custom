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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

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

    /** 后台任务页展示用的一条进程记录。 */
    internal data class LinuxProcess(
        val pid: Int,
        val command: String,
        val cpuPercent: String,
        val rssKb: Long,
        /** 人类可读的用途，不再是冷冰冰的命令行。 */
        val usage: String,
    ) {
        val summary: String
            get() = "$usage · PID $pid · CPU $cpuPercent% · ${rssKb / 1024} MB"
    }

    /** 按命令行关键字猜用途；命中不到时回退到可执行文件名。 */
    internal fun describeUsage(command: String): String {
        val c = command.lowercase()
        return when {
            c.isBlank() -> "未知进程"
            "apt" in c || "dpkg" in c -> "软件包安装 / 更新"
            "pip" in c || "python" in c -> "Python 脚本或包管理"
            "npm" in c || "node" in c -> "Node.js 运行时 / 包管理"
            "yarn" in c || "pnpm" in c -> "前端依赖安装"
            "git" in c -> "Git 仓库操作"
            "make" in c || "cmake" in c || "gcc" in c || "clang" in c || "gradle" in c || "javac" in c -> "编译构建"
            "curl" in c || "wget" in c -> "网络下载"
            "ssh" in c || "scp" in c -> "远程连接 / 传输"
            "nginx" in c || "apache" in c || "httpd" in c -> "本地 Web 服务"
            "mysql" in c || "postgres" in c || "redis" in c || "sqlite" in c -> "数据库服务"
            "ffmpeg" in c -> "音视频处理"
            "unzip" in c || "tar" in c || "gzip" in c || "7z" in c -> "解压 / 打包"
            "find" in c || "grep" in c || "rg" in c || "sed" in c || "awk" in c -> "文件检索 / 文本处理"
            "sleep" in c -> "等待（定时/占位）"
            "bash" in c || "sh " in c || c.startsWith("sh") || "zsh" in c -> "Shell 会话"
            else -> command.substringAfterLast('/').take(24).ifBlank { "未知进程" }
        }
    }

    private val listScript: String
        get() = """
            for p in /proc/[0-9]*; do
              pid=${'$'}{p##*/}
              hit=0
              r=${'$'}(readlink "${'$'}p/root" 2>/dev/null)
              case "${'$'}r" in $rootfsPattern) hit=1;; esac
              t=${'$'}(readlink "${'$'}p/exe" 2>/dev/null)
              if [ "${'$'}hit" = 0 ]; then
                case "${'$'}t" in $rootfsPattern) hit=1;; esac
              fi
              [ "${'$'}hit" = 1 ] || continue
              cmd=${'$'}(tr '\0' ' ' < "${'$'}p/cmdline" 2>/dev/null | cut -c1-120)
              [ -z "${'$'}cmd" ] && cmd="[${'$'}t]"
              rss=${'$'}(awk '/^VmRSS/{print ${'$'}2}' "${'$'}p/status" 2>/dev/null)
              st=${'$'}(cat "${'$'}p/stat" 2>/dev/null)
              cpu=${'$'}(echo "${'$'}st" | awk '{print ${'$'}14+${'$'}15}')
              echo "${'$'}pid|${'$'}cpu|${'$'}{rss:-0}|${'$'}cmd"
            done
        """

    /** 依次尝试常见 su 路径执行脚本；全部失败时返回 null 并记录日志。 */
    private fun runSu(script: String): String? {
        for (su in listOf("/data/adb/ksu/bin/su", "/system/bin/su", "/system/xbin/su", "su")) {
            val output = runCatching {
                val process = ProcessBuilder(su, "-c", script).redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().readText()
                process.waitFor()
                text
            }.getOrNull()
            if (!output.isNullOrBlank()) return output
        }
        AndroidAgentLogger.warn("linux_process_su_failed: no usable su (rootGranted=${RootAccess.isGranted})")
        return null
    }

    /** 列出 rootfs 内的进程；无 root 或读取失败时返回空表。 */
    fun listProcesses(): List<LinuxProcess> = runCatching {
        if (!RootAccess.isGranted) {
            AndroidAgentLogger.warn("linux_process_list_skipped: root not granted")
            return emptyList()
        }
        val text = runSu(listScript) ?: return emptyList()
        if (text.isBlank()) AndroidAgentLogger.warn("linux_process_list_blank")
        text.lineSequence().mapNotNull { line ->
            val parts = line.split('|', limit = 4)
            if (parts.size < 4) return@mapNotNull null
            val pid = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            val ticks = parts[1].trim().toLongOrNull() ?: 0L
            val rss = parts[2].trim().toLongOrNull() ?: 0L
            val command = parts[3].trim()
            LinuxProcess(pid, command, "%.1f".format(ticks / 100.0), rss, describeUsage(command))
        }.sortedByDescending { it.cpuPercent.toDoubleOrNull() ?: 0.0 }.toList()
    }.getOrDefault(emptyList())

    /** 结束单个后台进程。 */
    fun killProcess(pid: Int): Boolean = runCatching {
        if (!RootAccess.isGranted || pid <= 1) return false
        runSu("kill -9 $pid") != null
    }.getOrDefault(false)

    private val watchScript: String
        get() = """
            while true; do
              n=0
              for p in /proc/[0-9]*; do
                hit=0
                r=${'$'}(readlink "${'$'}p/root" 2>/dev/null)
                case "${'$'}r" in $rootfsPattern) hit=1;; esac
                if [ "${'$'}hit" = 0 ]; then
                  t=${'$'}(readlink "${'$'}p/exe" 2>/dev/null)
                  case "${'$'}t" in $rootfsPattern) hit=1;; esac
                fi
                [ "${'$'}hit" = 1 ] && n=${'$'}((n+1))
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

    /**
     * 「退出」入口：立刻收起通知，再在后台线程结束 Linux 进程。
     *
     * 调用方（Service.onStartCommand）在主线程上，绝不能同步等 root shell：
     * 之前就是这样调用阻塞版实现的，su 起得慢时整个 App 会冻住，只能强行停止。
     */
    fun requestExit() {
        updateNotification(0)
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
        // 一次 su 取全量，通知正文与展开态共用。
        val processSnapshot = runCatching { listProcesses() }.getOrDefault(emptyList())
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.linux_run_title))
            .setContentText(processSnapshot.take(3).joinToString("、") { it.command.take(24) }
                .ifBlank { context.getString(R.string.linux_run_summary, count) })
            .setStyle(Notification.BigTextStyle().bigText(
                processSnapshot.joinToString("\n") { "${it.command} (${it.summary})" }
                    .ifBlank { context.getString(R.string.linux_run_summary, count) },
            ))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.linux_run_exit), exit).build())
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private const val RECHECK_INTERVAL_MS = 5_000L
    private const val RESTART_INTERVAL_MS = 3_000L

    /** root 脚本超时：脚本本身只遍历 /proc，正常几十毫秒；超时就放弃，不挂住线程。 */
    private const val EXIT_TIMEOUT_SECONDS = 5L
}
