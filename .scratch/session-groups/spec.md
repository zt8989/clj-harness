# spec: 会话分组 —— `:session` 是默认组，`:groups` 按模型覆盖

## 问题

`config.edn` 的 `:session` 一节（editing / compaction / llm / approval / skills /
instructions / subagents）是**整个家的一份答案**：同一个进程里，跑 Opus 的会话和跑
本地 `qwen3` 的会话拿到的是同一套编辑模式、同一组压缩比例、同一个空闲超时。

这在两处具体地不合理：

1. **小模型与大模型要的编辑面不同。** `hashline` 的锚点工作区是为能读锚点的模型设计的；
   一个只会精确替换的模型在它上面反复试错。这件事本来就是**按模型**的，不是按家的。
2. **同一个家跑两种厂商时的超时不同。** 本地 endpoint 首字节慢，30 秒的空闲守卫会
   把一次真实的思考判成断线；云端强推理模型正好相反。

而 `:session` 至今**没有写入口**：只有 `:subagents` 那一个块有设置页（Subagents 页），
其余六个键只能手编 `config.edn`。「把 session 全部配置搬到设置页」是这件事的另一半。

## 设计

### 形状：默认组 + `:groups`

- **默认组就是 `:session` 顶层那七个键**，行为与从前逐字节相同——向后兼容，无迁移。
- **新增 `:session :groups`**：一个向量，每项是一个组：

```edn
{:session
 {:editing {:mode :hashline}                  ; 默认组：适配所有模型
  :groups
  [{:name   "本地小模型"
    :models [{:provider :local :model "qwen3"}]
    :editing {:mode :str-replace}
    :llm     {:idle-timeout-ms 0}}]}}
```

一个组 = `:name`（非空、唯一字符串）+ `:models`（非空列表，每项 `{:provider :x :model "id"}`，
`:provider` 可省 = 任何厂商的这个 id）+ 六个 `:session` 块中的任意几个。

### 解析：按会话的模型挑组

`harness.cap.providers/session-config` 从 0 元/1 元变成按 `thread-id`：用
`active-provider`（三档折叠的现成结果）拿到会话**当前**的 provider/model，把 `:models`
命中的组按**文件顺序**依次盖在默认组上——**逐键合并**（`merge-session-blocks`），靠后的组
赢一个两个组都写了的键；每个块内部也逐键，所以只写 `{:editing {:grep false}}` 的组保留默认
组的 `:mode`。

- 模型解析不出来（家还没配 provider、模型没登记）⇒ 只回落默认组，**不报错**：默认组是地板。
- `nil` thread-id ⇒ 家自己的答案（默认组，无模型可匹配）——名册与离线工具要的就是它。

所有七个键的消费者（`harness.cap.editing` / `harness.edge.compaction` /
`harness.edge.llm-timeout` / `harness.cap.project` 的围栏 / `harness.cap.skills` /
`harness.cap.preamble`）都经 `project/harness-config` 读，所以它们**一行不改**就跟着走。

### `:subagents` 不是组的键

一个 subagent 定义是这一家的事实（委派工具与 Subagents 页读同一份名册），不属于某个模型。
组里写 `:subagents` 在 `check-session-groups` **指名失败**；它照旧在 Subagents 页编辑。

### 写的一侧

`POST /api/session {default?, groups?}`：

- `default`（map）：某块写值 = 设它，写 `null` = **删掉**它；缺席的键别动。
- `groups`（数组）：整份替换，`[]` 清空；缺席别动。
- 两个半边都可以缺席——改了某个分组的表单不必复述默认组。
- 规矩与 `/api/security` 一致：**先校验整份、再原子落盘 + 一代 `.bak`**，被拒时一个字节都不写。
- JSON 没有关键字，所以 `:editing :mode` 与组里的 `:provider` 写入时还原成关键字
  （`canonical-session-block` / `canonical-session-group`）；不还原的话文件里会落下
  `{:mode "hashline"}` 这种没有消费者认得的东西。

### 读的一侧

- `GET /api/session` 是**这个家的事实**（没有 threadId）：`{:default .. :groups .. :path ..}`，
  一切**按文件里的原样**（`~` 也是）——表单编辑的就是文件。
- 某个会话实际落到哪一组是**另一个问题**，由 `session-config` 按那个会话的模型现算。两件事，两个入口。

### 校验

`check-config` 顶层 `:session` 的已知键加上 `:groups`；`check-session-groups` 一层更深地校验
骨架：组是 map、键在闭集内、`:name` 非空不重名、`:models` 是非空列表且每项有非空 `:model`。
每个**块的含义**仍是它消费者的事（与从前一致：`check-config` 只查键的名字）。

### 设置页

侧边栏「设置」新增 **会话行为**（Session behaviour）页（`page === "groups"`）：

- **默认组**一节：六个块**直接编辑**，没有开关——默认组就是每个模型拿到的东西。保存时
  `:editing` / `:compaction` / `:llm` 一律写入（字段值就是 harness 自己的默认值——
  `harness.cap.editing/defaults`、`pressure/default-ratios`、`llm` 的超时——所以不动就是空操作）；
  `:approval` 只说得出话（strict，或有一条放行路径）才写；`:skills` / `:instructions` **非空才写**，
  因为空 `:roots` 是一个「不读任何技能」的决定（`harness.cap.skills` 查的是 `(contains? cfg :roots)`），
  没动过的空框必须让这个键**缺席**，而不是写一个 `[]` 把内置根目录关掉。
- **分组**一节：清单 + 新增；每个分组一张表单（名称、模型多选，六个块**各带一个「覆盖此项」开关**，
  勾选后才展开编辑——关掉一个块是「沿用默认组」，与「把它设成 starter 的值」不是一回事）。
- 模型多选从 `GET /api/providers` 的目录里取——不在前端另造一份 id 表。

## 非目标

- 不做按项目覆盖（项目级 harness.edn 已退休，是另一张 spec）。
- 不做组内的 `:subagents`；不做组的启用/停用开关（不想要就删掉）。
- 不改任何块的语义与消费者；`merge-session-blocks` 只做一层的逐键合并，不做递归深合并。
- 不写 `:session` 以外的任何一节：`set-session-config!` 经 `change-session!` 走，`:subagents`
  与整份文件其余键原样带过。

## 验收主线

1. 默认组：无 `:groups` 的文件，行为与从前逐字节相同（既有 `providers-test` 全绿即证）。
2. 命中组：会话模型在 `:models` 里 ⇒ 组的块逐键盖在默认组上；不命中 ⇒ 只有默认组。
3. 多组命中：文件里靠后的赢一个两个组都写的键。
4. `:provider` 省略 ⇒ 按 model id 匹配任何厂商。
5. 坏形状（未知键、缺名、重名、空 `:models`、块不是 map）⇒ 读时**指名失败**；`config.edn` 一字不动。
6. `POST /api/session`：`default` 设值 / `null` 删除、`groups` 整份替换、两个半边可缺席；
   被拒时文件与 `.bak` 都不动；答案按文件读回（关键字已还原）。
7. `:subagents` 在一次 `:session` 写入中原样保留。
8. 设置页：默认组六个块**直接可编**（没有开关），分组勾选后才展开覆盖；能看、能加、能改、能删；
   中英两种文案都在。
9. 默认组不动任何字段就保存 ⇒ 文件里出现 `:editing` / `:compaction` / `:llm`（值就是内置默认值，
   行为不变），而 `:skills` / `:instructions` **没有**被写成 `[]`（内置根目录与 AGENTS.md 照旧）。
