package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.model.SystemNoticeCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 整理 run 完成后的结论：交给聊天流在发起整理的会话里注入一条节点。
 * 流体云通知由通知层另外单独发出，两者互不替代。
 */
internal data class AgentMemoryReviewOutcome(
    val token: String,
    val conversationId: String,
    val code: SystemNoticeCode,
    val detail: String?,
)

/**
 * 记忆「空闲审查」：run 结束后按**确定性打分**判断这次值不值得整理，
 * 过门槛再等一段空闲，起一个**隐藏的整理 run**（只给记忆工具、一轮只写一层）。
 *
 * 参照 dsh-mnemon 的 Idle Review：只用结构信号、不调 LLM 分类，也不持久化累计信号
 * （进程重启即丢失，和上游一致）。
 */
internal class AgentMemoryIdleReview(
    private val postDelayed: (Long, Runnable) -> Unit,
    private val cancelDelayed: (Runnable) -> Unit,
    private val launchReview: (sessionKey: String, prompt: String) -> Unit,
    private val trace: (String) -> Unit = {},
    private val notifyOutcome: (code: SystemNoticeCode, name: String?, sessionKey: String?) -> Unit =
        { _, _, _ -> },
) {
    private data class Signal(
        var userChars: Int = 0,
        var assistantChars: Int = 0,
        var turns: Int = 0,
        var toolResults: Int = 0,
        val toolNames: MutableSet<String> = mutableSetOf(),
    )

    private data class Binding(val sessionKey: String, val historySize: Int)

    private val bindings = mutableMapOf<String, Binding>()
    private val signals = mutableMapOf<String, Signal>()
    private var lastAttemptAtMillis = 0L
    private var pendingAction: Runnable? = null
    private var activeReviewSessionKey: String? = null
    private var reviewSnapshot: Map<String, Int>? = null
    private val reviewToolCalls = mutableListOf<String>()

    fun isReviewRun(runId: String): Boolean = runId.startsWith(REVIEW_RUN_PREFIX)

    /** 登记一次 run：记住会话归属与历史长度（只统计本次新增的消息）。 */
    fun bindRun(runId: String, sessionKey: String, historySize: Int) {
        if (isReviewRun(runId)) {
            // 整理 run 沿用发起它的会话键：完成后用来清掉那一段的累计信号。
            if (activeReviewSessionKey == null) activeReviewSessionKey = sessionKey
            return
        }
        // 没有会话归属的 run（sessionKey 退回 runId）统一并到一个桶里，
        // 否则每轮都是新键，回合数永远只有 1。
        val stable = if (sessionKey.isBlank() || sessionKey == runId) DEFAULT_SESSION_KEY else sessionKey
        bindings[runId] = Binding(stable, historySize)
    }

    /** 用户又发起了新任务：取消还没到点的整理（累计信号保留）。 */
    fun onUserRunStarted() {
        if (pendingAction != null) trace("用户发起新任务：取消待执行的整理")
        pendingAction?.let(cancelDelayed)
        pendingAction = null
    }

    /** 还有待执行/正在跑的整理：服务此时不能停，否则整理 run 因服务已销毁被静默丢弃。 */
    fun hasPendingWork(): Boolean = pendingAction != null || activeReviewSessionKey != null

    fun recordToolFinished(runId: String, toolName: String) {
        if (isReviewRun(runId)) {
            // 整理 run 的工具调用是「到底写没写」的唯一直接证据，别丢。
            if (toolName.isNotBlank()) reviewToolCalls += toolName
            return
        }
        val key = bindings[runId]?.sessionKey ?: return
        val signal = signals.getOrPut(key) { Signal() }
        signal.toolResults += 1
        if (toolName.isNotBlank()) signal.toolNames += toolName
    }

    /** run 结束：累计信号 → 打分 → 准入 → 排一个空闲定时器。 */
    fun onRunFinished(
        runId: String,
        transcript: List<AgentModelClient.ConversationMessage>,
        enabled: Boolean,
        /** 本轮用户输入字符数：3.3.4 的 transcript 不含用户消息，必须由调用方传入。 */
        userChars: Int = 0,
    ) {
        if (isReviewRun(runId)) {
            val key = activeReviewSessionKey
            activeReviewSessionKey = null
            // 整理已尝试过：清空累计信号（简化上游的"成功才清"，失败也不重放）。
            if (key != null) signals.remove(key)
            reportOutcome(key)
            return
        }
        val binding = bindings.remove(runId) ?: return
        if (!enabled) {
            trace("本次 run 结束：开关关闭，跳过")
            return
        }
        // 只取结尾一段：run 的 transcript 形状不固定（有时含请求历史、有时只有本轮），
        // 用「结尾 N 条」既能覆盖本轮、又不会把历史重复计进去。
        val added = transcript.takeLast(TRANSCRIPT_TAIL)
        val signal = signals.getOrPut(binding.sessionKey) { Signal() }
        added.forEach { message ->
            when (message.role) {
                ROLE_USER -> signal.userChars += message.content.length
                ROLE_ASSISTANT -> signal.assistantChars += message.content.length
                ROLE_TOOL -> signal.toolResults += 1
            }
        }
        // 用户消息在本轮 transcript 的开头，takeLast 取不到：补上最后一条用户发言，
        // 否则「用户字符」这项恒为 0，打分里最多 3 分永远拿不到。
        if (added.none { it.role == ROLE_USER }) {
            transcript.lastOrNull { it.role == ROLE_USER }?.let { signal.userChars += it.content.length }
        }
        if (userChars > 0) signal.userChars += userChars
        signal.turns += 1
        val score = score(signal)
        if (score < SCORE_THRESHOLD) {
            val line = "分数未达门槛 score=%.1f 会话=%s 回合=%d 用户字符=%d 助手字符=%d 工具=%d/%d transcript=%d history=%d".format(
                score, sessionTag(binding.sessionKey), signal.turns, signal.userChars, signal.assistantChars,
                signal.toolResults, signal.toolNames.size, transcript.size, binding.historySize,
            )
            AndroidAgentLogger.info("Memory idle review: below threshold. $line")
            trace(line)
            return
        }
        if (added.lastOrNull { it.role == ROLE_USER }?.content?.let { NO_REMEMBER.containsMatchIn(it) } == true) {
            signals.remove(binding.sessionKey)
            AndroidAgentLogger.info("Memory idle review: skipped by explicit request")
            trace("用户说了不要记，跳过")
            return
        }
        if (!hasDurableCandidate(signal)) {
            AndroidAgentLogger.info("Memory idle review: no durable candidate (score=%.1f)".format(score))
            trace("过门槛（score=%.1f）但没有可持久内容，跳过".format(score))
            return
        }
        val sessionKey = binding.sessionKey
        val delay = maxOf(IDLE_DELAY_MS, lastAttemptAtMillis + MIN_INTERVAL_MS - System.currentTimeMillis())
        pendingAction?.let(cancelDelayed)
        val action = Runnable {
            pendingAction = null
            startReview(sessionKey, score, added)
        }
        pendingAction = action
        AndroidAgentLogger.info(
            "Memory idle review: scheduled in ${delay}ms (score=%.1f)".format(score)
        )
        trace(
            "过门槛 score=%.1f（会话=%s 回合=%d 用户字符=%d 助手字符=%d 工具=%d/%d）：${delay / 1000} 秒后启动整理（用户一发消息就取消）".format(
                score, sessionTag(binding.sessionKey), signal.turns, signal.userChars, signal.assistantChars,
                signal.toolResults, signal.toolNames.size,
            )
        )
        postDelayed(delay, action)
    }

    private fun startReview(
        sessionKey: String,
        score: Double,
        added: List<AgentModelClient.ConversationMessage>,
    ) {
        lastAttemptAtMillis = System.currentTimeMillis()
        activeReviewSessionKey = sessionKey
        // 记下整理前的档案快照，跑完对比出「新建了谁 / 追加到谁」。
        reviewToolCalls.clear()
        reviewSnapshot = runCatching { archiveSizes() }.getOrNull()
        val prompt = buildPrompt(score, added)
        trace("启动整理 run（score=%.1f）".format(score))
        runCatching { launchReview(sessionKey, prompt) }.onFailure { failure ->
            activeReviewSessionKey = null
            AndroidAgentLogger.warn("Memory idle review launch failed: type=${failure.safeLogType()}")
            trace("整理 run 启动失败：${failure.safeLogType()}")
        }
    }

    /** 整理跑完后对比档案快照，把结果交给通知层与聊天流（新建 / 追加 / 无变化）。 */
    private fun reportOutcome(sessionKey: String?) {
        val before = reviewSnapshot
        reviewSnapshot = null
        val toolCalls = reviewToolCalls.toList()
        val writes = toolCalls.count { it == "memory_write" }
        reviewToolCalls.clear()
        if (before == null) return
        val after = runCatching { archiveSizes() }.getOrNull() ?: return
        val created = after.keys - before.keys
        val appended = after.filter { (name, size) -> before[name]?.let { it < size } == true }.keys - created
        when {
            created.isNotEmpty() -> {
                trace("整理完成：新建档案《${created.first()}》")
                notifyOutcome(SystemNoticeCode.MemoryReviewNew, created.first(), sessionKey)
            }
            appended.isNotEmpty() -> {
                trace("整理完成：追加到《${appended.first()}》")
                notifyOutcome(SystemNoticeCode.MemoryReviewAppended, appended.first(), sessionKey)
            }
            writes > 0 -> {
                // 等长替换（mode=edit）不会改变字节数，不能因为比对不到就报「没有内容」。
                trace("整理完成：执行了 $writes 次 memory_write，档案未变大但不是没写")
                notifyOutcome(SystemNoticeCode.MemoryReviewNone, null, sessionKey)
            }
            else -> {
                trace("整理完成：没有需要整理的内容（工具调用=${toolCalls.size}）")
                notifyOutcome(SystemNoticeCode.MemoryReviewNone, null, sessionKey)
            }
        }
    }

    /** 档案指纹：字节数与行数一起比，等长替换也能发现变化。 */
    private fun archiveSizes(): Map<String, Int> =
        io.github.mangi.eta.data.repository.AgentMemoryRepository.files()
            .associate { file -> file.name to file.byteSize * 100_000 + file.lineCount }

    /** 整理 run 的提示词：写入规则 + 现有记忆索引 + 有界检查点。 */
    private fun buildPrompt(
        score: Double,
        added: List<AgentModelClient.ConversationMessage>,
    ): String = buildString {
        appendLine("你是记忆整理子代理。只做一件事：判断这次对话有没有值得长期保留的内容；有就写进记忆，没有就什么都不做（直接回答「无需整理」）。")
        appendLine()
        appendLine("写入规则：")
        appendLine("- 档案是**项目记录的唯一去处**：设计、调查、流程、踩坑与排障、配置与路径、决策理由、交接材料。")
        appendLine("- 一轮只写一层：写档案 或 写条目，二选一，优先档案。")
        appendLine("- 档案太长就分卷：索引里每行带行数，目标档案已超过 120 行时不要再往里追加长内容，改为新建分档 memory_write archive=\"<主题名>·<段落名>\"（如「外星仔加速器看广告刷时长·10-17 流水」），再用 mode=edit 在主档里更新一行指针（`- <段落> → 《分档名》`）；主档只留当前有效的结论与规则，过程记录进分档。这两步同属档案层，一轮内都允许。")
        appendLine("- 写/追加档案：memory_write archive=<主题名> mode=append content=\"<正文>\"；先 memory_get 看有没有同名档案，别重复已有内容。")
        appendLine("- 修正已有档案里的**一处**内容：memory_write archive=<主题名> mode=edit old_text=\"<要替换的原文片段，必须唯一>\" content=\"<新内容>\"（比追加一条「更正」干净）。")
        appendLine("- 写条目：memory_write target=entry content=\"一句话结论\" importance=2；只收用户明确、持久的新结论，且必须是档案没覆盖的。")
        appendLine("- 什么都不做也是正常结果；不要改用户画像、不要覆盖整份工作记忆、不要删除任何东西。")
        appendLine("- 路径、端口、实现细节一律不进条目。")
        appendLine()
        val index = runCatching { io.github.mangi.eta.data.repository.AgentMemoryRepository.fileIndexText() }
            .getOrNull().orEmpty()
        appendLine("<现有档案>")
        appendLine(index.ifBlank { "（暂无档案）" })
        appendLine("</现有档案>")
        appendLine()
        val entries = runCatching {
            io.github.mangi.eta.data.repository.AgentMemoryRepository.entries().take(40).joinToString("\n") { entry ->
                "- (${entry.importance}) ${entry.text}"
            }
        }.getOrNull().orEmpty()
        appendLine("<现有条目>")
        appendLine(entries.ifBlank { "（暂无条目）" })
        appendLine("</现有条目>")
        appendLine()
        appendLine("<最近对话>")
        appendLine(renderCheckpoint(added))
        appendLine("</最近对话>")
    }.trimEnd()

    /** 有界检查点：只取每条消息前若干字符，总量受 [MAX_CONTEXT_CHARS] 限制。 */
    private fun renderCheckpoint(messages: List<AgentModelClient.ConversationMessage>): String {
        val builder = StringBuilder()
        messages.forEach { message ->
            val role = when (message.role) {
                ROLE_USER -> "用户"
                ROLE_ASSISTANT -> "助手"
                ROLE_TOOL -> "工具"
                else -> message.role
            }
            val body = message.content.replace('\n', ' ').trim().take(PER_MESSAGE_CHARS)
            if (body.isBlank()) return@forEach
            val line = "$role：$body"
            if (builder.length + line.length + 1 > MAX_CONTEXT_CHARS) {
                builder.append("\n（更早内容已截断）")
                return@forEach
            }
            builder.append(line).append('\n')
        }
        return builder.toString().trimEnd()
    }

    /** 会话键的短标签：只用来在日志里区分桶。 */
    private fun sessionTag(sessionKey: String): String =
        if (sessionKey == DEFAULT_SESSION_KEY) "-" else sessionKey.takeLast(6)

    /** dsh 原文的确定性活动评分。 */
    private fun score(signal: Signal): Double =
        (minOf(signal.userChars / 50, 3) + signal.turns + minOf(signal.toolResults / 5, 2) + diversity(signal)).toDouble()

    private fun diversity(signal: Signal): Int = when {
        signal.toolNames.size < 3 -> 0
        signal.toolNames.size == 3 -> 1
        else -> 2
    }

    private fun hasDurableCandidate(signal: Signal): Boolean =
        signal.userChars >= MIN_USER_CHARS ||
            signal.assistantChars >= MIN_ASSISTANT_CHARS ||
            (signal.toolResults >= 3 && signal.toolNames.any { it !in MEMORY_TOOLS })

    companion object {
        const val REVIEW_RUN_PREFIX = "memory-review:"

        /** 待注入聊天流的整理结论；UI 消费后调用 [acknowledgeOutcome] 移除。 */
        private val pendingOutcomes = MutableStateFlow<List<AgentMemoryReviewOutcome>>(emptyList())
        val outcomes: StateFlow<List<AgentMemoryReviewOutcome>> = pendingOutcomes.asStateFlow()

        fun publishOutcome(outcome: AgentMemoryReviewOutcome) {
            pendingOutcomes.value = pendingOutcomes.value + outcome
        }

        fun acknowledgeOutcome(outcome: AgentMemoryReviewOutcome) {
            pendingOutcomes.value = pendingOutcomes.value - outcome
        }

        private const val IDLE_DELAY_MS = 30_000L
        private const val MIN_INTERVAL_MS = 5 * 60 * 1000L
        private const val SCORE_THRESHOLD = 5.0
        private const val MIN_USER_CHARS = 320
        private const val MIN_ASSISTANT_CHARS = 600
        private const val MAX_CONTEXT_CHARS = 8_000
        private const val TRANSCRIPT_TAIL = 24
        private const val PER_MESSAGE_CHARS = 600

        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val ROLE_TOOL = "tool"

        /** 没有会话归属的 run 共用的桶。 */
        private const val DEFAULT_SESSION_KEY = "default-session"

        private val MEMORY_TOOLS = setOf("memory_get", "memory_write")
        private val NO_REMEMBER = Regex("不要记|别记|不用记|无需记|别存|不要存|只是问一下|仅测试")
    }
}
