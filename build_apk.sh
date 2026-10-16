#!/bin/bash
set -e

SDK_ROOT="/usr/local/share/android-commandlinetools"
BUILD_TOOLS="$SDK_ROOT/build-tools/28.0.3"
PLATFORM="$SDK_ROOT/platforms/android-28"
PROJECT_DIR="/Users/tao7/Projects/NasTvRemote"

cd "$PROJECT_DIR"
rm -rf build
mkdir -p build/classes build/gen

echo "[1/6] Generating R.java via aapt..."
"$BUILD_TOOLS/aapt" package -f -m \
    -J build/gen \
    -M app/src/main/AndroidManifest.xml \
    -S app/src/main/res \
    -I "$PLATFORM/android.jar"

echo "[2/6] Compiling Java code via javac..."
javac -source 1.8 -target 1.8 -d build/classes \
    -cp "$PLATFORM/android.jar" \
    build/gen/com/tao7/nasremote/R.java \
    app/src/main/java/com/tao7/nasremote/*.java

echo "[3/6] Generating classes.dex via d8..."
/usr/local/bin/d8 --output build/ \
    --lib "$PLATFORM/android.jar" \
    build/classes/com/tao7/nasremote/*.class

echo "[4/6] Packaging APK via aapt..."
"$BUILD_TOOLS/aapt" package -f \
    -M app/src/main/AndroidManifest.xml \
    -S app/src/main/res \
    -I "$PLATFORM/android.jar" \
    -F build/unaligned.apk \
    build/

echo "[5/6] Aligning APK via zipalign..."
"$BUILD_TOOLS/zipalign" -f -p 4 build/unaligned.apk build/NasTvRemote-uncompressed.apk

echo "[6/6] Generating debug keystore & signing via apksigner..."
if [ ! -f debug.keystore ]; then
    keytool -genkey -v -keystore debug.keystore -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi

"$BUILD_TOOLS/apksigner" sign --ks debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out build/NasTvRemote.apk build/NasTvRemote-uncompressed.apk

echo "=========================================="
echo "SUCCESS: build/NasTvRemote.apk created!"
ls -lh build/NasTvRemote.apk
echo "=========================================="
