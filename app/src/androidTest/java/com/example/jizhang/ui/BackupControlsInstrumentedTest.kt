package com.example.jizhang.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.data.LedgerRepository
import com.example.jizhang.ui.theme.JizhangTheme
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Password validation is checked before encryption or a system file picker is launched. */
@RunWith(AndroidJUnit4::class)
class BackupControlsInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var context: Context
    private lateinit var repository: LedgerRepository
    private lateinit var databaseName: String
    private lateinit var preferencesName: String
    private var workStarted = 0
    private var restores = 0
    private val messages = mutableListOf<String>()

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        databaseName = "backup-ui-$suffix.db"
        preferencesName = "backup-ui-$suffix"
        repository = LedgerRepository.isolated(context, databaseName, preferencesName)
        runBlocking { repository.retryPendingRestore() }
        composeRule.setContent { JizhangTheme {
            BackupControls(repository, onBusyChanged = { if (it) workStarted++ },
                onRestored = { restores++ }, onMessage = { messages += it })
        } }
    }

    @After fun tearDown() = runBlocking {
        composeRule.waitForIdle()
        repository.closeForTesting()
        context.deleteDatabase(databaseName)
        context.deleteSharedPreferences(preferencesName)
        Unit
    }

    @Test fun weakPasswordIsRejectedBeforeGeneratingOrSelectingAFile() {
        openPassword()
        fillPasswords("1234567", "1234567")
        composeRule.onNodeWithTag("backup-password-confirm").performClick()
        composeRule.onNodeWithText("请输入有效密码，创建备份至少8个字符").assertIsDisplayed()
        assertNoWork()
    }

    @Test fun unequalPasswordsAreRejectedBeforeGeneratingOrSelectingAFile() {
        openPassword()
        fillPasswords("abcdefgh", "abcdefgi")
        composeRule.onNodeWithTag("backup-password-confirm").performClick()
        composeRule.onNodeWithText("两次密码不一致").assertIsDisplayed()
        assertNoWork()
    }

    @Test fun cancellingPasswordDialogClearsBothPasswordsWithoutOpeningFilePicker() {
        openPassword()
        fillPasswords("synthetic-password", "synthetic-password")
        composeRule.onNodeWithText("取消").performClick()
        composeRule.onNodeWithTag("backup-password").assertDoesNotExist()
        composeRule.onNodeWithTag("create-backup").assertIsEnabled()
        assertNoWork()
        openPassword()
        assertEquals("", composeRule.onNodeWithTag("backup-password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("", composeRule.onNodeWithTag("backup-password-repeat").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    private fun openPassword() { composeRule.onNodeWithTag("create-backup").performClick() }
    private fun fillPasswords(password: String, repeated: String) {
        composeRule.onNodeWithTag("backup-password").performTextReplacement(password)
        composeRule.onNodeWithTag("backup-password-repeat").performTextReplacement(repeated)
        closeSoftKeyboard()
    }
    private fun assertNoWork() = composeRule.runOnIdle {
        assertEquals(0, workStarted)
        assertEquals(0, restores)
        assertTrue(messages.isEmpty())
        assertTrue(repository.entries.value.isEmpty())
        assertFalse(repository.restoreBlocked.value)
    }
}
