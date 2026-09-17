# 04 — 说明是 `SystemPrompt` 上一条可开关的行

**What to build:** Action Fusion 的**说明**——「一个 eval 里可以把工具串起来、按返回值决定下一步、
循环做批量操作，中间不回到模型」——不是写进 `prompt.md` 的一段话，而是 `SystemPrompt` 点上**一条内建行**，
排在 `<project>` / `<env>` 之后，**开关在 `harness.edn`**。默认开；关掉之后组装文本里一个字都没有。

**Blocked by:** 01, 02, 03 — `call!` 还不存在时告诉模型它存在，是这条块最坏的形态

**Status:** ready-for-agent

## 为什么不写进 `prompt.md`（牛总，2026-09-16）

**不在 prompt 中说明 eval 功能或者其他 tools 能力**——名册在 wire 上自描述，不必说。
**但技法不在 wire 上**：任何工具的描述都覆盖不了「怎么把它们串起来用」，所以技法得有地方说，
而那块地方就是这里。也正因如此它不该进冻结文件：技法随机制演进，而冻结文件里改一句话要 `reset-prompt!`。

两句话别压成一句：**名册不必说**（`<tools>` 因此退场，见 `.scratch/session-context/`），
**技法必须在某处说**（这块）。把前者当成「能力一律不必说」，就会把这一票也一起砍掉。

## 形状

```
<action-fusion>
You can run a whole sequence inside ONE eval call: call any tool with
(harness.kernel.tools/call! "read" {:path "deps.edn"}) -- a name and a Clojure
map, no JSON -- branch on what it returns, and loop for batch work. The session
is addressed for you, so relative paths and bash's working directory behave
exactly as they do when you call a tool directly. Nothing goes back to you
between those calls, which is the point: "edit the file, then run the tests" is
one call, not two.
Two things cannot happen inside a sequence and must be asked for at the top
level instead: a call that would park for human approval, and loading a skill.
Both are refused by name rather than half-working -- the ask has to reach a
person, and a skill's body only enters the conversation when the call itself is.
</action-fusion>
```

措辞由实现定（实现时对着真实 `call!` 的名字与拒绝文本核对一遍）。**必须在这段里的四件事**：
入口的名字与形态（名字 + 参数 map）、**会话已被处理好**（相对路径与 cwd 与直接调用一致）、
**中间没有模型**、以及**两类只能顶层做的事**。

## 开关

- **`harness.edn` 的一个键**（`~/.clj-harness/harness.edn` 与 `<项目>/.harness/harness.edn` 两级装配，
  与 `:editing` / `:approval` 同一处，每 run 现读），默认**开**。
- **关掉 = 这一行什么都不追加**（不是追加一个空的 `<action-fusion></action-fusion>`：
  一段没有内容的话不该出现）。
- **它同时还是一条普通的行**：`effective-hooks` 里看得见、`session-disable!` 关得掉、关掉后文本里没有它、
  再打开又回来。两个开关同一个效果**不是两份真相**——这就是行的性质，与内建的那几条一样。
- **`harness.edn.example` 要把它写在默认值上并注释**（那份 example 的纪律是「每个键都写出来、写在它的
  默认值上」，README 明说了这条）。

## 位置

`install!` 的 `:builtins` 表里排在 `project` / `env` 之后——**先事实、再技法**：知道自己在哪台机器上、
在哪个目录里，然后才谈怎么把这些工具串起来用。

## 现状文档

| 地方 | 该说什么 |
|---|---|
| `docs/architecture/hooks.md` | 内建行的表多一行 `builtin:action-fusion`：`<action-fusion>`、说什么、开关在哪 |
| `docs/architecture/kernel.md` | 工具那一节写清**本地序列**：`call!` 走完整的缝（关闭 / 缺参数 / 审批 / 门禁一条不少，**这是刻意的**）、两类只能顶层做的事、内层调用的审计行 |
| `docs/architecture/edge.md` | 内层调用的三行审计与它们的 id 形状（若 03 落在那里） |
| `docs/architecture.md` | 「在办」段里 Action Fusion 这一条拿掉；模块地图 `tools` 那一行若变了就改 |
| `prompt.md` | **一个字都不加。** 加任何一句都是把这一票做反了 |

## 验收

- [ ] `effective-hooks` 里有 `builtin:action-fusion`，来源 `:built-in`，排在 `<project>` / `<env>` 之后
- [ ] 默认开（`harness.edn` 不写这个键时）：组装文本里有那段说明
- [ ] `harness.edn` 写 `false` → 文本里**一个字都没有**，且不是一对空标签
- [ ] **两级装配**：用户级写 `true`、项目级写 `false`，项目级赢（整键替换，与既有那条一致）
- [ ] `session-disable!` 关掉这条行 → 文本里没有它、它仍在 `effective-hooks` 里（关闭不是隐藏）、
      `session-enable!` 打开又回来
- [ ] 开关**每 run 现读**：改 `harness.edn` 之后下一个 run 就变（不需要重启、不需要 `reset-prompt!`）
- [ ] `harness.edn.example` 有这个键，写在默认值上并注释
- [ ] **`prompt.md` 一个字未改**（有一句断言或一次 diff 检查都行——这一条要能被看见地守住）
- [ ] 说明里点的入口**真的存在**：拿那段文本里的名字去查工具表/函数（一个测试读那段文本并断言
      `call!` 可解析、`harness.kernel.tools/*thread-id*` 在 eval 里可见），否则文案与代码会各走各的
- [ ] 同一份事实组装两次**逐字节相同**（前缀缓存那条性质）
- [ ] 离线全量 `harness.test-runner` 全绿；`docs/architecture/` 四处与代码对得上
- [ ] 真机看一次：新会话里模型知道可以把工具串在一个 eval 里（**这块的全部价值就是这句话**）；
      没有人跑过就不写「过了」

## 不做

- 不在块里列举有哪些工具（那是名册，wire 上有）。
- 不写具体例子的长篇教程：三五句话，说清入口、能力的最强形态、两条边界。
- 不新增工具、不改任何工具的 schema、不改 `prompt.md`。
