package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.data.repository.AgentMemoryCoreUsage
import io.github.mangi.eta.data.repository.AgentMemoryEntry
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 记忆容量压缩协调器。
 *
 * 空闲审查（[AgentMemoryIdleReview]）只增不减；这里新开一条**压缩**路径：
 * 注入投影超过预算（已有条目被挤掉，或已用 ≥90%）时，起一个 `memory-compact:` run，
 * 允许合并/删除条目、重写画像，把投影压到预算的 80% 以内。
 *
 * 触发点统一走 [request]：memory_write 之后、回合结束之后、App 启动后首次、记忆页手动。
 * 节流：同一份记忆只压一次、全局最小间隔 10 分钟、与空闲审查互斥。
 */
internal object AgentMemoryCompactor {

    const val COMPACT_RUN_PREFIX = "memory-compact:"
    const val MIN_INTERVAL_MS = 10 * 60 * 1000L

    /** 压到预算的这个比例以下即停手，不要压到 0。 */
    const val TARGET_RATIO = 0.8f

    /** 压缩 run 里 ask_user 的等待上限：用户不在就放弃本轮删除动作。 */
    const val ASK_TIMEOUT_MS = 90_000L

    /** 压不动、需要用户拍板；记忆页据此显示提示条。 */
    private val pendingState = MutableStateFlow(false)
    val pendingDecision: StateFlow<Boolean> = pendingState.asStateFlow()

    private var launcher: ((String) -> Unit)? = null
    private var trace: ((String) -> Unit)? = null
    private var reviewActive: (() -> Boolean)? = null
    private var notify: ((text: String, pending: Boolean) -> Unit)? = null

    private var lastAttemptAtMillis = 0L
    private var lastCompactedRevision: String? = null
    private var activeRun = false
    private var beforeSnapshot: CompactionSnapshot? = null

    /** 压缩前的样子，跑完用来算「删了几条、改写了几条」。 */
    private data class CompactionSnapshot(
        val profileBytes: Int,
        val entries: List<String>,
    )

    fun attach(
        launcher: (String) -> Unit,
        trace: (String) -> Unit,
        reviewActive: () -> Boolean,
        notify: (text: String, pending: Boolean) -> Unit,
    ) {
        this.launcher = launcher
        this.trace = trace
        this.reviewActive = reviewActive
        this.notify = notify
    }

    fun detach() {
        launcher = null
        trace = null
        reviewActive = null
        notify = null
        activeRun = false
        beforeSnapshot = null
    }

    fun isCompactionRun(runId: String): Boolean = runId.startsWith(COMPACT_RUN_PREFIX)

    /** 压缩 run 是否在跑；空闲审查据此避让，同一时刻只允许一个记忆整理 run。 */
    fun isActive(): Boolean = activeRun

    /** 当前容量占用；供记忆页与手动整理使用。 */
    fun usage(): AgentMemoryCoreUsage {
        val budget = AgentMemoryContextBuilder.coreBudgetChars(null)
        return AgentMemoryRepository.coreUsage(budget)
    }

    fun onRunFinished(runId: String) {
        if (!isCompactionRun(runId)) return
        activeRun = false
        lastAttemptAtMillis = System.currentTimeMillis()
        lastCompactedRevision = AgentMemoryRepository.coreSnapshot().revision
        val before = beforeSnapshot
        beforeSnapshot = null
        val usage = usage()
        val entriesNow = runCatching { AgentMemoryRepository.entries().map { it.text } }.getOrDefault(emptyList())
        val (deleted, rewritten) = diff(before?.entries.orEmpty(), entriesNow)
        val pending = usage.needsCompaction
        pendingState.value = pending
        log(
            "done profile=${before?.profileBytes ?: -1}B->${usage.profileBytes}B " +
                "entry=${usage.entryBytes}B count=${usage.entryCount}/${usage.totalEntryCount} " +
                "skipped=${usage.skippedCount} ratio=%.2f deleted=$deleted rewritten=$rewritten pending=$pending"
                    .format(usage.ratio),
        )
        notify?.invoke(summaryText(before, usage, deleted, rewritten), pending)
    }

