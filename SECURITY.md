# Security policy

SessionSense holds sign-in material for people's Claude and ChatGPT accounts and installs its own updates, so
security reports are taken seriously.

## Reporting a vulnerability

**Please don't open a public issue.** Report it privately through
[GitHub's private vulnerability reporting](https://github.com/DevxD98/sessionsense/security/advisories/new).
Include what you found, how to reproduce it, and the app version.

You'll get a reply within a few days. Fixes ship as a normal release through the in-app updater, and you'll be
credited in the release notes unless you'd rather not be.

## What's in scope

- **Credential storage:** session cookies and tokens leaking off the device, or being readable by other apps.
  They're meant to be stored only on the device, encrypted with the Android Keystore.
- **The login WebView bridge:** anything that lets a page other than the provider's own main frame pass data to the
  app.
- **The updater's verification chain:** anything that gets an APK installed that isn't from this repo's releases,
  doesn't match the SHA-256 in `update.json`, or isn't signed with the release certificate.
- **Network:** usage requests going anywhere other than claude.ai / chatgpt.com, or update requests going anywhere
  other than GitHub.

Out of scope: problems that need a rooted phone or physical access to an unlocked device, and changes on the
providers' side that stop usage from loading (open a normal issue for those).

## Supported versions

Only the latest release gets fixes. The app updates itself, so staying current is one tap.
