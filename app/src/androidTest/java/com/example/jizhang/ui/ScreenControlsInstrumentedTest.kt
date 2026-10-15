package com.example.jizhang.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.jizhang.automation.ScreenCaptureStatus
import com.example.jizhang.automation.ScreenConnectionDiagnostics
import com.example.jizhang.data.LedgerSettings
import com.example.jizhang.ui.theme.JizhangTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic API/permission states; this never grants system permissions or reads another app. */
@RunWith(AndroidJUnit4::class)
class ScreenControlsInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private var appInfoOpened = 0
    private var changedSettings = 0
    private var connectionChecks = 0
    private var backgroundOpened = 0
    private var copiedDiagnostics = 0

    @Test fun samsungRestrictedSettingsHasAnExplicitApplicationInfoRoute() {
        show(granted = false)
        composeRule.onNodeWithText("由受限设置控制", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("screen-app-info").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, appInfoOpened); assertEquals(0, changedSettings) }
    }

    @Test fun grantedServiceWaitingForConnectionIsDistinctFromRestrictedSettings() {
        show(granted = true, settings = LedgerSettings(screenEnabled = true))
        composeRule.onNodeWithText("已授权，等待系统连接").assertIsDisplayed()
        composeRule.onNodeWithTag("screen-app-info").assertDoesNotExist()
    }

    @Test fun api24DisablesAutomaticScreenshotButAllowsManualImport() {
        show(sdk = 24)
        composeRule.onNodeWithTag("screen-ocr-enabled").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("导入支付截图识别").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("不支持自动截屏", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun recoveryBlocksRecognitionConsentAndNewImports() {
        show(enabled = false)
        composeRule.onNodeWithTag("screen-enabled").assertIsNotEnabled()
        composeRule.onNodeWithTag("screen-ocr-enabled").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("导入支付截图识别").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, changedSettings) }
    }

    @Test fun displayScreenshotLimitIsDisclosedBeforeOlderSystemOptIn() {
        show(sdk = 33)
        composeRule.onNodeWithTag("screen-ocr-enabled").performScrollTo().performClick()
        composeRule.onNodeWithText("本机系统提供整屏截图", substring = true).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, changedSettings) }
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle { assertEquals(0, changedSettings) }
    }

    @Test fun authorizedButNotListedShowsReadOnlyDiagnosticsAndRecoveryRoutes() {
        show(granted = true, settings = LedgerSettings(screenEnabled = true), diagnostic = ScreenConnectionDiagnostics(true, false, false))
        composeRule.onNodeWithTag("screen-connection-status").assertTextContains("已授权，系统尚未列出服务")
        composeRule.onNodeWithTag("screen-connection-help").performScrollTo().performClick()
        composeRule.onNodeWithTag("screen-diagnostic-granted").performScrollTo().assertTextContains("已授权", substring = true)
        composeRule.onNodeWithTag("screen-diagnostic-system").performScrollTo().assertTextContains("未列出", substring = true)
        composeRule.onNodeWithTag("screen-diagnostic-local").performScrollTo().assertTextContains("尚未收到", substring = true)
        composeRule.onNodeWithTag("screen-help-app-info").performScrollTo().performClick()
        composeRule.onNodeWithTag("screen-help-background").performScrollTo().performClick()
        composeRule.onNodeWithTag("screen-copy-diagnostics").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, appInfoOpened); assertEquals(1, backgroundOpened); assertEquals(1, copiedDiagnostics); assertEquals(0, changedSettings) }
    }

    @Test fun systemListingNeverSubstitutesForTheLocalConnectionCallback() {
        show(granted = true, settings = LedgerSettings(screenEnabled = true), diagnostic = ScreenConnectionDiagnostics(true, true, false))
        composeRule.onNodeWithTag("screen-connection-status").assertTextContains("系统已列出服务，尚未收到连接回调")
        composeRule.onNodeWithTag("screen-connection-help").performScrollTo().performClick()
        composeRule.onNodeWithTag("screen-diagnostic-system").performScrollTo().assertTextContains("已列出", substring = true)
        composeRule.onNodeWithTag("screen-diagnostic-local").performScrollTo().assertTextContains("尚未收到", substring = true)
        composeRule.runOnIdle { assertEquals(0, changedSettings) }
    }

    @Test fun explicitRecheckReflectsTheLatestLocalConnectionWithoutChangingSettings() {
        var diagnostic by mutableStateOf(ScreenConnectionDiagnostics(true, true, false))
        composeRule.setContent { JizhangTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScreenControls(LedgerSettings(screenEnabled = true), granted = true, connected = diagnostic.localConnected,
                    status = ScreenCaptureStatus("等待结果页"), importing = false, onSettings = { changedSettings++ },
                    onAccess = {}, onImport = {}, onReview = {}, connectionDiagnostics = diagnostic,
                    onCheckConnection = { connectionChecks++; diagnostic = ScreenConnectionDiagnostics(true, true, true) })
            }
        } }
        composeRule.onNodeWithTag("screen-check-connection").performScrollTo().performClick()
        composeRule.onNodeWithTag("screen-connection-status").assertTextContains("已连接 · 请在支付后停留于结果页")
        composeRule.onNodeWithTag("screen-check-connection").assertDoesNotExist()
        composeRule.onNodeWithTag("screen-connection-help").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(1, connectionChecks); assertEquals(0, changedSettings) }
    }

    @Test fun unavailableQueryIsDisplayedAsUnknownRatherThanPermissionDenied() {
        show(settings = LedgerSettings(screenEnabled = true), diagnostic = ScreenConnectionDiagnostics(null, null, false))
        composeRule.onNodeWithTag("screen-connection-status").assertTextContains("暂时无法读取系统状态，请重新检查连接")
        composeRule.onNodeWithTag("screen-app-info").assertDoesNotExist()
        composeRule.onNodeWithTag("screen-check-connection").performScrollTo().performClick()
        composeRule.onNodeWithTag("screen-connection-help").performScrollTo().performClick()
        composeRule.onNodeWithTag("screen-diagnostic-granted").performScrollTo().assertTextContains("查询不可用", substring = true)
        composeRule.onNodeWithTag("screen-diagnostic-system").performScrollTo().assertTextContains("查询不可用", substring = true)
        composeRule.runOnIdle { assertEquals(1, connectionChecks); assertEquals(0, changedSettings) }
    }

    @Test fun unavailableSystemQueryDoesNotDenyAnExistingLocalCallbackOrPromiseCaptureStopped() {
        show(connected = true, settings = LedgerSettings(screenEnabled = true), diagnostic = ScreenConnectionDiagnostics(null, null, true))
        composeRule.onNodeWithTag("screen-connection-status").assertTextContains("暂时无法读取系统状态，请重新检查连接")
        composeRule.onNodeWithText("请先核对下面的连接状态", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("连接恢复前不会读取交易页面", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("screen-app-info").assertDoesNotExist()
        composeRule.onNodeWithTag("screen-connection-help").performScrollTo().performClick()
        composeRule.onNodeWithTag("screen-diagnostic-granted").performScrollTo().assertTextContains("查询不可用", substring = true)
        composeRule.onNodeWithTag("screen-diagnostic-local").performScrollTo().assertTextContains("轻记连接回调：已收到")
        composeRule.runOnIdle { assertEquals(0, changedSettings) }
    }

    private fun show(granted: Boolean = false, enabled: Boolean = true, sdk: Int = 34, settings: LedgerSettings = LedgerSettings(), diagnostic: ScreenConnectionDiagnostics? = null, connected: Boolean = false) {
        composeRule.setContent { JizhangTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScreenControls(settings, granted, connected = connected, status = ScreenCaptureStatus("等待结果页"),
                    importing = false, onSettings = { changedSettings++ }, onAccess = {}, onImport = {}, onReview = {},
                    onAppInfo = { appInfoOpened++ }, enabled = enabled, sdk = sdk, connectionDiagnostics = diagnostic,
                    onCheckConnection = { connectionChecks++ }, onBackgroundSettings = { backgroundOpened++ }, onCopyDiagnostics = { copiedDiagnostics++ })
            }
        } }
    }
}
