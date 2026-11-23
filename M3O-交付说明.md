# M3O 交付说明 · v1.24.9 (153)

本轮只做 Media3 主力内核优化，mpv 仍是兜底，未改动其策略。

## 对标来源

| 项目 | 实际采用的机制 |
|---|---|
| Jellyfin AndroidTV `ExoPlayerBackend.kt` | 常量码率 seek、AudioOffloadPreferences、`setPauseAtEndOfMediaItems`、服务端下发缓冲参数 |
| Jellyfin Android `PlayerViewModel.kt` | 同上（手机端） |
| NextPlayer `PlayerService.kt` | Tunneling 开关、`setHandleAudioBecomingNoisy`、audio focus |
| Findroid `PlayerViewModel.kt` | 可配置 seek 步长、pauseAtEnd |
| SmartTube `ExoPlayerInitializer.java` | 按设备内存限制缓冲字节 `deviceRam/18`、BackBuffer、按码率分档 |

## 改动

### 1. 缓冲策略自适应（替代原来只调 15/50/1/2 的做法）
按 **设备总内存** × **片源码率** 选参数：

| 设备 | 码率 | min | max | 起播 | 重缓冲 | 字节上限 | BackBuffer |
|---|---|---|---|---|---|---|---|
| ≤2GB | 任意 | 15s | 30s | 1.0s | 2.0s | 64MB | 10s |
| 2–4GB | 普通 | 30s | 50s | 1.0s | 2.5s | 128MB | 20s |
| 2–4GB | >25Mbps | 30s | 60s | 1.0s | 3.0s | 160MB | 20s |
| >4GB | 普通 | 30s | 60s | 1.0s | 2.5s | 192MB | 30s |
| >4GB | >25Mbps | 50s | 90s | 1.5s | 3.0s | 256MB | 30s |

- 关闭「快速起播」时：起播阈值改 2.5s / 重缓冲 5s，其余不变
- `setPrioritizeTimeOverSizeThresholds(false)`，让字节上限真正生效
- 日志：`m3o buffer tier=… memMb=… bitrateMbps=… min=… max=… bytes=…`

### 2. 常量码率 seek
`DefaultExtractorsFactory.setConstantBitrateSeekingEnabled(true) + AlwaysEnabled(true)`。
对无索引/码率不准的 MKV/直链，拖进度不再死等关键帧。

### 3. 音频与播放器行为
- 直通模式下启用 `AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED`
- `setHandleAudioBecomingNoisy(true)`（拔 HDMI 自动暂停）
- `setSeekBackIncrementMs(10s)` / `setSeekForwardIncrementMs(30s)`
- `setPauseAtEndOfMediaItems(true)`

### 4. 播放质量统计
新增 `PlaybackStatsListener`，每次会话结束输出一行：
`m3o stats startup=… rebuffer=…/…ms dropped=… underrun=… bw=…Mbps`

## 验证
- `compileDebugKotlin` / `compileDebugUnitTestKotlin` / `testDebugUnitTest` 通过
- 未改 HLS 路径、未改 mpv 引擎策略、未改版本号以外的东西

## 待实测
1. 4K 高码率片源播 30 分钟：不 OOM、不重启，看 `m3o buffer tier=` 是否命中 high
2. 网盘直链拖进度：出画时间
3. EAC3 直通片源：看 `audioSink passthrough=true` 与 `m3o stats underrun=0`
4. 诊断里 `m3o stats` 的 startup / rebuffer / dropped 数值，作为下一轮基线
