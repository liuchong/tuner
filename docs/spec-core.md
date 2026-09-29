# spec-core — Rust core（tunar-core）规格

本文件是 core 的唯一权威规格。UniFFI 接口（附录 A）是原生层与 Rust 之间的合同。

## 1. 职责

- 音高检测（YIN）：f32 PCM → 基频 Hz + 置信度
- 音名/音分换算：Hz ↔ 音名（含 A4 校准 415–466Hz）
- 唱名换算：4 种唱名体系 × 调式（§5）
- 乐器预设：弦乐器定弦表、管乐器调性+指法表（数据见 spec-instruments.md）
- 音高平滑：中值滤波 + EMA + 音符滞回
- 输入状态机：门限滞回、连续帧确认、断音保持与显示强度
- 固定音高：按当前平均律生成 80–1500Hz 的可试听音级表
- 节拍器引擎：采样级定时的 tick 事件流 + PCM 混音

非职责（原生层负责）：UI、音频采集/播放设备、权限、保活。

## 2. 模块划分

```
src/
  lib.rs        crate 入口，re-export
  api.rs        UniFFI 导出层（附录 A 的一一映射，唯一的对外接口）
  pitch.rs      YIN 音高检测器
  note.rs       Hz ↔ MIDI/音名/音分，A4 校准
  solfege.rs    唱名体系与调式换算
  tuning.rs     弦乐器定弦预设数据
  fingering.rs  管乐器调性 + 筒音唱名 → 音阶目标
  smooth.rs     平滑滤波器
  signal.rs     输入门限滞回、连续帧确认、断音保持状态机
  reference.rs  当前平均律的固定音高表
  metronome.rs  节拍引擎
  spectrum.rs   FFT 频谱 + 泛音/和弦分析（§4a，v4 新增）
```

## 3. 音高检测（YIN）

- 算法：YIN（de Cheveigné & Kawahara），差函数 + 累积均值归一化（CMNDF）+ 抛物线插值。
- 亚采样精化（2026-07-20 修订）：抛物线插值后追加「谐波模型联合最小二乘精化」——
  以 x[n] = Σ_h a_h·cos(hωn) + b_h·sin(hωn)（h=1..6）为模型，对 (a,b,ω) 做联合
  Gauss-Newton 迭代（约 4–6 轮收敛）；矩阵奇异等退化情况回退到波形 Gauss-Newton 位移拟合。
  理由：SNR 20dB 下时域相关类估计（抛物线/自相关插值）单帧抖动达 1–7 cents，
  物理上无法达到 ±0.5 cent 指标；谐波联合最小二乘接近 CRLB，实测全部测试频点 ≤ 0.45 cent。
- 八度纠错（2026-07-20 真机修复）：两遍扫描——先取「低于阈值的局部极小值」的最小
  CMNDF（best_val），再在「CMNDF < best_val + 0.05 的全部局部极小值」（含真周期
  略高于阈值的折中情形）中取最短周期。防次谐波误判：真机扫频 400Hz 曾误读 201Hz，
  原「首个低于阈值」策略在真周期 CMNDF 略高于阈值时错选 2 倍周期；对强谐波信号的
  3 倍周期极小值同样有效。纠正后的周期再进入抛物线插值与谐波精化。
- 参数：窗口 2048 采样（hop 由原生层决定，建议 1024），阈值 0.15，采样率从配置读。
- 输入：单声道 f32 [-1,1]；输出 `Option<(freq_hz, clarity)>`，clarity = 1 - CMNDF 最小值。
- 无效输入（RMS 低于配置门限，默认 -45dBFS；或 clarity < 0.6）→ None。
- 精度指标：80–1500Hz，合成信号（基频+3 泛音，SNR 20dB）误差 ≤ ±0.5 cent。
- 实时安全：`feed` 路径零分配、零锁、零 panic（构造时预分配全部缓冲；精化只用栈上定长数组）。

## 4. 音名/音分换算

- `freq_to_midi(f, a4) = 69 + 12·log2(f/a4)`；`midi_to_freq(m, a4) = a4·2^((m-69)/12)`。
- 音名采用升号命名（C C# D D# E F F# G G# A A# B），八度采用科学音高记谱（A4=440，C4=中央 C，MIDI 60）。
- cents_off = 100 × (midi_float − round(midi_float))，范围 [-50, +50)。
- A4 校准范围 415–466Hz，默认 440，精度 0.1Hz。

