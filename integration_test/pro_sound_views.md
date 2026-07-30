# 专业声音视图 BDD 验收

## 场景 1：一次分析同时提供三种真实数据

Given 采样率 44100Hz、hop 1024，输入 440Hz 与 10000Hz 合成信号  
When core 分析一个 2048 样本窗口  
Then 乐音频谱固定返回 64 桶  
And 全频段频谱固定返回 128 桶，覆盖 20–20000Hz  
And 波形最小/最大包络各返回 256 个有限值  
And 两套频谱来自同一次 FFT  
And 10000Hz 在全频段中形成可辨峰，而不要求出现在乐音频谱中

## 场景 2：采样率决定全频上限与单调时间

Given 采样率 32000Hz、hop 800  
When 连续分析三帧  
Then `wide_spectrum_max_hz` 为 16000Hz  
And `sample_position` 依次增加 800  
And 平台无需系统时钟即可换算轨迹时间

## 场景 3：音高轨迹不伪造持续发声

Given 最近 12 秒依次收到 Tracking、Holding、Quiet、Tracking 帧  
When 绘制音高轨迹  
Then 只为两个 Tracking 帧记录真实点  
And Holding 与 Quiet 形成断点  
And 两段之间没有连接线

## 场景 4：暂停与恢复

Given 频谱、轨迹、波形和连续图谱正在更新  
When 用户点击暂停并继续收到分析帧  
Then 三种主图、峰值保持、轨迹和连续图谱均不变化  
When 用户恢复  
Then 新帧继续更新  
And 音高轨迹从新段开始，不连接暂停前后的点

## 场景 5：无效输入与权限边界

Given PCM 含非有限值或设备拒绝麦克风权限  
When 进入专业声音分析  
Then core 输出的波形与频谱不含非有限值  
And 原生界面显示权限引导而不崩溃  
And 不创建第二路采集

## 非目标

本阶段不录音、不写音频文件、不回放、不提供时间轴拖动或导出，也不显示这些入口。
