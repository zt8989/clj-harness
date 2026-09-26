# 02 — 会话标题：从第一条用户消息派生（纯函数 + 套件）

Status: done

## 做什么

新模块 `ui/src/lib/session-title.ts`，**runtime-zero imports**（照 `lib/turns.ts` /
`lib/injections.ts` 那两行的先例，套件才能把它当算数来钉）：

```ts
export const PRODUCT_NAME = "clj-harness";
export const TITLE_MAX = 60;
export type TitledMessage = { role: string; parts?: readonly { type: string; text?: string }[] };
export function firstUserText(messages: readonly TitledMessage[]): string | null;
export function sessionTitle(messages: readonly TitledMessage[], untitled: string): string;
export function documentTitle(title: string): string;   // `${title} · ${PRODUCT_NAME}`
```

规则（spec 决策五，一字不差）：

- 按顺序走**用户**消息，取第一条里第一个非空白的 `text` part。**不是**取第一条用户消息就停：
  一条只带图的消息（composer 支持贴图）不该让整场会话没有标题。助手消息、`system` 消息、
  `data` part（注入上下文）都不参与。
- 内部连续空白折成一个空格，去首尾。
- 截断 **60 个码点**（`Array.from`，不是 `slice`：一个 emoji / 一个汉字在 UTF-16 里占两个码元），
  截断后接 `…`。就在这个函数里截——浏览器 tab 没有省略号机制，字符串本身必须有界。
- 一条都没有 ⇒ `sessionTitle` 回 `untitled`（调用方传 `t("session.untitled")`）；
  `firstUserText` 回 `null`，让「有没有标题」这件事可被区分。

`ui/src/locales/{en,zh}/shell.json` 各加一条 `session.untitled`（en `New session` / zh `新会话`）——
它必须被某处**字面量**写出（i18n 套件的 orphan 检查），所以 03 的调用点落地时它才不孤单；
两票一起过门。

## 验收（`ui/test/suites/session-title.ts`，两例）

1. `the-title-is-the-first-thing-the-user-said`：第一条胜出；助手消息在前的组；
   只带图的消息被跳过、下一条有文字的被取到；纯空白不算；内部换行/多空格折成一个空格；
   `data` part 不参与；空列表回 `null`；`sessionTitle` 在 `null` 时回传进来的回退词。
2. `the-tab-title-says-whose-page-it-is`：`documentTitle("你好")` = `你好 · clj-harness`；
   `PRODUCT_NAME` 逐字 = `clj-harness`；60 码点边界（第 60 / 第 61 个字符处）；
   一个 CJK 串与一个 emoji 串在边界上**不被劈成半个**（断言结果里没有孤立代理项：
   `expect([...out].every(c => c.codePointAt(0) !== 0xfffd)).toBe(true)` 之类的写法即可）。

## 落地（2026-09-21）

`ui/src/lib/session-title.ts`（新，runtime-zero；另有两个私有助手 `tidy` 与 `clip`）、
`ui/src/locales/{en,zh}/shell.json` 各加 `session.untitled`。
`ui/test/suites/session-title.ts` 两例绿：`EXPECTED_CASES` 52 → 55。
浏览器里复量了截断：95 个字 → 顶栏 61 个码点（60 + `…`），`document.title` 同。
