#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="/home/wireguard-ui/android"
OUT_APK="/home/wireguard-ui/web/static/stabirush-android.apk"

cd "$PROJECT_DIR"

# Ensure icon exists
cp -f /home/wireguard-ui/extension/icons/icon-128.png "$PROJECT_DIR/app/src/main/res/drawable/ic_launcher.png"

echo "[1/2] Building Jetpack Compose Android APK with Gradle..."
./gradlew assembleRelease --no-daemon

echo "[2/2] Deploying signed APK to Web UI directory..."
RELEASE_APK=$(find "$PROJECT_DIR/app/build/outputs/apk/release" -name "*.apk" | head -n 1)
cp -f "$RELEASE_APK" "$OUT_APK"

ls -lh "$OUT_APK"
echo "Successfully built and deployed Jetpack Compose APK: $OUT_APK"
