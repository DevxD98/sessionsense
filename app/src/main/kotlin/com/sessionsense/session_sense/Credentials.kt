package com.sessionsense.session_sense

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class Provider(val id: String, val label: String) {
    CLAUDE("claude", "Claude"), CODEX("codex", "Codex");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: CLAUDE }
}

/**
 * A connected account. An empty [sessionKey] means the session expired and needs reconnecting.
 * Claude: [orgId] is the organisation UUID and [sessionKey] the claude.ai sessionKey cookie.
 * Codex: [orgId] is the ChatGPT account id, [sessionKey] the chatgpt.com Cookie header (used to mint new access
 * tokens without the WebView) and [accessToken] the current bearer token, valid until [tokenExpMs].
 */
data class Account(
    val id: String, val orgId: String, val sessionKey: String, val name: String, val email: String?,
    val provider: Provider = Provider.CLAUDE, val accessToken: String = "", val tokenExpMs: Long = 0,
) {
    val connected get() = sessionKey.isNotEmpty()
    val initial get() = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("orgId", orgId).put("key", sessionKey).put("name", name)
        .put("email", email ?: JSONObject.NULL).put("provider", provider.id).put("accessToken", accessToken).put("tokenExpMs", tokenExpMs)

    companion object {
        /** Entries written before Codex support have no provider and are Claude accounts. */
        fun fromJson(o: JSONObject) = Account(o.getString("id"), o.getString("orgId"), o.getString("key"), o.getString("name"),
            o.optString("email").takeIf { it.isNotBlank() && it != "null" }, Provider.of(o.optString("provider").takeIf { it.isNotBlank() }),
            o.optString("accessToken"), o.optLong("tokenExpMs"))
    }
}

/** What a login in [ClaudeAuth] or [CodexAuth] hands back; see [Account] for what each field holds per provider. */
data class AccountLogin(
    val sessionKey: String, val orgId: String, val name: String, val email: String?,
    val provider: Provider = Provider.CLAUDE, val accessToken: String = "", val tokenExpMs: Long = 0,
)

class CredentialStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "claude_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val state = MutableStateFlow(read())

    /** Observed by the UI; the polling service runs in the same process, so its writes show up here immediately. */
    val accounts: StateFlow<List<Account>> = state.asStateFlow()

    fun hasConnected() = state.value.any { it.connected }

    /** Adds a new account, or refreshes the credentials of one that is already connected (same provider, org and email). */
    @Synchronized fun upsert(login: AccountLogin): Result<Account> {
        val list = state.value
        val existing = list.firstOrNull { it.provider == login.provider && it.orgId == login.orgId && (it.email == null || login.email == null || it.email.equals(login.email, ignoreCase = true)) }
        val account = existing?.copy(sessionKey = login.sessionKey, name = login.name, email = login.email ?: existing.email, accessToken = login.accessToken, tokenExpMs = login.tokenExpMs)
            ?: run {
                // One limit across providers: every account is polled in the background.
                if (list.size >= MAX_ACCOUNTS) return Result.failure(IllegalStateException("You can track up to $MAX_ACCOUNTS accounts. Remove one in Settings first."))
                Account(if (list.isEmpty()) DEFAULT_ACCOUNT else UUID.randomUUID().toString().take(8), login.orgId, login.sessionKey, login.name, login.email,
                    login.provider, login.accessToken, login.tokenExpMs)
            }
        write(if (existing != null) list.map { if (it.id == account.id) account else it } else list + account)
        return Result.success(account)
    }

    @Synchronized fun updateProfile(id: String, name: String, email: String?) = write(state.value.map { if (it.id == id) it.copy(name = name, email = email ?: it.email) else it })
    @Synchronized fun markExpired(id: String) = write(state.value.map { if (it.id == id) it.copy(sessionKey = "", accessToken = "", tokenExpMs = 0) else it })
    /** Stores a refreshed Codex access token (and the cookies chatgpt.com rotated while issuing it). */
    @Synchronized fun updateToken(id: String, cookies: String, accessToken: String, tokenExpMs: Long) =
        write(state.value.map { if (it.id == id && it.connected) it.copy(sessionKey = cookies, accessToken = accessToken, tokenExpMs = tokenExpMs) else it })
    @Synchronized fun remove(id: String) = write(state.value.filterNot { it.id == id })

    private fun write(list: List<Account>) {
        val json = JSONArray(list.map { it.toJson() })
        prefs.edit().putString(ACCOUNTS, json.toString()).remove(LEGACY_KEY).remove(LEGACY_ORG).apply()
        state.value = list
    }

    private fun read(): List<Account> {
        prefs.getString(ACCOUNTS, null)?.let { raw ->
            return runCatching {
                val arr = JSONArray(raw)
                List(arr.length()) { i -> Account.fromJson(arr.getJSONObject(i)) }
            }.getOrDefault(emptyList())
        }
        // Single-account installs: adopt the old credentials as the "default" account, whose data keys are unprefixed.
        val key = prefs.getString(LEGACY_KEY, null) ?: return emptyList()
        val org = prefs.getString(LEGACY_ORG, null) ?: return emptyList()
        return listOf(Account(DEFAULT_ACCOUNT, org, key, "Claude", null))
    }

    companion object {
        const val MAX_ACCOUNTS = 3
        private const val ACCOUNTS = "accounts_v2"
        private const val LEGACY_KEY = "claude_session_key"
        private const val LEGACY_ORG = "claude_org_id"
    }
}
