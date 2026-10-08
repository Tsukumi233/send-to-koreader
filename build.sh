#!/bin/sh
# Builds send-to-koreader.apk with the bare SDK tools (no Gradle).
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-37.0/android.jar"
VERSION_CODE=1
VERSION_NAME=1.0

rm -rf build && mkdir -p build/res build/gen build/classes build/dex
"$BT/aapt2.exe" compile --dir res -o build/res/
"$BT/aapt2.exe" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 29 --target-sdk-version 35 \
    --version-code $VERSION_CODE --version-name $VERSION_NAME \
    --java build/gen build/res/*.flat
javac -nowarn -source 17 -target 17 -encoding UTF-8 -classpath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java') 2>&1 | grep -v "bootstrap classpath\|^1 warning" || true
"$BT/d8.bat" --release --min-api 29 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && jar uf ../unsigned.apk classes.dex)
"$BT/zipalign.exe" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner.bat" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
    --key-pass pass:android --out send-to-koreader.apk build/aligned.apk
"$BT/apksigner.bat" verify send-to-koreader.apk && ls -la send-to-koreader.apk
