# 01: 一次 `/api/git` 读别付两次登录 profile（本机约 1.6s）

**What to build:** 打开一个会话的目录时，分支条要的那一次读（`harness.cap.git/state`）在 Windows 上从
约 **1.6s** 落到「两条 git 命令本身」的量级——不再为每条命令各付一次 `bash -lc` 的登录 profile。
走哪条路见 `spec.md` 的「要定的」；三条都行，但先定。

**Blocked by:** None (can start immediately)

**Status:** needs-triage

- [ ] 先定方向：不经 profile 的 spawn / 两条读合成一条 / 判定接受这个价钱并写进文档
- [ ] 改动前后用 `dev/scratch_git_read_cost.clj` 在**同一窗口**量（同一个仓库六次），别拿不同时段的数比
- [ ] `-lc` 一个字不动（它是超时回收的一部分，见 `harness.infra.shell`）；改的只能是「哪些命令需要 shell」
- [ ] `cap.git-test` 与 `edge.http-test` 的 git 用例保持绿；超时/杀树的语义有回归用例
