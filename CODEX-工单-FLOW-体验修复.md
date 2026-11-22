# CODEX 工单 · FLOW 批次（实测体验 7 项 + 遗留 2 项）

版本基线：v1.24.6 (versionCode 150)，提交 `2eda1f9` + 工作区未提交的 S5–S8 改动。
目标版本：v1.24.7 (151)。
模型：sharedchat / gpt-5.6-sol / reasoning=xhigh（max SKU 服务端未配价，503）。
硬性约束（全批次通用）：

- 只改工单列出的文件；不得重构无关模块，不得改设置项语义，不得改产品取舍（默认 Media3、mpv 仅显式选择）。
- 构建环境（本仓库没有 gradlew，Java 也不在 PATH）：
  - `JAVA_HOME=C:/tools/jdk/jdk-17.0.21.0.2+1`
  - `GRADLE=C:/Users/Administrator/.gradle/wrapper/dists/gradle-8.12-bin/cetblhg4pflnnks72fxwobvgv/gradle-8.12/bin/gradle.bat`
  - 命令：`$env:JAVA_HOME='C:/tools/jdk/jdk-17.0.21.0.2+1'; & 'C:/Users/Administrator/.gradle/wrapper/dists/gradle-8.12-bin/cetblhg4pflnnks72fxwobvgv/gradle-8.12/bin/gradle.bat' compileDebugKotlin compileDebugUnitTestKotlin --offline --console=plain`
  - apply_patch 若报「requires a UTF-8 PATCH argument」，用 node spawnSync 把补丁作为参数传给 codex.exe，别用 PowerShell 管道。
- 每个批次结束必须编译通过，失败即修到通过。
- 每批次交付说明 ≤ 12 行：改了哪些文件、每处改了什么、编译结果、刻意没改什么。
- 禁止内部再开子代理；禁止 `git commit` / `git push`。
- 用户是电视盒子（1080p/4K 输出）+ 遥控器，所有交互必须遥控器可完成。

---

## FLOW-A（P0）mpv 请求头彻底失效

### 现象
所有走 mpv 的片源都提示「mpv 缺少 N/N 个请求头，需要 Referer 或鉴权头的片源可能无法播放」，实测所有片都放不了。这是 v1.24.4 的 W2/W3 改动引入的回退。

### 根因（已核实到 mpv 0.41 源码，不要推翻）
`MpvPlayerController.kt:41-50` 用 `setOptionString("http-header-fields-append", "$name: $value")` 逐项追加。

mpv 的 `-append` 后缀只在 **命令行解析**（`m_config_set_option_cli` → `m_config_mogrify_cli_opt`）里被识别；
`mpv_set_option_string` 走的是 `m_config_set_option_node`，它直接 `m_config_get_co(config, name)`，**不剥离后缀**，所以 `http-header-fields-append` 一律返回 `M_OPT_UNKNOWN`（负值）。

→ 每个 header 都被判失败，`accepted=0`，notice 就是「缺少 N/N」。

### 修复要求
文件：`app/src/main/java/com/lumina/tv/player/MpvPlayerController.kt`、`app/src/test/java/com/lumina/tv/player/B17Checks.kt`

