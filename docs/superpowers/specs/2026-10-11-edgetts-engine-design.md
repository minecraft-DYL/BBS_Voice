# BBS Voice — EdgeTTS 引擎设计（替换自研共振峰合成器）

- 日期：2026-10-11
- 状态：已实现并验证（单元测试 66 项 + 真机自检 30 项）
- 目标：**把配音的东西全删掉，换成用 EdgeTTS 配音**（用户原话）。
  自研共振峰合成器（可懂度「只能勉强听懂 `你好`」）与 Windows SAPI 后端全部删除，
  Edge 在线语音成为唯一的合成引擎。**人声轨道的功能一条不少**：
  14 个音色、音量/语速/音高三条曲线、6 个情绪预设、自己的录音、缓存、自动适配、演员 3D 播放。

## 1. 为什么能换成 EdgeTTS（上一版否掉它的两个理由都不成立）

上一版（`2026-09-27-system-speech-prosody-design.md`）明确写了「❌ 不做 Edge TTS 后端」，
理由是「要联网 + 要塞 MP3 解码器 + 接口是逆向的」。这一版逐条推翻：

| 上一版的顾虑 | 实测结论 |
| --- | --- |
| 要联网 | 属实，但代价可接受：**每句只联网一次**，结果缓存成 wav 后永久离线可用 |
| 要塞 MP3 解码器 | 属实，但一条依赖就够：JLayer 1.0.1.4（LGPL，约 100KB）用 `embed` 打进 jar，解码封装 120 行 |
| 接口是逆向的 | 属实，但已完整复现并**用黄金值钉死**（`SecMsGecTest` 对参考实现比对），不再靠猜 |

Edge 服务本身是免费的公开接口（浏览器「大声朗读」用的就是它），不需要账号、不需要 key。

## 2. 架构

```
VoiceData (text + 音色 + 音调 + 音量/语速/音高 三曲线 + 情绪 + 来源)
      │
      │  lang=zh_cn "/voice": "zh-CN-XiaoxiaoNeural"   ← 老存档的 "male" 会自动回退
      ▼
ProsodyPlanner  （纯 Java，可单测）  按标点切分句 → 采样三条曲线 → List<ProsodySpan>
      │                              ⚠ 服务限制：一份 SSML 最多 2 个 <prosody>，>2 句要合并成 2 组
      ▼
SsmlBuilder     spans → SSML（每条 <prosody> 同时带 rate/pitch/volume）
      ▼
EdgeTtsRequest  text + voice + 韵律指纹 → SHA-1 前 10 字节 = 缓存文件名
      ▼
EdgeTtsBackend  （实现 SpeechBackend）
      ▼
EdgeTtsClient   java.net.http.WebSocket → wss://speech.platform.bing.com/.../readaloud/edge/v1
      │          握手：TrustedClientToken + Sec-MS-GEC + Sec-MS-GEC-Version + Origin + Cookie
      │          会话：Path:speech.config(JSON) → Path:ssml → 二进制 mp3 分片 → Path:turn.end
      ▼
Mp3Decoder（JLayer）→ 16 位 PCM → WavFile.write → 24 kHz 单声道 wav
```

关键点：**整条 `tts/` 都没有 Minecraft 依赖**，所以不用启动游戏就能跑通完全相同的路径
（`tools/EdgeTtsProbe.java` 直接调 `EdgeTtsBackend`）。

## 3. 逆向出来的四条硬约束（全部实测，不是文档抄的）

### 3.1 Sec-MS-GEC 令牌：必须用 double 算

没有它，websocket 升级直接 `403`。令牌 = 大写 SHA-256 of
`(Windows file time 的 100ns 刻度，向下取整到 5 分钟边界) + trusted client token`。

坑在精度：刻度值约 **1.3e17**，超过 double 的 2^53，「正确」的整数运算反而算不出服务端的值。
必须照着参考实现（`tools/edge_tts.js`）用 double 走一遍、再用 double 的十进制输出。
5 分钟取整是容忍时钟偏差用的。

`SecMsGecTest` 用参考实现的黄金值（含一个 1970 时间戳和一个 2026 时间戳）钉住这一点——
这是唯一没法靠「发个请求看返回」来验证的部分。

### 3.2 只接受 mp3 / webm-opus

| outputFormat | 结果 |
| --- | --- |
| `audio-24khz-48kbitrate-mono-mp3` | ✅ 采用 |
| `webm-24khz-16bit-mono-opus` | ✅ 但 BBS 不能直接读 |
| `riff-24khz-16bit-mono-pcm` | ❌ `1007 Unsupported Edge output format` |
| `riff-16khz-16bit-mono-pcm` / `raw-*-pcm` / `ogg-*-opus` | ❌ 同上 |

