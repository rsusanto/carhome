#!/usr/bin/env bash
# Builds CarHome.apk without Gradle/Android Studio.
# Needs (Ubuntu/Debian): aapt apksigner android-sdk-platform-23 dalvik-exchange zipalign + a JDK.
set -euo pipefail
cd "$(dirname "$0")"
SDK_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes"

aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$SDK_JAR"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$SDK_JAR" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')
dalvik-exchange --dex --output="$OUT/classes.dex" "$OUT/classes"
aapt package -f -M AndroidManifest.xml -S res -I "$SDK_JAR" -F "$OUT/unsigned.apk"
(cd "$OUT" && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Keep the same key so future builds install as updates over the old one.
[ -f carhome.keystore ] || keytool -genkeypair -keystore carhome.keystore -alias carhome \
  -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=CarHome" >/dev/null 2>&1
apksigner sign --ks carhome.keystore --ks-pass pass:android --key-pass pass:android \
  --out CarHome.apk "$OUT/aligned.apk"
apksigner verify CarHome.apk && ls -lh CarHome.apk
