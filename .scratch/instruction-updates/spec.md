# spec: 指令变了就送一条更新 —— 能原位的端点保住前缀，不能的照旧换 `message[0]`

**一句话**：system 消息每轮现组装（`cap.system-prompt/assemble`）、工具表每次模型调用现解析
（`kernel.tools/specs`），两者今天都**没有名字**：变了就静默地换掉请求里那条 system 消息，代价是
**整场对话从头 prefill**。本特征让「变化」成为一个被比出来的事实，并给它一条送达方式——能原位更新的
端点，`message[0]` **一个字节都不动**，变化作为**一条 `developer` 消息**落在新提问之前；不能的端点，
照旧替换 `message[0]`，而那是一次**显式的**冷前缀。

2026-09-18 立票，2026-09-21 复议追加一张界面票（按模型配置能力位），2026-09-24 追加票 06（压力表的
锚点判据跟着送达方式）。六张票：`01 → 02 → {03, 04} → 05`，外加 `02 → 06`（压力表，独立一档）。

## 问题

1. **变化今天没有名字，也没有送达方式。** `assemble`（`cap/system_prompt.clj:82`）每轮现算，
   `inbound`（`edge/ag_ui.clj:369`）拿它的结果替换客户端那条 system 消息——组装的唯一调用处是
   `edge/http.clj:841`。事实站在正确的一边（模型读到的是真的），代价是：一次 `project/bind!`、一次
   会话禁用一个工具、一次换 provider，都让**整场对话从 token 0 重算**。
2. **那个代价不必然。** 铁律 2 自己的 docstring 写着「The price of a fact that moved is ONE cold
   prefix」，把它当成了必然。而 Chat Completions 兼容的端点接受**对话中途的 `developer` / `system`
   消息**：把变化作为尾部的一条消息送出去，`message[0]` 不动，**共享前缀（system + 客户端每轮重述
   的历史）整段留在缓存里**，只有尾部那一段重算——而尾部本来每轮都要重算（`.scratch/context-frames`
   票 05 那笔账，在飞）。
3. **今天无从判断「变了没有」。** 服务端不存上一轮送出的是什么——铁律 3（每轮从请求现收）、
   状态表里「system 消息里 hook 追加的那部分 | **不存**：每次组装现算」；客户端也不持有 system
   消息（`inbound` 的「否则前插」那条路只有协议测试与重建走，`edge/replay.clj:430`）。
4. **工具表比 system 文本更细，也躲不掉。** 它在 `loop.clj/model-call!`（`:47`）里**每次模型调用**
   现算（`:70` 的 `tools/specs`），一次 run 内都可能变；而它落在请求最前（`llm.clj:300` 的
   `:tools`）——**变一个字，整条前缀作废**。所以「工具变了」这一半，**本特征不省它的缓存**，能省的
   只有指令那一半。
5. **端点能力今天没有这个字段。** 模型条目只有 `:input` / `:output` 加两个可选计数
   （`model-keys`，`cap/providers.clj:114`，闭集，别的键按名字拒绝）；协议只实现了一种
   （`llm.clj:296` 的 `:openai-completions`）。而那张表里的端点今天**一个都不在目录里**。

## 决策

1. **比较的是两份 name hash，不是组装后的文本，也不是数量。**
   - **hooks 的名字集合**：SystemPrompt 点上参与组装的 hook 身份（名字 + 来源 + 顺序）。
   - **tools 的名字集合**：该 thread 有效工具表的**工具名**集合（`<tools>` 块只报集合、不复制描述——
     `.scratch/system-prompt-blocks` 决策 6）。
   任一份变了 ⇒ 「指令变了」⇒ **重建** system prompt（跑 hooks、拼文本）并按下面两档送达；两份都没
   变 ⇒ **跳过组装**，复用上一轮那份文本。
   - **不算内容**：工具**描述**改了不管；hook 输出里与名字无关的差别不管。
   - **`prompt.md` 不参与**（进程内冻结；`reset-prompt!` 要显式作废）。
   - **project 绑定不许动态变**（今天也不支持），所以 `<project>` 的变化不在本特征里。
   - 推论：`<tools>` 块是「工具生成」那一半的载体（`.scratch/system-prompt-blocks` 票 03，未落地），
     本特征把它列为**前置**，不自己再说一遍「可用哪些工具」。
