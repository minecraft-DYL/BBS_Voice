# 配音音色目录 + 可搜索选择框（2026-10-14）

## 目标

1. 音色从 14 个扩到「Edge 全部可读中文的音色 + 角色音」，面板里的**循环按钮换成可搜索列表**（条目一多循环按钮就没法用了）。
2. 让**曼波这类角色配音**（用户点名要，类似必剪/剪映的角色音）能在游戏里直接选。

## 非目标

- 不做本地模型推理：本机 GT 630 是 Fermi 架构 + 1GB 显存（现代 torch/CUDA 不支持），CPU 又是 i3-3240 双核，纯 CPU 推理不可用。
- 不逆向剪映/必剪的私有服务。
- 不把第三方音频打包进 mod：运行时按需请求。

## 已核实的事实（2026-10-14 实测，非推测）

| 事实 | 证据 |
| --- | --- |
| Edge 音色目录 322 个，中文（`zh-*`）只有 14 个 | 拉全量 `voices/list` 分组统计 |
| 其中 12 个 `*-MultilingualNeural` 可读任意语言 | 目录命名 + 微软文档；渲染读音待实测 |
| 中文音色的 `StyleList` 为空 | 情绪只能继续走现有曲线方案 |
| Edge 里没有任何角色/人设音色 | 322 个 ShortName 全是 `<locale>-<Name>Neural` |
| **曼波有公开免 key 的 HTTP API 且可用** | `GET api.milorapart.top/apis/mbAIsc?text=&format=wav` → JSON 的 `url` → 48kHz 单声道 16bit WAV，3.36s，峰值 73.7% |
| 新端点不提供音色列表（403） | 直接请求 `api.msedgeservices.com/.../voices/list` |

## 设计

### 音色模型

- `Voice`（新接口）：`id()` / `labelKey()` / `labelFallback()`——UI 与语言文件只依赖这三个。
- `EdgeVoice`：补 12 个多语音色（共 26），实现 `Voice`。**现有 id 形状测试（`<locale>-<Name>Neural`）继续成立**，所以外部音色不能塞进这个类。
- `ExternalVoice`（新）：id 形如 `ext:manbo`，带一组 endpoint 模板（`{text}` 占位）与镜像地址。
- `VoiceCatalog`（新）：统一 `all()` / `byId()` / `exists()` / `isExternal()`。`all()` 的顺序就是 UI 顺序：普通话 → 方言 → 粤语 → 台湾 → 多语 → 角色。

### 渲染

- `ExternalTtsBackend`（新，实现 `SpeechBackend`）：GET 模板 → **嗅探响应**而不是靠配置声明（Content-Type 或魔数）：要么响应体本身就是音频，要么是带 `url` 字段的 JSON 再取一次；然后解码（WAV 直读 / mp3 走 `Mp3Decoder`）→ **重采样到 24kHz 单声道 16bit** → 复用 `WavFile` 的写入与校验。
- 多 endpoint 顺序降级；429/5xx 退避重试（与 Edge 侧同一套策略）。
- `VoiceAudioCache.submit` 按 `VoiceCatalog.isExternal(voice)` 分派后端。
- 缓存键：`VoiceData.toRequest()` / `toSpeechRequest()` 改用**原始 voice id**。原来用的是 `edgeVoice().id`，外部 id 会被宽容 fallback 成晓晓，导致缓存键错、音色也错。
- 老影片兼容：voice 仍是字符串，`edgeVoice()` 的宽容 fallback 保留。

### UI

- `UIVoiceClipBase`：`UICirculate voiceCycle` → `UIButton voicePicker` + `UIStringOverlayPanel`。后者是 BBS 原生**可搜索**浮层（BBS 自己的音效/标签选择就用它），点击弹出全量音色、输入即过滤，选中即 `applyVoice(Voice)`。
- 标签形如「晓晓 · 女声」「晓北 · 东北话」「多语种 · Ava · 女声」「曼波 · 角色配音」——靠文案区分类别，不额外造分组控件。
- `fillData()` 负责把按钮文案刷成当前音色。

### 失败与降级

- 外部服务不可用：沿用现有 `FAILED` + 60 秒冷却，状态栏显示原因；不影响 Edge 音色使用。
- 未知的外部 id：回退到默认外部音色（与 `EdgeVoice.byId` 同策略），不让老影片静音。

## 验证计划

1. 单测：目录 id 唯一且可达、两个语言文件的键完整（扩展现有测试）、URL 模板转义、JSON 取 `url`、重采样后长度与峰值、响应嗅探判定。
2. 探针：真实渲染一句曼波 → wav 非静音 + 时长上报。
3. 真机自检：新增一条**离线**检查（目录含外部音色且可选）；联网那句仍走 Edge，不让发布门槛依赖第三方。
4. 手动：游戏里选曼波渲染一句并记录（第三方服务，只作为人工验证，不进自检门槛）。
