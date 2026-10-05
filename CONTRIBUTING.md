# Contributing to SessionSense

Thanks for helping. Bug reports, small fixes and well-scoped features are all welcome.

## Before you start

- **Bugs:** open an issue with the bug template. Include your app version (Settings → About), Android version,
  phone model, and which accounts you use (Claude Pro/Team/Max, Codex). Screenshots help a lot.
- **Features:** open an issue first, so we can agree on the shape before you spend time on it.
- **Security problems:** don't open an issue. Follow [SECURITY.md](SECURITY.md).

## Setup

You need JDK 17 and the Android SDK. Open the repository root in Android Studio, or use the command line:

```sh
./gradlew installDebug                   # build the sideload debug flavor and install it on a connected phone
./gradlew testDebugUnitTest lintDebug    # unit tests and lint for both flavors; CI runs this on every PR
```

No keystore or other secrets are needed for debug builds. Never commit `key.properties`, `*.jks` or
`local.properties`; they're git-ignored.

## How the code is laid out

- `app/src/main/kotlin/com/sessionsense/session_sense/`: the app. `UsagePollingService` is the single owner of
  polling, session transitions, history writes, alerts and widget updates. The UI (`AppViewModel` and Compose) only
  observes Room and DataStore.
- `app/src/sideload/` and `app/src/play/`: per-flavor code. Only `sideload` contains the self-updater.
- `app/src/test/`: JVM unit tests. Logic that decides something (parsing, policies, transitions) should have one.
- `scripts/release.sh` and [RELEASING.md](RELEASING.md): how releases are cut. Contributors never need these.

## Pull requests

- Keep each PR to one change, and explain the user-visible effect in the description.
- Add or update unit tests for changed logic, and make sure `./gradlew testDebugUnitTest lintDebug` passes.
- Match the surrounding code style. Commit messages say what changed for the user, in the imperative
  ("Widgets: scale legend text only as far as it fits").
- UI changes: include before/after screenshots.
- Don't bump `version.properties`. That happens at release time.

## Forks and your own builds

You're free to fork and ship your own build under the MIT license. Two things keep your build from clashing with the
official app:

1. Change `applicationId` in `app/build.gradle`, so your app installs alongside SessionSense instead of conflicting
   with it.
2. Point the updater at your own repo with `-PupdateRepo=you/yourfork` (or `updateRepo=you/yourfork` in
   `gradle.properties`), or ship the `play` flavor, which has no updater.

Please don't use the SessionSense name or icon for a build you distribute. The updater verifies signing
certificates, so a fork's APK can never replace an installed official copy, and the reverse is also true.
