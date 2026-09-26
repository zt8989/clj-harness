# spec: skills 与 instructions（会话开场时注入的两块约定内容）

让模型**看得见并加载**宿主约定位置的技能，同时**总是读到**约定位置的指令文件。两者注入的**形式相同**：

| | 位置（默认） | 注入的形态 |
| --- | --- | --- |
| 指令 | `~/AGENTS.md`（用户级）、`<project_dir>/AGENTS.md`（项目级） | 每个文件**一条 `role=user` 消息**，`<instructions path="…">` 包裹 |
| 技能清单 | `~/.agents/skills/*/SKILL.md`、`<project_dir>/.agents/skills/*/SKILL.md` | **一条 `role=user` 消息**，`<skills>` 包裹，每技能一行 |
| 技能正文 | 同上 | 模型调用 `skill` 后，`<skill name="…">` 包裹的 **`role=user`** 消息 |

**`prompt.md` 是整场会话唯一的 system 消息**，一字不动（冻结是为了 prefill 缓存）。其余全部落在 **user 侧**。

**前端一个字都不出现**——不是靠前端过滤，而是这些消息**从不产生任何 AG-UI 帧**，客户端永远收不到它们。
界面上只有一张普通的 `skill` 工具卡。它们只存在于服务端面向模型的那一侧，并且照旧写进 jsonl 的 `message` 行
（模型看到了什么，日志就有什么）。

只加载，不创作：不写技能、不装技能、不做授权。

## 为什么是这一套

参考实现是 `/Users/zhouteng/Documents/workspace/applepi`（ADR-0020 + `packages/extension/skills.ts`）。

**照搬的形状**（它对了两件事）：

- **目录进提示词、正文进消息。** 把全文拼进系统提示会随加载数量无界膨胀；目录（每技能一行）是 O(技能数) 的有界量。
- **注入用 `role=user`。** 各协议对 user 角色的位置保真度最高（anthropic 会把 system 抬到顶部），
  而且模型能靠角色/标签分辨「这不是刚才那位真人说的话」。

**不能照搬的一步：applepi 把注入物推进自己持有的历史，本仓没有地方可推。** applepi 的服务端持有会话
（jsonl + `SessionContext`），所以 `skill_load.execute` 能 `messages.push()`；本仓反过来——**对话归客户端所有，
服务端每轮现收现算**（`harness.ag_ui/inbound` 把客户端的消息折成 provider 形状，jsonl 只是记录）。
于是：存服务端内存则刷新即失；发给客户端则前端会画出来。

结论是本仓的注入必须是**派生的，不是累积的**：技能正文由会话自身推导（扫描成功的 `skill` 调用，
在它的 tool 结果之后补上那条 user 消息）；指令与清单则每轮现读现拼。客户端手里始终只有
tool_call + tool 结果，注入物只在服务端那一侧存在——这恰好就是「属于 system 的那一部分」的字面意思。

## 决策

1. **只有 `prompt.md` 是 system 消息。** 它冻结、一字不动，仍是消息向量的第一条；prefill 缓存的前缀因此照旧命中。
   其余注入——指令、技能清单、技能正文——**一律 `role=user`**。理由有两条：一是本仓的
   `inbound` 只有「客户端带了 system 就换成冻结的、没带就前置一条」这一条规则，多加 system 消息要把那条规则
   复杂化；二是 role 本身就是给模型的框架（「这是被放进来的东西，不是人刚打的字」），
   标签再补一层明确的边界。

2. **两块内容的注入位置与顺序固定**，由一条断言钉住：

   ```
   [system  prompt.md（冻结）]
   [user    <instructions path="~/AGENTS.md">…</instructions>]        全局，先
   [user    <instructions path="<project>/AGENTS.md">…</instructions>] 项目，后（更具体、离对话更近）
   [user    <skills>…清单…</skills>]                                   能力菜单
   [...客户端带来的会话消息...]
   [user    context（既有：每轮请求携带的 context，尾随，一字不动）]
   ```

   **顺序是语义，不是排版**：常驻规则在前、能力菜单在后、对话紧随其后。它只在一个地方决定（见决策 3），
   不许散落在调用点。**这个前缀随会话稳定**，所以它照旧被 prefill 缓存命中；只有改了 AGENTS.md 或技能集才会 miss 一次。

