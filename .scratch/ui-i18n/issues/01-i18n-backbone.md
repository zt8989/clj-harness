# 01 — 语言的底座：两个依赖、一份目录、一条判定链、一个开关

**What to build:** 从用户视角：在设置的 General 页选一次「中文」，页面立刻说中文（视图切换那两条
`Conversation` / `Trajectory` 先翻过来），刷新之后还是中文；换一台机器、或清掉浏览器的记录时按浏览器
语言自动选；页面的 `lang` 属性跟着变，读屏软件读的是正确的语言。

这是**唯一的横切票**：后面十一张只是把各自那一面的文案搬进目录，一分新机制都不加。所以这条判定链、
目录的形状、以及两条守卫必须在这一次就定死——晚一步就是一张跨票的重做。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

## 验收

- [ ] `ui/package.json` 里多了 `i18next` 与 `react-i18next`，`ui/package-lock.json` 一起改。
      装的来源是镜像 `registry.npmmirror.com`（可达性已验：`npm view i18next version` → `26.4.2`、
      `npm view react-i18next version` → `17.0.14`）；`cd ui && npm ci --offline` 能装出同一份。
      **这是本仓 UI 很久以来第一次新增依赖**（`skill-picker` 那次专门记了「零新依赖」），
      所以锁文件 diff 落地时单独看一眼。
- [ ] `lib/i18n.ts` 在 `main.tsx` 渲染之前完成初始化：`resources` 静态引入（不异步加载）、
      `fallbackLng: "en"`、`supportedLngs: ["en", "zh"]`、`nonExplicitSupportedLngs` 让 `zh-CN` /
      `zh-TW` 落到 `zh`、`interpolation.escapeValue: false`、`react: { useSuspense: false }`
      ——目录是打包进来的，没有 Suspense，也就没有「先闪一下原文」的窗口。
- [ ] 判定链是一个**纯函数**（零 React、零 DOM，放 `lib/i18n.ts` 或它旁边），三条分支都有用例：
      本机记住的 → `navigator.language`（`zh*` 归 `zh`，其余归 `en`）→ `en`。
      用例至少覆盖 `zh-CN` / `zh-TW` / `zh` / `en-US` / `fr` / 空串与畸形值——畸形值不许抛。
- [ ] 记住的那把键写死在**一处**（`lib/i18n.ts`），改语言时写进去；刷新后读回来生效。
      真机：切中文 → 刷新 → 仍是中文；手动删掉那条记录 → 回到浏览器语言。
- [ ] `<html lang>` 跟着语言走；`ui/index.html` 里那个静态的 `lang="zh"` 改成与兜底一致的值
      ——今天文案是英文而它写着 `zh`，这本身就是不自洽。
- [ ] 设置面板的 General 页有一行开关，显示的默认值是**当前生效的**语言（不是浏览器的），
      选项就是 `English` / `中文`（这两个词各自写法固定，不进目录的键值对）。这一行自己的标题与
      说明进目录。**今天那三个页签标签（`General` / `Models` / `MCP servers`）不归本票**，归 07。
- [ ] **目录奇偶守卫进套件**：`en` 与 `zh` 两份里，每个键两边都在、值都非空；缺一个**就是失败**，
      不是「回落到英文」。新套件登记进 `ui/test/ui.test.ts` 的 `SUITES`，`EXPECTED_CASES` 一起加。
- [ ] 一条真文案端到端翻过来：视图切换的 `Conversation` / `Trajectory`（`app.tsx` 那两条；
      03 不再重复做）。中英各一张真机截图进 `evidence/`。
- [ ] `docs/architecture/client.md` 写下来：语言判定链、目录的位置与「键字面写」这条纪律、
      **边界**（开关只影响界面、不上线；后端的句子原样穿过），并改掉今天那句
      「文案与 UI 其余部分同语言（英文）」。
- [ ] `cd ui && npm run typecheck && cd ui && npm test` 全绿。
