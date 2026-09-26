# spec: 这一侧挂断的 run 按「中止」报，不按「失败」报

2026-09-22 立。**要的是：按停止（或这一页把那条流关掉）时，界面上不再出现
`BodyStreamBuffer was aborted`。**

（现场那个慢 run 的脚本就是这份目录里的 `slow.json`。）

## 现场

脚本化后端 + 真浏览器，起一条慢 run（`bash sleep 60`），点「停止生成」：

- 工具卡变 `bash · 失败`，展开是 `错误：BodyStreamBuffer was aborted`——关于一次**人按下去的停**。
- 原因链是三层：
  1. 浏览器把被掐断的流报成 `AbortError`（Chromium 的措辞就是这一句；Node 下同一件事说
     `This operation was aborted`）；
  2. `@ag-ui/client` 的 SSE 传输专门认 `name === "AbortError"`，把它**合成**一条 `RUN_ERROR`
     （`code: "abort"`，浏览器那句 message 原样带上）——它不是服务端发的帧；
  3. `@assistant-ui/react-ag-ui` 的订阅者对 `RUN_ERROR` **帧**不做 abort 判定（库自己的
     `isAbortError` 只用在 `onRunFailed` 那条错误路上），于是 run 的状态落在
     `incomplete / reason: "error"`，卡片就是 `失败` + `status.error`。
- 时间上还输一层：runtime 自己的 abort 监听器**已经**派发了 `RUN_CANCELLED`（界面那一支是
  `已取消`），随后到达的合成 `RUN_ERROR` 把它盖掉了。

## 改成什么

`ui/src/lib/agent.ts` 的 `HarnessAgent` 多一条规则：**这条 run 的 `abortController` 一断，
它就不是失败，是中止**。两个入口，同一条判断：

- `cancellationAware`（包住交给 run 的订阅者，`subscribe` 与 `runAgent` 两条门都走它）：
  拦下传输合成的那条 `RUN_ERROR`（`code === "abort"` **且**本 run 的 signal 已断），改从库自己
  的 abort 通道递交（`onRunFailed` + 错误名 `AbortError`）⇒ 库派发 `RUN_CANCELLED`，没有
  `RUN_ERROR`，也没有随后的 `RUN_FINISHED` 把它冲成「完成」。
- `onError`（订阅者之外的那条错误路，例如响应头都还没到就掐）：同一个 signal 判断，把错误改名
  成 `AbortError`，库的 `isAbortError` 才认得出。

两处都**只看 signal，不看文字**：措辞是浏览器的，拿字符串去认 abort 会把一次真的失败吞掉。

## 落地结果

- 用例 `ui/test/suites/client.ts` 的
  `a-run-this-client-hung-up-is-a-cancellation-not-a-failure`：真后端 + 真客户端，等工具调用
  开始再 `abortRun`，断言订阅者没有收到 `RUN_ERROR` 帧、`onRunFailed` 拿到的是 `AbortError`。
  **拿掉这条规则它就是红的**（实测 `expected [ 'This operation was aborted' ] to deeply equal []`）。
- 走查（脚本化后端 + 真浏览器）：慢 run 跑着点「停止生成」⇒ 卡片
  `bash · sleep 60 已取消`，展开只有「参数」，没有错误行、没有「没有结果」；composer 复位到
  「发送消息」。响应头前后两种停法各试了一次，都是 `已取消`。
- `cd ui && npm test` 96 passed；`cd ui && npm run build` 过。

## 这一票不做什么（边界写清）

**服务端那条 run 照样跑到终了。** 这一侧只是关了自己的 fetch，`handle-run` 依旧不管客户端走没走
（`docs/architecture/home-and-storage.md` 已经写着这条边界）。真正把服务端那条 run 停下来的那件事
在 `.scratch/session-after-refresh` 的 07 / 08 / 09（取消端点 → 取消到得了循环 → 刷新之后也能停）。
这一票只保证界面不再把「人按的停」画成失败，也不再冒出浏览器那句英文。