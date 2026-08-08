# Apple 通用核心与 macOS 生命周期

## 通用 XCFramework

- iOS 与 macOS 共用同一份 UniFFI Swift 绑定；生成代码不得依赖应用 target 的模块名。
- macOS 的 `aarch64-apple-darwin` 与 `x86_64-apple-darwin` 静态库先用 `lipo` 合并，再作为
  一个 `macos-arm64_x86_64` slice 加入 XCFramework。
- 替换 XCFramework 后使用全新的 DerivedData 验证两种架构，避免 Xcode 沿用旧的
  `AvailableLibraries` 元数据。
- 交付前同时检查 XCFramework 的 `Info.plist` 与 `lipo -info`；目录存在不等于二进制真的
  包含两种架构。

## macOS 音频边界

- macOS 直接使用 `AVAudioEngine`，不得调用仅属于 iOS 的 `AVAudioSession`。
- 调音、乐器和专业分析仍共用一个 `CaptureHub`；窗口失活与页面离开都必须释放订阅，使旧
  异步启动令牌失效。
- 0Hz、0 声道或引擎启动失败时不能安装无效 tap。失败状态要回到主线程发布，且只有启动令牌
  仍有效时才显示重试入口，避免旧会话覆盖新页面状态。
- 声音回调仍只写预分配环形缓冲；错误状态、界面发布和重试逻辑均不得进入回调路径。

## 验证顺序

1. 在全新 DerivedData 运行 macOS 单元测试。
2. 分别构建 arm64 与 x86_64 应用。
3. 重跑 iOS 测试，验证共享 Swift 采集层没有回归。
4. 最后在安静环境人工验证权限、参考音回采、节拍器和窗口失活行为。
