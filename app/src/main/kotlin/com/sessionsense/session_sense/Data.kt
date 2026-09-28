package com.sessionsense.session_sense

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

val Context.dataStore by preferencesDataStore("session_sense")

/** Id of the first account; its data keeps the original unprefixed keys, so single-account installs need no migration. */
const val DEFAULT_ACCOUNT = "default"

@Entity(tableName = "sessions", indices = [Index("accountId")])
data class SessionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val pctUsed: Int,
    val accountId: String = DEFAULT_ACCOUNT,
) { val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0) }

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE accountId = :accountId AND endMs - startMs >= 60000 ORDER BY startMs DESC LIMIT 100")
    fun observeAll(accountId: String): Flow<List<SessionRecord>>

    @Insert suspend fun insert(record: SessionRecord)
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM sessions WHERE accountId = :accountId") suspend fun clear(accountId: String)
}

/** One reading of Claude usage, written by [UsagePollingService] whenever a value changes (plus a periodic heartbeat). */
@Entity(tableName = "usage_samples", indices = [Index("accountId", "ts")])
data class UsageSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val sessionPct: Int,
    val weeklyPct: Int,
    val opusPct: Int,
    val sonnetPct: Int,
    val accountId: String = DEFAULT_ACCOUNT,
)

@Dao
interface UsageSampleDao {
    @Query("SELECT * FROM usage_samples WHERE accountId = :accountId AND ts >= :since ORDER BY ts ASC")
    fun observeSince(accountId: String, since: Long): Flow<List<UsageSample>>

    @Query("SELECT * FROM usage_samples WHERE accountId = :accountId AND ts >= :since ORDER BY ts ASC")
    suspend fun since(accountId: String, since: Long): List<UsageSample>

    @Insert suspend fun insert(sample: UsageSample)
    @Query("DELETE FROM usage_samples WHERE ts < :before") suspend fun prune(before: Long)
    @Query("DELETE FROM usage_samples WHERE accountId = :accountId") suspend fun clear(accountId: String)
}

@Database(entities = [SessionRecord::class, UsageSample::class], version = 3, exportSchema = false)
abstract class SessionDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun samples(): UsageSampleDao
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `usage_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ts` INTEGER NOT NULL, `sessionPct` INTEGER NOT NULL, `weeklyPct` INTEGER NOT NULL, `opusPct` INTEGER NOT NULL, `sonnetPct` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_samples_ts` ON `usage_samples` (`ts`)")
    }
}

// Tables are rebuilt rather than ALTERed so the schema matches the entities exactly (no column DEFAULT to reconcile);
// existing rows belong to the first account.
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE `sessions_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `pctUsed` INTEGER NOT NULL, `accountId` TEXT NOT NULL)")
        db.execSQL("INSERT INTO `sessions_new` (`id`, `startMs`, `endMs`, `pctUsed`, `accountId`) SELECT `id`, `startMs`, `endMs`, `pctUsed`, '$DEFAULT_ACCOUNT' FROM `sessions`")
        db.execSQL("DROP TABLE `sessions`")
        db.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_accountId` ON `sessions` (`accountId`)")
        db.execSQL("CREATE TABLE `usage_samples_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ts` INTEGER NOT NULL, `sessionPct` INTEGER NOT NULL, `weeklyPct` INTEGER NOT NULL, `opusPct` INTEGER NOT NULL, `sonnetPct` INTEGER NOT NULL, `accountId` TEXT NOT NULL)")
        db.execSQL("INSERT INTO `usage_samples_new` (`id`, `ts`, `sessionPct`, `weeklyPct`, `opusPct`, `sonnetPct`, `accountId`) SELECT `id`, `ts`, `sessionPct`, `weeklyPct`, `opusPct`, `sonnetPct`, '$DEFAULT_ACCOUNT' FROM `usage_samples`")
        db.execSQL("DROP TABLE `usage_samples`")
        db.execSQL("ALTER TABLE `usage_samples_new` RENAME TO `usage_samples`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_samples_accountId_ts` ON `usage_samples` (`accountId`, `ts`)")
    }
}

