# 02 — `SystemPrompt` 点与追加

**What to build:** 第 27 个 hook 点。一次 run 组装 system 消息时触发；**匹配到的声明全部跑、全部追加**，
文本按来源档位与各自顺序拼在冻结开头之后。退出 2 表示「这次 run 不许开始」，stderr 就是理由。

从用户视角：`hooks.edn` 里声明一条命令，它的 stdout 从下一次 run 起就出现在 system 消息里；写坏了（超时、起不来、
自己说不行）就是这次 run 不开始，而不是悄悄少一段规矩。

**Blocked by:** 01（来源与运行缝——没有 `:run` 就没有内建的行，这个点也就自己吃不了自己的饭）

**Status:** ready-for-agent

## 验收

- [ ] 组装住在**新 ns `harness.system-prompt`**（拿冻结的开头、触发这个点、把文本拼好），边只调它一处；
      它**不并进 `harness.preamble`** 的理由写进 docstring：`project` 已 require `preamble`，
      而这条缝要 `tools` / `project` / `hooks.dispatch`，并进去就是 require 环
- [ ] 点表加一行：`"SystemPrompt"` / `:system-prompt`，时机「一次 run 的 system 消息正在被组装」，
      payload 只有公共四个（`hook` / `thread_id` / `project_dir`），没有匹配对象，`:on-error :block`；
      docstring 里那个「26 个」跟着改；未知点键的报错清单里出现 `:system-prompt`
- [ ] 行的**一格**说明这个点的 stdout 是内容（不是 verdict）：dispatch 照它把各条的文本收进**有序的 `:blocks`**；
      **其它点的返回与审计行逐字节不变**
- [ ] **全部跑、全部追加**，不是 first block 获胜——一条 hook 不该把另一条的文本吃掉；顺序即来源档位与声明顺序
- [ ] 文本 `trim` 后非空才追加；**空输出 = 这一条什么都不说**，不是错误
- [ ] 追加的文本**原样**进 prompt，引擎不包装：内建的块自己带 `<tools>` 这类标签，作者写什么就是什么；
      块之间恰好一个空行
- [ ] system 消息 = `prompt.md` 的**字节** + 各块；装配发生在边的 hook sink binding **之内**
      （这条不改就静默失效——拿到的 sink 是 nil，一条 hook 都不触发，写进 docstring）
- [ ] **退出 2 → 这次 run 不开始**：客户端收到 RUN_ERROR，stderr 逐字是理由，日志里有 `hook/SystemPrompt` 行，
      理由点名是哪一条声明
- [ ] **超时 / 起不来**照点自己的 `:on-error`（`:block`）走同一条，理由说清是超时还是起不来
- [ ] **一条声明都没有时一行审计都不写**，system 文本与今天（prompt.md 全文）逐字节相同
- [ ] 被 `session-disable!` 关掉的声明不追加，但仍留在 `effective-hooks` 里（关闭不是隐藏）
- [ ] 改 `hooks.edn` 在**下一次组装**生效，不需要重启（两级装配与「项目级整键替换」原样）
- [ ] 没有 sink 的调用方（offline 工具、replay、直接驱动内核的测试）**不跑 hook、不追加**，
      与今天逐字节相同；`replay` 以日志文件名当 thread-id **现算**（沿用「现读而不是从日志里取」的既有立场）
- [ ] 文本不截断；大小由审计行与 jsonl 的 `message` 行如实记录
- [ ] `clojure -M:test -m harness.test-runner` 全绿（基线 `main` @ `0ef17a9`，344 / 1933）
