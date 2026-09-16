# spec: 技能列表（在输入框打 `/` 弹出的那份菜单）

**参考**：一张宿主（ZCode）的截图——`$` 前缀的技能列表，每行 `名字 + 来源 · 描述`，底下一条窄提示
「输入内容以搜索命令、技能或子智能体」。参考的是**列表上读到什么**：名字、它属于哪一层、它做什么。
**不参考**它的前缀字符（本仓触发的字是 `/`，因为服务端只认 `/name`），也不把它的命令 / 子智能体搬过来。

**一句话**：人在输入框里打 `/`，harness 弹出**这场会话能加载的技能**——一张平铺的表，
每行带它的**技能层**（系统级 / 项目级）、名字与描述；选中一个，输入框里就是 `/名字 `。
**服务端一个字都不改**（`/name` 那条加载路径早已存在，见 `harness.skills/slash-request`），
**前端也几乎不用自己写弹层**——assistant-ui 已经带了一套触发面板（见决策 1）。

## 问题（对今天页面的实测）

1. **技能目录只存在于人的脑子里。** `/name` 是人的加载路径，但页面上没有任何一份目录：要加载一个技能，
   得先背出它的名字。（本机 `~/.agents/skills` 2026-09-16 实测 53 份，随本机而变——背 53 个名字不是设计。）
2. **打错的代价落在对话里，而人在页面上看不到。** 名字打错（或那份技能坏了），服务端照设计注入一句
   **点名说明**（「没有叫 X 的技能……本会话能加载……」），人是在**发出去之后**从模型嘴里知道的。
3. **层的信息今天只在 `:root` 里。** 技能有两个默认根，同名时**前面的根赢**（见 `skills/scan`），
   而这件事在页面上一个字都没有——`<OS 家目录>/.agents/skills` 与 `<项目>/.agents/skills`
   谁供给了这一条，只有读 `:root` 的人知道。
4. **上游的能力已经在那，只是没接。** 装着的 `@assistant-ui/react` 里有一套完整的触发面板
   （`ComposerPrimitive.Unstable_TriggerPopoverRoot` / `TriggerPopover` / `.Directive` / `.Items` /
   `.Item`，加 `Unstable_TriggerAdapter` 这条适配器形状），`ComposerPrimitive.Input` 也已经把
   **光标位置上报**、**键盘消费**、**combobox 的四个 ARIA 属性**接好了——而本仓的 composer 里
   **一个 trigger 都没注册**。所以本特征主要的活不是写弹层，是**接上**并把数据接对。

## 决策

1. **站在上游的触发面板上，不手写弹层。** `ComposerPrimitive.Unstable_TriggerPopoverRoot` 挂在
   **既有的 `ComposerFrame` 插入点**上（它今天已经套在 composer 外面，而 panel 必须是一个包住
   `ComposerPrimitive.Root` 的祖先），`char="/"`，行为子件用 `.Directive`。
   **手写一份的代价是三条已经接好的缝**：键盘的消费顺序（开着时 Enter 不能发送）、
   combobox 那四个 ARIA 属性、以及光标位置的上报——它们都在 `ComposerPrimitive.Input` 里，
   重写一遍等于把三条缝复制到我们这边，然后各自漂移。
2. **触发形状与服务端的 `slash-pattern` 同一形状。** 自定义 `matcher`：`/` 必须在**消息开头**
   （`offset === 0`），名字后面还没有空白。上游**默认** matcher 只要求前面是空白，于是 `see /alpha`
   也会弹——而服务端不认，列表要弹在**真的会被加载**的位置上。**不为列表放宽服务端**：
   那会多出第二条「什么算一次加载」的判据。
3. **一层一张徽标，平铺，不下钻。** 每行右边一点小字是它的层（`System` / `Project`，词由前端映射），
   不做「先选层、再选技能」的两级。上游的 `Categories` 正是那种下钻，**不采用**：
   参考截图是平铺 + 每行来源，而下钻多出来的一步，内容只是「确认我刚选的那个层」——
   与这个界面一贯的「少一步」相悖。
   **认不出的层不画徽标**（配置里写的 `:roots` 没有层，见决策 4），那一行的 `title` 里是它的根路径：
   一条路径比一个猜出来的层名诚实，也比它有用。