## 4a. 频谱与泛音分析（v4 新增）

`TunarEngine::analyze(pcm)` 在 feed 的音高检测基础上额外返回（均预分配、零分配热路径）：
- **频谱**：Hann 窗 + 实数 FFT（旋转因子构造时预计算），输出对数频率轴 60–2400Hz 共 64 bin 幅值（dBFS，-80~0 映射）。
- **泛音列 partials**：频谱显著峰（局部极大且超噪声底 12dB），定容 ≤8 个；映射到
  同一实际频率（差值 <0.01Hz）的候选只保留幅值更强者。每峰：频率/幅值/harmonic_index
  （基频整数倍 ±30c 容差则 2,3,4…，否则 0=独立音）/独立音的最近 12-TET 音名与 cents。
- **和弦**：独立音级 + 基频 ≥3 个音级时，与模板（maj/min/5/7/maj7/m7/sus4/add9）匹配，输出和弦名（如 "Cmaj"），匹配不到为 None。
  补充规则（2026-07-20 实现期发现）：真实三和弦存在共同次谐波（如 C+E+G 的听感基频 C3/C2），
  YIN 可能检出该「幻影基频」使 C/G 被标为泛音而无法匹配。因此：当泛音列中不存在
  harmonic_index == 1 的峰（基频未在频谱中出现，即幻影基频）时，harmonic_index ∈ {2..=6}
  的峰也计入和弦音级（C2 幻影基频下三和弦落在 4/5/6 次）；存在基频峰时仅计入独立音
  （避免单音泛音列误判为和弦——单音在 idx 2..=6 内的泛音最多产生 2 个不同音级，
  不足以凑满 ≥3 音级，不会误判）。
- 性能：analyze 单帧耗时 < 3× feed；测试：合成基频+泛音信号的 H2/H3 标注正确；双音/三音合成的和弦识别正确。

2026-07-30 专业声音视图扩展：

- 同一次 2048 点 FFT 同时生成两套展示数据，禁止为全频段视图再执行一次 FFT：
  既有 `spectrum_db` 为 64 桶、60–2400Hz；`wide_spectrum_db` 为 128 桶、
  20Hz 到 `min(20000Hz, sample_rate/2)`。
- `waveform_min` / `waveform_max` 各 256 点：把当前分析窗口等宽分桶，每桶输出有限样本的
  最小值与最大值；非有限样本按 0 处理。包络只用于时域展示，不参与音高判断。
- `sample_position` 以配置的分析 hop 递增，表示当前帧末端相对本引擎启动时的采样位置；
  `sample_rate_hz` 随帧返回。平台用二者建立单调时间轴，不读取系统时钟。
- 全频段上限随实际采样率裁剪并由 `wide_spectrum_max_hz` 明示；所有输出必须为有限值。

## 4b. 律制（Temperament，v4 新增）

- N 平均律，N ∈ {12, 19, 24, 31}，默认 12；步序以 A4 为参考：`f_step = a4·2^(k/N)`。
- TunarEvent 新增字段：`temperament`（当前 N）、`temperament_step`（最近步序 k）、`temperament_cents`（= 1200·log2(f/f_step)，范围 [-600/N, +600/N)）。
- 音名与 cents_off 主读数语义不变（12-TET）；律制读数走新字段。set_temperament 即时生效。
- 测试：19-TET 下 A4 上方第 7 步频率 ↔ 读数一致；cents 边界正确。

## 4c. 输入门限与读数保持

调音读数不得直接以单帧检测成功/失败决定出现或消失。`TunarCore` 在音高检测后使用以下
状态机，主调音页和乐器调音页只消费同一份状态，不得各自实现超时：

1. `Quiet`：输入电平低于开启门限，或尚未形成可信读数。无可显示音高。
2. `Acquiring`：输入达到开启门限且得到可信音高，但连续有效帧不足 2 帧。无可显示音高。
3. `Tracking`：连续 2 帧达到开启门限且音高有效后进入；每个有效帧更新读数。
4. `Holding`：输入低于关闭门限或检测不到可信音高时，以显示强度 1 持续保留最后一个
   有效读数，不按时间清除。重新超过开启门限并得到可信音高后，第一帧只作为候选且继续
   显示旧读数；第二个连续有效帧到达才回到 `Tracking` 并替换读数。候选中断或只有大声
   无效噪声时清除候选，但不清除已确认读数。

