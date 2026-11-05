package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 运行中向用户提问的工具：一次可以带多道题，支持多选与自定义回答。 */
internal object AgentUserPromptToolCatalog {
    fun appendTo(tools: JSONArray) {
        val option = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put("id", JSONObject().put("type", "string").put("maxLength", 20))
                    .put("title", JSONObject().put("type", "string").put("maxLength", 80))
                    .put("description", JSONObject().put("type", "string").put("maxLength", 200))
                    .put("recommended", JSONObject().put("type", "boolean")),
            )
            .put("required", JSONArray().put("title"))
        val question = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put("id", JSONObject().put("type", "string").put("maxLength", 20))
                    .put("label", JSONObject().put("type", "string").put("maxLength", 40))
                    .put("question", JSONObject().put("type", "string").put("maxLength", 300))
                    .put("multi", JSONObject().put("type", "boolean"))
                    .put("allowCustom", JSONObject().put("type", "boolean"))
                    .put("customPlaceholder", JSONObject().put("type", "string").put("maxLength", 60))
                    .put(
                        "options",
                        JSONObject().put("type", "array").put("minItems", 2).put("maxItems", 6).put("items", option),
                    ),
            )
            .put("required", JSONArray().put("question").put("options"))
        tools.put(
            AgentToolSchema.function(
                "ask_user",
                "Run pauses and asks the user to choose in the app. Use it only when the request is genuinely " +
                    "ambiguous or needs a decision you cannot make yourself; never for facts you can verify with other " +
                    "tools, and never for confirmation of an obvious next step. Provide 1-5 questions, each with 2-6 " +
                    "concrete options (put your recommendation first and set recommended=true), set multi=true when " +
                    "several options may hold at once, and keep allowCustom=true so the user can answer in their own " +
                    "words. The user may skip any question; when a question is skipped, continue with your own judgment " +
                    "and say what you assumed. The result returns the selected option ids, their titles, any custom " +
                    "text, and a per-question skipped flag.",
                JSONObject()
                    .put("type", "object")
                    .put("additionalProperties", false)
                    .put(
                        "properties",
                        JSONObject().put(
                            "questions",
                            JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 5).put("items", question),
                        ),
                    )
                    .put("required", JSONArray().put("questions")),
            ),
        )
    }
}
