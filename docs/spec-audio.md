# spec-audio — 音频管线规格（Android）

## 1. 采集（调音）

- 实现：Kotlin `AudioRecord`（MVP）；后续可换 Oboe 降延迟。
- 参数：单声道、PCM float（API 23+），采样率优先 44100（设备协商），帧长 1024 hop / 2048 窗口。
- 线程：专用读线程，循环 read → `TunerEngine.feed(pcm)` → 结果经 `tryEmit` 到 StateFlow（UI 消费，丢弃策略，不阻塞）。
- 延迟目标：采集到 UI 显示 ≤ 100ms。
- 权限：RECORD_AUDIO 运行时申请；拒绝则显示引导页。

## 2. 播放（节拍器）

- 实现：`AudioTrack` STREAM_MUSIC，MODE_STREAM，专用写线程。
- 节奏源：core `Metronome.render(out, frames)` 在写线程内调用，tick 采样在缓冲内精确混入（采样级对齐，可闻抖动 < 1ms）。
- 音色（2026-07-20 修订）：三种音色（机械 click / 电子 beep / 铃声 bell）的 PCM 采样
  由 App 启动时程序化合成（core-binding `TickSounds`，44100Hz 单声道 float，~100ms；
  重拍默认铃声、弱拍默认机械 click），音色选择变更时经 `set_click_samples` 注入。
  理由：原方案为 res/raw 内置资源，改为程序化合成——无二进制资源、参数（频率/包络）可调；
  属资源数据，非业务逻辑。
- 缓冲：AudioTrack `bufferSizeInFrames` 分片写入（写线程保持 ≥2 个缓冲余量防欠载），
  阻塞写提供天然背压；线程优先级 URGENT_AUDIO。
- Tick→UI 同步（2026-07-20 补充）：`RenderFrame.ticks` 的 `sample_offset` 加上
  「已排队未播放采样数」（已写采样 − playbackHeadPosition）换算为呈现时刻（ms），
  经 StateFlow 投递，UI 延时到该时刻再触发闪拍动画。
- 保活：播放中启动 foreground Service（mediaPlayback），通知栏显示当前 BPM + 停止按钮；
  Android 13+ 需运行时申请 POST_NOTIFICATIONS（拒绝时播放不受影响，仅通知栏不显示）。

## 3. 线程纪律

- 音频线程禁止：IO、网络、锁竞争、大对象分配。
- core 侧 `feed`/`render` 零分配（见 spec-core §3/§7）。
- UI 更新频率节流：TunerEvent ≥ 30fps 时 UI 按帧合并（conflate）。

## 4. 变更

任何采样率/缓冲/线程模型调整必须先更新本文件。
