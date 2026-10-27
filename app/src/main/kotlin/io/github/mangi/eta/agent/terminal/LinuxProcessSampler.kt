package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 一个 rootfs 内的进程样本；CPU 由相邻两轮 ticks 差值算出，首轮为 null。 */
internal data class LinuxProcessInfo(
    val pid: Int,
    val ppid: Int,
    val command: String,
    val usage: String,
    val cwd: String?,
    val rssKb: Long,
    val cpuPercent: Double?,
) {
    val memoryMb: Double get() = rssKb / 1024.0
    val summaryLine: String
        get() = buildString {
            append(usage)
            append(" · CPU ")
            append(cpuPercent?.let { "%.1f%%".format(it) } ?: "—")
            append(" · ")
            append("%.1f MB".format(memoryMb))
        }
}

internal data class LinuxProcessSnapshot(
    val processes: List<LinuxProcessInfo>,
    val sampledAt: Long,
)

/**
 * Linux rootfs 的常驻进程采样器。
 *
 * 原来每轮刷新都新起一次 `su` 全量遍历 /proc（几百毫秒到数秒），页面与通知各自轮询，
 * 于是「显示不及时」几乎全部来自这里。现在只保留一个长期运行的 root 采样循环，
 * 每轮把完整快照打到 stdout，App 侧解析行并更新 [snapshot]；页面和通知都订阅同一份数据。
 *
 * 生命周期用引用计数管理：页面可见时 1 秒一轮，只有通知时需要 3-5 秒一轮，
 * 取所有租约里最小的间隔；没有租约时立刻结束 root 进程，不留常驻 su。
 */
internal object LinuxProcessSampler {

    private const val MIN_INTERVAL_MS = 500L
    private const val MAX_COMMAND_CHARS = 300
    private const val RESTART_DELAY_MS = 2_000L
    private const val NO_ROOT_DELAY_MS = 5_000L

    /** 采样脚本的命令行标记：停止时用它兜底清理残留的 root 循环。 */
    private const val SCRIPT_MARKER = "eta_proc_sampler"

    private val suCandidates = listOf("/system/bin/su", "su", "/system/xbin/su", "/data/adb/ksu/bin/su")

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val previousTicksLock = Any()

    private val leases = mutableMapOf<Long, Long>()
    private var leaseSeq = 0L
    private var job: Job? = null
    private var activeIntervalMs = 0L

    @Volatile
    private var activeProcess: Process? = null

    private val _snapshot = MutableStateFlow<LinuxProcessSnapshot?>(null)
    val snapshot: StateFlow<LinuxProcessSnapshot?> = _snapshot.asStateFlow()

    /** 上一轮每个进程的 (ticks, 采样时刻)，用于算 CPU 占用。 */
    private var previousTicks = mapOf<Int, Pair<Long, Long>>()

    /** 一次采样订阅；页面与通知各持一个，释放后自动降低采样频率或停止。 */
    internal class Lease internal constructor(private val onRelease: () -> Unit) {
        private var released = false

        fun release() {
            if (released) return
            released = true
            onRelease()
        }
    }

    fun acquire(intervalMillis: Long): Lease {
        val token = synchronized(lock) {
            leaseSeq += 1
            leases[leaseSeq] = intervalMillis.coerceAtLeast(MIN_INTERVAL_MS)
            ensureRunningLocked()
            leaseSeq
        }
        return Lease { release(token) }
    }

    private fun release(token: Long) {
        synchronized(lock) {
            leases.remove(token)
            if (leases.isEmpty()) stopLocked() else ensureIntervalLocked()
        }
    }

    /** 采样间隔：所有租约里最小的那个。 */
    private fun desiredIntervalLocked(): Long =
        (leases.values.minOrNull() ?: 0L).coerceAtLeast(MIN_INTERVAL_MS)

    private fun ensureRunningLocked() {
        val desired = desiredIntervalLocked()
        if (desired <= 0L) return
        if (job?.isActive == true && activeIntervalMs == desired) return
        // 间隔变了必须重启 root 循环（sleep 参数写死在脚本里）；这里不阻塞调用线程。
        job?.cancel()
        activeProcess?.destroy()
        activeProcess = null
        activeIntervalMs = desired
        job = scope.launch { runLoop(desired) }
    }

    private fun ensureIntervalLocked() {
        val desired = desiredIntervalLocked()
        if (job?.isActive == true && activeIntervalMs == desired) return
        ensureRunningLocked()
    }

