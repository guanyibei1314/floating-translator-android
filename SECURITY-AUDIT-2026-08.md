# 悬浮翻译 0.2.1 安全复审（2026-08）

本次复审对象：仓库当前代码（`versionCode 6` / `0.2.1`）以及 `dist/FloatingTranslator-0.2.1-MagicOS10-RealOCR-ARM64.apk`。

复审方式：源码静态审计 + APK 静态分析（解包、合并后 AndroidManifest 二进制解析、DEX 字符串提取、签名证书检查）。
**本次环境没有实体设备、Emulator 或 ADB，所有结论均来自静态证据，没有做任何动态验证。**

## 结论概要

没有发现可被第三方应用直接利用的越权入口：所有组件（含 ML Kit / GMS 合并进来的组件）在合并后的 Manifest 中都是 `exported="false"`，唯一导出的 `MainActivity` 不读取任何 Intent extra；MediaProjection 授权令牌只在应用内的显式 Intent 中流转。

真正需要处理的是**隐私面**和**发布链**的问题：应用对屏幕像素的采集范围远大于功能需要，随 ML Kit 引入的 Google 遥测与安装标识与 README / 旧报告的描述不符，识别出的屏幕文字通过系统广播和全局剪贴板离开了本进程。

| 编号 | 问题 | 等级 | 0.3.0 状态 |
| --- | --- | --- | --- |
| H-1 | 会话期间持续全屏镜像，而非按需取帧 | 高（隐私过度采集） | 已修复（改为按需挂载 surface） |
| M-1 | ML Kit 遥测 / Firebase 安装 ID 默认开启，与文档陈述不符 | 中 | 已如实披露；遥测本身无法关闭 |
| M-2 | 屏幕文字写入全局剪贴板，未标记敏感、不清除 | 中 | 已修复 |
| M-3 | 无 Gradle Wrapper、无依赖锁定与校验，构建不可复现 | 中（供应链） | 部分：Wrapper 已加，校验和与依赖锁定未完成 |
| L-1 | 屏幕文字经系统广播跨进程传递（同进程组件之间） | 低 | 已修复（改为进程内 CaptureBus） |
| L-2 | 结果面板无 `FLAG_SECURE`，按钮无遮挡点击防护 | 低 | 已修复 |
| L-3 | 已签名 APK 提交进仓库，release 无 `signingConfig` | 低（发布链） | 未改动；已在 README 标注该包为旧版 |
| L-4 | `allowBackup="false"` 未覆盖 Android 12+ 设备迁移 | 低 | 已修复 |
| I-1 | 传递依赖版本偏旧且完全未显式声明 | 信息 | 未改动 |
| I-2 | 自定义权限被抢注会导致安装失败 | 信息 | 不再适用（权限已删除） |
| I-3 | 自身悬浮面板可能被下一帧 OCR 到 | 信息 | 间接缓解（按需挂载后延迟更长） |

修复的验证过程见 `SECURITY-RED-BLUE-REPORT.md`。以下各节记录的是 **0.2.1 当时的原始分析**，保留以说明问题成因。

---

## H-1 会话期间持续全屏镜像，而非按需取帧

`ScreenCaptureService.startContinuousCapture()`（`ScreenCaptureService.kt:230-262`）在**会话建立时**就创建了一个全屏分辨率的 `ImageReader`（`:238`）和带 `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` 的 `VirtualDisplay`（`:249-258`），并且这两者一直存活到用户关闭悬浮按钮为止。

结果是：从用户第一次点“译”开始，直到主动停止，**屏幕上的每一帧都会被复制进本应用的 `ImageReader` 缓冲区**，并逐帧回调 `onImageAvailable()`（`:265`）。不需要的帧确实被立刻 `close()` 丢弃了，但像素数据已经进入了本应用的地址空间。用户在此期间打开的银行 App、密码输入、私信内容，全部经过这条通路。

这跟 README 与旧报告里“点击‘译’只请求当前帧”的表述不是一回事：被请求的只是 *OCR 的输入*，被采集的是 *全部帧*。

同时 `imageToBitmap()`（`:716`）会为被选中的帧分配一张全屏 `ARGB_8888` Bitmap。`Bitmap.recycle()` 只是释放，不会清零，所以进程堆转储中仍可能残留屏幕内容。

