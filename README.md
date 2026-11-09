# Lumina TV

为客厅电视做的 Emby 客户端。Kotlin + Jetpack Compose + Media3，遥控器优先，重播放可靠性而非功能堆叠。

当前版本 **v1.20.1**（versionCode 34）· Android 8.0+（minSdk 26）

---

## 为什么是另一个 Emby 客户端

官方 Android TV 客户端在电视盒子上会遇到两个反复出现的问题：**格式不支持就黑屏**、**焦点乱跳**。Lumina 的大部分工程投入都在解决这两件事。

**播放失败时不是报错，而是继续找路**

一次播放请求会依次尝试：

```
Media3 直连 → Media3 转码 → 换下一个版本 → 全部版本试完 → mpv 本地软解 → 显示真实错误
```

其中任何一步失败都会自动落到下一步，不需要用户干预。识别到失败的是服务端转码流（`PARSING_MANIFEST_*`）时，会立刻改拉原文件直连，而不是重复转码。

**双内核，而不是把 mpv 当兜底摆设**

| 内核 | 定位 |
|---|---|
| Media3 | 默认。硬件解码、低功耗、HLS 兼容好 |
| mpv (libmpv 1.0.4) | 本地软解。Media3 认不出的容器、DTS / TrueHD / MLP 音轨走它 |

内核选择会被「钉住」：手动切换过、或已经因解码失败回落过，就不会被下一轮重载弹回 Media3 —— 避免两个内核来回拉锯。

**焦点是显式设计的**

电视端的焦点问题几乎都来自「容器包了全屏」和「focusGroup 成环」。这里没有用 `focusGroup` 包导航，每一处上下左右的出口都是显式指定，焦点目标失效时用状态门禁断开引用，不会因为 `FocusRequester is not initialized` 崩掉。

---

## 功能

### 播放

- 断点续播取服务器返回的最新进度，不用列表里的旧值
- 片头记忆（含单剧记忆）、片尾跳过（关闭 / 30 秒 / 60 秒）、自动连播下一集
- 长按左右键连续快退快进；长按 OK 3 倍速；数字键 1–9 直接跳 10%–90%
- PageUp / PageDown 调字幕偏移 ±250ms，按片记忆
- 多音轨 / 多字幕切换、字幕样式与缩放、画面比例
- 多版本片源：播前可选，播不了自动换下一个

### 浏览

- 首页英雄区轮播：标题优先用透明 Logo（Emby → TMDB → 文字），评分 Emby 优先、缺失取 TMDB
- 选片打分：横版剧照、Logo、未看完、入库时间、评分加分；已看完、不足 60 分钟减分。取前 20 名按当天日期做种子抽 6 个，同一天内稳定、隔天换一批
- 媒体库分类卡片网格：每张卡用该分类前 4 张海报做 2×2 拼贴，显示总数
- 详情页一进来就是集数 / 主演 / 相关推荐；版本 / 音频 / 字幕收进一个图标弹层
- 搜索：400ms 防抖即时搜、分页到 300 条、筛选下沉服务端、剧集结果折叠、搜索历史、拼音全拼与首字母
- 列表滚动位置记忆，进详情再返回回到原位

### 交互

- 扫码配对：手机打开配对页填好服务器与账号，直接回填到电视登录框，不用遥控器输密码
- 多服务器在线切换：左上角胶囊展开已登录服务器
- 开机自启、播放时屏幕常亮

### 个性化

- 5 套主题色：琉璃蓝 / 星紫 / 青碧 / 樱粉 / 暖金
- 3 档玻璃质感：磨砂 / 清透 / 关闭。Android 12（API 31）及以上为真模糊，低版本自动回退渐变
- 开屏动画期间并行预取首页六路数据，动画结束即是完整海报墙

---

## 构建

环境：JDK 17 · Gradle 8.11.1 · AGP 8.10.1 · compileSdk 36 · targetSdk 35 · minSdk 26

```bash
./gradlew :app:assembleRelease
```

签名与第三方 Key 全部从 `local.properties` 读取（该文件不入库），在工程根目录自建：

```properties
sdk.dir=/path/to/android-sdk
TMDB_API_KEY=你的 TMDB 密钥
LUMINA_STORE_PASSWORD=签名库密码
LUMINA_KEY_ALIAS=lumina
LUMINA_KEY_PASSWORD=签名密钥密码
```

`lumina.jks` 放在工程根目录；没有该文件时 release 构建不签名。

---

## 目录

```
app/src/main/java/com/lumina/tv/
├── data/      Emby 客户端、数据模型、偏好设置、TMDB、内存缓存
├── pair/      扫码配对服务
├── player/    mpv 控制器与字幕渲染
├── ui/
│   ├── components/  通用组件（焦点容器、玻璃面板、海报卡…）
│   ├── screens/     Home / Detail / Player / Login / Search / Settings / Splash
│   └── theme/       颜色、字号、圆角、阴影、间距、动效 token
├── MainActivity.kt   路由与返回栈
├── AppRuntime.kt     全局运行时状态
├── BootReceiver.kt   开机自启
└── CrashHandler.kt   崩溃捕获
```

---

## 已知限制

- **毛玻璃只在 Android 12 及以上生效**，低版本回退为半透明渐变（不是降级，是刻意的回退方案）
- **media3 侧的本地 FFmpeg 扩展不可得** —— Google 不发布 `media3-decoder-ffmpeg`，Maven 与 dl.google.com 均为 404。遇到 Media3 不认识的容器会走 mpv
- **不支持的容器**：AVI / RMVB / ISO 原盘 / BDMV 目录，Media3 没有对应解封装器
- 转码依赖 Emby 服务端能力。服务端 HLS 输出异常时会自动改直连，但直连需要客户端能解码原始视频流
- 不含签名文件与构建产物

---

## 关于

个人项目，为自己的客厅盒子而写。不接弹幕、不做网盘挂载、不做聚合搜索 —— 那些是另一个产品方向。
