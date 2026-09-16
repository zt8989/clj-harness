# 01 — 声明的来源与运行缝

**What to build:** hook 的声明今天只有两种来源（`hooks.edn` 里的命令、会话加的命令），跑的只有一种东西（命令）。
这一票补上第三格：**内核自己注册的行**——同一张表、同一条缝、同一套退出码与审计行，只是跑的是一个进程内的函数。

看得见的结果：`effective-hooks` 里出现内核自己的 hook（`:source :built-in`），会话能像关掉别的 hook 一样关掉它，
也能再打开；`eval` 能往会话里塞一条进程内的 hook；而 `hooks.edn` 里写函数被**指名拒绝**——文件里放不了函数，
拒绝的理由要说出来，不是静默忽略。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

## 验收

- [ ] 一条声明**说它跑什么**：恰好有 `:command`（非空字符串）或 `:run`（可调用）之一；
      两个都没有、两个都给 → 指名报错，说清该给哪一个（与既有的逐字段校验同一套口吻）
- [ ] `hooks.edn` 里出现 `:run` → **指名拒绝**并说清理由（文件里放不了函数）；`session-add!` 两者都收
- [ ] 三个来源：`:built-in` / `:config` / `:session`；**先后由来源档位决定**（内建 → 文件 → 会话），
      同一来源内按各自的顺序；今天 id 的拼法（文件 `#0`、会话 `@1`）**不变**，内建的 id 一眼看得出是内建的且叫得出名字
- [ ] 运行缝把一条声明变成**与 `shell/run` 同一个形状**的 run：`{:exit :out :err :timeout}`。
      进程内的 hook 直接返回这个 map；它抛异常 = 起不来（`{:exit nil :err <消息>}`）。
      `verdict-of` 之下的东西一个字都不改：退出码语义、`:on-error`、first-block-wins、审计行
- [ ] 进程内的 hook 收到 payload 的 **map**（与 shell 那侧 stdin JSON 同一批键、保类型）——不为了「统一」绕一圈 JSON
- [ ] 会话能 `session-disable!` 一条**内建** hook（关闭不是隐藏：仍在 `effective-hooks` 里、只是不跑），
      `session-enable!` 打开；`session-remove!` 仍只收得回本会话加的
- [ ] 演示（写成一个用例，不是手跑）：真实工具调用上，`eval` 加一条 `:pre-tool-use` 的**进程内**门禁，
      它退出 2 → 调用不执行、stderr 逐字回喂模型、run 继续；关掉之后不再拦
- [ ] 跨会话隔离不变：另一个 thread 看不见也改不动
- [ ] **行为零变化**：没有任何内建声明、没有任何新声明时，任何点的任何调用与今天逐字节相同（既有审计行也一样）
- [ ] `docs/architecture.md` 的「在办」加一行指向本 spec（05 落地后撤掉）
- [ ] `clojure -M:test -m harness.test-runner` 全绿（基线 `main` @ `0ef17a9`，344 / 1933）
