# 03: 把 run 的生效 provider 记成一行日志

**What to build:** 每个 run 生效的 provider 落成一行日志：config.edn 的三字段（`:protocol` / `:base-url` / `:model`）加一个是否走了 override 的标记，**api-key 绝不入行**。目的：02 删掉内存副本之后，「这次 run 到底用了什么」在文件里再无迹可寻——而 config.edn 是运行期可改的，事后无法反推某个历史 run 用的是哪一份。这是把该事实从内存搬成文件事实，不是新增一份副本。

**Blocked by:** 02: 撤掉读自省面 —— 内核不再维护内存副本，prompt.md 定位改「自我扩展」

**Status:** ready-for-agent

- [ ] 每个 run 恰好一行 provider 记录，含 `:protocol` / `:base-url` / `:model` 与 override 标记；断言行中不存在任何 api-key 键（含嵌套）
- [ ] override 生效与不生效两条路径都被覆盖（scripted provider / config.edn 派生）
- [ ] 该行的落点与时序在票面注释与代码注释里写明（落在哪一步、相对 message 行与审计行的先后），且不改变任何既有行的内容与顺序
- [ ] README 与 http 的日志行种类清单同步新增这一种
- [ ] 回放读侧不受影响：`replay/history` 只认 `input` / `event`，新增行不参与重建；既有 replay 与 http 集成测试不改断言通过
- [ ] 离线全量 harness.test-runner 全绿（只增不减）