门限开启值为 `TunarConfig.noise_gate_dbfs`（默认 -45dBFS，可设置 -60~-30dBFS），
`Tracking` 的关闭值固定比开启值低 3dB；进入 `Holding` 后必须重新超过开启值才能形成
候选。响度达到门限但音高无效的噪声不得推动指针。默认 hop 为 1024。
`AnalysisFrame` 必须回传输入电平、状态、是否保持和显示强度，原生界面据此渲染，不另设
清空计时器。

## 4d. 固定音高表

`list_reference_tones()` 根据引擎当前的 A4 校准与平均律生成一个有序音级表：

- 频率公式：`frequency_hz = a4_hz · 2^(step_from_a4 / temperament)`。
- 只返回闭区间 80–1500Hz 内的全部音级，按频率递增。
- 支持当前已有的 12/19/24/31 平均律；切换 A4 或律制后再次查询必须即时反映新值。
- 每项包含 `step_from_a4`、`frequency_hz`、`temperament`、最近的 12 平均律音名以及
  相对该音名的音分差，便于非 12 平均律仍有可读标签。
- core 只提供频率和标签，不访问扬声器，也不生成平台音频对象。

## 5. 唱名体系与调式

调式（KeyMode）= 主音（12 律）× 调式类别（宫/商/角/徵/羽/大调/小调）。宫商角徵羽五声调式音程（相对主音的半音数）：

| 调式 | 音阶级进（半音） | 对应首调唱名序列 |
|---|---|---|
| 宫 | 0 2 4 7 9 | 宫 商 角 徵 羽（do re mi sol la） |
| 商 | 0 2 5 7 10 | 商 角 徵 羽 宫 |
| 角 | 0 3 5 8 10 | 角 徵 羽 宫 商 |
| 徵 | 0 2 5 7 9 | 徵 羽 宫 商 角 |
| 羽 | 0 3 5 7 10 | 羽 宫 商 角 徵 |
| 大调 | 0 2 4 5 7 9 11 | do re mi fa sol la si |
| 小调 | 0 2 3 5 7 8 10 | la si do re mi fa sol（首调以 la 为主音） |

四种唱名体系（SolfegeSystem），输出均为「唱名字符串 + 升降标记」：

1. **FixedDo（固定 Do）**：C=do，与调式无关；变化音加 #/b。
2. **MovableDo（首调 Do）**：以调式主音为 do（大调/宫调）或 la（小调/羽调以外的按音级映射）；非调内音加 #/b。
3. **Numbered（简谱）**：首调，1–7，变化音加 #/b。
4. **Chinese（宫商角徵羽）**：首调，调内五声用 宫/商/角/徵/羽，偏音用 变宫（7）、变徵（#4）、清角（4）、闰（b7）。

换算输入：音名（ pitch class ）+ KeyMode；输出字符串。规则实现于 `solfege.rs`，边界用例（等价音、偏音）必须有单测。

实现规则（2026-07-20 补充，与上表唱名序列等价）：首调体系（MovableDo/Numbered/Chinese）
先按调式类别确定「宫/do/1 参照音」，再按相对参照音的半音偏移查表：

| 调式 | 主音唱名 | 参照音 = 主音 + 半音偏移 |
|---|---|---|
| 宫 / 大调 | 宫 / do | +0 |
| 商 | 商 | −2 |
| 角 | 角 | −4 |
| 徵 | 徵 | −7 |
| 羽 / 小调 | 羽 / la | +3 |

偏移查表（相对参照音 0–11 半音）：宫 #宫 商 #商 角 清角(4) 变徵(#4) 徵 #徵 羽 闰(b7) 变宫(7)；
简谱 1 #1 2 #2 3 4 #4 5 #5 6 b7 7；首调 do #do re #re mi fa #fa sol #sol la b7 si。

## 6. 乐器预设

数据与格式见 `docs/spec-instruments.md`。接口：

