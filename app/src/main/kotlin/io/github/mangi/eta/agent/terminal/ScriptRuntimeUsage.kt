package io.github.mangi.eta.agent.terminal

/**
 * 一个脚本运行时的进程树占用。pid 只是外壳（su / chroot / proot 包装下几乎不耗 CPU），
 * 所以统计口径是以宿主 pid 为根的整棵进程树。
 */
internal data class ScriptRuntimeUsage(
    val cpuPercent: Double,
    val rssBytes: Long,
    val processCount: Int,
)

/**
 * 读取脚本进程树的 CPU / RSS / 进程数。
 *
 * 采样在 Root 一次性 Shell 里完成：先全量扫一遍 /proc 建 ppid 表并累加树内 RSS，
 * 隔 1 秒再读树内进程的 utime+stime 取差值。读不到（无 Root、进程已退出、/proc 不可读）
 * 一律返回空，由 UI 显示「不可用」，不抛异常也不报 0。
 */
internal class ScriptRuntimeUsageReader(
    private val processSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(),
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
) : AutoCloseable {

    fun read(pids: List<Long>): Map<Long, ScriptRuntimeUsage> {
        val roots = pids.filter { it > 1 }.distinct()
        if (roots.isEmpty() || !rootAvailable()) return emptyMap()
        val result = runOneShotShell(
            processSupervisor = processSupervisor,
            identity = "root",
            command = samplingScript(roots),
            timeoutSeconds = 20,
            maxOutputBytes = 64 * 1024,
        )
        if (result.exitCode != 0) return emptyMap()
        return parseScriptUsage(result.output.decodeToString())
    }

    fun read(pid: Long): ScriptRuntimeUsage? = read(listOf(pid))[pid]

    override fun close() {
        processSupervisor.beginClosing()
    }
}

/** CLK_TCK 在 Android 上固定为 100；差值除以它换算成秒。 */
private const val CLOCK_TICKS_PER_SECOND = 100.0

private fun samplingScript(roots: List<Long>): String = """
eta_roots='${roots.joinToString(" ")}'
eta_all=''
read -r eta_u0 eta_x < /proc/uptime 2>/dev/null || eta_u0=0
for d in /proc/[0-9]*; do
  p=${'$'}{d#/proc/}
  read -r eta_line 2>/dev/null < "${'$'}d/stat" || continue
  case "${'$'}eta_line" in *") "*) ;; *) continue ;; esac
  eta_rest=${'$'}{eta_line##*) }
  set -- ${'$'}eta_rest
  eval "eta_pp_${'$'}p=${'$'}2"
  eval "eta_tk_${'$'}p=${'$'}(( ${'$'}{12} + ${'$'}{13} ))"
  eta_all="${'$'}eta_all ${'$'}p"
done
for r in ${'$'}eta_roots; do
  eta_tree=''
  eta_ticks=0
  eta_count=0
  for p in ${'$'}eta_all; do
    eta_c=${'$'}p
    eta_n=0
    eta_hit=1
    while [ "${'$'}eta_n" -lt 16 ]; do
      if [ "${'$'}eta_c" = "${'$'}r" ]; then eta_hit=0; break; fi
      if [ -z "${'$'}eta_c" ] || [ "${'$'}eta_c" = "0" ] || [ "${'$'}eta_c" = "1" ]; then break; fi
      eval "eta_c=\${'$'}eta_pp_${'$'}eta_c"
      eta_n=$((eta_n+1))
    done
    if [ "${'$'}eta_hit" = "0" ]; then
      eta_tree="${'$'}eta_tree ${'$'}p"
      eta_count=$((eta_count+1))
      eval "eta_tk=\${'$'}eta_tk_${'$'}p"
      eta_ticks=$((eta_ticks+eta_tk))
    fi
  done
  eta_rss=0
  for p in ${'$'}eta_tree; do
    eta_v=0
    while read -r eta_k eta_val eta_x2; do
      if [ "${'$'}eta_k" = "VmRSS:" ]; then eta_v=${'$'}eta_val; break; fi
    done 2>/dev/null < "/proc/${'$'}p/status"
    eta_rss=$((eta_rss+eta_v))
  done
  eval "eta_tree_${'$'}r=\${'$'}eta_tree"
  eval "eta_ticks_${'$'}r=${'$'}eta_ticks"
  eval "eta_rss_${'$'}r=${'$'}eta_rss"
  eval "eta_count_${'$'}r=${'$'}eta_count"
done
sleep 1
read -r eta_u1 eta_x < /proc/uptime 2>/dev/null || eta_u1=0
for r in ${'$'}eta_roots; do
  eval "eta_tree=\${'$'}eta_tree_${'$'}r"
  eval "eta_t1=\${'$'}eta_ticks_${'$'}r"
  eval "eta_rss=\${'$'}eta_rss_${'$'}r"
  eval "eta_count=\${'$'}eta_count_${'$'}r"
  eta_t2=0
  for p in ${'$'}eta_tree; do
    if read -r eta_line 2>/dev/null < "/proc/${'$'}p/stat"; then
      eta_rest=${'$'}{eta_line##*) }
      set -- ${'$'}eta_rest
      eta_t2=$(( eta_t2 + ${'$'}{12} + ${'$'}{13} ))
    fi
  done
  eta_alive=1
  [ -r "/proc/${'$'}r/stat" ] || eta_alive=0
  echo "USAGE ${'$'}r ${'$'}eta_alive ${'$'}eta_count ${'$'}eta_rss ${'$'}eta_t1 ${'$'}eta_t2"
done
echo "ELAPSED ${'$'}eta_u0 ${'$'}eta_u1"
""".trimIndent()

/** 解析采样脚本输出；进程已退出或间隔不可用（<=0）时该 pid 直接不出现。 */
internal fun parseScriptUsage(output: String): Map<Long, ScriptRuntimeUsage> {
    var elapsed = 0.0
    val rows = mutableListOf<List<String>>()
    output.lineSequence().forEach { line ->
        val parts = line.trim().split(' ')
        when (parts.firstOrNull()) {
            "USAGE" -> if (parts.size == 7) rows += parts
            "ELAPSED" -> if (parts.size == 3) {
                val start = parts[1].toDoubleOrNull()
                val end = parts[2].toDoubleOrNull()
                if (start != null && end != null) elapsed = end - start
            }
        }
    }
    if (elapsed <= 0.0) return emptyMap()
    return rows.mapNotNull { parts ->
        val pid = parts[1].toLongOrNull() ?: return@mapNotNull null
        if (parts[2] != "1") return@mapNotNull null
        val count = parts[3].toIntOrNull() ?: return@mapNotNull null
        val rssKb = parts[4].toLongOrNull() ?: return@mapNotNull null
        val ticks1 = parts[5].toLongOrNull() ?: return@mapNotNull null
        val ticks2 = parts[6].toLongOrNull() ?: return@mapNotNull null
        val delta = (ticks2 - ticks1).coerceAtLeast(0L)
        pid to ScriptRuntimeUsage(
            cpuPercent = delta / CLOCK_TICKS_PER_SECOND / elapsed * 100.0,
            rssBytes = rssKb * 1024,
            processCount = count,
        )
    }.toMap()
}