4. **列表的内容由服务端回答，前端不猜。** 新增一条只读端点 `GET /api/skills?threadId=…`：
   根是哪几个、同名谁赢、坏技能为什么坏、`disable-model-invocation` 能不能被人加载——这些答案只在
   `harness.skills` 里有一份。前端复刻一份就是第二份会漂移的答案（围栏那条纪律的同一句话）。

   形状（`layer` 在配置写的根上**缺席**）：

   ```json
   {"groups": [
     {"layer": "system", "root": "/Users/…/.agents/skills",
      "skills": [{"name": "alpha", "description": "…", "available": true, "reason": null}]},
     {"layer": "project", "root": "/proj/.agents/skills", "skills": []}
   ]}
   ```

   组按根的先后（系统级在前）；**空的组不出现**；同名被盖住的那份**不出现**（它不可加载，
   `scan` 今天就是这个行为，这里不新加「影子条目」）；没有 threadId 按未绑定算（与 `/api/choices` 同形）；
   一个技能都没有时是 `{"groups": []}` 而不是 404；非 GET 答 405。
   前端把组摊平成一张表，每行带着它那一组的 `layer` 与 `root`。

   **层的判据在「造出默认根」的那一处**：`skills/roots` 的默认两个根各自带一层（`:system` =
   `<OS 家目录>/.agents/skills`，`:project` = `<项目>/.agents/skills`），
   `:skills {:roots […]}` 写的根**一律不带层**。理由沿用既有那条纪律（见 `read-skill` 的 docstring）：
   配置表里没有「系统 / 项目」这回事，从列表位置猜一个层名就是**把猜测写成事实**。
   **插件级将来是这里多加一项**（多一个约定根带一个层），不是前端多一个分支。

5. **列表是「人能加载的」，不是「模型能用的」。** 两处与模型侧清单**故意不同**：

   - `disable-model-invocation: true` 的技能**进列表**：文件自己说这条不该由**模型**决定去用，
     人打 `/name` 就是人在决定，服务端放行（这条差别 `skills.clj` 已经写明）。
   - **坏技能也进列表**，带原因，但**不可选**：「一个静默消失的技能，与一个从没装过的技能，
     从外部看没有区别」——这条纪律在列表上就是「坏的那份要在，并说清为什么」。

6. **选中 = 上游的插入，我们不写插入逻辑。** 用 `.Directive` 行为 + 自定义 `formatter`，
   它的 `serialize(item)` 就是 `"/" + 名字`；上游在插入后**会补一个空格**并把光标放到空格之后
   （`TriggerSelectionResource` 的那几行），于是输入框里逐字是 `/名字 `。**不发送**：
   `/name` 后面那句「请照这个做」是打的人写的，服务端也明确「人打的字一个字不改」——
   替他发送，是把一个**人的决定**变成程序的猜测。
   用 `.Directive` 而不是 `.Action`：选中之后**什么都不执行**，文本就是全部效果，
   而 `.Action` 要一个 `onExecute`（还有个 `removeOnExecute` 的默认值要惦记）。

7. **过滤规则照上游那一条。** 平铺列表要自己实现 `search(query)`（没有 categories 时，
   上游那条回落路径会去遍历 categories，得到空表，所以必须给）：规则与上游的
   `matchesTriggerItemQuery` 同形——**id / label / description 的大小写无关包含**，
   顺序保持服务端给的顺序。这条不是抄一遍仪式，而是「名字与描述都能搜到」与「不重排」两件事各有一个出处。

8. **键盘四条与 ARIA 是上游的行为，票面上逐条量，不是实现。** ↑↓ 移（跳过不可选行）、
   Enter / Tab 选中、Esc 关掉、**开着时 Enter 不发送**；`role="listbox"` / `aria-activedescendant` 等
   四个属性由 `ComposerPrimitive.Input` 在与面板打开时自动加上。**所以不许动 `submitMode`。**
   哪一条不成立，**修在这里**，但优先在行为层修（`matcher` / `formatter` / 行为子件）；
   **不许改抄进来的 12 份元素**——真要改就得带 `LOCAL:` 记号，并在这张票里写明理由。

9. **零新前端依赖、零新测试环境。** 交互在**真机上量**（脚本化后端 + 假 `CLJ_HARNESS_HOME` +
   假 OS 家目录 + `cd ui && npm run dev` + 截图存 `.scratch/skill-picker/evidence/`），
   与 `composer-chrome` / `flat-step-rows` 同一条路。vitest 套件只加**走真 HTTP** 的那一条用例——
   那是它今天的形状（`ui/vitest.config.ts`：suites 「importing nothing from src/」，一个文件一个后端）。
   **不引入 jsdom / testing-library**：为一个弹层加一套 DOM 测试环境，比这个弹层本身重。

