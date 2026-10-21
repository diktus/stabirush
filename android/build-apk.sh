#!/usr/bin/env bash
set -euo pipefail

SDK_DIR="/home/ubuntu/android-sdk"
BUILD_TOOLS="$SDK_DIR/build-tools/34.0.0"
ANDROID_JAR="$SDK_DIR/platforms/android-34/android.jar"
KOTLINC="/opt/kotlinc/bin/kotlinc"
KOTLIN_LIB="/opt/kotlinc/lib/kotlin-stdlib.jar"

PROJECT_DIR="/home/wireguard-ui/android"
MAIN_DIR="$PROJECT_DIR/app/src/main"
BUILD_DIR="$PROJECT_DIR/build"
OUT_APK="/home/wireguard-ui/web/static/stabirush-android.apk"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/obj" "$BUILD_DIR/dex" "$MAIN_DIR/res/drawable"

# Copy Connection Logo icon
cp /home/wireguard-ui/extension/icons/icon-128.png "$MAIN_DIR/res/drawable/ic_launcher.png"

echo "[1/5] Compiling resources with aapt2..."
"$BUILD_TOOLS/aapt2" compile --dir "$MAIN_DIR/res" -o "$BUILD_DIR/resources.zip"

"$BUILD_TOOLS/aapt2" link \
  -I "$ANDROID_JAR" \
  --manifest "$MAIN_DIR/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  -o "$BUILD_DIR/unaligned.apk" \
  "$BUILD_DIR/resources.zip"

echo "[2/5] Compiling Kotlin sources with kotlinc..."
KT_SOURCES=$(find "$MAIN_DIR/kotlin" -name "*.kt")
GEN_SOURCES=$(find "$BUILD_DIR/gen" -name "*.java" 2>/dev/null || true)
"$KOTLINC" \
  -cp "$ANDROID_JAR" \
  -jvm-target 1.8 \
  -d "$BUILD_DIR/obj" \
  $KT_SOURCES $GEN_SOURCES

echo "[3/5] Converting bytecode to DEX with d8..."
CLASS_FILES=$(find "$BUILD_DIR/obj" -name "*.class")
"$BUILD_TOOLS/d8" \
  --lib "$ANDROID_JAR" \
  --min-api 26 \
  --output "$BUILD_DIR/dex" \
  "$KOTLIN_LIB" \
  $CLASS_FILES

# Add classes.dex to the APK
(cd "$BUILD_DIR/dex" && zip -q "$BUILD_DIR/unaligned.apk" classes*.dex)

# Add native libraries (libwg-go.so) to the APK
echo "[3.5/5] Packaging native libraries (arm64-v8a)..."
mkdir -p "$BUILD_DIR/apk_root/lib/arm64-v8a"
if [ -d "$MAIN_DIR/jniLibs/arm64-v8a" ]; then
  cp "$MAIN_DIR/jniLibs/arm64-v8a"/*.so "$BUILD_DIR/apk_root/lib/arm64-v8a/"
  (cd "$BUILD_DIR/apk_root" && zip -r -q "$BUILD_DIR/unaligned.apk" lib)
fi

echo "[4/5] Aligning APK with zipalign..."
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"

echo "[5/5] Signing APK with apksigner..."
KEYSTORE="$PROJECT_DIR/stabirush-release.jks"
KS_PASS="${STABIRUSH_KS_PASS:-android}"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -alias stabirush \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$KS_PASS" \
    -keypass "$KS_PASS" \
    -dname "CN=Stabirush VPN, OU=Network, O=Stabirush, L=Jakarta, S=ID, C=ID"
fi

"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias stabirush \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  --out "$OUT_APK" \
  "$BUILD_DIR/aligned.apk"

"$BUILD_TOOLS/apksigner" verify -v "$OUT_APK" | head -n 10
ls -lh "$OUT_APK"
echo "Successfully built native Android APK with Kotlin: $OUT_APK"
