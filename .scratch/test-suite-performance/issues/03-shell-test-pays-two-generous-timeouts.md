# 03: `infra.shell-test` 的 32s 里，约 20s 是两个固定超时，约 12s 是登录 profile

**What to build:** `harness.infra.shell-test` 改动后仍是 **32.3s**，而它只有 20 个 deftest。
实测拆开（从这家的代码与两次量出来的数）：

| 花在哪 | 约 | 为什么 |
|---|---|---|
| 两个固定超时 | **20s** | `:timeout-ms 12000`（子进程永不结束，等满）+ `:timeout-ms 8000`（`sleep 30`，等满） |
| Git Bash 登录 profile | **12s** | 每次 `bash -lc` 约 **850ms**（`-c` 只 84ms），约 14 次真实 spawn |

**这两笔的性质完全不同，别一起处理：**

- **登录 profile 是产品的行为，不许改。** `-c` 看起来白捡 700ms/spawn，但仓库记录过它**破坏超时
  子进程的回收**（`a-command-that-does-not-finish...` 之下那段注释，2026-09-20 实测）。要动只能动
  **测试里 spawn 的次数**（例如把 `the-shapes-a-command-is-built-out-of...` 那四次独立 spawn 合成
  一次能分辨各段输出的调用），不是动 shell 本身。
- **固定超时是余量问题，而余量不是随便缩的。** 那两个数是为「清过登录 profile 还有时间把 node 起
  来」留的（注释里写的是 2.2s 那次测量）；本机现在只有 850ms。缩之前要**先量这台机器上 profile +
  子进程启动的真实下界**，并且缩到一个仍然明显高于它的数——不然就是把 `shell-test` 变成
  「机器慢就红」的那一家，而那正是 `docs/rules/testing.md` 反复警告的事。

**Status:** needs-triage

- [ ] 这一家每条 deftest 的耗时（20 条，列出超过 1s 的）
- [ ] 测试里真实 spawn 的次数，以及其中哪些**不是**这一条用例在断言的东西
- [ ] 合并 spawn 能省多少（实测，不是估算）
- [ ] 「profile + 起 node」在这台机器上的下界，以及据此能给两个超时留多少余量
- [ ] 决定：做哪一半、留多少余量，并写进 spec
