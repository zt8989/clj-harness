# 回归判定：subagents（同一台机器，两份工作树对跑）

固定点 `a3847ea`。左边是本分支（`workbuddy/main-616bcab8`），右边是
`git worktree add --detach C:/Users/zhouteng/AppData/Local/Temp/lh-baseline HEAD` 的干净检出。
两边跑的是同一个 `node scripts/test.mjs`。

**比的是失败的名字，不是条数。** 条数在这台机器上没法读：`layers_test` 一条用例就贡献 101 次失败
（Windows 的 `\` 与命名空间路径），`glob_test` 的失败则是**按墙钟**出来的。名字集合才回答"谁坏了"。

## 两份数据

| 跑法 | 本分支 | 干净基线 |
|---|---|---|
| 全量 `test.mjs --backend` | 937 tests / 11708 assertions / **177 failures, 20 errors** | 903 tests / 11528 assertions / **169 failures, 21 errors** |
| 全量（先跑的那一轮，机器上同时有两套 JVM） | 937 / 169 FAIL, 20 ERR | 903 / 169 FAIL, 21 ERR |

全量那两条腿（`--build` type gate + bundle、`--ui` 套件）**两边都是 `:: ok`**：本分支 UI 50/50 过，
含 subagents 那六条。后端这条腿两边都红——这是**这台 Windows 机器上的预存篮子**。

`--backend` 单独重跑给的是 169 failures，全量给的是 177，差 8 条全在 `glob_test`——见下。

## 名字集合做差（拿全量那一轮对）

```bash
node .scratch/subagents/evidence/extract-failures.mjs <run.log> > <out.names>
LC_ALL=C sort -o <out.names> <out.names>
LC_ALL=C comm -23 mine.names baseline.names   # 只在我这边失败 → 候选回归
```

`comm` 在 Windows 上要 `LC_ALL=C`，否则它嫌 locale 排序"不是排好的"。也**不要用进程替换**
（`<(sort …)`）——这个壳里的 `comm.exe` 打不开 `/dev/fd/63`。

只在我这边失败的，四族，逐族查清：

### 1. 行号挪了位，同一条

`ERROR the-fence-lets-a-skill-reach-its-own-reference-files`：我这边 `project.clj:522`，基线
`project.clj:469`。**断言文字、异常、原因逐字相同**：

```
clojure.lang.ExceptionInfo: harness.edn is not valid EDN: …\.harness\harness.edn
(Unsupported escape character: \U)
```

我给 `project.clj` 加了 63 行，行号跟着走。**不是回归。**

### 2. 进程树时序（jobs）

`ERROR a-command-that-does-not-finish-is-stopped-together-with-what-it-started @ FileInputStream.java:-2`，
原因是 `java.io.FileNotFoundException: …\clj-harness-shell-tree-…\child.pid`——子进程还没把那个文件
写出来就去读了。基线的 11 条同族错误（`a-job-outlives-the-call-that-started-it` 等）我这边**一条不少
地全有**，我这边只多出这一条；哪几条撞上按那时机器多忙而定。

**判据是单跑**：`harness.infra.shell-test` 无其它负载时，**两份树各跑两遍、四次完全一样**
（4 failures + 1 error，行号相同）。两边都坏 ⇒ 与代码无关。

### 3. ripgrep 的 10 秒墙钟（glob）

基线全量 7 条 `glob_test` 失败，我这边全量 16 条。多出来的 9 条，`actual` 全是**同一个字符串**：

```
the search did not finish within 10000ms and was stopped.
Narrow it: give a `path` or a `glob`, or search for a plainer pattern.
```

这是墙钟超时，不是判定错。**判据同样是单跑**：`harness.cap.glob-test` 无负载时两份树各跑一遍，
**7 failures、行号逐条相同**（`glob_test.clj` 的 116 / 119 / 129 / 171 / 173 / 184 / 203）。基线全量
那一轮恰好只中这 7 条，我这边那一轮多中了 9 条——纯粹看当时机器多忙。改动不碰搜索这一层。

### 4. 基线自己飘的（反方向）

`the-endpoint-answers-the-context-section-over-real-http @ Numbers.java:1099 / context_test.clj:339,340`、
`bash-runs-the-hosts-own-shell-not-wsl @ tools_test.clj:155`、
`the-git-endpoint-reads-and-moves-the-sessions-working-tree @ http_test.clj:3214`——只在基线那一轮出现。
反向的同一件事，不解释。

## 唯一的真回归，且已修

`test/harness/edge/http_test.clj:375`。本特性在 `SystemPrompt` 这个点上多声明了一行（子agent 的那个
块），"匹配到几条声明"从 **3** 变 **4**；用例把这个数钉死了。改成 4 并写了注释——那个块在普通线程上
产出的是**空**内容，`join-blocks` 会丢掉，所以上面那些关于消息文字的断言不受影响。改完单跑该命名空间：

```
=== backend (harness.edge.http-test) :: ok
Ran 64 tests containing 708 assertions.
0 failures, 0 errors.
```

## 复现

```bash
node .scratch/subagents/evidence/extract-failures.mjs <run.log> > <out.names>
node scripts/test.mjs --backend --ns harness.infra.shell-test   # 两份树各跑一次
node scripts/test.mjs --backend --ns harness.cap.glob-test      # 两份树各跑一次
node scripts/test.mjs --backend --ns harness.edge.http-test     # 改完应为 0 失败
```

同目录 `mine2.names` / `baseline.names` 是两份全量日志的名字集合；原始日志在 `../runs/`
（`full.txt`、`backend-mine-2.txt`、`backend-baseline.txt`、`shell-iso-*.txt`、`glob-iso-*.txt`）。

## 一条方法论

**单跑一个命名空间时"两份树都坏"才是判据。** 全量那一轮谁过谁挂会飘（并发、机器的忙闲、墙钟），
不足以定罪；反过来也一样——"基线过了"不代表那条用例在我这边坏就是回归。要定罪或洗清，就把那个
命名空间单拎出来、两份树各跑，比名字与行号。
