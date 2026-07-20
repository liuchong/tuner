<p align="center">
  <img src="android/design/playstore-icon.png" width="128" height="128" alt="Tuner">
</p>

<h1 align="center">Tuner</h1>

<p align="center">
  一个认真的调音器 & 节拍器 · Rust 共享核心 × 原生多平台
</p>

<p align="center">
  <img alt="license" src="https://img.shields.io/badge/license-0PL-blue">
  <img alt="rust" src="https://img.shields.io/badge/core-Rust-orange">
  <img alt="android" src="https://img.shields.io/badge/platform-Android%20%E2%80%A2%20iOS%20%E2%80%A2%20macOS-green">
</p>

---

## ✨ 特性

**🎯 通用调音器**
- ±0.5 音分精度（YIN + 谐波模型最小二乘精化，抗八度误判）
- 「极光」表盘：分区弧 / 进度光弧 / 光针残影，准音瞬间多模态反馈
- 唱名显示：固定 Do / 首调 Do / 简谱数字 / 宫商角徵羽，支持 12 主音 × 7 调式即时切换
- 实时 FFT 频谱 + 泛音列标注（H1–H8）+ 和弦识别
- PRO 模式：12 / 19 / 24 / 31 平均律

**🎸 乐器面板**
- 吉他 · 尤克里里 · 古琴：标准 + 流行变体定弦，自动/手动按弦调音
- 竹笛 · 洞箫 · 尺八：调性 × 筒音唱名指法表，吹奏实时校音

**⏱ 节拍器**
- 30–250 BPM，tap 测速，拍号与重音型
- 机械 click / 电子 beep / 铃声，重弱拍独立音色
- 采样级定时（抖动 < 1ms），前台保活后台续播，经典摆锤动画

## 🏗 架构

```
┌─ android/  Kotlin + Jetpack Compose（首发）
├─ ios/ macos/  SwiftUI（二期，复用同一 core）
│        │  UniFFI 自动生成绑定
└─ core/  Rust —— 全部业务逻辑
   YIN 音高检测 · 唱名/调式 · 乐器预设 · 节拍引擎 · FFT 频谱/泛音/和弦 · 律制
```

业务逻辑只允许在 Rust core；原生层只做 UI、音频采集/播放、权限与生命周期。
设计系统见 [docs/design-system.md](docs/design-system.md)（「Aurora/极光」，跨平台视觉规范）。

## 🔨 构建

```bash
# Rust core 测试（72 个单测）
cd core && cargo test

# 生成 Android 端 .so + Kotlin 绑定（需 Android NDK 与 cargo-ndk）
scripts/build-core-android.sh

# Android 构建与测试（29 个 JVM 单测）
cd android && ./gradlew assembleDebug testDebugUnitTest
```

## 📚 文档

| 文件 | 内容 |
|---|---|
| [docs/spec-core.md](docs/spec-core.md) | core 规格 + UniFFI API 合同 |
| [docs/spec-instruments.md](docs/spec-instruments.md) | 乐器定弦/指法数据 |
| [docs/spec-ui.md](docs/spec-ui.md) | 面板交互规格 |
| [docs/spec-audio.md](docs/spec-audio.md) | 音频管线规格 |
| [docs/design-system.md](docs/design-system.md) | Aurora 设计系统 |
| [docs/roadmap.md](docs/roadmap.md) | 里程碑 |

## 📄 许可证

[0PL (Zero Public License)](https://license.pub/0pl/) — 见 [LICENSE](LICENSE)
