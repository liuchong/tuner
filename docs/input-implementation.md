# 输入判定与保持施工手册

基线 `67ecbd6` / master，开工干净。范围为 input-behavior.md 的Q1–Q8、A1–A12与三端全部接线；不启动goal。
图谱未索引本仓库，相关文件已直接查读。当前/目标树、旧数据流见该设计§1/§6；本手册补足施工决定，作为本轮唯一进度入口。
顺序：A设计→B全部测试源与原生步骤（不运行）→C全部实现→D统一构建/测试/修复/原生验收/签名推送。

## 1. 最终合同与设计精化

1. 保留旧`feed/analyze`的两帧兼容语义；生产CaptureHub统一改用`analyze_input`的新60ms音频时间合同。旧方法生成LegacySmoothed来源，新入口生成AcceptedRaw来源；不能把兼容路径当作生产验收。
2. `input_policy.rs`定义GateMode/InputProfile/ResponseMode/HoldMode/InputPolicy，按设计表解析。新增ResolvedInputPolicy保存判定字段、实际开关门限、背景、policy_version=1、policy_revision、calibration_revision。响应/保持不参与判定revision比较，不切段。
3. `InputDiagnostics { reason, clarity, clipped_fraction, policy:ResolvedInputPolicy, metronome_risk:bool, response:ResponseMode, hold:HoldMode }`。所有数值有限，clarity/削波比例0…1；reason含Calibrating。新增字段均有serde默认，以便读schema1。
4. InputFrameContext包含真实sample_position、capture_generation、reference_active（含原有尾保护）、metronome_active。重复/倒退位置返回InvalidFrame且无状态改变；跨代次清瞬态和校准。丢窗>1.5hop重置确认；不压缩音频时间。
5. InputRuntime私有固定状态：policy、resolved、calibration、128个电平槽、采样/采集上下文、pending_since、last_accepted、last_valid_position、octave_pending、显示logHz及自动音名候选。无平台时钟，无热路径堆增长。校准20Hz等时采样2秒，5秒截止；P80，P95−P20>12失败；持续clarity≥0.85音调≥200ms进入NeedsQuietConfirmation。取消/失败同上下文保留旧floor；input reset失效回退。
6. 新入口每帧：验证上下文→输入RMS/削波→校准→YIN一次CMNDF候选→60ms确认/80ms八度跳变保护→可信频率→独立显示logHz滤波→同窗FFT/包络/诊断。输入/校准/参考输出/削波的有效性由core决定。音名跨55c并持续60ms再切标签；换音≥80c重置显示滤波。缺口从首帧起无效，100ms内原因Dropout，之后为实际失效原因。
7. 候选排序定型：最多8个CMNDF局部低谷；保留既有最短可信周期为第一候选，最优竞争周期为第二候选。仅这两者进行谐波LS精化；解释分数为0.7×周期质量+0.3×归一化谐波解释度，差<0.02优先较短周期，避免子谐波无限锁定。目标音不参与选择。仅当相对上一可信频率近八度（1200±80c）跳变时积累80ms一致证据，最长120ms歧义；短异常无效且不污染测量。真实持续八度仍可通过。
8. `AnalysisFrame`尾增`input_diagnostics:Option<InputDiagnostics>`、`measured_freq_hz:Option<f64>`，旧构造/绑定同步；tuner.freq_hz保留显示频率，新测量音分只用measured。失效帧不得把旧显示频率当measured。参考输出可实时监听但不计成绩。
9. 引擎增加`presentation_frame()->Option<AnalysisFrame>`，缓存最近可信整帧供既有调音/乐器页使用；保持副本只标Held/原因，Hz/频谱/包络/原时间不混入新窗口。CaptureHub.events发布呈现帧（无呈现时清tuner），measurements发布真实当前帧。实时InputStatus单独发布。两种流由同次分析产生，无第二FFT。
10. PracticeSession沿用真实observe流，增加`presentation()->PracticePresentation`、`set_input_available(bool)`，source Live/Held/Review；含measurement、previous_measurement、live_input、held_age_seconds、summary。最多缓存两份可信点/配置；停吹摘要以最后可信时间为末端，重用现有时间加权分位函数，300ms/150ms门槛。Review优先。保持策略纯函数在core共用；输入中断不算普通静音。
11. PracticeSegment追加可选input_policy；同配置判定revision变化切段；response/hold不切段。新MeasurementBasis枚举LegacySmoothed/AcceptedRaw写到PracticePoint，serde默认旧来源。schema2写出，1/2读入，旧数据不回算。schema2严格校验来源/可信频率/诊断/分段策略一致；CSV追加来源、测量Hz、显示Hz、原因。
12. 偏好：两端持久化gateMode/profile/response/hold/hysteresis，已有旧门限键且无新gateMode时迁移Manual；原用户数值保持。新用户Auto，默认General/Balanced/UntilNext。应用普通A4设置不得意外把Auto改成Manual；Hub先应用兼容config，再仅在判定字段改变时下发InputPolicy。引擎重建始终重发当前策略。
13. 校准从设置/工作区同组入口发起；成功开始时暂停记录，完成/取消不自动继续。页面须获取同一CaptureHub、处理权限/无设备、可取消和确认持续音调。各平台只绑定状态，不计算阈值或定时。
14. 节拍器活动只产生metronome_risk，扬声器跟拍提示耳机，不屏蔽整段。参考音活动保留现有尾保护。没有可信点击时钟不能声称已经移除节拍污染。实际输出活动按播放器生命周期在原生供给。

