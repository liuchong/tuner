#!/usr/bin/env bash
# 从 android/design/*.svg 生成全部 Android 图标资源
# 依赖: rsvg-convert (brew install librsvg)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DESIGN="$ROOT/android/design"
RES="$ROOT/android/app/src/main/res"

# adaptive foreground（透明底，内容在 108dp 画布中央 66dp 安全区内）
FG_MDPI=108; FG_HDPI=162; FG_XHDPI=216; FG_XXHDPI=324; FG_XXXHDPI=432
# legacy 方形/圆形
SQ_MDPI=48; SQ_HDPI=72; SQ_XHDPI=96; SQ_XXHDPI=144; SQ_XXXHDPI=192

for dpi in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  DPI_UPPER=$(echo "$dpi" | tr 'a-z' 'A-Z')
  eval "FG=\$FG_$DPI_UPPER"
  eval "SQ=\$SQ_$DPI_UPPER"
  mkdir -p "$RES/mipmap-$dpi"
  rsvg-convert -w "$FG" -h "$FG" "$DESIGN/icon-foreground.svg" \
    -o "$RES/mipmap-$dpi/ic_launcher_foreground.png"
  rsvg-convert -w "$SQ" -h "$SQ" "$DESIGN/icon-full.svg" \
    -o "$RES/mipmap-$dpi/ic_launcher.png"
  rsvg-convert -w "$SQ" -h "$SQ" "$DESIGN/icon-round.svg" \
    -o "$RES/mipmap-$dpi/ic_launcher_round.png"
done

# adaptive icon 描述文件
mkdir -p "$RES/mipmap-anydpi-v26"
for name in ic_launcher ic_launcher_round; do
  cat > "$RES/mipmap-anydpi-v26/$name.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
XML
done

# adaptive 背景（渐变 drawable）
mkdir -p "$RES/drawable"
cat > "$RES/drawable/ic_launcher_background.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient
        android:angle="270"
        android:startColor="#26325C"
        android:endColor="#0F1526"
        android:type="linear" />
</shape>
XML

# Play Store 512 图标
rsvg-convert -w 512 -h 512 "$DESIGN/icon-full.svg" -o "$DESIGN/playstore-icon.png"

echo "图标资源已生成"