3. **位置的解析是纯函数，配置与项目目录由调用方递进来。** 技能与指令各自答「默认是哪几个」，但都
   `(cfg, project-dir)` 入参、**不查绑定、不读 harness.edn**。这不是洁癖，是断环：围栏住在
   `harness.project`，它必须知道技能根（决策 12），所以 `harness.project` 会 require 技能那一半；
   反过来技能那一半**不能** require `harness.project`，否则就是 `skills → project → skills` 的环
   （Clojure 会在 `require` 时就报出来）。

   调用方手里本来就有这两样：围栏（在 `harness.project` 里）、`skill` 工具体（`harness.tools` 已 require project）、
   以及拼装注入块的那一处（决策 8）。三处各写一行 `(skills/roots cfg dir)`，没有环，也不需要第三个中间 ns。

4. **两处默认位置 + `harness.edn` 整表替换。** 复用 `harness.project/harness-config` 已有的两级浅合并
   （用户级 `~/.clj-harness/harness.edn`，项目级 `<project>/.harness/harness.edn`）——**合并行为一个字不改**，
   两个键**各自内部**按键合并（与 `:editing` 同一纪律），每次调用现读，改配置不需要重启：

   ```edn
   {:skills       {:roots ["/abs/path/to/skills" "relative/to/project"]}
    :instructions {:files ["/abs/AGENTS.md" "docs/AGENTS.md"]}}
   ```

   两个键都**整表替换**该半边的默认值（不是追加）。`:instructions` 里的相对路径按工具路径的规矩解析
   （绑定项目时相对项目根，绝对路径直通），所以 `["AGENTS.md"]` 就是「只要项目那一份」。

   **用户级默认取 OS 家目录**（`user.home`），**不跟随 `CLJ_HARNESS_HOME`**：那两处是宿主的约定位置
   （与 ZCode / Claude 读的是同一份文件），不是 harness 的配置家目录；把配置家目录挪到别处不该让另一批技能凭空消失。

5. **OS 家目录上一个测试缝，两个特性共用。** `~/AGENTS.md` 与 `~/.agents/skills` 都挂在 OS 家目录下，
   所以缝只需要一条：`harness.home` 多一个 `(user-home)` 与它的 `*user-home-override*`，
   **测试运行器**把它钉在自己的临时目录（`isolate!` 今天已经在改 `home/*root-override*`，同缝加一处）。

   这条不是卫生，是必须：本机 `~/AGENTS.md` 有 4.1 KB、`~/.agents/skills` 有 51 份技能。不隔离的话，
   整套测试会突然依赖开发者的家目录内容，而且**每条既有断言都会多出三条 user 消息**
   （两份 AGENTS.md + 一条技能清单）。

6. **正文与清单一律不截断。** 指令被截断就是坏指令；清单里每条技能的 description 正是模型据以选择的依据
   （本机实测：51 份技能的全部 description 合计 10.5 KB ≈ 2.6k token），裁长度省下的正是选择所需的那截。

   **这是对 applepi 的一处有意偏离**：它把清单里的 description 截到 80 字符（`DESCRIPTION_LIMIT`），
   而实测显示 "Use when the user says…" 那一截大多落在 80 之后。不设上限的代价写清楚：一个超大
   `AGENTS.md` 会原样进上下文，所以**自省面报出每块的字符数**，让重量看得见——但绝不静默裁剪。

7. **指令文件读不动是硬失败，技能文件读不动是诊断。** 这条界沿用本仓既有的「缺失是空、损坏是失败」纪律，
   但按**单位**分开：

   - **AGENTS.md 是用户对这场会话的显式配置**，与 config.edn / harness.edn 同一族。文件存在却读不出来
     （权限、非 UTF-8）→ **指名失败**，说出绝对路径与原因，run 不开始。静默跳过等于会话按一套不是用户写的
     规矩在跑。文件**不存在**或**只有空白** → 不注入那一条，这不是错误。
   - **SKILL.md 是菜单上的一项**，坏一份不能拖垮一场会话：读不动 / 没有 frontmatter / 没有 `description` /
     `name` 与目录名不符 —— 该技能**不进清单**，但在自省里可见、带原因，被调用时得到**同一句话**
     （说法只有一份，两处共用）。

