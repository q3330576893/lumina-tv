# Native 来源核对 / V6-T15

核对日期：2026-11-27（Asia/Shanghai）。应用版本固定为 **163 / 1.24.19**；本次只读核对 `app/build.gradle.kts`，没有修改版本号。**来源发布门禁：BLOCKED**。这是技术记录，不是法律合规或发布批准。

## 证据范围与方法

- 对下列实际 APK 执行 Android SDK build-tools 35.0.0 的 `aapt2 dump badging`，并直接读取 ZIP 内所有 `.so`，计算解压后文件字节 SHA-256；没有用文件名推算哈希。
- 三个 APK 的 native 路径集合与 24 个文件哈希完全一致。两个 ABI 各 12 个文件；没有漏掉 `libplayer`、`libswresample`、`libswscale` 和 AndroidX path。
- 每个 `.so` 均与本机缓存中的对应 AAR 按字节哈希匹配。此举证明打包来源一致，不证明 Maven 发布者身份、编译所用 commit 或许可证义务已满足。
- 编译选项/版本/许可字符串为静态读取，未在设备上执行 native API；它们是产物自报信息，不等于上游构建证明。上游脚本单列为候选声明。
- 当前没有可用 REA MCP 工具，本次使用 Python 标准库 ZIP / SHA-256 / 字符串提取及真实 `aapt2` 输出；没有生成或伪造 REA Evidence ID。
- 完整逐文件字段、编译字符串、来源 URL 与取证响应 SHA-256 见 [native-sources.json](native-sources.json)。本文件的 profile 是逐文件记录的引用：下表每行继承相应 profile 的 upstream、revision、编译选项、patch、许可证及源码渠道，JSON 也将它们逐行展开。

## APK 版本与身份

关于页显示 `BuildConfig.VERSION_NAME`，与 Gradle 同源。下列哈希用于区分旧产物和本次 Debug 构建，版本相同不代表字节或代码相同。旧 APK 未被覆盖，unsigned Release 也没有因本次 UI 改动重新构建。

| APK | SHA-256 | aapt2 版本 | native 文件数 |
| --- | --- | --- | --- |
| `LuminaTV-v1.24.19.apk` | `80040ae413922e158c52d117bef2b1d5d8e92030e626c0d23e9a72666f567465` | 163 / 1.24.19 | 24 |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `89bb78e38de1c0ee2ebb5f30ebf3b20a57382322b74e8427a983176cac362a66` | 163 / 1.24.19 | 24 |
| `app/build/outputs/apk/debug/app-debug.apk` | `ddfef7fc54031ba65cfad19d1eaeb264bd360d777cb755d6a5a571ed0deff913` | 163 / 1.24.19 | 24 |

实测 `aapt2 dump badging` 的 package 行均包含：

```text
package: name='com.lumina.tv' versionCode='163' versionName='1.24.19'
```

上面摘取前三个字段；完整 package 行保存在 JSON，未用 `output-metadata.json` 代替实测。

## 实际 AAR 来源（仅为打包输入证据）

| 坐标 | AAR SHA-256 |
| --- | --- |
| `org.jellycine.mpv:libmpv:1.0.4` | `22c7e16003f59a03fd430063eb78036eb8cfb1844f9eb900387ee72c5fe7ce76` |
| `org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+2` | `c34814127291b8094f24c6287322e1cbeb131e6f2da8de7f6773c2765364d7ab` |
| `androidx.graphics:graphics-path:1.0.1` | `8ca4032b6d79b351f0b59ad4b580eddbb9423e1652f7c958830687f1eee2ec03` |

## 来源 profiles

### F9：七个 FFmpeg 动态库

对应 `libavcodec` / `libavdevice` / `libavfilter` / `libavformat` / `libavutil` / `libswresample` / `libswscale` 的两个 ABI，共 14 个文件。