BBS 的 `AudioReader` 只认 `.wav`（WaveReader）和 `.ogg`（仅 Vorbis），所以必须
**mp3 → 解码 → 16 位 PCM → wav**。24 kHz 单声道是这条链的固定产物，因此设置里**没有采样率选项**。

### 3.3 一份 SSML 最多 2 个 `<prosody>`（最反直觉的一条）

| SSML 形状 | 结果 |
| --- | --- |
| 1 个 `<prosody>` | ✅ |
| 2 个 `<prosody>`（属性各不相同、3 位小数的 rate 也行） | ✅ |
| 3 / 4 / 5 / 6 个 `<prosody>` 兄弟节点 | ❌ `1007 SSML is invalid` |
| 嵌套 `<prosody>`（2 层 / 3 层） | ❌ |
| `<break time='400ms'/>` | ❌（单独用时是另一种报错：`Data at the root level is invalid`） |
| 空 `</prosody><prosody></prosody>` 当作第 3 个 | ❌ |

判定：**元素个数**的硬限制，跟属性内容、文本长度无关（每个元素属性完全一样、各含一个字，第 3 个照样被拒）。

**这条直接推翻了上一版的设计**：上一版按「每个分句一个 `<prosody>`」规划韵律，长句必然 ≥3 个元素，
在 Edge 上 100% 失败。所以：

* `ProsodyPlanner` 在采样前先把分句**合并成 ≤2 组**（反复合并当前最短的相邻对，按字数均衡）；
* 1–2 个分句的句子完全不受影响（行为和之前逐分句一一对应）；
* `SsmlBuilder` 多加一道防御：>2 个直接抛 `IllegalArgumentException`，
  而不是把注定被拒的文档发出去、换回一个看不懂的 websocket 关闭码。

### 3.4 情绪风格 `mstts:express-as` 被拒

带正确的 `xmlns:mstts='https://www.w3.org/2001/mstts'` 也一样：`1007 SSML is invalid`；
不带命名空间则报 `'mstts' is an undeclared prefix`（说明解析器**确实**在校验命名空间，
不是命名空间没写对的问题）。

结论：**「感情」= 韵律曲线**（语速/音高/音量随时间变化 + 标点停顿），
这也正是剪辑软件里调 AI 配音感情的通用做法。真正的神经情绪风格只有 Azure 语音（付费 + key）才有。

## 4. 韵律标定结果（`tools/EdgeTtsProbe.java --matrix`）

同一句话（基线 5.064s / 峰值 21785 / RMS -20.9 dBFS）：

| 用例 | SSML 属性 | 时长 | 峰值 | RMS |
| --- | --- | --- | --- | --- |
| baseline | `rate=1 pitch=+0% volume=+0%` | 5.064s | 21785 | -20.9 |
| rate 1.5 | `rate=1.5` | 3.408s | 21851 | -21.0 |
| rate 0.7 | `rate=0.7` | 7.224s | 21866 | -21.0 |
| pitch +20% | `pitch=+20%` | 5.064s | 22561 | -21.0 |
| volume +80% | `volume=+80%` | 5.064s | 30830 | -17.4 |
| volume -60% | `volume=-60%` | 5.064s | 8508 | -28.9 |

三条属性的写法由此确定（`TtsProsody`）：

* **rate 用裸倍数**（`rate='1.25'`），不用百分比；
* **pitch 用带符号百分比**（`pitch='+20%'`）；
* **volume 用相对写法**（`volume='+0%'`）——绝对写法上限就是 `100%`，而 `100%` 恰好等于「不变」，
  表达不了「更响」，情绪预设需要它变响。

钳位：`rate ∈ [0.5, 2]`、`pitch` 偏离 `±50%`、`volume ∈ [0, 2]`。

## 5. 烘进合成 vs 播放时实时（修掉一个双重施加的老 bug）

| 项 | 烘进合成（进缓存签名） | 播放时实时（不进签名） |
| --- | --- | --- |
| 文本 | ✅ | |
| 音色 | ✅ | |
| 音调（`pitch`） | ✅ 作为 SSML `pitch` | |
| 音量曲线 / 语速曲线 / 音高曲线 | ✅（音高曲线与音调相乘后烘） | |
| 音量（`volume`） | | ✅ 播放增益 |
| 语速（`speed`） | | ✅ 播放速率（磁带式，会一起变调） |
| 偏移 / 自动适配 | | ✅ |

老版本把「音调」同时当播放倍速又当合成参数用（`speed × pitch`），等于施加了两次；
现在音调只在合成侧生效（顺带修掉花栗鼠效应），语速只在播放侧生效。

缓存文件名 = `SHA-1(engineVersion | voice | 韵律指纹 | text)` 前 10 字节。
字段用**长度前缀**拼接而不是 `|` 分隔：文本是用户随便打的，用分隔符时
`("v","","a|b")` 和 `("v","|a","b")` 会撞成同一个 key（单测 `fieldsCannotBeReSplitIntoTheSameKey` 盯着这条）。

