# 网页邮箱（Webmail APK）

把 Bulwark 网页邮箱（https://webmail.sshdog.com）套壳成安卓应用，
收到新邮件时通过 Firebase Cloud Messaging（FCM）实时推到手机通知中心。

- 包名：`com.sshdog.webmail`，应用名“网页邮箱”，版本 1.0.0 / versionCode 1
- 无广告
- WebView 打开 webmail.sshdog.com（JavaScript + DOM 存储、附件上传、返回键回退历史、Cookie 持久化）
- 通知渠道：id `mail`、名“新邮件”、高重要性；点通知打开应用

## 推送是怎么跑通的

```
新邮件到达 Stalwart
  → 服务器端监听服务（另写，不在这个仓库）
  → Firebase Cloud Messaging
  → 手机上的本应用
  → 通知中心出现“新邮件”通知，点开进邮箱
```

应用启动后拿到的 FCM token，由应用自己注册到服务器：

```
POST https://webmail.sshdog.com/fcm/register
Content-Type: application/json
{"token": "..."}
```

- token 变化时（`MailFirebaseMessagingService.onNewToken`）自动注册；
- 注册失败时静默保存，下次启动再试，不打扰用户；
- 服务器端必须实现 `/fcm/register` 接口收 token（监听服务那部分负责），
  否则 token 发出去没人收，推送推不起来。

去重规则：notification payload 在后台由系统自动展示，应用不再弹；
只有在前台、或收到纯 data payload 时，应用才自己弹一条本地通知，
系统 + 本地永远只弹一次。

## 换真实 google-services.json（必做，否则推送不工作）

`app/google-services.json` 现在是 **PLACEHOLDER 假文件**（格式完整、值全是假的，
只为让构建通过）。正式使用前：

1. 在 Firebase 控制台建项目，包名填 `com.sshdog.webmail`，下载真实的
   `google-services.json`；
2. 覆盖 `app/google-services.json`（不要提交真实文件里的密钥到公开仓库以外的地方）。

没换真实文件时应用能装能打开、能看邮件，但拿不到有效 FCM token，推送不会来。

## GitHub Secrets（构建签名用）

在仓库 Settings → Secrets and variables → Actions 里建 4 个：

| Secret | 含义 |
| --- | --- |
| `RELEASE_KEYSTORE` | 发布 keystore 文件的 base64（`base64 -w0 xxx.keystore`） |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | key 别名 |
| `RELEASE_KEY_PASSWORD` | key 密码 |

keystore 是这个应用专用的：以后所有 `com.sshdog.webmail` 的更新都必须用
同一把 keystore 签名，否则无法覆盖安装。不要复用别的 App 的 keystore，
密码只经文件附件交接、不进聊天正文、不记入记忆。

## 构建

只手动触发：仓库 Actions → Build Webmail APK (manual) → Run workflow。

流程：JDK 17 → `./gradlew app:assembleRelease` → Secrets 签名 →
`apksigner verify --print-certs` 校验签名 → 上传 APK artifact。

本地构建需要 Android SDK + JDK 17：`./gradlew app:assembleRelease`。
本机没有 Android SDK 时不要在本地试构建，走 GitHub Actions。

## 需要的权限

- `INTERNET`：访问网页邮箱
- `POST_NOTIFICATIONS`：Android 13+ 首次打开应用时申请；拒绝也不影响看邮件，
  只是收不到新邮件通知
