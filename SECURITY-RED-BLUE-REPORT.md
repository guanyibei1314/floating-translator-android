# 悬浮翻译红蓝队测试报告（0.3.0）

对象：本仓库 `versionCode 7` / `0.3.0` 的源码，以及 0.3.0 编译产物的字节码。

上一版报告针对 0.2.1，其中"网络权限仅用于模型下载"的说法经 APK 静态分析证伪，已在本版更正。逐条问题清单见 `SECURITY-AUDIT-2026-08.md`。

## 本次实际执行了什么

**执行了：**

1. **全量类型检查 / 编译**。用 Kotlin 2.0.21 把 `app/src/main/java` 下全部源码编译到 JVM 字节码，classpath 是**真实的 Android 15（API 35）framework 类**（Robolectric `android-all-15-robolectric-13954326`，186 MB）。结果：0 error、0 warning。ML Kit 与 aapt2 的 `R` 类用等价签名的桩代替（Google Maven 在本沙箱被出网策略拦截）。
2. **字节码调用面清点**。对编译出的全部应用类做 `javap -c`，提取 300 个不同的调用目标，逐类搜索可能把数据送出进程的原语。
3. **不变式检查**。直接在字节码里确认屏幕像素通路的开关点各自只有哪些调用方。
4. **结构性检查**。确认承载屏幕文字的类型无法被序列化跨进程。

5. **完整 APK 构建（在 GitHub Actions 上）**。本沙箱装不了 Android SDK，但 CI runner 可以：`assembleRelease` 用真实 AGP 8.7.3 + 真实 ML Kit 依赖 + R8 构建通过（2 分 45 秒），`lintRelease` 通过，`gradle-wrapper.jar` 通过 Gradle 官方校验和验证。见 run `32464706824`。

   第一次 CI 构建失败于 `:app:processReleaseMainManifest`：本次修改在 `AndroidManifest.xml` 的注释里引入了一个 `--`，XML 注释不允许该序列，导致 manifest 无法解析。已修复，并在工作流中加了 XML 格式前置检查。**这个问题是纯 Kotlin 类型检查发现不了的**，说明只做源码级验证不够。

**没有执行（本环境没有实体机、模拟器、ADB，且 `dl.google.com` / `android.googlesource.com` / `services.gradle.org` / Actions artifact 的 blob 存储均被出网策略拦截）：**

- 在本沙箱内构建 APK、下载 CI 产物做二进制复核
- 安装、真机运行
- `adb shell am broadcast` 之类的动态注入
- 抓包确认 ML Kit 遥测的实际流量
- MagicOS 后台保活、授权弹窗、剪贴板预览气泡的实机表现

**因此本报告只能声称静态结论。** 下面每条都注明了证据来源。

## 红队

| # | 攻击目标 | 结果 | 证据 |
| --- | --- | --- | --- |
| R-1 | 伪造"翻译完成"消息，让悬浮面板显示攻击者控制的文字 | **入口已不存在** | 应用字节码中 `sendBroadcast` 调用数为 0；不再注册任何动态 `BroadcastReceiver`；Manifest 中自定义权限已删除 |
| R-2 | 在 IPC 途中截获识别出的屏幕文字 | **文字不再进入 IPC** | `putStringArrayListExtra` 调用数为 0；全部 `putExtra` 的 key 只有 `capture_request_id`(long)、`projection_result_code`(int)、`projection_data`(Parcelable)、`capture_requested`(boolean)；`CaptureResult` 既非 `Parcelable` 也非 `Serializable`，结构上无法放进 Intent |
| R-3 | 从外部应用直接拉起捕获服务 | **不可达** | 两个 Service 与 `CapturePermissionActivity` 均 `exported="false"`；合并后 Manifest 中 ML Kit / GMS 引入的 7 个组件也全部 `exported="false"`；唯一导出的 `MainActivity` 不读取任何 Intent extra |
| R-4 | 注入或重放 MediaProjection 授权令牌 | **不可达** | 令牌只从系统 `onActivityResult` 经显式 Intent 流向私有服务；`startCaptureSession` 要求 `RESULT_OK` 且 data 非空 |
| R-5 | 在用户没有请求翻译时读取屏幕 | **已封堵**（0.2.1 的主要暴露面） | `attachCaptureSurface()` 在字节码中只有一个调用方 `queueTranslationRequest()`；`detachCaptureSurface()` 被 5 个终止路径调用：请求超时、`startFrameProcessing` 的守卫分支、`startFrameProcessing` 取到帧之后、`finishProcessing`、`releaseCapture` |
| R-6 | 事后从剪贴板捞回屏幕文字 | **已收敛** | Android 13+ 设置 `ClipDescription.EXTRA_IS_SENSITIVE`（字节码可见 `ClipDescription.setExtras`）；悬浮服务销毁时 `clearOwnClipboard()` 按 label 确认剪贴板仍是本应用写的才清除 |
| R-7 | 用别的应用截图 / 录屏抓走结果面板 | **已封堵** | 面板窗口 flags 常量折叠后为 `8232` = `FLAG_NOT_FOCUSABLE(8) | FLAG_NOT_TOUCH_MODAL(32) | FLAG_SECURE(8192)`，字节码可验证 |
| R-8 | 用恶意悬浮窗遮挡诱导点击"复制" | **已封堵** | 两个按钮均 `filterTouchesWhenObscured = true`（字节码中两处 `setFilterTouchesWhenObscured`） |
| R-9 | 从进程内存里翻出刚才的屏幕像素 | **部分缓解** | 释放前对可变 Bitmap 调 `eraseColor`（字节码可见 `Bitmap.isMutable` + `Bitmap.eraseColor`）。属尽力而为：`ImageReader` 自身的缓冲区由系统持有，本应用无法擦除 |
| R-10 | 借应用自身代码把数据写盘 / 打日志 / 发网络 | **无此代码** | 300 个调用目标中，网络、文件写入、`SharedPreferences`、`android.util.Log`、反射、`ClassLoader`、`Runtime`、`ProcessBuilder` 的命中数均为 0 |
| R-11 | 通过备份 / 换机迁移把应用数据带走 | **已封堵** | `allowBackup="false"` 之外新增 `dataExtractionRules`，`cloud-backup` 与 `device-transfer` 两个通道的全部 domain 都被 exclude |
| R-12 | 抢注同名自定义权限 | **不再适用** | 该权限已随广播一起删除 |

