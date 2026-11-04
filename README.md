# 琉璃 TV（Liuli TV）

Android TV / 手机双端 Emby 播放器。Kotlin + Jetpack Compose + Media3，iOS 玻璃质感视觉，安装包约 3 MB，适配 Android 6.0+ 低中高端电视盒子。

当前版本：**v1.4.0**（versionCode 6）

## 功能

- 首页：推荐大图 + 继续观看 / 最新添加 / 我的收藏 / 电影 / 剧集横滑列表
- 详情：海报、简介、Emby + TMDB 双评分、分季选集、**多片源 / 多画质切换**
- 播放：Media3 ExoPlayer 硬解直连，失败自动降级转码；倍速、字幕、音轨、画面比例；断点续播与进度回传
- 快捷：长按 OK 3 倍速快进、跳过片头（含单剧记忆）、自动连播
- 个性化：5 套主题色、3 档玻璃质感（磨砂 / 清透 / 关闭）
- 遥控：扫码把服务器地址、账号、密码直接写入电视；手机当遥控器
- 系统：开机自启、播放时屏幕常亮

## 构建

环境：JDK 17 / Gradle 8.7 / Android SDK 35

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
├── ui/        Compose 界面与主题
├── MainActivity.kt / AppRuntime.kt / BootReceiver.kt / CrashHandler.kt
```

## 说明

- 本仓库为应用源码，不含签名文件与构建产物
- 播放内核目前为 ExoPlayer 单内核

> AI生成
