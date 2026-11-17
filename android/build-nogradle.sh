#!/usr/bin/env bash
# Сборка APK без Gradle и Android SDK — только пакеты Ubuntu/Debian:
#   apt install aapt apksigner zipalign dalvik-exchange libzxing-core-java default-jdk
# плюс kotlinc (KOTLINC) и android.jar платформы 34 (ANDROID_JAR).
# Основной способ сборки — Gradle (см. README); этот нужен там, где SDK недоступен.
set -euo pipefail
cd "$(dirname "$0")"
: "${KOTLINC:=kotlinc}" "${ANDROID_JAR:?укажи путь к android.jar (API 34)}"
KOTLIN_STDLIB="${KOTLIN_STDLIB:-$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/../lib/kotlin-stdlib.jar}"
ZXING=/usr/share/java/core.jar
PKG=dev.smoke.wavecast
VC=$(grep -oP 'versionCode = \K\d+' app/build.gradle.kts); VN=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
B=build-nogradle; rm -rf "$B"; mkdir -p "$B"/{res,gen,classes,assets,dex}

sed "s|<manifest |<manifest package=\"$PKG\" |" app/src/main/AndroidManifest.xml > "$B/AndroidManifest.xml"
cp ../relay/wavecast/static/* ../p2p/host/* "$B/assets/"
aapt2 compile --dir app/src/main/res -o "$B/res.zip"
aapt2 link -o "$B/base.apk" -I "$ANDROID_JAR" --manifest "$B/AndroidManifest.xml" -A "$B/assets" --java "$B/gen" \
  --min-sdk-version 29 --target-sdk-version 34 --version-code "$VC" --version-name "$VN" "$B/res.zip"

javac -nowarn --release 8 -cp "$ANDROID_JAR" -d "$B/classes" $(find "$B/gen" -name '*.java') 2>/dev/null
"$KOTLINC" -nowarn -jvm-target 1.8 -Xsam-conversions=class -Xlambdas=class -no-stdlib \
  -cp "$ANDROID_JAR:$KOTLIN_STDLIB:$ZXING:$B/classes" -d "$B/classes" $(find app/src/main/java -name '*.kt')

# dx не понимает module-info и multi-release — убираем их из библиотек
for j in "$KOTLIN_STDLIB" "$ZXING"; do
  n="$B/$(basename "$j")"; cp "$j" "$n"; zip -qd "$n" 'META-INF/versions/*' 'module-info.class' >/dev/null 2>&1 || true
done
dalvik-exchange --dex --min-sdk-version=29 --output="$B/dex/classes.dex" "$B/classes" "$B/$(basename "$KOTLIN_STDLIB")" "$B/$(basename "$ZXING")"

(cd "$B/dex" && zip -qj ../base.apk classes.dex)
zipalign -f 4 "$B/base.apk" "$B/aligned.apk"
apksigner sign --ks app/wavecast.keystore --ks-pass pass:wavecast --key-pass pass:wavecast --ks-key-alias wavecast \
  --out "wavecast-$VN.apk" "$B/aligned.apk"
apksigner verify --print-certs "wavecast-$VN.apk" | head -1
echo "готово: $(pwd)/wavecast-$VN.apk"
