# 品牌与双语文档验收

## 场景 1：系统语言决定应用名称

Given 设备系统语言为简体中文  
When 安装或升级应用  
Then Android 启动器与 iOS 主屏显示「吐呐」

Given 设备系统语言不是简体中文  
When 安装或升级应用  
Then Android 启动器与 iOS 主屏显示 `TUNAR`

## 场景 2：升级不改变应用身份

Given 用户已经安装技术标识为 `com.liuchong.tuner` 的旧版本并保存了设置  
When 安装改名后的版本  
Then Bundle ID、Java package、Rust crate、Xcode scheme 与持久化键保持不变  
And 系统执行原位升级而不是安装第二个应用  
And 已保存设置继续可读

## 场景 3：两种语言均可进入完整文档

Given 读者打开仓库根 README  
When 选择 English 或简体中文入口  
Then 能访问核心、乐器、界面、音频、设计系统和路线图六份规格  
And 两种语言的文档链接均不存在仓库内断链
