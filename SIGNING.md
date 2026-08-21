# 签名设置（一次性）

Android 拒绝安装任何未签名的 APK，所以要装到手机上，APK 必须先签名。

0.2.1 的签名密钥不在本仓库里（`.gitignore` 一直排除着 `*.jks` / `*.keystore`，git 历史里也从未出现过），也无法从已有的 APK 里还原出来。**没有它就做不了覆盖更新**：装 0.3.0 之前必须先卸载手机上的 0.2.1。丢失的只有已下载的翻译模型（重新下一次即可）和悬浮窗授权。

下面这一次设置做完之后，以后每次更新都能直接覆盖安装，不用再卸载。

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

1. 卸载手机上的旧版「悬浮翻译」（**仅本次需要**，因为签名密钥变了）
2. 安装新的 APK
3. 重新授予悬浮窗权限，并按 README 里的 MagicOS 设置步骤操作一遍

装好后第一件事：在英文界面里**连续点两次「译」**。第二次如果超时并再次弹出授权框，说明 `setSurface` 按需挂载在该机型上不工作，需要反馈。

## 之后

密钥不变的话，以后所有版本都能直接覆盖安装，数据也不会丢。记得把新证书的 SHA-256 指纹更新到 README（现在记的还是 0.2.1 的那个）。
