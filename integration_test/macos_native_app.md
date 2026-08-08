# macOS 14+ 原生应用 BDD 验收

## 场景 1：五个桌面入口与直接跳转

Given macOS 14+ 启动吐呐  
When 用户依次选择侧栏  
Then 只能看到调音、乐器、专业分析、节拍器、设置五项  
And 默认进入调音  
And 点击调音页频谱预览后选中同一个专业分析入口

## 场景 2：麦克风只启动一路

Given 调音页已经订阅采集  
When 用户切到乐器或专业分析  
Then 页面复用同一个 `CaptureHub` 和 Rust `AnalysisFrame`  
And 系统音频回调只写预分配环形缓冲  
And 不创建第二路麦克风或第二次 FFT
When 应用窗口失活  
Then 当前页面释放采集订阅  
And 旧的异步启动不得重新打开麦克风

## 场景 3：权限拒绝与无效输入

Given 麦克风权限被拒绝或输入格式为 0Hz/0 声道  
When 用户进入调音、乐器或专业分析  
Then 应用显示可理解的重试/系统设置入口  
And 不安装无效 tap  
And 不崩溃或影响节拍器、设置页

## 场景 4：调音保持与音叉

Given core 已确认一个音高  
When 声音消失但没有新音高连续两帧越过门限  
Then macOS 继续显示最后可信读数且不本地超时清空  
When 用户播放 core 提供的 A4 并关闭音高选择窗  
Then 声音继续且调音指针仍工作  
And 离开调音页或窗口失活后停止播放

## 场景 5：乐器调音

Given 用户选择任一弦乐定弦或管乐指法表  
When Rust 返回新的可信读数  
Then 页面用 core 提供的预设和音分换算更新唯一目标  
And Holding 期间保持目标而不补画历史指针

## 场景 6：专业分析

Given 专业分析正在接收帧  
When 用户切换乐音/全频频谱、音高轨迹和波形  
Then 所有视图来自同一帧且历史不因切换被清空  
When 用户暂停或重置峰值  
Then 暂停冻结全部图表  
And 重置只清固定峰值保持  
And 连续图谱仍有时间、频率和强度刻度

## 场景 7：节拍器与设置

Given 用户选择拍号、每拍重音和十二种音色之一  
When 播放节拍  
Then Rust core 负责节奏和采样位置，macOS 只负责输出和闪拍  
When 用户修改 A4、噪声门限、唱名或律制  
Then 设置写入桌面端独立持久化容器并即时下发共享采集引擎

## 场景 8：跨架构构建

Given Apple Silicon 与 Intel Rust 目标均可用  
When 执行 Apple 核心和 macOS 构建脚本  
Then XCFramework 包含通用 macOS slice  
And macOS app 与测试 target 均能链接同一份 UniFFI 合同
