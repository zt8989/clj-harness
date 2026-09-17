# spec: composer 里的附件 —— 图进得来、发得出去、对话里看得见

**一句话**：输入框里 ⌘V 粘一张图（或拖进来、或用 `+` 选一张），它显示成一个可删的缩略图，随这条用户消息
一起走完整条路——AG-UI 的 image part 进服务端、翻成厂商的 `image_url`、模型收到——并且这条消息在对话里
带着那张图显示出来。**服务端零改动**，缺的一直只是客户端把它接上。

## 问题

三条路（粘贴 / 拖放 / `+`）今天都是**哑的，而且哑得没有声音**。三层原因叠在一起：

1. **运行时没接附件适配器**，于是 `capabilities.attachments` 为 false——那是上游唯一的能力位，
   而粘贴（`ComposerInput` 的 paste handler）、拖放（`Dropzone` 的 drop handler）与 `+` 三条路
   **都先问这个布尔**。没有适配器时三条路以同一个方式安静下来：粘贴不被消费、拖放被拒、
   `+` 弹出文件框然后什么都不落地。
2. 上游的 `attachment` / `image` 两份元素**已经抄进仓库**（`elements/attachment.aui.tsx`、
   `elements/image.tsx`），缩略图与「点开放大、Esc 关」早就写好了——只是没人喂它们。
3. **服务端那一侧早就做完了**：`harness.edge.ag_ui/provider-image-url` 会把 AG-UI 的 image part
   翻成厂商的 `image_url`（url 与 data 两种来源都认），模态守卫与 `GET /api/model` 的 `:input` 也都在。

所以这一票是**客户端把已经存在的一半接上另外一半**，而不是新造一条路。

## 决策

1. **适配器就是能力位，所以它只有一行。** `app.tsx` 里 `adapters.attachments: imageAttachments`。
   这一行不是「加一个功能」，它是把三条路一起打开的开关——理由见上。

2. **用上游那份 `SimpleImageAttachmentAdapter`，不手写。** 它的两个方法正好是这个界面要的：
   `add` 留下那个 `File`（草稿期间画的缩略图就是它），`send` 把字节读成 data URL。
   手写一份只会把「上游怎么发附件」这件事抄第二遍。**只在它前面加一道闸**（下面 4/6 两条）
   ——一个子类，`send` 一个字没动。

3. **没有上传，而且这是这个词的定义的一半。** data URL **就是 wire**：AG-UI 客户端把它转回
   `{type: "image", source: {type: "data", value: <base64>, mimeType}}`，服务端翻成
   `{type: "image_url", image_url: {url: "data:…"}}`。没有收字节的端点、没有中间存储、没有 URL、
   没有生命周期，所以它**不进库、不落盘、不从库里读回来**。记录里它跟着**每一轮**的 `input` 行被重记一遍
   ——那是它留下的全部痕迹，也是决策 6 那个上限存在的理由。

4. **判据与服务端 `harness.edge.ag_ui/undeclared-input` 是同一条**（两个读者、一条规则）。两个方向都错：
   比服务端**严**会把一个今天跑得通的配置挡在门外（「没有声明就是没有承诺」是那条规则的原话）；
   比服务端**松**则整条消息被 `RUN_ERROR` 吃掉，而 composer 已经清空——打的字和那张图一起没了，
   正是本仓「不许吃掉别人打的字」要防的那件事（同一条推理见 `approval-gate.tsx` 的 `isSendDisabled`）。
   **「缺字段」与「空集」是两个答案**，票面把它们当成一件，实测不是：

   ```
   declared nil  -> []          ; 没声明 → 不守（服务端放行）
   declared #{}  -> [:image]    ; 声明了空集 → 拒
   ```

   线上也分得开（`providers/wire` 对没声明的不写这个键、对空集写 `[]`），所以界面照同一个分法读。
   这条是本特征**唯一一处与票面不符**的地方：票 02 的验收把它写成「缺字段 / 空集 → 收」，
   落地时按服务端改掉，记在那一票的 Comments 里（`.scratch/` 只加注与划掉，不改写旧话）。

