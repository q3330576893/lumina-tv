# 第三方组件与许可

本项目整体按 **GNU General Public License v3.0** 分发（见根目录 `LICENSE`）。
原因是项目链接了以下 GPL 组件，GPL 义务由分发行为触发。

## GPL 组件

| 组件 | 版本/坐标 | 许可证 | 说明 |
|---|---|---|---|
| libmpv（JellyCine 构建） | `org.jellycine.mpv:libmpv:1.0.4` | GPLv2+（mpv 默认构建） | 备用播放内核；源码见 https://github.com/mpv-player/mpv |
| Media3 FFmpeg 解码扩展 | `org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+2` | GPL-3.0 | Media3 音频软解扩展（AC3/EAC3/DTS/TrueHD/MLP 等）；源码见 https://github.com/jellyfin/jellyfin-androidx-media |
| FFmpeg | 由上述扩展静态链接 | GPL（随扩展构建） | https://ffmpeg.org |

## Apache-2.0 组件（保留原许可）

- AndroidX / Jetpack Compose / Media3（`androidx.media3:*:1.7.1`）
- OkHttp、Coil、Haze、NanoHTTPD、ZXing

## 分发说明

发布 APK 时，对应源码即本仓库同一提交：
https://github.com/q3330576893/lumina-tv

如需构建信息（JDK 17、Gradle 8.12、compileSdk 36、minSdk 26），见 `README.md` 与 `.github/workflows/`。