10. **命令与子智能体不做，但形状不许写死。** 本仓**没有**命令、没有子智能体（没有对应物可列），
    所以本次只有技能这一个种类；而列表**按服务端给的东西渲染**、条目数不固定、层的词由前端映射、
    认不出的层就不画徽标。将来加一样东西，是适配器多给一类（或 `metadata` 多一个层），
    不是前端重写一遍。

11. **只读端点不留痕**，与 `/api/model`、`/api/choices` 同一条：GET 不写 jsonl、不改任何状态。

## 非目标

- **不做命令、子智能体、插件**三样。插件级只留形状（决策 4 / 10）；命令与子智能体在本仓**不存在**。
- **不手写弹层 / 键盘 / ARIA**（决策 1、8）；**不用 `Categories` 下钻**（决策 3）；
  **不动 `submitMode`**（决策 8）。
- **不加第二个前缀**（`$` / `@`），**不改服务端的 `slash-pattern`**，**不改 `/name` 的语义**。
- **不读第四个 frontmatter 键**：`hidden` / `argument-hint` / `allowed-tools` 照旧只读不认。
  代价如实写：`hidden: true` 的技能**照旧出现在列表里**（它今天本来就进模型的清单）。
- **不新增 DOM 测试环境**、不加前端依赖（决策 9）。
- 不做技能市场 / 安装 / 编辑 / 授权；列表**只读**。
- 不改 AG-UI 帧、不加 jsonl 行种类、不改 CORS。
- 不修**同名前谁赢**这条既有行为（今天的代码是「系统级赢」，见备注），只在文档里把它说对。

## 验收主线

命令两连（离线全量）：`clojure -M:test -m harness.test-runner` 全绿、`cd ui && npm test` 全绿
（用例数按本特征落地后的数报，`EXPECTED_CASES` 的改动要有理由）；外加 `cd ui && npm run typecheck`
与 `cd ui && npm run build` 0 error。真机五条（脚本化后端 + 假 `CLJ_HARNESS_HOME` + 假 OS 家目录，
两边各播一份同名技能与一份独有技能）：

1. 空输入框打 `/`：**一张平铺的表**弹出在 composer 上方，系统级的技能与项目级的技能都在，
   每行带自己的层徽标，`System` 在前；两份同名技能**只出现一次**、带 `System` 徽标（顺序是机制，见备注）。
2. 继续打 `sh`：列表按名字过滤；打只在某条 description 里出现的词也能过滤到它。
3. 点一行：输入框里逐字是 `/名字 `，光标在空格后；再打一句话，发出去之后**同一个回合**里模型就照着
   那个技能的文字回答了（jsonl 的 `message` 行里能看到那条 `<skill name="…">`）。
4. 键盘四条各一次：↑↓ 移（含跨过坏技能那一行）、Enter 选中且**不发送**（输入框里的话还在）、
   Tab 也能选中、Esc 关掉且文本不变。
5. 坏技能（`SKILL.md` 的 `name` 与目录名不符）在列表里、带原因、**点不动**；
   `disable-model-invocation: true` 的技能在列表里且**能选中**——选完发出去，模型照着它做。
   顺带：配置里写了 `:roots` 的会话，那些根出来的行**没有层徽标**，`title` 里是根路径。

## 状态

**01–07 全部落地**（2026-09-16，分支 `skill-picker`，从 `3ac23d8` 起）。票已按仓库约定删除
（完成了的票不留、不标记），记录归这里与 git 历史。

命令四连（都在本分支的工作树上跑）：

| 命令 | 结果 |
|---|---|
| `clojure -M:test -m harness.test-runner` | **613 tests / 9810 assertions**，4 failures 0 errors（基线 607 / 9774，2 failures，见下） |
| `cd ui && npm test` | **14/14 passed**（基线 11/11；`EXPECTED_CASES` 11 → 14，多出来的是 `skills` 套件的三条） |
| `cd ui && npm run typecheck` | 0 error |
| `cd ui && npm run build` | 全绿（chunk 体积警告是既有的） |