- Upstream：[FFmpeg/FFmpeg](https://github.com/FFmpeg/FFmpeg)。wrapper 候选 `920dd825f245d3b86fb964c7115948bf5b502b4b` 的 `depinfo.sh` 声明 FFmpeg 9.0.1，`download-deps.sh` 使用 `n9.0.1`。GitHub tag 对象 `501bb49457b9dfb25d6a208832e0a6e6cd53108d` 解引用到 commit **`bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa`**。这是明确的上游候选，不是已证实的二进制源码 revision。
- Revision：实际产物确切 revision **未找到上游证据**。部分产物含 `FFmpeg version n9.0.1`，但自报版本不能排除本地修改。
- 编译选项：每个文件均可读到完整 configure 字符串，见 JSON。共性包括 `--target-os=android --enable-cross-compile --enable-jni --enable-mediacodec --enable-mbedtls --enable-libdav1d --enable-libxml2 --disable-static --enable-shared --enable-gpl --enable-version3 --disable-stripping --disable-doc --disable-programs --disable-muxers --disable-encoders --disable-devices --disable-filters --disable-v4l2-m2m`；arm64 使用 API 26 / armv8-a，armv7 使用 API 26 / armv7-a 及 NEON / cortex-a8 参数。未记录的环境、完整静态依赖输入仍未知。
- Patch：在所查 wrapper patch 目录中**未发现 FFmpeg patch**；这不证明实际产物没有额外修改。
- 许可证：14 个文件均含自身 `license: GPL version 3 or later` 字符串；记录为 **GPL-3.0-or-later（产物自报）**，不是从 Maven MIT POM 推断。
- 源码渠道：[固定 FFmpeg 候选](https://github.com/FFmpeg/FFmpeg/tree/bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa)；[wrapper 构建脚本](https://github.com/sureshfizzy/libmpv-android/tree/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts)；[FFmpeg 法律说明](https://ffmpeg.org/legal.html)。实际静态依赖与完整对应源码交付仍待核实。
- 状态：**BLOCKED**，缺少二进制哈希与源码、全部构建输入的绑定证据。

### MPV：libmpv.so

- Upstream：[mpv-player/mpv](https://github.com/mpv-player/mpv)，产物自报 `mpv v0.41.0`。候选 tag v0.41.0 的 tag 对象 `2c219aa822df18a1b7fd9abe3e151cd93ad67307` 解引用到 **`41f6a645068483470267271e1d09966ca3b9f413`**；wrapper 候选为 `920dd825f245d3b86fb964c7115948bf5b502b4b`。实际产物确切 revision **未找到上游证据**。
- 编译选项：产物 enabled features 含 `gpl libass libplacebo lua vulkan` 等，完整字符串在 JSON。候选 `mpv.sh` 使用 `--default-library shared -Diconv=disabled -Dlua=enabled -Dlibmpv=true -Dcplayer=false -Degl-android=enabled -Dmanpage-build=disabled`；实际 crossfile、Meson 参数全集与环境未知。
- Patch：候选 wrapper 中发现下列两份 patch，`mpv.sh` 会尝试应用，但脚本允许 patch 失败后继续；**不能确认它们已应用到当前 `.so`**。
  - [0001-android-vulkan-enable-10bit-hdr-swapchain.patch](https://github.com/sureshfizzy/libmpv-android/blob/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts/patches/mpv/0001-android-vulkan-enable-10bit-hdr-swapchain.patch)，SHA-256 `61852106916d1e6f644eac305e248150d1675937721465a49975f115e4eeea10`。
  - [0002-android-egl-enable-10bit-hdr-surface.patch](https://github.com/sureshfizzy/libmpv-android/blob/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts/patches/mpv/0002-android-egl-enable-10bit-hdr-surface.patch)，SHA-256 `524953102374cc65eb9bf150749068b6d2991acd7ee930b56c78f5df38e23dac`。

- 许可证：[固定基线 Copyright](https://github.com/mpv-player/mpv/blob/41f6a645068483470267271e1d09966ca3b9f413/Copyright) 说明默认 GPL-2.0-or-later，LGPL 模式有额外条件；当前产物含 GPL 功能，不能当作已确认的 LGPL-only 构建。FFmpeg 及静态依赖可能影响完整许可组合，**待核实**。
- 源码渠道：[固定 mpv 候选](https://github.com/mpv-player/mpv/tree/41f6a645068483470267271e1d09966ca3b9f413) 与[固定 wrapper](https://github.com/sureshfizzy/libmpv-android/tree/920dd825f245d3b86fb964c7115948bf5b502b4b)，包括 patch、libass、libplacebo、字体、shaderc / SPIR-V 等全部依赖。状态：**BLOCKED**。

### PLAYER：libplayer.so

- Upstream：[sureshfizzy/libmpv-android](https://github.com/sureshfizzy/libmpv-android/tree/920dd825f245d3b86fb964c7115948bf5b502b4b) 的 `libmpv/src/main/cpp`，CMake `add_library(player SHARED ...)` 与此名称相符。
- Revision：候选 commit **`920dd825f245d3b86fb964c7115948bf5b502b4b`** 的 Gradle 声明 `org.jellycine.mpv:libmpv:1.0.4`；查询 tag 列表仅见 v1.0.3 / v1.0.0，不能补造 v1.0.4 tag。**实际产物确切 revision 未找到上游证据**。
- 编译选项：产物完整选项未知；候选 Gradle 声明 NDK 29.0.14206865、CMake 4.1.2、`-DANDROID_STL=c++_shared`、`-Werror`、`-std=c++11`；候选 CMake 链接 mpv、avcodec、log、android。
- Patch：**未发现独立 JNI patch**；源码直接维护，实际额外修改未知。
- 许可证：wrapper POM / LICENSE 声明 **MIT**，不覆盖所有链接组件；完整产物许可组合待核实。源码渠道为上述固定 wrapper；其 Gradle 使用 `SourcesJar.Empty()`，不能把空 sources JAR 当完整源码。状态：**BLOCKED**。

### JNI：libffmpegJNI.so

- Upstream：[Jellyfin 扩展 v1.6.1+2](https://github.com/jellyfin/jellyfin-androidx-media/tree/414d3fca8321b4875a5272f852a2abebe24b1bfe)，tag 指向 **`414d3fca8321b4875a5272f852a2abebe24b1bfe`**；固定 gitlink：FFmpeg **`d388c347d41e4eb516dec05910551c5461e65615`**（RELEASE=6.0.2），Media3 **`ae9a2339679826f870690092509a135dffc0d7e4`**。这些是扩展源码的确切固定引用；**实际产物确切 revision 未找到上游证据绑定**，不与 F9 的 FFmpeg 9.0.1 混用。
- 编译选项：实际产物未发现完整 configure 串，记为**未知**。候选 `build.sh` 声明 NDK 26.1.10909125、API 21，解码器 `flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd`；固定 Media3 `build_ffmpeg.sh` 使用静态 FFmpeg、禁用不需要的模块与 `--disable-vulkan`，详见 JSON。该脚本没有 `--enable-gpl`；不可因扩展 POM 是 GPL 就反推 FFmpeg 配置。
- Patch：在固定扩展源码树与 build.sh 中**未发现 patch 清单**；未审定 fork 的全部历史差异，不宣称产物未修改。
- 许可证：扩展 **GPL-3.0（POM / LICENSE 声明）**；固定 FFmpeg 的 LICENSE 说明默认 LGPL-2.1-or-later，实际选项与完整许可组合待核实。
- 源码渠道：上面的完整扩展仓库，须连同 [FFmpeg gitlink](https://github.com/FFmpeg/FFmpeg/tree/d388c347d41e4eb516dec05910551c5461e65615) 和 [Media3 gitlink](https://github.com/androidx/media/tree/ae9a2339679826f870690092509a135dffc0d7e4) 获取。扩展 `sourcesJar` 明确排除 `**/ffmpeg/`；sources JAR 不能替代全部对应源码。状态：**BLOCKED**。

### CXX：libc++_shared.so

- Upstream：[LLVM libc++](https://github.com/llvm/llvm-project/tree/main/libcxx) / [Android NDK](https://android.googlesource.com/platform/ndk/)，实际字节与 libmpv AAR 匹配。
- Revision：**未找到上游证据**。候选 wrapper 的 NDK 29.0.14206865、产物中的 Clang 21 / 19 字符串只能作线索；Clang 编译器 commit 不是 libc++ 源码 commit。
- 编译选项：**未知**。Patch：**未发现**；NDK 修改全集未知。
- 许可证：[上游 LICENSE.TXT](https://github.com/llvm/llvm-project/blob/main/libcxx/LICENSE.TXT) 包含 **Apache-2.0 WITH LLVM-exception** 与历史许可说明；不能代替实际 NDK 随附许可、第三方版权和 NOTICE，均待核实。状态：**BLOCKED**。

### PATH：libandroidx.graphics.path.so

- Upstream：[AOSP AndroidX graphics-path](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:graphics/graphics-path/)，实际字节匹配 `androidx.graphics:graphics-path:1.0.1` AAR。
- Revision：**未找到上游证据**，不将 Maven 1.0.1 当源码 revision。
- 编译选项：**未知**。Patch：**未发现**；对应 native 源树与完整改动未知。
- 许可证：**Apache-2.0（POM 声明）**；native 内部 vendored 代码及完整 NOTICE 待核实，不依据外层 POM 统一推断。
- 源码渠道为上述 AndroidX 入口与[版本说明](https://developer.android.com/jetpack/androidx/releases/graphics#1.0.1)，当前尚不足以还原此 native 构建。状态：**BLOCKED**。

## 逐文件实际 SHA-256

各行的 profile 指向上述完整记录；下面的状态均指来源闭环，不是哈希读取失败。

| APK 内路径 | SHA-256 | 来源 profile | 状态 |
| --- | --- | --- | --- |
| `lib/arm64-v8a/libandroidx.graphics.path.so` | `41e9a793c43a0f4fddb19e33f346bace464f30f888ba7b9eaf96294ea115bfb6` | PATH | BLOCKED |
| `lib/arm64-v8a/libavcodec.so` | `37a0df62b6e25a1f932e1d497f29395364909461ef21e660be655d855e8eab60` | F9 | BLOCKED |
| `lib/arm64-v8a/libavdevice.so` | `0cee5c459d2a80f49a3ae422d44c563bb8e32450ccf2ed930b258cd8f6eba7b6` | F9 | BLOCKED |
| `lib/arm64-v8a/libavfilter.so` | `30db27351cf28c2f2961446017eaaa806f24e8fbab6b33fc3c0a66424c2977a5` | F9 | BLOCKED |
| `lib/arm64-v8a/libavformat.so` | `ef90c677577ead246afdeb39af8716f3a5c10e7667bac7a67943de420f2c2755` | F9 | BLOCKED |
| `lib/arm64-v8a/libavutil.so` | `9c934c8d2e0977b19732cc52fe6810b1aa073b0c37fc18e1e0df95316eba479e` | F9 | BLOCKED |
| `lib/arm64-v8a/libc++_shared.so` | `c4c2fe5cbcb1fba0003a31fc7ab29a9bb12df6cc187ec45a806462540e83d93b` | CXX | BLOCKED |
| `lib/arm64-v8a/libffmpegJNI.so` | `3618d53b732de4884fc04c665cf5e7d694e35d38defac37899dd7165c37de628` | JNI | BLOCKED |
| `lib/arm64-v8a/libmpv.so` | `25bf8795b72f963df5433477cdaf9117e52c18f57215e9d3529a861219b70582` | MPV | BLOCKED |
| `lib/arm64-v8a/libplayer.so` | `230c09d329ffe55214e9a4032ff7c3bb8723daec1ed5f2dabf72e1db7f3ba11f` | PLAYER | BLOCKED |
| `lib/arm64-v8a/libswresample.so` | `b6718b09fe6cc5f192e569abbddbf8ed1b855efa1ae8f0b66122b319cfaa86e5` | F9 | BLOCKED |
| `lib/arm64-v8a/libswscale.so` | `dad384778b2808f0e7bf9ae214d035ba9957414d9eb99ffdade53d2a340b4cd9` | F9 | BLOCKED |
| `lib/armeabi-v7a/libandroidx.graphics.path.so` | `41399eba6fc2a60f6f14642375c1824f3cf25eb8fec7397d753730a3ceda3e2b` | PATH | BLOCKED |
| `lib/armeabi-v7a/libavcodec.so` | `0c4308dcbf9ed971abdd741d921f103ad87669a3fac3242f805f52ff03d5b428` | F9 | BLOCKED |
| `lib/armeabi-v7a/libavdevice.so` | `7110da0776c5870661da708640aded5e2037f929d8683b69bcd4be6956bccfe6` | F9 | BLOCKED |
| `lib/armeabi-v7a/libavfilter.so` | `efb6b3cf5eae17c85bbd1785bfc82f37932d18afff523b1deadebb6588a31933` | F9 | BLOCKED |
| `lib/armeabi-v7a/libavformat.so` | `8d66df9ed38983a003fccd0479fd8cc3fa3e38d6e4143fe8f496b1f334ef9aa6` | F9 | BLOCKED |
| `lib/armeabi-v7a/libavutil.so` | `6e3d2dced7abca1d7e68c3cf33ecf6ccd13c60710fb7d7170a75b947afd1983c` | F9 | BLOCKED |
| `lib/armeabi-v7a/libc++_shared.so` | `af383654daf4cf0829615460419a180f84edd9d8bf51aa0f81ed0db811bf8491` | CXX | BLOCKED |
| `lib/armeabi-v7a/libffmpegJNI.so` | `f198ea2f9a61c30cc607550fade43e61195799149ccf34518f632960d3f4c9fc` | JNI | BLOCKED |
| `lib/armeabi-v7a/libmpv.so` | `4cb035b7c64a87eb94f80ec2de1305e051caecdb6862b4112e84b13402b666f1` | MPV | BLOCKED |
| `lib/armeabi-v7a/libplayer.so` | `45cd86cb5c9ded56cc0a7a4cd99fa1c007973590a21957454d4b49a2e5091c0c` | PLAYER | BLOCKED |
| `lib/armeabi-v7a/libswresample.so` | `9789b9cf2dd12e2d1c2dcb18d3f99efa22e1242515b7619b026b2fe21e35379c` | F9 | BLOCKED |
| `lib/armeabi-v7a/libswscale.so` | `114568505e0fc23cec87cedd69aaec754dcdda39591709c74d7396c2d0fe91d9` | F9 | BLOCKED |

## 复核、检索边界与后续门禁

本次查阅本机实际 AAR/POM、wrapper 固定候选树（含下载/构建/patch/发布脚本）、上游 tag / release API、固定 Jellyfin 树和两个子模块引用、mpv Copyright、FFmpeg 与 GNU 官方许可资料。URL、响应 SHA-256、404/超时均保存在 JSON 的 `source_evidence` 中；负面结论只限此检索范围。未找到构建证明不等于证明上游从未提供过。

候选 wrapper 的 `depinfo.sh` 列出 libass 0.17.5、libplacebo 7.360.1、Lua 5.2.4、FreeType 2.14.3、HarfBuzz 14.4.0、FriBidi 1.0.16、libunibreak 7.0、fontconfig 2.18.3、libxml2 2.15.3、mbedTLS 3.6.7、dav1d 1.5.4，shaderc 取 NDK 随附源。它们的**实际** revision、patch 与完整许可/NOTICE 均待核实，不用候选版本替代产物事实。

发布前须取得或建立可复核的 binary → source / patch / toolchain / build-input 绑定，准备完整对应源码、适用许可全文与 NOTICE，并完成专业法律复核。GPL/LGPL 风险和权威入口见 [第三方许可](../THIRD-PARTY-NOTICES.md)。**未解除这些 BLOCKED，也未解除 T14 Linux AAPT2 缺失输入的独立阻塞。**

可在有 Python、实际 APK 和 Android build-tools 时复核：

```powershell
python app/src/test/resources/t15/check_t15.py --aapt2 C:/BuildTools/android-sdk/build-tools/35.0.0/aapt2.exe
# 应因已知缺证拒绝来源闭环，预期退出 2（不是允许发布）：
python app/src/test/resources/t15/check_t15.py --aapt2 C:/BuildTools/android-sdk/build-tools/35.0.0/aapt2.exe --require-source-closure
```

该夹具只读核对版本、全部 native 路径与哈希、历史记录及缺证状态，并包含内存中的缺文件/错哈希/错版本/虚假 VERIFIED 反例。夹具 PASS 只表示记录与产物相符；来源门禁仍 BLOCKED。

夹具尚未接入 Gradle / CI 发布工作流；本工单未授权修改这些接线文件。当前门禁是有证据的阻塞记录与上述显式拒绝命令，不能宣称现有 CI 已自动强制 T15 来源闭环。

## 本次验证结果

- SettingsAbout 已修改，实跑 `.\gradlew.bat --offline --no-daemon --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx2048m -Pkotlin.compiler.execution.strategy=in-process --dependency-verification=strict :app:verifyOffline :app:assembleDebug`：**PASS**，退出 0，`BUILD SUCCESSFUL in 17m 34s`；耗时约 1055 秒。日志：`.temp/t15-evidence/verifyOffline-assembleDebug.log`。
- `aapt2 dump badging` 对照三个实际 APK：**PASS**；逐文件 native SHA-256 与 AAR 字节匹配：**24 / 24 PASS**。
- T15 只读夹具：**PASS**，26 份 POM 快照、原有历史 CHANGELOG、根 LICENSE、版本与全部 native 记录均核对；四类反例被拒绝。
- `--require-source-closure`：按预期退出 **2 / BLOCKED**，未误报来源闭环。
- `git diff --check`：**PASS**；新增文件另用 no-index whitespace check 检查，无空白问题。
- Gradle 版本源文件 SHA-256 与本工单起点完全相同：`6ba80b12eead826b8674a9844076925ae5df0dc4900b47f8082a26f1b140d7e7`。仅修改白名单内文档、SettingsAbout 与对应测试夹具；没有 commit、push 或发布。

以上构建 PASS 不解除 native / 许可来源缺证、T14 Linux 输入覆盖或真机 UI 验证的限制。日志目录为本机忽略目录；机器可读来源与构建结果摘要已保留在本清单 JSON，不把日志存在于本机当作新克隆已具备的证据。


## 来源查实结果（V6-T15B）

取证日期：2026-11-27（Asia/Shanghai）；HTTP 抓取范围 **2026-11-27T00:10:42.071138+00:00 至 2026-11-27T00:15:58.076488+00:00（UTC）**。本节仅取证和评估；没有运行 download.sh、patch.sh、build.sh、configure、Gradle 或 native 编译器，也没有修改产品源码、依赖或版本。现有 T15 记录原样保留。

**决策结论：找到与 1.0.4 版本声明有关的 wrapper commit 和可下载的 FFmpeg/mpv 候选基线源码；未找到足以证明当前 AAR / .so 确切对应源码与全部构建输入的证据。完整对应源码可得性为 UNKNOWN，当前原样公开构建链存在损坏补丁，本机不具备离线重建条件；没有证明可复现或字节一致。**

### 1. wrapper 上游、版本关系与产物对应

- Maven POM 的项目 URL / SCM 明确指向 `sureshfizzy/libmpv-android`。仓库定位已核实。Maven module 公布的 AAR SHA-256 与本机缓存实测均为 `22c7e16003f59a03fd430063eb78036eb8cfb1844f9eb900387ee72c5fe7ce76`，大小 56,130,060 bytes。它证明本地 AAR 对应公开 Maven 输入，仍不证明编译源码。证据：`maven-pom`；`maven-module`（URL / HTTP / SHA-256 / 时间见本节末证据表）；本地复核 `.temp/T15B-evidence/binary-script-comparison.json`。

- **版本声明 commit：`920dd825f245d3b86fb964c7115948bf5b502b4b`**。提交说明 `chore: bump version to 1.0.4`，提交时间 2026-08-29T13:51:50Z；提交仅把 `libmpv/build.gradle.kts` 的 1.0.3 改成 1.0.4。该源码候选确实声明所查 Maven 坐标，**不是已证实的 AAR 构建 commit**。POM 没有 SCM commit/tag 字段；没有取得产物绑定证明。证据：`wrapper-commit`；`wrapper-build-gradle`；`maven-pom`；`wrapper-attestations`（URL / HTTP / SHA-256 / 时间见本节末证据表）。

- **1.0.4 tag / release：未找到。** tags API 返回 v1.0.3、v1.0.0，release 列表为 `[]`；直接查询 `v1.0.4`、`1.0.4` tag 和 `v1.0.4` release 均 HTTP 404。负面结论限公开、未认证查询和当前抓取时间，不等于证明上游从未提供或不存在私有草稿。证据：`wrapper-tags`；`wrapper-releases`；`wrapper-v1.0.4-ref`；`wrapper-1.0.4-ref`；`wrapper-release-v1.0.4`（URL / HTTP / SHA-256 / 时间见本节末证据表）。

- **公开 CI 不能补上成功构建证明。** run `33256129384` 的 head SHA 是上述 commit；job `99110037834` 显示 `Apply patches=failure`，`Build=skipped`、上传步骤跳过；该运行和仓库当前 artifact 列表均 `total_count=0`。详细 run/job 日志 API 均 HTTP 403，正文为 `Must have admin rights to Repository.`，不能确定上游具体错误与本机检查是同一错误。证据：`wrapper-actions-runs`；`wrapper-run-jobs`；`wrapper-run-artifacts`；`wrapper-repo-artifacts`；`wrapper-run-log`；`wrapper-job-log`（URL / HTTP / SHA-256 / 时间见本节末证据表）。

### 2. native 脚本、revision、configure 与 patch

候选 `depinfo.sh` 声明 FFmpeg 9.0.1、mpv 0.41.0；下载脚本分别按 `n$v_ffmpeg` / `v$v_mpv` 浅克隆。当前 tag 对象已通过 API 解引用：

| 组件 | 脚本 tag | tag 对象 SHA | 候选 commit SHA | 证据 |
| --- | --- | --- | --- | --- |
| FFmpeg | n9.0.1 | 501bb49457b9dfb25d6a208832e0a6e6cd53108d | bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa | `wrapper-depinfo` / `wrapper-download-deps` / `ffmpeg-tag` / `ffmpeg-tag-object` |
| mpv | v0.41.0 | 2c219aa822df18a1b7fd9abe3e151cd93ad67307 | 41f6a645068483470267271e1d09966ca3b9f413 | `wrapper-depinfo` / `wrapper-download-deps` / `mpv-tag` / `mpv-tag-object` |

**上述仅固定候选基线；不把候选 revision 升级为当前二进制已证实源码。**

FFmpeg 原脚本 `buildscripts/scripts/ffmpeg.sh` 第 26–34 行（含变量，非执行输出）：

```bash
../configure \
	--target-os=android --enable-cross-compile --cross-prefix=$ndk_triple- --cc=$CC \
	--arch=${ndk_triple%%-*} --cpu=$cpu --pkg-config=pkg-config --nm=llvm-nm \
	--extra-cflags="-I$prefix_dir/include $cpuflags" --extra-ldflags="-L$prefix_dir/lib" \
	--enable-{jni,mediacodec,mbedtls,libdav1d,libxml2} \
	--disable-static --enable-shared --enable-{gpl,version3} \
	--disable-{stripping,doc,programs} \
	--disable-{muxers,encoders,devices,filters} \
	--disable-v4l2-m2m
```

`--enable-{gpl,version3}` 经 Bash brace expansion 生成 `--enable-gpl --enable-version3`，`--disable-{stripping,doc,programs}` 同理。**与产物 flags 一致：YES。** 本单重新读取缓存 AAR 的 4 ABI `libavcodec.so` / `libavformat.so`，共 8 份 configure 串；用脚本的 API 26、CPU / cross-prefix 变量静态展开，安装前缀取自产物路径、extra-cflags/extra-ldflags 内空白归一化后，8/8 参数序列一致。未执行 shell 构建脚本或 native API。此结果只支持配置一致，不证明未记录修改、自动检测功能或产物可复现。证据：`wrapper-ffmpeg`；`wrapper-build`；`wrapper-path`（URL / HTTP / SHA-256 / 时间见本节末证据表）；比对方法及完整原串保存在 `.temp/T15B-compare.py`、`binary-script-comparison.json`，也写入 JSON 本节。

mpv 候选脚本使用 Meson `--default-library shared -Diconv=disabled -Dlua=enabled -Dlibmpv=true -Dcplayer=false -Degl-android=enabled -Dmanpage-build=disabled`。crossfile 由 `build.sh` 生成，默认 release/static、`wrap_mode=nodownload`，mpv 单独覆写为 shared。实际构建的配置日志和功能探测输入未找到。证据：`wrapper-mpv`；`wrapper-build`（URL / HTTP / SHA-256 / 时间见本节末证据表）。

**Patch 检索：**固定 wrapper 递归树 `truncated=false`；patch 目录仅有下列两份 mpv patch，**未找到 FFmpeg patch**。这不是实际 FFmpeg 从未打补丁的证明。`patch.sh` 在 `bash -e` 下执行 `git apply`；`mpv.sh` 另外尝试 `patch -p1 -N ... || true`，因此也不能从脚本存在推断产物一定应用成功。证据：`wrapper-tree`；`wrapper-patch`；`wrapper-mpv`（URL / HTTP / SHA-256 / 时间见本节末证据表）。


| 公开 patch | 文件 SHA-256 | GNU patch --dry-run | Git apply --numstat（只解析） |
| --- | --- | --- | --- |

| `0001-android-vulkan-enable-10bit-hdr-swapchain.patch` | `61852106916d1e6f644eac305e248150d1675937721465a49975f115e4eeea10` | 退出 2：malformed patch，第 50 行 | 退出 128：corrupt patch，第 51 行 |

| `0002-android-egl-enable-10bit-hdr-surface.patch` | `524953102374cc65eb9bf150749068b6d2991acd7ee930b56c78f5df38e23dac` | 退出 2：malformed patch，第 46 行 | 退出 128：corrupt patch，第 50 行 |


两份 patch 的原始 HTTP 响应与候选归档内文件 SHA-256 相同，排除了本单下载后编辑导致差异；只在候选 mpv 源包上使用 `--dry-run`，未应用、未修补。详见 `patch-dry-run.json`、`patch-git-parse.json` 和原始 `.log`；工具版本/工具哈希在 `inspection-tool-versions.json`。因此当前公开链路**不能记为已验证可复现**；修正补丁后的新构建也不能自动证明原 AAR 来源。下载证据：`wrapper-patch-mpv-1`；`wrapper-patch-mpv-2`；`wrapper-source-archive`；`mpv-source-archive`（URL / HTTP / SHA-256 / 时间见本节末证据表）。

### 3. 对应源码与构建环境可得性

以下归档均实际下载并读取 tar 目录，没有用 tag 名猜测哈希。**归档可得 YES；它们就是当前 .so 的完整对应源码 UNKNOWN。** GitHub 自动源归档不自动包含递归 Git 子模块或原构建者的本地修改。

| 候选源包证据 ID | commit | HTTP | 下载文件 SHA-256 | 字节数 |
| --- | --- | --- | --- | ---: |

| `wrapper-source-archive` | `920dd825f245d3b86fb964c7115948bf5b502b4b` | 200 | `bfd920663e6d2006c54a413b6ef62ae4b012dfda03357133161efe8342000bc8` | 68,200 |

| `ffmpeg-source-archive` | `bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa` | 200 | `fb1931fd4eb29297ee1c1017a24f800c4d8fbea35b4f2aaeb28308a48a9149b4` | 17,326,616 |

| `mpv-source-archive` | `41f6a645068483470267271e1d09966ca3b9f413` | 200 | `068960e89211f2adc80af03f637fa393fb5e407d3dd23592c8fe8c5490bb7ae4` | 7,264,274 |


URL 与精确 UTC 时间见本节末；tar 文件在 `.temp/T15B-evidence/http/*-source-archive.body`，归档目录证据见 `source-archive-inventory.json`。wrapper 固定树没有 vendored FFmpeg/mpv 源树；仓库提供下载/构建说明和脚本，但 Maven `sources.jar` 仅 **261 bytes**，ZIP 只有 `META-INF/` 和 25-byte `META-INF/MANIFEST.MF`，没有实际源码。公开 release 列表为空，未找到绑定当前 AAR 的完整源包/构建输入 manifest。证据：`wrapper-tree`；`maven-sources`；`wrapper-build-gradle`；`wrapper-releases`（URL / HTTP / SHA-256 / 时间见本节末证据表）。

**环境定义：部分提供。** `depinfo.sh` / Gradle 声明 NDK 29.0.14206865、CMake 4.1.2、平台 android-36、Build Tools 37.0.0、命令行工具 14742923_latest；CI 为 ubuntu-latest + JDK 17，安装系统工具并通过 pip 取未锁版本 Meson。固定树未找到 Dockerfile、容器 digest 或全部宿主工具/输入的校验清单；`download-sdk.sh` 还取 gas-preprocessor 的 master。Gradle 分发为 9.7.1、AGP 9.3.2、maven-publish 0.37.0。这是可读构建方式，不是不可变环境。证据：`wrapper-depinfo`；`wrapper-build-gradle`；`wrapper-ci-build`；`wrapper-ci-publish`；`wrapper-download-sdk`；`wrapper-gradle-wrapper`；`wrapper-gradle-libs`；`wrapper-tree`（URL / HTTP / SHA-256 / 时间见本节末证据表）。

### 4. 复现前置与本机条件（仅评估）

候选依赖图：`mpv -> ffmpeg / libass / lua / libplacebo`；`ffmpeg -> mbedTLS / dav1d / libxml2`；`libass -> freetype / fontconfig / fribidi / harfbuzz / libunibreak`；`fontconfig -> libxml2 / freetype`；`libplacebo -> shaderc`。这些是脚本声明。来源：`wrapper-depinfo` / `wrapper-download-deps` / `wrapper-libplacebo` / `wrapper-shaderc`。

| 前置组件 | 候选版本/输入 | 离线准备缺口 |
| --- | --- | --- |

| mbedTLS | `3.6.7`（脚本标记） | 对应当前产物的确切 commit、patch、完整源树/所需子模块和哈希未齐备 |

| dav1d | `1.5.4`（脚本标记） | 对应当前产物的确切 commit、patch、完整源树/所需子模块和哈希未齐备 |

| libxml2 | `2.15.3`（脚本标记） | 对应当前产物的确切 commit、patch、完整源树/所需子模块和哈希未齐备 |

| libass | `0.17.5`（脚本标记） | 对应当前产物的确切 commit、patch、完整源树/所需子模块和哈希未齐备 |

| FreeType | `2-14-3`（脚本标记） | 对应当前产物的确切 commit、patch、完整源树/所需子模块和哈希未齐备 |

| fontconfig | `2.18.3`（脚本标记） | 对应当前产物的确切 commit、patch、完整源树/所需子模块和哈希未齐备 |

| FriBidi | `1.0.16`（脚本标记） | 对应当前产物的确切 commit、patch、完整源树/所需子模块和哈希未齐备 |

| HarfBuzz | `14.4.0`（脚本标记） | 对应当前产物的确切 commit、patch、完整源树/所需子模块和哈希未齐备 |

| libunibreak | `7_0`（脚本标记） | 对应当前产物的确切 commit、patch、完整源树/所需子模块和哈希未齐备 |

| Lua | `5.2.4`（脚本标记） | 对应当前产物的确切 commit、patch、完整源树/所需子模块和哈希未齐备 |

| libplacebo | `7.360.1`（脚本标记） | 对应当前产物的确切 commit、patch、完整源树/所需子模块和哈希未齐备 |

| shaderc / glslang / SPIR-V / Vulkan-Headers 等 | 脚本从 NDK `sources/third_party/shaderc` 构建；生成的 shaderc.pc 硬编码 `Version: 2024.1` | 该数字不能充当 shaderc revision；NDK 未安装，路径/内容与原产物输入均待核实 |


`mbedTLS` 声明 framework 子模块；libplacebo 声明 Nuklear、glad、jinja、markupsafe、Vulkan-Headers、fast_float 子模块（声明完整列表不等于所有默认配置均需要编译）。两处公开文件均 HTTP 200，但本单未下载完整子模块或绑定其实际二进制 revision。dav1d / libplacebo 的候选 meson.build 可读。这些可得性不升级为产物来源。证据：`mbedtls-candidate-submodules`；`libplacebo-candidate-submodules`；`dav1d-candidate-meson`；`libplacebo-candidate-meson`（URL / HTTP / SHA-256 / 时间见本节末证据表）。

- **NDK：未找到可用安装。** 当前 SDK `C:/BuildTools/android-sdk` 中无 ndk / ndk-bundle，NDK/SDK 环境变量未设；常用目录的有界搜索也未找到其他 NDK。记录了搜索根、深度和排除目录，未宣称做过全盘档案取证。要求的 NDK 为 **29.0.14206865**；CMake 4.1.2 和 Build Tools 37.0.0 也未安装。已存在 JDK 17、android-36 平台和 Build Tools 34/35/36。详见 `environment.json`。

- **宿主：Windows / 8 逻辑 CPU / 16 GiB RAM。** WSL --list --verbose 返回 `PermissionError [WinError 5] 拒绝访问`；PATH 无 docker、make、Meson、Ninja、pkg-config、CMake 等。Git Bash 可用，不能据此认定 Linux NDK、apt 或 `/proc/cpuinfo` 依赖已满足。未尝试修复权限或安装环境。

- **磁盘实测：**C: 可用 12,050,186,240 bytes（11.22 GiB）；D: 可用 6,984,413,184 bytes（6.50 GiB）；Z: 可用 8,589,934,592 bytes（8.00 GiB）。按四 ABI 的工具链、源码、静态依赖及中间文件，建议预留约 **25–40 GiB 单工作卷空间**；这是工程预算，不是实测峰值或上游声明。当前 C 盘余量偏紧，其他盘不能直接视为同一工作卷的可用容量。采样时间与字节数在 environment.json。

- **预计时间：**未执行构建、无 native 构建基准。按本机 8 个逻辑 CPU / 16 GiB RAM 作规划估计：环境齐全且补丁问题解决后，arm64 约 1–3 小时、四 ABI 约 4–10 小时；不含下载、环境准备、失败排障，不能承诺完成时间或哈希一致。

- **离线重建：NO（当前状态）。** 本单已缓存 wrapper/FFmpeg/mpv 候选源包，但未具备 Linux 宿主、NDK/完整工具链、全部依赖及所需子模块/生成器缓存，也未取得有效补丁全集和原产物输入 manifest。现有 Gradle 9.7.1 目录仅见 `.part` / `.lck`，不算完整分发缓存；所查缓存根没有 AGP 9.3.2 / publish plugin 0.37.0。native 构建本身不必依赖全部 Maven 发布插件，但若重打 AAR 也要准备这些输入。`wrap_mode=nodownload` 只禁止构建时自动下载，不会补齐缺失输入。

### 5. 缺失项 → 提供方 → 动作

| 差什么 | 谁能提供 | 需要什么动作 |
| --- | --- | --- |

| 当前 AAR / 各 .so SHA-256 对应的 wrapper、FFmpeg、mpv 确切 commit、源码本地改动和全部构建输入绑定；1.0.4 tag/release 未找到。 | org.jellycine.mpv 发布者 / sureshfizzy（原构建者） | 向发布者索取与 AAR 22c7e160… 对应的构建记录、不可变源码清单、源树归档和产物哈希；本单未联系。 |

| 两份公开 mpv patch 均为 malformed/corrupt；未取得原构建实际使用且可应用的 patch 集。 | 原构建者 / wrapper 维护者 | 索取实际应用后的 mpv 源树或校验过的有效 patch 集及应用日志；不要把自行修补后的候选源升级成原产物已证实源码。 |

| 全部静态依赖的实际 commit、子模块 gitlink、额外 patch、源码归档哈希及配置日志（含 shaderc/glslang/SPIR-V、libplacebo、libass、dav1d、mbedTLS 等）。 | 原构建者；各上游只能提供基线候选源码 | 取得完整源码/子模块 bundle 与 manifest；核对 FFmpeg config.h/config.mak、Meson crossfile、pkg-config 前缀和构建日志。 |

| 原构建环境的不可变定义：NDK 包及内容、宿主环境/镜像、Meson/Ninja/系统包、自动检测到的功能。 | 原构建者；项目维护者可为未来自建建立锁定环境 | 索取 toolchain/容器 digest、输入哈希与版本；如取不到，另立工单自建可追踪产物，不能声称字节复现旧 AAR。 |

| 公开 CI 的详细失败日志与成功产物证明；日志 API 403，所列运行/仓库 artifacts 均为零。 | 上游仓库管理员 / 原构建者 | 由管理员导出 1.0.4 实际构建/发布日志和产物清单；本单不提 issue、不发邮件、不发消息。 |

| 本机可用 Linux/WSL/容器构建环境、NDK 29.0.14206865、CMake 4.1.2、Build Tools 37.0.0、完整源码依赖缓存和工具缓存；磁盘余量偏紧。 | 本机/CI 维护者 | 另立工单预置工具链、缓存、完整源包和足够磁盘；本单不安装、不下载工具链、不构建。 |

| 在无法获得原产物完整来源时可持续维护的分发输入。 | 项目维护者 / 依赖选型负责人 | 在另行授权的工单中评估自建或更换提供完整来源证据的依赖；本单不改依赖或版本。 |

### 6. 检索状态、代理与证据目录

本机 HTTP_PROXY / HTTPS_PROXY / ALL_PROXY 未设置，NO_PROXY 仅本地地址；WinHTTP 为 Direct access，WinINET ProxyEnable=0（残留 ProxyServer=127.0.0.1:7890 未启用）。本单 Python 请求显式使用 `ProxyHandler({})` **直连**，未改全局代理，未使用凭据。51 次请求无超时；所有 403/404 保留原始响应和哈希。GitHub 日志的 403 明确是 admin 权限要求，未把它归因为被墙。完整 headers、重定向、开始/结束时间、耗时、字节数见 JSON；下面 SHA-256 均针对实际保存的 HTTP 响应正文 bytes。

网络结论限所查公开 POM/元数据、固定 wrapper 树、前 100 条 commit（非完整历史）、当前公开 tags/releases/branches/actions/artifacts/attestations、候选上游 tag / 归档和所列依赖文件；未查私有/草稿/管理员日志，未发邮件、issue 或消息。未找到不等于证明从未存在。日志和归档位于本机忽略目录 `.temp/T15B-evidence`；可共享的 URL / 哈希 / 时间 / 关键片段已追加到本清单 JSON，不把本机文件等同于新克隆已有全部输入。


| 证据 ID | URL（实际尝试） | HTTP | 完成抓取时间（UTC） | 响应正文 SHA-256 |
| --- | --- | ---: | --- | --- |

| `repo` | https://api.github.com/repos/sureshfizzy/libmpv-android | 200 | 2026-11-27T00:10:43.084026+00:00 | `363e477e36e96eb17ce753c2d54fbd7ee8d61dfa2a326aaf25bbd78f2142002c` |

| `wrapper-commit` | https://api.github.com/repos/sureshfizzy/libmpv-android/commits/920dd825f245d3b86fb964c7115948bf5b502b4b | 200 | 2026-11-27T00:10:43.181375+00:00 | `d08480c159b4549bdb0a4d1bf97225b85a3321eb9c1fc710180c96a2792dc194` |

| `wrapper-tree` | https://api.github.com/repos/sureshfizzy/libmpv-android/git/trees/920dd825f245d3b86fb964c7115948bf5b502b4b?recursive=1 | 200 | 2026-11-27T00:10:43.013306+00:00 | `775c53dfca26194d5b1212fa006555d6e7cf48ec50b0fc40aa32a041b071e031` |

| `wrapper-tags` | https://api.github.com/repos/sureshfizzy/libmpv-android/tags?per_page=100 | 200 | 2026-11-27T00:10:43.082853+00:00 | `437d32d9cf3e1ce769c788cade610f7f5d94b275c1045acf32a1281631d382e8` |

| `wrapper-releases` | https://api.github.com/repos/sureshfizzy/libmpv-android/releases?per_page=100 | 200 | 2026-11-27T00:10:42.852180+00:00 | `4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945` |

| `wrapper-v1.0.4-ref` | https://api.github.com/repos/sureshfizzy/libmpv-android/git/ref/tags/v1.0.4 | 404 | 2026-11-27T00:10:42.906439+00:00 | `e2e3c64edab172132f77ce89a0e8c190db1cd30936a096344f45893bd5b75f8a` |

| `wrapper-1.0.4-ref` | https://api.github.com/repos/sureshfizzy/libmpv-android/git/ref/tags/1.0.4 | 404 | 2026-11-27T00:10:43.564032+00:00 | `e2e3c64edab172132f77ce89a0e8c190db1cd30936a096344f45893bd5b75f8a` |

| `wrapper-release-v1.0.4` | https://api.github.com/repos/sureshfizzy/libmpv-android/releases/tags/v1.0.4 | 404 | 2026-11-27T00:10:43.609042+00:00 | `010563d2cfb8f1e87f700936ce59ee3a33364da46e3db41b0049227918ece74d` |

| `maven-directory` | https://repo.maven.apache.org/maven2/org/jellycine/mpv/libmpv/1.0.4/ | 200 | 2026-11-27T00:10:43.965761+00:00 | `2baafb22dd2fc8b5a6a1ce4cc6f8b91e602681999828c86b8455eb2ba903e2be` |

| `maven-pom` | https://repo.maven.apache.org/maven2/org/jellycine/mpv/libmpv/1.0.4/libmpv-1.0.4.pom | 200 | 2026-11-27T00:10:44.015837+00:00 | `80462a87fc1182902c6e70dba6ca3486d58d5fb3b357db92d687e85b14e82a7a` |

| `maven-module` | https://repo.maven.apache.org/maven2/org/jellycine/mpv/libmpv/1.0.4/libmpv-1.0.4.module | 200 | 2026-11-27T00:10:45.901774+00:00 | `ec4afce87d18475bedc4539227a5505af398f156e10760cb343706112861c663` |

| `maven-sources` | https://repo.maven.apache.org/maven2/org/jellycine/mpv/libmpv/1.0.4/libmpv-1.0.4-sources.jar | 200 | 2026-11-27T00:10:44.227882+00:00 | `c6deada2fac53b8ea6523dbda77597b128006674616f140f04df23264c6d1aa3` |

| `maven-aar-sha256` | https://repo.maven.apache.org/maven2/org/jellycine/mpv/libmpv/1.0.4/libmpv-1.0.4.aar.sha256 | 404 | 2026-11-27T00:10:44.304650+00:00 | `e791ccfcfee9c0d299d07474d9bfcbfcbebf1181323be601220c8a823062ab99` |

| `wrapper-build-gradle` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/libmpv/build.gradle.kts | 200 | 2026-11-27T00:10:44.168510+00:00 | `fcb0427999f84abcc8b67913209b9d7576662ec250b78d782a44bc74699b7905` |

| `wrapper-ffmpeg` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts/scripts/ffmpeg.sh | 200 | 2026-11-27T00:10:44.427344+00:00 | `277d6da3e8b0714e0e574b8beb3906eb31af6b12e5e76193b2190ba6a85d9107` |

| `wrapper-source-archive` | https://codeload.github.com/sureshfizzy/libmpv-android/tar.gz/920dd825f245d3b86fb964c7115948bf5b502b4b | 200 | 2026-11-27T00:11:38.634995+00:00 | `bfd920663e6d2006c54a413b6ef62ae4b012dfda03357133161efe8342000bc8` |

| `wrapper-history` | https://api.github.com/repos/sureshfizzy/libmpv-android/commits?per_page=100 | 200 | 2026-11-27T00:11:39.357080+00:00 | `341d27dc29149a3b76462664134dae3d98ad3985ce4e270ae1b01c6a2d44c07a` |

| `wrapper-actions-runs` | https://api.github.com/repos/sureshfizzy/libmpv-android/actions/runs?per_page=100 | 200 | 2026-11-27T00:11:38.983764+00:00 | `d8362a5c5ee0f038e62f0295406e77a8f762a23e5adf8b7918ac0111c7564292` |

| `wrapper-attestations` | https://api.github.com/repos/sureshfizzy/libmpv-android/attestations/sha256:22c7e16003f59a03fd430063eb78036eb8cfb1844f9eb900387ee72c5fe7ce76 | 404 | 2026-11-27T00:11:38.441978+00:00 | `8ecae33333826db91507fbc9512eabf6e8e7c9be3c43f6681ca70bff4c028a6d` |

| `ffmpeg-tag` | https://api.github.com/repos/FFmpeg/FFmpeg/git/ref/tags/n9.0.1 | 200 | 2026-11-27T00:11:38.396776+00:00 | `b98da03d0015482b70e89eb0b9ffe10cda8cd35e9eed5dfc64eb803dbc609576` |

| `ffmpeg-tag-object` | https://api.github.com/repos/FFmpeg/FFmpeg/git/tags/501bb49457b9dfb25d6a208832e0a6e6cd53108d | 200 | 2026-11-27T00:11:38.301627+00:00 | `0e299c2d40ce7dff668787c649645a4b9e4660402e53a2adf6a605882ff341eb` |

| `mpv-tag` | https://api.github.com/repos/mpv-player/mpv/git/ref/tags/v0.41.0 | 200 | 2026-11-27T00:11:38.923555+00:00 | `c1794509aa9b8cd6150acab0e4446929024c93dab885fd92fc10eefd7764bc4f` |

| `mpv-tag-object` | https://api.github.com/repos/mpv-player/mpv/git/tags/2c219aa822df18a1b7fd9abe3e151cd93ad67307 | 200 | 2026-11-27T00:11:39.098058+00:00 | `a80e2fc8b74ca5864617b5a73f33971c640e31d99de7fc0678c1e69f56449ed6` |

| `ffmpeg-source-archive` | https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa | 200 | 2026-11-27T00:13:04.314230+00:00 | `fb1931fd4eb29297ee1c1017a24f800c4d8fbea35b4f2aaeb28308a48a9149b4` |

| `mpv-source-archive` | https://codeload.github.com/mpv-player/mpv/tar.gz/41f6a645068483470267271e1d09966ca3b9f413 | 200 | 2026-11-27T00:11:40.600067+00:00 | `068960e89211f2adc80af03f637fa393fb5e407d3dd23592c8fe8c5490bb7ae4` |

| `wrapper-depinfo` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts/include/depinfo.sh | 200 | 2026-11-27T00:14:38.312994+00:00 | `45d848fc5238142fe083972684bb566479784f9d264a73ed16e7477334b2f75e` |

| `wrapper-download-deps` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts/include/download-deps.sh | 200 | 2026-11-27T00:14:37.716947+00:00 | `489bba5a0c07aeb4409df770097452c693e3b2c6782f2115ddeb0d27fc8d7167` |

| `wrapper-download-sdk` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts/include/download-sdk.sh | 200 | 2026-11-27T00:14:37.663180+00:00 | `e2afeda8eac32bd2f9b98d48e632ac8ed4f0869d5ba8b73bb16786969c6ddee4` |

| `wrapper-build` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts/build.sh | 200 | 2026-11-27T00:14:37.312957+00:00 | `9e4e39f856fb1524bc839fb703797d578032c306158fcede8d448d50ad261d32` |

| `wrapper-path` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts/include/path.sh | 200 | 2026-11-27T00:14:37.361387+00:00 | `629ab6718368fa1dcceaf16f2500a1283503fce84439bad1addeaa5275f1c0ce` |

| `wrapper-mpv` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts/scripts/mpv.sh | 200 | 2026-11-27T00:14:37.361634+00:00 | `980be26d12b01852faedbe6746697bd923c7bc1b417df0da42fa50a7336d769e` |

| `wrapper-libplacebo` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts/scripts/libplacebo.sh | 200 | 2026-11-27T00:14:37.804043+00:00 | `7810b77b757dcfb10a0829d3beeee51f808bb6ebd7d05b3f0595cf979a2daaa2` |

| `wrapper-shaderc` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts/scripts/shaderc.sh | 200 | 2026-11-27T00:14:37.980507+00:00 | `ea45a6b2811cf7ce2d0a14f8caa20c75722e4f9e34fd8fda7add23de8232d009` |

| `wrapper-ci-build` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/.github/workflows/build.yaml | 200 | 2026-11-27T00:14:37.881839+00:00 | `b38a13109cecabb4797185208147a278410ccbe1e97e23006fb88db03ed9c6cd` |

| `wrapper-ci-publish` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/.github/workflows/publish.yaml | 200 | 2026-11-27T00:14:38.146157+00:00 | `ce9bb2324604a6d40470ad060005233c88af80c195fe0bd5803d8c985ae469f9` |

| `wrapper-patch` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts/patch.sh | 200 | 2026-11-27T00:14:38.202818+00:00 | `e9868bf4f0d5a2b38d16a3180f5bf4c2ec3dea724e9ec813a856fee495624f06` |

| `wrapper-patch-mpv-1` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts/patches/mpv/0001-android-vulkan-enable-10bit-hdr-swapchain.patch | 200 | 2026-11-27T00:14:38.307787+00:00 | `61852106916d1e6f644eac305e248150d1675937721465a49975f115e4eeea10` |

| `wrapper-patch-mpv-2` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/buildscripts/patches/mpv/0002-android-egl-enable-10bit-hdr-surface.patch | 200 | 2026-11-27T00:14:38.409889+00:00 | `524953102374cc65eb9bf150749068b6d2991acd7ee930b56c78f5df38e23dac` |

| `wrapper-gradle-wrapper` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/gradle/wrapper/gradle-wrapper.properties | 200 | 2026-11-27T00:14:38.483144+00:00 | `d01b41913834fb3cb14e71f69a08898e6a47e663b08f5c9b8c7e0772c38889d2` |

| `wrapper-gradle-libs` | https://raw.githubusercontent.com/sureshfizzy/libmpv-android/920dd825f245d3b86fb964c7115948bf5b502b4b/gradle/libs.versions.toml | 200 | 2026-11-27T00:14:38.811978+00:00 | `5da68cfa64b38c5afc8fb1e6a37aaf2f7fbc4d23b649405a1eeeb871ed481125` |

| `wrapper-run-jobs` | https://api.github.com/repos/sureshfizzy/libmpv-android/actions/runs/33256129384/jobs | 200 | 2026-11-27T00:14:39.159364+00:00 | `6f8be1a12c6c835baea3120a1f2cb2dc2047f3d2606e418487317bac6d227be4` |

| `wrapper-run-artifacts` | https://api.github.com/repos/sureshfizzy/libmpv-android/actions/runs/33256129384/artifacts | 200 | 2026-11-27T00:14:38.989396+00:00 | `244563f989470892c537c9a4622920af0d228ee4528f7d00c64a06d6cd95856c` |

| `wrapper-repo-artifacts` | https://api.github.com/repos/sureshfizzy/libmpv-android/actions/artifacts?per_page=100 | 200 | 2026-11-27T00:14:39.054764+00:00 | `244563f989470892c537c9a4622920af0d228ee4528f7d00c64a06d6cd95856c` |

| `wrapper-v1.0.4-branches` | https://api.github.com/repos/sureshfizzy/libmpv-android/branches?per_page=100 | 200 | 2026-11-27T00:14:39.136681+00:00 | `7fda9a9778a49a40bd0836fbda82752272f4b8e53e644188e7be43db8270414d` |

| `dav1d-candidate-meson` | https://code.videolan.org/videolan/dav1d/-/raw/1.5.4/meson.build | 200 | 2026-11-27T00:15:57.908379+00:00 | `9f0eea8f574c1aa456445be1527d610b3edf7b4a65836947264bc292e52cfd1f` |

| `libplacebo-candidate-meson` | https://code.videolan.org/videolan/libplacebo/-/raw/v7.360.1/meson.build | 200 | 2026-11-27T00:15:58.076488+00:00 | `55d5c686404f976ffd49c000f76c1ace926a0aa0a5ac0c5dfa4ca4e8067b33d8` |

| `libplacebo-candidate-submodules` | https://code.videolan.org/videolan/libplacebo/-/raw/v7.360.1/.gitmodules | 200 | 2026-11-27T00:15:57.688686+00:00 | `f11ce63173c8901db8518e73ea2515ddb1002a00397de98f981eaa0adf3b8f12` |

| `mbedtls-candidate-ref` | https://api.github.com/repos/Mbed-TLS/mbedtls/git/ref/tags/v3.6.7 | 200 | 2026-11-27T00:15:56.232839+00:00 | `09e22237a0316be26c274e07ef6724b28ae9bbdacd6f7976c5f8f9ed09b62b62` |

| `mbedtls-candidate-submodules` | https://raw.githubusercontent.com/Mbed-TLS/mbedtls/v3.6.7/.gitmodules | 200 | 2026-11-27T00:15:56.512323+00:00 | `56d749500767c558d812c09d22cd723ae779c798a5a778fa8d794a64f229b566` |

| `wrapper-job-log` | https://api.github.com/repos/sureshfizzy/libmpv-android/actions/jobs/99110037834/logs | 403 | 2026-11-27T00:15:56.236862+00:00 | `eb33485d28599807605118a29221fdfd58c48e041a1ad2aba74ae9c70646e289` |

| `wrapper-run-log` | https://api.github.com/repos/sureshfizzy/libmpv-android/actions/runs/33256129384/logs | 403 | 2026-11-27T00:15:56.957243+00:00 | `96b7561a85d7bfec4c176a9080c43c901cf2c1ba2c5d7de6cb07fceb2815cd91` |


### 7. 本单验证与边界

- 51 个 HTTP 原始响应正文重新计算 SHA-256：全部与 manifest 一致；17 份单独抓取的 wrapper 原文件与同 commit 归档内文件哈希一致。

- Maven module 的 AAR SHA-256 与本机缓存实测一致；4 ABI 的 avcodec/avformat 共 8 份 configure 参数静态比对一致。没有执行 native API 或 native 重建。

- 两份公开 mpv patch：只读 dry-run 均退出 2，Git 只读语法解析均退出 128；没有改补丁、没有把假设修复计作原产物证据。

- 原文以字节前缀保留；原 JSON 根字段语义原样保留，仅追加 `source_investigation_T15B`。产品/依赖/版本的起止文件哈希由 `.temp/T15B-audit.py verify` 核对，结果见 `scope-verification.json`。没有执行任何 commit/push/发布或对外联系。
