#!/usr/bin/env bash
# Cut a SessionSense release: bump the version, test, lint, build the signed sideload APK, write update.json and
# publish both to GitHub Releases on DevxD98/sessionsense-releases. Nothing is published without typing the version.
#
#   scripts/release.sh <patch|minor|major|X.Y.Z> [--notes "text" | --notes-file FILE] [--min-supported X.Y.Z] [--dry-run]
#
#   --notes / --notes-file  Release notes (short markdown). Without either, $EDITOR opens to write them.
#   --min-supported X.Y.Z   Oldest version still allowed to run; older installs get the blocking "Update required"
#                           screen. Defaults to the value in the currently published update.json (or none).
#   --dry-run               Build and write update.json, but don't publish.
#
# See RELEASING.md.
set -euo pipefail

REPO="DevxD98/sessionsense-releases"
LATEST_MANIFEST="https://github.com/$REPO/releases/latest/download/update.json"
cd "$(dirname "$0")/.."   # session_sense/android

die() { echo "error: $*" >&2; exit 1; }
prop() { sed -n "s/^$1=//p" version.properties; }
code() { local IFS=.; read -r a b c <<<"$1"; echo $((a * 1000000 + b * 1000 + c)); }
valid() { [[ "$1" =~ ^[0-9]{1,4}\.[0-9]{1,3}\.[0-9]{1,3}$ ]]; }

bump=""; notes=""; notes_file=""; min_supported=""; dry_run=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --notes) notes="$2"; shift 2 ;;
    --notes-file) notes_file="$2"; shift 2 ;;
    --min-supported) min_supported="$2"; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) [[ -z "$bump" ]] || die "one version argument only"; bump="$1"; shift ;;
  esac
done
[[ -n "$bump" ]] || die "usage: scripts/release.sh <patch|minor|major|X.Y.Z> [--notes ... | --notes-file FILE] [--min-supported X.Y.Z] [--dry-run]"

command -v gh >/dev/null || die "gh (GitHub CLI) is required"
command -v python3 >/dev/null || die "python3 is required"
[[ -f key.properties ]] || die "key.properties is missing: release builds need the release keystore (see RELEASING.md)"
$dry_run || [[ -z "$(git status --porcelain)" ]] || die "the working tree has uncommitted changes; commit them so the release matches a commit"

current=$(prop versionName); last=$(prop lastReleasedVersionName); last=${last:-0.0.0}
valid "$current" || die "version.properties: bad versionName '$current'"
IFS=. read -r major minor patch <<<"$current"
case "$bump" in
  patch) new="$major.$minor.$((patch + 1))" ;;
  minor) new="$major.$((minor + 1)).0" ;;
  major) new="$((major + 1)).0.0" ;;
  *) new="$bump" ;;
esac
valid "$new" || die "'$new' is not MAJOR.MINOR.PATCH"
[[ $(code "$new") -gt $(code "$last") ]] || die "$new is not newer than the last release ($last); Android can't install an older versionCode over a newer one"

if [[ -n "$min_supported" ]]; then
  valid "$min_supported" || die "--min-supported '$min_supported' is not MAJOR.MINOR.PATCH"
  min_code=$(code "$min_supported")
  [[ $min_code -le $(code "$new") ]] || die "--min-supported can't be newer than the release itself"
else
  min_code=$(curl -fsSL --max-time 15 "$LATEST_MANIFEST" 2>/dev/null | python3 -c 'import json,sys; print(int(json.load(sys.stdin).get("minSupportedVersionCode", 0)))' 2>/dev/null || echo 0)
fi

# Clean before anything is written under build/ (release output lives there): lint can crash on kapt stubs left
# over from an earlier build of the other flavor.
./gradlew --quiet clean
out="build/release/v$new"; rm -rf "$out"; mkdir -p "$out"
if [[ -n "$notes_file" ]]; then cp "$notes_file" "$out/notes.md"
elif [[ -n "$notes" ]]; then printf '%s\n' "$notes" > "$out/notes.md"
else
  printf '# Write the release notes for %s (short markdown). Lines starting with # are headings.\n- \n' "$new" > "$out/notes.md"
  "${EDITOR:-vi}" "$out/notes.md" </dev/tty >/dev/tty
