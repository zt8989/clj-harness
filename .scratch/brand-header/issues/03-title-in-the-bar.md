# 03 — 顶栏标题 + `document.title` 跟随可见会话

Status: done
Blocked by: 02

## 做什么

**一个组件**：`ui/src/components/session-title.tsx`，`export const SessionTitle`。

```tsx
const text = useAuiState((s) => firstUserText(s.thread.messages));   // 字符串，按值比较
const { t } = useTranslation();
const title = text ?? t("session.untitled");
useDocumentTitle(title);
return <span data-slot="session-title" className="min-w-0 truncate text-[13px] font-medium">{title}</span>;
```

**一个 hook**：`ui/src/hooks/use-document-title.ts`，`useDocumentTitle(title: string)`：

```ts
useEffect(() => {
  document.title = documentTitle(title);
  return () => { document.title = PRODUCT_NAME; };
}, [title]);
```

cleanup 还原成产品名而不是「什么都不做」：只有一场会话连历史都读不回来时 roster 会空
（`hostFailed` 把唯一那个 host 丢掉），那时 tab 里留着一个已经不在屏幕上的会话标题是错的。
切换会话不会闪——旧列卸载与新列挂载在**同一次提交**里，浏览器在提交之后才画。

**一处布局**：`ui/src/app.tsx` 的 `SessionColumn` 里那条 `data-slot="view-switch"` 的横条，
最前面插 `<SessionTitle/>`，两个视图页签包进一个 `-ms-2 flex shrink-0 items-center gap-1` 里
（**一版是 `ms-auto` 靠右；二版改成了两行，见 spec 的二版一节**）
顶到行尾。`folded && "ps-11"` 一个字不改——整条已有那道给浮标的让位，标题接在最前面，
于是自动落在浮标右边。

**一处选型要写进注释**：`data-slot="view-switch"` 这个名字**保留**。它今天被
`.scratch/sidebar-fold` 的走查记录引用（那道 `ps-11` 的让位就是量在它身上的），改名会让那份
还在用的记录指着一块不存在的东西，而收益只是名字更准。

## 为什么不需要往 `App` 里加东西

`SessionColumn` 是 `SessionHost` 的 children，而 `SessionHost` 只给 `visible` 的那个 host 渲染
children（`{visible ? children : null}`）——所以**这一份顶栏存在 = 这一场是屏幕上的那一场**，
不需要 id → 标题的注册表，也不需要 `SessionStatusReporter` 那样的上行回报。

## 验收

- 顶栏顺序：`session-title` 在 `view-switch-tab` **之前**，页签整组靠右。
- 打一句发出去（用户消息乐观进核心）⇒ 顶栏与 tab **当场**都变，不用等回答。
- 「新建任务」出来的空会话 ⇒ 回退词；切回有第一句话的会话 ⇒ 换回那句话，`location` 不变。
- 切语言 ⇒ 回退词那场跟着变；有第一句话那场**不变**。
- `cd ui && npm run typecheck` 0 error；`npm run build` 绿。

## 落地（2026-09-21）

`ui/src/components/session-title.tsx`（新）、`ui/src/hooks/use-document-title.ts`（新）、
`ui/src/app.tsx`（顶栏第一个子元素 + 页签包进 `ms-auto` 那一组）、`ui/index.html`（静态 `<title>` 上方的注释）。
走查：打字后 80 ms（助手的消息还不存在）标题与 tab 就是那句话；新建任务回回退词；点回第一场换回原句且
`location.href` 未变；收起后标题 x=44（浮标占 8..40）；切英文只有回退词跟着走。
