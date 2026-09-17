#!/usr/bin/env bash
# 手工构建签名 APK(不依赖 Gradle):aapt2 → javac → d8 → zipalign → apksigner
# 依赖:%LOCALAPPDATA%/Android/Sdk(build-tools 35 + platforms android-35)与 JDK 17
set -euo pipefail

SDK="${LOCALAPPDATA}/Android/Sdk"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
JAVA17="$HOME/scoop/apps/temurin17-jdk/current"
export PATH="$JAVA17/bin:$PATH"
export JAVA_HOME="$JAVA17"

cd "$(dirname "$0")"
rm -rf build
mkdir -p build/obj build/dex build/gen

# 0) 调试签名密钥(首次生成,长期复用)
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias androiddebug \
    -storepass android -keypass android \
    -dname "CN=cli-starter,O=starter,C=CN" -keyalg RSA -keysize 2048 -validity 10000
fi

# 1) 编译资源(矢量图标等)并链接清单,同时生成 R.java
"$BT/aapt2.exe" compile --dir res -o build/res.zip
"$BT/aapt2.exe" link -o build/app-unsigned.apk \
  --manifest AndroidManifest.xml \
  -I "$PLATFORM" \
  -R build/res.zip \
  --java build/gen \
  --min-sdk-version 24 --target-sdk-version 35 \
  --version-code 1 --version-name 1.0

# 2) 编译 Java(含生成的 R.java;zxing 在 classpath)
"$JAVA17/bin/javac" -encoding UTF-8 -source 17 -target 17 \
  -classpath "$PLATFORM;libs/zxing-core.jar" \
  -d build/obj \
  $(find src build/gen -name "*.java")

# 3) dex(应用类 + zxing 一起)
"$JAVA17/bin/jar" cf build/classes.jar -C build/obj .
"$BT/d8.bat" --release --lib "$PLATFORM" --lib libs/zxing-core.jar \
  --output build/dex build/classes.jar libs/zxing-core.jar

# 4) classes.dex 塞进 APK
(cd build/dex && "$JAVA17/bin/jar" uf ../app-unsigned.apk classes.dex)

# 5) 对齐 + 签名
"$BT/zipalign.exe" -f 4 build/app-unsigned.apk build/app-aligned.apk
"$BT/apksigner.bat" sign \
  --ks debug.keystore --ks-key-alias androiddebug \
  --ks-pass pass:android --key-pass pass:android \
  --out cli-starter-auth.apk build/app-aligned.apk

echo "---- verify ----"
"$BT/apksigner.bat" verify --print-certs cli-starter-auth.apk | head -6
ls -la cli-starter-auth.apk
echo "OK: android/cli-starter-auth.apk"
