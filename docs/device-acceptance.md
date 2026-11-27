# V6-T16 Android / TV 设备验收清单

登记日期：2026-11-27（Asia/Shanghai）。**本轮设备结论：未验收。** ADB 返回空设备列表，SDK 下没有 emulator 可执行文件。下列编号项均为 **未验收**；没有执行 `connectedAndroidTest`，没有实机声音、画面、decoder、焦点、性能或发布结果。

**androidTest 编译：BLOCKED。** 两个 AndroidX Test 依赖离线缓存缺失，探测失败后已立即回滚依赖；保留4个用例源码和runner配置，但当前不能编译/运行测试APK，详见第13节。

本表按编号计数，共 **99 项**。音频路由 × 格式的 24 个组合分别编号；同一项的内核、输出模式、设备和故障变体必须分别记结果，不能用一个组合的结果覆盖其他组合。编译、JVM checks、参数读回、采集脚本预检均不等于设备通过。

## 1. 来源与去重规则

| 任务 | 汇总来源（仓库相对路径） | 本文范围 |
| --- | --- | --- |
| T02 | `.temp/V6-T02-VERIFY-CODEX.log` 最终 `device_pending`、`.temp/V6-T02-ORDER.md` | T02-A01–A24、T02-B01–B06；含 DTS-HD、PCM、offload、同 caps 路由变化、duck 和健康恢复 |
| T03 | `.temp/V6-T03-CODEX.log` 最终 `device_pending` 与 `remaining_risks`、`.temp/V6-T03-ORDER.md` | T03-01–06；含 >3 秒、永久未决、cleanup/JNI 失败及真实 decoder 时序 |
| T06 | `.temp/V6-T06-VERIFY-CODEX.log` 最终 `device_pending`、`.temp/V6-T06-ORDER.md` | T06-01–08；A 连播与 B 时间线分别记录 |
| T07 | `.temp/T07-report.json` 的 `device_pending` | T07-01–06 |
| T08 | `.temp/T08-report.json` 的 `device_pending` | T08-01–09 |
| T09 | `.temp/t09-result.json` 的 `device_pending`、`remaining_risks` | T09-01–07；包含 DirectStream/转码与 R6-13 DV 输入取证 |
| T10 | `.temp/t10-result.json`、后续 `.temp/t10-confirm-result.json` | T10-01–08；以确认单 `strict_default` 为准，前一报告相同场景去重，保留同 index 换内容、缺版本、存储失败边界 |
| T11 | `.temp/V6-T11-result.json` 的 `device_pending`、`remaining_risks` | T11-01–06 |
| T12 | `.temp/T12-result.json`、`.temp/T12-device-plan.md`、`.temp/T12-collect-device.ps1`、`.temp/T12-frame-analysis.sql` | T12-01–11；保留采集方法、有效性与候选变更门槛 |
| T13 | `.temp/T13-result.json`、后续 `.temp/V6-T13-FIX-result.json`、`.github/workflows/build-and-release.yml` | T13-01–03；修复单已收口 unsigned 本机构建，不继续沿用旧报告中的已解决编译阻塞；真实 CI/签名仍待验 |
| T14 | `.temp/V6-T14-CODEX.log` 最终报告、T15 对其阻塞的继承 | T14-01–02；Linux AAPT2 输入和严格校验 |
| T15 | `.temp/t15-evidence/result.json`、`docs/native-sources.md`、`docs/native-sources.json` | T15-01–03；来源闭环、交付许可、TV 弹窗与链接 |

`.temp/` 属于本机证据，不保证新克隆包含这些文件。交接时需保留原始报告与 T12 脚本/SQL；缺失时记录 BLOCKED，不能只凭本表宣称来源或采集闭环。

## 2. 执行协议、前置物料与记录方式

1. 使用获准的测试电视/手机和测试账号。记录设备型号、Android/API、ABI、SoC、RAM、低 RAM 标志、显示密度/字号/分辨率/刷新率、热状态、音频设备及固件、APK 路径/SHA-256、版本、构建类型、内核、输出模式、服务器版本和脱敏夹具编号。版本相同不代表 APK 相同。冷启动需确认旧 PID 消失，再启动；已有会话应进入首页，无会话应进入登录页，记录画面及崩溃信息。
2. 媒体集需包含六种音频格式的已知内容/声道、PCM16/24/32/FLOAT、多音轨/字幕、跨季连续剧、末集、不可播下一集、原点 offset=0/>0、未知 position/duration、真实 BIF/损坏 BIF、裁切视频、匹配的 DV 输入。以媒体分析和服务器日志确认夹具，不按扩展名猜格式。
3. 测试服务器/代理应能延迟/恢复请求、返回 401/403/5xx、模拟断网和签名过期、保留重定向/HLS 子请求、记录 Progress/Stop。使用测试凭据，证据中隐去 token、Cookie、签名 query 和个人内容。三台服务器命名 A/B/C；准备历史首/中/末项与前四页无可见结果、第五页有结果的搜索数据。
4. Media3/mpv 均覆盖；音频按界面实际提供的自动/直通/解码等模式逐项记录，保留原文。不把 offload 当直通，不把 PCM 当 CPU 软解的证明。无相应硬件/格式/权限/故障夹具就记 BLOCKED，不默认 N/A 或通过。
5. 用带时间戳录像/截图、脱敏服务端事件、应用诊断、MediaCodec/Perfetto/厂商允许的输出证据建立同一时间线。decoder 要观察活动资源的创建/释放，不以 Java player 对象数量代替；系统不暴露数据时记证据不足。按 Back 后返回仍需测真实播放器，不能用 Activity 销毁冒充 decoder 释放。
6. 结果只使用：**未验收**（未执行）、**BLOCKED**（缺设备/依赖/权限/物料）、**FAIL**（可观察结果不符）、**inconclusive**（采集不足）、**PASS**（本编号全部必需变体均有证据）。`captured_unreviewed` 仅表示已采集，不能当 PASS。
7. JNI destroy、cleanup、真实存储失败、ANR/native crash 等故障仅在隔离测试设备上使用获准夹具/调试方法。若现有产物无法注入，不改 `src/main`，记录 BLOCKED 和所缺夹具，交回对应任务。发现产品缺陷也只登记回原单修复，本单不改业务代码。

每个编号及变体复制一份以下记录；本轮尚无任何已执行记录：

```json
{
  "id": "T02-A01",
  "task": "T02",
  "variant": {"device": null, "engine": null, "output_mode": null},
  "status": "未验收",
  "operator": null,
  "executed_at": null,
  "apk_sha256": null,
  "fixture": null,
  "observed": null,
  "evidence": [],
  "blocker_or_defect": null,
  "handoff_task": "T02"
}
```

