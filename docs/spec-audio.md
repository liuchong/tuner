# spec-audio — 音频管线规格（Android / iOS）

## 1. 采集（调音）

- 实现：Kotlin `AudioRecord`（MVP）；后续可换 Oboe 降延迟。
- 参数：单声道、PCM float（API 23+），采样率优先 44100（设备协商），帧长 1024 hop / 2048 窗口。
  实际 hop 必须写入 `TunerConfig.frame_hop_samples`，供 core 与原生层统一分析帧节奏。
- 线程：专用读线程，循环 read → `TunerEngine.feed(pcm)` → 结果经 `tryEmit` 到 StateFlow（UI 消费，丢弃策略，不阻塞）。
- 延迟目标：采集到 UI 显示 ≤ 100ms。
- 权限：RECORD_AUDIO 运行时申请；拒绝则显示引导页。

## 2. 播放（节拍器）

- 实现：`AudioTrack` STREAM_MUSIC，MODE_STREAM，专用写线程。
- 节奏源：core `Metronome.render(out, frames)` 在写线程内调用，tick 采样在缓冲内精确混入（采样级对齐，可闻抖动 < 1ms）。
- 音色（2026-07-28 修订）：十二种音色按常用程度排列为机械节拍、木块、电子滴声、拍板、
  边鼓、小鼓、牛铃、踩镲、拍手、沙锤、低鼓、铃声。PCM 采样由 App 启动时程序化合成
  （`TickSounds`，44100Hz 单声道 float，按音色使用短促冲击、谐振或噪声包络；重拍默认
  铃声、弱拍默认机械节拍），音色选择变更时经 `set_click_samples` 注入。每种波形必须
  非空、只包含有限数值且峰值不超过 0.95；Android 与 iOS 使用相同的频率、时长、包络和
  增益参数。
  理由：原方案为 res/raw 内置资源，改为程序化合成——无二进制资源、参数（频率/包络）可调；
  属资源数据，非业务逻辑。
- 缓冲：AudioTrack `bufferSizeInFrames` 分片写入（写线程保持 ≥2 个缓冲余量防欠载），
  阻塞写提供天然背压；线程优先级 URGENT_AUDIO。
- Tick→UI 同步（2026-07-20 补充）：`RenderFrame.ticks` 的 `sample_offset` 加上
  「已排队未播放采样数」（已写采样 − playbackHeadPosition）换算为呈现时刻（ms），
  经 StateFlow 投递，UI 延时到该时刻再触发闪拍动画。
- 保活：播放中启动 foreground Service（mediaPlayback），通知栏显示当前 BPM + 停止按钮；
  Android 13+ 需运行时申请 POST_NOTIFICATIONS（拒绝时播放不受影响，仅通知栏不显示）。

## 2a. 播放（固定音高音叉）

- Android 使用独立 `AudioTrack` 流式生成单声道正弦波；iOS 使用与采集兼容的
  `AVAudioEngine` 播放节点。频率只取自 core 的 `ReferenceTone`，平台层不重复计算律制。
- 同时最多播放一个音；峰值增益 0.65，启动、停止和换音使用约 20ms 淡入淡出，输出不得削波。
- Android 固定音高输出采用惰性生命周期：只打开浮窗或构造播放器时不得创建
  `AudioTrack`、启动写线程或持续写静音；首次点选音高时才创建输出，停止淡出完成后释放。
- 音叉浮窗打开和播放期间，麦克风采集及同一 `CaptureHub` 分析不得停止；播放声允许被
  麦克风重新捕捉，用于验证调音指针。
- 浮窗关闭后继续播放，以便由麦克风回采校准指针；离开调音页、退到后台或音频焦点/会话
  中断时停止。无需新增权限。
- iOS 同时采集与播放使用 `.playAndRecord`，并保持扬声器输出；采集 tap 必须用构造期分配的
  环形缓冲拼接 2048 样本窗口，音频回调中禁止 `append`、切片复制和 `removeFirst`。
- iOS 后台初始化采集时，每次首订阅必须分配启动令牌。最后一个订阅释放会使令牌失效；
  过期初始化完成后只能移除 tap 并停止本地引擎，不得重新提交为运行中的麦克风会话。
- iOS 输入节点报告采样率为 0 或声道数为 0 时，不得以自造格式安装采集 tap；本次启动
  安静失败并释放音频会话，等页面下一次获取采集资源时重试，避免 AVFAudio 进程级崩溃。

## 3. 线程纪律

- 音频线程禁止：IO、网络、锁竞争、大对象分配。
- core 侧 `feed`/`render` 零分配（见 spec-core §3/§7）。
- UI 更新频率节流：TunerEvent ≥ 30fps 时 UI 按帧合并（conflate）。
- 调音读数的 2 帧确认、门限滞回和无限保持由 core 完成；平台不得使用墙上时钟二次清空。
- 专业声音视图沿用同一采集窗口和同一次 core `analyze`；全频段频谱、乐音频谱与波形包络
  随同一个 `AnalysisFrame` 返回。禁止为任一视图新增麦克风、第二次 FFT 或音频回调内历史分配。

## 4. 变更

任何采样率/缓冲/线程模型调整必须先更新本文件。
