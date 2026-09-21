# 01 — 开场块随开场进会话，ordinary run 不再追加

**What to build:** 开场块（指令文件 + 技能清单）在**会话开场**那一刻写进对话（`role=user`，
固定 id，和出生 context 同一条路），此后作为历史的一部分被每轮继续。`edge/http.clj` 的
`opening-blocks!` 只在 `born?` 那一支被调用；`edge/ag_ui.clj` 的 `tail-blocks` 删掉，
`inbound` 不再收 `blocks`。人看的那面（会话栏里的注入卡片）不变：条目同时带 `data` 与 `text`
两个 part，`sessions/without-cards` 只摘 `data`。

**Blocked by:** 无

**Status:** ready-for-agent

## 要落地的判断

1. **新函数 `ag_ui/opening-entries`**（放在 `context-entry` 旁边，同一条出生路径）：
   BLOCKS -> 会话条目。每条：

   ```clojure
   {:id      "session-opening-0"            ; 固定、由边铸、位置决定顺序
    :role    "user"
    :content [{:type "data" :name "injected-context"
               :data {:role "user" :text "<instructions path=\"…\">…</instructions>"}}
              {:type "text" :text "<instructions path=\"…\">…</instructions>"}]}
   ```

   - **id 固定**的理由与 `context-entry` 逐字相同：`sessions/append!` 按 `:id` 去重，
     所以"出生两次"只留一份开场。编号从 0 起，顺序是 `cap.preamble/messages` 的决定
     （指令文件在前、技能清单最后），这里只编号，不重排。
   - **两个 part 是刻意的**：`data` 那半是页面画的卡片（今天由 run 开始时的帧发，改成随条目走），
     `text` 那半是模型读的。`without-cards` 今天就是「去掉 `data`、其余留下，什么都不剩才丢整条」，
     所以模型侧不需要新代码；`provider-part` 永远看不到 `data`（它只拒绝没见过的 part 类型）。
   - 卡片里的 `:role` 是 `"user"`，与消息自己的 role 一致（`injected-frame` 的 `:value` 同形）。

2. **`http.clj` 的出生支路**：

   ```clojure
   history  (sessions/messages thread-id)
   born?    (empty? history)
   opening  (when born? (ag/opening-entries (opening-blocks! thread-id)))
   entries  (cond-> (into (vec opening) (:append input))
              born? (into (when-some [e (ag/context-entry (:context input))] [e])))
   ```

   顺序 = `开场块… → 这次的提问… → 出生 context`（context 保持今天的位置不动，别顺手挪）。
3. **hook 的绑定要跟着搬。** `opening-blocks!` 会为每个折进来的文件发 `InstructionsLoaded`，
   它必须在 `hook/*sink*` 绑定里跑（`cap/system_prompt.clj:87` 那条要求）。出生那一刻在
   `async/go` 之前，所以把 sink 抽成一个私有函数（`sink-for thread-id run-id`，
   今天那一坨字面量在 `http.clj` 的 go 块里），出生支路自己 `binding` 一次、go 块里复用它。
   不要在 `go` 外面 `binding` 了指望它穿进 go 块——那是另一个线程。
4. **run 的组装少一样东西。** `let [[provider messages decisions resolved blocks injected] …]`
   里 `blocks` 整个退出：`(ag/inbound (into history added) (system-prompt/assemble thread-id))`
   （`inbound` 回到「system + 对话」两件事）。`opening-blocks!` 的 docstring 要改：
   它不再"每轮现读"，它是**开场的**素材。
5. **run 开始的卡片帧只剩 `injected`**：`(into (vec blocks) injected)` -> `injected`。
   开场块的卡片已经随条目进了会话（`input` 行的 `:added` 是它们的来源），重建折得回来，
   不需要再发一次帧——今天每轮重发一次，正是"开场看起来发生了 N 次"的来源
   （`.scratch/trajectory-injection-once` 在显示那一侧修过；这里修的是它重复的源头）。
6. **文档三处必须跟着改**（不改就是三份撒谎的说明）：
   - `src/harness/edge/sessions.clj` 头部：「指令文件每轮现读、改了立刻生效」那段，
     改成「开场块随开场进会话一次；**会变**的注入（技能正文、作业结尾）仍然每轮派生、不进会话」，
     并把代价写明（改 AGENTS.md 下一次开场才生效）。
   - `src/harness/cap/preamble.clj` 的 `messages`：「a run opens with」->「the conversation opens with」，
     删掉指向 `tail-blocks` 的那句。
   - `docs/architecture/skills-and-instructions.md`：顶部那张形状图、以及「2026-09-18 起注入物排在
     提问之后」那两段。要写成**两类注入、两条规矩**：随开场进前缀的不变之物（指令文件、技能清单）
     与留在尾部、前缀之外的会变之物（技能正文、作业结尾）。再补一节压缩的约束（见 spec）。
7. **不碰 `ui/src/`。** 卡片的渲染路径一个字不改；`ui/test/suites/injections.ts` 加一条
   「同一条消息带 `data` + `text` 时 `keepInjectionCards` 只换回卡片」的算术用例即可。

## 判据

- `harness.edge.http-test`：一场有 AGENTS.md 的会话，第一轮的 `:added` 里开场块在提问**之前**、
  id 是 `session-opening-0…`；第二轮提交侧**没有**新的开场块，而历史里那一份仍在原位。
  run 开始时的 `injected-context` 帧**只在第一轮**出现。
- `harness.edge.ag_ui-test`：`opening-entries` 的形状（固定 id、两个 part、顺序不动）；
  `inbound` 折出来的向量里**没有** `data` part，开场块的文本在提问之前。
- 重建（`harness.edge.replay`）：同一个会话折叠回来，开场条目同名同序，`display` 有卡片、
  `messages` 里是文本。
- 全量：`clojure -M:test -m harness.test-runner` 绿；`cd ui && npm test` 绿。
