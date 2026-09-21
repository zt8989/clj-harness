# 走查证据：sessions-live-on-the-server

一张票一份脚本、一段记录。跑法都一样：真后端 + 真前端 + 隔离家，端口由脚本自己挑。

## 票 02：记录写不进去，页面上说出来（2026-09-21）

跑法：

```bash
node scripts/dev.mjs --scripted .scratch/sessions-live-on-the-server/evidence/go.json --ui-port 5221
# 它会把临时的配置根打出来，复制那一行里的 <HOME>：
node .scratch/sessions-live-on-the-server/evidence/walkthrough.mjs http://localhost:5221/ <HOME>
```

`go.json` 两轮，一句话一轮：第一轮让这个会话的 jsonl 长起来，第二轮之前把**那个文件**改成只读
（`chmod 444`），于是第二轮的逐帧 append 全部失败。脚本自己找文件（按 thread id 在 `projects/` 下
递归找 `<id>.jsonl`），改权限，发第二轮，然后**不刷新**等那条红条出现。它顺带把那张图拍下来：
`02-record-notice.png`。

### 走查当天看见的（2026-09-21）

- **第一轮**：正常落盘（14 行），页面上**没有**任何提示——「正在存」不需要说。
- **第二轮之前把文件改成只读**：第二轮照常跑完、回答照常画出来（内存是权威，run 不受影响），
  跑完之后页面上**多出一条常驻的条**，就在视图切换下面、对话上面：

  > This conversation could not be saved to disk: …/projects/.unbound/<id>.jsonl (Permission denied).
  > 16 lines are waiting to be written.

  （浏览器语言是英文这次就出英文；前一次同一场景是中文的
  「这段对话没能存到磁盘上：…。还有 11 行在等着写。」，两种语言的句子都在
  `ui/test/suites/record.tsx` 里有渲染用例。）
- **`GET /api/threads/<id>/sofar` 同时说**：`"record":{"state":"degraded","reason":"…（Permission
  denied）","pending":16,"at":…}`——页面上的字就是服务端那一句，没有转述。
- **记录一个字节没长也没短**：14 → 14 行。写不进去的那一行停在队首，后面的还排着——这就是
  「降级 ≠ 丢行，记录永远是有序前缀」。

### 这次走查抓到了什么（所以它被留下）

第一版只在**挂载那次读**和**盯着别人跑的轮询**里上报记录状态。而**自己驱动这一轮**的页面两者都不走：
它没有一个「正在跑」的读要去轮询（轮询的条件是读回来的 state 是 `running`），所以跑中写失败
**谁都收不到**，页面安静地继续，记录安静地落后——正是 ADR 0002 决策 6 不许的那件事。

修法是在**这一轮跑完之后**再读一次记录（`app.tsx` 里那条 effect，用挂载同一条读、同一个 fallback），
于是这条红条在这一轮结束的那一刻出现在屏幕上——**不用刷新**。上面那条 GREEN 就是这么来的：
两次 turn 之间没有 reload。

### 这个脚本看不见的

布局好不好看（那条红条会不会把对话挤走）、以及**很久的一轮跑在中途**盘就坏了的时候，条要等到
那一轮结束才出现（现在是这一票的取舍，写在票 02 的落地记录里）。前者是人的眼睛，后者是票据上的
已知边界。

## 票 03：页面发的是动作，不是对话（2026-09-22）

跑法：

```bash
node scripts/dev.mjs --scripted .scratch/sessions-live-on-the-server/evidence/03-go.json --ui-port 5222
# 复制它打出来的临时配置根那一行里的 <HOME>：
node .scratch/sessions-live-on-the-server/evidence/03-walkthrough.mjs http://localhost:5222/ <HOME>
```

`03-go.json` 三轮，一轮一句话。脚本这一次不看磁盘，看**浏览器自己发出去的请求**
（`page.on("request")`）：空 localStorage 的新页面打开 → 发一轮 → **不刷新**再发一轮 →
刷新 → 回到同一个会话 → 再发一轮 → 最后从页面里直接敲一次「这个 id 我家不认识」的运行。
证据是它打出来的那几行 body（`03-action-body.png` 是同一时刻的截图，只给眼睛看布局）。

### 走查当天看见的

- **页面自己没铸 id**：打开后第一件事是 `POST /api/sessions`，body 就是 `{}`——一个 id 都没提。
  服务端答了 `e0913e1d-…`，页面的 localStorage `clj-harness.session` 记下的就是这个 id。
- **每一轮的 body 都只有这一轮的动静**（三轮逐字相同，只有 append 里的那条消息不同）：

  ```json
  {"threadId":"e0913e1d-e0f0-442b-903c-5393a7371642","tools":[],"context":[],
   "forwardedProps":{},"state":null,
   "append":[{"id":"gQUJTLr","role":"user","content":"走查：第一轮"}]}
  ```

  **没有 `messages`**（对话在服务端），**没有 `runId`**（run 是服务端铸的，上面记录里的
  `runId` 三个都是服务端的 UUID），`append` 里只有这一轮新说的那一句——第二轮、第三轮各一条，
  尽管页面上第一轮的字还在。`context`/`state`/`forwardedProps` 是 AG-UI 客户端自己的家具，
  服务端只在**出生那一轮**读 `context`，其余忽略。
- **刷新回到同一个会话**：reload 之后 localStorage 里还是同一个 id，页面把两轮的对话重建出来，
  而且**没有再问一次** `POST /api/sessions`（脚本数的就是这个：整个走查只出现一次问 id 的请求）。
- **不认识的 id 被点名拒绝**：从页面里 `fetch` 一次
  `{"threadId":"walkthrough-never-registered","append":[],"tools":[]}` →
  `404`，body 里就是那个 id：

  > no session "walkthrough-never-registered" exists in this home, so this run was not started:
  > a run continues a conversation, and this one has no beginning here. Create it first --
  > POST /api/sessions answers a fresh threadId when asked with no id -- and send the run again.

  这是「运行边不再悄悄登记一个没见过的会话」的那一半，从浏览器这一侧看见。
- **盘上只有一条会话、三条 input**：`GET /api/threads` 里那个 id 只出现一次；
  `<id>.jsonl` 里三条 `input` 行，各自的 `append` 恰好一条消息，且**没有一条带 `messages`**。

### 这次走查为什么留在这里

票 03 改的是**线上格式**：客户端不再把整段对话发回来，新会话的 id 也不再由页面铸。这两件事在
`ui/test/suites/client.ts` 里有断言（同一份 body、同一个请求），但那是**客户端代码**说的；
「页面在浏览器里真的这么发」是另一回事——尤其是 composer 那一轮：`append` 该装什么，是
`lib/agent.appendOf` 从 `this.messages` 里挑出来的，而 `this.messages` 是运行时攒的。
上面那句「第二轮的 append 只有一条」就是这个挑法的现场证明。

### 这个脚本看不见的

布局（截图是给人的）；以及**重试**那一路——同一句话被重发时，`appendOf` 挑出来的还是那条
id 相同的消息，服务端按 id 去重（`harness.edge.sessions/append!`），这一格由后端用例钉着
（`harness.edge.http-test` 里「同一轮跑两次」与 `replay-test` 的重复帧），走查没有伪造一次
失败的运行去演它。
