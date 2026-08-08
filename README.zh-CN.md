<p align="center">
  <img src="android/design/playstore-icon.png" width="128" height="128" alt="吐呐">
</p>

<h1 align="center">吐呐 · TUNAR</h1>

<p align="center">
  吐纳声音，听见音准 · Rust 共享核心 × 原生多平台
</p>

<p align="center">
  <img alt="license" src="https://img.shields.io/badge/license-0PL-blue">
  <img alt="rust" src="https://img.shields.io/badge/core-Rust-orange">
  <img alt="android" src="https://img.shields.io/badge/platform-Android%20%E2%80%A2%20iOS%20%E2%80%A2%20macOS-green">
</p>

---

<p align="center">
  <strong>简体中文</strong> · <a href="README.md">English</a>
</p>

## ✨ 特性

**🎯 通用调音器**
- ±0.5 音分精度（YIN + 谐波模型最小二乘精化，抗八度误判）
- 默认 -45dBFS 可调门限、两帧确认、3dB 滞回与持续锁存，弱噪声不驱动指针
- 「极光」表盘：分区弧 / 进度光弧 / 光针残影，准音瞬间多模态反馈
- 唱名显示：固定 Do / 首调 Do / 简谱数字 / 宫商角徵羽，支持 12 主音 × 7 调式即时切换
- 专业声音分析：乐音/全频 FFT、12 秒音高轨迹、实时波形包络、实际峰值 Hz、
  连续时间图谱与和弦识别
- 内置固定音高音叉：按当前 A4 与 12 / 19 / 24 / 31 平均律试听 80–1500Hz 音级，收起后可继续校准
- PRO 模式：12 / 19 / 24 / 31 平均律

**🎸 乐器面板**
- 吉他 · 尤克里里 · 古琴：标准 + 流行变体定弦，自动/手动按弦调音
- 竹笛 · 洞箫 · 尺八：调性 × 筒音唱名指法表，吹奏实时校音

**⏱ 节拍器**
- 30–250 BPM，tap 测速，拍号与重音型
- 十二种常见合成音色，重弱拍独立选择
- 采样级定时（抖动 < 1ms），前台保活后台续播，经典摆锤动画

## 🏗 架构

```
┌─ android/  Kotlin + Jetpack Compose（首发）
├─ ios/        SwiftUI（复用同一 core）
├─ macos/      macOS 14+ SwiftUI 原生桌面应用
│        │  UniFFI 自动生成绑定
└─ core/  Rust —— 全部业务逻辑
   YIN 音高检测 · 唱名/调式 · 乐器预设 · 节拍引擎 · FFT 频谱/泛音/和弦 · 律制
```

业务逻辑只允许在 Rust core；原生层只做 UI、音频采集/播放、权限与生命周期。
设计系统见 [docs/design-system.md](docs/design-system.md)（「Aurora/极光」，跨平台视觉规范）。

## 🔨 构建

```bash
# Rust core 测试
cd core && cargo test

# 生成 Android 端 .so + Kotlin 绑定（需 Android NDK 与 cargo-ndk）
scripts/build-core-android.sh

# Android 构建与 JVM 单元测试
cd android && ./gradlew assembleDebug testDebugUnitTest

# 生成含 iOS 与通用 macOS slice 的 Apple 绑定
scripts/build-core-ios.sh

# iOS 工程与模拟器测试
cd ios && xcodegen generate
xcodebuild -scheme Tuner -destination 'platform=iOS Simulator,name=<模拟器名称>' test

# macOS 原生应用与测试
cd ../macos && xcodegen generate
xcodebuild -project TunarMac.xcodeproj -scheme TunarMac \
  -destination 'platform=macOS,arch=arm64' test
xcodebuild -project TunarMac.xcodeproj -scheme TunarMac \
  -destination 'platform=macOS,arch=x86_64' build
```

## 📚 文档

文档语言入口见 [docs/README.md](docs/README.md)。

| 文件 | 内容 |
|---|---|
| [docs/spec-core.md](docs/spec-core.md) | core 规格 + UniFFI API 合同 |
| [docs/spec-instruments.md](docs/spec-instruments.md) | 乐器定弦/指法数据 |
| [docs/spec-ui.md](docs/spec-ui.md) | 面板交互规格 |
| [docs/spec-audio.md](docs/spec-audio.md) | 音频管线规格 |
| [docs/design-system.md](docs/design-system.md) | Aurora 设计系统 |
| [docs/macos-native.md](docs/macos-native.md) | macOS 桌面布局、生命周期与构建合同 |
| [docs/roadmap.md](docs/roadmap.md) | 里程碑 |

## 🏷 品牌与兼容性

中文产品名为「吐呐」，英文为 `TUNAR`。用户可见名称已本地化；仓库名、Rust crate、
Java 包名、iOS target/scheme、Bundle ID 与持久化设置键保留原技术标识，确保升级兼容。

## 📄 许可证

[0PL (Zero Public License)](https://license.pub/0pl/) — 见 [LICENSE](LICENSE)
