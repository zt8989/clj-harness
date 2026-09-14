# 02 — 内置主流大模型目录

**What to build:** 系统自带一张主流厂商 + model 及其输入/输出模态的表，于是 `config.edn` 只写三个旋钮就能跑
——`providers.edn` 可以不存在，密钥放 `.env` 即可。用户自己的 `providers.edn` 永远压在内置之上：同 provider
逐字段覆盖，`:models` 按 id 合并（内置有、用户没提的 model 存活）。

**Blocked by:** 01（目录形状与解析）。

**Status:** ready-for-agent

- [ ] 新增 `harness.models`：纯数据 + 合并/查找函数，持有内置目录（provider endpoint + models + 模态）。
      `harness.memory` 的解析在**取目录**这一处合并「内置 ∪ 用户文件」，其余解析逻辑不动。
- [ ] 内置只收 **OpenAI 兼容 chat-completions** 的厂商（Anthropic / Google 原生协议不是 OpenAI 兼容，
      内置它们会是错的；它们经 openrouter 到达）。至少覆盖本仓真在用的与主流：openrouter / openai /
      deepseek / xai / groq / ollama（本地、无需 key）。
- [ ] 每个内置 model 带 `:input` / `:output`；**至少有一个纯文本模型**（如 deepseek）用来把「不能收图片」
      这条声明钉在数据里。
- [ ] 内置表带 `:as-of` 日期；model id 逐条对厂商现网列表核对过，不凭记忆写。表的文档串说明它是
      **起步目录**、用户文件永远赢、以及它为何会过时。
- [ ] 缺失 `providers.edn` 仍是**空用户层、不是错误**：一个只写 `{:provider :openrouter :model "…"}` 的
      `config.edn` 在无任何用户文件时解析成功。
- [ ] 命名了一个内置表里没有、用户文件里也没有的 provider → 指名报错并列出已知 provider 名。
- [ ] 有测试钉住 merge 三态：用户新增 provider、用户覆盖内置 provider 的 base-url、用户给内置 provider
      加一个新 model id（且内置的其它 model 仍在）。
- [ ] 离线全量除既有 `bash-runs-git-bash-not-wsl` 外全绿。
