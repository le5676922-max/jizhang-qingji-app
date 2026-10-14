package com.example.jizhang.automation

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.jizhang.data.LedgerRepository
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.Money
import com.example.jizhang.domain.ScreenPayment
import com.example.jizhang.domain.ScreenPaymentParser
import com.example.jizhang.recognition.CandidateFactory
import com.example.jizhang.recognition.EvidenceType
import com.example.jizhang.recognition.RecognitionDecision
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class ScreenCaptureStatus(
    val message: String,
    val lastCheckedAt: Long = 0,
    val source: String? = null,
)

/** Read-only, opt-in receipt capture. Never clicks, sends gestures, edits fields, or opens payment apps. */
class ScreenCaptureService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<WindowEvent>(Channel.CONFLATED)
    private lateinit var repository: LedgerRepository
    // Main-thread accessibility callbacks replace the whole tuple. The IO collector must
    // never combine a previous Activity with a later package/window/session.
    @Volatile private var foregroundFrame = ForegroundFrame()
    private var lastOcrAt = -ScreenCapturePolicy.OCR_INTERVAL_MS
    private var lastSavedReceiptKey: String? = null
    private var lastSavedSession = -1L
    private var lastSavedEvidence: EvidenceType? = null
    private val retryLock = Any()
    private var ocrRetry: Job? = null
    private var pendingOcrRetry: WindowEvent? = null

    override fun onCreate() {
        super.onCreate()
        repository = LedgerRepository.get(applicationContext)
        scope.launch {
            repository.settings.map { it.screenEnabled }.distinctUntilChanged().collect { enabled ->
                if (!enabled) {
                    cancelOcrRetry()
                    publish("屏幕识别已关闭")
                }
                else publish(if (mutableConnectionState.value) "已连接，等待微信或支付宝的付款结果页" else "等待无障碍服务连接")
            }
        }
        scope.launch {
            for (initial in events) {
                // A conflated queue limits both allocation and work during animation/content storms.
                delay(ScreenCapturePolicy.READ_INTERVAL_MS)
                var latest = initial
                while (true) {
                    latest = events.tryReceive().getOrNull() ?: break
                }
                try {
                    inspect(latest)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Error messages contain neither a screen dump nor an OCR response.
                    publish("本次屏幕未能识别，请打开付款结果或账单详情页重试")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        invalidateForegroundFrame()
        // XML sets the same allowlist; keep it explicit even after a future settings change.
        serviceInfo = serviceInfo.apply { packageNames = ScreenCapturePolicy.SUPPORTED_PACKAGES.toTypedArray() }
        mutableConnectionState.value = true
        publish(if (repository.settings.value.screenEnabled) "已连接，等待微信或支付宝的付款结果页" else "屏幕识别已关闭")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType !in OBSERVED_EVENTS) return
        if (!mutableConnectionState.value) return
        if (!repository.settings.value.screenEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && event.isAccessibilityDataSensitive) {
            invalidateForegroundFrame(event.eventTime)
            cancelOcrRetry()
            publish("支付应用将此页面标记为敏感内容，已跳过识别")
            return
        }
        val packageName = event.packageName?.toString()
        if (packageName != null && packageName !in ScreenCapturePolicy.SUPPORTED_PACKAGES) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.eventTime >= foregroundFrame.lastStateEventTime) {
                invalidateForegroundFrame(event.eventTime)
            }
            return
        }
        var frame = foregroundFrame
        var eventPackage = packageName
        var eventWindowId = event.windowId
        // System window events can omit their package/window. Read only active-window
        // metadata, not text, to bind those events and first content-only observations.
        if (eventPackage == null || eventWindowId < 0) {
            val root = rootInActiveWindow ?: return
            try {
                val activePackage = root.packageName?.toString().orEmpty()
                if (activePackage !in ScreenCapturePolicy.SUPPORTED_PACKAGES ||
                    (eventPackage != null && eventPackage != activePackage)) return
                eventPackage = activePackage
                eventWindowId = root.windowId
            } finally {
                root.release()
            }
        }
        val observedPackage = eventPackage ?: return
        if (eventWindowId < 0) return
        val stateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && packageName != null
        if (stateChange) {
            // A delayed state event from an earlier window must not roll the frame back
            // after a newer Activity has already become the foreground observation.
            if (event.eventTime < frame.lastStateEventTime) return
            val newClass = event.className?.toString().orEmpty()
            val changed = observedPackage != frame.packageName || eventWindowId != frame.windowId ||
                newClass != frame.activityClass
            frame = ForegroundFrame(observedPackage, newClass, eventWindowId,
                frame.session + if (changed) 1 else 0, SystemClock.elapsedRealtime(), event.eventTime)
            foregroundFrame = frame
        } else if (observedPackage != frame.packageName || eventWindowId != frame.windowId) {
            // A content event's class is usually a widget, not an Activity. Adopt an
            // unknown class only if its window really is active; valid node receipts can
            // still be parsed, but empty unknown screens cannot authorize OCR.
            val root = rootInActiveWindow ?: return
            try {
                if (root.packageName?.toString() != observedPackage || root.windowId != eventWindowId) return
            } finally {
                root.release()
            }
            frame = ForegroundFrame(observedPackage, "", eventWindowId,
                frame.session + 1, SystemClock.elapsedRealtime(), frame.lastStateEventTime)
            foregroundFrame = frame
        }
        if (!sourceEnabled(observedPackage)) return
        // Event text can be a chat message. Page identity is probed from bounded heading
        // nodes instead; callbacks retain only package/window/Activity metadata.
        events.trySend(WindowEvent(
            frame = frame,
            recentWindowChange = SystemClock.elapsedRealtime() - frame.changedAt in 0..3_000,
            paymentHint = false,
        ))
    }

    private suspend fun inspect(event: WindowEvent) {
        val startedAt = SystemClock.elapsedRealtime()
        val frame = event.frame
        if (!coroutineContext.isActive || !repository.settings.value.screenEnabled ||
            !screenIsAvailable() || !isCurrentFrame(frame)) return
        // Activity metadata is available without inspecting the root's readable contents.
        // Reject known sensitive surfaces before any node text/contentDescription collection.
        if (ScreenCapturePolicy.isSensitiveContext("", frame.activityClass)) {
            cancelOcrRetry()
            lastSavedReceiptKey = null
            val source = if (frame.packageName == ScreenCapturePolicy.WECHAT_PACKAGE) "微信" else "支付宝"
            publish("当前是聊天、输入或验证页面，已跳过识别", source)
            return
        }
        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString().orEmpty()
        if (!sourceEnabled(packageName) || frame.packageName != packageName || frame.windowId != root.windowId ||
            !isCurrentFrame(frame)) {
            root.release()
            lastSavedReceiptKey = null
            return
        }
        val source = if (packageName == ScreenCapturePolicy.WECHAT_PACKAGE) "微信" else "支付宝"
        val identity = probeReceiptIdentity(root, frame)
        if (!identity.allowed || !isCurrentFrame(frame)) {
            root.release()
            lastSavedReceiptKey = null
            cancelOcrRetry()
            mutableMetrics.value = ScreenCaptureMetrics(visitedNodes = identity.visitedNodes,
                durationMs = SystemClock.elapsedRealtime() - startedAt)
            publish(if (identity.sensitive) "当前是聊天、输入、敏感或无关页面，已跳过识别"
                else "等待可确认身份的单笔交易结果或详情页", source)
            return
        }
        val snapshot = collectNodes(root, frame)
        mutableMetrics.value = snapshot.metrics
        if (!isCurrentFrame(frame)) return
        val currentActivity = frame.activityClass
        if (snapshot.hasSensitiveInput || ScreenCapturePolicy.isSensitiveContext(snapshot.text, currentActivity)) {
            lastSavedReceiptKey = null
            publish("当前是输入或验证页面，已跳过识别", source)
            return
        }
        if (!snapshot.complete) {
            publish("本页控件过多，已跳过自动识别，可手动导入支付截图", source)
            return
        }
        if (!ScreenCapturePolicy.hasPaymentContext(snapshot.text, currentActivity)) {
            lastSavedReceiptKey = null
            publish("等待付款结果、转账详情或红包详情页", source)
            return
        }
        val nodePayment = ScreenPaymentParser.parse(packageName, snapshot.text)
        if (nodePayment != null) {
            cancelOcrRetry()
            if (!coroutineContext.isActive || !isSameSafeWindow(frame)) return
            save(nodePayment, frame, source, EvidenceType.ACCESSIBILITY, startedAt)
            return
        }
        if (!repository.settings.value.screenOcrEnabled) {
            publish("本页文字不足，可开启本地截图识别或手动导入支付截图", source)
            return
        }
        if (!snapshot.complete || !ScreenCapturePolicy.mayUseOcr(
                packageName, snapshot.text, currentActivity, snapshot.hasSensitiveInput,
                event.recentWindowChange, event.paymentHint,
            )
        ) {
            publish("本页未满足截图识别条件，可手动导入支付截图", source)
            return
        }
        if (snapshot.hasRestrictedFields) {
            publish("本页包含非交易个人信息，已跳过自动截图，请手动补录", source)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            publish("此系统仅支持页面文字识别，请手动导入支付截图", source)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val waitForOcr = ScreenCapturePolicy.OCR_INTERVAL_MS - (now - lastOcrAt)
        if (waitForOcr > 0) {
            scheduleOcrRetry(event, waitForOcr)
            return
        }
        cancelOcrRetry()
        // Display screenshots on API 30–33 cannot isolate application windows. Reject a visible
        // keyboard or another application window and crop to the payment window after capture.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && windows.any {
                it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    (it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.id != snapshot.windowId)
            }
        ) {
            publish("当前存在其他窗口，已跳过自动截图，请手动导入支付截图", source)
            return
        }
        if (!isSameSafeWindow(frame, forOcr = true)) return
        lastOcrAt = now
        when (val attempt = takeBitmap(snapshot.windowId, snapshot.bounds)) {
            is ScreenshotAttempt.Failed -> {
                if (!coroutineContext.isActive || !isCurrentFrame(frame)) return
                publish(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                        attempt.errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                    ) "支付应用保护了此窗口，已停止截图；请手动补录或导入可用支付截图"
                    else "系统未允许本次截图，请手动导入支付截图", source,
                )
            }
            is ScreenshotAttempt.Captured -> {
                val bitmap = attempt.bitmap
                val text = try {
                    if (!coroutineContext.isActive || !repository.settings.value.screenOcrEnabled ||
                        !isSameSafeWindow(frame, forOcr = true)
                    ) return
                    LocalTextRecognizer.recognize(bitmap)
                } finally {
                    bitmap.recycle()
                }
                if (!coroutineContext.isActive || !repository.settings.value.screenOcrEnabled ||
                    !isSameSafeWindow(frame, forOcr = true)
                ) return
                val payment = ScreenPaymentParser.parse(packageName, text, fromOcr = true)
                if (payment == null) publish("未找到明确完成的单笔收支，请手动补录或导入支付截图", source)
                else save(payment, frame, source, EvidenceType.OCR, startedAt)
            }
        }
    }

    private suspend fun save(payment: ScreenPayment, frame: ForegroundFrame, source: String,
                             evidence: EvidenceType, startedAt: Long) {
        if (!coroutineContext.isActive || !sourceEnabled(frame.packageName) || !screenIsAvailable() ||
            !isCurrentFrame(frame)) return
        val receiptSession = frame.session
        // The repository persists strong identifiers. Weak candidates are suppressed only while
        // the same receipt stays open, so separate equal-value transfers can both be reviewed.
        if (lastSavedSession == receiptSession && lastSavedReceiptKey == payment.receiptKey &&
            lastSavedEvidence == evidence) return
        val amount = Money.format(payment.payment.amountCents)
        val direction = if (payment.payment.type == EntryType.EXPENSE) "支出" else "收入"
        val saved = repository.ingest(CandidateFactory.fromScreen(payment, System.currentTimeMillis(), evidence)) {
            isCurrentFrame(frame) && sourceEnabled(frame.packageName) &&
                (evidence != EvidenceType.OCR || repository.settings.value.screenOcrEnabled) && screenIsAvailable()
        }
        // An in-flight database transaction may finish after navigation. Do not attribute
        // its status or redraw-suppression key to the new foreground session.
        if (!coroutineContext.isActive || !isCurrentFrame(frame)) return
        if (saved.disabled) {
            publish("该来源的屏幕识别已关闭", source)
            return
        }
        lastSavedReceiptKey = payment.receiptKey
        lastSavedSession = receiptSession
        lastSavedEvidence = evidence
        val accepted = saved.decision != RecognitionDecision.IGNORE
        repository.reportRecognitionDiagnostic(evidence,
            if (accepted) "已识别交易候选" else "交易候选未通过验证", accepted,
            SystemClock.elapsedRealtime() - startedAt)
        publish(when {
            saved.duplicate -> "该 $direction ¥$amount 识别事件已处理，没有新增候选"
            saved.decision == RecognitionDecision.AUTO -> "已记录 $direction ¥$amount"
            saved.decision == RecognitionDecision.REVIEW -> "$direction ¥$amount 已加入待确认，确认前不计入账本"
            else -> "交易信息未通过验证，已忽略"
        }, source)
    }

    private fun sourceEnabled(packageName: String): Boolean {
        val settings = repository.settings.value
        if (!mutableConnectionState.value || !settings.screenEnabled) return false
        return when (packageName) {
            ScreenCapturePolicy.WECHAT_PACKAGE -> settings.wechatEnabled
            ScreenCapturePolicy.ALIPAY_PACKAGE -> settings.alipayEnabled
            else -> false
        }
    }

    private fun screenIsAvailable(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive == true &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    private fun isCurrentFrame(expected: ForegroundFrame): Boolean {
        val current = foregroundFrame
        return expected.windowId >= 0 && current.session == expected.session &&
            current.packageName == expected.packageName && current.windowId == expected.windowId &&
            current.activityClass == expected.activityClass
    }

    /** Main-thread lifecycle callbacks revoke all queued observations from the old session. */
    private fun invalidateForegroundFrame(stateEventTime: Long? = null) {
        val previous = foregroundFrame
        foregroundFrame = ForegroundFrame(session = previous.session + 1,
            changedAt = SystemClock.elapsedRealtime(),
            lastStateEventTime = maxOf(previous.lastStateEventTime, stateEventTime ?: previous.lastStateEventTime))
    }

    private fun isSameSafeWindow(frame: ForegroundFrame, forOcr: Boolean = false): Boolean {
        if (!isCurrentFrame(frame) || !sourceEnabled(frame.packageName) || !screenIsAvailable() ||
            ScreenCapturePolicy.isSensitiveContext("", frame.activityClass)) return false
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != frame.packageName || root.windowId != frame.windowId ||
            !isCurrentFrame(frame)) {
            root.release()
            return false
        }
        val identity = probeReceiptIdentity(root, frame)
        if (!identity.allowed || !isCurrentFrame(frame)) {
            root.release()
            return false
        }
        val current = collectNodes(root, frame)
        return isCurrentFrame(frame) && current.complete && !current.hasSensitiveInput &&
            (!forOcr || !current.hasRestrictedFields) &&
            !ScreenCapturePolicy.isSensitiveContext(current.text, frame.activityClass) &&
            ScreenCapturePolicy.hasPaymentContext(current.text, frame.activityClass)
    }

    private fun cancelOcrRetry() {
        val job = synchronized(retryLock) {
            pendingOcrRetry = null
            ocrRetry.also { ocrRetry = null }
        }
        job?.cancel()
    }

    private fun scheduleOcrRetry(event: WindowEvent, waitMillis: Long) {
        synchronized(retryLock) {
            pendingOcrRetry = event
            if (ocrRetry?.isActive == true) return
            ocrRetry = scope.launch {
                delay(waitMillis)
                // Atomically hand the latest pending frame back to the single reader.
                // Clearing the Job and candidate together lets a racing new receipt
                // schedule its own retry instead of being lost behind a finishing Job.
                val retry = synchronized(retryLock) {
                    pendingOcrRetry.also {
                        pendingOcrRetry = null
                        ocrRetry = null
                    }
                }
                if (retry != null && coroutineContext.isActive && isCurrentFrame(retry.frame) &&
                    sourceEnabled(retry.frame.packageName) && repository.settings.value.screenOcrEnabled &&
                    screenIsAvailable()) {
                    events.trySend(retry.copy(recentWindowChange = true))
                }
            }
        }
    }

    /** Small metadata/title probe. Ordinary chat/body nodes are not used as page identity. */
    private fun probeReceiptIdentity(root: AccessibilityNodeInfo, frame: ForegroundFrame): TitleProbe {
        val window = Rect().also(root::getBoundsInScreen).asEvidenceBounds()
        val knownActivity = ScreenCapturePolicy.isKnownReceiptActivity(frame.activityClass)
        val queue = ArrayDeque<QueuedNode>().apply { add(QueuedNode(root, 0)) }
        var visited = 0
        var matchedTitle = false
        var sensitive = false
        try {
            while (queue.isNotEmpty() && visited < ScreenCapturePolicy.TITLE_PROBE_NODES) {
                if (!isCurrentFrame(frame) || !sourceEnabled(frame.packageName)) return TitleProbe(false, false, visited)
                val item = queue.removeFirst()
                val node = item.node
                visited++
                try {
                    if (node.isVisibleToUser) {
                        if (node.isEditable || node.isPassword || nodeHasSensitiveData(node)) {
                            sensitive = true
                            break
                        }
                        val resource = node.viewIdResourceName.orEmpty()
                        val nodeBounds = Rect().also(node::getBoundsInScreen).asEvidenceBounds()
                        val headingMetadata = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && node.isHeading) ||
                            Regex("title|header|toolbar|action_bar", RegexOption.IGNORE_CASE).containsMatchIn(resource)
                        val leafTitle = node.childCount == 0 && node.className?.toString()?.endsWith("TextView") == true
                        if (!ReceiptNodeTextPolicy.excludesResource(resource) && nodeBounds.inTitleBand(window) &&
                            ScreenCapturePolicy.mayProbeHeading(knownActivity, headingMetadata, leafTitle)) {
                            fun inspectHeading(value: CharSequence?) {
                                if (value == null || value.length > ScreenCapturePolicy.TITLE_PROBE_CHARACTERS) return
                                val title = value.toString().trim()
                                if (ScreenCapturePolicy.isExcludedPageHeading(title)) sensitive = true
                                if (ScreenCapturePolicy.isIndependentReceiptTitle(title)) matchedTitle = true
                            }
                            inspectHeading(node.text)
                            inspectHeading(node.contentDescription)
                            if (sensitive) break
                        }
                    }
                    if (item.depth < ScreenCapturePolicy.TITLE_PROBE_DEPTH) {
                        val capacity = (ScreenCapturePolicy.TITLE_PROBE_NODES - visited - queue.size).coerceAtLeast(0)
                        for (index in 0 until minOf(node.childCount, capacity)) {
                            node.getChild(index)?.let { queue.addLast(QueuedNode(it, item.depth + 1)) }
                        }
                    }
                } finally {
                    if (node !== root) node.release()
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                val queued = queue.removeFirst().node
                if (queued !== root) queued.release()
            }
        }
        return TitleProbe(!sensitive && (matchedTitle || knownActivity),
            sensitive, visited)
    }

    /** Metadata is checked for the entire bounded tree before copying any receipt field. */
    private fun collectNodes(root: AccessibilityNodeInfo, frame: ForegroundFrame): NodeSnapshot {
        val startedAt = SystemClock.elapsedRealtime()
        val windowId = root.windowId
        val bounds = Rect().also(root::getBoundsInScreen)
        val queue = ArrayDeque<QueuedNode>().apply { add(QueuedNode(root, 0)) }
        val scanned = mutableListOf<QueuedNode>()
        val tokens = mutableListOf<ScreenNodeEvidence>()
        var retainedCharacters = 0
        var hasSensitiveInput = false
        var hasRestrictedFields = false
        var complete = true
        fun append(value: CharSequence?, nodeBounds: ScreenNodeBounds, depth: Int) {
            if (value == null) return
            if (value.length > 256) {
                hasRestrictedFields = true
                return
            }
            val part = value.toString().trim()
            if (part.isEmpty()) return
            if (ScreenCapturePolicy.isExcludedPageHeading(part)) {
                hasSensitiveInput = true
                return
            }
            val nearField = ReceiptNodeTextPolicy.neighboringValue(part, nodeBounds, tokens)
            val independent = ReceiptNodeTextPolicy.independentText(part)
            if (ReceiptNodeTextPolicy.excludesField(part) ||
                (ReceiptNodeTextPolicy.containsSensitiveNumber(part) && !independent && !nearField)) {
                hasRestrictedFields = true
                return
            }
            if (!independent && !nearField) return
            if (tokens.any { it.text == part && it.bounds.overlaps(nodeBounds) }) return
            val addedCharacters = part.length + if (tokens.isEmpty()) 0 else 1
            if (retainedCharacters + addedCharacters > ScreenCapturePolicy.MAX_TEXT_LENGTH) {
                complete = false
                return
            }
            tokens.add(ScreenNodeEvidence(part, nodeBounds, depth))
            retainedCharacters += addedCharacters
        }
        try {
            while (queue.isNotEmpty() && scanned.size < ScreenCapturePolicy.MAX_NODES) {
                if (!scope.isActive || !isCurrentFrame(frame) || !sourceEnabled(frame.packageName)) {
                    complete = false
                    break
                }
                val queued = queue.removeFirst()
                val node = queued.node
                val sensitiveNode = node.isPassword || node.isEditable || nodeHasSensitiveData(node)
                val restricted = queued.restricted || ReceiptNodeTextPolicy.excludesResource(node.viewIdResourceName.orEmpty())
                scanned.add(queued.copy(restricted = restricted || sensitiveNode))
                if (node.isVisibleToUser) {
                    if (sensitiveNode) hasSensitiveInput = true
                    if (restricted) hasRestrictedFields = true
                }
                if (queued.depth >= ScreenCapturePolicy.MAX_DEPTH) {
                    if (node.childCount > 0) complete = false
                    continue
                }
                val capacity = (ScreenCapturePolicy.MAX_NODES - scanned.size - queue.size).coerceAtLeast(0)
                if (node.childCount > capacity) complete = false
                for (index in 0 until minOf(node.childCount, capacity)) {
                    node.getChild(index)?.let { queue.addLast(QueuedNode(it, queued.depth + 1, restricted || sensitiveNode)) }
                }
            }
            if (!hasSensitiveInput && complete && isCurrentFrame(frame)) {
                for (item in scanned) {
                    if (!scope.isActive || !isCurrentFrame(frame) || !sourceEnabled(frame.packageName)) {
                        complete = false
                        break
                    }
                    if (item.restricted || !item.node.isVisibleToUser) continue
                    val nodeBounds = Rect().also(item.node::getBoundsInScreen).asEvidenceBounds()
                    append(item.node.text, nodeBounds, item.depth)
                    if (hasSensitiveInput || !complete) break
                    append(item.node.contentDescription, nodeBounds, item.depth)
                    if (hasSensitiveInput || !complete) break
                }
            }
        } finally {
            scanned.forEach { it.node.release() }
            while (queue.isNotEmpty()) queue.removeFirst().node.release()
        }
        return NodeSnapshot(tokens, hasSensitiveInput, hasRestrictedFields, complete, windowId, bounds,
            ScreenCaptureMetrics(scanned.size, tokens.size, retainedCharacters, SystemClock.elapsedRealtime() - startedAt))
    }

    private fun nodeHasSensitiveData(node: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && node.isAccessibilityDataSensitive

    private fun Rect.asEvidenceBounds() = ScreenNodeBounds(left, top, right, bottom)

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeBitmap(windowId: Int, windowBounds: Rect): ScreenshotAttempt = suspendCoroutine { continuation ->
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                var hardware: Bitmap? = null
                var software: Bitmap? = null
                try {
                    hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    software = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    if (software == null) {
                        continuation.resume(ScreenshotAttempt.Failed(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
                        return
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val crop = Rect(windowBounds)
                        if (!crop.intersect(0, 0, software.width, software.height) || crop.isEmpty) {
                            continuation.resume(ScreenshotAttempt.Failed(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
                            return
                        }
                        val cropped = Bitmap.createBitmap(software, crop.left, crop.top, crop.width(), crop.height())
                        if (cropped !== software) software.recycle()
                        software = cropped
                    }
                    val bitmap = software
                    software = null // Ownership transfers to the waiting coroutine.
                    continuation.resume(ScreenshotAttempt.Captured(bitmap))
                } catch (_: Exception) {
                    continuation.resume(ScreenshotAttempt.Failed(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
                } finally {
                    software?.recycle()
                    hardware?.recycle()
                    buffer.close()
                }
            }

            override fun onFailure(errorCode: Int) {
                continuation.resume(ScreenshotAttempt.Failed(errorCode))
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                takeScreenshotOfWindow(windowId, mainExecutor, callback)
            } else {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
            }
        } catch (_: Exception) {
            continuation.resume(ScreenshotAttempt.Failed(ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS))
        }
    }

    private fun publish(message: String, source: String? = null) {
        mutableStatus.value = ScreenCaptureStatus(message, System.currentTimeMillis(), source)
    }

    override fun onInterrupt() {
        invalidateForegroundFrame()
        cancelOcrRetry()
        publish("屏幕识别被系统暂时中断，请检查无障碍服务")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        invalidateForegroundFrame()
        cancelOcrRetry()
        mutableConnectionState.value = false
        publish("无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        invalidateForegroundFrame()
        cancelOcrRetry()
        mutableConnectionState.value = false
        events.close()
        scope.cancel()
        publish("无障碍服务已断开")
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.release() = recycle()

    private data class WindowEvent(
        val frame: ForegroundFrame,
        val recentWindowChange: Boolean,
        val paymentHint: Boolean,
    )

    private data class ForegroundFrame(
        val packageName: String = "",
        val activityClass: String = "",
        val windowId: Int = -1,
        val session: Long = 0,
        val changedAt: Long = 0,
        val lastStateEventTime: Long = 0,
    )

    private data class NodeSnapshot(
        val tokens: List<ScreenNodeEvidence>,
        val hasSensitiveInput: Boolean,
        val hasRestrictedFields: Boolean,
        val complete: Boolean,
        val windowId: Int,
        val bounds: Rect,
        val metrics: ScreenCaptureMetrics,
    ) {
        val text: String = tokens.joinToString("\n") { it.text }
    }

    private data class TitleProbe(val allowed: Boolean, val sensitive: Boolean, val visitedNodes: Int)
    private data class QueuedNode(val node: AccessibilityNodeInfo, val depth: Int, val restricted: Boolean = false)

    private sealed interface ScreenshotAttempt {
        data class Captured(val bitmap: Bitmap) : ScreenshotAttempt
        data class Failed(val errorCode: Int) : ScreenshotAttempt
    }

    companion object {
        private val OBSERVED_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )
        private val mutableConnectionState = MutableStateFlow(false)
        val connectionState: StateFlow<Boolean> = mutableConnectionState.asStateFlow()
        private val mutableStatus = MutableStateFlow(ScreenCaptureStatus("屏幕识别尚未连接"))
        val status: StateFlow<ScreenCaptureStatus> = mutableStatus.asStateFlow()
        private val mutableMetrics = MutableStateFlow(ScreenCaptureMetrics())
        val metrics: StateFlow<ScreenCaptureMetrics> = mutableMetrics.asStateFlow()
    }
}
