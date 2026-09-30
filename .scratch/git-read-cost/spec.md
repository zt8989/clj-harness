# spec: 一次 `/api/git` 读 = 约 1.6s，两条命令各付一次登录 profile

**来源**：`test-suite-performance` 的票 01 做完之后回答「还有可改进空间吗」时量出来的**产品账**
（2026-09-30）。票 01 修的是测试夹具里的同类花费；这一笔不在测试里，在页面要读的东西上。

## 症状（实测）

`dev/scratch_git_read_cost.clj`：对同一个刚建好的小仓库，`harness.cap.git/state` 六次——

```
1616ms / 1744ms / 1609ms / 1649ms / 1647ms / 1626ms
```

## 机制

`state` 是**两条** git 命令：

- `git status --porcelain=v2 --branch` —— 是不是仓库、在哪个分支、脏了几行；
- `git branch --format=%(refname)` —— 分支表（这个格式不带分支列表，所以是第二条）。

两条都经 `harness.infra.shell/run`，也就是 `bash -lc`。本机一次 spawn 约 **0.8s**，其中约七成是登录
profile；`-c` 只要 ~84ms，但仓库记录过它会**破坏超时子进程的回收**（见 `harness.infra.shell` 的注释），
所以 **`-lc` 不许动**——能动的只有「哪些命令需要 shell」。

## 谁付这笔钱

页面打开一个会话的目录时，分支条拿的就是这一次读（`/api/git` 的一次 GET）；切换分支另算（`switch!`）。
即 Windows 上开一个会话的分支条≈1.6s。

## 要定的（所以先 triage）

三条路，代价不同：

1. **短命的 git 读走一条不经 profile 的 spawn**（直接起 `git`，沿用 `shell/run` 的超时与杀树语义）——
   收益最大，但要在 `harness.infra.shell` 里开一条新路径，得说清它和 `-lc` 的边界；
2. **把两条读合成一条**——省一半，但要覆盖两类事实；
3. **判定接受这个价钱**（git 读本来就不频繁）——那就把它写进文档，别留着当惊喜。

动的是**产品行为**，不是测试，所以这张先 triage，不直接派。