## 3. T02 音频输出与路由

以下三个公共步骤是 T02-A01–A24 每一行的组成部分：

- **A-P 前置**：已知声道/编码的本行格式媒体、该路由硬件及其已知能力、稳定网络；准备 Media3/mpv 和所有现有输出模式；先用可用 PCM 对照片确认物理链路正常。AVR 行记录电视/盒子→HDMI/eARC→AVR 的完整拓扑和功放型号；HDMI 行记录直连接收端，不能混成 AVR 结果。
- **A-S 操作**：接通本行路由，选择对应轨道；分别切每个内核/模式，从停止状态加载，稳定播放至少 30 秒；记录听感、声道、AVR/接收端指示、诊断输出格式及置信度；暂停/恢复一次，切走再切回路由，检查回落和恢复。每个变体单独登记。
- **A-C 判定**：支持的路径持续出声、无爆音/意外静音，声道与接收端实际能力一致；直通只能在能力及接收端证据支持时认定；不支持时安全解码/回落，或明确可恢复的“不支持”提示，不能仍显示直通成功。无法观察实际输出则为 Unknown/证据不足；单凭参数读回不能判听感或直通通过。

| 编号 | 场景 | 前置条件 | 操作步骤 | 可观察判定标准 | 任务 |
| --- | --- | --- | --- | --- | --- |
| T02-A01 | 扬声器 × AC3 | A-P；AC3 夹具 | A-S，使用扬声器 | A-C；本机出声及回落记录 | T02 |
| T02-A02 | 扬声器 × EAC3 | A-P；EAC3 夹具 | A-S，使用扬声器 | A-C；本机出声及回落记录 | T02 |
| T02-A03 | 扬声器 × DTS | A-P；DTS 夹具 | A-S，使用扬声器 | A-C；本机出声及回落记录 | T02 |
| T02-A04 | 扬声器 × DTS-HD | A-P；DTS-HD 夹具 | A-S，使用扬声器 | A-C；区分 HD/core/PCM，不将 DTS core 冒充 HD | T02 |
| T02-A05 | 扬声器 × TrueHD | A-P；TrueHD 夹具 | A-S，使用扬声器 | A-C；本机出声及回落记录 | T02 |
| T02-A06 | 蓝牙 × AC3 | A-P；AC3 及蓝牙耳机/音箱 | A-S，连接蓝牙 | A-C；记录蓝牙实际回落格式 | T02 |
| T02-A07 | 蓝牙 × EAC3 | A-P；EAC3 及蓝牙设备 | A-S，连接蓝牙 | A-C；记录蓝牙实际回落格式 | T02 |
| T02-A08 | 蓝牙 × DTS | A-P；DTS 及蓝牙设备 | A-S，连接蓝牙 | A-C；记录蓝牙实际回落格式 | T02 |
| T02-A09 | 蓝牙 × DTS-HD | A-P；DTS-HD 及蓝牙设备 | A-S，连接蓝牙 | A-C；不误报 HD 直通 | T02 |
| T02-A10 | 蓝牙 × TrueHD | A-P；TrueHD 及蓝牙设备 | A-S，连接蓝牙 | A-C；不误报 TrueHD 直通 | T02 |
| T02-A11 | HDMI × AC3 | A-P；AC3、直连 HDMI 接收端 | A-S，使用 HDMI | A-C；接收端格式与出声对应 | T02 |
| T02-A12 | HDMI × EAC3 | A-P；EAC3、直连 HDMI 接收端 | A-S，使用 HDMI | A-C；接收端格式与出声对应 | T02 |
| T02-A13 | HDMI × DTS | A-P；DTS、直连 HDMI 接收端 | A-S，使用 HDMI | A-C；接收端格式与出声对应 | T02 |
| T02-A14 | HDMI × DTS-HD | A-P；DTS-HD、直连 HDMI 接收端 | A-S，使用 HDMI | A-C；区分 HD/core/PCM | T02 |
| T02-A15 | HDMI × TrueHD | A-P；TrueHD、直连 HDMI 接收端 | A-S，使用 HDMI | A-C；接收端实际格式可追溯 | T02 |
| T02-A16 | AVR × AC3 | A-P；AC3、AVR 及完整链路 | A-S，使用 AVR | A-C；功放指示与声道对应 | T02 |
| T02-A17 | AVR × EAC3 | A-P；EAC3、AVR 及完整链路 | A-S，使用 AVR | A-C；功放指示与声道对应 | T02 |
| T02-A18 | AVR × DTS | A-P；DTS、AVR 及完整链路 | A-S，使用 AVR | A-C；功放指示与声道对应 | T02 |
| T02-A19 | AVR × DTS-HD | A-P；DTS-HD、AVR 及完整链路 | A-S，使用 AVR | A-C；区分 HD/core/PCM，不混记 | T02 |
| T02-A20 | AVR × TrueHD | A-P；TrueHD、AVR 及完整链路 | A-S，使用 AVR | A-C；功放是否锁定 TrueHD 有证据 | T02 |
| T02-A21 | 扬声器 × PCM 对照 | A-P；PCM16/24/32/FLOAT 各一份 | A-S；逐个 PCM 位深/类型播放 | A-C；四种 PCM 均不归类编码直通；CPU 解码结论需另有证据 | T02 |
| T02-A22 | 蓝牙 × PCM 对照 | A-P；四种 PCM 和蓝牙设备 | A-S；逐个 PCM 位深/类型播放 | A-C；PCM 与蓝牙链路编码分别记录，不误报直通 | T02 |
| T02-A23 | HDMI × PCM 对照 | A-P；四种 PCM 和 HDMI 接收端 | A-S；逐个 PCM 位深/类型播放 | A-C；接收端 PCM 读数与编码直通分开 | T02 |
| T02-A24 | AVR × PCM 对照 | A-P；四种 PCM 和 AVR | A-S；逐个 PCM 位深/类型播放 | A-C；AVR 的 PCM/声道与诊断相符 | T02 |
| T02-B01 | 实际 renderer/sink、初始化与 offload | 可取得 Media3 renderer/audio sink 及 mpv 初始化证据 | 从停止状态切模式后启动；采集 prepare 前后输出配置和接收端；分别观察 offload 开/关（若可观测） | mpv 初始直通选择在 initialize 前落实；Media3 实际 sink/编码配置与选择一致；offload 单列，Unknown 不冒充已确认 | T02 |
| T02-B02 | 同 caps 路由事件使快照失效 | 能力相同的两条路由/热插拔，带时间戳诊断 | 播放中切到相同 caps 的新路由，再断开/重连；保留事件前后快照 | 旧 route/generation 快照失效；采样时间/可信度可区分；旧输出不滞留为新路由结论 | T02 |
| T02-B03 | 切源/轨/内核的晚采样隔离 | 两片源、多音轨、双内核，能延迟旧采样 | 连续切源、手选音轨、切内核；让旧采样晚到 | 显示归属于当前 scope/item/source/track/engine/route/generation；新输出未知时显示 Unknown，旧采样不覆盖 | T02、T03 |
| T02-B04 | duck→调音量→恢复 | 可产生真实瞬时音频焦点 duck 的测试应用/系统事件，先固定音量 | 播放并触发 duck；duck 期间多次增减应用请求音量；释放焦点；两内核各做，记录系统音量与应用音量 | 衰减基于最新用户请求值，调节不中途跳回旧值；mpv 输出按最新请求值约 20% 衰减，恢复按最新请求值；实际听感与数值均记录 | T02 |
| T02-B05 | 异常恢复与旧告警清除 | 可断开输出/制造不支持路径的测试链路 | 播放时移除路由/切不支持轨；恢复可用路由/轨；再试缺参数轨 | 异常后可恢复；恢复后旧告警消失；缺参数仅为未知/可疑，不能显示已确认故障或成功 | T02 |
| T02-B06 | 路由监听生命周期及 R6-14 边界 | 可重复前后台/进入退出，注册部分失败需获准夹具 | 连续进入/退出播放器、前后台和路由重连；如可注入则让部分 register/unregister 失败后重试 | 观察重复监听/遗漏更新/异常；R6-14 的部分成功回滚、独立清理、重试在原单仍未完成，不能由普通路径通过抹去该风险；缺夹具保持 BLOCKED，问题回 T02 | T02、T11 |