1. 删除 `http-header-fields-append` 用法，改为**一次性** `setOptionString("http-header-fields", value)`。
2. `value` 是 mpv string list：元素用 `,` 分隔；元素内的 `,` 必须转义成 `\,`；`\` 自身转义成 `\\`。
   （依据 `m_option.c` 的 `get_nextsep()`：反斜杠后的分隔符不切分，并吃掉反斜杠。）
3. 每个 header 先做值清洗：去掉 `\r`/`\n`，`name` 为空或含 `:` 的丢弃，`value` 为空允许但记日志。
4. 结果对象改为 `MpvHttpHeadersResult(mode, accepted, total, failedNames)`：
   - `accepted` = 1 表示整串设置成功（`setOptionString` 返回 `>= 0`），否则 0；
   - 读回校验：`getPropertyString("options/http-header-fields")` 若可用，用它确认元素数量（不可用就跳过，不要用失败判定）。
5. notice 只在真的设置失败时出现，文案改成「mpv 请求头设置失败：X/Y（缺 Referer 或鉴权头的片源可能无法播放）」，并把 `failedNames`（只名字，不含值）写进 `PageLog`。
6. 新增/加强单测（纯函数，不依赖 mpv）：
   - header 值含逗号 → 输出含 `\,`；
   - header 值含反斜杠 → 输出含 `\\`；
   - 两个 header 拼接后按 mpv 规则 split 能还原成 2 个元素；
   - 空 map → `total=0`，不产生 notice。

### 验收
`compileDebugKotlin` + `compileDebugUnitTestKotlin` 通过，B17Checks 全绿；给出一条「2 个 header、含逗号值」的样例输出。

---

## FLOW-B（P0）详情页预览漏音 + 播放中音量不可调 + 控制栏乱入

### B1 详情页一进去就有声音
文件：`app/src/main/java/com/lumina/tv/ui/components/DetailPreviewPlayer.kt`、`DetailVideoBackground.kt`

现状：`DetailPreviewPlayer.kt:66` 只做了 `current.volume = 0f`。
但本片是直通音轨（DTS/AC3/TrueHD）时，ExoPlayer 的 `volume` 对 passthrough 输出**无效**（音量交给 sink），所以预览会以满音量出声。

修复：
1. 构建 player 后立刻禁用音频轨：
   `current.trackSelectionParameters = current.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()`
   并在 `onPlaybackStateChanged(STATE_READY)`、`onTracksChanged` 里重申一次（防止自适应重新选轨）。
2. `setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ false)`，预览不抢音频焦点。
3. 保留 `volume = 0f` 作为兜底。
4. 若 `C` 未导入，补 `androidx.media3.common.C`。

### B2 播放时音量不可调
文件：`app/src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt`、`player/MpvPlayerController.kt`

现状：直通时系统音量对 bitstream 无效；app 没有自己的音量层，遥控器音量键表现为「没反应」。

修复：
1. 播放器内拦截 `Key.VolumeUp` / `Key.VolumeDown` / `Key.VolumeMute`（`onPreviewKeyEvent`，`repeatCount==0` 才处理），调整 **app 内音量**：
   - mpv：`setPropertyDouble("volume", v)`（0–100）；
   - media3：`exo.volume = v/100f`。
2. 屏幕右下显示音量 OSD（细长条 + 百分比 + 图标），1.5 秒后淡出；样式沿用现有 Glass 风格。
3. 初始值 100，写入 `Prefs`（新增 `playerVolume`，默认 100），下次进入沿用。
4. 若当前是 passthrough 且用户连续两次调整仍无效果，弹一次提示：「当前为音频直通，音量由电视/功放控制；按 OK 切换本地软解」，OK 触发一次重新加载并 pin `soft`。只提示一次/每次播放。
5. 不要吞掉音量键导致系统完全无响应：处理完返回 true（已由 app 接管），并在 OSD 上明确显示数值。

### B3 控制栏/暂停 UI 自己冒出来
文件：`app/src/main/java/com/lumina/tv/ui/screens/PlayerScreen.kt`、`player/HiddenPlaybackInput.kt`

现状：控制栏隐藏时，短按 OK 会走 `HiddenPlaybackAction.TogglePlay`（`HiddenPlaybackInput.kt:49-50`、`68-72`）。遥控器/系统/CEC 产生的**残留 KeyUp 或重复事件**会被当成一次 tap，于是暂停图标 + 进度条自己出现。

修复：
1. `reduceHiddenPlaybackInput` 增加约束：`Up` 只有在同一按键的 `Down` 已被本层接收（`state.key == event.key`）时才产生 Tap —— 现状已满足，补一个「`Down` 必须 `repeat==0`」的显式判定。
2. 新增进入播放器后的 **600ms 输入静默期**（`inputArmedAtMs`），期内所有 hidden input 事件只吞掉不产生动作。
3. 同一次物理按键的 `Up` 只能产生一次动作：加 `lastUpKey/lastUpAtMs`，400ms 内重复 Up 直接忽略。
4. 暂停指示器 `pauseIndicatorUntil` 只在真正执行了 `TogglePlay` 之后设置，并补 `PageLog.add("togglePlay src=hidden/ok t=...")`；`ctlShow` 打点补上 `elapsedSinceShow`。
5. 若 `Key.MediaPlayPause` 在 800ms 内重复到达，只处理第一次。

### 验收
`compileDebugKotlin` + 单测（为 reducer 新增：重复 Up 不触发、静默期内不触发、正常 Down+Up 触发一次）通过。

---

## FLOW-C（P1）首页/海报视觉：去全屏光圈、改为小光源、收藏再小 20%

文件：`app/src/main/java/com/lumina/tv/ui/screens/HomeScreen.kt`、`ui/components/Components.kt`、`ui/screens/FavoritesScreen.kt`

### C1 删掉电视四周的一圈光圈
`HomeScreen.kt:2437-2439`：
```kotlin
if (focusedHeroAction == HeroActionFocus.ARTWORK) {
    Box(Modifier.matchParentSize().padding(4.dp).border(1.5.dp, Accent.copy(alpha = 0.76f), RoundedCornerShape(8.dp)))
}
```
这是整屏描边，就是用户说的「电视周围一圈光圈，选中预览图就出现」。**整段删除**（不要替换成别的整屏边框）。

### C2 选中态改成「一小圈光源」而不是白框
`Components.kt`：
- `FocusSpec` 里 `ringColor = Color(0xE6FFFFFF)`（90% 白）太扎眼，海报类改为 `Color(0x40FFFFFF)`，宽度 1.dp。
- 新增 `Modifier.posterFocusGlow(focused: Boolean, shape: Shape, accent: Color)`：
  - 内环：1.dp，`Color.White.copy(alpha = 0.28f)`；
  - 外圈：`drawBehind` 里画 3 层同形状圆角矩形，逐层外扩 3/6/9dp，alpha 0.10/0.06/0.03，颜色 `Accent`；
  - **禁止**再用 `shadowElevation` 做选中阴影（电视 GPU 上极贵，且是灰黑影不是光）。
- `PosterCard`（Components.kt:364-390）与 `FocusSurface` 都改用它；保留 `animateFloatAsState` 缩放。
- 缩放：海报 1.06，spring(dampingRatio = 0.86f, stiffness = 380f)；非选中 alpha 0.92。

### C3 收藏页海报再小 20%
`FavoritesScreen.kt:120-127`：列数从 5/7/9 改为 **6/9/11**（同样的 40dp 边距 + 20dp 间距），等效单卡宽度约 -20%。不要改 `PosterCard` 的内部比例。

### C4 首页横向海报行同步收敛
`HomeScreen.kt:1154` 与 `1205` 的 `PosterCard` 复用 C2 的观感；横向行卡片宽度不要动（避免破坏 hero 对齐）。

### 验收
`compileDebugKotlin` 通过；全仓不再有海报选中态的 `1.5.dp + 0xE6FFFFFF` 组合；给出改动前后各 3 行关键代码。

---

## FLOW-D（P1）详情页版本选择：大小不一、旁边有缝、不推开邻居

文件：`app/src/main/java/com/lumina/tv/ui/screens/DetailScreen.kt`

### 根因
`DetailScreen.kt:1159-1206`：外层 `Box(Modifier.width(170.dp))` 固定 170dp，内部胶囊 `animateDpAsState(96.dp → 170.dp)`。
- 未选中时 170dp 容器里只放 96dp 胶囊 → 右侧 74dp 空隙；
- 选中后仍固定 170dp，长版本名被 ellipsis，短名留白 → 「每个大小都不一样」；
- 因为外层宽度不变，收藏等按钮不会被推开。

### 修复要求
1. 去掉外层固定 `Box(Modifier.width(170.dp))`，改为 `Box(Modifier)`（wrap content）。
2. 胶囊宽度改为「随内容」：
   - 未选中：46dp 圆形，只显示 Layers 图标；
   - 选中：图标 + 文本，宽度由文本实测决定，最大 280dp，超出省略；
   - 用 `SubcomposeLayout` 或 `Modifier.animateContentSize()` + `IntrinsicSize.Max` 实现，**不要**用固定 dp 猜；
   - 过渡 240ms，`FastOutSlowInEasing`。
3. 选中后同排的「收藏 / 更多」等按钮必须自然右移（Row 正常测量即可，禁止用 offset 假装）。
4. 与相邻按钮间距统一 12dp，不允许出现额外 Spacer 造成的空隙。
5. 不改变 `showSourceMenu` 的行为；版本弹层 `PickerDialog` 不动。

### 验收
`compileDebugKotlin` 通过；贴出「1 个版本 / 3 个长短不一版本」两种情况的宽度计算路径说明（3 行内）。

---

## FLOW-E（P0）媒体库遥控器 2–3 秒延迟

文件：`app/src/main/java/com/lumina/tv/ui/screens/HomeScreen.kt`、`ui/components/Components.kt`

### 已定位的热点（按优先级改）
1. **每次焦点变化都触发一次 focus 请求**
   `HomeScreen.kt:1149-1158` 与 `1200-1209`：
   ```kotlin
   val shouldRequestInitialFocus = remember(media.id) {
       derivedStateOf { focusedMediaId == media.id || (initialFocusPending && ...) }
   }
   ```
   `focusedMediaId` 在**每一次**焦点移动时变化 → 每个可见卡片的 `derivedStateOf` 全部重算（O(N)），新选中项的 `requestInitialFocus` 变 true → `PosterCard` 里 `LaunchedEffect` 再 `delay(120)` + `requestFocus()`。这形成「焦点→状态→再请求焦点」的闭环。
   改法：新增 `restoreTargetId: String?`（只在从详情页返回 / 首次进入 / 切库时设置），`requestInitialFocus` 只依赖它，落地后清空；`focusedMediaId` 不再参与该判断。
2. **`RowSection` 传 `focusItemId = focusedMediaId`**（`HomeScreen.kt:960/970/981`）→ 每次焦点移动让多条横向行重组。
   改法：改为传 `restoreTargetId`（同上，一次性），或传 `() -> Boolean` 的稳定 lambda。
3. **选中阴影**：`graphicsLayer.shadowElevation` 在处理焦点时逐帧重绘阴影（电视 GPU 极贵）。按 FLOW-C 的 glow 方案替换，海报不再用 shadowElevation。
4. **诊断打点**（必须加，供实测验收）：
   - 在根 `onPreviewKeyEvent` 记录 `PageLog.add("gridKey key=$key at=${SystemClock.elapsedRealtime()}")`；
   - 在新项 `onFocusChanged(isFocused=true)` 记录 `PageLog.add("gridFocus id=$id costMs=${now - lastKeyAt}")`。
   验收目标：`costMs` 中位数 < 120ms（用户实测导出诊断可读）。

### 边界
- 不改变网格列数、间距、滚动位置记忆逻辑。
- 不改 `LibraryCache` 的 API。
- 焦点恢复必须在返回时仍然回到原来那部片。

### 验收
`compileDebugKotlin` 通过；说明改完后一次焦点移动会触发多少次重组（给出估算依据）。

---

## FLOW-F（P2）遗留审查缺口（上一轮审计未做）

1. `Media3` 播放器构建处补 `setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)`（对照 jellyfin-androidtv）。位置：`PlayerScreen.kt` 里 `ExoPlayer.Builder(...)` 的 `trackSelector` 构建处；若用 `DefaultTrackSelector`，在 builder 上设置。
2. mpv 侧：`detachSurface` / 重绑前先 `setPropertyString("vo", "null")`，重绑后恢复 `vo=gpu`（对照 mpv-android 的 force-window 时序），只在 `MpvPlayerController` 内部做，加 `PageLog` 打点。
3. 两项都要有失败兜底：任何 `runCatching` 失败只记日志，不影响播放主流程。

验收：`compileDebugKotlin` 通过，给出两处 diff 摘要。

---

## 交付顺序

1. FLOW-A + FLOW-B 并行（互不共享文件）
2. FLOW-C + FLOW-D 并行（HomeScreen/Components/Favorites 与 DetailScreen 不重叠）
3. FLOW-E（独占 HomeScreen + Components，必须在 C 之后）
4. FLOW-F 最后并入
5. 主代理统一 `verifyOffline` + `assembleRelease` + 版本号 151
