# 01 — 目录形状与三旋钮解析

**What to build:** 配置里第一次能表达「哪个厂商的哪个模型」。`providers.edn` 变成
`厂商 → {protocol, base-url, 默认 model, models{id → {:input :output}}}`，`config.edn` 只写三个旋钮
`{:provider :openrouter :model "anthropic/claude-sonnet-4.5" :reasoning-effort "high"}`，解析结果由这三者
**装配**出来（endpoint 与模态都从目录取）。换 provider 而不同时改 model 时，endpoint 必须随之改变——
今天这个动作是静默无操作的死字段，这是本票要杀掉的核心 bug。

**Blocked by:** None —— 可以立即开始。

**Status:** ready-for-agent

```edn
;; providers.edn
{:openrouter {:protocol :openai-completions
              :base-url "https://openrouter.ai/api/v1"
              :model    "anthropic/claude-sonnet-4.5"      ;; provider 的默认 model id
              :models   {"anthropic/claude-sonnet-4.5" {:input #{:text :image} :output #{:text}}
                         "deepseek/deepseek-chat"      {:input #{:text}       :output #{:text}}}}}
```

- [ ] `providers.edn` 的 provider 条目形状如上报；`:models` 是**以 model id 为键的 map**；`:model` 是
      该 provider 的默认 id，且必须是 `:models` 里声明过的 id（否则指名报错）。
- [ ] `:input` / `:output` 必填，词汇表 `:input ⊆ #{:text :image}`、`:output ⊆ #{:text}`；集合外的值指名报错
      并说明本 harness 能搬什么。
- [ ] 解析仍是四档（目录 → `config.edn` 默认档 → 会话覆盖 → 本 run 请求），但每档只动三个旋钮
      `:provider` / `:model` / `:reasoning-effort`。**只写 `:provider` 换厂商 = 该厂商默认 model + 其 endpoint
      生效**（有测试钉住 base-url/protocol 真的跟着换）。
- [ ] 写了一个该 provider 未声明的 model id → 指名报错并列出已知 id（不回落、不猜）。
- [ ] 解析结果是装配体 `{:protocol :base-url :model :reasoning-effort :input :output :api-key}`，
      api-key 仍只在这一个函数里挂上，仍不出现在任何自省/日志/工具结果里。
- [ ] 旧形状（provider 级 `:model` 字符串 + 无 `:models`）遇到即指名报错，**不做兼容读**；inline 逃生门
      （`config.edn` 里直接给 `:protocol`/`:base-url`/`:model`，不命名 provider）保留，其 model 无声明时
      `:input`/`:output` 为 absent。
- [ ] `active-provider` 回答 `:provider :model :reasoning-effort :input :output :protocol :base-url`（内部是
      集合）；线上（日志行、HTTP、工具结果）渲染为排序后的字符串数组。
- [ ] `provider/init` 行改写为「三个选择旋钮 + `:resolved`（protocol/base-url/model/input/output）+ `:source`
      + `:api-key :stripped`」，`:source` 语义不变。
- [ ] 「四个描述字段」的硬编码收成一处（今天 `memory` 私有 `fields` 与 `http` 的 `select-keys` 各写一份），
      切片常量与其序列化只有一份实现。
- [ ] `providers.edn.example` / `config.edn.example` 改写为新形状；README「配置」整段（家目录文件树、
      解析优先级、示例）同步；`prompt.md` 里「四个描述字段」的措辞同步更新。
- [ ] 开发机真实 `~/.clj-harness/providers.edn` 与 `config.edn` 手工改写成新形状，`clojure -M:run` 起得来。
- [ ] 离线全量 `harness.test-runner` 除既有的 `bash-runs-git-bash-not-wsl`（macOS 恒红，与本票无关）外全绿。