这里 24 个组合为 4 路由 ×（6 种编码/PCM 类别），其中每个 PCM 类别再分四种输入；前 20 项对应用户要求的 AC3/EAC3/DTS/DTS-HD/TrueHD 五种编码。

## 4. T03 退休许可、取消和 decoder

| 编号 | 场景 | 前置条件 | 操作步骤 | 可观察判定标准 | 任务 |
| --- | --- | --- | --- | --- | --- |
| T03-01 | 快速切源与迟到 native 结果 | 同一影片多源，双内核，decoder/负载时间线 | 播放后连续切源至少 10 次；制造一次慢返回后选最后源 | 最后选择生效；旧画面/状态/错误不覆盖；旧退休未成功前不提交新活动 decoder；无双 decoder 重叠 | T03 |
| T03-02 | 快速切内核 | 双内核可播同一源；采集 build→setMediaItem→prepare→Surface→decoder 事件 | Media3/mpv 往返至少 10 次并穿插切源 | 区分 player 对象与活动 decoder；旧 release 完成后才准许新代活动资源；没有重叠/音画串代 | T03 |
| T03-03 | >3 秒和永久未决退休 | 可延迟释放 >3 秒及持续未决的获准夹具 | 分别触发延迟与不完成退休，尝试新负载并按遥控导航/Back；随后释放延迟操作 | 等待超时不视为成功、不 timeout-continue、不启动第二 decoder；UI 可响应/取消；晚成功仅允许仍有效的当前请求提交 | T03 |
| T03-04 | Back 取消与旧请求隔离 | 加载/切源可延迟，录像与服务端日志 | 加载中 Back 返回详情，立刻播放另一项目；让旧 JNI/请求返回 | 返回及时；旧负载不复活/抢 Surface/上报到新项目；decoder 始终符合许可；非 UI 线程等待 JNI | T03 |
| T03-05 | cleanup/destroy 失败 | 真 JNI 故障或获准调试夹具、隔离设备 | 制造 cleanup/destroy 失败；重试播放、取消、返回；记录错误与资源 | 失败不释放新负载许可，不能借第二 decoder 逃逸；UI 仍可返回并可见失败；永久 native 卡死的进程隔离未实现，单独报告；缺夹具即 BLOCKED | T03 |
| T03-06 | 播放器返回及 Activity 生命周期 | 正常播放与菜单/详情导航、进程/heap/codec 观察条件 | 进入播放→Back 返回→再次进入至少 10 轮；再退出应用，等待稳定并采资源 | 无遗留声音/Surface/活动 decoder，无持续增长的 Activity 保留链；用 heap 引用链证明泄漏问题，不能仅以一次 GC 或回调清理判无泄漏 | T03、T11 |

## 5. T06 连播与时间线（A/B 独立验收）

| 编号 | 场景 | 前置条件 | 操作步骤 | 可观察判定标准 | 任务 |
| --- | --- | --- | --- | --- | --- |
| T06-01 | A：EOF→Stop→下一集及跨季 | 有下一集/跨季的剧，服务端 Progress/Stop 时间线 | 正常播到 EOF；重复跨季末集；两内核执行 | 当前内容 Stop 与下一集加载顺序可追溯；下一集确实可播、只推进一次、不串 session；不以预解析 Found 代替实际播放 | T06、T03 |
| T06-02 | A：末集/缺源/不可播结果 | 末集、无源下一集、探测不可播夹具 | 各播放到结束并观察 UI、请求及重试 | NoNext 与失败区分；缺源/不可播不作为 Found；不无限转圈/跳过到错误项目；没有凭空开启新 decoder | T06 |
| T06-03 | A：临时失败恢复与预算耗尽 | 下一集接口可注入超时/5xx及不可重试错误 | EOF 时失败后恢复网络；再持续失败耗尽重试；另试不可重试错误 | RetryableFailure 有限退避且可恢复，预算耗尽结束并可见提示；不可重试错误不无限重试；请求次数/间隔留证 | T06 |
| T06-04 | A：暂停/后台/取消后的旧结果 | 可延迟下一集解析、可恢复旧重试 | 分别在重试期间暂停、HOME 后台、用户取消；让旧结果返回，再手动恢复 | 旧重试/Found 不自动复活或后台开播；当前状态/代际仍有效才可推进；各分支分别记录 | T06 |
| T06-05 | A：切集/切号后迟到隔离 | 两集/两账号，慢解析 | 旧集 EOF 后延迟结果，手动切集或切账号，再放行旧结果 | 旧回调不能替换当前项目/新账号状态，不污染新 session/下一集 UI；不绕过 T03 许可 | T06、T03 |
| T06-06 | B：offset=0 与 >0 | 可确认原内容坐标与物理负载坐标的两种播放源 | 从头播放、断点续播并 seek；记录 native position/duration、Progress/Stop | watchdog 使用当前物理负载同轮样本；Progress/Stop 保持原内容坐标；offset 不重复加减，两组独立留证 | T06、T07 |
| T06-07 | B：未知 position/duration | duration 未知/position 暂未知的受控媒体 | 起播、缓冲、恢复、切源；持续观察状态与上报 | 未知 position 不伪造 0，未知 duration 关闭 NO_END，不沿用旧 duration；不因未知值误触发连播/恢复/片尾判断 | T06 |
| T06-08 | B：旧物理样本与边界 | 可切物理负载、已知近片尾/停滞媒体 | 临近片尾切源/重载，放行旧样本；单独测真实 stall 与片尾 60 秒边界 | 旧代 native 样本被拒；既有 stall 阈值和 60 秒片尾阈值不因坐标混用改变；当前真实 EOF 与 T06-01 顺序一致 | T06、T03 |