**建议**：改成按需采集 —— 收到 `ACTION_REQUEST_TRANSLATION` 后再 `createVirtualDisplay()`，拿到一帧立即 `release()`，只保留 `MediaProjection` 令牌用于下次复用（Android 14+ 复用同一个 `MediaProjection` 创建新的 `VirtualDisplay` 是允许的，不会重新弹授权框）。这样把暴露窗口从“整个会话”压缩到“每次点击的几百毫秒”，是本次复审中收益最大的一项改动。若确实需要保留常驻 display 以避免首帧延迟，至少应在 README 中如实说明采集是持续性的。

## M-1 ML Kit 遥测 / Firebase 安装 ID 默认开启，与文档陈述不符

`AndroidManifest.xml:15` 的注释写着 “Used only by ML Kit to download the on-device translation model once.”，README 与 `SECURITY-RED-BLUE-REPORT.md` 也称“网络权限仅用于模型下载”。APK 静态分析不支持这个说法：

- 合并后的 Manifest 里多出一条源码中没有声明的权限 `android.permission.ACCESS_NETWORK_STATE`，以及 Google 遥测上报管线的三个组件：`com.google.android.datatransport.runtime.backends.TransportBackendDiscovery`、`...jobscheduling.JobInfoSchedulerService`、`...jobscheduling.AlarmManagerSchedulerBroadcastReceiver`（CCT / Firelog）。
- DEX 中含有 `MLKitLoggingOptions{libraryName=common, enableFirelog=true, firelogEventType=1}` 与 `MLKitLoggingOptions{libraryName=vision-common, enableFirelog=true, ...}`，即**遥测默认开启**。
- DEX 中含有 `com.google.mlkit.InstallationId`、`com.google.mlkit.RemoteConfig` 以及端点 `https://firebaseinstallations.googleapis.com/v1`、`https://firebaseremoteconfig.googleapis.com/v1/projects/`。Firebase Installations 会生成并上报一个每安装唯一的标识符（FID）。
- 翻译模型下载端点为 `https://dl.google.com/translate/offline/v5/high/r29/` 与 `https://redirector.gvt1.com/edgedl/translate/offline/v5/high/r29/`，均为 HTTPS。

需要说清楚的是：**没有发现屏幕文字被上传**——这部分原有结论成立，OCR 与翻译确实在端侧完成。有问题的是“只在下载模型时联网一次”这个描述：实际上应用会周期性地向 Google 上报 ML Kit 使用事件，并持有一个可跨会话关联的安装标识。

在当前依赖版本（`text-recognition:16.0.1` / `translate:17.0.3`）的 DEX 中没有找到公开的遥测关闭开关（既没有 metadata key，也没有对应的 API 字符串）。

**建议**：优先修正 `AndroidManifest.xml:15`、README 与旧报告中的表述，如实写明“ML Kit 会向 Google 发送使用统计并使用 Firebase 安装 ID”。若产品上不接受这一点，可考虑改用完全离线的 OCR/翻译方案，或在隐私说明中显式披露。另外 `DownloadConditions.Builder().build()`（`ScreenCaptureService.kt:426`）未设任何条件，建议至少加 `requireWifi()`，避免在移动网络下静默下载约 30 MB 模型。

## M-2 屏幕文字写入全局剪贴板，未标记敏感、不清除

`FloatingTranslatorService.copyTranslation()`（`FloatingTranslatorService.kt:491-502`）把整屏 OCR 出来的原文与译文拼接后写入系统剪贴板。这些内容可能包含验证码、私信、账号信息。当前实现存在两个缺口：

1. 没有设置 `ClipDescription.EXTRA_IS_SENSITIVE`。Android 13+ 在复制时会弹出内容预览气泡，未标记敏感的内容会被直接显示在屏幕上（并可能被正在录屏的应用拍到）。
2. 剪贴板内容不会过期，任何获得焦点的应用或输入法都能读取。

**建议**：

```kotlin
val clip = ClipData.newPlainText(getString(R.string.translation_label), text)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
}
clipboard.setPrimaryClip(clip)
```

并考虑在服务销毁时清空由本应用写入的剪贴板内容。

## M-3 无 Gradle Wrapper、无依赖锁定与校验

