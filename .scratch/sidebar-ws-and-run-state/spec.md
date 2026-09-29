# 侧栏:首次拉取 + 运行状态入库(sidebar-ws-and-run-state)

**状态:** 三张票全部落地(2026-09-27)。票文件按仓库约定删除,记录在这里和 git 历史里。

## 起因

主人一句话两条要求:

> 将左侧的 Project 改为第一次拉,然后后续通过 WebSocket 方式更新。然后 SQLite 要存那个运行的状态。

## 一、运行状态落 SQLite(票 01)

### 决定

**`sessions.run_state TEXT`,语义是「上次已知」而不是「现在」。**

- 值的词汇是 `running` / `idle`,不是 0/1:第三种状态(如 `interrupted`)是加一个词,不是加一个 bit。
- 迁移**不回填**:列到达为 NULL,读作 `idle`。回填会撒谎——一个早于该列存在的家对任何 run 一无所知,
  而「日志没有 terminal 帧 ⇒ 还在跑」正是 `running?` 存在的意义所拒绝的那种猜测。
- **进程启动清理**:`harness.cap.project/clear-startup-run-state!` 把所有 `running` 清回 `idle`,
  在 `harness.edge.http/start!` 里、socket 打开之前调用一次。理由不是试探而是事实:一个还没起过任何
  run 的进程里没有 run 活着,所以它看到的每个 `running` 都是死进程留下的。
- **写入点**:`harness.edge.sessions/run-started!` / `run-finished!` 这两个适配器动词——kernel 注册表
  仍是「本进程实时」的权威,store 列由这两个动词在同一时刻写,并**各自 ring 一次 host 流**。
- **读取点**:`session-row` 读列(`(= "running" run-state)`),不再每行问注册表。所以
  「左侧面板所有信息都来自 store」这句话现在**没有例外**。

### 为什么不是注册表的镜子

注册表随进程死亡而消失。跨重启能活下来的答案是这一列,而 sidebars 画的是客户端视图,必须在重启后
仍然说得出一行的话。二者在进程内保持一致(同一时刻写),跨进程由启动清理兜底。

## 二、首次 HTTP、后续 WebSocket(票 02)

### 决定

- **挂载一次 `GET /api/projects`**,之后所有 host 级变化走 `events.host` 推送(ADR 0004)。
- **手动刷新键保留**,但降级为「socket 连不上」时的兜底,注释里写明它不是常规路径。
- **切换会话不再重取**:当年重取的理由是「重建日志会追加 audit line,令 mtime 失真」,而 listing 早已
  不读 mtime/体积,理由随那些字段一起退场。
- **归档 / 删项目 / 加项目 / 绑定之后的 `await refresh()` 全部删除**:这几条路由本来就走 `rung`
  (`host/ring!`),推送会把新 listing 送回本页,自己再拉一次是重复劳动。
- **ask-again(`lib/sidebar-refetch.ts`)的第三条理由删除**:它过去会为「listing 说 running、而本页
  没在跑它」的行再问一次库。那在**轮询**模式下成立,在**推送**模式下是错的——`events.host` 现在承载
  run 的开始与结束,`running` 还是 true 就真的是在跑,而说它停了的帧就是更新的来源。留着这条规则,
  等于每个窗口都为别人的每一次 run 白拉一次 listing(走查抓到过)。
  剩下的两条理由都关于**本页自己的写**:行还没出现、行出现但还没记录发送时间。

### 边界

- ~~composer 的**目录选择器**有它自己的一份 listing 读~~ ——**2026-09-29 已拿下**(后续那一票就是它):
  选择器现在从页面手里那份 listing 取 projects(`composer-chrome.tsx` 的 `SidebarProjectsContext`,
  由 `app.tsx` 的 `onListed` 填),因此一次页面加载**只有侧栏那一发** `GET /api/projects`。

## 三、验证

- 后端全量:`clj -M:test -m harness.test-runner` — 1321 tests / 13984 assertions,0 失败。
- 前端:`npm run typecheck` / `npm test`(159)/ `npm run build` 全过。
- 浏览器走查:`walkthrough.mjs`(本目录,配 `script.json` 的慢脚本)全部绿:
  两个窗口、B 在 run 期间看到「运行中」、run 结束时**不重新拉取**就把行放回 idle、第二次发送由
  socket 帧送达、刷新键仍恰好读一次。
- 已知 flake:`harness.edge.http-test/an-overflow-refusal-compacts-aggressively-and-retries-in-one-turn`
  在 58a1ac9 基线上同样偶发失败,与本改动无关。

## 四、证据

`evidence/`:b-during-run.png(run 进行中,B 的行带 spinner)、b-after-run.png(推送把行放回 idle)、
b-pushed.png(早期一次)。

## 五、追记 2026-09-29:listed 那一半候选是错的(主人:「还是一直在调用」)

票 02 保留了 ask-again 的两条理由,但**实现**把候选算成了「本页有标题的 id ∪ listing 里的每一个 id」
(`lib/sidebar-refetch.ts` 的 `nextAsk`)。第二条理由本来只关于**本页自己的写**(行出现了、发送时间还没落),
而 listing 里的**别人的行**也被算了进去:一条 `last_sent_at` 还是 NULL 的行,对这条规则就是「该再问一次」,
问的是本页根本没有在等的事。

**为什么这就成了轮询**:每次问都会落一份新 listing,`listedRows` 是一份新 Map,effect 于是重跑,再挑下一个该问的
id —— 自持的一条链,直到每个 id 花完 5 次预算。主人家里 **243 行有 126 行 `last_sent_at` 是 NULL**
(比这一列更早的行、fork、子代理、没人发过的会话),于是一次页面加载就是几百次 `GET /api/projects`;
临时家里 7 条这样的行,**实测 36 次 / 11 秒,每 400ms 一次**。

**改法**:候选只留 `titles`(本页铸的、有标题的那些)。已经出现的行也在里面 —— 库里还没有可读的 title 时
`app.tsx` 的 `forgetListedTitles` 不会把 id 摘掉 —— 所以「人在等的那一行」照旧会被追问,别的行交给推送。
同时把侧栏挂载读修成**真的只读一次**:`onListed` 的身份会动(restore 结束、历史加载失败),原来它一变
`refresh` 就变、挂载 effect 就重跑(每次加载多一发 `GET`,还会把 host socket 关了重开);现在页面那个回调走 ref。

**验证**:`npm run typecheck` / `npm test`(183)/ `npm run build` 全绿;真浏览器(还是这台 scripted 服务):
加载后**一次页面只有一发** `GET /api/projects`(侧栏那一发;目录选择器那一份同一天也拿下了,见上面「边界」)、
20 秒内不再有;发一句新会话照旧出现且带时间(3 秒内一次追问);两窗复验票 02 的契约:run 期间 B 显示「运行中」,
run 结束时行变 idle 而
**B 的读次数一动不动**。
