# 证据：注入物落在记录的哪一半，轨迹又画成什么

四份东西，前三份都能重跑：

| 文件 | 是什么 |
|---|---|
| `injection-once.clj` → `injection-once.txt` | 真 HTTP 边上跑一条两轮的会话（第一轮是 `/alpha fix the bug`），把这条会话的 `message` 行按 **submitted 侧 / returned 侧** 打出来，再用 `curl` 从外面问一次轨迹端点 |
| `old-log.clj` → `old-log.txt` | 折一条**旧构建**写的真会话，证明读侧**不追溯** |
| `walkthrough.sh` + `t03-01-opening-once.png`、`t03-02-injection-turn.png` | 真机走查：脚本化后端 + UI 的 dev server，浏览器里两轮 + 切到轨迹页，两张截图 |

```
clojure -M:test -e '(load-file ".scratch/trajectory-injection-once/evidence/injection-once.clj")'
clojure -M:test -e '(load-file ".scratch/trajectory-injection-once/evidence/old-log.clj")'
```

两个脚本都**不碰**真实的 `~/.clj-harness`、`~/.agents/skills`，也不用 api-key：
家目录是 `harness.test-runner/isolate!` 给的两个临时目录（配置根与 OS 家互相为兄弟），
厂商是 `harness.fake` 的脚本替身（`AGENTS.md` 的家目录纪律）。

## 它证明了什么

1. **`/<名字>` 的正文在 submitted 侧**——第一次模型调用真正收到的那一份。run 1 与 run 2 都在里面；
   returned 侧只有内核自己的回复（run 1：一条 `assistant first`；run 2：一条 `assistant second`）。
2. **客户端自己那条消息不再被顶进 returned 侧**。旧代码里 returned 的切片被注入顶偏一格，
   `and another thing`（客户端自己的话）会落进 returned，于是被画成「注入的 context」。
3. **同一个端点折出来的是**：turn 1 = `system` + 开场块（技能清单）+ 用户 + `run` 正文 + `assistant`；
   **turn 2 = 用户 + assistant**（2 条）。同一段文本不重画，也没有凭空多出来的东西。
4. **旧日志不追溯**：`80de94f6…`（本仓一条真会话，旧构建写的）折出来仍旧是旧记录的样子
   ——turn 2 里那条 `103 需要 02 需要 04 拆出来 ` 还在。读侧不猜；把一条错位的记录「修对」就是自造。
5. **真机那两张**：turn 1 有 5 条（`system` / `context <skills>` / `user` / `context <skill name="alpha">` /
   `assistant`），turn 2 **只有 2 条**；点开那条 `context` 的面板里是 `injected: during the run` 与正文
   （`ALPHA BODY`）。

## 它没有证明什么（如实写在这里）

- **没有跑活厂商**：脚本替身，同 `AGENTS.md` 的家目录纪律。真厂商那一半由仓库里录下来的响应
  （`test/harness/fixtures/`）与 `llm_test` 断言。
- **走查不是在契约端口上跑的**。`ui/src/lib/threads.ts` 把 `AGENT_URL` 写死成 `:8080`，
  edge 的 CORS 只放行 `http://localhost:5173`——而写这份证据的那台机器上这两个端口都被一套**正在用**的
  harness 占着。所以 `walkthrough.sh` 换到 `:8124`（后端，CORS 用 `alter-var-root` 改成放行 `:5199`）与
  `:5199`（vite），并在跑完前把 `AGENT_URL` 那一行**改回来**——那一次改动没有进任何提交。
  端口空着时也可以直接用 8080/5173，脚本里三处换掉即可。
- **截图只有两张，且是脚本厂商的一轮会话**：它证明的是「轨迹画出来了什么」，不是「模型读了会不会照着做」。
