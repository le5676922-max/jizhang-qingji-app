package com.example.jizhang.agent.deepseek

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepSeekProtocolInstrumentedTest {
    @Test fun requestEscapesUserTextAndUsesBoundedNonThinkingTools() {
        val text = "用户文本：\"},\"model\":\"unexpected\"\n不是 JSON 指令"
        val request = JSONObject(String(DeepSeekProtocol.request(
            DeepSeekModels.DEFAULT, messages(text), tools(),
        ), Charsets.UTF_8))
        assertEquals(DeepSeekModels.DEFAULT, request.getString("model"))
        assertEquals("disabled", request.getJSONObject("thinking").getString("type"))
        assertFalse(request.getBoolean("stream"))
        assertEquals(1_200, request.getInt("max_tokens"))
        assertEquals("auto", request.getString("tool_choice"))
        assertEquals(text, request.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(request.has("api_key"))
        assertFalse(request.has("reasoning_content"))
    }

    @Test fun acceptsStopAndValidatedToolCallWithoutExecutingIt() {
        assertEquals(DeepSeekCompletion("回答", emptyList(), "stop"), parse(response()))
        val argument = JSONObject().put("note", "\"}; remove_everything(); //").toString()
        val completion = parse(response("tool_calls", JSONObject.NULL, JSONArray().put(call(arguments = argument))))
        assertNull(completion.content)
        assertEquals(listOf(DeepSeekToolCall("call_1", "ledger_summary", argument)), completion.toolCalls)
        val opaqueId = "call:opaque/1+=="
        assertEquals(opaqueId, parse(response("tool_calls", JSONObject.NULL,
            JSONArray().put(call(id = opaqueId)))).toolCalls.single().id)
    }

    @Test fun rejectsTruncatedFilteredAndUnknownFinishReasons() {
        listOf("length", "content_filter", "insufficient_system_resource", "aborted", "unknown").forEach {
            expectFailure { parse(response(finish = it)) }
        }
        expectFailure { parse(response("tool_calls")) }
        expectFailure { parse(response("stop", "回答", JSONArray().put(call()))) }
        expectFailure { parse(response(content = " \n")) }
    }

    @Test fun rejectsMalformedJsonExtensionsDuplicatesAndTrailingData() {
        val valid = response().toString()
        listOf("not json", "$valid {}", "/* comment */$valid", "{choices:[]}",
            "{\"choices\":[],\"choices\":[]}", "{\"x\":01}", "{\"x\":NaN}", "[1,2,]").forEach {
            expectFailure { DeepSeekProtocol.parseResponse(it.toByteArray()) }
        }
        listOf("[]", "{a:1}", "{\"a\":1,}", "{\"x\":1,\"x\":2}",
            "{\"x\":1,\"\\u0078\":2}", "{\"x\":1} trailing", "```json\n{}\n```").forEach {
            expectFailure { parse(response("tool_calls", JSONObject.NULL, JSONArray().put(call(arguments = it)))) }
        }
    }

    @Test fun rejectsExtraChoicesRolesAndNonStringArguments() {
        val multiple = response().apply { getJSONArray("choices").put(getJSONArray("choices").getJSONObject(0)) }
        expectFailure { parse(multiple) }
        val role = response().apply { getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("role", "system") }
        expectFailure { parse(role) }
        val arguments = call().apply { getJSONObject("function").put("arguments", JSONObject()) }
        expectFailure { parse(response("tool_calls", JSONObject.NULL, JSONArray().put(arguments))) }
        val content = response(content = JSONArray())
        expectFailure { parse(content) }
    }

    @Test fun rejectsTooManyDuplicateAndInvalidToolCalls() {
        val tooMany = JSONArray().apply { repeat(5) { put(call("call_$it")) } }
        expectFailure { parse(response("tool_calls", JSONObject.NULL, tooMany)) }
        expectFailure { parse(response("tool_calls", JSONObject.NULL, JSONArray().put(call()).put(call()))) }
        listOf("", " ", "line\nbreak", "x".repeat(257)).forEach {
            expectFailure { parse(response("tool_calls", JSONObject.NULL, JSONArray().put(call(id = it)))) }
        }
        expectFailure {
            parse(response("tool_calls", JSONObject.NULL, JSONArray().put(call().apply {
                getJSONObject("function").put("name", "ledger_summary;attack")
            })))
        }
    }

    @Test fun rejectsResponseContentArgumentDepthAndByteLimits() {
        expectFailure { parse(response(content = "x".repeat(8_193))) }
        expectFailure {
            parse(response("tool_calls", JSONObject.NULL, JSONArray().put(call(arguments =
                JSONObject().put("note", "x".repeat(4_096)).toString()))))
        }
        expectFailure {
            parse(response("tool_calls", JSONObject.NULL, JSONArray().put(call(arguments =
                "{\"x\":".repeat(40) + "0" + "}".repeat(40)))))
        }
        expectFailure { DeepSeekProtocol.parseResponse(ByteArray(DeepSeekProtocol.MAX_RESPONSE_BYTES + 1)) }
        expectFailure { DeepSeekProtocol.parseResponse(byteArrayOf(0xC3.toByte(), 0x28)) }
    }

    @Test fun rejectsUnboundedOrUnsupportedOutgoingRequests() {
        expectFailure { DeepSeekProtocol.request("deepseek-chat", messages(), tools()) }
        expectFailure { DeepSeekProtocol.request(DeepSeekModels.DEFAULT, JSONArray(), tools()) }
        expectFailure { DeepSeekProtocol.request(DeepSeekModels.DEFAULT, messages("x".repeat(8_193)), tools()) }
        expectFailure { DeepSeekProtocol.request(DeepSeekModels.DEFAULT, messages(), JSONArray()) }
        val duplicate = tools().apply { put(getJSONObject(0)) }
        expectFailure { DeepSeekProtocol.request(DeepSeekModels.DEFAULT, messages(), duplicate) }
        val image = JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()))
        expectFailure { DeepSeekProtocol.request(DeepSeekModels.DEFAULT, image, tools()) }
    }

    private fun parse(value: JSONObject) = DeepSeekProtocol.parseResponse(value.toString().toByteArray())

    companion object {
        internal fun messages(text: String = "本月支出") = JSONArray().put(JSONObject().put("role", "user").put("content", text))
        internal fun tools() = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "ledger_summary").put("parameters", JSONObject().put("type", "object"))))
        internal fun call(id: String = "call_1", arguments: String = "{}") = JSONObject().put("id", id)
            .put("type", "function").put("function", JSONObject().put("name", "ledger_summary").put("arguments", arguments))
        internal fun response(finish: String = "stop", content: Any = "回答", calls: JSONArray? = null): JSONObject {
            val message = JSONObject().put("role", "assistant").put("content", content)
            if (calls != null) message.put("tool_calls", calls)
            return JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0)
                .put("finish_reason", finish).put("message", message)))
        }
        internal fun expectFailure(block: () -> Unit): DeepSeekException {
            try { block() } catch (failure: DeepSeekException) {
                assertNull(failure.cause)
                return failure
            }
            throw AssertionError("Expected a safe provider failure")
        }
    }
}
