# hongguoTV 账号安全审查

审查对象：`N3urda/hongguoTV` 的 `kotlin-tv/`（Kotlin 原生版，0.11.1），即二次开发所依据的代码。
审查时间：2026-11-11。

## 结论

**没有发现盗号通道。** 代码里不存在把账号、Cookie、验证码发送给作者服务器或第三方统计的行为：

- 全部网络请求只去字节跳动/番茄的官方域名，以及 GitHub 的版本发布地址；没有作者自建域名、没有自建上报接口。
- 权限只有 `INTERNET`、`ACCESS_NETWORK_STATE`、`REQUEST_INSTALL_PACKAGES`；不读通讯录、短信、剪贴板、已安装应用，也不采集 IMEI / Android ID / OAID。
- 没有 WebView、没有 `Runtime.exec`、没有动态加载 dex / 外部 so，仓库里除 `gradle-wrapper.jar` 外没有二进制黑盒。
- 登录态用 Android Keystore 包裹的 AES-GCM 加密后落盘，日志里不打印 Cookie、验证码、sessionid。
- 应用内更新只从本仓库 Releases 拉 `update.json`，校验 SHA-256 与安装包签名后才交给系统安装。

## 证据

| 关注点 | 结论 | 证据 |
| --- | --- | --- |
| 权限 | 仅联网、网络状态、安装应用 | `app/src/main/AndroidManifest.xml` |
| 外发域名 | 只有 `*.fqnovel.com`、`*.snssdk.com`、`hongguoduanju.com`、`github.com` | `core/src/main/.../ContentRepository.kt`、`PhoneLogin.kt`、`AppUpdates.kt` |
| 作者服务器 | 不存在 | 全仓 grep 无自建上报域名 |
| 设备标识 | 不读取系统标识 | 无 `ANDROID_ID` / `getDeviceId` / `Settings.Secure` 调用 |
| 登录流程 | 短信验证码或官方网页扫码，均直连官方 passport | `PhoneLogin.kt`、`WebLogin.kt`、`AccountController.kt:455` |
| 凭证存储 | AES/GCM + Keystore，且 `allowBackup=false` | `AccountCipher.kt`、`AccountController.kt:89-94` |
| 日志 | 不记录 Cookie/验证码/session | 全仓日志检查仅含状态码与解码器统计 |
| 更新 | 固定 GitHub 仓库，校验摘要与签名 | `AppUpdates.kt:20,81`、`AppUpdater.kt:310,329` |
| 局域网功能 | 伴机默认关闭；备份页用随机 token + 5 分钟有效期 | `RelayClient.kt`（`enabled=false`）、`LibraryTransferServer.kt` |
| 动态代码 | 无 | 无 WebView / DexClassLoader / Runtime.exec |

## 真实存在的风险

这些不是"盗号"，但会影响账号安全，需要知情：

1. **共用的设备指纹。** `VendorConstants.kt` 里写死了一组 `iid` / `device_id`（`1905892595382586` / `1905892595378490`），所有用户共用同一台"假设备"的身份证去请求接口。平台做设备风控时容易把一批账号关联到一起，可能出现限流、验证码、甚至封号。
2. **模拟官方客户端。** UA 与参数伪装成 `com.phoenix.read`（番茄/红果官方包）。这属于第三方客户端常见做法，但违反平台协议，账号被风控时申诉没有立场。
3. **签名密钥就是更新信任根。** 自动更新只校验"新包与已装包签名一致"，也就是作者手里那把密钥能推任何代码进来。密钥一旦泄漏或被滥用，旧版本会认为那是合法更新。愿意用自动更新就等于信任作者。
4. **明文流量开关。** 上游 `network_security_config.xml` 全局允许 cleartext，是为了让用户自填的局域网伴机地址能用 HTTP。代价是任何走 http 的请求都可被中间人观察；账号接口本身是 https，风险有限。
5. **登录即交出完整会话。** 扫码或短信登录后，这个 APK 拿着你的 `sessionid` 可以读收藏、历史、预约。它现在没外传，但"当前版本没干"不等于"以后不会干"。

## 对二次开发的约束建议

- 不要在工程里加任何统计/崩溃上报 SDK；需要日志就写本地文件。
- 保留更新包的签名与摘要校验；换签名等于换信任根，要让用户知情。
- 优先使用官方接口的只读能力（浏览、播放、搜索），不做批量登录、不做账号池。
- 新工程已把 `usesCleartextTraffic` 关掉，只走 https。