**红与基线逐条对过**：4 条失败里 2 条是 `harness.project-test/a-binding-survives-a-real-restart`
（本机 JDK 25 把 native-access 警告打进子进程 stdout，基线就有），另 2 条是
`harness.http-test/the-projects-listing-joins-the-store-with-the-disk`（日志长度 / mtime 与列表快照不等）。
后者**不是本次引入的**，而且是**代码里写明会发生的竞态**：`:run/done` 的那批消息记录**落在终帧之后**
（见 `harness.http` 的 `run-agent!` 里那段注释），而这条用例在 RUN_FINISHED 之后立刻断言文件大小已经追上。
证据：在**干净检出**的 `3ac23d8` 上单跑这一个用例，两次里失败一次（临时 worktree，跑完即删）。
它该有自己的一个 bug 票，不在本特征里。

### 落地时撞出来的、票面没写的

1. **上游已经带了一整套触发面板，本特征因此从「写弹层」变成「接线」。** 原票面按手写弹层写：
   自己拦键盘、自己发 ARIA。开工前读 `@assistant-ui/react@0.15.19` 才发现
   `ComposerPrimitive.Unstable_TriggerPopover*` + `Unstable_TriggerAdapter` 都在，
   而 `ComposerPrimitive.Input` 已经把**光标位置上报**、**插件注册表式的按键消费**（开着时 Enter 不会
   发送）与 **combobox 四个属性**接好了。于是票面改成：挂点、`matcher`、`formatter`、`search` 四处接线，
   外加行怎么画。这一改把「Enter 不发送」从**要实现的难点**变成**要量的事实**。
2. **`matcher`、`formatter`、`search` 三个默认值都必须换掉**，各有理由：上游默认 matcher 只要求
   「前面是空白」，于是 `see /alpha` 也会弹——服务端不认它；`formatter.serialize` 决定输入框里落地的
   那串字（`/名字`），尾随空格与光标位置由上游负责；没有 categories 时上游那条回落路径会遍历 categories
   得到空表，所以过滤必须自己给（规则照它的 `matchesTriggerItemQuery`：名字与描述、大小写无关）。
3. **没有 categories 是决定**：上游的 `Categories` 是「先选层、再选技能」的下钻，与参考截图（平铺 + 每行
   来源）和这个界面一贯的「少一步」相悖。于是层是**行上的徽标**，数据仍是**按根分组的**（服务端答
   `groups`），前端把它摊平。
4. **↑↓ 到两端是「绕回去」，不是停住**（上游的行为）。真机上量到：打开时第 0 行已经高亮，
   `ArrowUp` 一次从 0 跳到末行；19 行时从 index 9 连按 23 次落在 index 13 —— `(9+23) mod 19`。
   票面原先写的是「停住不绕圈」，按「上游的行为优先、能不动就不动」改成如实记录。
5. **坏技能不在可选条目里，这就是它「不能选」的实现方式**：它们在面板里另起一段、**不经过
   `TriggerPopoverItem`**，所以方向键与 Enter 天然够不到（不是靠额外判断挡住的）。
6. **`http_test/wipe-conventions!` 原来是静默 no-op**：`io/delete-file` **不递归**，而 `.agents` 目录一旦
   有内容（一个技能就是一个目录）它就在 `silently` 下失败并排进 deleteOnExit——于是同一个 namespace 里
   **后跑的用例会继承前面播下的技能**，断言的是一个它没造过的家目录。本次改成自底向上删除
   （与 `skills_test` 早就写明的那条纪律一致），新用例也因此能断言「一个技能都没有」的会话。
7. **备注里那条既存矛盾已改**：`docs/architecture/skills-and-instructions.md:47` 现在写的是「前面的根赢同名，
   所以默认顺序下机器上的那份赢」，并点到钉住它的两个测试名。

### 真机主线：五条都量过（脚本化后端 + 假配置家 + 假 OS 家目录，Chromium 走查）

量到的数字与所见（截图在 `evidence/`）：

1. **两组、同名只出现一次**（`t04-01-two-layers.png`）：一颗 `System` 的 `shared`（描述是机器那份）
   与 `only-project` 的 `Project`；项目里同名的 `shared` 不出现。每行的 `title` 是它那个根的路径
   （`/tmp/sp-ui/user-home/.agents/skills` 与 `/private/tmp/sp-ui/project/.agents/skills`）。