## 6. T07 HTTP、seek 与 trickplay

| 编号 | 场景 | 前置条件 | 操作步骤 | 可观察判定标准 | 任务 |
| --- | --- | --- | --- | --- | --- |
| T07-01 | HTTP 重定向/HLS 子请求 | 可查看服务端/代理请求的 Emby 和 CDN/HLS 夹具 | 直连、跨源重定向、HLS playlist/segment 播放；双内核执行 | 实际 URL/协议归属、所需请求头与 session 正确；敏感认证不外泄到不可信源；错误属于真正失败请求 | T07 |
| T07-02 | Emby 401/403 | 受控 Emby 认证过期/拒绝访问 | 播放/续播时分别返回 401/403，再恢复合法会话 | 提示明确归于 Emby 认证/权限；可返回/重新认证；有限恢复，不无限重试或启动并行 decoder | T07、T03 |
| T07-03 | CDN/网盘 403 与签名直链 | 可过期且可重新取得的签名 URL；Emby 会话仍有效 | 直链播放与 seek 中让签名过期，尝试允许的刷新/重载 | 不把 CDN 403 误说成 Emby 登录失效；刷新有限且不泄漏签名；有效新链恢复，失败有可理解提示 | T07 |
| T07-04 | resume/seek/重载坐标 | Emby 流与签名直链，offset=0/>0 | 各续播、连续前后 seek、失败后重载，再退出 | 呈现位置与服务器 Progress/Stop 坐标对应；seek 确认可追踪，无重复 offset、负值或旧负载回写 | T07、T06 |
| T07-05 | BIF 正常显示、末帧与快速拖动 | 真实 BIF，多张有可识别时间画面的缩略图 | 打开预览，从起点到末帧并快速往返拖动 | 显示真实正确图片与时间，末帧索引不越界；旧请求不覆盖最新指针，不阻塞播放/遥控 | T07 |
| T07-06 | BIF 损坏、取消及重试 | 损坏/截断 BIF，可延迟下载 | 请求中 Back 取消再进入；失败后恢复文件重试 | 损坏安全降级，不崩溃/持续卡住；取消的结果不回填，后续可重新请求成功 | T07 |

## 7. T08 遥控焦点与无障碍

| 编号 | 场景 | 前置条件 | 操作步骤 | 可观察判定标准 | 任务 |
| --- | --- | --- | --- | --- | --- |
| T08-01 | 三服务器删非末项 | 已保存 A/B/C；位于账号管理页 | 分别重置夹具后删 A、删 B；按方向键与 OK | 删除后落在下一存活服务器的切换项（A→B、B→C），不落到已删项或直接跳保存；焦点可见/可继续 | T08 |
| T08-02 | 三服务器删末项/删空 | 已保存 A/B/C，独立测试账号 | 删除 C，再逐个删空，确认前后的当前会话 | 删 C 落到 B 的切换项；删空落到保存当前项；不访问失效 requester；列表与会话保持现有正确语义 | T08 |
| T08-03 | 第五页手动继续与追加失败 | 前四页无可见结果、第五页有结果；可制造追加失败 | 搜索达到自动请求上限，用 D-pad 选择继续；追加失败后重试 | 保留自动页数上限，显示可聚焦的继续入口而非误报无结果；手动到达第五页，失败可重试且不重置已有结果 | T08 |
| T08-04 | 快速查询与延迟初始焦点 | 延迟 A 查询、快速 B 查询，慢首屏 | 连续改关键字，返回历史再进入；延迟列表加载后按方向键 | A 迟到不覆盖 B，焦点不抢回旧结果；延迟首次焦点只落到有效项，不出现永久失焦 | T08 |
| T08-05 | 历史首/中/末项删除 | 多条历史，含长词 | 分别聚焦首/中/末项删除；再连续删除直到空 | 优先下一存活项，再前一项；空表落到现有有效入口；焦点对应稳定词条，不随 index 错位 | T08 |
| T08-06 | D-pad 连续导航/返回恢复 | Home/详情/列表/设置/账号/搜索有数据 | 每页连续上下左右、长按和 OK；跨页返回；边界各重复 20 次 | 焦点持续可见、滚动跟随，边界不丢失、不跳到无关页；返回落点合理且无异常 | T08 |
| T08-07 | 禁用项不可激活 | 详情等包含不可操作/加载中的项 | 用 D-pad/OK/长按尝试进入并触发禁用项，待其恢复可用再试 | 禁用项无业务副作用，enabled 语义与外观一致；可用后正常；不以变灰代替禁用 | T08 |
| T08-08 | TalkBack 语义与操作 | 支持 TalkBack 的测试设备，长历史词及删除按钮 | 打开 TalkBack，逐项朗读、导航和激活；读禁用项/历史/删除按钮 | 正确朗读完整历史词、删除对象/动作与禁用状态；顺序可理解，操作无误触；TV 不支持时另用支持设备，不能省略 | T08 |
| T08-09 | 多宽度/密度/字号的焦点可见性 | 电视与不同宽度/密度设备，系统标准及大字号 | 在各组合打开账号/搜索/详情，滚动到边缘并连续导航 | 文本不因裁切遮住必要信息，焦点框完整可辨，目标可滚入视口；记录具体配置和截图 | T08、T09 |

## 8. T09 展示、轮播记账与计数

