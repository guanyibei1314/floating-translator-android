# 签名设置（一次性）

Android 拒绝安装任何未签名的 APK，所以要装到手机上，APK 必须先签名。

下面这一次设置做完之后，以后每次更新都能直接覆盖安装。

**如果你从没装过 0.2.1**（`dist/` 里那个包一直只是放在仓库里），那就没有任何历史包袱：现在生成的密钥就是这个 app 的密钥，直接往下做即可。

**如果你手机上装过 0.2.1**：它的签名密钥不在本仓库里（`.gitignore` 一直排除着 `*.jks` / `*.keystore`，git 历史里也从未出现过），也无法从 APK 还原。因此新版本无法覆盖安装到它之上，**需要先卸载旧版**，只会丢失已下载的翻译模型和悬浮窗授权。

## 第 1 步：生成密钥（只有你能做）

在你自己的电脑上运行。密钥不要经过任何聊天窗口或共享环境：

```bash
keytool -genkeypair -v \
  -keystore floating-translator-release.jks \
  -alias floating-translator \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Floating Translator, OU=Personal, O=Floating Translator, C=CN"
```

会提示你设置密码，设一个并记住。

> **把这个 .jks 文件备份好。** 它丢了就再也无法给这个 app 发布可覆盖安装的更新了——只能换新密钥，让所有用户卸载重装。建议存到密码管理器或离线备份里，不要只放在一台电脑上。

## 第 2 步（二选一）

### A. 本地构建并签名

在仓库根目录建一个 `keystore.properties`（已被 `.gitignore` 排除，不会提交）：

```properties
storeFile=/绝对路径/floating-translator-release.jks
storePassword=你的密码
keyAlias=floating-translator
keyPassword=你的密码
```

然后：

```bash
./gradlew assembleRelease
```

产物是已签名的 `app/build/outputs/apk/release/app-release.apk`，可直接安装。

### B. 让 GitHub Actions 自动签名

把密钥转成 base64：

```bash
base64 -w0 floating-translator-release.jks   # macOS 用 base64 -i floating-translator-release.jks
```

在仓库 **Settings → Secrets and variables → Actions** 里添加 4 个 secret：

| 名称 | 值 |
| --- | --- |
| `KEYSTORE_BASE64` | 上一条命令的完整输出 |
| `KEYSTORE_PASSWORD` | 你的密码 |
| `KEY_ALIAS` | `floating-translator` |
| `KEY_PASSWORD` | 你的密码 |

之后每次推送，CI 都会产出已签名、可直接安装的 APK，run summary 里会打印签名证书信息。

> 取舍：这样最省事，但私钥就存在 GitHub 上了。任何能向本仓库推代码的人都可以写一个 workflow 把 secret 读出来。个人项目通常可接受；如果不放心就用方案 A，私钥全程不离开你的电脑。

## 第 3 步：安装到手机

1. 如果装过 0.2.1，先卸载（仅这一次，因为签名密钥不同）；没装过就跳过
2. 安装 APK
3. 授予悬浮窗权限，并按 README 里的 MagicOS 设置步骤操作一遍

装好后第一件事：在英文界面里**连续点两次「译」**。第二次如果超时并再次弹出授权框，说明 `setSurface` 按需挂载在该机型上不工作，需要反馈。

## 之后

密钥不变的话，以后所有版本都能直接覆盖安装，数据也不会丢。记得把新证书的 SHA-256 指纹更新到 README（现在记的是 0.2.1 那个包的指纹，与你新生成的密钥无关）。

## 只想先跑起来看看？

如果暂时不想弄密钥，只是想在手机上看看 0.3.0 能不能用：

```bash
./gradlew assembleDebug
```

debug 包由 AGP 自动用调试密钥签名，可以直接安装。但**不要拿它当日常使用的版本**：debug 构建是 `debuggable` 的，任何能连 ADB 的人都可以附加调试器——对一个持有录屏权限的应用来说这是实打实的风险；而且它不经过 R8 压缩，调试密钥每台机器/每次 CI 都不同，下次更新还是得卸载重装。
