# 思考行：不自己展开，流式那段在行上滚，停下来回到首行

**由谁提的**：2026-09-22，主人一句话——*「不要默认展开思考，思考中那一行文字滚动显示，思考完成再回到第一行」*。
三句话是三条判据，本文件把每一条落到一处实现、一次实测上。

**推翻的是哪一条**：`.scratch/flat-step-rows/spec.md` 与 `.scratch/assistant-ui/issues/04-message-parts.md`
第 14 节（2026-09-17）写下的「**正在到达的那段思考自己展开**，用上游那扇跟随最新 token 的窗口滚动显示，
最后一个 token 落下再折回行上留首行」。那扇窗**还在**，但它只给**点它的人**开；两处按仓库规矩就地划掉
并在文末各追加一段复议。

## 三条判据，各自落在哪

| 主人说的 | 判据 | 落在哪 |
| --- | --- | --- |
| 不要默认展开思考 | 行仍是 disclosure，但 `open` 由行自己持有、初值 `false`；上游那条 `userOpen ?? (streaming \|\| defaultOpen)` 再没有机会替人点开 | `message-parts.tsx` 的 `ReasoningBlock`（`open` / `onOpenChange` 交给 `<ReasoningRoot>`） |
| 思考中那一行文字滚动显示 | 流式期间行上的摘要换成「**已经到了的那段的最后 120 字**」，而这一窗**从左边缘裁**：最新到达的字留在右边，旧的从左边跑出去——这就是滚动 | `lib/reasoning-preview.ts` 的 `tail` / `previewOf(…, running=true)` ＋ `styles.css` 的 `.aui-reasoning-trigger-tail` |
| 思考完成再回到第一行 | 不流式时回到原来那条规矩：第一个有字的 part 的首行、截 120 字、末尾 `…` | `lib/reasoning-preview.ts` 的 `firstLine` / `clip` / `previewOf(…, running=false)` |

## 决策与代价

1. **「不展开」不是「把 `streaming` 拿掉」。** `streaming` 照旧传给 `ReasoningRoot`：它还决定**打开着的**那扇窗
   跟不跟最新 token、长不长底部渐隐。真正拿掉的是**上游替人点头的那一下**——所以 `open` 收到 `ReasoningBlock`
   自己手里（初值 `false`），整套上游动画、scroll lock、`max-h-64` 全都不动。
   **代价**：接管了 open 状态之后，上游那条「流式开始/结束时自己播一次动画」的分支不再走（它本来就只在
   `!isControlled && userOpen === null && !defaultOpen` 时才走）——而那正是这次要停掉的东西。
   手动点开的人在流式期间拿到的仍是原来那扇实时窗（`streaming && open` 才长出底部渐隐与跟随）。

2. **「滚动」是物理的，不是跑马灯。** 窗里的字每来一个 token 就往左挪一点：`direction: rtl` 的那只盒子
   **从左边缘裁**（`overflow` 在 rtl 里溢的是**起始**那一侧），内层再 `direction: ltr` 把文字本身掰回来
   （inline-block 是 bidi 隔离，模型的中英混排照原样读）。选它而不是 CSS 跑马灯，三条理由：
   * **没有动画**，所以 `prefers-reduced-motion` 不需要特判——不是「关了动画仍能用」，是根本没有动画；
   * **不复制文本**，机器读屏不会把同一句读两遍；
   * 跑马灯的位移按内容宽度算（`translateX(-50%)`），而这里的内容**每个 token 都在变宽**，位移会跟着跳；
     物理滚动的位移就是新字本身，跳不了。
   **代价**：模型停顿时这一行也停。这是诚实的——那一行没有新东西可看——但它确实不是「一直在动」的那种跑马灯；
   如果主人要的是后者，这条决策就是改回来的地方。

3. **流式贴的是尾巴，不是首行、也不是整段。** 首行一秒后就冻住，而读者要看的正是模型**现在**在哪；
   整段跑马灯则要求读者从头跟，跟到一半时最新的话还没轮到——所以窗口贴着**已到达内容的末尾**。
   **代价**：行上的字每来一个 token 就换一次，`useAuiState` 的选择器每次都返回新字符串、行就重渲染一次；
   拿 120 字的封顶把每次重渲染按住（这也是原来那个 `PREVIEW_LIMIT` 的第二个用处：原来它是为了**不**重渲染，
   现在它只是把每次都变得很小）。**折着的那扇抽屉本身不画内容**（Radix 关着时不渲染子节点，实测高度 0、无文本），
   所以这场流式里真正在动的只有这一行。

4. **三条文字规则搬进 `lib/reasoning-preview.ts`，而不是留在 `message-parts.tsx`。** 那个文件进不了 UI 套件
   （它摸 assistant-ui runtime），而这三条规则恰好是**纯字符串规则**，搬出去就能被 `test/suites/reasoning-row.ts`
   当数测（三条用例）；剩下三条只有浏览器能看的判据去了走查。`firstLine` 同时被 `subjectOf` 给 `bash` / `eval`
   的摘要在用，所以它是导出、不是私有。

## 实测（2026-09-22，真 Chromium，`--scripted`）

`node .scratch/thinking-row-tail/walkthrough.mjs http://localhost:5393/` → **GREEN，20 条**（全表见
`evidence/README.md`）。几个数：

* 流式期间采到 **82 个活着的样子，81 个互不相同**；窗的左边缘从 `507px` 一路走到 `-250px`
  （`inner` 1417px 宽，盒子 632px）——**裁在左边、一直往左走**，这就是「滚动」。
* 每一份样子都是那段思考里**连续的一段字**，整窗（120 字）的位置从 `30` 单调走到 `7605`（全文 7871 字）。
* **一次也没有自己展开**：每个样子上 `data-state="closed"`、内容高 0px。
* 行高全程 **28px**（一行 13px 字 + `py-1.5`）：没有换行，下面的步骤不会被推走。
* 停下来之后行上回到首行 ` · 先读 deps.edn，确认依赖有没有变。`；手动点开是整段（7871 字），再点收起；
  刷新之后仍是折着的一行首行。

## 证据

| 文件 | 是什么 |
| --- | --- |
| `walkthrough.mjs` | 走查本体：CDP 限速 → 采样 → 20 条判据；期望值从 `script.json` 现算，不抄 |
| `script.json` | 那一条被「想」出来的话（7871 字，句子带编号，为的是窗口在全文里唯一） |
| `evidence/01-while-thinking.png` | 流式期间：行上是在滚的那一段 |
| `evidence/02-after-thinking.png` | 停下来：一行首行 |
| `evidence/03-opened-by-hand.png` | 点开：整段 |
| `evidence/04-restored.png` | 刷新之后：恢复出来的会话仍是折着的一行 |

## 没有做的事

* **不动抄来的 `reasoning.tsx` / `reasoning.aui.tsx`**：这次只用了它们的 `open` / `onOpenChange` 与
  `streaming` 三个已有的口子，一份抄来的文件都没改（`LOCAL:` 标注数不变）。
* **不动那一轮的折叠**（`.scratch/assistant-ui/issues/04` 第 15 节）：本轮结束时步骤仍会收起来，
  收起来的是**已经想完**的东西。
* **没给 `harness.fake` 加节奏开关**：走查用 CDP 限速把同一段字节摊开（见 `walkthrough.mjs` 头注释），
  夹具因此一行没改——代价是这条证据依赖限速，换台机器跑要重新读一遍那几个数。
* **恢复出来的会话**永远是折的一行首行（不流式 ⇒ 首行），这条与改动前一致。