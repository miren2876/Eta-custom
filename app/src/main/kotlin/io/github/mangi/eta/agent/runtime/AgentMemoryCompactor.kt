package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.data.repository.AgentMemoryCoreUsage
import io.github.mangi.eta.data.repository.AgentMemoryRepository

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

    private var launcher: ((String) -> Unit)? = null
    private var trace: ((String) -> Unit)? = null
    private var reviewActive: (() -> Boolean)? = null

    private var lastAttemptAtMillis = 0L
    private var lastCompactedRevision: String? = null
    private var activeRun = false

    fun attach(
        launcher: (String) -> Unit,
        trace: (String) -> Unit,
        reviewActive: () -> Boolean,
    ) {
        this.launcher = launcher
        this.trace = trace
        this.reviewActive = reviewActive
    }

    fun detach() {
        launcher = null
        trace = null
        reviewActive = null
        activeRun = false
    }

    fun isCompactionRun(runId: String): Boolean = runId.startsWith(COMPACT_RUN_PREFIX)

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
        val usage = usage()
        log(
            "compact done profile=${usage.profileBytes}B entry=${usage.entryBytes}B " +
                "count=${usage.entryCount}/${usage.totalEntryCount} skipped=${usage.skippedCount} " +
                "ratio=%.2f".format(usage.ratio),
        )
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
        log(
            "start($reason) profile=${usage.profileBytes}B entry=${usage.entryBytes}B " +
                "count=${usage.entryCount}/${usage.totalEntryCount} ratio=%.2f".format(usage.ratio),
        )
        runCatching { launch(prompt) }.onFailure { failure ->
            activeRun = false
            log("start failed: ${failure.javaClass.simpleName}")
        }
        return true
    }

    private fun log(line: String) {
        trace?.invoke("compact $line")
    }

    /** 压缩 run 的提示词：与空闲审查相反，这一轮允许删除条目、允许重写画像。 */
    fun buildCompactionPrompt(usage: AgentMemoryCoreUsage): String {
        val profile = runCatching { AgentMemoryRepository.coreProfile() }.getOrDefault("")
        val entries = runCatching { AgentMemoryRepository.entries() }.getOrDefault(emptyList())
        val target = (usage.budgetChars * TARGET_RATIO).toInt()
        return buildString {
            appendLine("你是记忆压缩子代理。目标：把「用户画像 + 工作记忆条目」的总占用压到预算的 80% 以内（目标约 $target 字符），不是压到 0。")
            appendLine()
            appendLine("当前占用：预算 ${usage.budgetChars} 字符，已用 ${usage.usedBytes} 字符（画像 ${usage.profileBytes} 字节 + 条目 ${usage.entryBytes} 字符），")
            appendLine("注入条目 ${usage.entryCount} 条 / 共 ${usage.totalEntryCount} 条，已被预算挤掉 ${usage.skippedCount} 条。")
            appendLine()
            appendLine("**这一轮允许删除条目，也允许重写用户画像**（与空闲整理相反，别按空闲整理的规则来）。")
            appendLine("写入限制：")
            appendLine("- 条目：memory_write target=entry match=\\\"<原句片段>\\\" content=\\\"<更短的新句>\\\" 改写；content=\\\"\\\" 删除。")
            appendLine("- 画像：memory_write target=profile content=\\\"<整份新画像>\\\"（会整份替换，务必保留全部仍然有效的偏好与关系）。")
            appendLine("- 档案：只能 append 或 edit，禁止 replace；档案层不是本轮重点。")
            appendLine("- 禁止 target=working 整份覆盖：工作记忆只能逐条改写或删除。")
            appendLine()
            appendLine("执行顺序：")
            appendLine("1) 先合并同义/重复条目：用 match 改写为更短的一句；")
            appendLine("2) 再删除过时、一次性、已被档案覆盖的条目（content=\\\"\\\"）；")
            appendLine("3) 最后压缩画像：合并同类事实、删掉已过时的事实（过期日期、已完成的临时目标），")
            appendLine("   保留用户的稳定偏好、关系与长期事实；不得编造，不得丢失用户明确说过的偏好；压缩后仍是可读的短句列表。")
            appendLine("4) 每改一次给出一句话理由；达到目标就立刻停止，不要过度压缩。")
            appendLine()
            appendLine("如果画像已经压到最短、条目也合并删除过，占用仍超过预算的 90%：")
            appendLine("调用 ask_user 让用户决定（删除低 EI 条目 / 改写为更短版本 / 接受条目不注入 / 提高预算），")
            appendLine("选项要具体、可执行，保留自定义输入。ask_user 没有答复或超时，就保留已经做过的无损改动，不再删除任何内容。")
            appendLine()
            appendLine("<当前画像>")
            appendLine(profile.ifBlank { "（空）" })
            appendLine("</当前画像>")
            appendLine()
            appendLine("<当前条目>")
            if (entries.isEmpty()) {
                appendLine("（暂无条目）")
            } else {
                entries.take(80).forEach { entry ->
                    appendLine("- (重要度 ${entry.importance} · EI %.1f) %s".format(entry.effectiveImportance, entry.text))
                }
            }
            appendLine("</当前条目>")
        }.trimEnd()
    }
}
