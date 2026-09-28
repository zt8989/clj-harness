# 05: 两处红改读字段

**What to build:** `.scratch/record-envelopes` 留下的两处红，都因为读者在**猜**而红，现在改成**读字段**：

1. **轨迹的「哪一侧」**：今天按位置猜（run 的第一个 `event` 之前 / 之后），于是 resume 那种
   「先答后提交」被判到提交侧。改成读这一行的 `:producer`（谁写的），并保留「途中注入落在它落地
   的那一侧」这条性质。
2. **pressure 的锚点前缀**：锚点是「这次调用赖以发送的前缀」，改成读同一批 item（这次调用的请求
   由哪些行拼成），于是活表与折法不再各说各话。

**Blocked by:** 04

**Status:** ready-for-agent

- [ ] resume 那一轮的返回侧与今天 main 读出来的一样（那三条断言变绿）
- [ ] 一次途中注入仍然落在它落地的那个 run 的返回侧；调用前的注入仍然在提交侧
- [ ] pressure 的活表与记录折法相等（那一条断言变绿），且没有改判据以外的行为

## 一次尝试的记录（2026-09-28，**未落地**，留给下一位）

`harness.edge.trajectory` 里判「哪一侧」的那处（`(= "message" …)` 分支）改成读 `:producer`
是**对的**，但**只改它一处会让别的用例变红**：`harness.edge.trajectory-test` 的
`a-later-turn-is-pushed-on-the-open-stream` 会超时（HEAD 上 29/125/0 绿，改完就红，
连跑两次都一样；回退即绿）。所以「侧别」与**分段 / 推送**之间还有别的耦合，得一起看清楚再动。

两次尝试的两个教训（都踩过）：

1. **别做括号手术**。那段 `cond` 的括号很紧（`.scratch/record-envelopes` 时期就吃过一次）；
   正确做法是**只改条件、一个括号都不动**——把 `(if (:streaming current) …)` 变成
   `(if (not (if-some [producer (:producer record)] (= "request" producer) (not (:streaming current)))) …)`，
   语义相同、括号不变。反过来动了括号会**误闭合 `cond`**：文件照样读得过、编译得过，
   后面的分支却变成死代码（`compiles but wrong`），只有用例抓得到。
2. **改完立刻跑 `harness.edge.trajectory-test` 全量**，不要只看那三条目标断言。

**另外那处红**（pressure 的活表 vs 折法，差 9 token）与这一处**不是同一处代码**，各修各的。

## 第二次尝试（2026-09-28 稍后，同样**未落地**）——线索更具体了

「只改条件、括号不动」那一版**又**只红了 `a-later-turn-is-pushed-on-the-open-stream`（其余 27 条全绿）：
`pushed=……/timeout`，并且「视图里就只有一个 turn」（`(= 2 (count (:turns …)))` 实测 1）。
**所以不是括号问题**（这次结构是对的），是这条规则与**分段机器**真有耦合。

已经排掉的猜想（省得下一位重走）：

- **不是** `opens-segment?` 的问题：它只看 `(= "message" (replay/kind record))` 与 run-id / 条目 / 系统提示，
  与 `:submitted` / `:returned` 无关；第二个 run 的条目行**按三条里的两条**都该开新段；
- **不是**「客户端那条消息被标成了 kernel-message」：agent 路由的客户条目走
  `request-log!`（`harness.edge.http:1748`），出的是 `:request`；
  `log-messages!` 那条默认 `:kernel-message` 的路只服务「run 自己产出的」（子代理的第一条任务、
  `:run/done` 的补齐）。

**下一步该探的**（一条就能定性）：在那个用例里让 `segments-answer` / `trajectory-answer` 把
「第二个 run 的行到底有没有进分段机」打出来——在 `segments-step` 上包一层计数，
看第二段是**没开**还是**开了但被 answer 丢掉**（后者更可能：一个 `:submitted` 为空的段
很可能被读数侧略过，而那条规则恰好把某一行从 `:submitted` 挪进了 `:returned`）。
定性之后再决定：是修读数侧的「空段」判定，还是给那一行换一个 `:producer`（若它本就属于「交给调用的」）。

## 第三次：探针的读数（2026-09-28，代码仍**未落地**）

在读数侧打了一处探针（`trajectory-answer` 每次调用把 `(:turns folding)` 的规模追加到
`C:\tmp\segments.txt`），然后只跑那一条用例。读数：

```
[[nil 0 0 nil]]                                   ← 第一次调用：1 个 turn
[[nil 0 0 nil]]
[[nil 0 0 nil]]
[[nil 0 0 nil] [nil 0 0 nil]]                     ← 最后一次调用：2 个 turn
```

