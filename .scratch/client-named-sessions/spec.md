# spec: 会话的名字由浏览器铸 —— AG-UI 客户端的设计

**一句话**：一场会话的名字由**页面**在打开它的时候铸（`lib/id.ts`，就是 `@ag-ui/client` 自己导出的
`randomUUID()`），第一次发送时把这枚 id 交给服务端登记／与项目绑定
（`POST /api/sessions {threadId}` / `POST /api/project {threadId, dir}`）。
「点击新增不立刻会话，发送才新建」一个字没动。

2026-09-24 立（**撤掉 2026-09-23 那一版**）。主人的话：「如果 `@ag-ui/client` 说明由 web 创建是设计，
那你改成初次由浏览器创建，然后第一次用户发送，把 uuid 和 project 绑定。」

## 前提（当天查证的）

**`@ag-ui/client` 的设计确实是客户端铸 thread-id**，两条证据：

1. `AbstractAgent` 的构造函数里就是 `this.threadId = threadId ?? v4()` —— 不给就自己铸一枚（`uuid` 包）。
   AG-UI 的 `RunAgentInput.threadId` 本来也是**调用方**的字段。
2. **本仓的 host 一直在让库铸，然后把那枚扔掉**：`app.tsx` 的 `SessionHost` 写的是
   `const created = new HarnessAgent({ url: AGENT_URL, ready: onReady }); created.threadId = threadId;`
   —— 不传 threadId（库铸一枚）紧接着覆盖成页面那枚。

而且这个库**自己导出一个 `randomUUID()`**，类型声明上的注释是
*"Generate a random UUID v4 / Cross-platform compatible (Node.js, browsers, React Native)"* ——
它存在的理由就是 `crypto.randomUUID` 并非哪里都有。实现是 `uuid` 包的 v4：优先平台那一个，
**没有就走 `crypto.getRandomValues`**（那个**不**受安全上下文限制）。所以：

**页面不该自己写生成器，也不该调 `crypto.randomUUID`；它该用这个库的函数。**

## 问题（为什么前一天会走错）

手机走 `http://192.168.x.x` 读 dev server 时 `crypto.randomUUID` **根本不存在**，页面在画出来之前就抛
`TypeError: crypto.randomUUID is not a function`；而 127.0.0.1 **是**安全上下文，所以同一份 bundle 在
开发机上是好的 —— **机器门全绿**。当天的第一反应是加兜底链（`ac891e3` 的 `lib/id.ts`），第二天又
「统一由后端创建」（`9c7e747`，`GET /api/ids/new`）。两次都在**换铸名字的人**，而真正的答案在**换那个人
用的函数**：把 `crypto.randomUUID` 换成客户端库的 `randomUUID`，页面照旧铸名，手机照旧能跑。

后端铸那一版的代价，正是撤掉它的理由：

- **一个名字有了两个主人。** 名字本来就是客户端那份输入的一部分（上面第 1 条），让服务端铸等于把
  AG-UI 的设计掰过来；`AbstractAgent` 已经铸好一枚等着被用，页面却去问别人要另一枚。
- **多一次往返、多一条路由、多一层状态。** `GET /api/ids/new`（铸名但不写任何东西）+ 入口先 await 一枚名
  + `spareName` 备用池 + 侧栏一个 `openFresh` 才把它做得不卡手：为了拿到一个**随机字符串**，值得吗。
- **它并没有解决手机那个错**，只是不再触发它 —— 而换成库的函数同样不触发，且不动任何结构。

## 决策

1. **`lib/id.ts` 是一行再导出**：`export { randomUUID as newId } from "@ag-ui/client";`
   （上面是一整段注释：名字归谁、为什么不是 `crypto.randomUUID`、为什么不是包装函数）。
   **不是包装**是有意的：中间没有我们自己的规矩要守，而**身份**本身是要钉住的东西
   （`test/suites/id.ts` 断言 `newId === randomUUID`：谁想把 `crypto.randomUUID` 换回来，
   都会先弄红一条用例）。
2. **页面在挂载与「新建」时铸名**（回到 `app.tsx` 的 roster 与 `sidebar.tsx` 的四处），
   **第一次发送时绑定**：项目会话 `POST /api/project {threadId, dir}`、任务 `POST /api/sessions {threadId}`
   —— 这正是主人那句话的后半句，也是**本来就有的**那条路（`registerPending`）。不写库、不刷新、
   列表上什么都不出现，点击没有往返、没有会失败的东西。
3. **`GET /api/ids/new` 与它那一半结构撤掉**：路由、它的用例、`main.tsx` 的入口 await、
   `app.tsx` 的 `spareName` / `askForName`、`sidebar.tsx` 的 `openFresh` / `freshError`、
   `projects.ts` 的 `mintThreadId`、i18n 的 `mintingSession`，一起退场。
4. **那四条 `id` 用例变成两条**：页面没有生成器了，就没有分支可钉 —— 剩下的是**决定**
   （用的是库的函数，不是平台那个）与**形状**（version-4 uuid，两次两次不同）。130 → 128。
5. **文档**：`client.md` 里「页面铸 id」那一段补一句「用哪个函数、为什么」；
   ADR 0002 决策 9 加一句日期注（那一天的来回）；`CONTEXT.md` 的同一条也点一句。

## 非目标

- **不动 id 的形状**：仍然是 v4 uuid（谁铸都一样）。
- **不动 `ui/test/e2e.ts` 里那处 `crypto.randomUUID`**：node 里跑的测试夹具，不是 web 运行时。
- **不换掉 `@ag-ui/client`，也就不动它带进产物的 `uuid` 包**：那是它的实现细节，而它自己
  feature-detect，不会抛手机那个错。「不要有这个依赖」在我们这一侧已经做到了 —— 页面不写生成器、
  不调平台那个函数。

## 验收

- [x] `ui/src` 里再无 `crypto.randomUUID`；`lib/id.ts` 是 `@ag-ui/client` 的再导出
- [x] 点「新建任务」/「项目中新建会话」/ 挂载三种时刻都还在铸名，且**第一次发送才登记**
- [x] 后端那条路由与它的用例撤净（`harness.edge.http-test` 不再有 `a-name-can-be-had-without-a-conversation`）
- [x] `npm run typecheck` / `npm run build` 干净；`npm test` 128 例绿
- [x] 后端全量绿（除预存在那条，见下面报数）
- [x] 在**非安全源**上真浏览器走查（`http://192.168.196.54:<port>`，`crypto.randomUUID` 为 undefined）：
      页面起得来、能新建会话、新建出来的 id 是页面铸的 v4、发一句话能登记并跑起来
- [x] 文档收口

## 报数

（落地当天补）
