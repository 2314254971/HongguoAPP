# 红果短剧 · 手机竖屏版

以 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV) 的 `kotlin-tv` 为基础做的手机向二次开发：
去掉电视遥控器和遗留实现，界面改成竖屏卡片流，播放支持「竖屏铺满宽 / 横屏全屏」两种视角。

> 非官方客户端，仅供个人学习研究。内容接口来自公开网络服务，本项目不存储、不分发任何音视频。

## 与上游的差异

保留：

- `core/`：协议、签名、解密、接口解析、本地库（原样复用，含 335 项单元测试）。
- 播放链路：`RemoteVideo` 分段解密 + Media3，和上游同一套实现。

删除：

- `android/`、`ios/`、`src/`、`server/`：旧 React Native 实现与服务端，原生版本来就不依赖。
- `app/` 里的电视 UI（焦点系统、遥控器按键、TV 版式）。
- 伴机转码、局域网备份/手机输入、应用内更新、`Probe` 调试入口。
- `decoder-ffmpeg`：需要 NDK 与 FFmpeg 源码；手机系统解码器已覆盖 AAC，先不引入。

新增：

- 竖屏首页、详情页、搜索、收藏四个手机界面。
- 双视角播放器（竖屏按比例铺满宽度 / 横屏全屏），上下滑动切集。
- 本机收藏与观看进度（不登录也能用）。

## 功能

- 竖屏首页：三列海报卡片，竖向滚动，滚到底自动加载下一页。
- 详情页：海报、标签、简介、分集网格（前 30 集，可展开），收藏与「继续观看第 N 集」。
- 播放：
  - 竖屏：视频宽度占满屏幕，高度按真实画幅计算（9:16 内容约铺满八成屏高，16:9 内容自动变矮），黑底居中。
  - 横屏：点右上角旋转按钮切全屏，返回键先回竖屏再退出。
  - 上下滑动视频区域切集：上滑下一集，下滑上一集；播完自动下一集。
  - 进度条可拖动，进度每 4 秒落盘，退出或切集时立即保存。
- 倍速：底部 `1x` 按钮，0.5 / 0.75 / 1 / 1.25 / 1.5 / 2 六档，记住上次选择。
- 弹幕：底部「弹」开关，按时间轴滚动，暂停即静止；同一文本 5 秒内合并为「×N」。
  **长按「弹」可调字号**（小 16 / 标准 18 / 大 21 / 特大 24），选择会记住；
  横竖屏切换后按新的画布重算行高与轨道（上半屏、避开顶栏与底部控制条），滚动方向始终从右向左。
- 评论：播放器底部「评」按钮弹出半屏浮层，按当前集读取，游标分页，滚到底继续加载；返回键先收起浮层。
- 预解析：初始在片尾前 3 秒解析下一集，之后按实测解析耗时自动伸缩（1.5–12 秒），并随倍速换算；拖动进度落到窗口内会立即触发，不等心跳。同一集只解析一次：命中预解析直接接管，预解析还在路上时等待它，不再重复请求。
- 搜索：剧名/演员关键词，结果同样以卡片网格展示。
- 我的：首页右上角头像进入，包含「收藏」「历史」两个页签。
- 收藏：登录后合并本地与云端收藏；本机取消收藏会记住，云端刷新不会把它顶回来。
- 历史：未登录时记录本机观看进度；登录后展示云端观看记录（覆盖本机列表），点进去直接续播对应的集数与秒数。
- 账号：手机红果 App 扫码登录，登录态用 Android Keystore 加密保存，可随时退出。
- 列表缓存：先渲染本地缓存，5 分钟内不重复请求；收藏与历史都按 30 条一页下滑追加。

## 目录

```
hongguo-mobile/
├── core/                 # 协议与媒体解析（复用上游）
│   └── src/main/kotlin/com/hongguotv/core/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── kotlin/com/hongguo/mobile/
│       │   ├── App.kt
│       │   ├── data/      # 本地库、图片加载
│       │   ├── ui/        # 首页、详情、搜索、收藏
│       │   └── player/    # 双视角播放器、解密数据源、清晰度选择
│       └── res/           # 深色主题与布局
├── docs/SECURITY-AUDIT.md
└── settings.gradle.kts
```

## 播放解析

播放走官方移动端的单集播放接口（`/novel/player/multi_video_model/player/v1`），请求使用 v5 签名。
解析失败时按以下顺序回退（1.4.2 同步自上游 0.16.12/0.16.13）：

1. 主接口空正文 → 用同一匿名身份、v5 签名的 `video_tab` GET（空字节摘要，不带 POST 专用头）；
2. 备用接口只给 `bytevc2`（手机/电视解不了）→ 换备用域名重新请求主接口；
3. 两条都空 → 保留两份诊断信息后报错，不把无法解析的空正文交给 JSON。

旧签名链路保留为 `legacyStreamLadderWithCompatibility`，仅供回归测试。

## 签名

正式包使用项目专用 keystore 签 release 构建，不再使用 Android Debug 证书：

- 本地：`keystore/hongguo-mobile.jks`，密码写在 `keystore.properties`（两者都已 gitignore）；
- CI：从仓库 Secrets 的 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 还原；
  缺少密钥时普通分支只构 debug 包，**打 tag 会直接报错**，避免误发调试签名包；
- 本地构建：`./gradlew :app:assembleRelease`，产物在 `app/build/outputs/apk/release/`。

keystore 丢了的后果：以后的新包无法覆盖安装，只能卸载重装。请备份 `keystore/` 目录与密码。

## 自动构建与发布

`.github/workflows/android.yml`：push / PR 跑单元测试并构建 APK 作为 artifact；
推送 `v*` 标签会自动创建 GitHub Release，附 `hongguo-mobile-<版本>.apk` 与 `SHA256SUMS.txt`。

## 构建

要求 JDK 17+、Android SDK 36、Build Tools 36.0.0。

```sh
# local.properties 里写 sdk.dir，或设置 ANDROID_HOME
./gradlew :app:assembleDebug
./gradlew :core:test          # 335 项协议测试
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

正式包需要自己的签名：

```sh
./gradlew :app:assembleRelease   # 未配置签名时产物不可安装
```

## 日志

播放解析失败时先看日志：

- 手机路径：`Android/data/com.hongguo.mobile/files/logs/app.log`（超过 512 KB 轮换为 `app.log.1`）；
- 「我的」页右上角「日志」按钮，可直接用系统分享把文件发出去；
- 或用 adb：`adb pull /sdcard/Android/data/com.hongguo.mobile/files/logs/app.log`；
- 日志只写本机，不含账号凭证，也不上传。

## 已知限制

- 收藏目前是"读云端"：客户端内的收藏/取消只改本机，不会写回红果账号（写回要额外的签名与回读校验）。
- 云端历史一次只取最近 50 条（接口上限），本机历史最多 200 条。
- 没有预约功能；评论与弹幕已接入（只读）。
- 只有 VVC（bytevc2）档的剧集放不了，会提示换一集；上游电视版也是同样处理。
- 竖屏卡片的列数在 `HomeActivity.COLUMNS` 固定为 3，想改成两列或单列瀑布流只改这个常量。
- 未在真机验证；本机没有可用模拟器，编译与单元测试通过，UI 需要你装到手机上看实际效果。
