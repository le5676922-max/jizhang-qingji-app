package com.example.jizhang.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.jizhang.ui.theme.JizhangTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic capability states only: no settings launch, identifiers, permission grant, or storage. */
@RunWith(AndroidJUnit4::class)
class DeviceCompatibilityCardInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun backgroundManagementRemainsReachableAt320DpAndDoubleFontSize() {
        var opened = 0
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                JizhangTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight().testTag("device-test-viewport")) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            DeviceCompatibilityCard(onBackgroundSettings = { opened++ },
                                manufacturer = "合成厂商", model = "合成长型号 Android 设备", sdk = 33, lowRam = false)
                        }
                    }
                }
            }
        }
        val button = composeRule.onNodeWithTag("device-background-settings").performScrollTo().assertIsDisplayed()
        val viewport = composeRule.onNodeWithTag("device-test-viewport").fetchSemanticsNode().boundsInRoot
        val buttonBounds = button.fetchSemanticsNode().boundsInRoot
        val labelBounds = composeRule.onNodeWithText("管理后台运行", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(buttonBounds.left >= viewport.left && buttonBounds.right <= viewport.right)
        assertTrue(labelBounds.left >= buttonBounds.left && labelBounds.right <= buttonBounds.right)
        button.performClick()
        composeRule.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun unknownManufacturerUsesPlatformTiersAndDisclosesLowRamNotificationLimit() {
        val sdk = mutableIntStateOf(24)
        composeRule.setContent { JizhangTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DeviceCompatibilityCard(onBackgroundSettings = {}, manufacturer = "unknown", model = "",
                    sdk = sdk.intValue, lowRam = true)
            }
        } }
        composeRule.onNodeWithTag("device-platform").assertTextEquals("未知 · 未知 · API 24")
        composeRule.onNodeWithTag("device-screen-capability").assertTextContains("系统未提供自动截图接口", substring = true)
        composeRule.onNodeWithTag("device-low-ram-limit").performScrollTo().assertTextContains("系统不支持通知监听服务", substring = true)
        composeRule.runOnIdle { sdk.intValue = 33 }
        composeRule.onNodeWithTag("device-screen-capability").performScrollTo().assertTextContains("整屏 OCR", substring = true)
        composeRule.onNodeWithTag("device-low-ram-limit").assertDoesNotExist()
        composeRule.runOnIdle { sdk.intValue = 34 }
        composeRule.onNodeWithTag("device-screen-capability").performScrollTo().assertTextContains("交易窗口截图", substring = true)
        composeRule.onNodeWithText("能力按系统版本判断", substring = true).performScrollTo().assertIsDisplayed()
    }
}