5. **「本会话的模型收不收图」这件事住在一个小 store 里，判据当次刷新。** 读它的有三处：适配器（拒）、
   `+`（自禁）、`ComposerFrame`（画句子）。而适配器是从**上游自己的事件处理函数**里被调的——那里够不着任何
   React 树——所以这个事实落在 `lib/attachments.ts` 的 subscribe/getSnapshot 上，适配器写、界面读。
   **谁刷新**：`ComposerTools` 每次取（挂载 / 会话切换 / 模型改完）顺带问一次
   `GET /api/model?threadId=…`，所以**换了模型不用重载页面**。数据来自那个端点、**不新增端点**、
   `GET /api/choices` 的形状一个字节没改。这一问失败被折成「什么都没声明」（服务端自己的语义），
   不让它把选择器一起拖掉。

6. **2 MB 的上限量的是源文件字节，而且不许偷偷改字节。** 客户端每一轮把整段历史重发，所以一张图跟着
   每一轮的 `input` 行被重记一次（2 MB 截图 ≈ 2.7 MB base64/轮，二十轮五十多兆）。量源文件而不是
   base64 长度或解码后的像素，因为**人手里那张图的体积是人唯一看得见、也唯一能自己动手改的数**。
   **不做客户端压缩、不做缩放、不做重编码**：改掉别人给的字节再发出去，等于在记录与「模型到底看到了什么」
   之间多一层没人能复盘的东西。超限就是拒，并说清拒的是什么。判据只有一处（`overByteLimit`），
   所以不会一处量 `file.size`、另一处量 base64 长度。

7. **拒话只画一处，`+` 留原地变 disabled。** 两条判据的句子都画在 `ComposerFrame` 里那一行
   （`role="alert"`、`data-slot="composer-attachment-refusal"`）——「拒绝长什么样」只有一份。
   `+` 在不受图的会话里**留在原地、disabled**，理由挂在**包着它的 `span` 的 `title`** 上（disabled 的按钮
   在值得在意的浏览器里收不到指针事件，挂它身上的 `title` 是一句没人看得见的提示）。
   **留着而不是拿掉**是决定，不是省事：一个悄悄消失的按钮与一颗从来没做出来的按钮从外面看一模一样，
   而这两件事里更难查的那件不该是 bug 的产物（与技能列表把坏技能仍列出来同源）；而且 `+` 是人决定要不要试
   的那一刻，理由必须在那之前就在——粘贴与拖放只能在被拒之后才说得上话。

8. **拒绝在适配器的 `add` 里发生，所以三条路都绕不过去。** `add` 是三种入口唯一汇合的地方；被拒时**抛**
   （上游 `add` 自己的契约，三个调用方各自接住自己的 rejection），此刻什么都还没挂上去，所以**输入框里的字
   与已经挂着的附件一个都不动**。

9. **抄来的元素不动，接法是加插入点。** `thread.aui.tsx` 加第三个 `LOCAL:` 槽
   （`ComposerAddAttachment`），前两个（`ComposerFrame` / `ComposerTools`）是既有先例；行、样式与结构
   其余部分与上游一致。对话那一侧的缩略图与放大用的是**原样未改**的 `attachment.aui.tsx` /
   `image.tsx`——这一票没有为了它们改过任何抄来的文件。

10. **服务端零 diff。** `src/harness/edge/ag_ui.clj`、`src/harness/edge/http.clj`、
    `src/harness/cap/providers.clj` 一个字不动，`http-test` 那条
    `an-image-part-reaches-the-model-translated-and-the-log-says-so` 早就盖住了线那一侧。

## 非目标（票面原文）

