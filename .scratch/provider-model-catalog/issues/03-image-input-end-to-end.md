# 03 — 图片输入端到端（AG-UI part → 出网 part）

**What to build:** 用户在入站消息里带一张图，模型真的收到它。AG-UI 的 image part 与 OpenAI 兼容端点的
image part 不是同一个形状，当前 `inbound` 原样透传，图片会被直接发到厂商接口换回一个 400。本票做翻译，
并让记录的 `message` 行如实反映「LLM 看到了什么」。

**Blocked by:** 01（新配置形状；本票的测试家目录与 fixture 按 01 的写法建）。

**Status:** ready-for-agent

- [ ] `harness.ag-ui/inbound` 把用户消息的 content parts 翻成出网形状：
      - `{:type "text" :text "…"}` 保持原样（AG-UI 与 OpenAI 兼容端点同形）；
      - `{:type "image" :source {:type "url" :value "https://…"}}` → `{:type "image_url" :image_url {:url "https://…"}}`；
      - `{:type "image" :source {:type "data" :value "<base64>" :mimeType "image/png"}}` →
        `{:type "image_url" :image_url {:url "data:image/png;base64,<base64>"}}`；
      - 认不出的 part 类型 → **指名报错**（既不静默丢弃，也不原样发出）。
- [ ] content 是**字符串**的消息（无 parts）逐字不变——既有行为是回归保证。
- [ ] 翻译放在 `inbound` 而不是 `llm.clj`：`message` 行的契约是「LLM 真实看到的东西，逐字」，到协议层才翻
      会让日志撒谎。这条理由写进函数文档串（第二个协议出现时，这里是拆分接缝）。
- [ ] 修正现有测试里伪造的 part 形状 `{:type "image" :url "u"}`——真实 AG-UI 客户端发的是
      `:source` 包裹的形状，旧断言锁的是一个不存在的形状。
- [ ] 端到端（离线、scripted provider）：POST 一个带 url 图片与 data 图片的 AG-UI run，落盘的 `message` 行
      里两种 part 都是翻译后的出网形状，且与帧流一致。
- [ ] `replay/history` 路径同样经过 `inbound`（种子来自 `input` 行的 AG-UI 形状消息），有测试钉住重建后的
      历史里图片 part 也是出网形状——live 与 replay 两条路不能各翻一套。
- [ ] 离线全量除既有 `bash-runs-git-bash-not-wsl` 外全绿。
