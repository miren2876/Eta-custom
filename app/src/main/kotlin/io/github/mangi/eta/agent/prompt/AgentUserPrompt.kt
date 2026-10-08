package io.github.mangi.eta.agent.prompt

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** 单个选项；[id] 只用于回传，界面显示 [title] 与可选 [description]。 */
internal data class AgentUserPromptOption(
    val id: String,
    val title: String,
    val description: String?,
    val recommended: Boolean,
)

/**
 * 一道问题。[multi] 为真时允许选中多个选项；
 * [allowCustom] 为真时用户可以另外用文字补充（两者可以同时存在）。
 */
internal data class AgentUserPromptQuestion(
    val id: String,
    val label: String?,
    val question: String,
    val multi: Boolean,
    val options: List<AgentUserPromptOption>,
    val allowCustom: Boolean,
    val customPlaceholder: String?,
)

/** 一次待回答的提问；token 只在本进程内有效。 */
internal data class AgentUserPromptRequest(
    val token: String,
    val owner: String,
    val questions: List<AgentUserPromptQuestion>,
)

/** 用户对某一道题的回答；[skipped] 表示跳过（模型应按自己的判断继续）。 */
internal data class AgentUserPromptAnswer(
    val id: String,
    val selected: List<String>,
    val custom: String,
    val skipped: Boolean,
)

/**
 * 运行中向用户提问的挂起请求。
 *
 * 与虚拟屏回退授权同构：模型发起工具调用 → 会话线程阻塞等待 → 聊天页渲染选择控件 →
 * 用户提交或跳过 → 结果作为工具返回值交还给模型。
 */
internal object AgentUserPrompt {
    /** 用户可能离开很久；超时后按"跳过"处理，任务不会永久卡住。 */
    private const val TIMEOUT_MS = 30 * 60 * 1000L
    private const val POLL_INTERVAL_MS = 200L
    private const val MAX_QUESTIONS = 5
    private const val MAX_OPTIONS = 6
    private const val MAX_TITLE_CHARS = 80
    private const val MAX_DESCRIPTION_CHARS = 200
    private const val MAX_QUESTION_CHARS = 300
    private const val MAX_CUSTOM_CHARS = 600

