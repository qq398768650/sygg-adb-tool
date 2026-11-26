#!/usr/bin/env bash
# 手工构建（不依赖 gradle）：aapt2 -> javac -> d8 -> zipalign -> apksigner
set -e
cd "$(dirname "$0")"

JDK="H:/GitWork2026/Tools/jdk17/jdk-17.0.21.1+2"
BT="H:/AndroidSDK/AndroidSDK/build-tools/36.0.0"
ANDROID_JAR="H:/AndroidSDK/AndroidSDK/platforms/android-30/android.jar"

AAPT2="$BT/aapt2.exe"
D8_JAR="$BT/lib/d8.jar"
ZIPALIGN="$BT/zipalign.exe"
APKSIGNER_JAR="$BT/lib/apksigner.jar"
# 二维码识别（javac 编译期 + d8 打进 dex 都要它）
ZXING="libs/core-3.5.3.jar"

OUT=build
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile"
"$AAPT2" compile --dir res -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$AAPT2" link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --java "$OUT/gen" \
  --min-sdk-version 29 --target-sdk-version 30 \
  "$OUT/res.zip"

echo "[3/6] javac"
find src "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
# 注意：这里不要再把 javac 输出管道给 grep —— 中文 GBK 输出会被 grep 当二进制丢掉，真实报错看不见
"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -classpath "$ANDROID_JAR;$ZXING" \
  -d "$OUT/classes" @"$OUT/sources.txt" || { echo "[javac] 编译失败，见上面的错误"; exit 1; }

echo "[4/6] d8"
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
"$JDK/bin/java" -cp "$D8_JAR" com.android.tools.r8.D8 --lib "$ANDROID_JAR" --min-api 29 --output "$OUT/dex" @"$OUT/classes.txt" "$ZXING" 2>&1 | tail -5

echo "[5/6] 打包 dex"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && "$JDK/bin/jar" uf "../unsigned.apk" classes.dex)

echo "[6/6] zipalign + sign"
"$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f devupdate.jks ]; then
  "$JDK/bin/keytool" -genkeypair -v -keystore devupdate.jks -alias devupdate \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass 123456 -keypass 123456 \
    -dname "CN=HSY DevUpdate, OU=Dev, O=HSY, L=Shenyang, ST=Liaoning, C=CN" >/dev/null 2>&1
fi
"$JDK/bin/java" -jar "$APKSIGNER_JAR" sign --ks devupdate.jks --ks-key-alias devupdate \
  --ks-pass pass:123456 --key-pass pass:123456 \
  --out "DevUpdate.apk" "$OUT/aligned.apk"

ls -lh DevUpdate.apk

# 自更新清单：发布时和 DevUpdate.apk 一起挂到 GitHub Release，
# App 里点「关于」拉这个文件判断有没有新版（见 SelfUpdate.java）
VC=$(grep -o 'android:versionCode="[0-9]*"' AndroidManifest.xml | head -1 | grep -o '[0-9]*')
VN=$(grep -o 'android:versionName="[^"]*"' AndroidManifest.xml | head -1 | sed 's/.*="//; s/"$//')
SIZE=$(stat -c %s DevUpdate.apk)
SHA=$(sha256sum DevUpdate.apk | cut -d' ' -f1)
printf '{"versionCode":%s,"versionName":"%s","size":%s,"sha256":"%s"}\n' "$VC" "$VN" "$SIZE" "$SHA" > devupdate.json
echo "[自更新] v$VN ($VC) · $SIZE 字节 · sha256=$SHA"