    private fun stopLocked() {
        job?.cancel()
        job = null
        activeIntervalMs = 0L
        activeProcess?.destroy()
        activeProcess = null
        synchronized(previousTicksLock) { previousTicks = emptyMap() }
        _snapshot.value = null
        // su 被杀后 root 侧的 shell 可能变成孤儿继续跑，按脚本标记再清一次。
        scope.launch { killStraySampler() }
    }

    private suspend fun runLoop(intervalMs: Long) {
        var suIndex = 0
        while (currentCoroutineContext().isActive) {
            if (!RootAccess.isGranted) {
                _snapshot.value = null
                delay(NO_ROOT_DELAY_MS)
                continue
            }
            val su = suCandidates[suIndex % suCandidates.size]
            val process = startSamplerProcess(su, intervalMs)
            if (process == null) {
                suIndex += 1
                AndroidAgentLogger.warn("linux_process_sampler_su_failed: $su")
                delay(RESTART_DELAY_MS)
                continue
            }
            activeProcess = process
            var sawData = false
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    val rows = mutableListOf<SnapshotRow>()
                    reader.forEachLine { line ->
                        val trimmed = line.trim()
                        when {
                            trimmed == "END" -> {
                                sawData = true
                                publish(rows, intervalMs)
                                rows.clear()
                            }
                            trimmed.startsWith("P|") -> parseRow(trimmed)?.let(rows::add)
                        }
                    }
                }
            }
            runCatching { process.waitFor() }
            activeProcess = null
            // 这个 su 一条数据都没给（不可执行或权限被拒），换下一个候选。
            if (!sawData) suIndex += 1
            if (!currentCoroutineContext().isActive) break
            delay(RESTART_DELAY_MS)
        }
    }

    private fun startSamplerProcess(su: String, intervalMs: Long): Process? = runCatching {
        ProcessBuilder(su, "-c", samplerScript(intervalMs)).redirectErrorStream(true).start()
    }.getOrNull()

    private fun samplerScript(intervalMs: Long): String {
        val intervalSeconds = (intervalMs.coerceAtLeast(MIN_INTERVAL_MS) / 1000.0).toString()
        return """
            # $SCRIPT_MARKER
            while true; do
              for p in /proc/[0-9]*; do
                pid=${'$'}{p##*/}
                r=${'$'}(readlink "${'$'}p/root" 2>/dev/null)
                hit=0
                case "${'$'}r" in *terminal/*rootfs*) hit=1;; esac
                t=${'$'}(readlink "${'$'}p/exe" 2>/dev/null)
                if [ "${'$'}hit" = 0 ]; then
                  case "${'$'}t" in *terminal/*rootfs*) hit=1;; esac
                fi
                [ "${'$'}hit" = 1 ] || continue
                st=${'$'}(cat "${'$'}p/stat" 2>/dev/null)
                [ -z "${'$'}st" ] && continue
                ppid=${'$'}(echo "${'$'}st" | awk '{print ${'$'}4}')
                ticks=${'$'}(echo "${'$'}st" | awk '{print ${'$'}14+${'$'}15}')
                rss=${'$'}(awk '/^VmRSS/{print ${'$'}2}' "${'$'}p/status" 2>/dev/null)
                cwd=${'$'}(readlink "${'$'}p/cwd" 2>/dev/null)
                cmd=${'$'}(tr '\0' ' ' < "${'$'}p/cmdline" 2>/dev/null | cut -c1-$MAX_COMMAND_CHARS)
                [ -z "${'$'}cmd" ] && cmd="[${'$'}t]"
                echo "P|${'$'}pid|${'$'}{ppid:-0}|${'$'}{ticks:-0}|${'$'}{rss:-0}|${'$'}cwd|${'$'}cmd"
              done
              echo "END"
              sleep $intervalSeconds
            done
        """.trimIndent()
    }

    private data class SnapshotRow(
        val pid: Int,
        val ppid: Int,
        val ticks: Long,
        val rssKb: Long,
        val cwd: String?,
        val command: String,
    )

    private fun parseRow(line: String): SnapshotRow? {
        // 命令放最后：命令行里可能出现任何字符，split 限制在 7 段内。
        val parts = line.split('|', limit = 7)
        if (parts.size < 7) return null
        val pid = parts[1].trim().toIntOrNull() ?: return null
        return SnapshotRow(
            pid = pid,
            ppid = parts[2].trim().toIntOrNull() ?: 0,
            ticks = parts[3].trim().toLongOrNull() ?: 0L,
            rssKb = parts[4].trim().toLongOrNull() ?: 0L,
            cwd = parts[5].trim().ifBlank { null },
            command = parts[6].trim(),
        )
    }

    private fun publish(rows: List<SnapshotRow>, intervalMs: Long) {
        val now = System.currentTimeMillis()
        if (rows.isEmpty()) {
            _snapshot.value = LinuxProcessSnapshot(emptyList(), now)
            synchronized(previousTicksLock) { previousTicks = emptyMap() }
            return
        }
        val previous = synchronized(previousTicksLock) { previousTicks }
        val processes = rows.map { row ->
            val prev = previous[row.pid]
            val cpu = prev?.let { (prevTicks, prevAt) ->
                val seconds = (now - prevAt) / 1000.0
                if (seconds <= 0.05) null
                else (row.ticks - prevTicks).coerceAtLeast(0L) / SAMPLER_TICKS_PER_SECOND / seconds * 100.0
            }
            LinuxProcessInfo(
                pid = row.pid,
                ppid = row.ppid,
                command = row.command,
                usage = ProcessUsage.describe(row.command),
                cwd = ProcessUsage.readableCwd(row.cwd),
                rssKb = row.rssKb,
                cpuPercent = cpu,
            )
        }.sortedWith(compareByDescending<LinuxProcessInfo> { it.cpuPercent ?: 0.0 }.thenBy { it.pid })
        synchronized(previousTicksLock) {
            previousTicks = rows.associate { it.pid to (it.ticks to now) }
        }
        _snapshot.value = LinuxProcessSnapshot(processes, now)
    }

    /** 结束单个进程；调用方负责乐观更新列表。 */
    fun killProcess(pid: Int): Boolean = runCatching {
        if (pid <= 1 || !RootAccess.isGranted) return false
        val process = ProcessBuilder("/system/bin/su", "-c", "kill -9 $pid").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        !output.contains("Permission denied") && !output.contains("inaccessible or not found")
    }.getOrDefault(false)

    /** 兜底清理可能残留的采样 shell（su 被杀后子进程会变孤儿）。 */
    private fun killStraySampler() {
        runCatching {
            for (su in suCandidates) {
                val process = ProcessBuilder(su, "-c", "pkill -f $SCRIPT_MARKER")
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader().readText()
                process.waitFor()
            }
        }
    }
}