仓库中没有 `gradlew` / `gradle/wrapper/gradle-wrapper.properties`，因此没有固定的 Gradle 版本，也没有 `distributionSha256Sum`。`settings.gradle.kts` 直接使用 `google()` 与 `mavenCentral()`，没有 `gradle/verification-metadata.xml`（依赖签名/校验和校验），也没有开启依赖锁定。`app/build.gradle.kts` 只声明了两个 ML Kit 坐标，其余 30 多个 AndroidX / GMS / Kotlin 组件全部由传递依赖决定版本。

后果是：任何人在任何时间点构建这个项目，产物都可能不同，且没有任何机制能发现被替换的依赖。对于一个持有 MediaProjection 权限的应用，这是最值得优先补上的工程性防线。

**建议**：提交 Gradle Wrapper 并锁定发行版校验和；启用 `dependencyLocking`；生成 `verification-metadata.xml`；把关键依赖（含 AndroidX）显式声明或改用 version catalog。

## L-1 屏幕文字经系统广播跨进程传递

`FloatingTranslatorService` 与 `ScreenCaptureService` 运行在同一个进程里，但两者之间用 `sendBroadcast()` 通信（`ScreenCaptureService.kt:584-593`），`EXTRA_SOURCE_LINES` / `EXTRA_TRANSLATED_LINES` 携带的正是刚从屏幕上识别出来的文字（`:552-576`）。

广播的 Intent 及其 extras 会被打包传给 `system_server` 再分发，也就是说这些文字**离开了本应用进程**，尽管它已经用 `setPackage()` + 签名级权限做了收敛，第三方应用无法接收。

访问控制本身是正确的：Android 13+ 用 `RECEIVER_NOT_EXPORTED` 注册（`FloatingTranslatorService.kt:148`），Android 12 及以下用签名级自定义权限（`:151-157`），发送方两条路径都带权限参数。问题不在于能不能被截获，而在于同进程通信没有必要走系统 IPC。

**建议**：改为进程内回调（一个简单的单例 observer，或 `LocalBroadcastManager`），让屏幕文字始终不出进程。这同时会顺带消除 `dumpsys` / bug report 中出现相关 Intent 记录的可能。

## L-2 结果面板无 `FLAG_SECURE`，按钮无遮挡点击防护

结果面板（`FloatingTranslatorService.kt:441-451`）展示的是从其他应用屏幕上提取的文字，但窗口没有设置 `WindowManager.LayoutParams.FLAG_SECURE`，因此可被其他录屏工具或截图捕获。面板上的“复制 / 关闭”按钮也没有 `setFilterTouchesWhenObscured(true)`，理论上可被恶意悬浮窗做遮挡点击诱导。

**建议**：给面板的 `flags` 加上 `FLAG_SECURE`；对两个按钮调用 `setFilterTouchesWhenObscured(true)`。

## L-3 已签名 APK 提交进仓库，release 无 `signingConfig`

`dist/FloatingTranslator-0.2.1-MagicOS10-RealOCR-ARM64.apk`（29 MB）被提交进了 Git 历史，`.gitignore` 还专门为它开了白名单。同时 `app/build.gradle.kts` 的 release buildType 没有 `signingConfig`，说明签名是在构建流程之外完成的。

签名证书本身没有问题（自签名，`CN=Floating Translator`，2048-bit RSA / SHA256withRSA，有效期至 2054 年，SHA-256 指纹 `7B:36:7F:91:88:63:B7:72:88:88:C9:D3:C8:F3:89:31:0E:CE:5D:19:34:A0:E3:75:06:56:CE:A9:7A:CC:8D:9D`），仓库和历史中也**没有**发现 keystore、私钥或任何硬编码凭据。

风险在于分发链：由于构建不可复现（见 M-3），没有人能从源码验证这个 APK 就是这份代码编译出来的；克隆仓库的人拿到的是一个无法核对的二进制。

**建议**：改用 GitHub Releases 分发，附带 SHA-256 与签名证书指纹；把 APK 移出 Git 历史；在 Gradle 中配置 `signingConfig` 并从环境变量或本地 `keystore.properties`（已被 `.gitignore` 覆盖）读取凭据。

## L-4 `allowBackup="false"` 未覆盖 Android 12+ 设备迁移

