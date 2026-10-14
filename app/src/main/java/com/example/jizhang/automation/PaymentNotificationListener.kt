package com.example.jizhang.automation

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.jizhang.data.LedgerRepository
import com.example.jizhang.domain.PaymentNotificationParser
import com.example.jizhang.recognition.CandidateFactory
import com.example.jizhang.recognition.EvidenceType
import com.example.jizhang.recognition.RecognitionDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Captures only fresh payment notifications after the user opts in. Never scans notification history. */
class PaymentNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<Snapshot>(capacity = 128)
    @Volatile private var connectedSince = Long.MAX_VALUE
    @Volatile private var connectionGeneration = 0L
    private lateinit var repository: LedgerRepository

    override fun onCreate() {
        super.onCreate()
        repository = LedgerRepository.get(applicationContext)
        scope.launch {
            for (snapshot in events) {
                try {
                    capture(snapshot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Do not log notification text or payment fields.
                    repository.reportCaptureError("部分付款通知未能保存，请检查存储空间、核对账本并手动补录。")
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedSince = System.currentTimeMillis()
        connectionGeneration++
        mutableConnectionState.value = true
    }

    override fun onListenerDisconnected() {
        connectedSince = Long.MAX_VALUE
        connectionGeneration++
        mutableConnectionState.value = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (sbn.packageName !in SUPPORTED_PACKAGES) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val settings = repository.settings.value
        if (!settings.autoEnabled ||
            (sbn.packageName == WECHAT_PACKAGE && !settings.wechatEnabled) ||
            (sbn.packageName == ALIPAY_PACKAGE && !settings.alipayEnabled)
        ) return
        if (sbn.postTime < maxOf(connectedSince, repository.automationEnabledSince())) return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if(title.length>512 || !PaymentNotificationParser.isServiceTitle(sbn.packageName,title)) return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { it.toString().trim() }?.filter { it.isNotBlank() }.orEmpty()
        // A multi-message inbox can contain several transactions, so do not guess one amount from it.
        if (lines.size > 1 && bigText.isBlank() && text.isBlank()) return
        val body = bigText.ifBlank { text.ifBlank { lines.singleOrNull().orEmpty() } }
        if (title.length > 512 || body.isBlank() || body.length > 8_192) return
        val snapshot = Snapshot(sbn.packageName, sbn.key, sbn.postTime, notification.`when`, title, body, connectionGeneration)
        if (!events.trySend(snapshot).isSuccess) {
            repository.reportCaptureError("通知过于频繁，部分通知未能处理，请核对并手动补录。")
        }
    }

    private suspend fun capture(snapshot: Snapshot) {
        val settings = repository.settings.value
        if (!settings.autoEnabled || snapshot.generation!=connectionGeneration ||
            snapshot.postTime < maxOf(connectedSince, repository.automationEnabledSince()) ||
            (snapshot.packageName == WECHAT_PACKAGE && !settings.wechatEnabled) ||
            (snapshot.packageName == ALIPAY_PACKAGE && !settings.alipayEnabled)
        ) return
        val started=System.nanoTime()
        val parsed = PaymentNotificationParser.parse(snapshot.packageName, snapshot.title, snapshot.text)
        if(parsed==null) {
            repository.reportRecognitionDiagnostic(EvidenceType.NOTIFICATION,"通知未满足已完成单笔交易规则",false)
            return
        }
        val identity = NotificationEventIdentity.create(
            snapshot.packageName, snapshot.key, snapshot.postTime, snapshot.notificationWhen, parsed,
        )
        val candidate=CandidateFactory.fromNotification(snapshot.packageName,snapshot.title,snapshot.text,
            identity.primary,listOfNotNull(identity.stableTransaction),snapshot.postTime) ?: return
        val saved=repository.ingest(candidate) { snapshot.generation==connectionGeneration && connectedSince!=Long.MAX_VALUE }
        if(!saved.disabled) repository.reportRecognitionDiagnostic(EvidenceType.NOTIFICATION,
            if(saved.decision==RecognitionDecision.IGNORE) "通知候选未通过账务验证" else "通知交易候选已通过解析",
            saved.decision!=RecognitionDecision.IGNORE,(System.nanoTime()-started)/1_000_000)
    }

    override fun onDestroy() {
        connectedSince = Long.MAX_VALUE
        connectionGeneration++
        mutableConnectionState.value = false
        events.close()
        scope.cancel()
        super.onDestroy()
    }

    private data class Snapshot(
        val packageName: String,
        val key: String,
        val postTime: Long,
        val notificationWhen: Long,
        val title: String,
        val text: String,
        val generation: Long,
    )

    companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        private val SUPPORTED_PACKAGES = setOf(WECHAT_PACKAGE, ALIPAY_PACKAGE)
        private val mutableConnectionState = MutableStateFlow(false)
        val connectionState: StateFlow<Boolean> = mutableConnectionState.asStateFlow()
    }
}
