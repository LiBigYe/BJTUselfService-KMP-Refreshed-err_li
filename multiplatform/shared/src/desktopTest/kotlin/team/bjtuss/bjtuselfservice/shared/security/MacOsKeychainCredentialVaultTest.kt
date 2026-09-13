package team.bjtuss.bjtuselfservice.shared.security

import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacOsKeychainCredentialVaultTest {
    @Test
    fun syntheticCredentialRoundTripUsesSystemKeychain() {
        // 该 vault 直接调用 macOS Security.framework。在 Windows/Linux 上加载 native 库
        // 必然失败，这不是被测代码的问题。跳过而不是让它失败：否则
        // `:shared:desktopTest` 会在这两个平台上永远变红，真正的回归会被这条噪声掩盖。
        if (!System.getProperty("os.name").lowercase().contains("mac")) {
            println("SKIP MacOsKeychainCredentialVaultTest (requires macOS Keychain)")
            return
        }
        runSuspend {
            val vault = MacOsKeychainCredentialVault(
                service = "team.bjtuss.bjtuselfservice.kmp.credentials.desktop-smoke",
                account = "synthetic-fixture",
            )
            val fixture = Credentials("fixture-student", "fixture-password-安全")

            try {
                vault.clear()
                vault.save(fixture)
                assertEquals(fixture, vault.load())
            } finally {
                vault.clear()
            }
            assertNull(vault.load())
        }
    }
}

private fun runSuspend(block: suspend () -> Unit) {
    var failure: Throwable? = null
    block.startCoroutine(
        object : kotlin.coroutines.Continuation<Unit> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                failure = result.exceptionOrNull()
            }
        },
    )
    failure?.let { throw it }
}