    private data class Pending(
        val request: AgentUserPromptRequest,
        val answer: AtomicReference<List<AgentUserPromptAnswer>?>,
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val prompts = MutableStateFlow<List<AgentUserPromptRequest>>(emptyList())

    /** 聊天页要渲染的待回答请求，通常最多一条。 */
    val state = prompts.asStateFlow()

    fun current(): AgentUserPromptRequest? = prompts.value.firstOrNull()

    /**
     * 发起一次提问并阻塞等待用户操作；返回值是交给模型的工具结果 JSON。
     * 参数非法、被取消或超时时也返回 JSON（ok=false 或 skipped=true），模型据此继续。
     */
    fun request(
        context: Context,
        owner: String,
        args: JSONObject,
        isCancelled: () -> Boolean,
    ): String {
        val questions = parseQuestions(args)
            ?: return errorResult("INVALID_QUESTIONS", "questions 至少需要一道带 2 个以上选项的题目")
        val token = UUID.randomUUID().toString()
        val request = AgentUserPromptRequest(token, owner, questions)
        val entry = Pending(request, AtomicReference(null))
        pending[token] = entry
        prompts.update { it + request }
        io.github.mangi.eta.agent.runtime.AgentExecutionService.refreshRunNotification()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        try {
            while (true) {
                entry.answer.get()?.let { answers -> return answersResult(request, answers, timedOut = false) }
                if (isCancelled()) return errorResult("PROMPT_CANCELLED", "任务已取消，提问未送达或未回答")
                if (android.os.SystemClock.elapsedRealtime() - startedAt >= TIMEOUT_MS) {
                    return answersResult(
                        request,
                        request.questions.map { AgentUserPromptAnswer(it.id, emptyList(), "", skipped = true) },
                        timedOut = true,
                    )
                }
                runCatching { Thread.sleep(POLL_INTERVAL_MS) }
                    .onFailure { Thread.currentThread().interrupt(); return errorResult("PROMPT_CANCELLED", "线程中断，提问未完成") }
            }
        } finally {
            pending.remove(token)
            prompts.update { list -> list.filterNot { it.token == token } }
            io.github.mangi.eta.agent.runtime.AgentExecutionService.refreshRunNotification()
        }
    }

    /** 用户提交或跳过：同一 token 只有第一次生效。 */
    fun resolve(token: String, answers: List<AgentUserPromptAnswer>) {
        pending[token]?.answer?.compareAndSet(null, answers)
    }

    /**
     * 通知/流体云动作直接作答：只回答被点击的题目，其余按跳过处理。
     * 返回 false 表示这条提问已经失效（被取消或已作答）。
     */
    fun answerFromNotification(token: String, questionId: String, optionId: String): Boolean {
        val request = prompts.value.firstOrNull { it.token == token } ?: return false
        val answers = request.questions.map { question ->
            if (question.id == questionId) {
                AgentUserPromptAnswer(question.id, listOf(optionId), "", skipped = false)
            } else {
                AgentUserPromptAnswer(question.id, emptyList(), "", skipped = true)
            }
        }
        resolve(token, answers)
        return true
    }

    fun cancelOwner(owner: String) {
        prompts.value.filter { it.owner == owner }.forEach { request ->
            resolve(request.token, skippedAnswers(request))
        }
    }

    fun cancelAll() {
        prompts.value.forEach { request -> resolve(request.token, skippedAnswers(request)) }
    }

    private fun skippedAnswers(request: AgentUserPromptRequest): List<AgentUserPromptAnswer> =
        request.questions.map { AgentUserPromptAnswer(it.id, emptyList(), "", skipped = true) }

    private fun parseQuestions(args: JSONObject): List<AgentUserPromptQuestion>? {
        val array = args.optJSONArray("questions") ?: return null
        if (array.length() == 0) return null
        val questions = mutableListOf<AgentUserPromptQuestion>()
        for (index in 0 until minOf(array.length(), MAX_QUESTIONS)) {
            val raw = array.optJSONObject(index) ?: continue
            val question = raw.optString("question").trim().take(MAX_QUESTION_CHARS)
            if (question.isBlank()) continue
            val options = mutableListOf<AgentUserPromptOption>()
            val optionArray = raw.optJSONArray("options") ?: continue
            for (optionIndex in 0 until minOf(optionArray.length(), MAX_OPTIONS)) {
                val rawOption = optionArray.optJSONObject(optionIndex) ?: continue
                val title = rawOption.optString("title").trim().take(MAX_TITLE_CHARS)
                if (title.isBlank()) continue
                options += AgentUserPromptOption(
                    id = rawOption.optString("id").trim().takeIf { it.isNotBlank() } ?: (optionIndex + 1).toString(),
                    title = title,
                    description = rawOption.optString("description").trim().take(MAX_DESCRIPTION_CHARS).takeIf { it.isNotBlank() },
                    recommended = rawOption.optBoolean("recommended", false),
                )
            }
            if (options.isEmpty()) continue
            questions += AgentUserPromptQuestion(
                id = raw.optString("id").trim().takeIf { it.isNotBlank() } ?: "q${index + 1}",
                label = raw.optString("label").trim().take(40).takeIf { it.isNotBlank() },
                question = question,
                multi = raw.optBoolean("multi", false),
                options = options,
                allowCustom = raw.optBoolean("allowCustom", true),
                customPlaceholder = raw.optString("customPlaceholder").trim().take(60).takeIf { it.isNotBlank() },
            )
        }
        return questions.takeIf { it.isNotEmpty() }
    }

    /** 把回答整理成模型可读的结果；同时给出选项标题，模型不必自己映射 id。 */
    private fun answersResult(
        request: AgentUserPromptRequest,
        answers: List<AgentUserPromptAnswer>,
        timedOut: Boolean,
    ): String {
        val output = org.json.JSONArray()
        val summary = StringBuilder()
        request.questions.forEach { question ->
            val answer = answers.firstOrNull { it.id == question.id }
            val selected = answer?.selected.orEmpty().filter { id -> question.options.any { it.id == id } }
            val titles = selected.mapNotNull { id -> question.options.firstOrNull { it.id == id }?.title }
            val custom = answer?.custom.orEmpty().trim().take(MAX_CUSTOM_CHARS)
            val skipped = answer?.skipped != false && selected.isEmpty() && custom.isBlank()
            output.put(
                JSONObject()
                    .put("id", question.id)
                    .put("question", question.question)
                    .put("selected", org.json.JSONArray(selected))
                    .put("selected_titles", org.json.JSONArray(titles))
                    .put("custom", custom)
                    .put("skipped", skipped),
            )
            summary.append(question.question).append(" → ")
            when {
                skipped -> summary.append("跳过（未回答，按你自己的判断继续）")
                titles.isNotEmpty() && custom.isNotBlank() -> summary.append(titles.joinToString("、")).append("；补充：").append(custom)
                titles.isNotEmpty() -> summary.append(titles.joinToString("、"))
                else -> summary.append(custom)
            }
            summary.append('\n')
        }
        return JSONObject()
            .put("ok", true)
            .put("timed_out", timedOut)
            .put("answers", output)
            .put(
                "summary",
                if (timedOut) "用户长时间未回答，已按跳过处理；请按你的判断继续并在回复里说明你的选择。\n$summary"
                else "用户已回答：\n$summary",
            )
            .toString()
    }

    private fun errorResult(code: String, message: String): String = runCatching {
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()
    }.getOrElse { """{"ok":false,"code":"$code"}""" }

    /** 供 UI 侧记录异常，不参与控制流。 */
    fun warnFailure(throwable: Throwable) {
        AndroidAgentLogger.warn("Agent user prompt failed: type=${throwable.safeLogType()}")
    }

    /**
     * 预览入口：设置页可以手动弹一次示例卡片，用来检查渲染与交互。
     * 不经过模型，回答只写日志，不改变任何任务状态。
     */
    fun previewDemo(context: Context) {
        if (current() != null) return
        val app = context.applicationContext
        Thread {
            val args = runCatching { JSONObject(DEMO_PAYLOAD) }.getOrNull() ?: return@Thread
            val result = request(app, "preview", args) { false }
            AndroidAgentLogger.info("Agent user prompt preview answered: ${result.take(300)}")
        }.apply { name = "eta-ask-user-preview"; isDaemon = true }.start()
    }

    private val DEMO_PAYLOAD = """
        {"questions":[
          {"label":"选项框试试","question":"这是一次弹窗选择测试 —— 你想让我接着干哪件事？","multi":false,"allowCustom":true,
           "customPlaceholder":"输入你的答案（也可以写别的需求）",
           "options":[
             {"title":"什么都不做，只看 UI","description":"我只把卡片渲染出来，不改任何代码","recommended":true},
             {"title":"顺手把卡片样式再调细一点","description":"圆角、间距、字号按你的反馈微调"},
             {"title":"继续测虚拟屏剩下的两项","description":"主屏回退授权、熄屏执行、空闲回收"}]},
          {"label":"第二个问题","question":"卡片里这几项，你想保留哪些？","multi":true,"allowCustom":true,
           "customPlaceholder":"补充说明",
           "options":[
             {"title":"推荐角标","description":"标在推荐的选项后面"},
             {"title":"自定义输入框","description":"可以自己写答案"},
             {"title":"跳过按钮","description":"不想回答就跳过本题"},
             {"title":"翻页 1/N","description":"多题时用 ‹ › 切换"}]}]}
    """.trimIndent()
}
