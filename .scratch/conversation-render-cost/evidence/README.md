# 票 01 的证据：流式那条回答的重建频率，与读写两次读数

这一票（`.scratch/conversation-render-cost/issues/01-*`）要回答两件事：**defer 到底有没有生效**，
以及**把答案重建的频率压下来能省多少**。两件都是在真浏览器里量的，仪器与 `spec.md` 那套一致
（CDP `Performance.getMetrics` 的差值 + 从 socket 数帧），浏览器也是同一个版本（Chromium 153，
即当初那台 Chrome 153）。

**但绝对值不要横着比**：这一轮的 `ScriptDuration` 比 `spec.md` 那张表高 3–8 倍（无头 Chromium、
视口大小、当时机器上还有什么在跑，都不一样）。可比的是**同一轮里的改前与改后**——两次背靠背、
同一台机器、同一个浏览器、同一支脚本。

## 怎么复现

```bash
# 1. 造两条脚本（一场 18k、一场 36k 的长回答，5 字一块 / 8ms 一块）
node .scratch/conversation-render-cost/evidence/gen-script.mjs

# 2. 起一个走查用的服务（隔离家、OS 分配端口，页面由后端从 ui/dist 发出）
node scripts/dev.mjs --scripted .scratch/conversation-render-cost/evidence/script-18k.json --port 4811

# 3. 量。3 次取中位数，`--out` 把原始读数落盘
node .scratch/conversation-render-cost/evidence/measure-run.mjs \
  --url http://127.0.0.1:4811 --repeats 3 --out my-readings.json

# 4. 走查（另一个脚本，看的是「读起来是什么样」而不是「多少钱」）
node .scratch/conversation-render-cost/evidence/walkthrough.mjs \
  --url http://127.0.0.1:4811 --out walkthrough-after.json
```

`measure-run.mjs` 要一个 `playwright`，本仓库不装它：脚本按 `--playwright` → `PLAYWRIGHT_PATH`
→ `npx` 缓存的顺序找一个**浏览器真的下过**的那份（缓存里常有好几份，最新那份要的 Chromium 这台
机器没有），并用 `channel: "chromium"` 起**完整 Chromium**而不是默认的 `chrome-headless-shell`
——后者同样的流同样的脚本要贵 4 倍，读数与 `spec.md` 不可比（chromium-1243 就是 153.0.8010.12，
即当初那台 Chrome 153）。

## 读数（3 次的中位数，ms/秒）

| 场景 | 主线程 task | 其中 JS | 样式 | 布局 | 空闲 JS |
|---|---|---|---|---|---|
| 18k 回答，改前（`baseline-18k.json`） | 528.5 | **377.2** | 11.3 | 22.4 | 0.7 |
| 18k 回答，改后（`after-18k.json`） | 275.2 | **178.2** | 11.2 | 5.8 | 0.7 |
| 36k 回答，改前（`baseline-36k.json`） | 748.7 | **591.9** | 10.7 | 22.2 | 0.7 |
| 36k 回答，改后（`after-36k.json`） | 360.0 | **245.7** | 11.1 | 6.1 | 0.6 |

- 两次场景的帧数分别是 ~4,300 / ~8,250，`rafPerSecond` 都是 56——**页面在画**，所以
  `lib/coalesce.ts` 的「一动画帧一次交付」确实在生效，改前后比的是同一条路。
- JS 降到 **1/2.1**（18k）与 **1/2.4**（36k）；样式与布局没变贵（布局反而从 22 降到 6）。
- 内容翻倍（18,992 → 36,954 字）时，改前 377 → 592（×1.57），改后 178 → 246（×1.38）。

## 钱花在哪里：`profile-36k.json`

`measure-run.mjs --profile` 会开一次 V8 CPU profile，先按函数（self time）再按模块汇总；按模块
那半需要**不压缩的**构建（`cd ui && npx vite build --minify false`），因为 `//#region <path>`
注释才是把采样行号归到模块的依据。36k 那一场（改前）的结论是：

