# 02 — 只给会看图的模型附图（与守卫同一条判据）

**What to build:** composer 知道本会话此刻被哪个 model 服务、那个 model 声明收不收图，并且在附图这件事上
用它：声明里有 `image`，或者**根本没声明**，粘贴 / 拖放 / `+` 照常；声明了但不含 `image`，三条路都拒绝，
并给一句指名的话（哪个 model、不收哪个模态、怎么改）。

**判据必须与服务端 `harness.edge.ag_ui/undeclared-input` 是同一条，因为两个方向都是错的：**

- **比服务端严**（把「没声明」当成「不收」）→ 一个今天跑得通的配置被界面挡掉，而服务端从没说过不能发。
  「没有声明就是没有承诺」是那条规则的原话。
- **比服务端松** → 整条消息被 `RUN_ERROR` 吃掉：composer 已经清空，打的字和那张图都没了。这正是本仓
  「不许吃掉别人打的字」那条纪律要防的（同一条推理见 `approval-gate.tsx` 里 `isSendDisabled` 的注释）。

**顺带的决定：这个判据要一句拒绝的话，而那句话是全特征唯一一处「拒绝长什么样」的缝。** 03 会复用同一处，
所以 03 也挡在这一票后面。

**Blocked by:** 01（附图得先能发生，才谈得上什么时候不许）

**Status:** ready-for-agent

## 验收

- [ ] 判据是一个**纯函数**，落在一个**不 import 任何东西**的模块里（`ui/src/lib/` 下新开一个）——
      它要能被 vitest 按相对路径直接 import，而 `vitest.config.ts` 不加载 vite 的 `@` 别名，那份注释
      明写了这是前提（`format.ts` 是同一个先例）。
- [ ] 三态各有一条断言：`["text","image"]` → 收；`["text"]` → 拒；缺字段 → 收、~~空集 → 收~~
      （**落地时按服务端改成「空集 → 拒」**，见 `## Comments`）。这三条与
      `test/harness/edge/ag_ui_test.clj` 里 `undeclared-input` 的几条**逐条对齐**，不另立一套。
- [ ] 数据从 `GET /api/model?threadId=…` 的 `:input` 来（那个端点存在就是为了这件事，`model-get` 的
      docstring 自己写着「for a client deciding whether to offer an image picker」）。**不新增端点、
      不改 `GET /api/choices` 的形状。**
- [ ] **判据随模型当次刷新**：真机把会话的模型换成一个不收图的，**不重载页面**，立刻粘一张图 → 被拒；
      换回会看图的 → 立刻又收。
- [ ] 拒绝有声音：composer 里出现一句指名的话（`role="alert"` + `data-slot`），句子里有 model 的 id；
      不受图时 `+` 这个按钮**不出现**，或 disabled 且 `title` 说明为什么——**二选一，实现时定死并写进
      本票的验收回执**。截图 `evidence/t02-01-refused.png`。
- [ ] 被拒之后 composer 里原有的文字**一个字不少**，已经挂着的附件也不动。
- [ ] vitest 新增一例盖这个纯函数（`EXPECTED_CASES` 相应 +1）。
- [ ] 服务端零 diff（守卫已经在，这一票只让界面在更早的地方说同一句话）。
- [ ] `npm run typecheck` / `npm run build` / `npm test` 全绿。

## Comments

### 回执（2026-09-17）：`+` 这一处定死为「disabled + title」，以及票面那条「空集 → 收」是错的

**一、`+` 定死为 disabled 且带 title（不是不出现）。** 理由与 `skill-picker` 把坏 skill 仍列出来那条同源：
一个悄悄消失的按钮和一颗从来没做出来的按钮，从外面看一模一样，而这两件事里更难查的那一件不该是 bug 的产物。
还有一层是时序：粘贴与拖放只能在被拒之后才说得上话，而 `+` 是人决定要不要试的那一刻——理由必须在那之前就在。
title 挂在**包着按钮的 `span`** 上而不是按钮自己身上：被 disabled 的按钮在值得在意的浏览器里收不到指针事件，
挂它身上的 `title` 是一句永远没人看得见的提示（真机截图 `evidence/t02-01-refused.png`，DOM 里
`[data-slot="composer-attach-disabled"]` 的 `title` 就是那句拒话）。

**二、「缺字段 / 空集 → 收」里，空集那一半是错的；落地成「缺字段 → 收，空集 → 拒」。** 票面把「没声明」
与「声明了空集」当成同一件事，服务端不是：

```
declared nil  -> []          ; 没声明 → 不守
declared #{}  -> [:image]    ; 声明了空集 → 拒
```

（`clojure -M:test` 里对 `harness.edge.ag-ui/undeclared-input` 实测，四态见 `test/harness/edge/ag_ui_test.clj`
与 `ui/test/suites/attachments.ts`。）线上两个状态也分得开：`providers/wire` 对没声明的**不写这个键**，
对声明了空集的写 `[]`。所以「空集 → 收」会让客户端**比服务端松**——正是本票开头自己列为错的那一个方向，
代价是整条消息 `RUN_ERROR`、打的字和那张图一起没。判据因此逐条对齐服务端，空集按拒。

**三、其余按票面落地**：纯函数在 `ui/src/lib/attachment-rules.ts`（不 import 任何东西），数据来自
`GET /api/model?threadId=…` 的 `input`，服务端零 diff；判据随模型当次刷新（真机：不重载页面切换
`sees-images` / `text-only`，拒与收立刻跟着变），被拒时输入框里的字与已挂的附件都不动。

