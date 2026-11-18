#!/usr/bin/env bash
# Builds CarHome.apk without Gradle/Android Studio.
# Needs (Ubuntu/Debian): aapt apksigner android-sdk-platform-23 dalvik-exchange zipalign + a JDK.
set -euo pipefail
cd "$(dirname "$0")"
SDK_JAR="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes"

# Version comes from git, so nobody has to bump it by hand:
#   versionCode = 100 + commits on HEAD   (always grows, so every build installs as an update)
#   versionName = <base from AndroidManifest.xml>.<commits>, e.g. 1.5.13 (-dev if uncommitted changes)
# Outside a git checkout the manifest values are used as they are.
BASE=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
VERSION_CODE=$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' AndroidManifest.xml)
VERSION_NAME="$BASE"
if COMMITS=$(git rev-list --count HEAD 2>/dev/null); then
  VERSION_CODE=$((100 + COMMITS))
  VERSION_NAME="$BASE.$COMMITS"
  git diff --quiet HEAD -- 2>/dev/null || VERSION_NAME="$VERSION_NAME-dev"
fi
printf 'VERSION_CODE=%s\nVERSION_NAME=%s\n' "$VERSION_CODE" "$VERSION_NAME" > "$OUT/version.env"
echo "Building CarHome $VERSION_NAME (versionCode $VERSION_CODE)"

aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$SDK_JAR"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$SDK_JAR" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')
dalvik-exchange --dex --output="$OUT/classes.dex" "$OUT/classes"
aapt package -f -M AndroidManifest.xml -S res -I "$SDK_JAR" -F "$OUT/unsigned.apk" \
  --replace-version --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
(cd "$OUT" && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Keep the same key so future builds install as updates over the old one.
# The password comes from KEYSTORE_PASSWORD (a CI secret) and defaults to "android".
export KS_PASS="${KEYSTORE_PASSWORD:-android}"
[ -f carhome.keystore ] || keytool -genkeypair -keystore carhome.keystore -alias carhome \
  -storepass "$KS_PASS" -keypass "$KS_PASS" -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=CarHome" >/dev/null 2>&1
apksigner sign --ks carhome.keystore --ks-pass env:KS_PASS --key-pass env:KS_PASS \
  --out CarHome.apk "$OUT/aligned.apk"
apksigner verify CarHome.apk

# Make sure the version really made it into the APK.
aapt dump badging CarHome.apk | grep -q "versionCode='$VERSION_CODE' versionName='$VERSION_NAME'" \
  || { echo "error: APK version is not $VERSION_CODE / $VERSION_NAME" >&2; exit 1; }
ls -lh CarHome.apk
