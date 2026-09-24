# spec: 会话的名字由后端给 —— 页面不再铸 id

**一句话**：页面里每一处「给一场会话起名」都不再自己铸 id（挂载那一次、`新建任务`、`项目中新建会话`、
以及两个「这一类已经没有别的了」的分支），改成向后端要一枚「只铸名、什么都不写」的名
（`GET /api/ids/new`）；首次发送时照旧登记（`POST /api/sessions` / `POST /api/project`），
所以「点击新增不立刻会话，发送才新建」一个字没动。

2026-09-23 立。**主人的两句话**：「web端不要使用uuid，统一由后端创建」；
问清之后：「不使用uuid的意思是 **不引入uuid库**，之前手机端报错了，所以不要有这个依赖」。

## 问题

1. **`crypto.randomUUID` 是安全上下文才有的 API。** 手机走 `http://192.168.x.x` 读 dev server 时它根本不存在，
   页面在画出任何东西之前就抛 `TypeError: crypto.randomUUID is not a function`；而 127.0.0.1 **是**安全上下文，
   所以同一份 bundle 在开发机上是好的 —— **机器门全绿**。当天那次救火（`ac891e3`，`lib/id.ts`）加了一条兜底链
   （`randomUUID` → `getRandomValues` → `Math.random`），那让手机能用，但**依赖还在**：页面仍然在铸 id，
   仍然要靠一个「这个上下文有没有那个 API」的分支才活着。
2. **id 本来就是后端的东西。** `sessions-post` 的规矩（ticket 03）是 *"THE ID IS THE SERVER'S TO MINT"*：
   一场会话的名字，是**保存它的那个进程**给的 —— 一个自己编名字的页面，编的是别人家命名空间里的名字。
   ADR 0002 决策 9 原本就这么写；2026-09-21 因为主人另一条规矩（点击新增不立刻会话，发送才新建）
   改成「页面铸、服务端登记」。**今天两条规矩可以同时满足**，见决策 1 与决策 3。

## 决策

1. **`GET /api/ids/new`：铸一枚名字，什么都不写。** 它是 `/api/sessions` 的**铸名那一半**，而且必须另开一条：
   `POST /api/sessions` 会**落一行**（`register-session!`），而点击那一刻不该写库。所以这条路由没有副作用 ——
   这也正是它用 **GET** 的理由（本仓的规矩：方法说的是有没有 effect）。没人花掉的名字不是泄漏：一个 128 位空间里的
   uuid，没有任何东西持有它。命名空间与 `sessions-post` / `project-post` 自铸时**同一个**，所以这枚 id 交给哪扇门都行。

2. **页面在第一次绘制**之前**拿到第一枚名字**（`ui/src/main.tsx`）。页面**永远没有「没有会话」的那一刻**——
   那是一条早先的票特意立起来的性质（`app.tsx` 渲染那一段的原话：*"there is no 'no session' box any more"*），
   所以名字既然要等，就等在**入口**：`main.tsx` 先要名，再 `render(<App initialThreadId={…}/>)`。
   代价是首屏多一次请求（这个 home 自己发的页面，答不了这一次的服务器也发不出这份 bundle）；
   换来的是 `App` 里每一个 id 从第一次渲染起就是**真的**。答不上来**给一句话**（`boot-error`），不留白屏：
   开发时页面由 vite 发、harness 是另一个进程，「后端没起」应该读起来就是「后端没起」。

3. **点击花的是「已经拿到手的下一枚」**（`app.tsx` 的 `spareName`）。`showFresh` 需要 id 的那一刻，
   一枚后端给的名要一次请求才到 —— 所以**下一枚**在后台先要到、攥在手里：点击花现成的，
   没有往返、没有转圈、没有会失败的东西；花掉之后立刻再要一枚。**空着不是错**：那是「上一次没到手」，
   这时点击会等它，而这就是「新建」唯一可能失败的时刻 —— `sidebar.tsx` 把服务器的那句话画在列表上方
   （新建的会话还没有行可落）。

4. **侧栏不再有 id 可传**：`onShowFresh(projectDir)`（原来是 `onShowFresh(threadId, projectDir)`）。
   它交出去的是**它知道的那一件事**（这场会话将属于哪个目录，或 `null` 表示任务），名字由页面去要。
   它返回 **Promise**：不在下面几条路里各写一个 `catch`，而是侧栏内一个 `openFresh` 统一接住
   （「NOTHING HERE CAN FAIL」那句话跟着改了 —— 现在有一次请求能失败，只是它不该落在某一行的头上）。

5. **消息的渲染 id 也不再是 uuid**（`app.tsx` 的 `toThreadMessages`）：`message.id ?? \`m${index}\``。
   这个 id 是**渲染器**在这份列表里认一条消息的把手（重建出来的会话凭什么重画），它从不离开页面、
   从不发给任何人，所以它不需要命名空间，也不需要 uuid。

