# 品牌与双语文档验收

## 场景 1：系统语言决定应用名称

Given 设备系统语言为简体中文  
When 安装或升级应用  
Then Android 启动器与 iOS 主屏显示「吐呐」

Given 设备系统语言不是简体中文  
When 安装或升级应用  
Then Android 启动器与 iOS 主屏显示 `TUNAR`

## 场景 2：技术标识全面迁移为 tunar

Given 项目已完成 tuner → tunar 的全量改名（经确认的破坏性迁移）  
When 检查仓库中的技术标识  
Then Rust crate 为 `tunar-core`、Java package 为 `com.liuchong.tunar`、iOS target/scheme 为 `Tunar`、Bundle ID 为 `com.liuchong.tunar*`、UniFFI 绑定为 `tunar_core`  
And 旧版（`com.liuchong.tuner`）安装会被系统视为新应用，不保证原位升级与设置继承  
And 功能命名中的 `tuner`（调音功能页，如 `TunerView`）允许保留

## 场景 3：两种语言均可进入完整文档

Given 读者打开仓库根 README  
When 选择 English 或简体中文入口  
Then 能访问核心、乐器、界面、音频、设计系统和路线图六份规格  
And 两种语言的文档链接均不存在仓库内断链
