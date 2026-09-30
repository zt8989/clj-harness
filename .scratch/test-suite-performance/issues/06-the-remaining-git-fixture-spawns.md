# 06: 还剩两处 git 夹具按八次 spawn 建仓库

**What to build:** 票 01 把 `edge/http_test.clj` 的 `init-repo!` 从八次 `shell/run` 合成一条 `&&` 链
（一个仓库约省 5.4s，同窗口实测见 spec 的票 01 一节）。同一个形状还剩两处，都是「真的 git 命令，但
付了不必要的登录 profile」：

- `test/harness/cap/git_test.clj` 的 `build-repo!`：八次 spawn，每个 namespace 一次，约 **6s**（那家
  整只 45.8s，票 01 那次全量里量的）；
- `test/harness/edge/http_test.clj` 的两条 git 用例**一共建三个仓库**。`cap/git_test.clj` 已经有
  「建一个模板、其余拷贝」的写法（`template-root` / `copy-tree!` / `scratch-repo`），而一个仓库现在约
  1.6–2.2s、拷贝一个微小的 `.git` 树是毫秒级 ⇒ 把其中两个「建」换成「拷贝」，约再省 **3s**。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `cap/git_test.clj` 的 `build-repo!` 编成一条 `&&` 链：步骤仍是可枚举的 vector，链的退出码要查
      （`&&` 遇错就停，半建好的仓库会让下面用例为一个与它无关的理由红）
- [ ] `edge/http_test.clj` 的 git 夹具改成「一个模板 + 拷贝」，两条用例的三个仓库里两个走拷贝
- [ ] 量法照票 01：同一窗口交替量（`dev/scratch_git_fixture_cost.clj` 的路子），不要拿不同时段的全量
      墙钟比——那台机器上整轮会差 ±15–20%，是负载
- [ ] `cap.git-test` 与 `edge.http-test` 全绿；全量用例数/断言数/失败集与改动前逐条相同
