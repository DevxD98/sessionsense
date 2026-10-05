package com.sessionsense.session_sense

import android.content.Intent
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/** Launch action that opens the app on the update sheet (the "Update" notification action). */
const val ACTION_OPEN_UPDATE = "com.sessionsense.OPEN_UPDATE"

/**
 * update.json, attached to every GitHub release of DevxD98/sessionsense and read from the stable
 * releases/latest/download URL. [notes] is short markdown or plain lines.
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSupportedVersionCode: Int = 0,
    val notes: String = "",
    val publishedAt: String = "",
) {
    fun toJson(): String = JSONObject()
        .put("versionCode", versionCode).put("versionName", versionName).put("apkUrl", apkUrl).put("sha256", sha256)
        .put("sizeBytes", sizeBytes).put("minSupportedVersionCode", minSupportedVersionCode).put("notes", notes).put("publishedAt", publishedAt)
        .toString()
}

sealed interface ManifestResult {
    data class Ok(val manifest: UpdateManifest) : ManifestResult
    data class Invalid(val reason: String) : ManifestResult
}

/** Strict: anything that could point the installer at the wrong file is rejected rather than defaulted. */
object UpdateManifestParser {
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    fun parse(body: String): ManifestResult {
        val root = runCatching { JSONTokener(body).nextValue() as? JSONObject }.getOrNull() ?: return ManifestResult.Invalid("not a JSON object")
        fun missing(name: String) = ManifestResult.Invalid("missing or invalid \"$name\"")
        val code = int(root.opt("versionCode"))?.takeIf { it > 0 } ?: return missing("versionCode")
        val name = (root.opt("versionName") as? String)?.trim()?.takeIf { Semver.parse(it) != null } ?: return missing("versionName")
        val url = (root.opt("apkUrl") as? String)?.trim()?.takeIf { UpdateHosts.isAllowed(it) } ?: return missing("apkUrl")
        val sha = (root.opt("sha256") as? String)?.trim()?.takeIf { SHA256.matches(it) }?.lowercase() ?: return missing("sha256")
        val size = long(root.opt("sizeBytes"))?.takeIf { it > 0 } ?: return missing("sizeBytes")
        val min = if (root.has("minSupportedVersionCode")) int(root.opt("minSupportedVersionCode"))?.takeIf { it >= 0 } ?: return missing("minSupportedVersionCode") else 0
        if (Semver.parse(name)!!.code != code) return ManifestResult.Invalid("versionCode $code does not match versionName $name")
        val notes = (root.opt("notes") as? String).orEmpty().trim()
        val published = (root.opt("publishedAt") as? String).orEmpty().trim()
        return ManifestResult.Ok(UpdateManifest(code, name, url, sha, size, min, notes, published))
    }

    /** Whole numbers only: 3.5 or "3" is not a version code. */
    private fun int(v: Any?): Int? = long(v)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
    private fun long(v: Any?): Long? = when (v) {
        is Int -> v.toLong()
        is Long -> v
        is Double, is Float -> (v as Number).toDouble().takeIf { it % 1.0 == 0.0 }?.toLong()
        else -> null
    }
}

/** MAJOR.MINOR.PATCH; the versionCode is derived from it exactly as app/build.gradle does. */
data class Semver(val major: Int, val minor: Int, val patch: Int) : Comparable<Semver> {
    val code: Int get() = major * 1_000_000 + minor * 1_000 + patch
    override fun compareTo(other: Semver) = code.compareTo(other.code)
    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("^(\\d{1,4})\\.(\\d{1,3})\\.(\\d{1,3})$")
        fun parse(value: String): Semver? = PATTERN.matchEntire(value.trim())?.destructured?.let { (a, b, c) ->
            Semver(a.toInt(), b.toInt(), c.toInt()).takeIf { it.major <= 2_100 }
        }
    }
}

/**
 * Update downloads may only touch GitHub: the release page on github.com and the asset CDN it redirects to
 * (release-assets.githubusercontent.com today, objects.githubusercontent.com historically). HTTPS only.
 */
object UpdateHosts {
    val ALLOWED = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    fun isAllowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            uri.host?.lowercase() in ALLOWED
    }
}

/** What the app should do about an update, derived from what's installed and the latest known manifest. */
sealed interface UpdateStatus {
    data object UpToDate : UpdateStatus
    /** [skipped]: the user chose "Skip this version"; no pill or notification, but Settings still offers it. */
    data class Available(val manifest: UpdateManifest, val skipped: Boolean) : UpdateStatus
    /** The installed build is below minSupportedVersionCode: the app is blocked until it updates. */
    data class Required(val manifest: UpdateManifest) : UpdateStatus
}