2. **「变了没有」由进程内存回答**：按 thread 记一份**上一轮送出时的签名与那份 system 文本**。先算签名
   比一次——没变就**复用文本、不跑 hooks**，变了才重建。不进库、不落盘、不进日志——与
   `session-started`（`http.clj:469`）、`live-runs`（`:498`）同一条纪律，**重启即失是特性**：重启后的
   第一轮重新组装（一次冷前缀），然后照旧。
3. **送达（`:in-place` 的端点）**：`message[0]` **原样不动**（上一轮那份的字节），把**新的指令全文**
   作为**一条 `developer` 消息**插在**新提问之前**。
   - **送全文，不送差量**：厂商的指令槽是**覆盖**语义；一条只带差量的消息等于要求模型自己合并，
     还要为「差量」再造一套算法（第二个真相源）。一条消息一个事实。
   - **位置**：`system → 历史 → developer 更新 → 新提问 → context → skill_context`。更新是**提问的
     前提**，所以在提问**之前**；context 与技能正文是**为提问准备的料**，在**之后**
     （`.scratch/context-frames/issues/05`，在飞）。两段都在尾部，缓存上是同一笔账。
   - **累积成链**：每变一次一条，位置就是它们发生的顺序，后说的覆盖先说的；链**也要每轮重送**，
     因为客户端不持有它。
4. **送达（其余端点）**：**替换 `message[0]`**（今天的行为），一次冷前缀——**显式化，不是偶然**。
   缺省就是这一档。
5. **能力位**：模型条目新增可选键 `:instruction-updates`，取值 `:in-place` / `:replace`，
   **缺省 `:replace`**。保守的方向有理由：不声明 ⇒ 一次冷前缀（可接受的坏结果）；错发一条 vendor
   不认的消息 ⇒ **整个 run 起不来**。`model-keys` 跟着开，`resolved-fields`（`:127`）跟着带上。
   **值也是闭集**：这两个名字之外的在 `check-model` 里按名字拒——静默退化成 `:replace` 只换来一次
   没人注意的冷前缀，写错名字的人会以为它生效了。
   - **缺省落在解析那一步，不落在文件里**：报告（`registry-report` 的 `model-row`）照文件说，没写
     就是没有这个键；解析（`selection`）出来的那份选择一定有值。两个读者两个答案，因为问的不是
     一件事——「文件里怎么写的」与「这次 run 照哪一档送」。
   - **它有一个配置入口**（2026-09-21 追加）：设置 → Models 页，按模型三态——未声明 / `in-place` /
     `replace`。未声明是**真实的第三档**（保存时不写那个键），不是空档：表单不替人写下他没说过的
     话。界面只搬名字，合法的值与缺省都只在 `cap/providers.clj` 说，细节在票 04。
6. **那张端点表只落地目录里已有的端点。** Moonshot / Fireworks / OpenCode / Copilot / Kimi K3 今天
   都不在目录里；它们接进来时顺手带这个键——本特征**不预置看不到依据的行**。
7. **system 消息仍然只有一条。** 「原位更新」送出去的是 **`developer` 角色的一条消息**，不是第二条
   system 消息；铁律 2 那句照样成立，要改的是它**推论**：变化的送达有两种，选择权在端点的能力位
   手里。
