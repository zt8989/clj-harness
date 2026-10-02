# goal 的走查现场

`.scratch/goal` 票 09 的第三部分：跑起来看一遍，把现场留在这里。**机器门挡不住「渲染出来看不见」那一格**
（2026-09-18 那次 900 多条全绿而侧栏标题全是空的就是这一格），所以这一趟是人开着浏览器走的。

## 一、机器门（在这棵树上跑过的原话）

| 门 | 命令 | 结果 |
|---|---|---|
| 后端 | `clojure -M:test -m harness.test-runner` | 全部命名空间，0 失败 0 错误（见下面「跑过的记录」） |
| 前端单测 | `cd ui && npm test` | 225 通过（含本次新增的 `goal` 那一套 10 条） |
| 前端类型 | `cd ui && npm run typecheck` | 干净 |
| 前端构建 | `cd ui && npm run build` | 干净（只剩 vite 那条既有的 chunk 体积提示） |
| 端到端 | `node scripts/dev.mjs --scripted` | 见下 |

后端这一轮新增的用例分三处：`harness.cap.goal-test`（记录与 fold、栅栏、相位、armed、提醒、三个工具）、
`harness.edge.goal-http-test`（`GET …/goal`、`goal` 帧、命令执行、命令队列、驱动器的四条闸与零进展刹车、
三处字段名同一套）、`harness.edge.http-test` 里那条**真 run** 的驱动器用例（见下）。

## 二、驱动器的端到端（票 06 要求的那一条）

`harness.edge.http-test/a-run-that-changed-a-file-opens-the-next-round-and-then-the-brake-stops-it`
跑一场真 run（脚本 provider）：第一轮里模型 `write` 一个文件（就是驱动器的进展判据），跑完以后
**驱动器自己把 round 2 开了起来**——记录里多出一条真的 user 消息（`round 1/…` 那一句），`rounds` 也在库里；
紧接着的那一轮什么文件都没改，于是目标被标成 `blocked`（`no-progress`）并停下。

**这一条是这套里唯一必须真跑 run 才能验的**：驱动器是在 `:run/done` 那一刻决定的，别的东西造不出这个事实。
时间与并发都不用时钟、不用 sleep——用例只等「记录里出现了那条消息」这一个事实（`wait-for-recorded` 有界轮询，
与这个文件里既有的用例同一条路）。

## 三、浏览器走查（`node scripts/dev.mjs --scripted`，2026-10-02）

真浏览器，真页面（`ui/dist` 由后端自己发）。走的是票 09 那六步，其中五步**当场过了**，并当场抓出并修掉三个 bug：

1. `/goal 把登录模块重构完，补齐测试和迁移说明` → 目标条长出来，文字逐字对；jsonl 里多一条 `goal/change`
   行，**对话里没有这条 user 消息**（命令不是提问）。`/goal clear` → 目标条消失。
2. 发一句话 → 对话栏那张注入卡上看得见 `<goal revision=…>` 提醒（提示模型「用 get_goal 看清现状」那一句）。
3. 普通消息的脚本回放照旧工作（无回归）。

**当场抓出的三处（都已修，并重新走过）：**

- **命令没有栅栏**：`/goal resume|pause|edit|clear` 都被「目标已移动」拒绝——输入框那条命令没有带上
  页面上正看着的 `{goal_id, revision}`。修法：目标条把「这一页正显示哪一份」发布出来，发送那一缝照抄它
  （`ui/src/lib/goal.ts` 的 `goalShown` / `lib/agent.ts` 的 `fence`）。
- **命令消息重发**：一条 `/goal …` 从没进过服务端，于是它一直留在客户端「还没送出去的尾部」，下一次发送
  又把它带上——同一个命令被下两次。修法：发送那一缝记下「这条已经被折成命令送走了」。
- **`/goal` 单打被技能菜单吃掉**：打 `/goal` 回车，被 `/` 菜单拦下（它把保留名也当成技能在选），
  只有点发送按钮才出得去。修法：技能那条匹配器拒绝保留名 `goal`。

**没有走到的**：驱动器那两步（改文件的一轮自动续下一轮、零进展标 blocked）**不在浏览器里走**——
脚本 provider 的轮次用完以后会答一条空消息，靠它去摆弄「这一轮改没改文件」是在测夹具而不是测功能；
这两步由上面那条真 run 的集成用例覆盖。

## 四、跑过的记录

- `clojure -M:test -m harness.test-runner`（全量）：见本次收尾时的那一轮输出（0 失败 0 错误）。
- 走查用的六步现场截图/页面输出没有留在本机（这一趟是交互式浏览器，不是脚本），所以本节写下的是
  **观察到的事实与三处修复**，而 `.scratch/goal/walkthrough.mjs` 是把这六步写成脚本的版本，
  留给有 Playwright 的环境重跑。