object UpdatePolicy {
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    fun status(installedCode: Int, manifest: UpdateManifest?, skippedCode: Int): UpdateStatus = when {
        manifest == null || manifest.versionCode <= installedCode -> UpdateStatus.UpToDate
        installedCode < manifest.minSupportedVersionCode -> UpdateStatus.Required(manifest)
        else -> UpdateStatus.Available(manifest, skipped = manifest.versionCode == skippedCode)
    }

    /** Automatic checks run at most every six hours; a clock that jumped backwards counts as due. Manual checks always run. */
    fun shouldCheck(now: Long, lastCheckMs: Long, manual: Boolean = false) =
        manual || lastCheckMs <= 0 || now < lastCheckMs || now - lastCheckMs >= CHECK_INTERVAL_MS

    /** Notify once per version, never for a skipped one (a required update ignores skip), and not during quiet hours. */
    fun shouldNotify(status: UpdateStatus, notifiedCode: Int, quiet: Boolean): Boolean {
        val manifest = when (status) {
            is UpdateStatus.Required -> status.manifest
            is UpdateStatus.Available -> status.manifest.takeUnless { status.skipped }
            UpdateStatus.UpToDate -> null
        } ?: return false
        return !quiet && manifest.versionCode != notifiedCode
    }
}

/** Quiet hours as the alert settings define them: [start] until [end], wrapping past midnight when start > end. */
fun isQuietHour(enabled: Boolean, start: Int, end: Int, hour: Int): Boolean {
    if (!enabled || start == end) return false
    return if (start < end) hour in start until end else hour >= start || hour < end
}

// ─── What the UI sees ──────────────────────────────────────────────────────────────────────────────────

sealed interface CheckState {
    data object Idle : CheckState
    data object Checking : CheckState
    data object Done : CheckState
    data class Failed(val message: String) : CheckState
}

sealed interface InstallPhase {
    data object Idle : InstallPhase
    data class Downloading(val bytes: Long, val total: Long) : InstallPhase { val fraction get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f }
    data object Verifying : InstallPhase
    /** Android's "Install unknown apps" is off for SessionSense; the user has to allow it in Settings first. */
    data object NeedsPermission : InstallPhase
    /** The system install dialog is ready; the UI launches [intent] from the visible activity. */
    data class AwaitingConfirmation(val intent: Intent) : InstallPhase
    data object Installing : InstallPhase
    data class Failed(val title: String, val message: String) : InstallPhase
}

data class UpdateUiState(
    val installedCode: Int = 0,
    val installedName: String = "",
    val autoCheck: Boolean = true,
    val check: CheckState = CheckState.Idle,
    val lastCheckMs: Long = 0,
    val status: UpdateStatus = UpdateStatus.UpToDate,
    val phase: InstallPhase = InstallPhase.Idle,
) {
    /** A newer version the user hasn't skipped: drives the Home pill. */
    val pending get() = (status as? UpdateStatus.Available)?.takeUnless { it.skipped }?.manifest ?: (status as? UpdateStatus.Required)?.manifest
    /** The newest version on offer, skipped or not. */
    val offered get() = when (val s = status) { is UpdateStatus.Available -> s.manifest; is UpdateStatus.Required -> s.manifest; UpdateStatus.UpToDate -> null }
    val required get() = status is UpdateStatus.Required
    val busy get() = phase is InstallPhase.Downloading || phase == InstallPhase.Verifying || phase == InstallPhase.Installing || phase is InstallPhase.AwaitingConfirmation
}

/** The self-updater, present only in the sideload flavor (see UpdateFeature); the play flavor has none. */
interface UpdateController {
    val state: StateFlow<UpdateUiState>
    /** App start: tidy old downloads, then check if automatic checks are on and the last one is 6h+ old. */
    fun onAppStart()
    /** Every RecoveryWorker run: posts a pending notification that quiet hours held back. No network. */
    suspend fun onPeriodicTick()
    fun checkNow()
    fun setAutoCheck(enabled: Boolean)
    fun skip(versionCode: Int)
    /** Download, verify and hand the APK to the system installer (asks for "Install unknown apps" first if needed). */
    fun startUpdate()
    fun cancel()
    /** Clears a failed or cancelled attempt so the sheet returns to its offer. */
    fun dismissError()
    fun confirmationLaunched()
    fun openInstallPermissionSettings(): Intent
}
