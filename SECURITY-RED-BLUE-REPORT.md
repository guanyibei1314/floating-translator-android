# 悬浮翻译红蓝队安全测试报告

## 结论

当前 0.2.1 ARM64 精简包未发现高危或中危的跨应用屏幕读取、MediaProjection 授权绕过、组件越权启动、屏幕文字外传或硬编码密钥问题。

历史版本的静态审计发现的问题已在后续版本修复；0.2.1 继续保留会话复用、真实 OCR/翻译和 ARM64 精简构建：

1. Android 8–12 的动态广播接收器没有权限保护，其他应用可以伪造“捕获完成”消息，弹出假的结果面板。修复方式：加入签名级自定义权限；Android 13 及以上继续使用 `RECEIVER_NOT_EXPORTED`。
2. 屏幕捕获被系统停止、初始化失败或重复启动时，可能遗留前台服务和图像资源。修复方式：统一失败清理、处理 `onStop`、拒绝重入、处理空 `VirtualDisplay` 和异常释放。
3. 发布构建未启用 R8，容易暴露实现细节。修复方式：开启 release 混淆/压缩规则，并保留必要的 Android 入口类。
4. 原实现每次点击都创建并销毁一个 MediaProjection 会话，导致 Android 14+ 每次都再次弹出系统授权。修复方式：首次授权后由 `ScreenCaptureService` 持有一个会话和一个 `VirtualDisplay`，后续点击只发送内部请求；关闭悬浮按钮、主界面停止或系统 `onStop()` 时统一释放。

会话控制增加了每次请求的帧序号、超时、会话 generation 防旧回调误伤新会话，以及投射通知中的“停止屏幕读取”操作；不会直接复用上一次请求的成功状态。0.2.1 新增 ML Kit Latin OCR 和设备端英译中：OCR 模型随 APK 提供，翻译模型首次使用时按需下载；网络权限仅用于模型下载，屏幕文字不上传到服务器。

## 红队检查

- 检查了 Manifest 导出的 Activity、Service、权限和前台服务类型。
- 检查了 MediaProjection 授权 Intent 是否能被外部应用注入或重放。
- 检查了动态广播伪造、Intent extra 信任、overlay UI 欺骗和 clipboard 数据流。
- 检查了网络、日志、文件写入、WebView、硬编码 token/API key 和备份配置。
- 检查了 APK 的权限清单、组件清单、ZIP 完整性和签名。

旧版本可用于验证低危广播问题的测试命令如下；本次环境没有 ADB，因此没有执行动态命令：

```text
adb shell am broadcast -a com.example.floatingtranslator.action.CAPTURE_FINISHED --package com.example.floatingtranslator --ez capture_completed true
```

该路径只影响旧系统上的结果面板，不会提供屏幕像素或 MediaProjection 令牌。

## 蓝队验证

- `CapturePermissionActivity` 必须收到系统 `RESULT_OK` 和非空授权 Intent 后，才会启动私有的 `ScreenCaptureService`。
- 捕获服务和悬浮服务均为 `android:exported="false"`，没有对外绑定接口。
- 屏幕帧获取后立即交给设备端 ML Kit OCR，处理后关闭；未发现屏幕文字上传、文件持久化或应用日志输出。持续会话只在内存中保留 `MediaProjection`、`VirtualDisplay` 和 `ImageReader` 引用，不保存授权 `Intent`。
- 每次点击只接受点击之后到达的新帧；无新帧会在超时后返回失败并清除 pending 状态，旧会话的回调会被 generation 检查丢弃。
- 屏幕读取通知带有“停止屏幕读取”操作；主界面停止按钮和悬浮服务销毁也会调用同一释放路径。
- `allowBackup="false"`，APK 仅声明 ML Kit 模型下载所需的网络权限，未发现 WebView 或外部 URL 加载。
- APK 通过 v2/v3 签名校验和 ZIP 对齐校验；0.2.1 只包含 `arm64-v8a` 原生库，旧版若签名不同，安装前可能需要卸载旧包。
- 0.2.1 的 release 构建启用 R8；最终 DEX 未发现调试编译标记或 Kotlin 源文件名。
- APK 的最低 API 为 26，目标 API 为 35；当前设备仍需在荣耀 200 Pro / MagicOS 10.0 上实机确认首次授权、连续点击免弹窗、通知停止和后台保活行为。

## 未完成的动态验证

本次环境没有实体荣耀 200 Pro、ADB 或 Android Emulator，因此没有声称完成以下测试：真实安装、首次/重复点击授权弹窗、跨应用广播攻击、录屏停止回收、MagicOS 后台清理、横竖屏和低电量模式。
