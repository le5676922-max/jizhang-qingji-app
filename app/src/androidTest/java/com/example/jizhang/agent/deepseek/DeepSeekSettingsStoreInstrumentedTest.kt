package com.example.jizhang.agent.deepseek

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.agent.deepseek.DeepSeekProtocolInstrumentedTest.Companion.expectFailure
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeepSeekSettingsStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString()
    private val preferencesName = "deepseek_store_test_$suffix"
    private val alias = "qingji_deepseek_test_$suffix"
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val store = DeepSeekSettingsStore(context, preferencesName, alias)

    @After fun cleanUp() {
        context.deleteSharedPreferences(preferencesName)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    @Test fun keyRoundTripUsesKeystoreCiphertextAndSeparateConfiguration() {
        assertEquals(DeepSeekConfiguration(false, DeepSeekModels.DEFAULT, false), store.configuration())
        store.save(KEY, DeepSeekModels.DEFAULT, true)
        assertEquals(KEY, store.readApiKey())
        assertEquals(DeepSeekConfiguration(true, DeepSeekModels.DEFAULT, true), store.configuration())
        val encrypted = preferences.getString(DeepSeekSettingsStore.KEY_CIPHERTEXT, null)!!
        assertTrue(encrypted.startsWith("v1:"))
        assertFalse(preferences.all.toString().contains(KEY))
        val entry = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
        assertNotNull(entry)
        assertNull(entry.encoded)
        assertEquals(KEY, DeepSeekSettingsStore(context, preferencesName, alias).readApiKey())
    }

    @Test fun encryptingSameKeyTwiceUsesDifferentNonces() {
        store.save(KEY, DeepSeekModels.DEFAULT, false)
        val first = preferences.getString(DeepSeekSettingsStore.KEY_CIPHERTEXT, null)!!
        store.save(KEY, DeepSeekModels.DEFAULT, false)
        val second = preferences.getString(DeepSeekSettingsStore.KEY_CIPHERTEXT, null)!!
        assertNotEquals(first, second)
        assertNotEquals(first.split(':')[1], second.split(':')[1])
        assertEquals(KEY, store.readApiKey())
    }

    @Test fun nullReplacementPreservesKeyWhileModelAndEnabledChange() {
        store.save(KEY, DeepSeekModels.DEFAULT, true)
        val encrypted = preferences.getString(DeepSeekSettingsStore.KEY_CIPHERTEXT, null)
        store.save(null, "deepseek-v4-pro", false)
        assertEquals(encrypted, preferences.getString(DeepSeekSettingsStore.KEY_CIPHERTEXT, null))
        assertEquals(KEY, store.readApiKey())
        assertEquals(DeepSeekConfiguration(false, "deepseek-v4-pro", true), store.configuration())
        store.save(null, "deepseek-v4-pro", true)
        assertTrue(store.configuration().enabled)
    }

    @Test fun missingKeyCannotEnableProviderAndClearRemovesCiphertextAndAlias() {
        expectFailure { store.save(null, DeepSeekModels.DEFAULT, true) }
        assertFalse(store.configuration().enabled)
        store.save(KEY, DeepSeekModels.DEFAULT, true)
        store.clear()
        assertEquals(DeepSeekConfiguration(false, DeepSeekModels.DEFAULT, false), store.configuration())
        assertTrue(preferences.all.isEmpty())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
        expectFailure { store.readApiKey() }
    }

    @Test fun malformedKeyAndUnknownModelCannotOverwriteExistingConfiguration() {
        store.save(KEY, DeepSeekModels.DEFAULT, true)
        listOf("", "   ", "x".repeat(513), "a\r\nb", "a\tb", "非 ASCII 密钥").forEach { value ->
            expectFailure { store.save(value, DeepSeekModels.DEFAULT, true) }
            assertEquals(KEY, store.readApiKey())
        }
        expectFailure { store.save(KEY, "deepseek-chat", true) }
        assertEquals(DeepSeekModels.DEFAULT, store.configuration().model)
    }

    @Test fun tamperedCiphertextFailsClosedWithoutPlaintextFallback() {
        store.save(KEY, DeepSeekModels.DEFAULT, true)
        val fields = preferences.getString(DeepSeekSettingsStore.KEY_CIPHERTEXT, null)!!.split(':')
        val ciphertext = Base64.decode(fields[2], Base64.NO_WRAP)
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        val tampered = "v1:${fields[1]}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
        assertTrue(preferences.edit().putString(DeepSeekSettingsStore.KEY_CIPHERTEXT, tampered).commit())
        val failure = expectFailure { store.readApiKey() }
        assertFalse(failure.message.orEmpty().contains(KEY))
        expectFailure { store.save(null, DeepSeekModels.DEFAULT, true) }
        // The user can still turn off a broken provider without successfully decrypting it.
        store.save(null, DeepSeekModels.DEFAULT, false)
        assertFalse(store.configuration().enabled)
        assertFalse(preferences.all.toString().contains(KEY))
    }

    @Test fun missingKeystoreEntryDoesNotCreateReplacementKeyForOldCiphertext() {
        store.save(KEY, DeepSeekModels.DEFAULT, true)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry(alias)
        expectFailure { store.readApiKey() }
        assertFalse(keyStore.containsAlias(alias))
        store.save("replacement-test-key", DeepSeekModels.DEFAULT, true)
        assertEquals("replacement-test-key", store.readApiKey())
    }

    companion object { private const val KEY = "synthetic-provider-secret-not-a-real-key" }
}
