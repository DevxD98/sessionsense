package com.sessionsense.session_sense

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** A downloaded update that must not be installed; [title] and [message] are shown to the user as-is. */
class UpdateRefused(val title: String, override val message: String) : Exception(message)

object ApkVerifier {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun checkHash(file: File, manifest: UpdateManifest) {
        if (file.length() != manifest.sizeBytes || sha256(file) != manifest.sha256) throw UpdateRefused("Download didn’t check out",
            "The file doesn’t match the fingerprint (SHA-256) published with version ${manifest.versionName}, so it was deleted and nothing was installed. It may have been corrupted on the way — try again.")
    }

    /**
     * The APK must be this app, the version the manifest promised, and signed by the same key as the installed app
     * (or a key the installed one rotated to). Android would refuse a mismatch too, but only after the user confirmed.
     */
    fun checkPackage(context: Context, file: File, manifest: UpdateManifest) {
        val pm = context.packageManager
        val archive = packageInfo(pm, context.packageName, file.path) ?: throw UpdateRefused("Not a valid app", "The downloaded file isn’t an installable Android app, so it was deleted.")
        if (archive.packageName != context.packageName) throw UpdateRefused("Wrong app", "The download is a different app (${archive.packageName}), so it was deleted and nothing was installed.")
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        if (archiveCode != manifest.versionCode.toLong()) throw UpdateRefused("Version mismatch", "The download is build $archiveCode, not ${manifest.versionCode} as published, so it was deleted.")
        val installed = packageInfo(pm, context.packageName, null) ?: throw UpdateRefused("Couldn’t verify", "SessionSense couldn’t read its own signature.")
        val theirs = signers(archive); val ours = signers(installed)
        if (theirs.current.isEmpty() || ours.current.isEmpty()) throw UpdateRefused("Couldn’t verify", "The download’s signature couldn’t be read, so it was deleted and nothing was installed.")
        if (theirs.current != ours.current && !theirs.history.containsAll(ours.current)) throw UpdateRefused("Signed by a different key",
            "This update is signed with a different certificate from the SessionSense on this phone, so Android can’t install it over this version. " +
                "That’s expected if this phone runs a debug build: install a release build once (see RELEASING.md). Otherwise, don’t install it.")
    }

    private class Signers(val current: Set<String>, val history: Set<String>)

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager, packageName: String, archivePath: String?): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return runCatching {
            when {
                archivePath == null && Build.VERSION.SDK_INT >= 33 -> pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
                archivePath == null -> pm.getPackageInfo(packageName, flags)
                Build.VERSION.SDK_INT >= 33 -> pm.getPackageArchiveInfo(archivePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
                else -> pm.getPackageArchiveInfo(archivePath, flags)
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Signers {
        fun fp(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let { si ->
            if (si.hasMultipleSigners()) return si.apkContentsSigners.map { fp(it.toByteArray()) }.toSet().let { Signers(it, it) }
            val history = si.signingCertificateHistory.orEmpty().map { fp(it.toByteArray()) }
            if (history.isNotEmpty()) return Signers(setOf(history.last()), history.toSet())
        }
        val legacy = info.signatures.orEmpty().map { fp(it.toByteArray()) }.toSet()
        return Signers(legacy, legacy)
    }
}

/** How a PackageInstaller result reads to the user; null for success and for "waiting on the user". */
object InstallOutcome {
    data class Failure(val title: String, val message: String, val cancelled: Boolean = false)

    fun of(status: Int, detail: String?): Failure? {
        val why = detail?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return when (status) {
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_PENDING_USER_ACTION -> null
            PackageInstaller.STATUS_FAILURE_ABORTED -> Failure("Update cancelled", "Nothing was installed. You can update any time from Settings.", cancelled = true)
            PackageInstaller.STATUS_FAILURE_CONFLICT -> Failure("Signature conflict",
                "Android refused the update because it conflicts with the installed app — usually a different signing key or a lower version.$why")
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> Failure("Not compatible", "This version can’t run on this phone.$why")
            PackageInstaller.STATUS_FAILURE_STORAGE -> Failure("Not enough space", "Free up some storage and try again.$why")
            PackageInstaller.STATUS_FAILURE_INVALID -> Failure("Invalid update", "Android says the downloaded app is invalid.$why")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> Failure("Install blocked", "Something on this phone blocked the install, such as a device policy.$why")
            else -> Failure("Update failed", "Android couldn’t install the update.$why")
        }
    }
}
