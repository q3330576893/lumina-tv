# JC 交付说明 · v1.24.8 (152)

## 1. EAC3 等「本机无解码器」音轨不再直接失败

实测现场：Redmi 23013RK75C（Android 14，无 EAC3 硬解），Media3 报 `audio/eac3 supported=false`，
app 提示「该片音轨（EAC3）本机无法硬解，自动恢复次数已用完…」，但 mpv 自带 ffmpeg，软解 EAC3 没问题，却一次都没试。

三处断点，全部修掉：
1. `requestAudioRecovery` 里预算耗尽先于 mpv 尝试就终止 →
   改为 `(exhausted && !tryMpv) || ...`，只要还能试 mpv 就不算终止。
2. `shouldAutomaticallyTryMpvAudioFallback` 要求 `mpvExplicitlyRequested`，默认 auto 永远不成立 →
   新增 `audioUndecodable`，仅当 `audioSupport == UNSUPPORTED`（音轨本身无解码器）时允许自动兜底；
   视频/DV 不自动切 mpv 的规则保持不变。
3. `recoverAutomatically` 内部还有一道 `recoveryBudget.exhausted` / `tryAcquire()` 闸门 →
   新增 `bypassRecoveryBudget`，只在 mpv 音频兜底时绕过，其它恢复路径仍受 3 次预算限制。

效果：再遇到 EAC3/AC3/DTS 这类 Media3 解不了的音轨，会直接切 mpv 软解一次，而不是弹「次数已用完」。

## 2. 季目录不再「退出播放再进详情就没了」

- 缓存命中分支原来只刷 item 和 sources，季目录直接用缓存，缓存为空就永远「暂无季目录」→
  现在 Series 命中缓存后会在后台重新拉一次季目录，成功且非空则更新并写回缓存；失败/空则保留缓存值。
- Series 拿到空季列表原来被当成合法 EMPTY（无重试入口）→ 现在转为可重试的失败态（显示「季目录读取失败，请重试」）。
- 空季不再写进详情缓存，避免把空结果固化。

## 3. 媒体库分类海报间距收紧

两处网格（分类网格 / 普通内容网格）：
- 水平间距 20dp → 12dp，垂直间距 32dp → 18dp
- 水平内边距 40dp → 32dp
- 卡片宽度公式同步改为 `32.dp * 2` 与 `12.dp * (列数-1)`，避免卡片变窄反而显得更空

收藏页、列数、横向推荐行未动。

## 验证
- `compileDebugKotlin` / `compileDebugUnitTestKotlin` / `testDebugUnitTest` 通过
- `assembleRelease` 通过

## 待实测
- EAC3 片源：应看到短暂「已切换软解内核」后有画面有声音，不再停在错误页
- 剧集：播放一集退出 → 回详情，季目录应仍在；断网进详情应显示「读取失败，请重试」而不是「暂无季目录」
- 媒体库分类：海报间距变小、同屏更紧凑
