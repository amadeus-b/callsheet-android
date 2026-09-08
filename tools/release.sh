#!/usr/bin/env bash
#
# Baut ein Release und veröffentlicht es auf GitHub.
#
#   tools/release.sh patch     1.0.0 -> 1.0.1   (Standard)
#   tools/release.sh minor     1.0.0 -> 1.1.0
#   tools/release.sh major     1.0.0 -> 2.0.0
#   tools/release.sh 1.4.2     genau diese Version
#
# Das Skript bricht ab, bevor es etwas veröffentlicht, wenn Tests scheitern,
# das Arbeitsverzeichnis nicht sauber ist oder der CHANGELOG-Abschnitt zur
# neuen Version fehlt. Der versionCode steigt bei jedem Release um eins — ohne
# das erkennt weder Android noch Obtainium ein Update.
set -euo pipefail

cd "$(dirname "$0")/.."

REPO_APK_NAME="callsheet"
VERSION_FILE="version.properties"
CHANGELOG="CHANGELOG.md"

fail() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
step() { printf '\n\033[36m==> %s\033[0m\n' "$*"; }

# --- Vorbedingungen ---------------------------------------------------------

command -v gh >/dev/null || fail "gh fehlt. Ohne GitHub-CLI kein Release."
gh auth status >/dev/null 2>&1 || fail "gh ist nicht angemeldet: gh auth login"
[ -f keystore.properties ] || fail "keystore.properties fehlt — der Build würde mit dem Debug-Schlüssel signieren."

if [ -n "$(git status --porcelain)" ]; then
    git status --short
    fail "Arbeitsverzeichnis nicht sauber. Erst committen, dann veröffentlichen."
fi

CURRENT_VERSION=$(grep '^versionName=' "$VERSION_FILE" | cut -d= -f2)
CURRENT_CODE=$(grep '^versionCode=' "$VERSION_FILE" | cut -d= -f2)

# --- Neue Version bestimmen -------------------------------------------------

IFS=. read -r MAJOR MINOR PATCH <<<"$CURRENT_VERSION"
case "${1:-patch}" in
    major) NEW_VERSION="$((MAJOR + 1)).0.0" ;;
    minor) NEW_VERSION="$MAJOR.$((MINOR + 1)).0" ;;
    patch) NEW_VERSION="$MAJOR.$MINOR.$((PATCH + 1))" ;;
    [0-9]*.[0-9]*.[0-9]*) NEW_VERSION="$1" ;;
    *) fail "Unbekanntes Argument: $1 (erwartet: major, minor, patch oder x.y.z)" ;;
esac
NEW_CODE=$((CURRENT_CODE + 1))
TAG="v$NEW_VERSION"

git rev-parse "$TAG" >/dev/null 2>&1 && fail "Tag $TAG existiert schon."

# --- Release-Notes aus dem CHANGELOG ----------------------------------------

# Der Abschnitt wird vor dem Release von Hand geschrieben und dient als Text
# des GitHub-Releases. Ohne ihn erschiene dort nur ein nackter Versionssprung.
[ -f "$CHANGELOG" ] || fail "$CHANGELOG fehlt."

NOTES=$(awk -v version="$NEW_VERSION" '
    $1 == "##" && $2 == version { inside = 1; next }
    inside && $1 == "##" { exit }
    inside { print }
' "$CHANGELOG" | sed -e '/./,$!d')

[ -n "$NOTES" ] || fail "In $CHANGELOG fehlt ein Abschnitt \"## $NEW_VERSION\". Erst aufschreiben, was sich geändert hat."

NOTES_FILE=$(mktemp)
trap 'rm -f "$NOTES_FILE"' EXIT
printf '%s\n' "$NOTES" > "$NOTES_FILE"

step "$CURRENT_VERSION ($CURRENT_CODE) → $NEW_VERSION ($NEW_CODE)"
printf '\nRelease-Notes:\n%s\n' "$NOTES"

# --- Tests --------------------------------------------------------------------

step "Tests"
./gradlew --quiet testDebugUnitTest || fail "Tests rot. Kein Release."

# --- Bauen ------------------------------------------------------------------

step "Version schreiben"
cat > "$VERSION_FILE" <<EOF
# Erhöht das Release-Skript. versionCode muss bei jedem Release steigen,
# sonst erkennt weder Android noch Obtainium ein Update.
versionCode=$NEW_CODE
versionName=$NEW_VERSION
EOF

step "Release-Build"
./gradlew --quiet assembleRelease || { git checkout -- "$VERSION_FILE"; fail "Build fehlgeschlagen."; }

BUILT="app/build/outputs/apk/release/app-release.apk"
[ -f "$BUILT" ] || { git checkout -- "$VERSION_FILE"; fail "APK nicht gefunden: $BUILT"; }

APK="app/build/outputs/apk/release/$REPO_APK_NAME-$NEW_VERSION.apk"
cp "$BUILT" "$APK"

# Die Signatur muss über alle Releases dieselbe bleiben, sonst verweigert
# Android das Update. Deshalb den Fingerprint bei jedem Release zeigen.
step "Signatur"
APKSIGNER=$(ls "${ANDROID_HOME:-$HOME/android-sdk}"/build-tools/*/apksigner 2>/dev/null | tail -1)
if [ -n "$APKSIGNER" ]; then
    "$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | grep 'SHA-256 digest' || true
fi

# --- Veröffentlichen --------------------------------------------------------

step "Commit und Tag"
git add "$VERSION_FILE"
git commit -m "Release $NEW_VERSION"
git tag -a "$TAG" -m "Callsheet $NEW_VERSION"
git push origin HEAD --tags

step "GitHub-Release"
gh release create "$TAG" "$APK" \
    --title "Callsheet $NEW_VERSION" \
    --notes-file "$NOTES_FILE"

printf '\n\033[32mVeröffentlicht: %s\033[0m\n' "$TAG"
printf 'Obtainium meldet die neue Version beim nächsten Prüflauf.\n'
