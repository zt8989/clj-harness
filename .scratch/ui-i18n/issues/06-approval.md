# 06 — 审批与提问

**What to build:** 从用户视角：切成中文后，一次挂起的 run 上那张卡片说中文——标题、两个按钮
（批准 / 拒绝）、已经决定之后的各态、以及「消息框会一直关着直到这里被决定」那句说明；
MCP 服务器反过来提问时，那张提问卡自己的话也是中文。

**Blocked by:** 01 — 机制。本票不依赖 02。

**Status:** ready-for-agent

## 验收

- [ ] `approval-gate`（约 14 条）进目录（`approval` namespace）。
- [ ] **话术本身来自后端**（`` Approve `read`? arguments: … `` 与 elicitation 的
      `A server is asking you for input.`），按 spec 决策 3 **原样穿过**；本票只翻卡片自己的话与
      按钮，不改后端一个字节。服务端给了 label 的选项用服务端的 label，本侧那张默认标签表才进目录。
- [ ] elicitation 表单里的**字段名、类型、说明来自 MCP 服务器的 schema**，原样穿过——本票不动它们。
- [ ] 真机：中文下走一次审批（批一次、拒一次）与一次 elicitation 提问（服务端那个 Playwright MCP
      能回答的形态即可）。截图进 `evidence/`。
- [ ] `cd ui && npm run typecheck && cd ui && npm test` 全绿。