data class UsageSnapshot(
    val sessionPct: Int = 0,
    val weeklyPct: Int = 0,
    val opusPct: Int = 0,
    val sonnetPct: Int = 0,
    val sessionResetMs: Long = 0,
    val weeklyResetMs: Long = 0,
    val lastUpdatedMs: Long = 0,
    val connection: String = "idle",
    /** Plan reported by the provider (Codex only, e.g. "plus"); empty when unknown. */
    val planType: String = "",
    /** False when the provider reported no 5-hour window (Codex sometimes returns only the weekly one). */
    val sessionWindow: Boolean = true,
    /** Whether claude.ai reports a separate Opus / Sonnet weekly limit. Most plans have none: then the pct is meaningless. */
    val opusReported: Boolean = false,
    val sonnetReported: Boolean = false,
)

/** The Claude plan the user picked in Settings, as shown on a plan tile. */
fun claudePlanName(plan: String) = when (plan) { "max5" -> "Max 5×"; "max20" -> "Max 20×"; else -> "Pro" }

data class UserSettings(
    val route: String = "onboarding",
    val plan: String = "pro",
    val quietHours: Boolean = false,
    val quietStart: Int = 22,
    val quietEnd: Int = 7,
    val sessionAlerts: Boolean = true,
    val weeklyAlerts: Boolean = true,
    val modelAlerts: Boolean = true,
    val weeklyDigest: Boolean = true,
)

/** App-wide preferences. Per-account values live in [AccountKeys]. */
object Keys {
    val ROUTE = stringPreferencesKey("route")
    val ACTIVE_ACCOUNT = stringPreferencesKey("active_account")
    val QUIET = booleanPreferencesKey("quiet_hours")
    val QUIET_START = intPreferencesKey("quiet_start")
    val QUIET_END = intPreferencesKey("quiet_end")
    val SESSION_ALERTS = booleanPreferencesKey("session_alerts")
    val WEEKLY_ALERTS = booleanPreferencesKey("weekly_alerts")
    val MODEL_ALERTS = booleanPreferencesKey("model_alerts")
    val DIGEST = booleanPreferencesKey("weekly_digest")
}

/** Usage, session-tracking and alert state for one account. The default account uses the original key names. */
class AccountKeys(val accountId: String) {
    private val prefix = if (accountId == DEFAULT_ACCOUNT) "" else "acct.$accountId."
    val PLAN = stringPreferencesKey("${prefix}plan")
    val SESSION = intPreferencesKey("${prefix}session_pct")
    val WEEKLY = intPreferencesKey("${prefix}weekly_pct")
    val OPUS = intPreferencesKey("${prefix}opus_pct")
    val SONNET = intPreferencesKey("${prefix}sonnet_pct")
    val SESSION_RESET = longPreferencesKey("${prefix}session_reset_ms")
    val WEEKLY_RESET = longPreferencesKey("${prefix}weekly_reset_ms")
    val UPDATED = longPreferencesKey("${prefix}last_updated_ms")
    val CONNECTION = stringPreferencesKey("${prefix}connection")
    val PREV_SESSION = intPreferencesKey("${prefix}prev_session_pct")
    val ACTIVE_START = longPreferencesKey("${prefix}active_start_ms")
    val ACTIVE_PEAK = intPreferencesKey("${prefix}active_peak")
    val ACTIVE_RESET = longPreferencesKey("${prefix}active_reset_ms")
    val LAST_SAMPLE = longPreferencesKey("${prefix}last_sample_ms")
    val ALERT_FLAGS = stringSetPreferencesKey("${prefix}alert_flags")
    val PLAN_TYPE = stringPreferencesKey("${prefix}plan_type")
    val SESSION_WINDOW = booleanPreferencesKey("${prefix}session_window")
    val OPUS_REPORTED = booleanPreferencesKey("${prefix}opus_reported")
    val SONNET_REPORTED = booleanPreferencesKey("${prefix}sonnet_reported")
    /** Parsed [CodexAnalytics] (JSON), refreshed every 30 minutes for Codex accounts. */
    val CODEX_ANALYTICS = stringPreferencesKey("${prefix}codex_analytics")

