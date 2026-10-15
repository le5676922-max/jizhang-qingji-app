package com.example.jizhang.agent.deepseek

/** Provider identifiers verified against the official API documentation. */
object DeepSeekModels {
    const val DEFAULT = "deepseek-flash"
    val SUPPORTED: List<String> = listOf(DEFAULT, "deepseek-v4-pro")

    internal fun requireSupported(model: String) {
        if (model !in SUPPORTED) throw DeepSeekException("请选择受支持的 DeepSeek 模型。")
    }
}

/** Only fixed, user-facing messages belong here; never attach a provider body or a cause. */
class DeepSeekException(message: String) : Exception(message)

data class DeepSeekConfiguration(val enabled: Boolean, val model: String, val hasKey: Boolean)

data class DeepSeekToolCall(val id: String, val name: String, val arguments: String)

data class DeepSeekCompletion(
    val content: String?,
    val toolCalls: List<DeepSeekToolCall>,
    val finishReason: String,
)