2. **过滤**：`/sh` → 只剩 `shared`（`t04-02`）；`/human` → 只剩 `manual-only`，那个词只出现在它的
   description 里（`t04-03`）。
3. **选中即写入，不发送**：点 `shared` → 输入框逐字 `/shared `、光标在 8（空格之后）、面板关、消息数不变
   （`t04-04`）。键盘同路：`/alpha` + `↓` + `Enter` → `/alpha `，接着打 `please follow this` 发出去
   （`t04-07`），**同一回合**的记录里就有 `<skill name="alpha">` 的 user 消息与正文 `ALPHA BODY`
   （`.unbound/058f43a8….jsonl` 第 11 行），人打的那句话一个字没改（在 `input` 行里）。
4. **键盘四条**：打开时第 0 行高亮且 `aria-activedescendant` 已就位；`↑↓` 移（两端绕回）；
   `Enter` / `Tab` 都是选中；`Esc` 关掉且文本一字不改、**同一个文本状态下不再自己重开**、再打一个字就重开；
   `Shift+Enter` 仍是换行。**开着面板时按 `Enter`，输入框里的话还在、消息列表没有新消息**（`t05-02`）。
   高亮会跟着滚动（19 行、面板可视 286px / 内容 653px，走 12 步后高亮行仍在面板矩形内，`t05-01`）。
5. **坏技能与禁用技能**：`misnamed`（名字与目录不符）在表尾、划线、带「its name does not match its
   folder」、**不在可选条目里**；`manual-only`（`disable-model-invocation`）在可选条目里且能选中。
   另外两条**只能量、拍不出来**的：面板与 composer 的位置（列表底边 403 < 框顶 409，composer 高度
   开合都是 131px）与四个 ARIA 属性 + `role=listbox` + 焦点始终在 textarea。
6. **两种「没有东西可显示」也各量了一次**：服务端不可达时面板里是 `role="alert"` 的那句话（`t04-05`）；
   `{"groups": []}`（这个会话一个技能都没有）时**面板根本不在 DOM 里**，输入框上也没有
   `aria-controls` / `aria-expanded`（`t04-06`）。

### 还剩两件不属于本分支的活

- `docs/architecture.md` 的快照点（现在写着 `ded04d5`）要在**合进 main 的那一次**更新到落地提交——
  本特征在工作树里落地，还没有一个提交名可写。
- 上面那条 `the-projects-listing-joins-the-store-with-the-disk` 的竞态：要么给它自己的 bug 票，
  要么把断言改成等 `:run/done` 那批记录落盘再看。

## 备注

- **一条既存矛盾，已在票 07 里改对**：`docs/architecture/skills-and-instructions.md:47` 原来写
  「**前面的根赢同名**，所以项目里的一份同名技能盖过用户级的」——**后半句是反的**。
  `skills/roots` 给出的顺序是 `[系统级 项目级]`，而 `scan` 的规则是「前面的根赢」，
  所以**系统级赢**；`test/harness/skills_test.clj` 里那条测试名就写着 *LOSE a name conflict*。
  文档现在说的是现状（系统级赢，个项目级排后面），而**谁赢**这件事本身要不要翻过来
  （项目里钉的那份应该盖过机器上那份吗）**是另一个决定，不在本特征里**。
- **票 07 的闭表之外多动了一份 `README.md`**：那份文件的定位是「每个特性讲一遍为什么这样设计」
  （见 `docs/architecture.md` 的分工表），而技能那一节本来就讲着两条加载路径——人打 `/name` 那句话
  现在有了输入面，不写就只有读过 `docs/architecture/` 的人知道。加的是这一段的七行，不是新章节。
- 上游那套面板的 API 前缀是 `Unstable_`（`TriggerPopoverRoot` / `TriggerPopover` / `TriggerItem` /
  `TriggerAdapter`），版本是 `@assistant-ui/react@0.15.19`。**别抄它的默认值过来当规格**：
  `matcher`、`formatter`、`search` 三处我们都必须换掉（决策 2 / 6 / 7），
  而抄进来的 12 份元素与本特征无关——它们一行不改。
- 与本特征相邻的两处在办工作：`.worktrees/flat-step-rows`（工具行的形状）与
  `.worktrees/layer-layout`（`src/harness/` 分层改名）都不碰 composer 的弹层；`layer-layout`
  落地时会把 `harness.skills` 换个前缀，本特征的票面用**命名空间名**而不是路径写它。