## 2. 精确接口

新增`InputPolicyError { InvalidPolicy, InvalidFrame, OutputActive, NoCalibration }`（UniFFI flat error，原生映射可操作文本）。
新增`InputStatus { available:bool, input_level_dbfs:f32, diagnostics:Option<InputDiagnostics>, calibration:CalibrationStatus,output_active:bool }`。
`CalibrationStatus {state:CalibrationState, floor_dbfs:Option<f32>,elapsed_seconds:f64,reason:String}`。
`LiveInput {input_level_dbfs:f32,diagnostics:InputDiagnostics}`；`NoteSummary {median_cents:f64,low_cents:f64,high_cents:f64,valid_seconds:f64}`。
`PracticePresentation {source:PresentationSource,display_cents:Option<f64>,measurement:Option<PracticePoint>,previous_measurement:Option<PracticePoint>,live_input:Option<LiveInput>,held_age_seconds:Option<f64>,summary:Option<NoteSummary>}`。

TunarEngine worker API：
```rust
set_input_policy(policy: InputPolicy) -> Result<(), InputPolicyError>
input_policy() -> InputPolicy
begin_noise_calibration(output_active: bool) -> Result<(), InputPolicyError>
confirm_noise_environment() -> Result<(), InputPolicyError>
cancel_noise_calibration()
calibration_status() -> CalibrationStatus
input_status() -> InputStatus
analyze_input(pcm: Vec<f32>, context: InputFrameContext) -> Result<AnalysisFrame, InputPolicyError>
reset_input_context()
presentation_frame() -> Option<AnalysisFrame>
```

现有PitchEngine两端门面增加同名方法；测试替身可以保留默认兼容实现，真实适配器必须全部调用生成绑定。
Hub增加applyInputPolicy、begin/confirm/cancelCalibration与InputStatus发布，错误保留在status.reason，不吞掉。锁只在工作线程，不进入OS回调。

## 3. 文件级清单

