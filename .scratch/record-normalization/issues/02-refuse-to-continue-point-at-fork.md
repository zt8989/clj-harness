# 02: 后端拒绝续跑，并指向 fork

**What to build:** 所有「让这场会话继续」的门——续跑（`POST /api/agent` 对已有会话）、resume、
compact、以及任何会写这份记录的路径——遇到**未重整化**的会话一律**拒绝**，拒绝语里带上
「先 fork 重整化」以及 fork 那条门的地址。**只读的门照旧**（能看）。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 续跑 / resume / compact 各有一条用例：未重整化 → 拒绝 + 指向 fork
- [ ] 只读的门（`sofar`/`trajectory`/`stats`/`page`/`frames`）不受影响
- [ ] 已重整化的会话一个字节的行为都不变（既有用例全绿）
- [ ] 拒绝是**一条句子**（主人的口吻：说清下一步做什么），不是一串错误码
