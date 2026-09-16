# 03 — 写在档位里的目录属性要指名失败，不能被静默丢弃

**What to build:** 这两个数字是目录属性，不是旋钮。今天在 `config.edn` 的档位里、在 `session-configure`
工具调用里、或在一次 run 的请求里写 `:context-window`，会被 `select-keys` 的三个旋钮过滤掉——工具接受它、
审批通过它、什么都不改变，还报告成功。改完以后：写在这三处任何一个，都是指名失败，说清它是目录属性、
该写在哪（provider 目录里那个 model 的条目下）。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] `config.edn` 的档位里出现这两个字段 → 指名失败，点名该字段并说明它属于目录里的 model 条目
- [ ] `session-configure` 工具调用里出现 → 同样指名失败，且**不写配置、不落 `provider/changed` 行**：
      改不动的配置不该成为这个 session 的配置（先解析后写的既有纪律）
- [ ] 一次 run 的请求（`/api/model` 之外的 run 入参）里出现 → 指名失败，不静默丢弃
- [ ] 工具描述与结果保持诚实：三个旋钮仍是 `:provider` / `:model` / `:reasoning-effort`，
      描述里不暗示可以调这两个数字
- [ ] 换 model 仍然只靠 `:model` 一个旋钮：数字跟着 model 走，不需要、也不允许单独覆盖
- [ ] 三种入口各有一条测试，断言的是「指名失败」而不是「被忽略」
- [ ] 离线全量 `harness.test-runner` 全绿