| 编号 | 场景 | 前置条件 | 操作步骤 | 可观察判定标准 | 任务 |
| --- | --- | --- | --- | --- | --- |
| T09-01 | 多密度 Hero 曝光/轮播记账 | 至少两密度/分辨率，可核对曝光计数/日志 | 稳定停留、轮播、手动换卡、来回滚动使 Hero 出入屏，各按固定时间录像 | 仅实际满足曝光条件的可见内容记账；重组/滚回/动画不额外重复记账；不同密度按实际可见性判定 | T09 |
| T09-02 | 裁切/遮挡/前后台曝光 | 可让 Hero 部分离屏或页面进后台 | 局部裁切、完全滚离、进入详情遮挡、HOME/返回，再停留 | 不可见/后台不计曝光或错误推进可见记账；回来按当前内容恢复，不补记未展示项 | T09 |
| T09-03 | Logo/父级图/无图兜底 | 有 Logo、Logo 404/超时、仅父级图、全部缺图四组 | 冷图进入详情/Hero，切换来源并重试图片失败 | Logo 成功正确显示，失败按现有父级图/文字兜底，无空白永久占位或上一影片串图；保留四组截图 | T09 |
| T09-04 | 宽银幕/裁切视频标签 | 已知原始与裁切后宽高/显示比例的媒体 | 查看详情与播放标签，切不同源并比较服务器元数据和实际呈现 | 宽银幕标签与可证实显示比例一致，不把文件像素比当所有内容的最终显示比例；源切换后不保留旧标签 | T09 |
| T09-05 | 计数失败/恢复/切号 | 两账号数据明显不同，可令 count 请求失败/迟到 | 失败时查看首页总数，重试；A 请求延迟时切到 B 再放行 A | 失败不显示伪 0/旧账号总数，恢复后更新；迟到 A 不写入 B；既有列表仍可用，错误可辨认 | T09 |
| T09-06 | DirectStream/转码与设备能力 | 可观察服务端播放决策与客户端能力的同一媒体 | 同片在不同设备/轨道/内核播放，记录协商请求、服务端决策与实际流 | 记录实际 DirectPlay/DirectStream/transcode，不能凭 UI 标签推断；能力不符/不必要转码交回 T09，不在本单改策略 | T09、T02 |
| T09-07 | R6-13 DV 输入对照 | 匹配的 DV 原始输入及可核对元数据/设备能力 | 同一内容不同 DV 变体分别取证，比较标签/协商/画面 | 必须有匹配输入证据才作分类结论；没有该物料保持 BLOCKED，不借普通 HDR 样片推断 DV 分类 | T09 |

## 9. T10 轨道记忆（strict_default）

当前生产模型没有能证明来源和 SOURCE_BYTES 覆盖的可信 validator producer；不能把任意 opaque validator/contentVersion 当作精确 TRACK 恢复依据。显式可信测试夹具的 Available 结果不代表真实服务器已具备该能力。

| 编号 | 场景 | 前置条件 | 操作步骤 | 可观察判定标准 | 任务 |
| --- | --- | --- | --- | --- | --- |
| T10-01 | 即时手选与实际确认 | 多语言/同语言多轨/关闭字幕夹具，双内核 | 播放中手选音轨/内置字幕/关闭字幕；比较实际声音/字幕和 UI | 本轮当前负载的即时手选生效，确认的是实际选轨而非默认标记；失败/待确认不写成已生效 | T10 |
| T10-02 | 重协商降级提示 | 不可验证版本的真实源，可触发重新 probe/load | 先手选精确轨道，再 seek 重载/切内核等触发重协商 | 不宣称跨 probe/load 保持精确手选；按现有语言/Emby 默认规则降级，提示重新选择；不静默用旧 index | T10 |
| T10-03 | 切源及同 index 换内容 | 两个轨道 index 相同而语言/内容不同的源 | 手选后切源；再在测试服务器替换同 index 媒体内容并重新加载 | 旧源精确轨道不串入新内容；缺可信字节版本时保守降级并提示，不因 index 相同认同一轨 | T10 |
| T10-04 | 缺版本/来源不明/覆盖不足 | 缺 validator、opaque validator、仅 contentVersion 三组 | 分别选择轨道，退出并重新加载/重启 | 三组都不作为可信精确 TRACK 恢复证据；Unknown 与降级可解释；若需要正向恢复，先交 T10 审核 producer | T10 |
| T10-05 | 重启后的失效墓碑/v1 不复活 | 隔离测试账号有获准构造的 v1 TRACK/成功持久化 v2 墓碑 | 触发失效，确认持久化成功；退出进程再冷启动，多次进入同一媒体 | 已持久化墓碑下旧 TRACK 不复活；v1 TRACK 永不直接恢复；不通过清空存储“修复”结果 | T10 |
| T10-06 | 外置字幕独立替换 | 相同路径/index 的外置字幕，独立内容版本不可证实 | 手选外置字幕，服务端替换字幕后重新 probe/load/重启 | 无独立可信版本时不持久恢复精确 TRACK；不沿用旧字幕身份，保守降级/提示 | T10 |
| T10-07 | 真实服务器 validator 取证 | 服务端可追踪生成来源和媒体变更过程 | 分别更改内部媒体字节、元数据、外置字幕；采集 validator 更新与覆盖说明 | 只记录实际来源/更新时机/覆盖范围；证据不全保持 BLOCKED；可信 producer 与外置版本方案交回 T10 另案评审 | T10 |
| T10-08 | SharedPreferences 失败/进程中止边界 | 可注入真实提交失败的隔离设备及进程控制 | 提交前后分别中断进程；另制造 commit=false，再正常重启 | 失败不报持久化成功、当前进程安全回退；失败提交/全部 v2 标记被外部清除后不承诺跨重启历史不可丢失；记录真实边界，无夹具则 BLOCKED | T10 |

## 10. T11 诊断导出与退出信息

