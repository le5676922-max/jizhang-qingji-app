package com.example.jizhang.agent.deepseek

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.jizhang.agent.deepseek.DeepSeekProtocolInstrumentedTest.Companion.expectFailure
import com.example.jizhang.agent.deepseek.DeepSeekProtocolInstrumentedTest.Companion.messages
import com.example.jizhang.agent.deepseek.DeepSeekProtocolInstrumentedTest.Companion.response
import com.example.jizhang.agent.deepseek.DeepSeekProtocolInstrumentedTest.Companion.tools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Every connection is an in-memory fake. No test sends a request to DeepSeek. */
@RunWith(AndroidJUnit4::class)
class DeepSeekHttpTransportInstrumentedTest {
    @Test fun fixedEndpointHeadersAndTimeoutsAreAppliedWithoutRetries() = runBlocking {
        val client = FakeConnection()
        val count = AtomicInteger()
        val completion = DeepSeekHttpTransport { count.incrementAndGet(); client }
            .complete(KEY, DeepSeekModels.DEFAULT, messages(), tools())
        assertEquals("回答", completion.content)
        assertEquals(1, count.get())
        assertEquals("POST", client.requestMethod)
        assertEquals("Bearer $KEY", client.getRequestProperty("Authorization"))
        assertEquals("application/json", client.getRequestProperty("Accept"))
        assertFalse(client.instanceFollowRedirects)
        assertFalse(client.useCaches)
        assertEquals(10_000, client.connectTimeout)
        assertEquals(30_000, client.readTimeout)
        val request = JSONObject(client.output.toString("UTF-8"))
        assertEquals("本月支出", request.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(request.toString().contains(KEY))
        assertTrue(client.disconnected.get())
    }

    @Test fun httpFailuresAreSanitizedAndDoNotReadErrorBodyOrRetry() = runBlocking {
        listOf(401, 402, 429, 500, 503).forEach { status ->
            val client = FakeConnection(status = status)
            val count = AtomicInteger()
            val failure = safeFailure {
                DeepSeekHttpTransport { count.incrementAndGet(); client }
                    .complete(KEY, DeepSeekModels.DEFAULT, messages(), tools())
            }
            assertFalse(failure.message.orEmpty().contains(KEY))
            assertNull(failure.cause)
            assertFalse(client.errorBodyRead.get())
            assertEquals(1, count.get())
            assertTrue(client.disconnected.get())
        }
    }

    @Test fun redirectsAreRejectedWithoutFollowingTheirLocation() = runBlocking {
        val client = FakeConnection(status = 307)
        val failure = safeFailure {
            DeepSeekHttpTransport { client }.complete(KEY, DeepSeekModels.DEFAULT, messages(), tools())
        }
        assertTrue(failure.message.orEmpty().contains("重定向"))
        assertFalse(client.instanceFollowRedirects)
        assertFalse(client.responseBodyRead.get())
        assertTrue(client.disconnected.get())
    }

    @Test fun refusesUntrustedConnectionBeforeAttachingBearer() = runBlocking {
        val client = FakeConnection(url = URL("https://example.invalid/chat/completions"))
        safeFailure { DeepSeekHttpTransport { client }.complete(KEY, DeepSeekModels.DEFAULT, messages(), tools()) }
        assertNull(client.getRequestProperty("Authorization"))
        assertEquals(0, client.output.size())
        assertTrue(client.disconnected.get())
    }

    @Test fun rejectsOversizedAndUnexpectedContentTypes() = runBlocking {
        val clients = listOf(
            FakeConnection(declaredLength = DeepSeekProtocol.MAX_RESPONSE_BYTES.toLong() + 1),
            FakeConnection(body = ByteArray(DeepSeekProtocol.MAX_RESPONSE_BYTES + 1) { ' '.code.toByte() }),
            FakeConnection(type = "text/event-stream"),
        )
        clients.forEach { client ->
            safeFailure { DeepSeekHttpTransport { client }.complete(KEY, DeepSeekModels.DEFAULT, messages(), tools()) }
            assertTrue(client.disconnected.get())
        }
    }

    @Test fun unknownToolIsRejectedBeforeItCanReachTheAgent() = runBlocking {
        val call = DeepSeekProtocolInstrumentedTest.call().apply {
            getJSONObject("function").put("name", "delete_all_entries")
        }
        val body = response("tool_calls", JSONObject.NULL, JSONArray().put(call)).toString().toByteArray()
        val client = FakeConnection(body = body)
        safeFailure { DeepSeekHttpTransport { client }.complete(KEY, DeepSeekModels.DEFAULT, messages(), tools()) }
        assertTrue(client.disconnected.get())
    }

    @Test fun cancellationDisconnectsBlockedReadAndCannotDeliverLateResult() = runBlocking {
        val client = FakeConnection(blockRead = true)
        val delivered = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            DeepSeekHttpTransport { client }.complete(KEY, DeepSeekModels.DEFAULT, messages(), tools())
            delivered.set(true)
        }
        assertTrue(client.readStarted.await(3, TimeUnit.SECONDS))
        withTimeout(3_000) { job.cancelAndJoin() }
        assertTrue(client.disconnectStarted.await(3, TimeUnit.SECONDS))
        assertFalse(delivered.get())
        assertTrue(client.readReleased.await(3, TimeUnit.SECONDS))
    }