**它有时看到两个 turn。** 也就是说：分段机器**确实**开出了第二段，读数侧也把它折出来了；
断言那一次看到的 1 个，更像是**读的时刻还没折到**（活视图的推进 / 标记 / 推送的时序），
**而不是这条 `:producer` 规则算错了哪一行。**

（探针记的是折叠后的 turn，键名与段不同，所以 `submitted/returned` 的计数都是 0——下一程要探的是
**段**而不是 turn：把探针挪到 `segments-answer`，并把每次调用的时刻与写入的时刻一起记下来，
就能判定是「第二段没开」「开了没被折」还是「折了没被推」。）

**结论**：05 剩下的不是「规则对不对」，是「什么时候被看见」。规则本身在两次尝试里都把三条目标断言
改绿了；代价那条红的机制指向活视图的时序，值得单独一票来处理，不要和规则混在一起改。

## 第四次：探针找到真根因（2026-09-28，代码**未落地**，回退到 HEAD）

**A 路线（真记录）结论**：`~/.clj-harness/logs/` 下的记录都是**旧格式**（`kind`、无 `source`），但在
`~/.clj-harness/projects/<project>/<uuid>.jsonl` 找到一条**新格式、带开篇块**的真记录，它出生的行序是：

```
5: "type":"message" "source":"system-prompt"  role=system
6: "type":"message" "source":"client"         role=user     ← 客户那条
7: "type":"message" "source":"opening"        role=user     ← 开篇块
8: "type":"message" "source":"opening"        role=user
```

**客户那条在开篇块之前**（`run-agent!` 的注释也这么说：the client's own messages **plus** what the birth
wrote）。所以 `trajectory_test` 里把开篇块写在客户之前的那几处夹具**与真记录不符**——这是要改的。

**探针找到我那一版判据的真 bug**：`source "opening"` 但**没有 id** 的行（一轮**重新派生**的块）
落进了 `(= "user" role) → entry-row?` 那一支，返回 `false` = 「这一轮产出的」✗；它其实是**这次调用
真读到的**，应当返回 `nil`（落回位置）。改成 `(and (= "user" role) (entry-row? row)) true` 之后：

- 轨迹用例的红 **5 → 1**；
- **1 条是 HEAD 上绿的**（`a-resume-continues-the-parked-turn`）：`["…" "context" "tool" "assistant"]`
  变成了 `[… "context" "assistant" "tool" "assistant"]`——`role "assistant"` 的行与 tool 行**对调**；
- `http_test:1397-1399` 三条**依然红**（`returned` 仍是 `["assistant"]`，期望 `["tool" "assistant"]`）
  → 说明那条被 resume 重放的答复，**在记录里的形状不是我以为的 `role "tool"`**。

**净账是 4 红 > HEAD 的 3 红**，所以**没有落地**。下一步（很短，一次探针就够）：把这两条场景的行
原样打出来（`[type source id role producer]` + 段落归属），照**真实行形状**定判据，而不是照我猜的 role 名。

## 第五次：以真 JSONL 为准（2026-09-28，代码**未落地**）

**真数据的行形状普查**（12 条真记录、约 1.4 万行，`~/.clj-harness/projects/<project>/<uuid>.jsonl`）：

```
2648 tool / tool     2109 assistant / model     432 reasoning / -
4464 tool / -        3901 assistant / -         111 user / -
  49 user / client     24 user / opening   15 user / job   1 user / skill
  65 system / system-prompt
```

**结论**：真正产出答复的行是 `role` = `tool` / `assistant` / `reasoning`（新格式还带 `source` = `tool` / `model`）。
按这条定的判据是：

```clojure
(defn- run-produced?            ; 这一轮自己产出的 → 返回侧，无论它坐在哪
  [row]
  (let [m (get-in row [:payload]) r (:role m)]
    (or (contains? #{"tool" "reasoning"} r)
        (and (= "assistant" r)
             (or (seq (:tool_calls m)) (not= "" (str (:content m))))))))  ; 空 assistant（resume 给停住的调用
                                                                          ; 重写的占位）**不算**产出
```

**两处要一起改**（这是这一轮的新发现）：

1. 分类器那一支：返回侧 = `(or (:streaming current) (run-produced? record))`；
2. **开段那一行**（`segments-step` 建新段时 `:submitted [(row-message record)]`）：resume 重放的那条答复
   就是**开段的第一行**，它也走 `run-produced?`（产出的 → `:returned`，否则 → `:submitted`）。

