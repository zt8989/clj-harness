# 01: 运行状态落 SQLite

**What to build:** 进程重启之后,侧栏对「这个会话还在跑吗」仍然有一个可信的答案。目前 `running` 是侧栏 listing 里唯一不是 store 事实的字段——它读自进程内的注册表(`harness.kernel/session` 的 `:runs` pin),进程一死注册表就没了。本票把「上次已知运行状态」写进 SQLite:`sessions` 表加 `run_state` 列(迁移),`run-started!` / `run-finished!` 在同一事务里写列;`GET /api/projects` 的 `:running` 改为从 store 读。进程启动时把上一进程遗留的 `running` 清回 `idle`(启动清理),读取侧因此不需要「超时猜测」逻辑。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] `harness.infra.db` 迁移新增 `sessions.run_state` 列,迁移带 docstring 说明「上次已知状态,不是现在进行时」的语义
- [ ] `run-started!` / `run-finished!` 与注册表 pin 在同一事务/同一变更点写列,两者不能各说各话
- [ ] 进程启动时有一个一次性的清理:把所有 `run_state='running'` 的行清回 `idle`,并钉住测试
- [ ] `GET /api/projects`(及 `projects-body`)的 `:running` 从 store 读;注册表仍负责本进程的实时性,二者的一致性有测试钉住
- [ ] 测试隔离照旧走 `harness.test-runner/isolate!`,不手设 `CLJ_HARNESS_HOME`
