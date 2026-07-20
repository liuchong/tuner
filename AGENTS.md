# Tuner — 项目规则（AGENTS.md）

跨平台调音器 + 节拍器。**Rust 共享核心 + 多端原生 UI**：Android（Kotlin/Compose，首发）、iOS/macOS（SwiftUI，二期）。

## 仓库结构

```
core/            Rust crate `tuner-core`：全部业务逻辑（DSP/唱名/预设/节拍引擎），UniFFI 导出
android/         Android App（Kotlin + Jetpack Compose）
  app/           UI 与音频桥接
  core-binding/  UniFFI Kotlin 绑定 + 各 ABI .so
  design/        图标源文件（SVG），用 scripts/generate-icons.sh 重新生成 mipmap 资源
ios/ macos/      二期（占位）
docs/            规格文档（spec-*.md）与 design-system.md（「Lumen/微光」设计系统，跨平台 UI 圣经），代码以此为准
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
```

工具链：Rust stable（targets: aarch64/armv7/x86_64-linux-android）、cargo-ndk、uniffi、JDK 21、Android SDK + NDK。

## 编码规范

- Rust：`cargo fmt` + `cargo clippy -- -D warnings`；公共 API 必须有文档注释；`no_std` 不要求但禁止依赖平台 API（std 仅限 alloc/数学）。
- Kotlin：官方代码风格；Compose UI 状态用 StateFlow；UI 层不放业务计算。
- 注释与文档使用中文；代码标识符使用英文。
- 测试要求见 `docs/spec-core.md` §8 与 `.agents/rules/rust-core.md`。
