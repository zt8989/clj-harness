# 走查记录：subagent-view（2026-09-22）

真浏览器（Chrome，playwright）。两条脚本，都在隔离的临时家里跑：

```bash
node scripts/dev.mjs --scripted .scratch/subagent-view/walkthrough-script.json      --ui-port 5311
node scripts/dev.mjs --scripted .scratch/subagent-view/walkthrough-live-script.json --ui-port 5311
```

第一个脚本两次委派（`explore`、`general`），用来验「两张卡都是门 / 换人 / 关掉复原」；
第二个脚本让子agent 先 `bash "sleep 10"`，用来验**边跑边长**和**关掉时挂断**——
这两条是离线套件够不到的（`.scratch/subagent-view/spec.md` 里写明「只有真浏览器说得清」）。

截图在本目录；下面每一条都是当场量出来的数，不是「看着对」。

## 04 正文里那次调用是门

- 卡片读作 `agent · explore · 列出 src/harness/cap 下面有哪些文件，一句话说清这个目录是干什么的。`
  —— 名字在前、任务在后，任务截成一行（`v04-01-...png`）。
- **两次委派 = 两张门**，各配各的子会话：`explore · 列出 src/...` 与
  `general · 把刚才那句结论压成不超过二十个字的版本。` 两个 `toolCallId`（`c1`/`c2`）
  各查各的行 —— 按位置配（「这次会话里第 N 次委派」）在两张卡上会错。
- **门的出现追上了那个竞态**：卡片在调用开始流出时就画出来了，而 `delegation` 行是内核执行
  这次调用时才写的。实测门在**发消息后 867ms** 出现（第一次读是空，重试那几次里拿到）；
  修这个竞态之前的表现是：一次 200、空列表、门永远不出现。

## 05 右栏

| 量的是什么 | 结果 |
|---|---|
| 并排 | 主栏 `896px`，右栏 `416px`（`w-[26rem]`），同一行、都是整高 |
| 输入框 | 右栏里 `textbox = 0`、`form = 0`、发送/停止按钮 `= 0` —— 不是禁用态，是没有 |
| 主对话还在 | 主栏里的任务原文可见，主 composer 还在（`textbox = 1`） |
| 换人 | 点第二张门：`panels = 1`（换掉不是叠加），标题从 `子agent · explore` 变成 `子agent · general`，内容是**另一个**子会话 |
| 关掉 | `panels = 0`，主栏从 `896px` 长回 `1312px`（正好收回 416），两张门都还在 |
| 一次请求/一父会话 | 重开一个已有两次委派的会话：**两次委派一共 1 次** `/delegations`（第二张卡要的 id 已经在答案里）；刚发出去那一轮是 4 次（两张卡 × 各一次立刻读 + 一次重试就命中） |

### 边跑边长（`v05-05-mirror-grown-live.png`）

子agent 在 `bash "sleep 10"` 里的时候点开门，右栏当场是这样的，**每 1.5 秒采一次都没变**：

```
子agent · general | 先跑 sleep 10，然后一句话说清 … | 思考 | （子agent：先跑那条命令。） |
bash · sleep 10 && echo done | 运行中 | ●            ← 转圈的那个 ●
```

`t+10.5s` 那一次采样（**没人碰页面**）变成了：

```
子agent · general | … | 1 次工具调用 · 2 条消息 | （子agent：cap 是能力目录，一个文件装一种工具。）
```

也就是：转圈没了、在跑的那张工具卡收成了折叠组、子agent 的结论自己长出来。这一条是
跟随通道（票 02）在浏览器里的那一半 —— 补发的帧、总线上的帧、终帧关流，串起来就是它。

### 关掉 = 挂断

子agent 还在 `sleep` 的时候关右栏：`requestfailed` 收到
`net::ERR_ABORTED` on `/threads/<child>/follow`，右栏消失，而**主对话里那次 `agent` 调用还在转**
（子agent 属于父会话那次调用，关掉镜子不停它）。开发构建里 React StrictMode 双挂载会开两条
连接，两条都被 `abortRun()` 收掉 —— 生产构建只有一条。

## 这一遍改掉的两件事（都是浏览器才看得见的）