| 编号 | 场景 | 前置条件 | 操作步骤 | 可观察判定标准 | 任务 |
| --- | --- | --- | --- | --- | --- |
| T11-01 | 导出期间 UI 与耗时 | 正在播放且有大量诊断的设备、录像/trace | 连续打开诊断/导出并操作 D-pad，覆盖首次/重复导出 | 主线程不等待长 IO，按键持续响应，无 ANR；记录按键到画面/导出耗时和 trace，不声称未测收益 | T11、T12 |
| T11-02 | applicationLooper/快速退出释放 | 可采集 player 线程与资源事件，双内核 | 边播放边导出、快速切源/退出/重入 | 播放器访问符合实际 applicationLooper；旧诊断不碰已释放对象；无崩溃、UI 卡死或资源串代 | T11、T03 |
| T11-03 | 厂商 logcat 权限与降级 | 至少一台受限量产设备及一台可读设备 | 应用内导出系统日志；对照 shell 能力，模拟读取失败/超时（可用时） | 只报告应用实际可读范围；拒绝/空/超时明确标记，不借 shell 权限冒充 App 权限，也不造完整日志 | T11 |
| T11-04 | ANR/native 退出 trace 与 reader 饱和 | 支持退出原因 API/trace 的设备，获准 ANR/native 故障夹具 | 分别触发 ANR/native crash，重启导出；尝试迟返/阻塞读，比较 API/厂商差异 | 类型/来源对应真实退出；权限不足有状态；reader 饱和为 reader_busy、晚返流关闭，不无限增长线程；不能中断的 Binder/open 限制保留 | T11 |
| T11-05 | 共享包内容/隐私/授权 | 可接收分享的测试应用，含脱敏标记的日志/大 trace | 导出并分享，解包逐个检查；记录 URI、MIME、临时读授权与可打开性 | 包内容可读且限长/格式有效；无 token/Cookie/完整签名 URL；不含原始 native trace；合法摘要、缺失/截断状态可辨 | T11 |
| T11-06 | 异步退出快照的持久性边界 | 有可识别退出快照、可控制终止时机的设备 | 正常退出后重启；另在排队写入尚未完成时终止进程 | 正常已落盘内容可读；直接杀死前未落盘的最后快照可能缺失，明确记录，不将异步排队当持久成功 | T11 |

## 11. T12 性能：沿用原采集方案

### 固定采集方法

- 同设备、APK SHA-256、账号/媒体修订、显示/电源/主题/玻璃设置及热状态；低 RAM 标志、总 RAM 和当前压力分别记录。先同版本 A/A 测噪声。
- 每场景 1 次预热后至少 **5 轮独立 30 秒**；冷启动/首次播放至少 **10 次进程冷启动**。后台更新、输入超时、解码错误、热节流的轮次保留作废原因。
- System trace、Layout Inspector 重组计数、Full Java/Kotlin allocations、方法跟踪/调试断点分轮采集，不能混作无干扰时延基线。
- 输出目录使用原脚本的 `.temp/T12-device-<scenario>-<timestamp>/`；保留原始 trace、gfxinfo、前后 meminfo、显示/包信息、thermal、输入实际时间与页面/焦点录像。脚本不安装、不登录、不判断当前页，需人工确认首卡、loaded 数和有效移动，碰边界后的空按键不算滚动。

以下命令仅供未来有设备时执行，本轮未执行。先确认四个 T12 来源文件存在：

```powershell
$adb = 'C:\BuildTools\android-sdk\platform-tools\adb.exe'
& $adb devices -l
& ./.temp/T12-collect-device.ps1 -PreflightOnly
# 手动进入相应页面并固定焦点；每条命令独立预热后执行至少五轮。
& ./.temp/T12-collect-device.ps1 -Serial '<SERIAL>' -Scenario Home -KeyCodes 22,21
& ./.temp/T12-collect-device.ps1 -Serial '<SERIAL>' -Scenario Favorites -KeyCodes 20,19
& ./.temp/T12-collect-device.ps1 -Serial '<SERIAL>' -Scenario Detail -KeyCodes 22,21
& ./.temp/T12-collect-device.ps1 -Serial '<SERIAL>' -Scenario Options -KeyCodes 22,21
& ./.temp/T12-collect-device.ps1 -Serial '<SERIAL>' -Scenario Player -Steps 0
& ./.temp/T12-collect-device.ps1 -Serial '<SERIAL>' -Scenario SaveState -Steps 0
```

原脚本配置 `gfx/view/wm/am/input/dalvik`、调度/频率、进程/内存与 `android.surfaceflinger.frametimeline`；先 `dumpsys gfxinfo com.lumina.tv reset`，push UTF-8 配置，再以 `perfetto --txt -c <remote-config> -o <remote-trace> --background` 采集，结束后 pull 原始 trace，并取 `gfxinfo ... framestats`、`meminfo -d`、`thermalservice`。Perfetto/权限失败保留原错。用 `.temp/T12-frame-analysis.sql` 前先核 schema/进程/UI 与视频 layer，填真实稳定窗口，检查 token join 唯一性及未匹配比例。API 26–30 的 HWUI gfxinfo 只能是有限基线，不能代替视频 SurfaceView/SurfaceFlinger；新系统也需核验层覆盖。覆盖不足记 inconclusive，不能填零卡顿。