    @Test fun blockingDisconnectNeverRunsOnCancellingThreadOrBlocksCancellationCallback() = runBlocking {
        val client = FakeConnection(blockRead = true, blockDisconnect = true)
        val delivered = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            DeepSeekHttpTransport { client }.complete(KEY, DeepSeekModels.DEFAULT, messages(), tools())
            delivered.set(true)
        }
        val cancellationReturned = CountDownLatch(1)
        val cancellingThread = Thread({
            job.cancel()
            cancellationReturned.countDown()
        }, "synthetic-cancellation-initiator")
        try {
            assertTrue(client.readStarted.await(3, TimeUnit.SECONDS))
            cancellingThread.start()
            assertTrue(client.disconnectStarted.await(3, TimeUnit.SECONDS))
            // disconnect is still held by the test driver at this point.
            assertTrue("Cancellation callback must return before disconnect is released",
                cancellationReturned.await(2, TimeUnit.SECONDS))
            assertNotSame(cancellingThread, client.disconnectThread.get())
            assertFalse(client.disconnectFinished.get())
            withTimeout(3_000) { job.cancelAndJoin() }
            assertFalse(delivered.get())
        } finally {
            client.allowDisconnect.countDown()
            job.cancelAndJoin()
            cancellingThread.join(3_000)
        }
        assertTrue(client.disconnectEnded.await(3, TimeUnit.SECONDS))
    }

    @Test fun networkExceptionNeverExposesProviderBodyOrKey() = runBlocking {
        val failure = safeFailure {
            DeepSeekHttpTransport { throw IOException("$KEY private server body") }
                .complete(KEY, DeepSeekModels.DEFAULT, messages(), tools())
        }
        assertFalse(failure.message.orEmpty().contains(KEY))
        assertFalse(failure.message.orEmpty().contains("private server body"))
        assertNull(failure.cause)
    }

    @Test fun ambientCookieHandlerIsRefusedBeforeOpeningConnection() = runBlocking {
        val before = CookieHandler.getDefault()
        val opened = AtomicBoolean(false)
        try {
            CookieHandler.setDefault(CookieManager())
            safeFailure {
                DeepSeekHttpTransport { opened.set(true); FakeConnection() }
                    .complete(KEY, DeepSeekModels.DEFAULT, messages(), tools())
            }
            assertFalse(opened.get())
        } finally {
            CookieHandler.setDefault(before)
        }
    }

    private suspend fun safeFailure(block: suspend () -> Unit): DeepSeekException {
        try { withTimeout(3_000) { block() } } catch (failure: DeepSeekException) {
            assertNull(failure.cause)
            return failure
        }
        throw AssertionError("Expected safe provider failure")
    }

    private class FakeConnection(
        url: URL = URL(DeepSeekHttpTransport.ENDPOINT),
        private val body: ByteArray = response().toString().toByteArray(),
        private val status: Int = 200,
        private val type: String = "application/json; charset=utf-8",
        private val declaredLength: Long = -1,
        private val blockRead: Boolean = false,
        private val blockDisconnect: Boolean = false,
    ) : HttpsURLConnection(url) {
        val output = ByteArrayOutputStream()
        val disconnected = AtomicBoolean(false)
        val disconnectFinished = AtomicBoolean(false)
        val disconnectThread = AtomicReference<Thread?>()
        val disconnectStarted = CountDownLatch(1)
        val disconnectEnded = CountDownLatch(1)
        val allowDisconnect = CountDownLatch(1)
        val errorBodyRead = AtomicBoolean(false)
        val responseBodyRead = AtomicBoolean(false)
        val readStarted = CountDownLatch(1)
        val readReleased = CountDownLatch(1)
        private val unblock = CountDownLatch(1)

        override fun connect() = Unit
        override fun disconnect() {
            disconnectThread.set(Thread.currentThread())
            disconnected.set(true)
            disconnectStarted.countDown()
            if (blockDisconnect) {
                var interrupted = false
                while (true) {
                    try { allowDisconnect.await(); break } catch (_: InterruptedException) { interrupted = true }
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
            unblock.countDown()
            disconnectFinished.set(true)
            disconnectEnded.countDown()
        }
        override fun usingProxy() = false
        override fun getCipherSuite() = "TLS_FAKE_FOR_TEST_ONLY"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream(): OutputStream = output
        override fun getResponseCode() = status
        override fun getContentType() = type
        override fun getContentLengthLong() = declaredLength
        override fun getErrorStream(): InputStream = object : InputStream() {
            override fun read(): Int { errorBodyRead.set(true); throw IOException("$KEY private body") }
        }
        override fun getInputStream(): InputStream {
            responseBodyRead.set(true)
            if (!blockRead) return ByteArrayInputStream(body)
            return object : InputStream() {
                override fun read(): Int {
                    readStarted.countDown()
                    try { unblock.await(10, TimeUnit.SECONDS) } catch (_: InterruptedException) { /* cancellation */ }
                    readReleased.countDown()
                    return -1
                }
            }
        }
    }

    companion object { private const val KEY = "synthetic-provider-key-never-sent" }
}
