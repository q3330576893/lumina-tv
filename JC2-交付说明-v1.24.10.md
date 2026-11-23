# 交付说明 · v1.24.10 (154)

本版两件事：接 FFmpeg 音频扩展；做 F03–F06 四项交互修正。许可证按你的选择改为 GPL-3.0（继续公开发布的前提）。

## 1. FFmpeg 音频软解（Media3 内核内）

- 依赖：`org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+2`
  - 该包 POM 依赖 **media3 1.7.1**，与本项目完全一致，**没有升级 media3**。
  - 含解码器：eac3 / ac3 / dca(DTS) / truehd / mlp / opus / vorbis / flac / av1。
- 生效方式：`PlayerScreen` 的 `DefaultRenderersFactory` 早已是 `EXTENSION_RENDERER_MODE_ON`，加依赖即注册，未改渲染器代码。
- 混淆：新增 `-keep class androidx.media3.decoder.ffmpeg.** { *; }`，防止 R8 把反射加载的 `FfmpegAudioRenderer` 裁掉。
- 体积：AAR 含 4 ABI，`abiFilters` 只打包 arm64-v8a / armeabi-v7a，约 +2.9MB。

## 2. F03 日志降噪

- `gridKey`、`gridFocus` 改为 `BuildConfig.DEBUG && ++counter % 20 == 0` 采样，Release 不再在按键热路径拼字符串。

## 3. F04 FocusSurface 去阴影

- `shadowElevation` 固定 0f，删除 `FocusSpec.elevation` 及 4 处构造参数。
- 海报卡原本就没有阴影（`posterFocusGlow`），未改动。

## 4. F05 海报 gutter

- 新增 `focusGutter(cardWidth, maxScale)`：`((maxScale-1)/2 * 卡宽) + 8dp`。
- 媒体库网格、默认网格、RowSection 的 LazyRow 的 `contentPadding` 四边加 gutter，四角卡放大不再被裁。
- 卡片宽度公式、`spacedBy`、列数均未改。

## 5. F06 开屏

- 删掉恒为 0 的 `elapsed` 判断，最短展示 600ms → **320ms**；`bootDone` 改 `rememberSaveable`，回前台不重播。
- 淡入改为 `Animatable(0f) → 1f`（520ms），不再"初值即目标"。
- "LUMINA" 字色 `Ink` → `TextMain`（原来深色压深色几乎不可见）。
- 扫光只在 `Lifecycle.STARTED` 时渲染。
- `window` 背景同步 `ThemeRuntime.bgTop`，消除首帧色差。

## 6. 许可证

- 根目录新增 `LICENSE`（GPL-3.0 全文）与 `THIRD-PARTY-NOTICES.md`（libmpv GPLv2+、FFmpeg 扩展 GPL-3.0、Apache-2.0 组件清单与源码地址）。

## 验证

- `compileDebugKotlin` / `compileDebugUnitTestKotlin` / `testDebugUnitTest` 通过。
- `assembleRelease` 通过后核对 APK 内 `libffmpegJNI.so` 与 `libmpv.so` 的 ABI 对齐。

## 待实测

1. EAC3 / DTS / TrueHD 片源：应不再跳转码或切 mpv，Media3 内直接出声。
2. 首页按键：日志降噪后无观感变化；四角海报放大不裁边。
3. 冷启动：开屏约 320ms，LUMINA 可见，无白闪。
