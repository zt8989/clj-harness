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

- composer 的**目录选择器**有它自己的一份 listing 读(`composer-chrome.tsx` 的 `useRemote(listSidebar)`),
  切到一个新会话会挂载一个新 composer,于是多一次读。这是另一个组件的问题,不在本票里,走查脚本
  明确写出来而不是假装不存在。它若也要推送化,是后续一票。

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
