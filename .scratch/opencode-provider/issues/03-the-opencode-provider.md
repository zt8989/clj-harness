# 03 — opencode 这个 provider（第一个真扩展）

**What to build:** `ext` 这个根里的第一个真扩展 `ext.opencode`（入口 `install`，名字由 `config.edn` 的
`:ext ["opencode"]` 点上）：它贡献 `opencode` 这一家的目录条目，
并给**每一次 run 请求**加上 `x-opencode-session: <thread-id>`。密钥一个字不用写——`opencode` 这个名字按
现有派生规则自动得到 `OPENCODE_API_KEY`，查找顺序（派生名 → 全局 `HARNESS_API_KEY`）也不动。
并给**每一次 run 请求**加上 `x-opencode-session: <thread-id>`。密钥一个字不用写——`opencode` 这个名字按
现有派生规则自动得到 `OPENCODE_API_KEY`，查找顺序（派生名 → 全局 `HARNESS_API_KEY`）也不动。

目录条目里的 **endpoint 与模型目录由主人当场给**；拿不准的 model id 与模态用设置面板那条
「Fetch available models」（`POST /api/providers/models`）向厂商要一次，按它自己的话落下来——
**不手抄 id，也不猜模态**（声明得最少就是最诚实的默认，这一点照 `custom-providers` 票 09）。

从用户视角：选择器里多出 opencode 这一家，选了它就能跑；opencode 那头看到的是「这一轮属于哪场会话」。

**Blocked by:** 02

**Status:** ready-for-agent

## 验收

- [ ] `GET /api/choices` 里出现 `opencode` 这一家与它的模型目录；它是**扩展贡献**的，不是 `config.edn`
      里手写的一条（判据：一个隔离家，`config.edn` 里只有 `:ext ["opencode"]`、别的节都没有，
      也看得见它；把 `:ext` 清空就不见了）。
- [ ] 选中 opencode 跑一轮：**每一次** `chat/completions` 请求都带 `x-opencode-session: <thread-id>`，
      包括工具轮的第二次、第三次请求（不是只在第一发上）。用本地假 endpoint 的用例钉住这一点。
- [ ] 头的值是**这次解析所依据的那个 thread-id**。因此：换一场会话 ⇒ 头的值跟着换；
      **子 agent 的请求带的是父会话的 thread-id**（子 agent 的 provider 本来就从父会话解析——
      既有的 THE PROVIDER IS THE PARENT'S, RESOLVED NOW）。这一条要一个用例，因为「一个 run 一个
      thread-id」的直觉会把子 agent 写成它自己的名字。
- [ ] 探询（设置面板的 `GET <base-url>/models`）**不带**这个头。主人定的规则，写成用例，
      写成 `providers.md` 里的一句话——它是一条规则，不是碰巧没带。
- [ ] `provider/init` 审计行如实地记这一家（名字、model、endpoint、模态）；密钥被剥掉这件事照旧成立。
- [ ] 一次真机/脚本走查：选择一个 opencode 的模型、跑一轮、回复正常。
      endpoint 与密钥由主人当场给；走查记录下来（截图或一行文字结论）写进 `spec.md`。
- [ ] 失败要指名：endpoint 不通、密钥缺失、model 不在目录里，三种失败各有一句说得出
      「是 opencode 这一家的哪一步」的话，且 run 的失败路径（`RUN_ERROR` 帧 + 记录行）与既有厂商一致。
