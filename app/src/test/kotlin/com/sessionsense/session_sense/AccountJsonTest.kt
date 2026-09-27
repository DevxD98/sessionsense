package com.sessionsense.session_sense

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountJsonTest {
    @Test fun entriesWrittenBeforeCodexSupportAreClaudeAccounts() {
        // Exactly what CredentialStore wrote before the provider field existed.
        val legacy = JSONObject("""{"id":"default","orgId":"0f3c-org","key":"sk-ant-abc","name":"Dev","email":null}""")
        val a = Account.fromJson(legacy)
        assertEquals(Provider.CLAUDE, a.provider)
        assertEquals(Account("default", "0f3c-org", "sk-ant-abc", "Dev", null), a)
        assertTrue(a.connected)
    }

    @Test fun codexAccountsRoundTrip() {
        val a = Account("a1b2c3d4", "acc-123", "__Secure-next-auth.session-token=x; cf_clearance=y", "Dev", "dev@example.com", Provider.CODEX, "eyJ.tok.en", 1_791_000_000_000L)
        assertEquals(a, Account.fromJson(JSONObject(a.toJson().toString())))
    }

    @Test fun unknownProvidersFallBackToClaude() {
        assertEquals(Provider.CLAUDE, Provider.of("gemini"))
        assertEquals(Provider.CODEX, Provider.of("codex"))
        assertEquals(Provider.CLAUDE, Provider.of(null))
    }
}
