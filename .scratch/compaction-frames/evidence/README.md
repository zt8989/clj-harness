# 证据：压缩卡在真浏览器里的样子

**这一场是用这台机器上能跑的东西做的，说清楚它是什么**：本机**没有装 Playwright**
（`ui/node_modules` 里没有，仓库也不依赖它），所以 `walkthrough.mjs` 的 import 在这里跑不起来。
这一次走查是**用 Playwright MCP 的浏览器**驱动的——同一个流程、同一组断言、同一台真机器上的真页面，
只是驱动它的是 agent 而不是那份脚本。脚本照仓库规矩留在 `../walkthrough.mjs`（在装了 Playwright 的机器上
`node .scratch/compaction-frames/walkthrough.mjs <url> <project-dir>` 就是这一场）。

## 怎么起的这一场

```
node scripts/dev.mjs --scripted .scratch/compaction-frames/evidence/go.json
```

隔离家、OS 分配端口、scripted provider、页面由后端从 `ui/dist` 发出（本机 Node 22）。

**还要一个项目，它的 `.harness/harness.edn` 让这么短的对话也压得起**：

```
{:compaction {:threshold-ratio 0.005 :retain-ratio 0.0001}}
```

（写在一个临时项目目录的 `.harness/harness.edn` 下——**项目级**才是这个路径，写下 `<dir>/harness.edn`
不会被读到，这一步试错过一次。）不这么写就**什么都不会压**：默认的 0.7 / 0.16 对着 128k 的窗口要九万
token 的历史，任何走查都敲不出来。

然后：新建会话 → `POST /api/project` 把它绑到那个项目 → 依次发三句话。

## 看到的东西

1. **第三轮开头压了一次，卡就在那一轮里**。折着的那一行，DOM 里读出来是
   `压缩的上下文 · 第二句的回答：这两轮只是聊了两句，记录里多了几条消息。 14 tok`——名字、摘要首行、被折
   那段的估算 token，三样在一行里；而它在**助手那条消息之内**、在模型自己的回答之前：帧是在 `:run/start`
   发出去的（`closest('[data-slot="aui_assistant-message-content"]')` 答得出父节点）。
2. **点开是那段摘要**（`t01-01-card-open.png`）：正文就是摘要原文，上面一行「折叠了 1 条消息」。
3. **刷新之后还在，而且是折着的**（`t02-01-after-reload.png`）：重建把同一张卡带回来——同一个 id、
   同一个 `14 tok`。
4. **回发的那一份里没有它**：刷新之后再问一句，`POST /api/agent` 的请求体里只有那个人的两句话，
   `compacted-context` 一个字都没有——卡是 `data` part，`toAgUiMessages` 没有它的分支。

记录里（`<home>/projects/<workspace>/<cd17cf44….jsonl>`）同时能看到卡的另一半：

```
context/compacted  第二句的回答：…  14  98d27aa2-46c3-49b4-8d37-3c03a2c1e626
compacted-context  第二句的回答：…  14  （messageId 就是上面那个 id）
```

## 一个顺带量到的事实，别当成 bug

这一场的阈值被压到极小（0.005），所以那一段对话里**压了不止一次**；scripted provider 的 turn 用完之后，
后面那几次的**摘要是空串**。记录里因此有三条 `compacted-context` 帧，而屏幕上**只有一张卡**——
空摘要的那两条 `compactionView` 答 null，卡不画。这正是「一段没话可说的帧不是一张卡」那条规矩
（与注入卡同一条），不是漏画。
