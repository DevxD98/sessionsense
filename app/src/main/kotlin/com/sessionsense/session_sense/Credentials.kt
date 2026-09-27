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

/** A connected Claude account. An empty [sessionKey] means the session expired and needs reconnecting. */
data class Account(val id: String, val orgId: String, val sessionKey: String, val name: String, val email: String?) {
    val connected get() = sessionKey.isNotEmpty()
    val initial get() = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** What a claude.ai login in [ClaudeAuth] hands back. */
data class ClaudeLogin(val sessionKey: String, val orgId: String, val name: String, val email: String?)

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

    /** Adds a new account, or refreshes the key of one that is already connected (same org and email). */
    @Synchronized fun upsert(login: ClaudeLogin): Result<Account> {
        val list = state.value
        val existing = list.firstOrNull { it.orgId == login.orgId && (it.email == null || login.email == null || it.email.equals(login.email, ignoreCase = true)) }
        val account = existing?.copy(sessionKey = login.sessionKey, name = login.name, email = login.email ?: existing.email)
            ?: run {
                if (list.size >= MAX_ACCOUNTS) return Result.failure(IllegalStateException("You can track up to $MAX_ACCOUNTS accounts. Remove one in Settings first."))
                Account(if (list.isEmpty()) DEFAULT_ACCOUNT else UUID.randomUUID().toString().take(8), login.orgId, login.sessionKey, login.name, login.email)
            }
        write(if (existing != null) list.map { if (it.id == account.id) account else it } else list + account)
        return Result.success(account)
    }

    @Synchronized fun updateProfile(id: String, name: String, email: String?) = write(state.value.map { if (it.id == id) it.copy(name = name, email = email ?: it.email) else it })
    @Synchronized fun markExpired(id: String) = write(state.value.map { if (it.id == id) it.copy(sessionKey = "") else it })
    @Synchronized fun remove(id: String) = write(state.value.filterNot { it.id == id })

    private fun write(list: List<Account>) {
        val json = JSONArray(list.map { JSONObject().put("id", it.id).put("orgId", it.orgId).put("key", it.sessionKey).put("name", it.name).put("email", it.email ?: JSONObject.NULL) })
        prefs.edit().putString(ACCOUNTS, json.toString()).remove(LEGACY_KEY).remove(LEGACY_ORG).apply()
        state.value = list
    }

    private fun read(): List<Account> {
        prefs.getString(ACCOUNTS, null)?.let { raw ->
            return runCatching {
                val arr = JSONArray(raw)
                List(arr.length()) { i -> arr.getJSONObject(i).let { Account(it.getString("id"), it.getString("orgId"), it.getString("key"), it.getString("name"), it.optString("email").takeIf { e -> e.isNotBlank() && e != "null" }) } }
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
