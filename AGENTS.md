# 吐呐 / TUNAR — 项目规则（AGENTS.md）

产品品牌为中文「吐呐」、英文 `TUNAR`。跨平台调音器 + 节拍器，采用 **Rust 共享核心 +
多端原生 UI**：Android（Kotlin/Compose）、iOS（SwiftUI）与 macOS（SwiftUI）。

品牌名只影响用户可见文案。为保证已安装版本原位升级与设置数据兼容，仓库目录、Rust crate、
Java package、iOS target/scheme、Bundle ID 和持久化键继续使用既有 `tuner` / `Tuner`
技术标识，除非另有经确认的迁移方案。

## 仓库结构

```
core/            Rust crate `tuner-core`：全部业务逻辑（DSP/唱名/预设/节拍引擎），UniFFI 导出
android/         Android App（Kotlin + Jetpack Compose）
  app/           UI 与音频桥接
  core-binding/  UniFFI Kotlin 绑定 + 各 ABI .so
  design/        图标源文件（SVG），用 scripts/generate-icons.sh 重新生成 mipmap 资源
ios/             iOS SwiftUI App + UniFFI Swift 绑定
macos/           macOS 14+ SwiftUI App（侧栏式五入口桌面界面）
docs/            中英双语规格与「Aurora/极光」设计系统，代码以此为准
.agents/rules/   分场景开发规则
scripts/         构建脚本（build-core-android.sh 等）
```

## 架构红线（不可违反）

1. **所有业务逻辑只允许在 `core/`（Rust）**：音高检测、音名/唱名换算、调弦预设、节拍定时。原生层禁止重新实现这些逻辑。
2. 原生层只负责：UI、麦克风采集、节拍器发声、权限与生命周期。
3. 原生层访问 Rust **只允许走 UniFFI 生成的绑定**，接口即合同，见 `docs/spec-core.md` 附录 A。
4. **音频回调路径禁止分配内存、加锁、抛异常/unwrap**（Rust 侧同样约束）。
5. 改代码触及规格时必须同步更新 docs（见 `.agents/rules/docs-sync.md`）。

## 构建与测试

```bash
# Rust core 测试（每次改 core 后必跑，必须全绿）
cd core && cargo test

# Android 端 Rust 库 + Kotlin 绑定
scripts/build-core-android.sh        # 自动探测 ANDROID_NDK_HOME（或用最新已装 NDK）；输出到 android/core-binding

# Android 构建与测试
cd android && ./gradlew assembleDebug
cd android && ./gradlew testDebugUnitTest

# iOS 端 Rust 库 + Swift 绑定 + XCFramework
scripts/build-core-ios.sh           # 输出含 iOS 与通用 macOS slice 的 ios/TunerCore/

# iOS 构建（xcodegen 重新生成工程；模拟器/真机）
cd ios && xcodegen generate
xcodebuild -scheme Tuner -destination 'generic/platform=iOS Simulator' build
xcodebuild -scheme Tuner -destination 'generic/platform=iOS' -allowProvisioningUpdates build
# 真机安装：xcrun devicectl device install app --device <id> <Tuner.app>

# macOS 工程、单元测试与应用构建（测试运行器需访问系统 testmanagerd）
cd macos && xcodegen generate
xcodebuild -project TunarMac.xcodeproj -scheme TunarMac \
  -destination 'platform=macOS,arch=arm64' test
xcodebuild -project TunarMac.xcodeproj -scheme TunarMac \
  -destination 'platform=macOS,arch=x86_64' build
```

工具链：Rust stable（Android targets，以及
`aarch64-apple-darwin` / `x86_64-apple-darwin`）、cargo-ndk、uniffi、JDK 21、
Android SDK + NDK、Xcode 及 xcodegen。

## 编码规范

- Rust：`cargo fmt` + `cargo clippy -- -D warnings`；公共 API 必须有文档注释；`no_std` 不要求但禁止依赖平台 API（std 仅限 alloc/数学）。
- Kotlin：官方代码风格；Compose UI 状态用 StateFlow；UI 层不放业务计算。
- 注释与文档使用中文；代码标识符使用英文。
- 测试要求见 `docs/spec-core.md` §8 与 `.agents/rules/rust-core.md`。