    /**
     * 请求一次压缩整理。返回是否真的启动了 run。
     * reason 只用于日志（write / run-finished / app-start / manual）。
     */
    fun request(reason: String, manual: Boolean = false): Boolean =
        runCatching { requestInternal(reason, manual) }.getOrDefault(false)

    private fun requestInternal(reason: String, manual: Boolean): Boolean {
        val launch = launcher
        if (launch == null) {
            log("skip($reason): 压缩器未就绪")
            return false
        }
        if (activeRun) {
            log("skip($reason): 已有压缩 run 在跑")
            return false
        }
        if (reviewActive?.invoke() == true) {
            log("skip($reason): 空闲审查正在进行")
            return false
        }
        val usage = usage()
        if (!usage.needsCompaction) {
            pendingState.value = false
            log("skip($reason): ratio=%.2f skipped=%d 未到阈值".format(usage.ratio, usage.skippedCount))
            return false
        }
        val now = System.currentTimeMillis()
        val revision = AgentMemoryRepository.coreSnapshot().revision
        if (!manual && revision == lastCompactedRevision) {
            log("skip($reason): 同一份记忆已经压过")
            return false
        }
        if (!manual && now - lastAttemptAtMillis < MIN_INTERVAL_MS) {
            log("skip($reason): 距上次压缩不足 ${MIN_INTERVAL_MS / 60_000} 分钟")
            return false
        }
        val prompt = buildCompactionPrompt(usage)
        activeRun = true
        lastAttemptAtMillis = now
        beforeSnapshot = CompactionSnapshot(
            profileBytes = usage.profileBytes,
            entries = runCatching { AgentMemoryRepository.entries().map { it.text } }.getOrDefault(emptyList()),
        )
        log(
            "start($reason) profile=${usage.profileBytes}B entry=${usage.entryBytes}B " +
                "count=${usage.entryCount}/${usage.totalEntryCount} ratio=%.2f".format(usage.ratio),
        )
        runCatching { launch(prompt) }.onFailure { failure ->
            activeRun = false
            beforeSnapshot = null
            log("start failed: ${failure.javaClass.simpleName}")
        }
        return true
    }

    private fun log(line: String) {
        trace?.invoke("compact $line")
    }

    /** 删了几条 / 改写了几条：同一份内容改短表现为「少一条 + 多一条」。 */
    private fun diff(before: List<String>, after: List<String>): Pair<Int, Int> {
        if (before.isEmpty()) return 0 to 0
        val removed = before.filterNot { it in after }.size
        val added = after.filterNot { it in before }.size
        val deleted = (removed - added).coerceAtLeast(0)
        val rewritten = minOf(removed, added)
        return deleted to rewritten
    }

    private fun summaryText(
        before: CompactionSnapshot?,
        usage: AgentMemoryCoreUsage,
        deleted: Int,
        rewritten: Int,
    ): String = buildString {
        if (before != null) {
            append("画像 ${formatKb(before.profileBytes)}→${formatKb(usage.profileBytes)}")
        } else {
            append("画像 ${formatKb(usage.profileBytes)}")
        }
        append(" · 条目 ${usage.entryCount}/${usage.totalEntryCount} 条")
        if (deleted > 0) append(" · 删除 $deleted 条")
        if (rewritten > 0) append(" · 改写 $rewritten 条")
        append(" · 已用 ${(usage.ratio * 100).toInt()}%")
    }

    private fun formatKb(bytes: Int): String = "%.1f KB".format(Locale.US, bytes / 1024.0)

