# 01 — 目录形状：model 条目加上 :context-window / :max-output-tokens

**What to build:** 在 provider 目录里给任意一个 model 写上它的上下文窗口与最大输出 token，写对了下一次
run 就能被解析和报告，写错了在下一次 run 指名失败并说清该怎么写；内置的主流模型开箱就带真实数字，
用户一个字都不用抄。

形状（与 `:input` / `:output` 同一层，两者都可选）：

```edn
{"anthropic/claude-sonnet-4.5" {:input #{:text :image} :output #{:text}
                                :context-window 200000 :max-output-tokens 8192}}
```

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 两个字段都是可选：一个都不写的 model 条目与今天完全一样，不报错、不补默认值
- [ ] 值不是正整数就指名报错，说出写的是什么、该写什么（`0`、负数、小数、字符串 `"8192"` 各算一种），
      失败信息点名 provider 与 model id
- [ ] 两者都声明时校验 `:max-output-tokens` ≤ `:context-window`，越界指名报错并把两个数都报出来
- [ ] model 条目里没人读的 key 指名报错（`harness.models` 里已有一个定义了却没人用的 `model-keys`：
      今天拼成 `:context_window` 会被静默丢弃），失败信息列出该条目认识的 key
- [ ] 内置表的主流模型逐条填数：数字读自厂商现网列表（OpenRouter `/api/v1/models` 的 `context_length`
      与 `top_provider.max_completion_tokens`，DeepSeek 官方文档，ollama library 页），沿用该表已有的
      `:as-of` 日期；核对不到的 model 留空，不写凭记忆的数
- [ ] 用户在 `providers.edn` 里覆盖一个内置 model 时**按字段盖**而不是整条替换：只想改一个数字不该被迫
      重抄 `:input` / `:output`
- [ ] inline provider（config.edn 里直接描述的那个）同样可以声明这两个数字，规则与模态一致
- [ ] 示例文件与 `harness.models` 的形状 docstring 同步改写
- [ ] 离线全量 `harness.test-runner` 全绿（基线 175 tests / 835 assertions）
