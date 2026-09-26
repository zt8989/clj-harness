# 走查证据：注入物在会话栏里画一张卡

跑法（真后端 + 真前端 + 隔离家，端口由脚本自己挑）：

```bash
node scripts/dev.mjs --scripted .scratch/context-frames/evidence/go.json --ui-port 5219
# 另开一个终端：
node .scratch/context-frames/walkthrough.mjs http://localhost:5219/ "$PWD"
```

`go.json` 六轮，按模型调用一次一轮地喂：

1. 第一轮回一句话（这一轮**还没绑定项目**，所以没有 `<instructions>`，也就没有卡）；
2. 绑定之后的第一句——这一轮的第一次调用带上了 `<instructions>`；
3. 起一条 1 秒就结束的后台作业（`bash {run_in_background: true}`）；
4. 拿 `bash {sleep 3}` 拖住自己；
5. 作业在这期间结束了：**下一次模型调用之前**，那条 `<job-ended id="j1" …>` 被注入；
6. 刷新之后再问一句——这一句是用来核对**客户端交上来的历史里没有那些卡**的。

`walkthrough.mjs` 是那个真人视角：它按页面自己的状态等一轮跑完（composer 上的 Cancel 按钮只
在 `thread.isRunning` 时在），把跑完的那一轮**展开**（步骤默认折着，卡就在步骤里），再看卡。

## 走查当天看见的（2026-09-20）

一轮一轮地：

- **第一轮（未绑定）**：会话栏里**一张卡都没有**。空注入不画空卡。
- **绑定之后那一句**：助手文本**之前**多出一行折叠卡
  `Injected context · instructions · 3 KB`；点开是整份
  `<instructions path="/Users/zhouteng/Documents/workspace/clj-harness/AGENTS.md">…</instructions>`。
  卡在同一个 assistant 消息里排在助手文本之前（帧在模型被调用**之前**就发了）。
- **起作业那一轮**：会话栏里三张卡——这一轮的 `<instructions>`（每次 run 都会重新注入，所以每条
  run 一张）、上一轮的 `<instructions>`、以及**作业那一张** `Injected context · job-ended · 172 B`；
  点开是 `<job-ended id="j1" path="…/jobs/<会话>/j1.log">[exit 0]</job-ended>`，**三样事实、没有尾部**。
  位置：**在那一轮的工具卡之后、助手收尾那句之前**（`compareDocumentPosition` 量的，不是眼看）。
- **刷新之后**：三张卡**逐字还在**（标签、标题、字节数全同）——重建带回来的，id 是帧自己的
  （`<runId>-open<n>` 是开场块、`<runId>-ctx<n>` 是内核注入的那几条）。
- **刷新之后再问一句**：读那一次 `POST /api/agent` 的**请求体**（1178 字节，`page.on("request")` 拿的），
  里面**没有** `injected-context`、没有 `<job-ended`、没有 `<instructions path=`——而客户端自己那段
  历史（前几轮的用户消息与助手文本）在。**卡片是视图，不是消息**，这一条是在 wire 上量的。

截图（`evidence/`）：

| 文件 | 看什么 |
|---|---|
| `t01-01-no-card-unbound.png` | 未绑定的那一轮：一张卡也没有 |
| `t02-01-instructions-card-in-the-conversation.png` | 绑定之后：`instructions` 那张卡在助手文本之前 |
| `t03-01-job-ended-card-open.png` | 作业那一轮：`job-ended` 那张卡，点开是 `[exit 0]`；位置在工具卡之后、收尾之前 |
| `t04-01-cards-back-after-refresh.png` | 刷新之后同一批卡还在 |
| `t05-01-next-request-is-clean.png` | 再问一句之后的样子（那一刻的请求体已在上面的检查里量过） |

## 撞上的两件事（脚本里都写着原因）

1. **一轮跑完的消息是折着的。** 卡的触发器在 DOM 里但 `display:none`——第一次跑这个脚本时
   `waitForSelector` 一直等一个「可见」的元素，量到的是「页面里没有卡」。所以脚本先点开
   每一行还折着的步骤（`[data-slot="turn-steps-trigger"][aria-expanded="false"]`），这正是人要做的动作。
2. **不能拿「记录已经 settled」当「这一轮跑完了」。** 第一次跑时脚本拿 `sofar` 的状态当信号，
   结果在跑着的那一轮里就按下了下一次发送（composer 在流式期间是可打的，回车不生效），
   于是那一轮的开场块赶在记录写系统消息之前被绑定改了——证据就是那一场的 jsonl 里
   `project/bound` 落在 `input` 与 `message` 之间。现在等的是 composer 的 Cancel 出现再消失，
   并且要求 `POST /api/agent` 的次数真的涨了。