## 6. 向下兼容

* BBS 的 `ValueGroup.fromData` 遍历传入的 map 并**跳过不认识的 id**，所以删掉
  `f0/formant/breath/vibrato/jitter/tilt/excitation/tone/speechVoice` 这些字段后，
  **老影片照样能读**，只是这些值不再有效果。
* 老存档里的 `voice` 是 `male`/`female` 这类预设 id → `EdgeVoice.byId()` 找不到就回退到
  `zh-CN-XiaoxiaoNeural`（`anUnknownVoiceFallsBackInsteadOfFailingTheRequest` 单测覆盖）。
  真机自检里也有一项直接喂一份「带老字段的 map」进 `VoiceData.fromData()`。
* 缓存签名换了引擎版本前缀（`edge-tts-v1`），老的自研合成器缓存不会被命中，会被缓存上限自然淘汰。

## 7. 失败与重试

联网引擎比离线引擎多一种失败模式，所以：

* 状态栏在 FAILED 时**直接显示原因**（「合成失败：java.net.UnknownHostException…」），
  不再只是「合成失败」——用户没法去翻游戏日志分辨「断网」和「服务拒绝」。
* 失败**不永久拉黑**：60 秒冷却后自动重试一次（否则一次掉包 = 这句话永远没声音，
  除非用户自己找到「重新合成」按钮）。面板按钮随时可以立刻重试。
* 渲染线程是单线程后台队列，不卡游戏；失败时删掉半成品文件，避免被当成缓存命中。

## 8. 测试

| 测试 | 覆盖 |
| --- | --- |
| `SecMsGecTest` | 参考实现黄金值、5 分钟桶内/跨桶、64 位大写十六进制 |
| `EdgeVoiceTest` | 默认音色、未知 id 回退、id 唯一性/形状、**语言文件与音色表双向对齐**（少一个 key 或留下一个已删音色的 key 都会红） |
| `EdgeTtsRequestTest` | 同输入同 key、文本/音色/韵律都改 key、字段不可重切分、null 安全 |
| `SsmlBuilderTest` | XML 转义、三属性齐全、命名空间/语言、**第 3 个 span 必须抛异常** |
| `TtsProsodyTest` | 三种属性写法、钳位、无符号 rate、属性内不能出现引号 |
| `ProsodyPlannerTest` | 分句（中/英标点）、不丢字、按字符位置采样、**≥3 句合并成 2 组且均衡**、空白/纯标点 |
| `Mp3DecoderTest` | 用**服务真实发回的 mp3**（`src/test/resources/edge/hello.mp3`）解码：帧同步、24 kHz、时长/峰值/RMS 合理、解码确定性、垃圾输入报 IOException |
| `WavFileTest` | 16 位单声道往返、RIFF 头字段、**只读头算出时长**（24 kHz 1 秒）、**跳过 data 之前的未知 chunk**、垃圾/缺失文件返回 0 |
| `ClipFitRulesTest` | 秒→tick 换算（向上取整）、长台词延长剪辑、**只增不减**、**自动适配时不动剪辑**、**播放期只调自己设过的长度**（老影片/用户拖过的不碰） |
| 真机 `BBSVoiceSelfTest` | 工厂注册、序列化往返、老存档回退、l10n、资源包、缓存签名、曲线轨道、面板构造、**Edge 合成 + wav 非静音校验 + 语音时长上报 + 剪辑自动贴合（新剪辑会长 / 手动拖过的不动 / 开自动适配的不动）** + OpenAL 加载 + 播放 |

一共 66 项纯 Java 单测（不需要游戏、不需要联网）+ 真机自检 30 项。
`tools/EdgeTtsProbe.java` 是联网的集成探针：默认（生产形态的多分句计划）、`--single`、`--matrix`、
`--dump`（把服务发回的 mp3 原样落盘，用来重新采集上面那个 fixture）。

真机自检抓出的第一个真 bug 值得记一笔：「音调」在没有开音高曲线时被整条丢掉
（`prosodyPlan()` 的无曲线分支返回全 1.0 的 plan），也就是**音调滑块完全无效**。
单测覆盖不到它（`VoiceData` 依赖 BBS 的类，只能在游戏里跑），现在自检里有两项专门盯着：
「plain pitch reaches the engine with no curves on」和「un-animated axes stay at their plain value」。

### 8.1 第一次真机手测发现的问题：「有时候啥都没有，还有时候只有一声（极短）」

用户手测原话。现象看着像合成失败，其实**三个新渲染出来的 wav 全是健康的**（1.224s / 1.728s / 2.376s，
峰值 19k–22k，文件完整），所以问题一定在播放链路。逐条查证：