8. **拼装块的那一处只有一个。** 「这场会话开场时拿到哪几块、什么顺序」是一个问题、一个答案、一条有序向量，
   归一个 ns 所有（它 require 技能那一半，并负责把两边拼起来）。三块各自的**来源与诊断**也在这里回答：
   每块报出它来自哪个文件、字符数，以及被跳过的（坏技能、`disable-model-invocation` 的技能、
   空白指令文件）与原因。**顺序散落在调用点就是把一个语义决定藏进两行 `into`**，所以不许。

9. **技能正文是派生的。** 一条纯函数（provider 形状的消息向量 → 同样的向量）：
   扫出 assistant 消息里名为 `skill` 的 tool_call，按 `tool_call_id` 配对它的 tool 结果；
   结果是**那句加载确认**时（见决策 10），在该 tool 结果之后插入 `<skill name="X">…</skill>` 的 user 消息。
   **同名只注入第一次**（重复调用不重复占上下文）；**幂等**（原位已有那条就不重复插）。

   **施加点是 `harness.loop/drive!`：每次 `llm/stream!` 之前对 history 施加一次。** 不放在 `inbound`——
   模型调用 `skill` 就是为了**现在**照着做，等下一轮等于白调；幂等让「每轮施加」不需要任何簿记。

   **正文从根现读**（每轮现解析），与 config.edn / harness.edn / providers.edn 同一条纪律：改一份技能，
   下一轮就生效。代价写清楚：**正文因此不冻结在会话里**——技能在会话中途从根里消失时，注入位改成一句
   点名说明（「技能 X 已不在任何根里」），**绝不静默少一段指令**。

10. **`skill` 工具：一个参数 `{name}`，成功回一句关于会话的话。**

    ```
    skill "to-tickets" loaded — its instructions are in this conversation from here on (12,543 chars).
    ```

    这句短语同时是**注入的判据**（决策 9 靠它分辨「真加载过」与「被否决 / 被禁用 / 参数缺失」），
    所以这个前缀由工具与派生函数**共用一份常量**，不许两处各写一遍。
    定位**只按清单里的 id 查表，从不拼路径**（`../../etc/passwd` 只是一个不存在的名字）。
    未知名字、坏技能 → **指名拒绝**，说出收到的名字与本会话能用的名字（沿用
    `harness.providers` 那句 "no provider named X; the registry defines …" 的形状），`:error` 为真。
    **不标 `:requires-approval`**：读一份指令不是副作用；正文里让人做的事，各自过各自那道缝。


> **更正（2026-09-26，票 01 of `.scratch/skill-body-in-result`）：决策 9 / 10 描述的「工具回一句确认、正文由派生补一条 `<skill>` 消息」已经不成立。**
> `skill` 的**工具结果就是正文本身**（末尾一行说技能目录），工具路径不再产生 `<skill>` 消息、`:context/injected` 事件或那张卡；
> `loaded-prefix` / `loaded-summary` / `load-confirmations` 随之删除。
> 派生只剩**人的 `/name`** 这一半——它没有工具结果可以携带正文。
> 上面第 9 / 10 条与「验收主线」第 2 条里凡说「工具加载的正文是一份注入」的句子，以该票为准。
11. **`InstructionsLoaded` 钩子点接线。** 点表里**已经声明了这个点**（`payload #{:path}`，
    "an instruction file is folded into the run's context"），而本特征正是它的触发源——它的子系统到了，
    点就该活。每折叠一个文件触发一次，观察者（`:gate? false`），verdict 丢弃，失败按点自己的 `:on-error`。

    **实现约束**：run 作用域的 sink 由 http 边 binding，所以**折叠必须发生在那个 binding 之内**，
    否则 `hook/emit` 拿到 nil sink、一个钩子都不触发（边今天是在 binding 之前就调 `ag/inbound` 的）。

12. **技能根进围栏的允许集；AGENTS.md 不进，也不许扩围栏。** 技能正文里常写「读 `references/x.md`」，
    这些路径在项目目录之外，今天会被围栏拦下停泊审批——每读一份参考文件点一次批准，等于把技能废掉。
    与配置家目录同级：那是 harness 与人类自己装进来的东西。`:approval {:strict true}` 不会把它们收回去
    （strict 收的是项目目录那条）。

    **指令文件本身不经过围栏**：它们是服务端读的，不是 `read` 工具调的，所以 -- 一条 `~/AGENTS.md`
    里写的「去读 ~/notes/x.md」仍然照常停泊。**AGENTS.md 的内容不能成为放行依据**——文件能扩权是另一套
    安全故事，不是这次的范围。