`AndroidManifest.xml:18` 设置了 `android:allowBackup="false"`，这会关闭云备份与 adb backup，但在 Android 12（API 31）及以上**不会**阻止设备到设备（D2D）迁移。ML Kit 下载的翻译模型和 Firebase 安装 ID 存放在应用私有目录中，仍可能被迁移带走。

**建议**：增加 `android:dataExtractionRules`，在其中同时禁用 `<cloud-backup>` 与 `<device-transfer>`。

## I-1 传递依赖版本偏旧

APK 中的组件版本：`androidx.core 1.9.0`、`appcompat 1.6.1`、`fragment 1.3.6`、`lifecycle 2.5.1`、`kotlinx-coroutines 1.6.1`、`startup-runtime 1.1.1`。这些都不是显式声明的，全部由 ML Kit 传递带入。未发现其中存在已知的高危漏洞，但版本明显落后，且没有任何机制（如 Dependabot）跟踪更新。

## I-2 自定义权限被抢注会导致安装失败

`com.example.floatingtranslator.permission.INTERNAL_CAPTURE_RESULT` 用 `protectionLevel="signature"` 声明，这是正确做法。需要知道的是：如果某个恶意应用抢先用同名权限安装，本应用的安装会因 `INSTALL_FAILED_DUPLICATE_PERMISSION` 失败——这是可用性层面的骚扰，**不是**权限绕过，签名级保护在运行期依然成立。若改成 L-1 建议的进程内通信，这条权限连同这个场景都可以一并去掉。

## I-3 自身悬浮面板可能被下一帧 OCR 到

`requestTranslation()`（`FloatingTranslatorService.kt:283-304`）在请求前先 `removeResultPanel()` 并隐藏气泡，逻辑是对的。但视图移除到系统实际完成合成之间存在异步延迟，`requestedAfterFrame` 只保证“取点击之后到达的帧”，不保证那一帧里悬浮窗已经消失。极端情况下上一次的译文可能被重新识别一遍。这不构成安全问题，但会污染结果。

---

## 已确认没有问题的部分

以下项经过本次检查，结论与旧报告一致：

- 合并后的 Manifest 中，包括 ML Kit / GMS 引入的 `MlKitComponentDiscoveryService`、`MlKitInitProvider`、`GoogleApiActivity`、`InitializationProvider`、三个 datatransport 组件在内，**全部 `exported="false"`**；唯一导出的 `MainActivity` 不读取任何 Intent extra。
- MediaProjection 授权令牌只从系统 `onActivityResult` 流向显式的、非导出的 `ScreenCaptureService`（`CapturePermissionActivity.kt:40-58`），外部无法注入或重放；`resultCode != RESULT_OK || data == null` 时直接走取消路径。
- 通知栏“停止屏幕读取”使用的 `PendingIntent` 带 `FLAG_IMMUTABLE`（`ScreenCaptureService.kt:800-806`）。
- 会话 generation + 请求 ID + 帧序号 + 双重超时的组合，能正确丢弃旧会话回调，不会把上一次的成功结果复用给新请求。
- 释放路径统一：`endSession()` / `onDestroy()` / `MediaProjection.Callback.onStop()` / 主界面停止按钮最终都汇聚到 `releaseCapture()`，`VirtualDisplay`、`ImageReader`、`MediaProjection`、`TextRecognizer`、`Translator` 均被关闭。
- 没有 WebView、没有动态代码加载、没有 `Runtime.exec`、没有文件持久化屏幕内容、没有应用自身的日志输出。
- 仓库与 Git 历史中没有 keystore、私钥、token 或 API key。
- OCR 输入有上限（`MAX_OCR_DIMENSION = 2048`、`MAX_TRANSLATION_LINES = 24`、`MAX_TRANSLATION_CHARACTERS = 2400`），可防止超大帧造成的内存压力。
- release 构建启用了 R8（`isMinifyEnabled` / `isShrinkResources`），APK 中未发现 Kotlin 源文件名或调试标记。

## 本次未做的验证

没有实体荣耀 200 Pro、Emulator 或 ADB，因此以下均未执行，不能作为已通过的结论：实际安装、首次与重复点击的授权弹窗行为、跨应用广播攻击的实机复现、录屏停止后的资源回收、MagicOS 后台清理、剪贴板预览气泡的实际表现、遥测流量抓包确认。M-1 中关于遥测的结论来自 APK 静态证据（Manifest 组件、DEX 字符串、端点），未经流量验证。
