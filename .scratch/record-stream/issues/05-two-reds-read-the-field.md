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