fi
[[ -n "$(grep -v '^[[:space:]]*-*[[:space:]]*$' "$out/notes.md" | grep -v '^# Write the release notes' || true)" ]] || die "release notes are empty"
sed -i '' '/^# Write the release notes/d' "$out/notes.md" 2>/dev/null || sed -i '/^# Write the release notes/d' "$out/notes.md"

# Bump, and put the old version back on any exit that isn't a completed publish (failure, dry run, no confirmation).
# The backup lives outside build/, so nothing a build does can take it away.
backup=$(mktemp -t sessionsense-version); cp version.properties "$backup"
published=false
restore() { $published || { cp "$backup" version.properties; echo "version.properties restored to $current" >&2; }; }
trap restore EXIT
sed -i '' "s/^versionName=.*/versionName=$new/" version.properties 2>/dev/null || sed -i "s/^versionName=.*/versionName=$new/" version.properties
echo "==> Building SessionSense $new (versionCode $(code "$new"))"

./gradlew --quiet testDebugUnitTest lintDebug assembleSideloadRelease

apk_src="app/build/outputs/apk/sideload/release/app-sideload-release.apk"
[[ -f "$apk_src" ]] || die "build produced no APK at $apk_src"
apk="$out/sessionsense-$new.apk"; cp "$apk_src" "$apk"
# Same file under a fixed name, so releases/latest/download/sessionsense.apk is a stable download link (README).
cp "$apk" "$out/sessionsense.apk"
sha=$(shasum -a 256 "$apk" | cut -d' ' -f1)
size=$(wc -c <"$apk" | tr -d ' ')
apk_url="https://github.com/$REPO/releases/download/v$new/sessionsense-$new.apk"

signer="(apksigner not found)"
sdk=$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null || true)
apksigner=$(ls -d "${sdk:-/nonexistent}"/build-tools/*/apksigner 2>/dev/null | tail -1 || true)
if [[ -n "$apksigner" ]]; then
  "$apksigner" verify "$apk" || die "apksigner rejected the APK"
  signer=$("$apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
fi

NEW="$new" CODE="$(code "$new")" URL="$apk_url" SHA="$sha" SIZE="$size" MIN="$min_code" NOTES_FILE="$out/notes.md" python3 - >"$out/update.json" <<'PY'
import json, os, datetime
print(json.dumps({
    "versionCode": int(os.environ["CODE"]),
    "versionName": os.environ["NEW"],
    "apkUrl": os.environ["URL"],
    "sha256": os.environ["SHA"],
    "sizeBytes": int(os.environ["SIZE"]),
    "minSupportedVersionCode": int(os.environ["MIN"]),
    "notes": open(os.environ["NOTES_FILE"]).read().strip(),
    "publishedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
}, indent=2))
PY

cat <<EOF

  Release      v$new  (versionCode $(code "$new"))
  Repository   $REPO
  APK          $apk  ($size bytes)
  SHA-256      $sha
  Signer       $signer
  Min version  $min_code
  Notes
$(sed 's/^/    /' "$out/notes.md")

EOF

if $dry_run; then echo "Dry run: nothing published. update.json is in $out/"; exit 0; fi

[[ -t 0 ]] || die "publishing needs an interactive terminal to confirm"
read -r -p "Publish v$new to $REPO? Type the version ($new) to confirm: " answer
[[ "$answer" == "$new" ]] || { echo "Not published."; exit 1; }

gh release create "v$new" "$apk" "$out/sessionsense.apk" "$out/update.json" --repo "$REPO" --title "SessionSense $new" --notes-file "$out/notes.md" --latest
published=true

sed -i '' "s/^lastReleasedVersionName=.*/lastReleasedVersionName=$new/" version.properties 2>/dev/null || sed -i "s/^lastReleasedVersionName=.*/lastReleasedVersionName=$new/" version.properties
echo "==> Published. Checking the stable manifest URL…"
sleep 3
live=$(curl -fsSL --max-time 20 "$LATEST_MANIFEST" | python3 -c 'import json,sys; print(json.load(sys.stdin)["versionName"])' || echo "?")
echo "    $LATEST_MANIFEST -> $live"
cat <<EOF

Done. Now commit the version bump so the next build knows v$new is out:
  git add version.properties && git commit -m "release: v$new" && git tag v$new
EOF
