# 02 — 手机端的模型与思考收成图标，点开才选

**What to build:** 窄窗（< `sm`，即 < 40rem / 640px）下，composer 动作行里的
**模型**（`composer-model`）与**思考档**（`composer-effort`）只画一个图标，不画当前的取值文字、也不画
那颗 `⌄`；点那个图标，列表（今天那个 Popover）照常从输入框上方展开，选一项即关。宽窗一个字都不改。

**Blocked by:** None — can start immediately.

**Status:** 已落地（2026-09-25，分支 `mobile-adaptation`）。

## 现状与证据

`ui/src/components/composer-chrome.tsx` 的 `ComposerTools`（`:339`）把两个 picker 画在动作行里
（`data-slot="composer-tools"`，`:418`）：

- 模型：`<Picker slot="composer-model" …>`（`:425`），**没有 `leading`**——它左边那颗是
  `ContextRing`（`:423`，与 picker 同一个 `flex items-center gap-1.5` 里），而环是 picker 的**兄弟**：
  点环开的是上下文面板、**不是**模型菜单（`components/context-ring.tsx` 的头注释把这条写死了）。
- 思考：`<Picker slot="composer-effort" … leading={<BrainIcon …/>}>`（`:446`、`:450`）。

`components/picker.tsx` 的触发器（`:148`）今天固定三件：`leading`、一段取值文字
（`${slot}-value`，`:151`，`max-w-[16rem] truncate`）、`ChevronDownIcon`（`:154`）。取值最长是
`deepseek-v4.1-flash` 那一档（16rem ≈ 256px）。动作行里还并排着 attach 的 `+` 与发送 / 停止，
窄到 360–430px 时这一行装不下：文字被截断，甚至把整行挤坏——而**取值本来就没有必读的价值**：它此刻
是什么，点开就看得到；宽窗才需要「一眼可读」。

## 形状与决策

1. **断点用 `sm`**（Tailwind v4 的 40rem / 640px），与设置弹窗、`hidden sm:*` 这一族同一个数；
   不给 composer 单独发明一个宽度。

2. **`Picker` 加一个 prop，而不是复制一份 picker。** 名字取 `iconOnly`（或等价）：为真时，取值那段
   `${slot}-value` 与 `ChevronDownIcon` 都挂 `hidden sm:inline` / `hidden sm:block`——**类名逐字写在
   `picker.tsx` 里**（Tailwind 扫的是字面量）。默认假，其余三处 picker（目录 / 分支 / 设置里那些）不动。

3. **模型那颗图标由本票新给**：`Picker` 的 `leading` 传一个 lucide 的 `CpuIcon`
   （`className="text-muted-foreground size-4 shrink-0 sm:hidden"`），**只在窄窗画**——宽窗今天长什么样，
   就还是什么样。思考那边 `BrainIcon` 已经在 `leading` 里，什么也不用加。

4. **环不动、也不许并进 picker。** `ContextRing` 是兄弟、是另一个面板的门；窄窗下它照画在模型图标左
   边。点击环仍然只开上下文面板。

5. **取值虽然不画了，仍要够得着**：触发器的 `aria-label={label}`（`picker.tsx:144`）与
   `title={title ?? current?.label}`（`:146`）保留，所以图标按钮对屏幕阅读器、对长按，都还说得清
   「现在是哪个」。

6. **不做**：不动宽窗、不动 `ContextRing`、不给 picker 引新依赖、不改 popover 列表本身。

## 验收

- [ ] 宽度 < 640px：动作行里模型与思考各只有一个图标；点模型图标 → 模型列表正常展开、能搜、能选；
      点思考图标 → 三档列表展开、能选；选完 popover 关，会话照旧被改写（`POST /api/model`）。
- [ ] 宽度 ≥ 640px：与改动前一致（`composer-model-value`、`composer-effort-value` 与 `⌄` 都在），
      `composer-model`、`composer-effort` 这些 `data-slot` 一个不少。
- [ ] 窄窗下 `composer-model-trigger` / `composer-effort-trigger` 的 `aria-label` 与 `title`
      仍分别说得清「Model」、当前模型与当前思考档。
- [ ] `cd ui && npm test`、`npm run typecheck`、`npm run build` 全绿（`test/suites/picker.ts` 是纯
      逻辑，不该因此改动）。
- [ ] 真浏览器走查：`node scripts/dev.mjs --scripted`，手机宽度（例如 390×844）与桌面宽度各看一趟，
      截图进 `evidence/`（手机那张要能看到「一排图标」与展开后的列表）。
- [ ] 本票只动 `ui/`，`clojure -M:test -m harness.test-runner` 的失败名单逐条不变。

## 落地

`ui/src/components/picker.tsx`：`PickerProps` 多一个 `iconOnly`（默认 false）；为真时取值那段
`${slot}-value` 得多 `hidden sm:inline`、`⌄` 得 `hidden sm:block`（类名逐字写在文件里）。
`ui/src/components/composer-chrome.tsx`：模型 picker 传 `iconOnly` 并补 `leading` 的 `CpuIcon`
（`sm:hidden`，只在窄窗画）；思考 picker 传 `iconOnly`（它的 `BrainIcon` 本就在 `leading`）。

实测（真浏览器，390 / 360px）：模型与思考的触发器宽度各 16px（只剩图标），取值文字与 `⌄` 都
`display:none`；1280px：`seeded` / `默认档` 与 `⌄` 都在、模型的 chip 图标 `display:none`——与改动前
一致。`aria-label` / `title` 保留。截图 `evidence/02-composer-icons-390.png`。