    fun usage(p: Preferences) = UsageSnapshot(p[SESSION] ?: 0, p[WEEKLY] ?: 0, p[OPUS] ?: 0, p[SONNET] ?: 0,
        p[SESSION_RESET] ?: 0, p[WEEKLY_RESET] ?: 0, p[UPDATED] ?: 0, p[CONNECTION] ?: "idle", p[PLAN_TYPE] ?: "", p[SESSION_WINDOW] ?: true,
        p[OPUS_REPORTED] ?: false, p[SONNET_REPORTED] ?: false)

    fun clear(p: MutablePreferences) = listOf(PLAN, SESSION, WEEKLY, OPUS, SONNET, SESSION_RESET, WEEKLY_RESET, UPDATED, CONNECTION,
        PREV_SESSION, ACTIVE_START, ACTIVE_PEAK, ACTIVE_RESET, LAST_SAMPLE, ALERT_FLAGS, PLAN_TYPE, SESSION_WINDOW, OPUS_REPORTED, SONNET_REPORTED, CODEX_ANALYTICS).forEach { p.remove(it) }
}

data class AccountOverview(val account: Account, val usage: UsageSnapshot)

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepository(private val context: Context, private val db: SessionDatabase, private val credentials: CredentialStore) {
    /** The account being viewed: the only one that drives alerts, the live notification and widgets. */
    val activeAccountId: Flow<String> = combine(context.dataStore.data, credentials.accounts) { p, list ->
        p[Keys.ACTIVE_ACCOUNT]?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id ?: DEFAULT_ACCOUNT
    }.distinctUntilChanged()

    val usage = combine(context.dataStore.data, activeAccountId) { p, id -> AccountKeys(id).usage(p) }
    val accounts = combine(context.dataStore.data, credentials.accounts) { p, list -> list.map { AccountOverview(it, AccountKeys(it.id).usage(p)) } }
    val settings = combine(context.dataStore.data, activeAccountId) { p, id ->
        UserSettings(p[Keys.ROUTE] ?: "onboarding", p[AccountKeys(id).PLAN] ?: "pro",
            p[Keys.QUIET] ?: false, p[Keys.QUIET_START] ?: 22, p[Keys.QUIET_END] ?: 7,
            p[Keys.SESSION_ALERTS] ?: true, p[Keys.WEEKLY_ALERTS] ?: true,
            p[Keys.MODEL_ALERTS] ?: true, p[Keys.DIGEST] ?: true)
    }
    val sessions = activeAccountId.flatMapLatest { db.sessions().observeAll(it) }
    val codexAnalytics = combine(context.dataStore.data, activeAccountId) { p, id -> p[AccountKeys(id).CODEX_ANALYTICS]?.let(CodexAnalytics::fromJson) }.distinctUntilChanged()
    fun samplesSince(since: Long) = activeAccountId.flatMapLatest { db.samples().observeSince(it, since) }

    suspend fun activeId() = activeAccountId.first()
    suspend fun snapshot() = usage.first()
    suspend fun preferences() = context.dataStore.data.first()
    suspend fun setRoute(value: String) = context.dataStore.edit { it[Keys.ROUTE] = value }
    suspend fun setActive(id: String) = context.dataStore.edit { it[Keys.ACTIVE_ACCOUNT] = id }
    suspend fun setPlan(value: String) { val id = activeId(); context.dataStore.edit { it[AccountKeys(id).PLAN] = value } }
    suspend fun setBoolean(key: Preferences.Key<Boolean>, value: Boolean) = context.dataStore.edit { it[key] = value }
    suspend fun setQuietHours(start: Int, end: Int) = context.dataStore.edit { it[Keys.QUIET_START] = start; it[Keys.QUIET_END] = end }

    /** Drops everything stored for an account; with no accounts left the app returns to onboarding. */
    suspend fun forgetAccount(id: String, remaining: List<Account>) {
        db.sessions().clear(id); db.samples().clear(id)
        context.dataStore.edit {
            AccountKeys(id).clear(it)
            if (remaining.isEmpty()) { it.remove(Keys.ACTIVE_ACCOUNT); it[Keys.ROUTE] = "onboarding" } else it[Keys.ACTIVE_ACCOUNT] = remaining.first().id
        }
    }
}