13. **前端零改动。** 加载技能在界面上就是一张普通的 `skill` 工具卡（assistant-ui 04 已落地的折叠形状），
    卡的正文是那句确认。不新增面板、不加 `/api/skills` 端点、不改 `ui/` 一行。

14. **不加 jsonl 行种类、不改 AG-UI 帧形状、不动 CORS。** 注入物本身就是 `message` 行；
    `InstructionsLoaded` 走既有的 `hook/<point>` 审计行。清单不进日志——它是**提示词的一部分**，而提示词从不记。

## 非目标

- 不写、不装、不改技能；不做技能市场、版本、依赖、安装器。
- **不做 `/` 触发面**：applepi 有 `/` 面板与 PATCH `skill-load` 这条「人也能加载」的路；本仓没有斜杠输入面，
  本次只有模型侧。
- **不加 `$ARGUMENTS` 替换**、不读 `argument-hint`：调用者就是模型，上下文本来在它手里。
- **不信 `allowed-tools`。** 本仓的工具表由会话与编辑模式决定，不让一个技能文件扩权。
- **不读第三处**：`CLAUDE.md` / `~/.claude/AGENTS.md` / `~/.zcode/skills` / `.cursorrules` 一律不读；
  默认就是这两类约定位置，要加就在 `:roots` / `:files` 里点名。（本机 `~/.claude/CLAUDE.md` 恰好是
  `~/AGENTS.md` 的软链，但那是宿主的事，我们按自己的规则读。）
- 不新增钩子点：`InstructionsLoaded` 是既有声明，本特征只是让它活。
- 不改 `prompt.md`（它冻结；能力由工具自己的描述与那几条 user 消息宣布）。
- 不做 UI（决策 13）、不新增 jsonl 行种类、不改 AG-UI 帧、不动 CORS。
- 不往仓库里塞示例 `.agents/skills/` 或示例 AGENTS.md：测试夹具落在临时目录（既有做法）。

## 验收主线

离线全量 `harness.test-runner` 全绿（**基线 247 tests / 1221 assertions**）。**测试运行器把 OS 家目录
钉在临时目录**，所以「家目录里什么都没有」时：只有 `prompt.md` 一条 system、其余向量与今天**逐字节相同**，
既有断言一条不动；新增断言的夹具全部落在临时目录。此外真机（有 api-key 的环境）跑五条：

1. 新会话里模型说得出 `~/.agents/skills` 里的技能名（清单块在起作用），并**主动遵守项目 AGENTS.md**
   （例如它写着的文字规范 / 流程）；
2. 模型调用 `skill` 加载一个，**同一回合**就照着正文做，且 jsonl 的 `message` 行里能看到那条
   `<skill name="…">` 的 user 消息；
3. 前端从头到尾没有这些消息的任何痕迹：只有一张 `skill` 工具卡；
4. 绑定项目目录后，`<project_dir>/.agents/skills` 里的同名技能赢过用户级，清单里只有一行；
   两份 AGENTS.md 都在，且项目那份在后；
5. 跟着技能正文里的相对路径 `read` 一份参考文件，**不出现审批卡**。

## 状态

**01–05 全部落地**（2026-09-15，分支 `skills-and-instructions`；票已按仓库约定删除，记录归本 spec 与
git 历史）。**290 tests / 1444 assertions 全绿**（基线 247 / 1221），连跑三轮一致。

落地时撞出来的、票面没写的东西，按重要性排：

1. **`(seq xs)` 与裸的真值判断不是同一件事。** `parse-frontmatter` 最初写成 `(if-not ls ...)`，
   而 `(rest [x])` 是**空列表——真值**，于是最后一行拿到 nil 交给正则，每一份 frontmatter 都在最后
   一行炸掉。这个 bug 只在有 frontmatter 的文件上出现，而测试夹具正是有 frontmatter 的那种。
2. **`io/delete-file` 不递归**，而且它的 `silently` 参数把失败变成 `deleteOnExit` 排队——所以
   「删掉这个目录」的一行写法对一个非空目录是**静默 no-op**。夹具清理必须自底向上遍历。
   这个坑在本特征里出现了两次（测试的家目录清理、以及测「技能消失」时删目录）。