8. **内置前缀表：一条只在「自动获取模型列表」那条路上说话的规则**（2026-09-21 追加）。表单问
   `/models` 拿回来的每个 id，命中表里的前缀就**预填**成 `:in-place`；没命中就什么都不说（未声明，
   人自己选）。
   - **表住在服务端**（`cap/providers.clj`，与内建 provider 表同一个家，`probe-models` 的答案带行
     不带裸 id）：前缀 → 一个值，今天的行全是 `:in-place`，`replace` 行只在要给更宽的前缀开一个
     例外时才写。**最长前缀赢**——不许「先遇到的赢」，map 的迭代顺序不是一个事实。未命中就是没有
     那个键，与报告的「有才带」同一条。
   - **每一行都要有依据，而且是有方向的那种**：这张表是对厂商的断言，猜错的方向是**整个 run 起不来**
     （与决策 5 同一条理由），所以没有依据的家族不进表——与「那张端点表不预置看不到依据的行」是同
     一套纪律。
   - **它不参与解析。** 运行时那条路只认文件里的声明（决策 5）：规则说过而没写进文件的，run 照旧走
     `:replace`。理由：「这次 run 照哪一档送」不许有一个不在 `config.edn` 里的主人——同一个**没写**
     这个键的模型，配上一旧一新两版表，会在两个进程里送出两种请求（一个 `:in-place` 一个
     `:replace`），而文件里读不出为什么。它**也不追认**已经写下的行：不迁移、不重写，文件是那个人
     当时说的话。
   - **前端不做前缀匹配**（那是第二份会漂的表）：命中与否由探测答案说，候选清单里标出来，take 进来
     的行预填成那个值。预填**不是替人写下**——它看得见、改得动，而未声明那一档什么也不预填。

## 非目标

- **不接新端点**，不做那张表的其余行。
- **不改工具表的解析时机与内容**（仍每次模型调用现算）：本特征只**看见**它变了，并保证指令里那份
  清单跟上。
- **不改 `before-llm`**（技能正文 + 作业通知那条会话注入）的语义与位置。
- **不做「模型读没读」的确认**，不做更新的重试与回执。
- **更新本身不画**：能力位有了一个配置入口（票 04），但**让模型看见的那条消息**不进界面——它落在
  `message` 行的 submitted 侧，轨迹那一栏本来就读那儿，会话栏不因此多任何东西，也不弹一句「指令
  变了」。两件事分得清：**配置**这一半是新增的一张票，**渲染更新**那一半仍然是这里划掉的。
- **不改重建路径**（`edge/replay.clj:430`）：重建从记录组装 system 消息，那里没有「上一轮」可言，
  照旧读全文。

## 验收主线

1. **没变就不动**：连着两轮什么都没改 → 第二轮的 `message[0]` 与上一轮**逐字节相同**，且**没有**新的
   developer 消息。这是回归保证。
2. **变了就送**：`:in-place` 且一轮里加/删一个工具（或加/删一条 hook）→ 下一轮 `message[0]` 与上一轮**逐字节相同**，多出
   **恰好一条** developer 消息，内容是**新的指令全文**，位置在最后一条 user 消息之前。
3. **不支持的照旧**：`:replace`（含缺省、含重启后的第一轮）不出现 developer 消息，`message[0]` 就是
   新的全文。
4. **重启**：记忆空 ⇒ 第一轮走 `:replace`，随后恢复。
5. **工具**：会话禁用一个工具 → `:tools` 数组确实变了（冷前缀，本特征不省），而下一次组装出的
   指令里 `<tools>` 块跟着变、并按 2/3 送出。
6. **记录与读侧**：`message` 行的 submitted 侧包含那条 developer 消息；`run-segments`
   （`edge/trajectory.clj:51`）的切分不变（它按 record kind 切，不按数量）。
7. **界面（2026-09-21 追加）**：设置 → Models 页里，一个模型行的能力位**就是文件里的样子**；选
   `in-place` 保存后 `config.edn` 那一行多出这个键，同一 provider 的**其余模型行逐字节不动**；选回
   未声明则那个键消失。
8. **自动获取列表的预填**：问一次 `/models` → 命中内置前缀的 id 在候选清单里带着值，take 进来的行
   预填 `:in-place`，没命中的停在未声明；而**同一行还没写进文件之前**，run 走的仍是 `:replace`
   （规则不参与解析）。
9. 两套全量与真浏览器走查证据。
10. **压力表**：`:in-place` 下加/删一条 hook（工具名字变了则两档都作废）→ 压力表的锚点**仍被采用**（`:baseline "usage"`），新指令全文
    算进 delta；`:replace` 下同一改动 → 锚点作废、退回 `estimated`；工具表变了则两档都作废。（票 06）

