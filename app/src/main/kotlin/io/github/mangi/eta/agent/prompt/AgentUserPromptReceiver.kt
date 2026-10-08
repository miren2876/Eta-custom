package io.github.mangi.eta.agent.prompt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 流体云 / 通知展开卡片里的选项按钮：点一下即以该选项作答。
 *
 * 只回答被点击的那道题，其余题目按"跳过"处理——需要多选或自定义回答时，
 * 点通知正文回到聊天页用完整卡片作答。
 */
internal class AgentUserPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val questionId = intent.getStringExtra(EXTRA_QUESTION) ?: return
        val optionId = intent.getStringExtra(EXTRA_OPTION) ?: return
        AgentUserPrompt.answerFromNotification(token, questionId, optionId)
    }

    companion object {
        const val ACTION_ANSWER = "io.github.mangi.eta.action.ANSWER_USER_PROMPT"
        const val EXTRA_TOKEN = "prompt_token"
        const val EXTRA_QUESTION = "prompt_question"
        const val EXTRA_OPTION = "prompt_option"
    }
}
