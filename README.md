# QQ 特别关心通知独立模块

这是一个面向 QQ（`com.tencent.mobileqq`）的最小 Xposed/LSPosed 模块：只把“特别关心”好友的私聊通知改到独立通知渠道 `QQ_Friend_Special`，不重建通知内容。

本项目是基于 QAuxiliary 相关实现整理的修改作品，独立拆分日期为 2026-08-28。

## 为什么单独拆出来

QAuxiliary 的相关实现同时包含 MessagingStyle、通知气泡、快捷方式、快捷回复、通知清理和历史消息等逻辑。这个项目只保留“识别特别关心 + 改通知渠道”这一条路径：

- NT/旧版 QQ：只 Hook 最终的 `NotificationManager.notify(...)` 入口，识别通知标题中的 `[特别关心]`，再改渠道。
- 不改消息正文、标题、图标、PendingIntent、通知 ID 或通知样式。
- 不 Hook QQ 的撤回、清空通知、气泡、快捷回复、Activity 或发送消息逻辑。

## 构建

在本目录执行：

```powershell
\.\gradlew.bat :app:assembleDebug --no-daemon
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。需要 Android SDK 35、JDK 17 或更高版本，以及可访问 Google Maven 和 Xposed Maven 的网络环境。

仓库内已经附带 `.github/workflows/build.yml`：提交到 GitHub 后，在 Actions 中运行 `Build APK`，它会自动准备 Android SDK 35、使用仓库 Secrets 中的固定签名，并把 `app-debug.apk` 上传为构建产物。正式 push 或手动运行时，`versionCode` 使用 GitHub Actions 的 `run_number` 自动递增，`versionName` 为 `0.1.<run_number>`；来自外部 fork 的 Pull Request 不会获得签名 Secrets。

固定签名需要在仓库 Settings → Secrets and variables → Actions 中配置以下四个 Secrets：`SPECIALCARE_KEYSTORE_BASE64`、`SPECIALCARE_STORE_PASSWORD`、`SPECIALCARE_KEY_ALIAS`、`SPECIALCARE_KEY_PASSWORD`。不要把 keystore 或密码提交到公开仓库。

## 安装和启用

1. 安装 APK。
2. 在 LSPosed 中启用“QQ 特别关心通知独立模块”，作用域只选择 `com.tencent.mobileqq`。
3. 强制停止并重新打开 QQ。
4. 在 QQ 的系统通知设置中确认“特别关心消息”渠道已开启。
5. 用一个特别关心好友和一个普通好友各发一条私聊消息，确认只有前者进入独立渠道；群消息不改动。

如果手机里已经用过 QAuxiliary，`QQ_Friend_Special` 这个渠道 ID 会复用 QQ 现有的同名渠道及其系统设置。Android 不允许应用修改用户已经创建的渠道的重要性、声音等设置，需要手动在系统通知设置中调整。

如果从旧版 Actions APK 更新到首次使用固定签名的 APK，需要先卸载旧版一次；旧版使用的是临时 runner 的 debug 签名，无法与新签名直接覆盖安装。之后同一仓库的构建会保持相同签名，只需递增版本号即可覆盖更新。

使用本模块时，建议关闭 QAuxiliary 中的“MessagingStyle通知”功能，避免两个模块同时改写同一条通知。为了降低 Hook 面，本模块不保证 QQ 或 LSPosed 的检测风险为零；它只是把本模块自身的 Hook 和行为缩减到最小。

## 日志和兼容性

安装成功、最终通知入口被触发或匹配到特别关心通知时会写入 Xposed 日志，标签内容包含 `SpecialCareNotification`。如果当前 QQ 版本不再把特别关心标记放在通知标题中，模块不会主动重建或伪造通知。

本项目的实现依据 QAuxiliary 中的相关实现和公开源码整理，重点参考：

- `SpecialCareNewChannel.kt` 的最终 `NotificationManager.notify(...)` Hook 和 `[特别关心]` 标题判定。

上游项目地址：<https://github.com/cinit/QAuxiliary>

## 许可

相关衍生实现随附 `LICENSE-QAUXILIARY.md`。若公开传播源代码或 APK，请一并保留许可、署名、来源网址和修改说明，并遵守其中的非商业及源码提供要求。
