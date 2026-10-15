package com.example.jizhang.agent.deepseek

import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.CookieHandler
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface DeepSeekTransport {
    suspend fun complete(
        apiKey: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray,
    ): DeepSeekCompletion
}

/**
 * One non-streaming call to the fixed official HTTPS endpoint. No redirects, SDK telemetry,
 * custom TLS trust, cookies, body logging or application-level retries are used.
 * The caller owns consent, tool execution, overall deadline and generation validation.
 */
class DeepSeekHttpTransport internal constructor(
    private val connectionFactory: () -> HttpsURLConnection,
) : DeepSeekTransport {
    constructor() : this({ URL(ENDPOINT).openConnection() as HttpsURLConnection })

    override suspend fun complete(
        apiKey: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray,
    ): DeepSeekCompletion {
        val bearer = DeepSeekSettingsStore.validatedKey(apiKey)
        // Freeze the caller-owned JSON before another coroutine can mutate a later round.
        val request = try {
            DeepSeekProtocol.request(model, messages, tools)
        } catch (failure: DeepSeekException) {
            throw failure
        } catch (_: Exception) {
            throw DeepSeekException("智能助手请求格式不正确，请重试。")
        }
        val allowedFunctions = DeepSeekProtocol.functionNames(tools)
        return suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<ConnectionLease?>()
            val pending = AtomicReference<Future<*>?>()
            val started = AtomicBoolean(false)
            continuation.invokeOnCancellation {
                // A platform HTTP implementation may block in disconnect(). Never run it on
                // the cancelling thread (often the Compose main thread).
                connection.getAndSet(null)?.let(::scheduleDisconnect)
                pending.get()?.let { future ->
                    future.cancel(true)
                    (future as? Runnable)?.let(EXECUTOR::remove)
                }
                // A queued call may never enter its worker's finally block.
                if (!started.get()) request.fill(0)
            }
            try {
                val future = EXECUTOR.submit {
                    started.set(true)
                    var response: ByteArray? = null
                    var completed: DeepSeekCompletion? = null
                    var failed: DeepSeekException? = null
                    var ownedConnection: ConnectionLease? = null
                    var unassignedPermit = false
                    try {
                        ensureActive(continuation.isActive)
                        // Do not share ambient cookie storage with an authenticated provider request.
                        if (CookieHandler.getDefault() != null) {
                            throw DeepSeekException("当前共享网络 Cookie 设置不适用于智能助手，请重启应用后重试。")
                        }
                        if (!CONNECTION_SLOTS.tryAcquire()) {
                            throw DeepSeekException("上一请求仍在结束，请稍后重试。")
                        }
                        unassignedPermit = true
                        val client = connectionFactory()
                        val lease = ConnectionLease(client)
                        ownedConnection = lease
                        unassignedPermit = false
                        connection.set(lease)
                        ensureActive(continuation.isActive)
                        if (client.url.toExternalForm() != ENDPOINT) {
                            throw DeepSeekException("DeepSeek 服务地址不受支持，已停止请求。")
                        }
                        client.apply {
                            instanceFollowRedirects = false
                            requestMethod = "POST"
                            connectTimeout = 10_000
                            readTimeout = 30_000
                            doInput = true
                            doOutput = true
                            useCaches = false
                            allowUserInteraction = false
                            setRequestProperty("Authorization", "Bearer $bearer")
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                            setRequestProperty("Accept", "application/json")
                            setFixedLengthStreamingMode(request.size)
                        }
                        client.outputStream.use { output ->
                            ensureActive(continuation.isActive)
                            output.write(request)
                        }
                        ensureActive(continuation.isActive)
                        val code = client.responseCode
                        if (code != HttpsURLConnection.HTTP_OK) httpFailure(code)
                        val type = client.contentType?.substringBefore(';')?.trim()
                        if (!type.equals("application/json", ignoreCase = true)) invalidResponse()
                        if (client.contentLengthLong > DeepSeekProtocol.MAX_RESPONSE_BYTES) invalidResponse()
                        response = client.inputStream.use { input ->
                            readBounded(input) { ensureActive(continuation.isActive) }
                        }
                        ensureActive(continuation.isActive)
                        completed = DeepSeekProtocol.parseResponse(response).also { completion ->
                            if (completion.toolCalls.any { it.name !in allowedFunctions }) invalidResponse()
                        }
                    } catch (_: CancellationException) {
                        // Cancellation owns delivery; a late network result never resumes the agent.
                    } catch (failure: Exception) {
                        failed = failure as? DeepSeekException
                            ?: DeepSeekException("无法连接 DeepSeek，未执行新的操作；请检查网络后手动重试。")
                    } finally {
                        response?.fill(0)
                        request.fill(0)
                        if (unassignedPermit) CONNECTION_SLOTS.release()
                        ownedConnection?.let { lease ->
                            connection.compareAndSet(lease, null)
                            // A worker always retains cleanup ownership as a fallback if the
                            // terminator's bounded queue cannot accept the cancellation task.
                            lease.disconnect()
                        }
                    }
                    // Release the socket and owned byte arrays before the caller can start a new round.
                    if (continuation.isActive) {
                        val result = completed
                        val failure = failed
                        if (result != null) continuation.resume(result)
                        else if (failure != null) continuation.resumeWithException(failure)
                    }
                }
                pending.set(future)
                if (!continuation.isActive) {
                    future.cancel(true)
                    (future as? Runnable)?.let(EXECUTOR::remove)
                    if (!started.get()) request.fill(0)
                }
            } catch (_: Exception) {
                request.fill(0)
                if (continuation.isActive) {
                    continuation.resumeWithException(DeepSeekException("智能助手正在结束上一请求，请稍后重试。"))
                }
            }
        }
    }

    private fun readBounded(input: InputStream, checkActive: () -> Unit): ByteArray {
        val buffer = ByteArray(4 * 1_024)
        val output = ByteArrayOutputStream()
        var zeros = 0
        try {
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    if (++zeros >= 3) invalidResponse()
                    continue
                }
                zeros = 0
                if (output.size() + count > DeepSeekProtocol.MAX_RESPONSE_BYTES) invalidResponse()
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
            // ByteArrayOutputStream owns a separate backing array, overwritten before release.
            val size = output.size()
            output.reset()
            var remaining = size
            while (remaining > 0) {
                val count = minOf(remaining, buffer.size)
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.close()
        }
    }

    private fun ensureActive(active: Boolean) {
        if (!active || Thread.currentThread().isInterrupted) throw CancellationException()
    }

    private fun scheduleDisconnect(connection: ConnectionLease) {
        try {
            TERMINATOR.execute { connection.disconnect() }
        } catch (_: Exception) {
            // Never use CallerRunsPolicy or fall back to disconnecting on the main thread.
            // The request worker retains this lease and closes it in its finally block.
        }
    }

    private class ConnectionLease(private val connection: HttpsURLConnection) {
        private val closing = AtomicBoolean(false)

        fun disconnect() {
            if (!closing.compareAndSet(false, true)) return
            try {
                connection.disconnect()
            } catch (_: Exception) {
                // Provider details are never retained in a cause or log.
            } finally {
                // A stuck platform disconnect holds its slot: new calls fail fast rather than
                // accumulating additional sockets or unbounded cleanup workers.
                CONNECTION_SLOTS.release()
            }
        }
    }

    private fun httpFailure(code: Int): Nothing = throw DeepSeekException(when (code) {
        401 -> "DeepSeek API Key 无效，请检查密钥设置。"
        402 -> "DeepSeek 账户余额不足，请检查服务账户。"
        429 -> "DeepSeek 请求过于频繁，请稍后手动重试。"
        in 300..399 -> "DeepSeek 返回了重定向，已停止请求。"
        in 500..599 -> "DeepSeek 服务暂时不可用，请稍后手动重试。"
        else -> "DeepSeek 未接受本次请求，请检查设置后重试。"
    })

    private fun invalidResponse(): Nothing =
        throw DeepSeekException("DeepSeek 返回了不完整或不受支持的回复，本次未执行操作。")

    companion object {
        const val ENDPOINT = "https://api.deepseek.com/chat/completions"
        private val CONNECTION_SLOTS = Semaphore(2)
        private val TERMINATOR = ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(2),
            { task -> Thread(task, "qingji-deepseek-close").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
        private val EXECUTOR = ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(2),
            { task -> Thread(task, "qingji-deepseek").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
