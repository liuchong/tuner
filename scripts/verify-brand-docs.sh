#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

expect_text() {
  local file="$1"
  local text="$2"
  if ! grep -Fq "$text" "$repo_root/$file"; then
    echo "missing '$text' in $file" >&2
    exit 1
  fi
}

expect_text "android/app/src/main/res/values/strings.xml" '<string name="app_name">TUNAR</string>'
expect_text "android/app/src/main/res/values-zh-rCN/strings.xml" '<string name="app_name">吐呐</string>'
expect_text "ios/Tunar/en.lproj/InfoPlist.strings" '"CFBundleDisplayName" = "TUNAR";'
expect_text "ios/Tunar/zh-Hans.lproj/InfoPlist.strings" '"CFBundleDisplayName" = "吐呐";'
expect_text "ios/project.yml" "PRODUCT_BUNDLE_IDENTIFIER: com.liuchong.tunar"
expect_text "README.md" "TUNAR · 吐呐"
expect_text "README.zh-CN.md" "吐呐 · TUNAR"

documents=(
  design-system.md
  roadmap.md
  spec-audio.md
  spec-core.md
  spec-instruments.md
  spec-ui.md
)

for document in "${documents[@]}"; do
  test -s "$repo_root/docs/$document"
  test -s "$repo_root/docs/en/$document"
  expect_text "docs/README.md" "en/$document"
  expect_text "docs/README.md" "$document"
done

echo "brand and bilingual documentation checks passed"