## 蓝队

- **屏幕像素通路**：`MediaProjection` 与 `VirtualDisplay` 各只创建一次（字节码中 `createVirtualDisplay` 调用点为 1 处，满足 Android 14+ "每个 MediaProjection 只允许一次"的限制），像素的开关完全由 `VirtualDisplay.setSurface()` 控制。点击"译"之前和取到一帧之后，display 没有输出 surface，本进程收不到任何屏幕内容。
- **每次请求一个新 `ImageReader`**：上一次请求的缓冲区不可能被下一次请求拿到；`onImageAvailable` 里额外用 `reader !== imageReader` 丢弃属于旧 reader 的帧。
- **会话隔离**：generation + requestId 双重校验保留，旧会话的异步回调不会影响新会话，也不会把上次的成功结果复用给新请求。
- **释放路径**：`endSession()` / `onDestroy()` / `MediaProjection.Callback.onStop()` / 主界面停止按钮仍然汇聚到 `releaseCapture()`，且 `releaseCapture()` 第一步就是断开 surface。
- **超时自愈**：请求超时会直接结束整个会话。理由是给镜像 display 挂上 surface 后本应立刻出帧，超时说明该会话已经不可用；结束它之后下次点击会重新走授权，而不是留下一个永远拍不到东西的会话。
- **进程内投递**：`CaptureBus` 是普通的 JVM 单例 + `CopyOnWriteArrayList`，回调统一 post 到主线程，与原先 `BroadcastReceiver` 的线程语义一致。

## 仍然存在的暴露面（未消除，如实记录）

1. **ML Kit 遥测**。APK 中 `enableFirelog=true`，并带有 Firebase Installations（`firebaseinstallations.googleapis.com`）与 Remote Config 端点，以及 CCT/datatransport 上报组件。这意味着应用会向 Google 发送使用统计，并持有一个跨会话可关联的安装标识。**屏幕文字不在其中**——OCR 与翻译都在端侧完成，结果不出进程。当前 ML Kit 版本没有找到公开的关闭开关，因此本版只做了如实披露（`AndroidManifest.xml` 注释与 README），没有伪称已关闭。要真正去掉，只能换掉 ML Kit。
2. **请求窗口内的屏幕帧仍会进入本进程堆**。这是功能本身，无法消除；能做的是把窗口从"整个会话"压到"每次点击的一帧"，本版已经这么做了。
3. **`dist/` 里的 APK 是 0.2.1，不包含本次任何修复**。0.3.0 的 APK 由 GitHub Actions 构建并作为 artifact 提供，但**未签名**（仓库未配置签名密钥），不能直接安装；签名方式见 README。
4. **Gradle 发行版校验和未填**。`gradle/wrapper/gradle-wrapper.properties` 里 `distributionSha256Sum` 留了 TODO 和取值命令，因为 `services.gradle.org` 在本沙箱不可达。

## 必须在真机上验证的行为

按优先级：

1. **`setSurface` 重新挂载后能否出帧**。这是本版改动的核心假设：`VirtualDisplay.setSurface(null)` 暂停投射、再 `setSurface(reader.surface)` 恢复。这是文档化的用法（`VirtualDisplay.Callback.onPaused/onResumed` 明确提到 `setSurface(null)`），但没有在荣耀 200 Pro / MagicOS 10 上实测过。若该机型上重新挂载不出帧，表现是"第一次点击正常、之后每次点击都超时并重新弹授权"。
2. 首次授权、同一会话内连续点击是否免弹窗。
3. 静态画面下点击能否出结果（新模型下应当可以，旧模型需要画面变化才有新帧）。
4. Android 13+ 复制时是否不再弹出内容预览气泡。
5. 关闭悬浮按钮后剪贴板是否被清空。
6. MagicOS 后台清理下前台服务的存活情况。
