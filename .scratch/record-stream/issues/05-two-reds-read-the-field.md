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