- 弦乐器：Tuning = 有序弦列表，每弦 { 弦号, 音名, 目标频率(随 A4 校准换算), 首调唱名 }。
- 新管乐接口：`WindVariant` 表示「调性/尺寸 × 孔制」，`WindChart` 表示
  「型号 + 12 档中的一档筒音唱名 + 七声/十二音范围」。`WindFingering` 同时返回稳定半音
  id、基础孔位半音、音区、指法文字、孔位状态、`fingering_kind`（普通或叉指/半孔标记）、
  `anchor_hole`（最上方开孔）、音名/MIDI/频率、唱名、正声/偏音和超吹标记。
- 洞箫 G/F 调各提供 8 孔与 6 孔，型号顺序令 8 孔成为默认；孔制型号返回 12 档
  `TongyinOption`。`fundamental_midi` 按实物取 G 调 `d1 = 62`、F 调 `c1 = 60`
  （2026-08-25 修复：曾误取低八度的 50/48，实吹低音区会命中中音列）。UI 固定展示**低音（缓吹）**、**中音（超吹）**、
  **高音（急吹）**三列。core 为每个基础孔位在三个音区各取**该音高的实测孔位**
  （+0/+12/+24 半音）：8 孔 `Scale` 19 项、`Chromatic` 32 项，6 孔更少；
  指法表未覆盖的音高不返回，客户端该格视觉留白。