**效果**：`harness.edge.trajectory-test` **29/125/0 全绿**（含那条 live 推送用例——它之前红是我更早那版判据
造成的）；但 `http_test:1397-1399` **仍红**（`(:returned (last runs))` 仍是 `["assistant"]`）——
说明那条被重放的答复**既不在开段那一支、也不在 message 分类那一支**里被我判到（另有写它的路径）。
**净账与 HEAD 相同（3 红），所以没落地。**

**下一次探针（就一步）**：在那条 http 用例里把 `records` 逐行的
`[type payload.role source payload.tool_call_id]` 打出来——**以那条用例自己的真行为准**，
而不是再去猜 record 的形状。另外：主人提醒「所有消息都该被 start/end 信封包裹，否则要先 fork 重整化」，
**我看的那条出生序可能来自未重整化的记录**，所以「夹具写反了」这个结论**先不执行**，等重整化后的记录再确认。

## 第六次：探那条 http 用例**自己的记录**（2026-09-28）——根因是「行没写下来」

按票里那一步，在用例里把 `records` 逐行打出来（`[type source payload.role payload.tool_call_id]` +
每段的规模），然后只跑它一条。读数（43 行，`SEGMENTS [["172308fc…" 4 1] ["2d36ed70…" 2 1]]`）：

```
 7 message src=system-prompt role=system
 8 message src=client        role=user
 9 message src=opening       role=user      ← 真序：客户在前、开篇在后（与出生记录一致）
10 message src=opening       role=user
17 message src=model role=assistant
29 event   role="tool"                      ← 工具那条**答复只有一行 event（线上帧 TOOL_CALL_RESULT）**
32 message src=system-prompt role=system    ← resume 段开始
33 message src=client        role=user
38 message src=model role=assistant
```

**整份记录里没有任何 `message role="tool"` 行，也没有任何 `tool_call_id`。**

⇒ 那条用例的 `wait-for-recorded`（等一条 content 为 `wrote it` 的 `message` 行）**等超时**，
`returned` 只剩 `["assistant"]`。**这不是「判据把行分错侧」，是「被 resume 重放的工具答复根本没有写成一
行 `message`」**——它是 `harness.edge.replay` 与「returned 侧」两处读者共同缺的那一行。

**于是票 05 该拆成**：

1. **补那一行**（resume 重放的工具答复要作为 `message` 行落下——落点在内核重放答复的那条路 /
   `:run/done` 的对账），这一条才是 `http_test:1397-1399` 红的正主；
2. **哪一侧**（本轮已定的 `run-produced?`：tool/reasoning 与「真说了话」的 assistant → 返回侧，
   含**开段那一行**）——第 1 条落地后它的效果才可验；
3. **夹具的开篇块位置**：本条用例的真记录证实是 `system-prompt → client → opening`，
   与出生记录一致（主人提醒的「未重整化」在此不适用：这是当前代码真跑写出来的）。夹具按此改。

## 压力那一处红（2026-09-28 收，`记录重整化` 那一程顺手做的）

`pressure_test/the-live-band-and-the-record-fold-answer-the-same-thing`：活表与记录折法差 **9 token**
（`49072` vs `49081`）。**根因不是折法，是锚点取早了/取晚了**。

**现场**（在用例里打点，把两边的 band 摊开）：

```
活表   :anchor-n 1      ← 这次调用赖以发送的会话
记录   :anchor-n 2      ← 多了调用自己那条答复
```

**为什么**：`band-step` 原来在 `model/end` 那一行取「会话快照」，而到那一行为止，这次调用自己的答复**已经
进了会话**——它的帧被折成一条 assistant 消息（组是内核那条 `message` 行冲开的，真记录的次序是
`TEXT_MESSAGE_START → … → message(model) → model/end → TEXT_MESSAGE_END`）。于是锚点被**多算了那次答复**，
`anchor-est` 偏高，`total = prompt + current - anchor-est` 就偏低 9 token。活表没这个问题：它读的是会话
里**已经结算**的条目（run 的帧要到 settle 才进去）。

**修法**：锚点的会话 = **调用那一刻**的会话。`band-step` 在 `model/start` **和**边为这次调用写下的每一行
（`:producer "request"`：prompt、这次动作的条目、注入、压力读数）上刷新 `:start-messages`，`model/end` 取它。
两边的 ctx 于是都在「请求写完、答复还没到」那一刻取值 ⇒ 活表与折法逐字段相等。

**判据**：`pressure` + `relieve-pressure` + `compaction` + `compaction-run` + `stats` + `context` +
`sessions` + `replay` + `trajectory` + `loop` + `http` 见提交信息（那一轮全绿）。
