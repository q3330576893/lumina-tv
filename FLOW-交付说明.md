# FLOW 交付说明 · v1.24.7 (151)

## 修复内容

| # | 问题 | 根因 | 改动 |
|---|---|---|---|
| 1 | mpv 全部片源提示缺请求头、放不了 | `http-header-fields-append` 用了命令行专属的 `-append` 后缀，`mpv_set_option_string` 不识别 → 每次设置都返回 M_OPT_UNKNOWN | 改为一次性 `http-header-fields` + mpv string-list 转义（`,`→`\,`，`\`→`\\`），带读回校验与失败头名日志 |
| 2 | 详情页一进去就有声音、音量调不动 | 预览 ExoPlayer 只设 `volume=0`，直通音轨（DTS/AC3）下 volume 无效 | 预览禁用音频轨（`TRACK_TYPE_AUDIO`）+ 不抢音频焦点，`onTracksChanged`/`READY` 重申 |
| 3 | 播放中音量不可调 | 直通 bitstream 不受系统音量控制，app 无自建音量层 | 播放器接管音量键：mpv `volume` / ExoPlayer `volume` + 右下角 OSD + 持久化；直通无效时提示一次并可切软解 |
| 4 | 播放中突然冒出暂停 UI / 进度条 | 隐藏输入层把残留 KeyUp 当短按 OK，触发 TogglePlay | Down 必须 repeat=0 且被本层接收；进入播放器 600ms 静默期；同键 400ms 去重；MediaPlayPause 800ms 去重；补 togglePlay/ctlShow 打点 |
| 5 | 电视四周一圈光圈 | hero 选中态画了整屏 1.5dp Accent 描边 | 整段删除 |
| 6 | 海报选中框太白、缩放不精致 | 90% 白 1.5dp 边框 + `shadowElevation` 灰黑影 | 1dp 28% 白内环 + 3 层 Accent 外扩光晕；缩放 1.06 spring(0.86/380)，非选中 alpha 0.92；去掉选中阴影 |
| 7 | 收藏页海报太大 | 5/7/9 列 | 6/9/11 列（单卡约 -20%） |
| 8 | 详情页版本胶囊大小不一、旁边有缝、不推开邻居 | 外层固定 `width(170.dp)`，内层 96→170 动画 | 改为 wrap content：未选中 46dp 圆形，选中按文本测宽（max 300dp），`animateContentSize`，同行按钮自然右移 |
| 9 | 媒体库遥控器延迟 2–3 秒 | 每次焦点变化都改 `focusedMediaId`，触发全网格 derivedStateOf 重算 + 再请求焦点 | 引入一次性 `restoreTargetId`，只在进入/返回/切库时生效；行与网格不再按每次焦点重组；加 `gridKey`/`gridFocus costMs` 打点 |
| 10 | 遗留审查项 | — | media3 开启 `setAllowInvalidateSelectionsOnRendererCapabilitiesChange`；mpv detach 前 `vo=null`、attach 后 `vo=gpu` |

## 验证

- `compileDebugKotlin` / `compileDebugUnitTestKotlin` 通过
- `testDebugUnitTest` 通过（含重写的 B5/B15/B17 请求头断言）
- `verifyOffline` 通过
- `assembleRelease` 通过：`app/build/outputs/apk/release/app-release.apk`（29 MB）

## 待实测

- 媒体库焦点延迟：抓诊断看 `gridFocus costMs`，目标中位数 < 120ms
- 奥德赛：详情页应完全无声；播放中按音量键应出现 OSD 并生效
- 网盘直链片源：mpv 不再提示缺请求头
- 详情页版本：长/短版本名都不出现空隙，收藏按钮跟随右移
