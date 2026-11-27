# 第三方组件与许可

核对基线：2026-11-27，当前工作区 `versionCode 163` / `versionName 1.24.19`。本文件是技术来源核对，**不是法律合规结论，也不是发布批准**。根目录 `LICENSE` 中的 GPL v3 文本保持不变；项目放置该文本不能替代第三方组件的许可、版权声明与对应源码义务。

## 已核实的许可证声明

这里的「已核实」指依赖 POM 或列出的上游许可文本确实包含该声明，**不表示所有传递依赖、源码交付或 native 构建对应关系已核实**。表中版本是 Gradle 直接声明或 BOM 约束，不是完整运行时解析 SBOM；本机缓存 POM 的原文、SHA-256 与许可字段快照见 [license-evidence.json](app/src/test/resources/t15/license-evidence.json)。关于页展示同样的声明范围，并提供来源按钮。

| 组件 / 当前直接依赖 | 核实到的声明 | 证据 / 源码渠道 |
| --- | --- | --- |
| AndroidX Core KTX 1.13.1、Activity Compose 1.9.2 | Apache-2.0，POM | [Core](https://developer.android.com/jetpack/androidx/releases/core#1.13.1)、[Activity](https://developer.android.com/jetpack/androidx/releases/activity#1.9.2)；源码 [AndroidX](https://cs.android.com/androidx/platform/frameworks/support) |
| Lifecycle Runtime / ViewModel Compose 2.8.6 | Apache-2.0，POM | [Lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle#2.8.6) |
| Compose BOM 2024.09.03 及 UI / tooling-preview / Material3 / icons / foundation；TV Material 1.0.0 | Apache-2.0，依赖 POM 声明；BOM 本身不提供可执行代码 | [Compose](https://developer.android.com/jetpack/androidx/releases/compose)、[TV](https://developer.android.com/jetpack/androidx/releases/tv#1.0.0) |
| Media3 ExoPlayer / HLS / DASH / UI / OkHttp datasource 1.7.1 | Apache-2.0，POM 与上游 LICENSE | [Media3 1.7.1 LICENSE](https://github.com/androidx/media/blob/1.7.1/LICENSE) |
| Haze 1.5.0 | Apache-2.0，POM | [源码](https://github.com/chrisbanes/haze) |
| OkHttp 4.12.0 | Apache-2.0，POM | [源码](https://github.com/square/okhttp/tree/parent-4.12.0) |
| Coil Compose 2.7.0 | Apache-2.0，POM | [源码](https://github.com/coil-kt/coil/tree/2.7.0) |
| NanoHTTPD 2.3.1 | **BSD-3-Clause**，上游版本 LICENSE.md；旧文档的 Apache-2.0 分类不正确 | [版本许可](https://github.com/NanoHttpd/nanohttpd/blob/nanohttpd-project-2.3.1/LICENSE.md)。版权归 Paul S. Hawke、Jarno Elonen、Konstantinos Togias 等，详见该文件 |
| ZXing Core 3.5.3 | Apache-2.0，上游版本 LICENSE | [版本许可](https://github.com/zxing/zxing/blob/zxing-3.5.3/LICENSE) |
| ZXing Android Embedded 4.3.0 | Apache-2.0，POM | [源码与 COPYING](https://github.com/journeyapps/zxing-android-embedded) |
| TinyPinyin 2.0.3 | Apache-2.0，v2.0.3 指向的提交许可 | [LICENSE @ c4c65b9](https://github.com/promeG/TinyPinyin/blob/c4c65b9c0c82cc8e60ef081e91f3cc66b7829dcc/LICENSE)；不使用不存在的 `2.0.3` tag 链接 |
| Anime4K，随附的五份 shader | MIT，实际 `app/src/main/assets/anime4k/*.glsl` 文件头 | [上游](https://github.com/bloc97/Anime4K)；Copyright (c) 2019-2021 bloc97。当前文件保留完整 MIT 许可头；精确源码 commit 未找到上游证据 |
| Kotlin stdlib / kotlinx.coroutines（传递运行库） | Apache-2.0，本机缓存对应 POM 中的声明 | [Kotlin](https://github.com/JetBrains/kotlin)、[Coroutines](https://github.com/Kotlin/kotlinx.coroutines)。缓存含多个版本，不能把缓存目录中最高版本当作当前解析版本；本表不据此补造版本号 |

## native：dev.jdtech.mpv 1.0.0；完整交付仍待核实

实测双 ABI（arm64-v8a、armeabi-v7a）共 **24 个 `.so`**。逐文件 SHA-256、AAR 字节匹配、上游候选 revision、编译选项、patch 与缺证项见 [native-sources.md](docs/native-sources.md) 和 [机器清单](docs/native-sources.json)。**当前供应方为 `dev.jdtech.mpv:libmpv:1.0.0`**，AAR SHA-256 `df146592480fc8418415a06b1f1a1d6318b0088e21f52254b0e9a82b61ca8fa2`。旧 JellyCine 的来源不可得问题**已通过更换依赖解决**；完整对应源码交付、静态依赖 NOTICE 及可复现绑定仍待核实，整体门禁继续 BLOCKED。T15 的旧 24 项逐文件门禁是历史快照，不表示仍在使用旧供应方。

| 产物 / 依赖 | 已核实的声明或观测 | 待核实与源码渠道 |
| --- | --- | --- |
| `dev.jdtech.mpv:libmpv:1.0.0` wrapper、`libplayer.so` | **wrapper 代码 MIT 已核实**：v1.0.0 LICENSE 与 POM；Copyright (c) 2016 Ilya Zhuravlev、sfan5。所含 mpv / FFmpeg native 按 **GPL-3.0-or-later** 声明，MIT 不覆盖完整 bundle | [wrapper tag v1.0.0](https://github.com/jarnedemeulemeester/libmpv-android/tree/v1.0.0)；T18B 源归档 SHA-256 `c6cd247b55653dabaecfb3e383e4bb8f77eb6e4a02e1a642405a6a46d94a48b9`。归档和配方已取得，完整依赖/NOTICE 及二进制源码绑定待核实 |
| `libmpv.so` | 两个 ABI 均自报 `mpv v0.41.0`，enabled features 含 `gpl`；[mpv Copyright](https://github.com/mpv-player/mpv/blob/v0.41.0/Copyright) 默认 GPL-2.0-or-later；随附 GPLv3+ FFmpeg 后 native 组合按 **GPL-3.0-or-later** 声明 | 新 wrapper 配方固定 mpv v0.41.0；完整静态依赖许可与实际源码/patch/构建绑定待核实。不能按 LGPL-only 或整个 AAR 的 MIT 声明 |
| `libavcodec`、`libavdevice`、`libavfilter`、`libavformat`、`libavutil`、`libswresample`、`libswscale` | 两个 ABI 共 14 个文件均含 `GPL version 3 or later` 与 `--enable-gpl --enable-version3`；实际 APK 自报 **FFmpeg n8.1** | **GPL-3.0-or-later 产物声明已核实**；[源码 n8.1](https://github.com/FFmpeg/FFmpeg/tree/n8.1)、[法律说明](https://ffmpeg.org/legal.html)。完整静态依赖许可、对应源码交付与二进制源码绑定仍待核实；不与 Media3 内 FFmpeg 混用 |
| `org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+2` / `libffmpegJNI.so` | POM 与[扩展 LICENSE](https://github.com/jellyfin/jellyfin-androidx-media/blob/414d3fca8321b4875a5272f852a2abebe24b1bfe/LICENSE) 声明 GPL-3.0；v1.6.1+2 的 gitlink 固定 FFmpeg `d388c347d41e4eb516dec05910551c5461e65615` 与 Media3 `ae9a2339679826f870690092509a135dffc0d7e4` | [完整上游仓库](https://github.com/jellyfin/jellyfin-androidx-media/tree/414d3fca8321b4875a5272f852a2abebe24b1bfe)，获取源码需包含两个子模块。该固定 FFmpeg 的 LICENSE 说明默认 LGPL-2.1-or-later；扩展的 GPL 声明不能反推 FFmpeg 一定以 GPL 选项构建。产物对应关系待核实 |
| `libc++_shared.so` | 当前 APK 字节与 libmpv AAR 一致；[LLVM libc++ 上游](https://github.com/llvm/llvm-project/blob/main/libcxx/LICENSE.TXT) 包含 Apache-2.0 WITH LLVM-exception 及历史许可说明 | **实际 NDK / libc++ revision 和完整随附 NOTICE 待核实**。编译器版本或 wrapper 的 NDK 版本只能作线索，不能替代 libc++ 源码证明 |
| `libandroidx.graphics.path.so`，`androidx.graphics:graphics-path:1.0.1` | APK 字节与该 AAR 一致；POM 声明 Apache-2.0 | [AndroidX 源码入口](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:graphics/graphics-path/)。**确切 native commit、构建选项与内部 vendored 代码的许可/NOTICE 待核实**；不将全部内部代码一律归为 Apache-2.0 |

## 缺证、风险与发布门禁

- **BLOCKED：产物 → 源码绑定**。现有 AAR / APK 哈希可以确认当前交付字节，不能证明编译源树、工具链、patch 全集或所有静态依赖。未找到发布者针对这些哈希的完整构建来源证明，也未进行 native 重建比对。
- **BLOCKED：完整对应源码交付**。dev.jdtech.mpv 的 v1.0.0 wrapper/JNI 与构建脚本可取得，旧来源不可得已通过更换解决；但 Maven `sources.jar` 仍为空，T18B 源归档并未包含所有依赖源码。按 [native-sources.md](docs/native-sources.md) 当前章节中的固定 tag / 下载脚本取得 mpv、FFmpeg、静态依赖和递归子模块，再收集构建工具链、patch 与许可；Jellyfin 的 sourcesJar 仍排除 `**/ffmpeg/`，不能只交 JAR 就宣称完整。
- **BLOCKED：静态依赖与 NOTICE**。新 mpv 配方涉及 libass、libplacebo、Lua、FreeType、HarfBuzz、FriBidi、libunibreak、fontconfig、libxml2、mbedTLS、dav1d 等。虽已取得声明的版本与下载脚本，实际产物 revision、patch、完整许可证和版权声明仍待逐项核实；未知部分继续标「待核实」。AndroidX path 内部代码与 NDK libc++ 随附声明同样待补；不把旧供应方 shaderc / SPIR-V 配方当作新包已证实输入。
- GPL 的 copyleft（常称「传染性」）可能影响组合/衍生作品的分发条件；LGPL 构建也可能涉及修改说明、源码与重链接等要求。应根据实际代码、链接方式及分发形式，由专业人员复核；不能仅靠 MIT POM、动态链接、放置 LICENSE 或将项目称为 GPL 就判断已合规。
- 权威核对入口：[GNU GPL v3，特别是 §§1、5、6](https://www.gnu.org/licenses/gpl-3.0.html)、[GNU LGPL v2.1，特别是 §6](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)、[FFmpeg License and Legal Considerations](https://ffmpeg.org/legal.html)、[mpv Copyright](https://github.com/mpv-player/mpv/blob/v0.41.0/Copyright)。这是复核依据，不构成本项目已满足条款的声明。
- 本文是来源清单，**不是所有第三方许可全文和版权声明的交付包**。发布前仍须按实际分发范围准备对应源码、适用许可全文与所有必需 NOTICE，并保留以上缺证项直到有可检查的证据。
- 当前 Gradle packaging 排除了 `/META-INF/{AL2.0,LGPL2.1}` 资源；是否需要通过其他交付路径补齐相应许可文本和声明，必须纳入分发复核。本单未修改 Gradle 或据此宣称满足许可文本交付要求。
- T14 的 Linux AAPT2 校验输入缺失是另一个独立 BLOCKED；本次许可核对不解除它。
- T15 来源夹具尚未接入 CI 发布步骤（不在本工单可写范围）；不能把文档中的 BLOCKED 误认为现有自动发布流程已经强制检查它。
