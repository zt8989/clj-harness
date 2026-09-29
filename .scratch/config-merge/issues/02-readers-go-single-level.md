# 02 — 读取侧改读新段，项目级那一档去掉

**What to build:** `harness.cap.project` 的 `harness-edn-levels` / `harness-config` 改成从 config.edn 的
`:session` 读**一级**；`.harness/harness.edn` 从此没有任何人读。`cap.editing` / `edge.compaction` /
`edge.llm-timeout` 那些「两级逐键合成」的机械随之退化成一次读。

**Blocked by:** 01

**Status:** ready-for-agent

## 验收

- [ ] `harness-config` 答的就是 `providers/session-config`（缺段 = `{}`），**不再有 `:project` 这一级**
- [ ] `harness-edn-levels` 要么删掉、要么改成单级形状；**不许**留一个永远返回 `{}` 的 project 档
      （那会让下一个人以为两级还在）
- [ ] `.harness/harness.edn` 里写什么都**不影响任何会话**：写了不报错，只是没人读（一条测试钉住）
- [ ] `cap.editing` 的逐键合成对**一级**仍然成立：`:editing {:editing-mode ...}` 那套「缺键取默认、
      错键按名字失败、文件里写着错形状按名字失败」的证据一条不少
- [ ] `edge.compaction/config`、`edge.llm-timeout` 的值与今天的**两级合成结果**逐字节相同（把老的家
      的 user 级内容搬进 `:session` 之后）
- [ ] 围栏那几条测试（`project-test` / `system-prompt-test`）**逐字节全绿**：`:session :approval` 与
      今天的 `:approval` 对围栏的意思一模一样
- [ ] 所有失败句子里说「去哪个文件、写什么」的地方，从 `harness.edn` 改成 `config.edn` 的 `:session`
      （`infra.home` 的 `config-files` 那句也在内）

## 不做

- 不动 `:skills` / `:instructions` 的路径解析（它们只拿值）。
- 不碰 `hooks.edn`。