3. **边的 hook sink binding 必须包住 set-up。** 原来的写法是 `when provider` 之后才 binding，
   而折叠指令文件发生在 `ag/inbound` 之前——`InstructionsLoaded` 会拿到 nil sink，一个钩子都不触发。
   这是本次唯一一处**不改就会静默失效**的接线。
4. **`harness.home` 不只是配置根，它是两层 floor。** OS 家目录是第二层，且**不跟随
   `CLJ_HARNESS_HOME`**；两个测试缝因此都要（`*root-override*` 与 `*user-home-override*`），
   并且**互为兄弟而非嵌套**——把用户家目录放进配置根里会让它落进围栏的允许集，
   于是围栏测试问的问题被安排本身悄悄回答了。
5. **e2e server 也要钉 OS 家目录。** `CLJ_HARNESS_HOME` 管不到它（那是 JVM 的 `user.home`），
   而 `npm test` 的后端跑在孩子进程里——不钉的话开发者自己的 `~/AGENTS.md` 会搭上每一次请求。
6. **两个键的段本身必须是 map。** `{:skills 42}` 上直接 `contains?` 会抛一句既不说键也不说文件的
   JVM 错误；`harness.home/as-config-section` 因此先校验段、再问键。

### 真机验收：机械部分已验，真实模型部分未做

五条验收里的机械部分，由 `harness.http-test` 的两条端到端用例在**真实 HTTP 边 + 脚本 provider**
上验过（`an-opening-block-reaches-the-model-and-never-the-client`、
`an-unreadable-instruction-file-stops-the-run-by-name`）：

- ✅ 模型上下文里能看到清单与两份指令，顺序是「全局 → 项目 → 清单」；
- ✅ 加载后**同一回合**就有 `<skill name="alpha">` 的 user 消息，且 jsonl 的 `message` 行里有；
- ✅ **没有任何一帧**带上这些内容（脚本化的帧序列里逐字搜过）；`skill` 调用本身照常出现在 wire 上；
- ✅ 项目层同名技能赢过用户层（`skills_test` 的优先级用例）；
- ✅ 参考文件不 park、技能根之外的路径照常 park（`project_test` + `approval_test`）。

**未做**：在一台有 api-key 的机器上用**真实模型**跑一遍（看模型是否真的主动遵守 AGENTS.md、
是否真的按描述选中技能）。这一条是人的判断，脚本验不了——记在这里，不假装它过了。

### 已知：`cd ui && npm test` 有一条**与本次无关**的红

`approval > a-parked-write-runs-only-after-approval` 失败
（`expected [ 'tool:write', 'RUN_FINISHED' ] to include 'RUN_FINISHED/INTERRUPT'`）。
**在基线 `82a63d4` 的干净检出上逐字复现**（10 passed / 1 failed），不是本次改出来的。
本特征是 JVM 侧特性，`ui/` 一行未改，e2e server 的改动只是多钉一个家目录。
按 repository 的纪律，这条红要么是它自己的一个 bug、要么是一个已经失去意义的用例——
它是**既存**问题，留待处理，不在本特征范围内假装绿掉。

## 备注

- 与 `hashline-edit` 的交界：那套按模式决定工具表的动作（hashline-edit 03）只管编辑工具，
  `skill` 在两套模式下都在。落地时别让它被模式过滤器顺手挡掉。
- 与 `hook-engine` 的交界：那里写了「P3 的点声明了但不会触发，直到它所属的子系统被建出来」。
  本特征就是 `InstructionsLoaded` 的子系统。
- 本机实测（2026-09-15）：`~/.agents/skills` 51 份技能、全部 `name` 与目录名一致、1 份用块标量
  description、14 份带 `disable-model-invocation: true`、1 份 `hidden: true`、正文最长 12.5 KB；
  `~/AGENTS.md` 4.1 KB，本仓 `AGENTS.md` 509 B。
- 参考实现：applepi `packages/extension/skills.ts` + `docs/adr/0020-skill-injection-user-message.md`
  （目录三源重扫、正文 `<skill name>` 包裹的 user 消息、幂等按名查重、resume 只丢头部连续 system 块）。
  本仓取的是前三条形状；第四条（resume 规则）在本仓不成立——本仓没有服务端历史可丢。