## 跨特征对照

- **`.scratch/system-prompt-blocks` 票 03（`<tools>` 块）**：本特征的前置，理由见决策 1。该票 04 的
  两个块（`<project>` / `<env>`）已落地（`cap/system_prompt.clj:195` 的 `install!`）。
- **`.scratch/context-frames` 票 05（注入物挪到提问之后）**：本特征的 developer 消息排在它**前面**；
  它在飞（未提交），本特征不依赖它的字节，只依赖「尾部本来每轮重算」这笔账。
- **铁律 2（`docs/architecture/overview.md` 的「三条铁律」）**：推论要跟着收窄（决策 7）；同文件的
  状态表里「system 消息里 hook 追加的那部分 | 不存」要加一句：**送出去过的**那份有一份进程内存
  （决策 2）。
- **`harness.edge.context`**（`context.clj:148`）：它按 `:role = "system"` 分桶，所以 developer 消息
  落进「其余」那一桶——票 03 要把这件事定下来并写下理由。
- **`harness.edge.pressure`**（`pressure.clj` 的 `anchored?`）：它把「锚那次 run 的 system」与「现在的
  system」比一遍，这个判据只在 `:replace` 下成立；`:in-place` 让 `message[0]` 不动，锚不该因为 system
  变了就作废。票 06 把这条判据按送达方式分档。
- **`docs/architecture/client.md` 的「设置面板」那一节**（`:565` 起）：两页、两页都会写的说法不变，
  要加的是 Models 页多了一个**按模型**的控件（票 05 核）。README 的「配置说明」不因此多一个字——
  它说的还是那两页能改的东西，而这一栏改的仍是厂商目录里的条目。
- `.scratch/job-endings`、`.scratch/trajectory-injection-once`：不动。

## 交付顺序

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | 比较的缝与那份记忆 | `system-prompt-blocks` 票 03（跨特征） | 按 thread 记「当前生效的指令文本」；组装之后比一次，报出「变了」与新的全文；进程内存、重启即失；用例：没变＝无变化、变了＝能指出全文、run 没开始＝不更新 |
| 02 | 原位的送达：能力位与那条 `developer` 消息 | 01 | catalog 的 `:instruction-updates`；`:in-place` 时 `message[0]` 冻结 + 一条 developer 消息插在新提问之前（全文、累积成链）；用例：逐字节相同、位置、链的顺序、病态历史不猜 |
| 03 | 不支持的端点与读侧三处 | 02 | `:replace` 显式化（含缺省与重启后第一轮）；记录 / `run-segments` / `context` 归属跟着核；用例：不出现 developer 消息、按 kind 切分不变 |
| 04 | 按模型配置能力位的那个控件（2026-09-21 追加） | 02 | 设置 → Models 页模型行里的**三态**控件：读报告（未声明就缺席）、写文件（未声明就删键）；自动获取列表时的**内置前缀预填**（决策 8）；两语言文案、走查点到它 |
| 05 | 收口：铁律 2、状态表、文档、报数 | 03、04 | `overview.md`（铁律 2 的推论 + 状态表）、`kernel.md` / `providers.md` / `edge.md` / `client.md`；两套全量报数；落地记录 |
| 06 | 压力表与送达方式：锚点该不该因 system 变了就作废 | 02 | `anchored?` 的 system 那一格按 `:instruction-updates` 分档；工具表两档都比；跨档切换的判据；用例：`:in-place` 锚点仍采用、`:replace` 作废 |

## 状态

**2026-09-18 立票；2026-09-21 复议：追加票 04（按模型配置能力位的界面），非目标那条收窄成「更新本身
不画」，「值也是闭集」写进决策 5；同日又追加决策 8（内置前缀表只在自动获取列表时预填，不参与解析）。**
尚未落地。

**先不落地（2026-09-21 的口径）：** 票已经写全，但按人的话，等 session live server 那件事完成之后
再实现。这不是一条 `Blocked by`——本仓里没有那条依赖，它是一次排期。


