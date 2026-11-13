#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# What is inside a built release APK, read from the APK itself (the minified, shrunk one that ships), not from the
# source: not debuggable, no debug receiver or other src/debug part, and exactly the permissions ManifestTest pins.
# release.yml runs it before anything is published; run it by hand on any APK:  tools/check-apk.sh path/to/app.apk
set -euo pipefail
APK=${1:?usage: tools/check-apk.sh <apk>}
APK=$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")  # a relative path means the caller's folder, not the repo root
cd "$(dirname "$0")/.."
AAPT2=${AAPT2:-"${ANDROID_HOME:?set ANDROID_HOME or AAPT2}/build-tools/36.0.0/aapt2"}
TREE=$(mktemp); trap 'rm -f "$TREE"' EXIT
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" > "$TREE"
test -s "$TREE" || { echo "::error::could not read the manifest of $APK"; exit 1; }
bad=0
fail() { echo "::error::$1"; bad=1; }

# Not debuggable. (A release build leaves the attribute out; an explicit false is fine too.)
if grep -E ':debuggable\(.*\)=(true|0xffffffff|\(type 0x12\)0xffffffff)' "$TREE" > /dev/null; then fail "the APK is debuggable"; fi

# No part from src/debug (the adb test hooks) and no library's preview or test activity.
PARTS=$(awk '/^ *E: (activity|activity-alias|service|receiver|provider)/ { p = 1; next } p && /:name\(/ { sub(/.*="/, ""); sub(/".*/, ""); print; p = 0 }' "$TREE")
if grep -E '\.util\.|DebugReceiver|BarFlipActivity|PreviewActivity|ComponentActivity|\.test\.' <<< "$PARTS" > /dev/null; then
  fail "the APK has a debug or test component: $(grep -E '\.util\.|DebugReceiver|BarFlipActivity|PreviewActivity|ComponentActivity|\.test\.' <<< "$PARTS" | tr '\n' ' ')"
fi

# Permissions: the set ManifestTest pins, plus the one AndroidX adds for receivers registered in code.
WANT=$(sed -n '/fun thePermissionsAreTheseAndNoOthers/,/toSet()/p' app/src/test/java/com/meridian11/books/bentobar/ManifestTest.kt | grep -o 'android\.permission\.[A-Z_]*' | sort -u)
test "$(wc -l <<< "$WANT")" -ge 5 || { echo "::error::could not read the permissions from ManifestTest.kt"; exit 1; }
HAVE=$(awk '/^ *E: uses-permission/ { p = 1; next } p && /:name\(/ { sub(/.*="/, ""); sub(/".*/, ""); print; p = 0 }' "$TREE" | grep -v '\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$' | sort -u)
if [ "$WANT" != "$HAVE" ]; then
  fail "the APK's permissions differ from ManifestTest's: $(diff <(echo "$WANT") <(echo "$HAVE") | grep '^[<>]' | tr '\n' ' ')"
fi
[ "$bad" = 0 ] && echo "$APK: not debuggable, no debug components, $(wc -l <<< "$HAVE" | tr -d ' ') permissions as pinned"
exit "$bad"
