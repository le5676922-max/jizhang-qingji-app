package com.example.jizhang.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.jizhang.automation.ScreenCaptureStatus
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

    private fun show(granted: Boolean = false, enabled: Boolean = true, sdk: Int = 34, settings: LedgerSettings = LedgerSettings()) {
        composeRule.setContent { JizhangTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScreenControls(settings, granted, connected = false, status = ScreenCaptureStatus("等待结果页"),
                    importing = false, onSettings = { changedSettings++ }, onAccess = {}, onImport = {}, onReview = {},
                    onAppInfo = { appInfoOpened++ }, enabled = enabled, sdk = sdk)
            }
        } }
    }
}