**2026-09-24 追加：** 票 06（压力表）。本特征原本只核了记录 / `run-segments` / `context` 三处读侧，
漏了 `harness.edge.pressure`——它的 `anchored?` 恰好踩这一格。与 `.scratch/model-surface-and-meter`
票 03 配套：那张票把压力表从「每轮读整份记录」改成「读缓存模型面 + O(1) 条带」，本票给它分档后的判据。
## 已验证到什么程度

**未验证（尚未落地）。** 基线：见票 05 落地的报数。

## 落地记录

2026-09-25 — **票 01、02、03、06 与 04 的后端一半已落地**（工作区直接改在 `main`，未开 worktree）。
下面按「与票面不同的地方」写；票面没改。

### 票 01（比较的缝与那份记忆）

- **那份记忆与签名住在 `harness.cap.instruction-updates`**（新命名空间）：`signature` / `plan` / `commit!` /
  `forget!` / `fallback`。`plan` 在 hook sink 绑定之内被调用（`edge/http.clj` 的两条 run 路径里，
  `cap.system-prompt/assemble*` 原来的位置），没变就复用上一轮的文本、**一个 hook 都不跑**。
- **`hooks` 那一半的名字集合换了个算法**：`cap.system-prompt/hooks-names-hash` 直接读
  `hooks/declarations-at`，不跑 hook 就能算。`assemble*` 仍用它自己那次 `emit` 的 `:hooks`，两者在
  有 sink 时相等（SystemPrompt 点没有 matcher），docstring 写下了这个边界。
- **跨特征前置（票面写的 `system-prompt-blocks` 票 03）换了个落法**：那个 `<tools>` 块**故意不进正文**
  （该 spec 2026-09-24 的边界，决策 6 不变），所以「工具名字集合」的家不是 prompt 正文，而是
  `model/start` 上的 `:tools-names-hash`（`.scratch/model-surface-and-meter` 票 04）与 system 行信封上的
  `:tools`。本票的签名因此照 `kernel.tools` 现算，不依赖那个块。
- **偏离决策 6 一处（记明细）**：票面写「签名不含 provider / binding 档，本项目今天也不允许中途换它们」。
  实际上 `POST /api/project` 就是中途换绑定，`cap/project/bind!` 也允许；只比两个 name hash 会让
  `:in-place` 的会话把旧的 `<project>` 块**一直**发下去（模型读到一句不再成立的围栏）。所以 `signature`
  多带了 `:project-dir` 与 `:prompt-epoch`（后者是 `reset-prompt!` 的显式作废门，`llm/prompt-epoch`）。
  两个 name hash 一字未动；验收里「没变就不重建 / 只改描述不算变」照样成立。
- **`:in-place` 下 system 那条 `message` 行写的是 message[0] 的字节**（冻结那份），不是这一轮的新组装——
  这是票 06 能只靠 `:sig` 就分档的原因（见下）。

### 票 02（原位的送达）

- 能力位在 `cap/providers.clj`：`model-keys` / `resolved-fields` 各加 `:instruction-updates`，
  `check-model` 按**闭集**校验值（`instruction-updates-of`，写错名字指名报错），缺省落在
  `fold-and-assemble`（`:replace`），`model-row` 只在文件写过时才带上它。内联那个扁平形状也跟着带。
- 拼装点在 `edge/ag-ui/place-updates`（纯函数）：插在**最后一条 user 消息之前**；最后一条不是 user 就
  **答 nil**，调用方退回 `:replace` 并记一行 `:instruction/update-unplaced`。`developer` 这个 role 只
  在这里拼一次。
- **更新行落在记录上**（票 03 的第 2 条）：`edge/http.clj` 在每个 run 写条目行时，把更新行插在
  「新提问」那条条目之前；来源是 `"instruction-update"`。它**不是会话条目**（`replay/entries` /
  `trajectory/entry-row?` 都不收）。

### 票 03（不支持的端点与读侧三处）