/** Android 上 CLK_TCK 固定为 100；和 ScriptRuntimeUsage 里的同名私有常量分开命名，避免顶层冲突。 */
private const val SAMPLER_TICKS_PER_SECOND = 100.0

/**
 * 按脚本任务的根 pid 汇总进程树占用。
 * 快照里没有根进程（已退出、或受 /proc 权限限制）时不返回该项，UI 显示「不可用」。
 */
internal fun aggregateProcessTrees(
    processes: List<LinuxProcessInfo>,
    roots: Collection<Int>,
): Map<Int, ScriptRuntimeUsage> {
    if (roots.isEmpty() || processes.isEmpty()) return emptyMap()
    val byPid = processes.associateBy { it.pid }
    val children = processes.groupBy { it.ppid }
    val result = mutableMapOf<Int, ScriptRuntimeUsage>()
    roots.distinct().forEach { root ->
        val tree = mutableListOf<LinuxProcessInfo>()
        val queue = ArrayDeque<Int>()
        val seen = mutableSetOf<Int>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            if (!seen.add(pid)) continue
            byPid[pid]?.let { tree += it }
            children[pid]?.forEach { queue.add(it.pid) }
        }
        if (tree.isEmpty()) return@forEach
        result[root] = ScriptRuntimeUsage(
            cpuPercent = tree.mapNotNull { it.cpuPercent }.sum(),
            rssBytes = tree.sumOf { it.rssKb } * 1024,
            processCount = tree.size,
        )
    }
    return result
}

/** 不属于任何脚本任务进程树的进程，即「游离进程」。 */
internal fun detachedProcesses(
    processes: List<LinuxProcessInfo>,
    roots: Collection<Int>,
): List<LinuxProcessInfo> {
    if (roots.isEmpty()) return processes
    val children = processes.groupBy { it.ppid }
    val inTree = mutableSetOf<Int>()
    roots.distinct().forEach { root ->
        val queue = ArrayDeque<Int>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            if (!inTree.add(pid)) continue
            children[pid]?.forEach { queue.add(it.pid) }
        }
    }
    return processes.filter { it.pid !in inTree }
}