| ID | 基线锚点 | 施工动作 | 验收 |
|---|---|---|---|
| C01 | 新`core/src/input_policy.rs`，`lib.rs`，`api.rs:280,337,773,818,895` | 类型/解析/校准/时间判定/独立显示/引擎入口与整帧保持，pub文档 | A1/A2/A4/A5/A9/A11 |
| C02 | `pitch.rs:46`、`harmonic_ls` | 一次CMNDF、固定候选与两次拟合上限，保持legacy入口 | A1/A3/A4 |
| C03 | `practice.rs:151,359,508,636`、`measurement_file.rs` | raw音分、source、输入策略分段、呈现与摘要、schema1/2与CSV | A5–A10 |
| C04 | Apple `Bridge/EngineBridge.swift:18`、`Audio/AudioCapture.swift:196,293,362` | 新真实上下文分析、两种流、状态、校准命令、重启策略恢复 | A2/A9/A11 |
| C05 | Apple `Settings/SettingsStore.swift:24`、`SettingsView.swift:90`；新`Practice/InputControlsView.swift` | 可注入偏好存储/迁移；三项设置、校准状态/确认、手动高级值与恢复默认 | A9/A12 |
| C06 | Apple `PracticeWorkspaceModel.swift:60,86,106`、`PracticeWorkspaceView.swift:144`；既有Tuner/Instrument VM | 用core呈现/summary/状态与实时电平；停准音触觉；中断保持与窗口/手机入口 | A6/A7/A8/A12 |
| C07 | Android `core-binding/.../corebinding/TunarCore.kt`、`audio/CaptureHub.kt:73,86`、`ReferenceTonePlayer.kt`及节拍播放器 | 对应真实适配/上下文/呈现流/校准和输出活动 | A2/A9/A11 |
| C08 | Android `data/SettingsRepository.kt:32,110`、`ui/settings/SettingsViewModel.kt:22`和SettingsScreen；新`ui/practice/InputControls.kt` | 持久化迁移与三项/高级/校准交互 | A9/A12 |
| C09 | Android `PracticeWorkspaceViewModel.kt:88`、`PracticeWorkspaceScreen.kt:148`和Tuner/Instrument VM | raw读数、整帧保持、summary、来源/风险/实时输入 | A5–A8/A12 |
| C10 | tests、生成绑定、Xcode工程、scripts/verification、全部相关中英文规格/路线图 | 全测试源先写；C后统一生成，D逐例报告/截图/差异检查/签名交付 | 全部 |

当前树/流是YIN→Hz中值/EMA→旧Tracker→AnalysisFrame→Session；目标为InputPolicy/真实时钟→候选→时间判定→可信raw测量→存档/统计；独立显示滤波→统一呈现，另列实时输入。所有旧结构仍复用，新增仅input_policy及两端InputControls文件，不新建第二套页面历史真值。

## 4. 唯一进度

- [x] A 精化全部合同、文件图景、确定候选评分与兼容策略。
- [x] B 全部新增/变更测试源、三端原生步骤写完，未执行。
- [x] C 全部生产实现与接线完成，未执行验证。
- [x] D 统一构建、绑定生成、分层测试与可用原生验收、报告完成；签名推送以本交付提交的真实状态核定。

施工写完即勾，A验收另记：
- [x] C01 核心策略/校准/时间/引擎
- [x] C02 候选选择
- [x] C03 会话/呈现/格式
- [x] C04 Apple采集/桥接
- [x] C05 Apple偏好/控制
- [x] C06 Apple全部读数/工作区
- [x] C07 Android采集/桥接
- [x] C08 Android偏好/控制
- [x] C09 Android全部读数/工作区
- [x] C10 生成输入/双语规格/验证脚本

## 5. 测试合同

新增Rust `input_policy_tests.rs`（纯策略、时钟、校准、参数验证）、`tests/input_workflow.rs`（真实PCM→新入口→会话→文件与呈现）；复用既有legacy回归但不把旧入口等同新生产链。
Apple新增`InputPolicySettingsTests.swift`和共享PracticeWorkspaceTests，Android扩充SettingsRepository/Hub测试并新增原生InputControls/呈现流程。已有AnalysisFrame夹具追加两个可空字段。
原生A1–A12沿input-behavior.md步骤补充到`integration_test/input_behavior.md`；实际物理乐器/设备中断没有条件时保留缺口。
最后分层运行、逐例计时、绑定源码/配置/失败修复记录；复用已有Rust计时和XCTest/JUnit报告，不新造测试平台。日志放项目忽略的本机证据目录。

C阶段接线完成：macOS独立设置页复用输入控件；Android工作区绑定同一偏好与Rust呈现。统一生成绑定与所有正确性检查在D开始后进行。

D验收修正：20Hz校准采样采用固定截止位置，避免hop取整累积；恢复输入清旧中断文案但不自动续录；Apple环16槽且主队列按代次丢弃晚到发布；Android分析后再次检查代次并在停止完成后发布不可用，工作区跳过停机缓存包。新增大批量与中途停止用例。

最终可执行验收：374项通过（Rust155/23、iOS76、macOS26、Android87/7）。完整物理矩阵与iOS模拟器GUI缺口见[报告](verification/input-behavior/report.md)。

文件回看收尾：导入时从最后帧所属配置段恢复缓存配置；与当前条件不同的结果进入previous_measurement，不将新目标套到旧帧。扩展现有导入/选区回归断言，未重复计数。