    /** 压缩 run 的提示词：与空闲审查相反，这一轮允许删除条目、允许重写画像。 */
    fun buildCompactionPrompt(usage: AgentMemoryCoreUsage): String {
        val profile = runCatching { AgentMemoryRepository.coreProfile() }.getOrDefault("")
        val entries = runCatching { AgentMemoryRepository.entries() }.getOrDefault(emptyList())
        val skipped = runCatching { AgentMemoryRepository.coreSkippedEntries(usage.budgetChars) }
            .getOrDefault(emptyList())
        val target = (usage.budgetChars * TARGET_RATIO).toInt()
        return buildString {
            appendLine("你是记忆压缩子代理。目标：把「用户画像 + 工作记忆条目」的总占用压到预算的 80% 以内（目标约 $target 字符），不是压到 0。")
            appendLine()
            appendLine("当前占用：预算 ${usage.budgetChars} 字符，已用 ${usage.usedBytes} 字符（画像 ${usage.profileBytes} 字节 + 条目 ${usage.entryBytes} 字符），")
            appendLine("注入条目 ${usage.entryCount} 条 / 共 ${usage.totalEntryCount} 条，已被预算挤掉 ${usage.skippedCount} 条。")
            appendLine()
            appendLine("**这一轮允许删除条目，也允许重写用户画像**（与空闲整理相反，别按空闲整理的规则来）。")
            appendLine("写入限制：")
            appendLine("- 条目：memory_write target=entry match=\"<原句片段>\" content=\"<更短的新句>\" 改写；content=\"\" 删除。")
            appendLine("- 画像：memory_write target=profile content=\"<整份新画像>\"（会整份替换，务必保留全部仍然有效的偏好与关系）。")
            appendLine("- 档案：只能 append 或 edit，禁止 replace；档案层不是本轮重点。")
            appendLine("- 禁止 target=working 整份覆盖：工作记忆只能逐条改写或删除。")
            appendLine()
            appendLine("执行顺序：")
            appendLine("1) 先合并同义/重复条目：用 match 改写为更短的一句；")
            appendLine("2) 再删除过时、一次性、已被档案覆盖的条目（content=\"\"）；")
            appendLine("3) 最后压缩画像：合并同类事实、删掉已过时的事实（过期日期、已完成的临时目标），")
            appendLine("   保留用户的稳定偏好、关系与长期事实；不得编造，不得丢失用户明确说过的偏好；压缩后仍是可读的短句列表。")
            appendLine("4) 每改一次给出一句话理由；达到目标就立刻停止，不要过度压缩。")
            appendLine()
            appendLine("删除取舍：优先删已经被挤掉、EI 低、很久没命中、写入很早的条目；重要度 4–5 且近期命中过的条目要保留。")
            appendLine()
            appendLine("如果画像已经压到最短、条目也合并删除过，占用仍超过预算的 90%：")
            appendLine("1) 先做一轮**无损改动**（只合并、只改写，不删除任何条目）；")
            appendLine("2) 再调用 ask_user 让用户决定，问题里写清楚「记忆快满了，画像已经压到 ${usage.profileBytes} 字节仍不够」，")
            appendLine("   选项依次是：① 删除列出的低 EI 条目（把前 3–5 条原文摘要写进选项描述）；② 把某几条改写为更短的版本（给出改写前后对照）；")
            appendLine("   ③ 接受「低 EI 条目暂不注入」，不再删任何内容；④ 提高记忆预算（会挤占上下文，需在设置里确认）。保留自定义输入。")
            appendLine("3) ask_user 会在 ${ASK_TIMEOUT_MS / 1000} 秒后超时（返回 skipped=true）：一旦超时或用户跳过，**不要再删除任何条目**，")
            appendLine("   保留已经做过的无损改动，直接结束本轮。")
            appendLine()
            appendLine("<被挤掉的条目（低 EI，优先从这里删）>")
            if (skipped.isEmpty()) {
                appendLine("（没有被挤掉的条目；压缩靠合并与改写画像）")
            } else {
                skipped.take(10).forEach { entry -> appendLine(renderEntry(entry)) }
            }
            appendLine("</被挤掉的条目>")
            appendLine()
            appendLine("<当前画像>")
            appendLine(profile.ifBlank { "（空）" })
            appendLine("</当前画像>")
            appendLine()
            appendLine("<当前条目>")
            if (entries.isEmpty()) {
                appendLine("（暂无条目）")
            } else {
                entries.take(80).forEach { entry -> appendLine(renderEntry(entry)) }
            }
            appendLine("</当前条目>")
        }.trimEnd()
    }

    /** 条目在提示词里的样子：重要度、EI、命中次数与写入时间都写清楚，便于判断谁该删。 */
    private fun renderEntry(entry: AgentMemoryEntry): String = buildString {
        append("- (重要度 ${entry.importance} · EI %.1f".format(entry.effectiveImportance))
        append(" · 命中 ${entry.hitCount} 次")
        if (entry.writtenAtMillis > 0L) {
            append(" · 写入 ").append(shortDate(entry.writtenAtMillis))
        }
        append(") ").append(entry.text)
    }

    private fun shortDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
}