1. **跟随通道的第一帧必须是 `RUN_STARTED`**。原来先发 `MESSAGES_SNAPSHOT` 再发帧，
   `@ag-ui/client` 当场拒了：`First event must be 'RUN_STARTED'`，面板上一行红字、一条消息都不画。
   现在按记录的顺序先发那条 `RUN_STARTED`（真实记录的第一帧就是它），快照跟在它后面。
2. **`/delegations` 的答案是个信封**（`{:threadId .. :delegations [..]}`），不是裸数组；
   当成数组读会在 `.map` 上抛、被 catch 吞掉 —— 网络上 200，卡片上就是没有门。
   顺带把重试做成**每次调用幂等**：`subscribe` 每次渲染都会跑，没有护栏时两次委派发了 17 次请求
   （现在实测 4 次，重开会话 1 次）。

### 窄窗口（票 05 要求走查回答的那一条）

面板是 `hidden md:flex`：**768px 以下不画**，主对话拿整幅宽度。768px 以上量了一遍 ——

| 窗口 | 1600 | 1400 | 1200 | 1024 | 900 | 800 | 767 |
|---|---|---|---|---|---|---|---|
| 右栏 | 416 | 416 | 416 | 416 | 416 | 416 | **0（不画）** |
| 主栏 | 896 | 696 | 496 | **320** | 484 | 384 | 767 |
| 横向滚动 | 无 | 无 | 无 | 无 | 无 | 无 | 无 |

最窄是 1024px 时的 320px（那时左侧栏还是占位的 288px 一列；900px 以下它改成浮层，所以 900px 反而
比 1024px 宽）。**没有**给主栏加 `min-w-*`：右栏固定宽 + `shrink-0`，给主栏设下限换来的是整页横向
滚动，而不是「不被压扁」——下限由「768px 以下不画面板」守。截图 `v05-06-...png` 是 767px 那一格。

## 离线套件的判据（`scripts/test.mjs` 已经删了）

spec 的验收写的是「离线 `node scripts/test.mjs` 的失败名单与基线一致」，但那个 runner 已经被删，
现在是原生命令（`AGENTS.md` 的「单元测试（原生命令）」）。所以这一条的诚实读法是：

- 后端 `clojure -M:test -m harness.test-runner`：**1134 tests / 13098 assertions，0 failures 0 errors**；
- 前端 `cd ui && npm test`（106 条）、`npm run typecheck`、`npm run build` 全绿；
- 老那份 `.scratch/subagents/evidence/baseline.names` 里的 89 行是**那个 runner 在老机器上的**
  `ERROR ... @ FileInputStream.java:-2` 一类环境性失败（和 `.scratch/subagents` 那次的走查笔记对得上）。
  抽查它点名的命名空间（`cap.jobs-test`、`kernel.hooks-wired-test`、`cap.hashline.write-test`、
  `layers-test`）：70 tests / 475 assertions，**0 failures 0 errors** —— 基线那些 ERROR 现在不成立，
  这份名单是那份 runner 的遗物，不是当前的红。

## 这一遍**没有**证明的

- 断线重连：跟随通道断了再连是一次新的补发，这一版不做「不丢帧」的强保证（spec 的非目标）。
  走查里没造断线。
- 子agent 的帧**从进程外**看到：跟随通道是进程内总线，进程退出后只剩记录（`rebuild` 那条路），
  这条按设计如此，没在浏览器里演。
- **右栏里被卡住的审批**：子agent 的工具若需要人工批准，它自己那次 run 会停在审批上，而镜子里的
  那张审批卡没有通道可以把答案递回去（面板没有页面那套审批接线）。票面第 20 行说的「没有禁用态」是
  composer 那一半，这一格没被演过，也没被挡住 —— 记在这里，别当成已经证明。
- **水合走的是补发 + 快照，不是 `rebuildThread`**（票 05 第 12-14 行）。两条路通向同一份记录，
  但叠在一起会把子agent 的答案画两遍，所以只留跟随那条；「跑完仍然读得到」由补发的那份重放保证
  （实测：跑完的那次委派打开右栏，内容齐全、没有红字）。