| 编号 | 场景 | 前置条件 | 操作步骤 | 可观察判定标准 | 任务 |
| --- | --- | --- | --- | --- | --- |
| T12-01 | 预检/采集分支/SQL 有效性 | 原脚本/SQL齐备，授权设备，工具版本可记 | PreflightOnly 后用一个场景实采；核对页面/输入/trace schema/层和 SQL 稳定窗口 | 有实际 trace/有效帧与原始输入时间；脚本设备分支错误、厂商权限/层缺失明确保留；captured_unreviewed 不能 PASS | T12 |
| T12-02 | Home 冷/热图与规模 | 实际 loaded=50/500/5000（缺数据记真实值） | Recommend 横排及 Media 网格固定动作，冷图/缓存图分组各 5 轮 | 各轮稳定区有效 UI 帧至少 300；输出 jank 类型、overrun p50/p95/p99、main/RenderThread/GC；p95 overrun>0ms 或 jank>5% 是调查线，不是凭阈值自动改代码 | T12 |
| T12-03 | Favorites 滚动/追加/焦点恢复 | 同样 loaded 分组，可真实追加 | 分开采页内滚动、追加一页、详情返回恢复焦点，每组 5 轮 | 帧/GC与重组数据齐备；记录 snapshotFlow 求值/发射、anchor 保存栈、loaded/offset、恢复延迟；不混成单个滚动均值 | T12 |
| T12-04 | 重组/skip 与逐帧 Java 对象 | Inspector、Profiler Full；不能 Full 时记录 Sampled | 稳定后重置计数、固定 120 次输入；单独采 main/相关栈对象和字节；验证 monotonic 时间基准再与帧对齐 | 分开可见/新入屏节点与正常动画；不能从 skippable 推断零重组；无法对齐则对象/帧=null，只报对象/动作或秒；不能按假定对象大小由字节造对象数 | T12 |
| T12-05 | MainActivity 完整 outState | 可调试保存回调，访问 1/10/100 个不同详情键 | Home→Media→Favorites→Detail→返回，HOME 触发保存；独立断点轮次在整个 outState 完成后 Parcel.writeBundle 并取 dataSize，finally recycle | 保存整个真实 Bundle/registry 图，记录字节/键/保留页数；不可见 outState 则 pending；JVM JSON 大小不能替代；>200KiB 或 p95>1刷新周期调查，>500KiB 或连续3轮无界增长升级 | T12 |
| T12-06 | 保存耗时与进程回收恢复 | 与 T12-05 同夹具，已解除调试器 | 独立 method recording 分开保存回调/registry/Bundle.writeToParcel；再无跟踪 System trace；HOME 后 `am kill com.lumina.tv`，确认 PID 消失后从最近任务恢复 | 报告主线程 wall time/方法跟踪开销/GC及真实恢复结果；am kill 不等同 force-stop，先核保存发生；不以断点轮次计无干扰耗时 | T12 |
| T12-07 | DetailHeroParallax | 同大图/详情、可用的既有 parallax/玻璃选项 | 静止10秒，再固定焦点移动；现有选项分别5轮 | 采 UI/RenderThread/GPU slice、overrun、graphics/PSS、图片解码和重组；Java 几何算术快不能排除 blur/离屏合成，需慢帧归因和 A/A 噪声 | T12 |
| T12-08 | GlassSurface/Components | 同页同面板数/动作；区分 FrostPanel 与绘制式玻璃 | 现有玻璃开关分别5轮，独立采 draw/shader/分配/GC/内存与录像 | 实际 draw/RenderThread、PSS及帧结果可比；静态绘制操作数不能作为收益；无显著改善不支持新增内存策略 | T12 |
| T12-09 | PlayerOptions 首开/重复开 | 同源稳定播放，实际0/8/32/128轨（缺口记明） | 首次开菜单，字幕/音轨/更多往返；重复打开独立分组 | 按键到首呈现帧 p50/p95，OptionMenu 重组、标签/TrackMapping/FocusRequester 分配栈齐备；首开p95>100ms 或构建p95>1刷新周期先分段归因 | T12 |
| T12-10 | bufferPlan/首次播放/真实内存 | 低 RAM 与普通设备、同码率，fastStart 现有开/关 | 至少10次进程冷启动首次播放，并与同进程再次播放分组；记录 getMemoryInfo IPC、首次 plan、prepare、首帧/重缓冲、PSS/codec | Java/native/graphics PSS和活动 decoder 有实测；plan>1ms 只作调查线，分离类加载/IPC/ART首次成本；缓冲上限不等于实际分配，始终单 decoder 许可 | T12、T03 |
| T12-11 | 候选 A/B 证据闸门 | 当前无候选；未来须先有目标路径问题及另单授权 | 先5轮A/A；未来候选同条件至少5轮A/B，复测首帧/重缓冲/焦点/保存/T03/T10 | 分配改善至少20%且>3×MAD并有GC/帧同向证据；PSS改善中位数同时≥10%、≥8MiB且>3×MAD，或慢帧显著下降；无正确性回退。不具备候选时维持待验，不能宣称优化通过 | T12 |

## 12. T13 / T14 / T15 外部发布与来源门禁

以下包含 CI/合规材料验收，**不全是设备项**，仍收敛于本表避免遗失。本单不触发远程 workflow，不读取签名秘密，不签名/发布；未来执行涉及外部发布需另行授权，先保留 BLOCKED。

| 编号 | 场景 | 前置条件 | 操作步骤 | 可观察判定标准 | 任务 |
| --- | --- | --- | --- | --- | --- |
| T13-01 | GitHub 端 unsigned CI/权限 | 获准 CI 运行及 Ubuntu 工具链；先解 T14 输入阻塞 | 在获准 PR/分支运行 verify job，查看实际日志/unsigned APK/R8 产物 | 实际远端 verifyOffline+assembleRelease 成功；PR不拿签名secret、不发布、无 Debug 回退；记录run/commit/APK hash，不用Windows本机构建冒充 | T13、T14 |
| T13-02 | 正式签名/证书固定与缺秘密失败 | 配置 release environment 保护及五项 signing secrets 的获准环境 | 获准后对匹配版本tag执行签名流程；检查实际证书指纹/版本/hash；在隔离流程分别测缺秘密/错误tag/错误指纹 | 缺条件在签名前失败；APK证书匹配固定指纹、非Debug、产物结构/版本正确；秘密不出日志；当前未执行，不能记signed通过 | T13 |
| T13-03 | 真实发布资产与安装行为 | 另行发布授权、完成签名门禁；本单无该授权 | 在获准发布中核对tag/APK/SHA-256/报告/R8留档；下载资产复算，测试设备安装启动及同证书升级 | 只发布通过门禁的signed Release，资产字节/hash/版本一致，安装/升级与TV启动可用；流程失败不留下被当成功的发布；当前保持BLOCKED | T13 |
| T14-01 | Linux AAPT2 缓存/校验元数据 | Ubuntu环境，`aapt2:8.10.1-12782657:linux@jar` 的可追溯真实输入 | 在获准补件流程取得真实输入/校验来源并交回T14更新metadata；Linux strict验证完整构建 | 缺失的linux JAR及真实SHA-256有依据，strict构建实际成功；不关校验、不用Windows变体代替、不编造哈希；本轮继承BLOCKED | T14 |
| T14-02 | 新 AndroidX Test 依赖的供应链闭环 | runner/ext-junit及传递依赖缓存齐备，T14可审核metadata | 后续另单审核来源/sha256后补metadata，strict离线编译androidTest，再在Linux核验需要的变体 | 不使用off/lenient、trusted-artifact通配或临时换仓过关；只有实际strict编译成功才更新compiled；本单不越权改metadata | T14、T16 |
| T15-01 | Native 确切源码/patch/输入绑定 | 对应待发APK、24个native文件、完整可追溯源码/构建材料 | 按 `docs/native-sources.json` 对每ABI/so核对哈希、确切revision、patch全集、编译选项/静态依赖；运行既有来源夹具与source-closure门禁 | AAR字节匹配不能代替源码绑定；空/排除FFmpeg的sources JAR不足；全部绑定及交付材料齐备才可能解除BLOCKED；不以设备可播放代替 | T15 |
| T15-02 | 许可全文/NOTICE/源码分发包 | 待发包、许可证/NOTICE/对应源码与必要重链接/安装信息 | 核对GPL/LGPL组合、NDK libc++与AndroidX native/静态依赖；检查被packaging排除的许可如何实际交付；核对分享/下载渠道 | 真实交付物包含适用完整许可/NOTICE/对应源码及必要信息；专业复核完成前不宣称合规；来源夹具尚未接入CI的风险保留 | T15 |
| T15-03 | TV 关于页许可弹窗/遥控/链接 | 含T15 UI的实际APK、TV遥控、可用/不可用浏览器各一组 | 从关于页进入许可弹窗，长列表滚动/切焦点/Back，逐个打开源码/许可链接；离线重试 | 版本与BuildConfig一致，文本/焦点可读可达且返回正常；链接正确，浏览器缺失/网络失败可恢复；旧同版本APK不能代替当前UI验收 | T15、T08 |

