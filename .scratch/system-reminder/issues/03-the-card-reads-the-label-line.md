# 票 03：卡片标题改读标签行

Blocked by: 01, 02。

## 目标

会话栏里那张注入卡，折叠行的标题来自 **reminder 里的首行标签行**，不是 `system-reminder` 这个标签本身。

## 现状

`ui/src/lib/injections.ts` 的 `tagOf(text)` 取首行的标签名（`<skill name="tdd">` → `skill`）当 `title`，
`preview` 是首行。改成 reminder 之后，每条的首行标签都是 `system-reminder`——所有卡片标题一样，卡片就不分辨了。

## 改哪里

1. `ui/src/lib/injections.ts`：
   - `title` = `system-reminder` 的**下一行**（去掉定界行与空行之后的第一行）；
   - `preview` 不变（首行有意义的那一行）；
   - **旧字节兜底**：首行直接就是 `<instructions` / `<skills` / `<skill` / `<job-ended` 时按老规矩把标签名
     当标题（老记录、老会话）。
   - `tagOf` 的注释重写：它现在认两种形状，这是记录里真的有两种形状。
2. `ui/src/components/context-card.tsx`：标题已经是 `view.title`，通常不用改；若卡片的空态判断依赖
   `tagOf` 非空，跟着改。
3. i18n：若标题需要一个人话兜底（例如空首行），加 `thread` 命名空间的一条键。

## 判据

- `ui/test/suites/injections.ts`：给 `{text: "<system-reminder>\nInstructions from /a/AGENTS.md\n…\n</system-reminder>"}`
  → `title === "Instructions from /a/AGENTS.md"`；`Skill tdd` → `"Skill tdd"`；
  `Background job j1 ended: [exit 0]` → 那一行；旧 `<skill name="tdd">` → `"skill"`；空文本 → `null`。
- `ui && npm run typecheck && npm test && npm run build`。
- `node scripts/dev.mjs --scripted` 起服务、开浏览器：出生块画出**一张**卡（不是每个 AGENTS.md 一张），
  标题是 `Instructions from …`。

## 不做

- 不做注入卡的外观重做。
- 不碰压缩卡。
