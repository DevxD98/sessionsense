# Releasing SessionSense

SessionSense is installed from an APK, not the Play Store. Each release is published to the public repo
[DevxD98/sessionsense](https://github.com/DevxD98/sessionsense), and the app updates itself from there.
There is no server: the app reads the latest release's `update.json` from GitHub.

## How the in-app updater works

- Each release carries two assets: `sessionsense-X.Y.Z.apk` and `update.json`. The app reads
  `https://github.com/DevxD98/sessionsense/releases/latest/download/update.json`, and that URL always
  points at the newest non-prerelease.
- The app checks when it starts (at most once every 6 hours) and in a daily background job. Both can be turned off
  under Settings → About → Check automatically. Each new version gets one notification on the "App updates"
  channel, which waits until quiet hours are over. Home shows an "Update available" pill until the update is
  installed or you skip that version.
- **Update now** downloads the APK over HTTPS, following redirects only within github.com and GitHub's asset
  hosts. The app checks the SHA-256 and size against `update.json`, and checks that the APK is SessionSense, is the
  promised version, and is **signed with the same certificate as the installed app**. Only then does it pass the
  APK to Android's installer. If any check fails, the file is deleted and nothing is installed.
- The update installs in place: accounts, history and settings stay. After it finishes, Android sends
  `MY_PACKAGE_REPLACED`, and `RestartReceiver` restarts the polling service.
- If the installed version is below `minSupportedVersionCode`, the app shows a blocking "Update required" screen.
- Only the `sideload` flavor contains the updater. The `play` flavor has no updater code and no
  `REQUEST_INSTALL_PACKAGES` permission, as Play policy requires.

## Versions

`version.properties` is the only place the version is set:

```properties
versionName=1.2.3                 # semver
lastReleasedVersionName=1.2.2     # written by scripts/release.sh after publishing
```

`versionCode` is derived from `versionName` as `major * 1_000_000 + minor * 1_000 + patch`, so 1.2.3 → 1002003.
A release build (`assemble*Release`) fails unless `versionName` is newer than `lastReleasedVersionName`. That
stops you from publishing an APK that can't install as an update. Debug builds aren't affected.

## Cutting a release

1. Commit everything. The script refuses to publish from a working tree with uncommitted changes.
2. Run it from the repository root:

   ```sh
   scripts/release.sh patch --notes $'- Fixed widget refresh\n- Faster startup'
   scripts/release.sh minor --notes-file notes.md
   scripts/release.sh 2.0.0 --min-supported 1.5.0   # older installs are blocked until they update
   scripts/release.sh patch --dry-run               # build + update.json only, publishes nothing
   ```

   The script bumps `versionName`, then runs `./gradlew testDebugUnitTest lintDebug assembleSideloadRelease`. It
   runs `apksigner verify`, computes the SHA-256, writes `build/release/vX.Y.Z/update.json` and prints a summary.
   Nothing is published until you **type the version number** to confirm, and it won't publish from a
   non-interactive shell. If the build fails or you don't confirm, `version.properties` is put back.
3. After publishing, the script records `lastReleasedVersionName` and checks that the stable URL serves the new
   version. Commit that bump:

   ```sh
   git add version.properties && git commit -m "release: vX.Y.Z" && git tag vX.Y.Z
   ```

App-store metadata (used by IzzyOnDroid, F-Droid and similar catalogs) lives in `fastlane/metadata/android/en-US/`.
With each release, add `changelogs/<versionCode>.txt` (plain text, at most 500 characters), and refresh the
screenshots or descriptions when the app changes noticeably.

Without `--min-supported`, the new release keeps the `minSupportedVersionCode` of the currently published one.

## Rolling back

Android can't install a lower versionCode over a higher one, so a bad release can't be "un-updated". Roll
**forward**:

1. Check out the last good commit, e.g. `git switch -c hotfix v1.4.0`, or revert the bad change.
2. Release it under a **higher** version: `scripts/release.sh 1.5.1 --notes "- Reverts 1.5.0"`.

To stop people installing a bad release before the fix is ready, delete `update.json` from that release, or the
whole release (`gh release delete vX.Y.Z --repo DevxD98/sessionsense`). The latest URL then points at
the previous release, which installed apps already have, so nothing more is offered.

## The keystore: back it up

`sessionsense-release.jks` and `key.properties` (both in the repository root, both git-ignored) sign every
release. **If the keystore or its passwords are lost, no future APK can update the installed app, ever.** Android
refuses an update signed with a different key. Every user would have to uninstall, and lose their local data,
to move to a newly signed app.

- Keep at least two copies off this machine, e.g. a password manager (as an attachment) plus an encrypted drive or
  cloud storage. Store the passwords separately from the file.
- Never commit either file, never paste their contents anywhere, and never put the passwords in CI logs.
- To check a backup, verify its certificate fingerprint matches the one the release script prints ("Signer"):
  `keytool -list -v -keystore <backup>.jks` (asks for the store password).

## One-time switch from debug to release builds

A phone that has run `./gradlew installDebug` has the **debug-signed** app. Release APKs are signed with the
release key, so:

- The updater will refuse them ("Signed by a different key"). This is correct, and Android would refuse them too.
- To move to release builds, the app must be **uninstalled once**. That deletes local history, settings and
  sign-ins; the Android Keystore keys that encrypt credentials can't be moved, so there's no way to carry them
  across. Afterwards you sign in again, and every later update installs in place with data kept.

```sh
adb uninstall com.sessionsense.session_sense         # deletes the app's data
adb install build/release/vX.Y.Z/sessionsense-X.Y.Z.apk
```

From then on, for day-to-day development on that phone, either install release-signed builds
(`./gradlew installSideloadRelease`) or accept another uninstall whenever you switch back to debug.

## Testing an update without touching "latest" (debug builds)

Debug builds can read `update.json` from any release on the releases repo, including a **prerelease**, which
`releases/latest` ignores. That lets you test the whole flow without offering anything to real installs:

```sh
gh release create test-1 --prerelease --repo DevxD98/sessionsense <apk> update.json
adb shell am broadcast -n com.sessionsense.session_sense/.UpdateDebugReceiver \
  -a com.sessionsense.DEBUG_UPDATE_MANIFEST \
  --es url https://github.com/DevxD98/sessionsense/releases/download/test-1/update.json
# and back to the real latest release:
adb shell am broadcast -n com.sessionsense.session_sense/.UpdateDebugReceiver -a com.sessionsense.DEBUG_UPDATE_MANIFEST
```

The URL still has to be HTTPS on GitHub, and the hash and signature checks still apply. Release builds don't
include this receiver.