| 查证 | 结论 |
| --- | --- |
| 磁盘上的 wav 坏了？ | ❌ 时长/峰值/文件大小全部正常，data chunk 长度与文件长度自洽 |
| `SoundPlayer` 的 `getPlaybackPosition` / `setPlaybackPosition` 单位错了？ | ❌ 反编译 BBS：两者都是 `AL_SEC_OFFSET`（秒），`setPlaybackPosition` 还自带 `clamp(0, buffer.duration)` |
| 剪辑太短？ | ✅ **BBS 的 `Clip` 构造函数默认 `duration = 1` tick；用户 `bbs.json` 里 `editor.duration = 30`，也就是新建剪辑默认只有 1.5 秒**，而一句话合成出来 1.2–2.4 秒。**离开剪辑时 BBS 会停掉这条剪辑里的声音**（`VoiceClientClip.shutdown → VoicePlayback.stop → SoundManager.stop`），所以剪辑不够长 = 台词被硬切 |
| 为什么第一遍干脆没声音？ | ✅ **合成的唯一触发点是播放**：`VoiceAudioCache.get()` 只被 `VoicePlayback.tick()` 调用，而 `tick` 只在播放头位于剪辑内时执行。1–3 秒的联网合成 vs 1.5 秒的剪辑窗口 → 第一遍必然赶不上（合成落在窗口末尾时，就只能听到最后零点几秒，"只有一声"） |

两个修复，都在**不改变 v1 语义**的前提下：

1. **编辑即预热**：面板一打开 / 改文本 / 换人物 / 换来源就 `prefetch()`（面板打开 → 选中剪辑 → 写字，
   全部发生在播放之前）。播放路径保持只读缓存。
2. **剪辑贴合语音**：合成完成的那一刻把剪辑 `duration` 延长到语音长度（`ClipFitRules` 决策表：
   只增不减、自动适配优先、编辑期跟随新台词、播放期只调 mod 自己设过的长度、老影片不碰）。
   顺带录音选文件后也按录音长度贴合。
3. **不再让用户猜**：状态栏在「剪辑比语音短」时显示
   `已就绪，剪辑比语音短，会被截断：2.4s > 1.5s`（并指出开自动适配或拖长剪辑）。

新增测试：`ClipFitRulesTest`（决策表全部边界）、`WavFileTest`（只读头算时长）、真机自检 4 项
（时长上报 / 新剪辑会长 / 手动拖过的不动 / 开自动适配的不动）。自检那次渲染出来的台词正好是
**2.4s（48 tick）**，而默认剪辑 30 tick —— 修复前这条台词的后 0.9 秒必被切掉，这就是手测发现的同一个坑。

## 9. 删除清单（旧引擎，git 提交 `d82f34d` 之前可恢复）

主源码：`tts/{Phonemizer,Phoneme,Phone,PinyinTable,PinyinSyllable,EnglishLts,SpeechSynthesizer,VoiceSynthesis,VoiceRequest}.java`、
`tts/speech/SystemSpeechBackend.java`、`voice/VoicePreset.java`；
资源：`assets/bbsvoice/pinyin.txt`（190KB）、`assets/bbsvoice/bbsvoice-say.ps1`；
测试：`SpeechSynthesizerTest`、`PhonemizerTest`、`PinyinSyllableTest`、`PinyinTableTest`；
开发工具：`AnalyzeSpectrum`、`AnalyzeWave`、`AsciiSpec`、`BuildPinyinTable`、`CalibrateRate.ps1`、
`DiagBreath`、`DiagPhonemes`、`MeasureLevels`、`MeasureWav.js`、`RenderLine/Sample/Speech/Vowel`、
`ResonatorResponse`、`SpecImage`、`edge_tts_probe.js/ps1`；以及 `scratch/`。

保留：`tools/edge_tts.js`（协议参考实现）、`tools/EdgeTtsProbe.java`（新的集成探针）、
`tools/RunSelfTest.ps1`（真机自检）。

## 10. 明确不做（YAGNI）

* ❌ 不做情绪风格（`mstts:express-as` 被服务拒绝，见 3.4）
* ❌ 不做 `webm/opus` 路线（BBS 的 ogg 读取器只支持 Vorbis，还得再塞一个 opus 解码器）
* ❌ 不做语音列表在线刷新（14 个中文音色够用；要加人肉加一行 `EdgeVoice` 常量）
* ❌ 不做逐句多请求拼接（能突破 2 个 `<prosody>` 的限制，但会把一次请求变成 N 次，
  还要处理拼接接缝和部分失败；当前合并成 2 组的方案在 1–2 句的常见情况下本来就是精确的）
* ❌ 不做 macOS/Linux 兜底（删掉自研合成器后没有第二个引擎，统一走 Edge）