- 不做文档 / 语音 / 任意文件附件：今天只有图片一种（`image/*`）。
- 不做 OCR、不做图片生成、不做按轮去重。
- 不做客户端压缩或缩放（03 的理由：超限就是拒，不偷偷改字节）。

## 已知局限（票面原文，不假装它不存在）

重建只从**首个 `input`** 取种（`harness.edge.replay/records->messages`，它的测试
`seeds-from-the-first-input-and-ignores-later-ones` 把这条明写下来），所以**第 2 轮以后发的图会跟着那条
用户消息一起不进重建**。首轮发的图不受影响——它就在种子里。这不是本特征能修的，也不在本特征的范围里。

另有一条**与本特征无关、顺手撞上**的现状偏差，如实写在这里而**不**在本票里改：
`CONTEXT.md` 的**会话**词条说日志落在 `~/.clj-harness/logs/<thread-id>.jsonl`，
而绑定项目的会话实际落在 `~/.clj-harness/projects/<project>/<thread-id>.jsonl`
（未绑定的在 `projects/.unbound/`）——本次真机验收里看到的就是后者。
（改它属于那一节文档的账，不属于附件。）

## 落地记录

四条票面都已落地并**按惯例删除**（它们带着各自的验收回执，落在 `91ce9d9` 里，历史留着；
今天要读哪一条，`git show 91ce9d9:.scratch/composer-image/issues/<票>.md`）。
本特征落地的那一提交是 `91ce9d9`（分支 `composer-image`，从 `main` @ `7fc34c8` 切出）。
证据在 `evidence/`：

- **01（图进得来、发得出去、对话里看得见）**：`ui/src/app.tsx` 一行适配器 + `ui/src/lib/attachments.ts`。
  真机三条路各走一次；记录里 `input` 行是三个
  `{:type "image" :source {:type "data" :value <b64> :mimeType "image/png"}}`，
  同一个 run 的 `message` 行是三个 `{:type "image_url" :image_url {:url "data:image/png;base64,…"}}`。
  服务端零 diff。
- **02（只给会看图的模型附图）**：`ui/src/lib/attachment-rules.ts`（零 import）+ 上面决策 4/5/7。
  真机把会话的模型在 `sees-images` / `text-only` 之间切换，**不重载页面**，拒与收当次跟着变。
- **03（太大的图不进）**：同一个模块的 `overByteLimit` / `sizeRefusal`。真机 2.96 MB 拒、
  同一张图缩到 0.29 MB 收。
- **04（收口）**：`CONTEXT.md` 加**附件**词条、`docs/architecture/client.md` 改口并加一节、
  本文件、三条路各一张截图、离线全量跑绿。

**UI 套件**：`attachments` 套件两例（都是纯的），`EXPECTED_CASES` 24 → 26。
**服务端套件**：零 diff，所以没有新增用例——线那一侧本来就由 `http-test` 那条盖着。

**收口这次实测**：

- `clojure -M:test -m harness.test-runner` → `Ran 824 tests containing 11135 assertions.
  0 failures, 0 errors.`（这条分支上这次连那条真竞赛用例都没撞上）
- `cd ui && npm test` → `26 passed (26)`（9 组）；`npm run typecheck` 0 error、`npm run build` 过。
- `README.md` 的报数跟着改：后端换成这条分支今天的实测（`trajectory` @ `f7f4d31` 那一次的
  801 / 11018 留作对照），UI 那行 24 → 26、8 组 → 9 组，并写明那两条「本机固定失败」的现状
  （JDK 25 那条已修——子进程把自己的答案写进文件而不是 stdout；真竞赛那条留着）。

**一处没做到位，如实记下**：真机那两种剪贴板来源（截图工具复制、从 Finder 复制图片文件）需要一台有
桌面会话的机器，本次用的是浏览器自己的剪贴板 + 一次真的 ⌘V（同一条 `paste` / `clipboardData.files`
的路）。理由与替代写法在 `evidence/README.md`。
