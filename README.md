# Lumina TV

面向客厅大屏的 Emby 客户端。基于 Kotlin 与 Jetpack Compose 构建，遥控器优先操作，
以播放可靠性与界面一致性为核心设计目标。

**当前版本源** v1.24.19（versionCode 163，`app/build.gradle.kts`）· Android 8.0+（minSdk 26）

2026-11-27 核对：关于页使用 `BuildConfig.VERSION_NAME`，现有版本 APK、unsigned Release 与 Debug APK 的 `aapt2 dump badging` 均为 163 / 1.24.19。此版本号不代表发布已获授权；native 来源发布门禁仍为 **BLOCKED**，详见 [native 来源清单](docs/native-sources.md) 与 [第三方许可](THIRD-PARTY-NOTICES.md)。

---

## 概述

Lumina TV 是原生 Android TV 应用，通过 Emby Server 访问个人媒体库。
项目不提供媒体托管能力，需配合自建 Emby 服务端使用。

主要设计取向：

- **播放优先**：多内核协作与自动降级，尽最大可能让内容播得出来
- **遥控器优先**：全部交互围绕方向键与确认键设计，焦点路径显式定义
- **信息克制**：首页与详情页只呈现播放决策所需的信息，避免装饰性元素干扰

---

## 核心特性

### 播放

| 能力 | 说明 |
| --- | --- |
| 双内核与看门狗 | Media3 / mpv 协作；卡住时有界恢复，先保留位置重载，再换源，最多 3 次 |
| `rebuildRetry`（重建播放器重试） | 播放失败时重建播放器并重试；不提供 FFmpeg 解码能力 |
| Media3 FFmpeg 音频扩展 | 已集成 Jellyfin `media3-ffmpeg-decoder:1.6.1+2`，播放器启用扩展音频 renderer；实际解码能力仍受该 native 构建与设备限制 |
| 自动降级 | 直连 → 转码 → 切换片源 → 全部尝试后回落 mpv，各环节自动衔接 |
| 内核保持 | 手动指定或已因解码失败回落后，内核选择保持不变，避免反复切换 |
| Dolby Vision P7 | 识别 Profile 7 双层封装后直接交由 mpv 解码，跳过无效尝试 |
| STRM 直连 | 解析 `.strm` 内容与 `#EXTVLCOPT` / `#EXTHTTP` 指令，透传请求头；直链失效时明确提示 |
| 断点续播 | 以服务端返回的最新进度为准，不使用列表缓存值 |
| 片头片尾 | 片头位置记忆、片尾跳过（关闭 / 30 秒 / 60 秒）、自动连播 |
| 播放控制 | 长按方向键连续快退快进、长按确认键加速、数字键按百分比跳转、字幕偏移快捷键 |
| 音频直通 | 按编码、声道、采样率与当前输出设备判定；设备变化会重算并提示 |
| 字幕 | Off / Auto / 指定轨三态；mpv 与 Media3 均受设置控制 |
| 缓存 | 播放缓存每天自动清理，超过上限时提前淘汰，无需手动清理 |
| 详情页播放 | 「继续播放」与「从头播放」分离 |
| 多轨与画面 | 音轨与字幕切换、字幕样式与缩放、画面比例调整 |
| Anime4K | 可选 mpv shader，默认关闭；效率 / 画质两档，不适用于 Media3 |

### 浏览

- **首页轮播**：标题优先使用透明 Logo（Emby → TMDB → 文字），评分同样优先取 Emby 数据
- **轮播背景**：依次尝试 Emby 自身横图、无标签 `Backdrop/0`、父级横图、TMDB 与海报兜底；右下 3 张缩略图跟随当前条目
- **相关推荐**：同剧种候选按高置信度同地区、类型重合、评分及已看降权排序，同分按日稳定排序；正常结果缓存 6 小时，空结果 30 分钟，超时使用 Similar 兜底且不缓存
- **智能选片**：综合横版剧照、Logo、观看进度、入库时间与评分加权排序，按日期取种子抽取，
  同日内结果稳定
- **媒体库**：分类卡片采用封面拼贴，展示条目总数
- **详情页**：进入即可见集数与主演，版本、音频、字幕收于播放设置面板
- **视频背景**：详情页背景播放预告片或正片片段，静音循环，带磁盘缓存与失败静默回落
- **搜索**：输入防抖即时检索、结果分页、类型筛选、剧集折叠、搜索历史、拼音全拼与首字母匹配
- **位置记忆**：列表滚动位置在返回后恢复

### 交互

- **扫码配对**：手机端填写服务器与账号信息，同步至电视端登录界面
- **多服务器**：已登录服务器之间在线切换
- **系统集成**：开机自启、播放期间屏幕常亮

### 外观

- **主题色**：琉璃蓝 / 星紫 / 青碧 / 樱粉 / 暖金，共 5 套
- **玻璃质感**：磨砂 / 清透 / 关闭，共 3 档。Android 12 及以上使用系统模糊，
  低版本采用等效的半透明层叠方案

---

## 视频背景实现说明

详情页背景视频采用独立解码实例，与主播放器互不干扰。

**预览源选择**

1. 优先读取 Emby 的本地预告片（`LocalTrailers`）
2. 无预告片时，从片头跳过记录或片长 22% 位置起播

**循环与缓存**

- 循环区间 10 至 30 秒，通过 seek 回绕实现，不使用 `ClippingMediaSource`
  （该类对实时转码产生的 live 型播放列表会抛出异常）
- 采用 300MB LRU 磁盘缓存。缓存键归一化为条目标识与片源标识的组合，
  避免因鉴权参数变化导致的缓存穿透
- 已缓存的片段在后续循环与再次进入时零网络请求

