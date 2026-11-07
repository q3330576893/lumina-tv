# LuminaTV（Lumina）项目说明

Android TV 端 Emby 播放器。Kotlin + Jetpack Compose + Media3，另有 mpv、IJK 两套可选播放内核。

## 技术栈与版本
- minSdk 26 / compileSdk 36 / targetSdk 35
- AGP 8.10.1 / Gradle 8.11.1 / Kotlin + Compose
- 播放内核：Media3 1.7.1（默认）、libmpv 1.0.4、ijkplayer 0.0.26
- 版本号：versionCode 22 / versionName 1.15.0
- 包名：com.lumina.tv

## 构建环境（Windows）
```powershell
$env:JAVA_HOME = "C:\BuildTools\jdk-17.0.13+11"
$env:GRADLE_USER_HOME = "C:\BuildTools\gradle-home"
C:\BuildTools\gradle-8.11.1\bin\gradle.bat -p . assembleDebug --no-daemon
```
- Android SDK：C:\BuildTools\android-sdk
- 签名：仓库根目录 `lumina.jks` + `local.properties`（不入库）
- 若构建卡在 R8/classes.dex，先 `taskkill` 残留 Gradle 守护进程再重试
- 中文路径会导致 Gradle 异常，构建与临时目录一律用纯英文路径

## 目录结构
```
app/src/main/java/com/lumina/tv/
  data/        EmbyClient、Prefs、LibraryCache、模型
  player/      MpvPlayerController、IjkPlayerController、SubtitleRenderersFactory
  pair/        PairingServer（局域网网页配对登录）
  ui/components/  Components.kt（FocusSurface、PosterCard、GlassPanel、QrCode）
  ui/screens/  HomeScreen、DetailScreen、PlayerScreen、SettingsScreen、LoginScreen、...
  tv/          应用入口与运行时
```

## 设计约定
- 视觉：玻璃质感（半透明 + 磨砂 + 圆角 + 高光边），不要改成扁平风
- 主题：深色底 + 蓝紫强调色，Accent/TextMain/TextSub/GlassLine 统一取色
- 电视交互：所有可点元素走 FocusSurface，焦点态要明显（遥控器 D-pad）

## 当前状态（重要）
- 已发布 v1.15.0（commit 214680a）
- 工作区有 7 个文件未提交改动，共 +327 行，是上一轮修复的成果，**必须保留，不得回退**
- 改动备份：`.temp/pre-resume-backup.patch`
- 本轮任务工单：`.temp/fix-ticket-03-claude.md`

## 工作纪律
1. 先读工单，再动手；按工单列出的优先级执行
2. 已完成项禁止重写、禁止"顺手优化"
3. 每完成一组改动立即 assembleDebug 验证
4. 改完必须给出：改了哪些文件、为什么、如何验证

> AI生成
