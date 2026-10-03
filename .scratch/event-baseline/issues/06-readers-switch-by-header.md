# 06 — 读侧切流：双方言（rebuild / trajectory / compaction / repair）

**What to build:** 记录会说话了之后（format 2 有帧、format 3 只有事件），把**每个读者**
切到按 header 选 fold：rebuild 与会话构建（`replay` 的 `entries` / `messages-so-far` /
`rebuild`）、trajectory（按行分段、找注入卡）、compaction 的 head 挑选与 shadowed 计算
（`compaction.clj` 读 `session/closed-off` 的那处）、closing repair（`closing-frames`
写给谁）、pressure 的 meter fold、`read-records` / `fold-record` 的下游。读者只多一个
「这条记录是哪个方言」的问题，答案在 header；**fold 的输出形状不变**（还是 AG-UI 消息表 +
entry `:seq`），所以 `sessions` 表、窗口算术、`sofar`、feed 一个字不动。

**为什么值得做：** 这是承诺的另一半——「旧记录照读」不是一句宽限，而是产品能力：
昨天的会话要在新进程里能 rebuild、能继续、能 compaction、能 repair。双方言并存也是
切流的安全带：新方言哪里折错了，旧方言还在，回滚是切回去而不是修数据。

**Blocked by:** 05

**Status:** needs-triage

- [ ] header → fold 的分派一处写死（`replay` 里），读者各取；不各写一个 `if format 2`；
- [ ] 每个读者一条双方言用例：同一段对话，format 2 与 format 3 各一条记录，答案逐字相等；
- [ ] 旧记录上的 repair/fork 照旧能写（`closing-frames` / `session/forked` /
      `session/closed-off` 在 format 2 的记录上仍产出它今天产出的东西）；
- [ ] 混读边界说死：一条记录一个方言，没有「一条记录里两半」这种东西
      （carry-back 的旧段是新方言么——按 header 行本身说了算，用例钉住）；
- [ ] 性能不回退：`cheap-session-load` 那组读侧判据在新 fold 上复跑。

## Comments

2026-10-02 — 从对账拆出。fold 输出形状不变是本票能「只切流」的前提，写进了第一段。
