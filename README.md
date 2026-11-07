# Lumina

Android TV / 手机双端 Emby 播放器。Kotlin + Jetpack Compose + Media3，iOS 玻璃质感视觉，适配 Android 8.0+ 低中高端电视盒子。

当前源码版本：**v1.15.0**（versionCode 22）。

## v1.15.0 更新汇总

- 播放内核升级为三内核：Media3 ExoPlayer / **mpv** / **IJK**，播放失败或格式不支持时自动降级，设置页可手动指定
- Media3 升级 1.4.1 → 1.7.1；工程升级 minSdk 23 → 26、compileSdk 35 → 36、AGP 8.10.1、Gradle 8.11.1
- 搜索页：电影 / 剧集分组、类型筛选，支持拼音全拼与首字母搜索
- 字幕：完整字幕样式设置与时间偏移调节
- 播放：Emby 章节 / 片段跳转
- 设置：新增清除当前 Emby 用户已观看标记入口
- 播放中缓冲提示与客户端更新检测
- 媒体库列表滚动位置记忆、各来源页面焦点恢复

## 功能

- 首页：推荐大图 + 继续观看 / 最新添加 / 我的收藏 / 电影 / 剧集横滑列表；题材分类逐项筛选，遥控器返回可逐层退出
- 详情：海报、简介、Emby + TMDB 双评分、主演与主创、相关推荐、分季选集、多片源 / 多画质切换
- 播放：三内核（Media3 / mpv / IJK）硬解优先、自动降级；倍速、字幕样式与偏移、音轨、画面比例；章节 / 片段跳转；断点续播与进度回传
- 快捷：长按 OK 3 倍速快进、跳过片头（含单剧记忆）、自动连播
- 个性化：5 套主题色、3 档玻璃质感（磨砂 / 清透 / 关闭）
- 遥控：扫码把服务器地址、账号、密码直接写入电视；手机当遥控器
- 系统：开机自启、播放时屏幕常亮

## 构建

环境：JDK 17 / Gradle 8.11.1 / AGP 8.10.1 / Android SDK 36（minSdk 26）

```bash
gradle assembleRelease
```

签名与第三方 Key 全部从 `local.properties` 读取，该文件不入库。需要在工程根目录自建 `local.properties`：

```properties
sdk.dir=/path/to/android-sdk
TMDB_API_KEY=你的TMDB密钥
LUMINA_STORE_PASSWORD=你的签名库密码
LUMINA_KEY_ALIAS=lumina
LUMINA_KEY_PASSWORD=你的签名密钥密码
```

`lumina.jks` 放在工程根目录；没有该文件时 release 构建不会签名。

## 目录

```
app/src/main/java/com/lumina/tv/
├── data/      Emby 客户端、数据模型、偏好设置、TMDB
├── pair/      扫码配对服务
├── player/    mpv / IJK 播放控制器与字幕渲染器
├── ui/        Compose 界面与主题
├── MainActivity.kt / AppRuntime.kt / BootReceiver.kt / CrashHandler.kt
```

## 说明

- 本仓库为应用源码，不含签名文件与构建产物
- 播放内核：Media3（默认）、mpv（libmpv 1.0.4）、IJK（ijkplayer 0.0.26），可自动降级

> AI生成