## 13. Instrumentation 骨架、命令和本轮事实

新增 `app/src/androidTest/java/com/lumina/tv/acceptance/DeviceSmokeTest.kt`，四个 `@Test` 只覆盖无需媒体账号夹具的基础边界：

| 用例 | 实际断言范围 | 不能推导的结论 | 本轮 |
| --- | --- | --- | --- |
| `tvLauncherResolvesToExportedLandscapeMainActivity` | Leanback入口解析到导出的横屏MainActivity | 实际设备启动/首页数据正常 | 未运行 |
| `launchReachesVisibleResumedActivityAndRegistersRemoteCallbacks` | Activity RESUMED、原生content有可见子视图、遥控/搜索回调存在 | 认证首页加载完成、真正进程冷启动 | 未运行 |
| `recreationDestroysOldActivityAndResumesReplacement` | recreate旧实例销毁、新实例恢复、运行时回调存在 | 完整保存图正确、轨道/decoder状态正确 | 未运行 |
| `finishDestroysActivityAndClearsRuntimeCallbacks` | finish后销毁且两个全局回调清除 | heap无泄漏、播放器Back释放或无双decoder | 未运行 |

测试使用平台 `Instrumentation` 管理 Activity，不增加 ActivityScenario/Espresso/Compose test 依赖；运行前使用可丢弃测试设备/账号，测试会启动/清除当前Activity任务。状态断言使用10秒轮询截止时间，结束清理本测试启动的Activity；平台同步API自身的阻塞还需runner/测试进程超时约束。真实首页、Back、音频/decoder与所有业务场景仍按上表人工验收。

`defaultConfig.testInstrumentationRunner` 保留为 `androidx.test.runner.AndroidJUnitRunner`。曾添加最小依赖 `androidx.test:runner:1.6.2`、`androidx.test.ext:junit:1.2.1`，严格离线探测在 `:app:processDebugAndroidTestManifest` 失败：两者均为 `No cached version ... available for offline mode`。**已立即回滚两条依赖**；没有联网补件或修改 verification metadata/关闭严格校验。runner 配置不表示 runner 已可用。

因此当前骨架 **编译 BLOCKED（compiled=false）**，保留为标准 Kotlin 源码，当前工程尚不具备可编译/可安装 androidTest 的依赖条件；不能把 Debug APK 构建成功写成骨架编译成功。后续在T14审核依赖缓存、传递依赖和元数据后，在 `app/build.gradle.kts` 的 `dependencies` 中恢复以下两条（本轮最终文件中没有这两条依赖）：

```kotlin
androidTestImplementation("androidx.test:runner:1.6.2")
androidTestImplementation("androidx.test.ext:junit:1.2.1")
```

然后执行：

```powershell
$env:JAVA_HOME = 'C:\BuildTools\jdk-17.0.13+11'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:compileDebugAndroidTestKotlin --offline --no-daemon --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2048m' '-Pkotlin.compiler.execution.strategy=in-process' --dependency-verification=strict
.\gradlew.bat :app:verifyOffline :app:assembleDebug --offline --no-daemon --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2048m' '-Pkotlin.compiler.execution.strategy=in-process' --dependency-verification=strict
# 仅在依赖/编译已收口且有授权设备时执行（本轮没有执行）：
.\gradlew.bat :app:connectedDebugAndroidTest --offline --no-daemon --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2048m' '-Pkotlin.compiler.execution.strategy=in-process' --dependency-verification=strict '-Pandroid.testInstrumentationRunnerArguments.class=com.lumina.tv.acceptance.DeviceSmokeTest'
```

| 本轮操作 | 结果 |
| --- | --- |
| `adb devices -l` | 仅有 `List of devices attached`，无设备；SDK `emulator/emulator.exe` 不存在 |
| 带依赖执行 `:app:compileDebugAndroidTestKotlin :app:verifyOffline :app:assembleDebug`，offline/strict | BUILD FAILED in 34s，退出1，实测35.016秒；缺两个AndroidX Test缓存，未到Kotlin测试编译/完整门禁；随后立即回滚依赖 |
| 回滚后 `:app:verifyOffline :app:assembleDebug`，offline/strict | BUILD SUCCESSFUL in 14m 55s，退出0，实测896.352秒；68个任务，29 executed / 39 up-to-date；这是离线回归与Debug构建结果，不是androidTest编译或设备结果 |
| `connectedAndroidTest` / `connectedDebugAndroidTest` | **未运行**：无设备且AndroidX Test依赖/编译BLOCKED |
| 设备/外部发布清单99项 | **全部未验收**；缺物料的专项还需记录具体BLOCKED原因 |

## 14. 阻塞与交接

- **T16/T14**：AndroidX Test runner/ext-junit离线缓存缺失，两条依赖已回滚；测试源码未编译通过。补件/元数据审核后必须重新编译并再跑完整离线门禁，不能在本单越权改metadata或主依赖表。
- **全部设备项**：无ADB设备、无emulator；无声音/焦点/画面/实际轨道、decoder或性能结果。必须连接获准设备、补齐媒体/故障夹具后逐编号/变体执行。
- **T02**：R6-14未完成；音频输出/直通/CPU解码和offload须各自取证。
- **T03/T11**：真实JNI永久阻塞、cleanup失败、厂商trace/logcat权限及异步落盘边界仍需设备证据；本单不增加生产故障注入。
- **T10**：真实validator producer可信度/覆盖及外置字幕独立版本仍需另案评审；真实存储故障边界不能由JVM夹具代替。
- **T12**：原设备采集脚本/SQL设备分支和各层schema尚待实测；没有A/A基线、没有候选性能通过，也没有引入Auto/低内存产品策略。
- **T13/T14/T15**：真实GitHub签名/发布未执行；Linux AAPT2输入/metadata和native源码/许可交付闭环仍BLOCKED，不因Windows Debug成功解除。
- 发现业务问题时回传：编号/对应任务、设备与APK hash、最小复现步骤、预期/实际、证据和建议修复归属；业务代码变更与版本/发布另走原单。本单不commit/push、不开子代理、不改版本、不发布。
