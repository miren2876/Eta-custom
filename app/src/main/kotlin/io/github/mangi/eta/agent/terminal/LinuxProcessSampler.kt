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

    /** 采样脚本的命令行标记，只用于识别与日志。 */
    private const val SCRIPT_MARKER = "eta_proc_sampler"

    /**
     * 停止标志文件：脚本每轮开头检查它，存在就自行退出。
     * 只杀 su 父进程时 root 侧 shell 会变成孤儿继续跑，这是之前 CPU 飙高的直接原因。
     */
    private const val STOP_FILE = "/data/local/tmp/eta_proc_sampler.stop"

    /** 让旧循环看到停止标志并退出的等待时间。 */
    private const val STOP_GRACE_MS = 1_500L

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

    /**
     * 额外要纳入快照的根 pid（Android 环境的脚本任务不在 rootfs 内）。
     * 过滤在 App 侧做，所以更新它不需要重启 root 循环。
     */
    @Volatile
    private var extraRootPids: Set<Int> = emptySet()

    fun setExtraRootPids(pids: Collection<Int>) {
        val next = pids.toSet()
        if (next == extraRootPids) return
        synchronized(lock) {
            if (next == extraRootPids) return
            extraRootPids = next
            // 脚本里的额外 pid 是插值，变了必须重启 root 循环。
            if (leases.isNotEmpty()) ensureRunningLocked(forceRestart = true)
        }
    }

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

    private fun ensureRunningLocked(forceRestart: Boolean = false) {
        val desired = desiredIntervalLocked()
        if (desired <= 0L) return
        if (!forceRestart && job?.isActive == true && activeIntervalMs == desired) return
        val hadLoop = job != null || activeIntervalMs != 0L
        // 间隔变了必须重启 root 循环（sleep 参数写死在脚本里）；这里不阻塞调用线程。
        job?.cancel()
        activeProcess?.destroy()
        activeProcess = null
        activeIntervalMs = desired
        job = scope.launch {
            if (hadLoop) {
                // 先让可能残留的旧循环看到停止标志，再启动新的，避免孤儿循环叠加。
                stopStrayLoops()
                delay(STOP_GRACE_MS)
            }
            runLoop(desired)
        }
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
        // 让 root 侧循环自己退出：只 destroy su 会留下孤儿 shell。
        scope.launch { stopStrayLoops() }
    }

    private suspend fun runLoop(intervalMs: Long) {
        var suIndex = 0
        var failures = 0
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
                failures += 1
                AndroidAgentLogger.warn("linux_process_sampler_su_failed: $su")
                delay(backoffMillis(failures))
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
                                publish(rows)
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
            if (!sawData) {
                suIndex += 1
                failures += 1
            } else {
                failures = 0
            }
            if (!currentCoroutineContext().isActive) break
            delay(if (failures > 0) backoffMillis(failures) else RESTART_DELAY_MS)
        }
    }

    /** 连续失败时指数退避，避免 su 不可用时每 2 秒 fork 一次。 */
    private fun backoffMillis(failures: Int): Long =
        (2_000L shl failures.coerceIn(0, 5)).coerceAtMost(60_000L)

    private fun startSamplerProcess(su: String, intervalMs: Long): Process? = runCatching {
        ProcessBuilder(su, "-c", samplerScript(intervalMs)).redirectErrorStream(true).start()
    }.getOrNull()

    private fun samplerScript(intervalMs: Long): String {
        val intervalSeconds = (intervalMs.coerceAtLeast(MIN_INTERVAL_MS) / 1000.0).toString()
        // 每轮只做一次 ls 批量读 /proc/*/root 筛候选 pid，再只对候选读 stat/status/cmdline。
        // 逐进程 fork 的写法在这台机器上 10 秒才处理 74 个进程，常驻循环根本跑不完一轮。
        // 没有命中进程时把周期放宽到 5 秒，空闲时几乎不占 CPU。
        return """
            # $SCRIPT_MARKER
            STOP='$STOP_FILE'
            EXTRA_PIDS='${extraRootPids.joinToString(" ")}'
            rm -f "${'$'}STOP"
            while [ ! -f "${'$'}STOP" ]; do
              hits=${'$'}( {
                ls -l /proc/[0-9]*/root 2>/dev/null | awk '
                  {
                    i = index(${'$'}0, " -> ")
                    if (i == 0) next
                    path = substr(${'$'}0, i + 4)
                    if (path !~ /terminal/ || path !~ /rootfs/) next
                    lhs = substr(${'$'}0, 1, i - 1)
                    sub(/.*\/proc\//, "", lhs)
                    sub(/\/.*/, "", lhs)
                    if (lhs != "") print lhs
                  }'
                for r in ${'$'}EXTRA_PIDS; do echo "${'$'}r"; done
              } | sort -u )
              for pid in ${'$'}hits; do
                [ -d "/proc/${'$'}pid" ] || continue
                st=${'$'}(cat "/proc/${'$'}pid/stat" 2>/dev/null)
                [ -z "${'$'}st" ] && continue
                rest=${'$'}{st##*) }
                set -- ${'$'}rest
                ppid=${'$'}{2:-0}
                ticks=${'$'}(( ${'$'}{12:-0} + ${'$'}{13:-0} ))
                rss=${'$'}(awk '/^VmRSS/{print ${'$'}2}' "/proc/${'$'}pid/status" 2>/dev/null)
                root=${'$'}(readlink "/proc/${'$'}pid/root" 2>/dev/null)
                exe=${'$'}(readlink "/proc/${'$'}pid/exe" 2>/dev/null)
                cwd=${'$'}(readlink "/proc/${'$'}pid/cwd" 2>/dev/null)
                cmd=${'$'}(tr '\0' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null | cut -c1-$MAX_COMMAND_CHARS)
                [ -z "${'$'}cmd" ] && cmd="[${'$'}exe]"
                echo "P|${'$'}pid|${'$'}ppid|${'$'}ticks|${'$'}{rss:-0}|${'$'}root|${'$'}exe|${'$'}cwd|${'$'}cmd"
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
        val rootfsHit: Boolean,
        val cwd: String?,
        val command: String,
    )

    private fun parseRow(line: String): SnapshotRow? {
        // 命令放最后：命令行里可能出现任何字符，split 限制在 9 段内。
        val parts = line.split('|', limit = 9)
        if (parts.size < 9) return null
        val pid = parts[1].trim().toIntOrNull() ?: return null
        val root = parts[5]
        val exe = parts[6]
        return SnapshotRow(
            pid = pid,
            ppid = parts[2].trim().toIntOrNull() ?: 0,
            ticks = parts[3].trim().toLongOrNull() ?: 0L,
            rssKb = parts[4].trim().toLongOrNull() ?: 0L,
            rootfsHit = isRootfsPath(root) || isRootfsPath(exe),
            cwd = parts[7].trim().ifBlank { null },
            command = parts[8].trim(),
        )
    }

    private fun isRootfsPath(path: String): Boolean =
        path.contains("terminal") && path.contains("rootfs")

    /** 只保留 rootfs 内进程，外加脚本任务（含 Android 环境）的整棵进程树。 */
    private fun filterRows(rows: List<SnapshotRow>): List<SnapshotRow> {
        val extra = extraRootPids
        if (extra.isEmpty()) return rows.filter { it.rootfsHit }
        val children = rows.groupBy { it.ppid }
        val keep = mutableSetOf<Int>()
        val queue = ArrayDeque<Int>()
        extra.forEach { queue.add(it) }
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            if (!keep.add(pid)) continue
            children[pid]?.forEach { queue.add(it.pid) }
        }
        return rows.filter { it.rootfsHit || it.pid in keep }
    }

    private fun publish(rows: List<SnapshotRow>) {
        val now = System.currentTimeMillis()
        val filtered = filterRows(rows)
        if (filtered.isEmpty()) {
            _snapshot.value = LinuxProcessSnapshot(emptyList(), now)
            synchronized(previousTicksLock) { previousTicks = emptyMap() }
            return
        }
        val previous = synchronized(previousTicksLock) { previousTicks }
        val processes = filtered.map { row ->
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
            previousTicks = filtered.associate { it.pid to (it.ticks to now) }
        }
        _snapshot.value = LinuxProcessSnapshot(processes, now)
    }

    /** 结束单个进程；调用方负责乐观更新列表。 */
    fun killProcess(pid: Int): Boolean {
        if (pid <= 1 || !RootAccess.isGranted) return false
        for (su in suCandidates) {
            val ok = runCatching {
                val process = ProcessBuilder(su, "-c", "kill -9 $pid").redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                !output.contains("Permission denied") && !output.contains("inaccessible or not found")
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /** 让所有 root 侧采样循环看到停止标志并退出（写文件比 pkill 安全，也不会误杀自己）。 */
    private fun stopStrayLoops() {
        for (su in suCandidates) {
            val ok = runCatching {
                val process = ProcessBuilder(su, "-c", "touch $STOP_FILE").redirectErrorStream(true).start()
                process.inputStream.bufferedReader().readText()
                process.waitFor()
                true
            }.getOrDefault(false)
            if (ok) return
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

/** 快照刷新后只保留仍然存在的 pid，避免 pid 复用后误杀。 */
internal fun retainLivePids(selected: Set<Int>, live: Set<Int>): Set<Int> = selected intersect live
