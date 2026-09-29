# 18 — `ctx.mcp` 接缝

**What to build:** 外部服务器作为工具来源是一条缝：**定义**（连接 / 列举 / 调用 / elicitation）、
**实现**（stdio 子进程与 HTTP 两种 transport）、**消费**（`ctx.tools` 那条动态来源）。

**Blocked by:** 06 — `ctx.tools`

**Status:** ready-for-agent

## 验收

- [ ] 两种 transport 都跑通：真子进程一条、假 HTTP 一条
- [ ] elicitation 仍复用审批那条 park/resume 通道，**不新开一条**
- [ ] 缓存键（项目身份 × server × 声明形状）不变
- [ ] **换一个 transport 实现**，工具表照旧——接缝成立的反证
- [ ] 会话级启停照旧；连不上时报出是哪一份 `mcp.edn`、哪一个 server
- [ ] 桥过来的工具名形状（`mcp__<server>__<tool>`）不变