**失败处理**

- 播放前通过范围请求读取响应头与首字节，识别 HTML 页面等非媒体响应
- 判定为不可播放时静默回退至静态背景，界面不呈现任何错误信息
- 失败条目在进程内记录，再次进入时直接跳过，不占用服务端转码资源

---

## 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言与运行时 | Kotlin、Coroutines |
| 界面 | Jetpack Compose |
| 播放 | Media3 ExoPlayer 1.7.1、Jellyfin FFmpeg decoder 1.6.1+2、JellyCine libmpv 1.0.4（Maven 包版本，非 mpv upstream 版本） |
| 网络 | OkHttp |
| 图像 | Coil |
| 元数据 | Emby Server API、TMDB API |

---

## 构建

**环境要求**

```
JDK 17
Gradle 8.11.1
Android Gradle Plugin 8.10.1
compileSdk 36 / targetSdk 35 / minSdk 26
```

**构建命令**

```bash
./gradlew :app:verifyOffline :app:assembleDebug
# 不启用发布签名的 Release；不是发布指令：
./gradlew -PreleaseSigning=false :app:assembleRelease
```

**纯函数断言**

`SamplesMain` 运行 samples 及可靠性断言，每组打印 START / PASS，异常直接让 Gradle 失败，使用 `fun main()` + `check()`，不依赖 JUnit。`verifyOffline` 还汇总已接入的 P / B / E / T 系列检查，以 `app/build.gradle.kts` 中的任务依赖为准。
JVM JSON 运行库固定为 `org.json:json:20240303`，通过独立的 `samplesJson` 配置解析，不进入 APK。在仓库根目录执行：

```bash
./gradlew :app:verifyOffline
# SDK 和依赖首次缓存后可完全离线重跑：
./gradlew --offline :app:verifyOffline
```

不需要 `local.properties`、签名库或服务端密钥；SDK 可通过 `ANDROID_HOME` 指定。A1 只连接本机回环 HTTP 服务。
统一任务还运行 `p1Checks`：收藏/已看真实 HTTP 响应、异步回滚与条目隔离、排序键与随机固定样本缓存。
`check` / `assemble*` / `bundle*` 依赖验证成功；PR 仅运行验证、不发布产物，构建发布 job 依赖 `verify`。

**配置**

SDK 路径与可选第三方 API 密钥从未入库的 `local.properties` 读取：

```properties
sdk.dir=/path/to/android-sdk
TMDB_API_KEY=你的 TMDB 密钥
MDBLIST_API_KEY=你的 MDBList 密钥
```

Release 签名仅在显式传入 `-PreleaseSigning=true` 时启用，并要求环境变量 `LUMINA_KEYSTORE_PATH`、`LUMINA_STORE_PASSWORD`、`LUMINA_KEY_ALIAS`、`LUMINA_KEY_PASSWORD` 全部可用；缺失即失败，不回退 Debug 签名。日常验证不需要签名凭据。

Wrapper 固定 Gradle 8.11.1 并校验发行 ZIP 的 SHA-256；依赖校验基线见 `gradle/verification-metadata.xml`。T14 已核实 Windows 严格离线构建，但 Linux AAPT2 `8.10.1-12782657` 输入与校验元数据尚缺，不应据本机成功宣称 Ubuntu CI 已通过。

---

## 工程结构

```
app/src/main/java/com/lumina/tv/
├── data/          Emby 客户端、数据模型、偏好设置、TMDB、缓存
├── pair/          扫码配对服务
├── player/        mpv 播放控制器与字幕渲染
├── ui/
│   ├── components/    通用组件（焦点容器、玻璃面板、海报卡、视频背景）
│   ├── screens/       首页 / 详情 / 播放 / 登录 / 搜索 / 设置 / 启动
│   └── theme/         颜色、字号、圆角、阴影、间距、动效
├── MainActivity.kt    路由与返回栈
├── AppRuntime.kt      运行时状态
├── BootReceiver.kt    开机自启
└── CrashHandler.kt    崩溃捕获
```

---

## 已知限制

- **系统模糊**：仅在 Android 12（API 31）及以上生效，低版本使用等效的半透明层叠方案
- **`rebuildRetry`（重建播放器重试）**：仅重建 Media3 播放器并重试，不提供 FFmpeg 解码能力；
  Media3 无法识别的封装仍依赖 mpv 或服务端转码
- **转码流时间线**：代码已区分播放器位置与内容位置，并按观测校准；无法确认的时间线保留 `timelineUnverified`。直放、remux、转码的真实设备样片仍需覆盖，不能据离线断言宣称全部验证完成
- **音频直通状态**：以设备报告为准，UI 标注「设备报告」，不代表功放一定点亮
- **容器支持**：AVI、RMVB、ISO 原盘与 BDMV 目录缺少 Media3 解封装器，
  依赖 mpv 或服务端转码
- **转码能力**：转码依赖 Emby 服务端配置。服务端输出异常时客户端会改走直连，
  但直连要求终端具备对应解码能力
- **网盘直链条目**：此类条目通常返回网页而非媒体流，视频背景会自动跳过，仅显示静态背景
- **背景缓存**：视频背景占用最多 300MB 本地缓存，随使用情况自动淘汰
- **native 来源与许可证**：24 个 `.so` 的实际 SHA-256、候选 revision、编译信息与缺证项见 [来源清单](docs/native-sources.md)。已核实许可证声明不等于对应源码交付或法律合规已完成；当前发布门禁为 BLOCKED

---

## 版本

发行版本与更新说明见 [Releases](https://github.com/q3330576893/lumina-tv/releases)，
逐步变更见 [CHANGELOG.md](CHANGELOG.md)。
