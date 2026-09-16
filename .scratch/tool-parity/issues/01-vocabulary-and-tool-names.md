# 01 — 词与名：四个工具名与「任务清单」

**What to build:** 后面六张票的标题、测试名、工具 `:name`、前端两张表的键都要用到四个名字，而
`CONTEXT.md` 那条"工具名的写法"是**唯一**说名字的地方。先立它，是**先做 prefactor**：晚一步就是一次
跨票改名扫描，而 `CONTEXT.md` 明文写着"不给它们起别名"。

从用户视角：读代码的人（和写代码的 agent）不会在 `webSearch` / `web_search` / `search` 之间挑一个，
也不会把"任务清单"与"待办"、"todo 列表"混着写；四个名字定了就定了，别名是要避免的，不是要宽容的。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

## 验收

- [ ] `CONTEXT.md` 的"工具名的写法"那一行加上 `glob` / `todo_write` / `web_fetch` / `web_search`
      ——同一条纪律：就是这些名字，不给别名。
- [ ] `CONTEXT.md` 加「**任务清单**」：本会话的待办，**整份替换**（模型每次送完整清单，不是增量），
      所以它是**状态**而不是记录，落 `harness.db`。带上它对着的英文与**别叫成**那一行
      （`TODO list` / 待办 / task list 都要挡）。
- [ ] 「库装状态、文件装记录」那一段补一句：`todos` 是这张库里又一件状态，
      判据同前（能被整份改写的是状态）。
- [ ] 三个名字的**词形**与本仓既有写法一致并写下来：小写、多词用下划线（`undo_last_replace` 是既有先例），
      不说 `camelCase`、不说 `PascalCase`——参考图里的 `WebFetch` / `TodoWrite` 是那张图的写法，不是本仓的。
- [ ] **一行代码都不改**：本票只动 `CONTEXT.md`。
- [ ] `clojure -M:test -m harness.test-runner` 不比基线更差（基线见 `spec.md` 的"状态"：
      `main` @ `3ac23d8`，607 tests / 9774 assertions / 2 failures，两条都是本机 JDK 25 的环境失败）。