| 模块 | 占比 |
|---|---|
| `@assistant-ui/react-markdown`（含它的 micromark/react-markdown） | 30.0% |
| unist-util-visit-parents、micromark-extension-gfm-\*、mdast-util-find-and-replace、unist-util-is、hast-util-to-jsx-runtime | 9.8% |
| react-dom（+ react、scheduler） | 5.4% |
| `@assistant-ui/react`、`tap`、`store`、`core`、`react-ag-ui` | 8.1% |
| 我们自己的代码（`lib/mux.ts`、`lib/agent.ts`、`app.tsx`） | 0.8% |
| `(other)`（idle / program / GC / native） | 42.8% |

把 idle 那部分（约 37%）摘掉，**真正的工作里大约三分之二是 markdown 解析**，React 的
reconciliation 只有个位数百分比。这就是「不要每帧重建整条消息子树」这条路的价钱：子树本身不贵，
贵的是那条消息里那段越来越长的 markdown 被整篇重读。

## 走查（`.json` + `.png`）

`walkthrough.mjs` 在会话上挂一个 `MutationObserver`，把「屏幕上的字数 + 时刻」每一次变化都记下来
——那正是读者的眼睛拿到的那串更新。它自己的回调也要钱，所以它报的 `chatSeconds` **不能**横向比，
能比的是更新的节奏：

| | 改前（`walkthrough-before.json`） | 改后（`walkthrough-after.json`） |
|---|---|---|
| 更新次数 / 秒 | 4,536 次 / 52.2 次每秒 | 1,143 次 / 14.0 次每秒 |
| 相邻两次的间隔（中位） | 18.2 ms（≈ 一动画帧） | 71.8 ms（≈ `STREAM_COMMIT_MS` 那个区间） |
| 每次更新的字数（中位） | 8 | 33 |
| 倒着走的更新 | 1 | 1（改前也有，不是这次引入的） |
| 走完的答案 | 36,139 字 | 36,139 字 |
| 答案里的元素 | 37 个 `<p>`、91 个 `<strong>`、91 个 `<code>`、1 个 `.aui-md` | **一模一样** |

- `streaming-36k-before.png` / `streaming-36k-after.png` —— 同一场流进行到 4,000 字左右时各截一张，
  两侧是同一条回答、同一套排版；差别只有「多久重画一次」，不是「画成什么样」。
- 元素数量一致就是「样式与今天一致」这一格在机器上的那一半：markdown 照样是 markdown（段落、加粗、
  行内代码都在），没有被降级成一个纯文本节点。

## 机器门（票的验收那一条）

```bash
cd ui && npm run typecheck   # 绿
cd ui && npm run build       # 绿（tsc --noEmit + vite build）
cd ui && npm test            # 203 passed / 203（合进 main 之后；见下）
```

**这个数不是一开始就这样的**，写下来是因为它解释了一件事：量这些读数的时候，`main` 上本来就
有两条红，与这一票无关——

- `elicitation > a-servers-question-parks-the-run-and-the-answer-finishes-it`
  （`the run parked on one question: expected +0 to be 1`）
- `subagents > the-endpoint-answers-in-the-shape-both-screens-read`
  （`body.path?.endsWith("harness.edn")`）

——我按上面两条命令在**未改动的主检出**上单独跑过，同样红，所以它们不是这一票带进来的。收尾时
main 上已经有人把这两条修掉了（`Merge red-suite 进 main`），本票合进去之后整轮是 **203 / 203 绿**。

新增的 `markdown-commit` 用例读的是 `markdown-text.tsx?raw`：把 JSX 里那行
`smooth={MARKDOWN_SMOOTH}` 删掉，它会红（验证过）；只断言两个并排定义的常量则不会。

## 这一票没做的事

`spec.md` 那张票原来设想的是「把正在流的那条消息从消息列表里拎出来」。上面的 profile 说这条路的
收益在 markdown 解析，而不是消息子树的重建，所以改的是**重建的频率**（一次一行的 prop）而不是
消息的位置；两种做法的取舍写在 `spec.md` 的「票 01 落地」一节。
