# 05 — 绑定落在 run 中途时，不许和写手抢文件

Status: done
Blocked by: 03（走查里发现的，见票 04）

## 症状（走查逮到的）

「发送才建会话」的第一次发送会 `POST /api/project`，而那一刻 **run 正在写这条会话的日志**。走查第 ⑤ 格
第一次跑出来时，项目下那一行底下挂着一句红字：

```
could not move the log …/projects/.unbound/<id>.jsonl to
…/projects/<sanitized-project>/<id>.jsonl
```

库里那行 `project_id` 是对的（绑成功），日志最后也归位了（写手的「残留段归位」收拾的），
**只有那句话是假的**——一次成功的绑定报了一次失败。再跑几遍未必复现：窗口只有几微秒。

## 为什么

`/api/project` 做两件事：写库改绑定，再把这条会话的日志**搬**到新 workspace（`move-log!`），
因为「一个会话一份文件」是重放/重建/eval 三条路的前提。而写手 `log!` 每一行都问「这条记录该写哪个
文件」——**答案就是那个正在被改的绑定**。旧代码里这两件事的顺序是：

```
log!          : 先问库定文件 f（在锁外），再进锁 append
/api/project  : 先写库（锁外），再进锁搬文件
```

于是有两次真实的坏交错：
1. 写手在锁外定好 `f` = 旧 workspace → 绑定把文件搬走 → 写手把这一行 append 到**旧路径**（重新造出一个
   属于已迁走会话的文件，一段对话被劈开）；
2. 绑定写库之后、搬文件之前，写手按新绑定**创建了目标文件**并写了一行 → `move-log!` 问树时看到
   「这个名字有两份」，于是按名字拒绝（这一次是**真的**劈成了两份，只是被拒绝了）。

第 2 条不是理论：把这条路径做成用例、十二次换绑 + 一个不停的写手，**修之前 3/3 全红**，每一次都是
`this session's log is being moved from … to …, but it already has a log at …`。

## 怎么修

**两种事实共用一把已经存在的锁**（`log-lock`，它本来只管「一行是一个 JSON 对象」）：

- `log!`：**连「这条记录写哪个文件」也在锁内决定**（`log-file-for` 挪进 `locking`）。
- `/api/project`：**读旧绑定、写库、搬文件、写审计行整段在锁内**；审计行本身在锁外（它自己会取锁，
  而且必须在搬完之后才写，落点才对）。

于是次序只剩两种，两种都对：写手先——那一行落在旧文件里，绑定随后把整个文件搬走（那一行跟着走）；
绑定先——写手问库时已经看到新绑定，直接落在新文件里。没有第三种。

**不会死锁**：需要「持库事务再去拿 log-lock」的路径才成环，而 `harness.edge.http` 里没有任何
`with-transaction`，`log!` 是这个 namespace 私有的——锁序只可能是 log-lock → 库。这句话写在
`log-lock` 的 docstring 里，因为下一个动这段的人需要它。

## 验收

- `a-bind-that-arrives-while-the-writer-is-mid-run-leaves-one-file`（`edge/http_test`）：一个写手线程
  写 120 行（`#'http/log!`，与生产同一条路）、主线程**在它写的时候**换绑 12 次（两个目录来回），
  然后断言：最后一次绑定的那个 workspace 里那一份文件**每一行都在、按顺序**，
  另一个 workspace 与 `projects/.unbound/` **什么都不剩**，`replay/logs-for` 只看到 1 份。
  修之前 3/3 红，修之后 3/3 绿。
- 走查第 ⑤ 格加了「那一行底下没有红字」这一条（就是最初逮到它的那一格）。
- 整轮后端套件绿（991 tests / 12175 assertions）。

## 落地

- `edge/http.clj`：`log-lock` 的 docstring 扩成「一行是一行 + 一条记录写哪个文件」两种事实，并写明
  锁序与「为什么不会死锁」；`log!` 把 `log-file-for` 与 `mkdirs` 挪进 `locking`；
  `project-post` 的**读旧绑定 + `bind!` + `move-log!`** 整段进 `locking log-lock`（返回值收成一个
  map，审计行仍在锁外写）。
- `test/harness/edge/http_test.clj`：上面那条用例（这个 namespace 76 tests / 864 assertions →
  **77 / 894**）。
- 文档：`docs/architecture/home-and-storage.md` 新增「一个会话一份文件」一节（搬文件的理由、
  2026-09-18 那次残留段归位、这把锁为什么必须共用）；`docs/architecture/edge.md` 的
  `/api/project` 行补上同一件事。
- **走查那格的红字截图没有留档**（它被修好之后的截图覆盖了），但缺陷的形状留在本文件与那份用例里：
  用例是对着**修之前的源码**跑红的，3/3。