- 中、高音区孔位不是低音区的机械平移，必须逐格抄录来源：8 孔取
  [八孔洞箫指法表](https://simumis.com/posts/xiao/) 相对筒音 0–31 半音的 32 列，
  中音区只有 22 半音换用另一种叉口，高音区（24–31）孔位自成一套；6 孔取
  [donsiau.net 洞簫指法](http://donsiau.net/notedr.htm) 六孔表五个筒音列的一致项，
  其自注「超高音指法因簫不同略有差異」，列间冲突的 18、23、28、30 半音留空。
  超出图表范围或列间冲突的格禁止复制低音孔位，也禁止通过增加八度或五度偏移合成孔位、
  音名或频率。未访问的 Quark 付费页不得作为已核实证据。
- 客户端主表只用自然级 `[0,2,4,5,7,9,11]` 作为 `Scale.tongyin_degree`，按七档循环；
  详情独立使用全部 12 档请求 `Chromatic`。两张表不得共享一个可变筒音档状态。
- 8 孔 `holes` 采用第 2、6 辅助孔常闭的主指法：来源表若标记“可开可闭”，core 固定选择
  `Closed`，不得退化为八孔逐孔开放。辅助孔常闭仍属于普通指法；只有真正交叉开闭的孔型
  才返回 `Combination`，半孔则同时由 `HoleMark::Half` 明确表达。
- 同一型号切换 `tongyin_degree` 时，`Chromatic` 返回项保持相同 `fingering_id`、孔位和音名，
  只改变唱名/宫音字段；`Scale` 必须按
  `(宫调式七声半音 - tongyin_degree) mod 12` 重新筛选并升序排列七个基础半音，使低音列完整
  覆盖 `1–7`。例如作 5 为 `[0,2,4,5,7,9,10]`，作 2 为 `[0,2,3,5,7,9,10]`。
- UI 只把一个当前目标的 `holes` 渲染到一支完整大型洞箫。每个已返回的 `WindFingering`
  作为“基础孔位行 × 音区列”的可点单元格；某音区没有返回项时该格必须留白且不可点。
  基础行按 `anchor_hole` 与最上方开孔孔心严格共用水平坐标；全闭
  锚定出音口。没有对应七声基础孔位的物理孔位线保持空白。`fingering_kind` 只决定单元格
  是否显示小型“叉”或“半”提示，不得产生独立轨道。音名与唱名是分离字段和点击区域；
  点选单元格把完整指法固定到大图，优先于实时识别，再点一次交回实时识别。
- 逐半音 `holes` 是 core 选用的主指法示范，不构成唯一权威指法；不同乐器/流派允许替代指法。
- 竹笛、洞箫、尺八三种管乐统一走本接口与同一套“完整乐器图 + 三音区列”客户端表格
  （2026-09-29）。六孔竹笛六孔全在正面，孔位与六孔箫逐孔开放一致，唯 10 半音（作 5 的「4」）
  用竹笛通行叉口“开第一二三六孔”；中音区 12 半音只开第六孔、22 半音换叉口，高音区只收录
  资料一致的 24 半音，其余高音格返回空。尺八为固定音阶类：五孔（前四后一），按八度分
  乙音 / 甲音 / 大甲三音区，`Scale` 返回 11 条；变化音靠俯仰口风（meri/kari）而非孔位，
  因此 `supports_tongyin` 与 `supports_chromatic` 均为 false，忽略 `tongyin_degree` 与
  `Chromatic`，客户端不提供唱名拖动转调与十二音详情。

全局查询接口的校准约定（2026-07-20 修订，原附录 A 注释「按当前 A4 校准换算」在
无配置参数的全局函数上无从获得「当前」配置）：`list_tunings` / `list_wind_variants` /
`wind_fingering_chart` 返回的 `freq_hz` 一律按 A4=440 换算、`solfege` 按乐器习惯调的首调简谱（古琴 F 调，
吉他/尤克里里 C 调；管乐器按各调性筒音唱名推宫音）。预设表内只存音名/MIDI，
带 A4 校准的实时频率换算由 `TunarEngine`（持有 TunarConfig）在 feed 事件中给出。
（2026-07-20 M3 修复重申）上述预设条目的 `solfege` 字段不随全局唱名体系/调式设置
变化；全局设置仅影响 `TunarEvent.solfege`。理由：乐器面板的「筒音作 X」等唱名基准
必须锚定在该乐器习惯调 / 该 chart 自身调性上（如 D 调曲笛筒音作 5 必须显示 5，
古琴正调必须显示 F 调唱名 5 6 1 2 3 5 6），用全局调式重算会失去定弦/筒音意义。

旧接口移除（2026-09-29）：`list_fingering_charts` / `FingeringChart` / `FingeringNote` 已删除。
三端管乐面板都已改用 `list_wind_variants` + `wind_fingering_chart`，旧形状只剩“作 5 / 作 1 / 作 2”
三档，无法表达孔位、三音区与十二音详情，继续保留只会让新旧两条路径长期分叉。

## 7. 节拍器引擎

- 调度：lookahead 模型。引擎内部维护「下一 tick 的绝对采样序号」（f64 相位精确累加，渲染时 round 到采样，舍入误差 ~1e-16/tick，不随 tick 数线性增长）。`render(buf, frames)` 把 [cursor, cursor+frames) 区间内的 tick 精确混入输出缓冲（重拍/弱拍用不同采样源索引），并返回该区间内的 `TickInfo { 采样偏移, 拍号索引, 是否重拍 }` 列表。
- 参数：BPM 30–250（浮点）、拍号 numerator 1–12 / denominator ∈ {2,4,8}、每拍重音型（accent/normal/mute）、音量。
- tempo/拍号变更在下一采样立即生效（2026-07-20 明确语义：已排程的下一 tick 位置不动，其后的 tick 间隔按变更后参数计算）。
- mute 拍（2026-07-20 明确）：仍产生 TickInfo 事件（供 UI 亮灯），但不混入音色。
- 发声体跨缓冲续放（2026-08-26 修订）：音色长度普遍超过一次渲染的缓冲长度（铃声 250ms =
  11025 采样，而 Apple 侧每次只泵 1024 帧），因此每个 tick 登记为发声体（固定 12 个槽位，
  热路径零分配），未放完的尾巴由后续 `render` 从缓冲头继续混入，直到音色放完；音色长于 tick
  间隔时多个发声体叠加，槽位用尽则抢占 `pos` 最大（衰减最久）的那个。
  `stop()` 即静音，丢弃全部尾巴，与平台侧 pause 丢弃已排队缓冲的行为一致。
  理由：此前发声按 `min(音色长度, 缓冲剩余)` 裁剪，尾巴被丢弃，铃声等长音色被截断成一声闷响。
- tap tempo：输入连续 tap 的采样时间戳序列，取最近 ≤4 次间隔中位数换算 BPM；间隔对应 BPM 超出 30–250 的视为野值丢弃（全部越界则重置 tap 序列）。
- 精度：连续 1000 tick 位置误差 < 1 采样（相对理论时刻累计误差不得线性增长）。
- 实时安全：`render` 路径零分配（TickInfo 写入调用方提供的预分配缓冲或固定容量数组）。

## 8. 测试基线（cargo test 必须全绿）

- pitch：80/110/220/440/880/1046.5/1500Hz ±0.5 cent；噪声/静音 → None。
- note：A4=440↔MIDI69；A4=442 校准偏移；cents 边界 ±50。
- solfege：四种体系 × 至少宫/羽/大调/小调 × 含偏音用例。
- tuning/fingering：预设表完整性（弦数、频率随 A4 换算正确）；洞箫 G/F × 8/6 孔、
  8 孔默认、筒音音高 G=`D4`/62 与 F=`C4`/60、12 档筒音、
  三音区按实测孔位展开（8 孔 `Scale` 19 条、`Chromatic` 32 条）、
  中音区与低音区孔位一致（22 半音例外）而高音区不同、指法表未覆盖的格不返回且无合成
  频率/指法、单一大图目标优先级、行锚点与空白辅助线稳定、转调仅改变唱名相关字段；
  竹笛六孔孔位（10 半音叉口、12/22 半音中音特例、高音只收 24 半音）与三音区唱名；
  尺八五孔、乙/甲/大甲按八度分区共 11 条、不支持转调与十二音。
- metronome：tick 位置、tempo 变更、tap tempo、accent pattern。
- signal：门限上下边界、2 帧确认、3dB 滞回、无限保持、保持期两帧替换、强噪声无效音高。
- reference：12/19/24/31 平均律 80–1500Hz 边界、排序、A4 校准和标签音分。

## 附录 A — UniFFI API 合同（udl 签名，唯一对外接口）

```webidl
namespace tunar_core {
    // ---- 全局 ----
    sequence<Instrument> list_instruments();
    sequence<Tuning> list_tunings(string instrument_id);
    // 管乐接口：型号（调性/尺寸 × 孔制）与按需生成的指法表
    sequence<WindVariant> list_wind_variants(string instrument_id);
    WindChart? wind_fingering_chart(
        string variant_id,
        u8 tongyin_degree,
        FingeringScope scope
    );
    // 两频率间的音分差：1200·log2(freq/target)（§4 公式；无效输入返回 null）
    f64? cents_between(f64 freq_hz, f64 target_hz);
    // 任意 MIDI 音的唱名（随用户唱名体系/调式；用于乐器面板弦/孔唱名显示）
    string solfege_for_midi(SolfegeSystem system, KeyMode key, i32 midi);
};

[Enum]
interface InstrumentKind { String, Wind };

dictionary Instrument {
    string id;            // "guitar" | "ukulele" | "zhudi" | "dongxiao" | "shakuhachi" | "guqin"
    string display_name;  // 中文名
    InstrumentKind kind;
};

dictionary StringSpec {
    u32 index;            // 弦号（从 1 开始，细→粗或按乐器习惯，见 spec-instruments）
    string note_name;     // "E2"
    i32 midi;             // MIDI 音高（随 A4 换算/唱名重算的基准）
    f64 freq_hz;          // 全局接口按 A4=440 换算（见 §6 校准约定）
    string solfege;       // 按乐器习惯调的首调简谱（见 §6）；不随全局唱名设置变化
};

dictionary Tuning {
    string id;            // "standard" | "drop_d" ...
    string display_name;
    sequence<StringSpec> strings;
};

// ---- 管乐孔位/唱名模型（2026-08-25 洞箫先行，2026-09-29 覆盖竹笛与尺八）----
[Enum]
interface HoleMark { Closed, Open, Half };

[Enum]
interface FingeringScope {
    Scale,      // 孔制类：7 个基础孔位 × 三音区实测指法（8 孔洞箫 19 条）；尺八：基本音阶 11 条
    Chromatic   // 孔制类：12 个基础孔位 × 三音区实测指法（8 孔洞箫 32 条）；尺八忽略
};

[Enum]
interface FingeringKind { Sequential, Combination };

[Enum]
interface WindRegister {
    Low,     // 低音（缓吹）；尺八乙音
    Middle,  // 中音（超吹）；尺八甲音
    High     // 高音（急吹）；尺八大甲
};

dictionary TongyinOption {
    u8 degree;             // 0–11；7=作5、0=作1、2=作2
    string solfege;        // "1"、"#1"、"2"…；首调简谱
    boolean common;        // 常用标记，不限制选择
};

dictionary WindVariant {
    string id;                    // "g_xiao_x8"
    string display_name;          // "G调洞箫 · 8孔"
    string key_id;                // "g_xiao"
    string key_name;              // "G调洞箫"
    string hole_system_name;      // "8孔"；固定音阶类为空
    u8 hole_count;                // 尺八为 5（前四后一）
    u8 back_hole_count;
    i32 fundamental_midi;
    string fundamental_note_name;
    boolean supports_tongyin;
    boolean supports_chromatic;
    sequence<TongyinOption> tongyin_options; // 孔制类固定 12 档
    u8 default_tongyin_degree;    // 孔制类默认 7（作5）
};

dictionary WindFingering {
    i32 fingering_id;       // 表内唯一，等于该条的音高半音数
    i32 semitones;          // 当前音高相对筒音低音基准的半音数（决定孔位）
    i32 base_semitones;     // 所属基础孔位行 0–11（音区列分组用）
    WindRegister register;
    string label;           // core 主指法文字
    sequence<HoleMark> holes; // 索引 0=第一孔（最下），不宣称唯一权威指法
    FingeringKind fingering_kind; // 仅供小型“叉/半”提示，不用于分轨
    u8? anchor_hole;        // 最上方开孔；全闭为 null 并锚定出音口
    string note_name;
    i32 midi;
    f64 freq_hz;            // A4=440
    string solfege;         // 随 tongyin_degree 整列同步变化
    boolean in_scale;
    boolean overblown;
};

dictionary WindChart {
    string variant_id;
    string variant_name;
    u8 tongyin_degree;
    string tongyin_solfege;
    u8 tonic_pc;
    string tonic_name;
    string key_display;
    u8 hole_count;
    u8 back_hole_count;
    // 按基础孔位升序；洞箫每个基础孔位携带各音区的实测孔位。
    // UI 映射为低/中/高三列，指法表未覆盖而缺项的格保持空白。
    sequence<WindFingering> notes;
};

[Enum]
interface SolfegeSystem { FixedDo, MovableDo, Numbered, Chinese };

[Enum]
interface ModeKind { Gong, Shang, Jue, Zhi, Yu, Major, Minor };

dictionary KeyMode {
    u8 tonic_pc;      // 主音 pitch class 0-11（C=0）
    ModeKind mode;
};

dictionary TunarConfig {
    f64 sample_rate;
    u32 frame_hop_samples;    // 默认 1024
    f64 a4_hz;              // 415-466
    f32 noise_gate_dbfs;    // 默认 -45
    SolfegeSystem solfege;
    KeyMode key;
    u8 temperament;         // N 平均律，12/19/24/31，默认 12（v4 新增）
};

[Enum]
interface SignalState { Quiet, Acquiring, Tracking, Holding };

dictionary TunarEvent {
    f64 freq_hz;
    string note_name;     // "A4"
    i32 midi;             // 最近 MIDI 音
    f64 cents_off;        // [-50,50)
    f32 clarity;          // 0-1
    string solfege;       // 按 config 唱名体系
    u8 temperament;       // 当前 N（v4 新增）
    i32 temperament_step; // 最近步序 k（A4 为参考）
    f64 temperament_cents;// [-600/N, +600/N)
};

// ---- v4 新增：频谱与泛音 ----
dictionary Partial {
    f64 freq_hz;
    f32 magnitude_db;     // dBFS
    u8 harmonic_index;    // 0=独立音；2,3,4…=基频泛音
    string note_name;     // 独立音时有效（12-TET 命名）
    f64 cents_off;        // 独立音时有效
};

dictionary AnalysisFrame {
    TunarEvent? tuner;          // Tracking/Holding 时为当前或最后有效读数
    sequence<f32> spectrum_db;  // 64 bin，对数轴 60-2400Hz，dBFS -80~0
    sequence<f32> wide_spectrum_db; // 128 bin，对数轴 20Hz-wide_spectrum_max_hz
    f64 wide_spectrum_max_hz;   // min(20000, sample_rate/2)
    sequence<f32> waveform_min; // 256 列当前窗口最小值包络
    sequence<f32> waveform_max; // 256 列当前窗口最大值包络
    u64 sample_position;        // 当前帧末端的单调采样位置
    f64 sample_rate_hz;         // 实际分析采样率
    sequence<Partial> partials; // ≤8
    string? chord;              // 如 "Cmaj"，无则为 null
    SignalState signal_state;
    f32 input_level_dbfs;
    f32 display_strength;       // 0~1；Tracking/Holding 为 1
    boolean is_held;
};

dictionary ReferenceTone {
    i32 step_from_a4;
    f64 frequency_hz;
    u8 temperament;
    string note_name;          // 最近 12-TET 音名
    f64 cents_from_note;
};

interface TunarEngine {
    constructor(TunarConfig config);
    TunarEvent? feed(sequence<f32> pcm);   // 零分配路径；无效输入返回 null
    AnalysisFrame analyze(sequence<f32> pcm);  // v4 新增：频谱+泛音+和弦（UniFFI 边界允许分配）
    void set_a4(f64 hz);
    void set_solfege(SolfegeSystem system, KeyMode key);
    void set_noise_gate(f32 dbfs);
    void set_temperament(u8 divisions);    // v4 新增
    sequence<ReferenceTone> list_reference_tones();
};

[Enum]
interface TickAccent { Accent, Normal, Muted };

dictionary TickInfo {
    u64 sample_offset;    // 相对本次 render 缓冲起点
    u32 beat_index;       // 小节内第几拍（0 起）
    TickAccent accent;
};

dictionary MetronomeConfig {
    f64 sample_rate;
    f64 bpm;              // 30-250
    u8 beats_per_bar;     // 1-12
    u8 beat_unit;         // 2|4|8
    sequence<TickAccent> accents;  // 长度 = beats_per_bar
};

interface Metronome {
    constructor(MetronomeConfig config);
    // 渲染 frames 个采样（含精确混入的 tick 音色），返回 PCM 与 tick 事件。
    // 注：UniFFI 边界允许分配（marshal 开销主导）；引擎内部逻辑零分配，
    // 实时安全约束作用于引擎内核（操作 &mut [f32] 的预分配缓冲）。
    RenderFrame render(u32 frames);
    void set_bpm(f64 bpm);
    void set_time_signature(u8 beats, u8 unit);
    void set_accents(sequence<TickAccent> accents);
    void set_click_samples(sequence<f32> accent, sequence<f32> normal);  // 由原生层注入铃声音色
    f64 tap(u64 timestamp_samples);   // 返回当前 BPM
    void start(u64 at_sample);
    void stop();
    boolean is_running();
};

dictionary RenderFrame {
    sequence<f32> samples;
    sequence<TickInfo> ticks;
};
```

实现注（2026-07-20）：Rust 侧命名按惯例使用 snake_case（list_instruments 等与上表一一对应），
UniFFI 生成端自动转为各语言惯例；本附录语义未变。§3 精化算法、§6 校准约定、
§7 tempo 变更/mute/tap 野值语义为本次实现期修订（理由见对应章节）。

2026-07-20（M3）：新增全局函数 cents_between / solfege_for_midi；StringSpec、
FingeringNote 增加 midi 字段（理由：乐器面板需要「目标 cents 换算」（§4 公式，属 core 职责）
与「随用户唱名体系重算每弦/每孔唱名」，原接口只有 A4=440 固定换算结果，不含 MIDI 基准。

2026-08-25：新增 `HoleMark`、`FingeringScope`、`FingeringKind`、`WindRegister`、
`TongyinOption`、`WindVariant`、`WindFingering`、`WindChart`，以及 `list_wind_variants` /
`wind_fingering_chart`。旧 `list_fingering_charts` 明确为从新模型派生的兼容 shim，继续返回
默认孔制与作 5/1/2 旧表，不扩展其返回形状。

2026-09-29：删除 `list_fingering_charts` / `FingeringChart` / `FingeringNote`（理由见 §6 末）。
尺八改由自带孔位表返回 `hole_count = 5`、`back_hole_count = 1` 与各音孔位，并按八度填写
`register`；竹笛换用独立的六孔竹笛孔位表。

变更规则：任何签名/类型修改必须先改本附录并注明版本日期。