- `:replace` 是缺省那一档，也是「没有记忆 / 历史不合法」时的落点；它与 `:in-place` 各有一条端到端用例
  （`http_test`：两条 run、中间换一次 hook 集合，断言 message[0] 逐字节相同 / 换成新全文、更新条数）。
- 记录：`message` 行的 submitted 侧含那条 developer 行（用例读记录断言）。
- `run-segments` 仍按 record kind 切；developer 行不是 entry，切分不变。
- `context` 的归属**定为 conversation 桶**（`shares` 的 docstring 写了理由：`system` 桶就是记录里那一条
  role=system 的行，更新是一条尾部消息）。

### 票 06（压力表的锚点）

- 判据加了**交付方式**一格：system 行的信封写 `:instruction-updates`，`band-step` 折成 `latest-mode`，
  `anchored?` 要求锚那次与最新一次**同档**。`:sig` 只分得出「同档下 hook 集合变没变」——`:in-place` 下
  system 行是冻结的，hook 变了 `:sig` 也不变（锚点保留、新指令算进 delta）；`:replace` 下 system 行换成
  新文本，`:sig` 变（锚点作废）。跨档切换 message[0] 是另一份字节，所以同档这一条把两种切换都作废。
- 老记录（信封没有这个键）读成 `:replace`，保守那一档。
- `instruction-update` 行加进了 `band-step` 的「run 自己的注入」集合，所以**在线表针与离线折法对同一份
  记录给出同一个答案**（`messages-in` 的 `injected-rows` 本来就不看来源、只看有没有 id）。

### 票 04（按模型配置能力位）

- **后端与设置页已落**：`ModelRow` / payload 多一个 `"instruction-updates"`；Models 页每个 model 行多一个
  **三态** `<select>`（未声明 / `in-place` / `replace`），未声明就删键；中英两套文案一起加。
- **决策 8 的内置前缀预填也已落**（主人当日拍板「照票做，但只对走 Models 接口那些 provider；手填的按
  custom 走」）：`cap/providers.clj` 里一张 `instruction-updates-hints`（前缀 → 值的 vector，旁边逐行写
  依据），`suggested-instruction-updates` 是一个**收表的纯函数**、最长前缀赢；`probe-models` 把 id 变成行
  （`{:id .. :instruction-updates ..}`，没命中的没有那个键），`*list-models*` 那道缝合线答的还是 id 向量；
  前端只照搬答案预填，自己不做前缀匹配，手打进来的 id 不预填。**实测记在旁边的一句话里**：id 名字是模型、
  不是端点——kongming 那台网关列 `deepseek-*` 却拿 422 拒 `developer`（2026-09-25 实测）。
- **真机走查**（2026-09-25）：真配置的一份临时副本 + 真厂商 + Playwright。三态控件看得见；Fetch 一个 provider
  之后命中前缀表的 id 印「建议：`in-place`」（`z-ai/glm-5.3-prime`、`openai/gpt-6-luna-pro`），没命中的只有
  id（`fireworks/ember-1`）；take 进来后命中那行的 `<select>` 停在 `in-place`、没命中的停在未声明。

### 票 05（收口）

- `docs/architecture/overview.md`：铁律 2 的推论补了「送达有两种，选择权在端点能力位」；状态表里
  system 消息那一行补了那份**进程内存**。
- `docs/architecture/providers.md`：model 条目多了 `:instruction-updates` 一段（闭集、缺省、报告与解析
  各说各的那半句）。
- `docs/architecture/edge.md`：`message` 行那一节补了 `:in-place` 的字节落法、`instruction-update` 行、
  以及 system 行信封上的 `:instruction-updates`。
- `docs/architecture/client.md`：设置面板那一节补了 Models 页那个三态控件。
- **ADR**：没有单开。交付方式这一条已经是架构级的（铁律 2 的推论），落进了 `overview.md`；如果以后
  「前缀怎么保」还要长，再单开一条。
- 全量数字见票 05 的 `## Comments`（跑在 main 上）。

### 尚未做的（如实说）

1. 决策 6 里「provider 中途换」那一格：该块已退场，本特征不涉及；换 model 会改 route，压力表那一格照旧作废。