6. **`lib/id.ts` 与它的四条用例删掉。** 兜底链是那天对症状的处置；症状的**根**是页面在铸 id。
   规则搬到了它该在的地方：后端路由那一条用例
   （`harness.edge.http-test` 的 `a-name-can-be-had-without-a-conversation`，它同时钉住「要名」与「什么都没写」）。

7. **文档跟着改**：ADR 0002 决策 9 加日期注（把 2026-09-21 那条修正收回去，并说明为什么这次两条规矩不冲突）、
   ADR 0001、`docs/architecture/client.md`、`docs/architecture/edge.md`、`CONTEXT.md`、`README.md`。

## 非目标

- **不改 id 的形状**：仍然是 v4 uuid，仍然由 `java.util.UUID/randomUUID` 铸 —— 那是后端，永远有。
  主人否掉的是**浏览器**对那个能力（或任何一个 uuid 库）的依赖，不是 uuid 本身。
- **产物里还留着 `uuid` 这个包，它是 `@ag-ui/client` 的依赖，不是我们加的。** 落地当天搜 `ui/dist/assets/*.js`
  能看到 `uuid` v11 的一段（`github.com/uuidjs/uuid`）。它**不会**抛手机那个错：那段代码自己 feature-detect
  （`crypto.randomUUID && …`，没有就走 `crypto.getRandomValues`，而那个在非安全上下文里有）。要把它也从产物里
  拿掉，就得换掉或 fork `@ag-ui/client`（run 协议那条边），是另一件事 —— 本特征做的是**页面自己不再铸 id**，
  也就是主人那句「不要有这个依赖」指的是**我们这一侧**。
- **不动 `ui/test/e2e.ts` 里那处 `crypto.randomUUID`**：那是 node 里跑的测试夹具（给脚本起前缀），
  不是 web 运行时，不在「页面不许有依赖」的范围里。同一句话也适用于 `scripts/` 下的东西。
- **不动「点击不落库」**：那一条是主人的，本特征只换了「谁铸名」，没有把登记的**时刻**从首次发送挪回点击。

## 验收

- [x] `GET /api/ids/new` 答 200 + 一枚 v4 uuid，且**什么都没写**（不在任务列表、不在任何项目、没有日志文件）
- [x] 同一枚名交给 `POST /api/sessions` 仍然被接受，且从此成为这场会话的 id
- [x] `ui/src` 里再无 `crypto.randomUUID`，再无 `lib/id.ts`
- [x] **我们自己的代码**在打包产物里不再出现 `crypto.randomUUID`（搜出来还有一处，是依赖带来的，见下）
- [x] `npm run typecheck` / `npm run build` 干净；`npm test` 126 例全绿（126 = 130 − 那四条）
- [x] 后端全量绿
- [x] 在**非安全源**上真浏览器走查（`http://192.168.196.54:<port>`）：页面起得来、能新建会话、
      新建出来的 id 是后端给的 v4
- [x] 文档收口

## 报数

- **后端全量（落地当天，工作树里）**：`Ran 1197 tests containing 13415 assertions. 1 failures, 0 errors.`
  —— 那 1 条**不是本特征的**：`harness.cap.claims-test` 的
  `a-second-jvm-owns-a-conversation-until-it-goes-away`（它等 5 秒要子进程日志里那行 `taken-over`，
  那行 13 秒后才出现）。在**没带本特征、干净的 HEAD**（`0f1976d`，另开一个 worktree）上跑同一个
  命名空间，同样红 —— 预存在，与本特征无关。隔离判据只有一行 `ISOLATION NOTE`（我自己那个活着的
  harness 会话在写同一个库，`docs/rules/testing.md` 说的那条，不算失败）。
- **定向**：`harness.edge.http-test` → `Ran 106 tests containing 1182 assertions. 0 failures, 0 errors.`
- **前端**：`npm run typecheck` / `npm run build` 干净；`npm test` → **126 passed**。
- **真浏览器走查（非安全源）**：`node scripts/dev.mjs --scripted --port 5199`，浏览器打开
  `http://192.168.196.54:5199/`（`isSecureContext` **false**、`crypto.randomUUID` 为 **undefined**）：
  页面起得来、控制台没有 TypeError、挂载时一次 `GET /api/ids/new`（第一枚名）、紧接着第二次（备用的那枚）、
  点「新建任务」花掉手里那枚（`957b9012-…`，v4）并立刻再要一枚、发一句话之后 `POST /api/sessions` +
  `POST /api/agent` 照旧把这会话登记并跑起来（标题变成「你好」）。剩下的控制台报错只有 favicon 与
  「这场会话还没登记」的 stats 404 —— 懒创建本来就有的那两条。
