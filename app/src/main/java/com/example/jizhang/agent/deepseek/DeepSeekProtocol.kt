package com.example.jizhang.agent.deepseek

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Bounded protocol validation. A tool name is still subject to the agent's local allowlist. */
internal object DeepSeekProtocol {
    const val MAX_RESPONSE_BYTES = 128 * 1_024
    const val MAX_REQUEST_BYTES = 64 * 1_024
    const val MAX_CONTENT_CHARS = 8 * 1_024
    const val MAX_ARGUMENT_CHARS = 4 * 1_024
    const val MAX_TOOL_CALLS = 4
    private val identifier = Regex("[A-Za-z0-9_-]{1,128}")

    fun request(model: String, messages: JSONArray, tools: JSONArray): ByteArray {
        DeepSeekModels.requireSupported(model)
        if (messages.length() !in 1..32 || tools.length() !in 1..8) invalidRequest()
        val names = mutableSetOf<String>()
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index) ?: invalidRequest()
            val function = tool.optJSONObject("function") ?: invalidRequest()
            val name = function.opt("name") as? String ?: invalidRequest()
            if (tool.opt("type") != "function" || !identifier.matches(name) || !names.add(name)) {
                invalidRequest()
            }
        }
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: invalidRequest()
            val role = message.opt("role") as? String ?: invalidRequest()
            if (role !in setOf("system", "user", "assistant", "tool")) invalidRequest()
            val content = message.opt("content")
            if (content != null && content !== JSONObject.NULL &&
                (content !is String || content.length > MAX_CONTENT_CHARS)) invalidRequest()
            if ((role == "system" || role == "user" || role == "tool") && content !is String) {
                invalidRequest()
            }
        }
        val bytes = JSONObject()
            .put("model", model)
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("stream", false)
            .put("max_tokens", 1_200)
            .put("messages", messages)
            .put("tools", tools)
            .put("tool_choice", "auto")
            .toString().toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_REQUEST_BYTES) {
            bytes.fill(0)
            throw DeepSeekException("本次请求内容过多，请缩短问题或查询范围。")
        }
        return bytes
    }

    fun parseResponse(bytes: ByteArray): DeepSeekCompletion {
        if (bytes.isEmpty() || bytes.size > MAX_RESPONSE_BYTES) invalidResponse()
        try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val root = strictObject(decoder.decode(ByteBuffer.wrap(bytes)).toString())
            val choices = root.optJSONArray("choices") ?: invalidResponse()
            if (choices.length() != 1) invalidResponse()
            val choice = choices.optJSONObject(0) ?: invalidResponse()
            if (choice.opt("index") != 0) invalidResponse()
            val finishReason = choice.opt("finish_reason") as? String ?: invalidResponse()
            if (finishReason == "length") {
                throw DeepSeekException("DeepSeek 回复被截断，本次未执行操作，请缩小查询范围后重试。")
            }
            if (finishReason != "stop" && finishReason != "tool_calls") invalidResponse()
            val message = choice.optJSONObject("message") ?: invalidResponse()
            if (message.opt("role") != "assistant" || !message.has("content")) invalidResponse()
            val contentValue = message.opt("content")
            val content = when (contentValue) {
                JSONObject.NULL -> null
                is String -> contentValue.also {
                    if (it.length > MAX_CONTENT_CHARS) invalidResponse()
                }
                else -> invalidResponse()
            }
            val callsValue = message.opt("tool_calls")
            val calls = when (callsValue) {
                null, JSONObject.NULL -> emptyList()
                is JSONArray -> parseCalls(callsValue)
                else -> invalidResponse()
            }
            if (finishReason == "tool_calls" && calls.isEmpty()) invalidResponse()
            if (finishReason == "stop" && (calls.isNotEmpty() || content.isNullOrBlank())) invalidResponse()
            return DeepSeekCompletion(content, calls, finishReason)
        } catch (failure: DeepSeekException) {
            throw failure
        } catch (_: Exception) {
            invalidResponse()
        }
    }

    fun functionNames(tools: JSONArray): Set<String> = (0 until tools.length()).map {
        tools.getJSONObject(it).getJSONObject("function").getString("name")
    }.toSet()

    private fun parseCalls(values: JSONArray): List<DeepSeekToolCall> {
        if (values.length() > MAX_TOOL_CALLS) invalidResponse()
        val ids = mutableSetOf<String>()
        return (0 until values.length()).map { index ->
            val call = values.optJSONObject(index) ?: invalidResponse()
            val id = call.opt("id") as? String ?: invalidResponse()
            // The API constrains function names, but tool-call IDs are opaque strings. Echo the
            // exact bounded ID rather than incorrectly applying the function-name character set.
            if (call.opt("type") != "function" || id.length !in 1..256 || id.isBlank() ||
                id.any { it.isISOControl() } || !ids.add(id)) invalidResponse()
            val function = call.optJSONObject("function") ?: invalidResponse()
            val name = function.opt("name") as? String ?: invalidResponse()
            if (!identifier.matches(name)) invalidResponse()
            val arguments = function.opt("arguments") as? String ?: invalidResponse()
            if (arguments.length > MAX_ARGUMENT_CHARS) invalidResponse()
            strictObject(arguments)
            DeepSeekToolCall(id, name, arguments)
        }
    }

    /** Android's JSONObject parser accepts extensions; validate strict JSON before using it. */
    private fun strictObject(value: String): JSONObject {
        StrictJson(value).validate()
        val first = value.firstOrNull { !it.isJsonWhitespace() }
        if (first != '{') invalidResponse()
        return JSONObject(value)
    }

    private fun invalidRequest(): Nothing = throw DeepSeekException("智能助手请求格式不正确，请重试。")
    private fun invalidResponse(): Nothing =
        throw DeepSeekException("DeepSeek 返回了不完整或不受支持的回复，本次未执行操作。")

    private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\r' || this == '\n'

    private class StrictJson(private val text: String) {
        private var position = 0

        fun validate() {
            value(0)
            whitespace()
            if (position != text.length) invalidResponse()
        }

        private fun value(depth: Int) {
            if (depth > 32) invalidResponse()
            whitespace()
            when (peek()) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> invalidResponse()
            }
        }

        private fun objectValue(depth: Int) {
            position++
            whitespace()
            if (consume('}')) return
            val keys = mutableSetOf<String>()
            while (true) {
                whitespace()
                if (peek() != '"' || !keys.add(string())) invalidResponse()
                whitespace()
                expect(':')
                value(depth)
                whitespace()
                if (consume('}')) return
                expect(',')
            }
        }

        private fun arrayValue(depth: Int) {
            position++
            whitespace()
            if (consume(']')) return
            while (true) {
                value(depth)
                whitespace()
                if (consume(']')) return
                expect(',')
            }
        }

        private fun string(): String {
            expect('"')
            val decoded = StringBuilder()
            while (position < text.length) {
                val char = text[position++]
                when {
                    char == '"' -> return decoded.toString()
                    char.code < 0x20 -> invalidResponse()
                    char != '\\' -> decoded.append(char)
                    else -> {
                        if (position >= text.length) invalidResponse()
                        when (val escaped = text[position++]) {
                            '"', '\\', '/' -> decoded.append(escaped)
                            'b' -> decoded.append('\b')
                            'f' -> decoded.append('\u000C')
                            'n' -> decoded.append('\n')
                            'r' -> decoded.append('\r')
                            't' -> decoded.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) invalidResponse()
                                val hex = text.substring(position, position + 4)
                                if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
                                    invalidResponse()
                                }
                                decoded.append(hex.toInt(16).toChar())
                                position += 4
                            }
                            else -> invalidResponse()
                        }
                    }
                }
            }
            invalidResponse()
        }

        private fun number() {
            val start = position
            consume('-')
            if (consume('0')) {
                if (peek() in '0'..'9') invalidResponse()
            } else {
                if (peek() !in '1'..'9') invalidResponse()
                while (peek() in '0'..'9') position++
            }
            if (consume('.')) {
                if (peek() !in '0'..'9') invalidResponse()
                while (peek() in '0'..'9') position++
            }
            if (peek() == 'e' || peek() == 'E') {
                position++
                if (peek() == '+' || peek() == '-') position++
                if (peek() !in '0'..'9') invalidResponse()
                while (peek() in '0'..'9') position++
            }
            if (position - start > 128) invalidResponse()
        }

        private fun literal(value: String) {
            if (!text.regionMatches(position, value, 0, value.length)) invalidResponse()
            position += value.length
        }

        private fun whitespace() { while (peek().isJsonWhitespace()) position++ }
        private fun peek(): Char = text.getOrNull(position) ?: '\u0000'
        private fun expect(char: Char) { if (!consume(char)) invalidResponse() }
        private fun consume(char: Char): Boolean = if (peek() == char) {
            position++
            true
        } else false
    }
}
