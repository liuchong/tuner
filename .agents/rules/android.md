# Android 端开发规则

适用于 `android/` 目录。

## 硬性约束

1. **禁止在原生层实现业务逻辑**（音高/唱名/预设/节拍计算一律走 UniFFI 绑定调用 core）。
2. 音频回调线程（AudioRecord 读线程 / AudioTrack 写线程）：
   - 不得做网络、IO、分配大对象；
   - 与 UI 线程通信用 `Channel.trySend`（丢弃策略）或 StateFlow.tryEmit，禁止阻塞。
3. 录音权限必须运行时申请；未授权时 UI 优雅降级（显示引导页），禁止崩溃。
4. 节拍器前台保活用 foreground Service（纯播放用 mediaPlayback 类型；同时录音时才加 microphone）。

## Compose 规范

- UI 状态：ViewModel + StateFlow，Compose 只 collect，不做计算。
- 指针/闪拍动画用 `Animatable`/逐帧 `withFrameNanos`，与 core 的 TunerEvent/TickInfo 对齐。
- 面板行为以 `docs/spec-ui.md` 为准；改动行为必须同步改 spec。

## 构建

- 依赖 `core-binding` 模块；Rust 库由 `scripts/build-core-android.sh` 生成。
- 不要手改 `core-binding` 中生成的绑定代码（generated 目录）。
