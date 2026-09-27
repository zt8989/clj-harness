# 会话数字入库:首次一次 SELECT,之后推送增量

**状态:** 票 01 落地(2026-09-27)。票文件按仓库约定删除,记录在这里和 git 历史里。

## 起因

> 把现在会话里面的上下文、轮次、步数还有什么缓存啊什么的……也是默认第一次从 sqlite 拉,
> 然后再通过 WebSocket 推增量推。

对应到代码里就是 composer 上面那条统计条那一包数字(`ui/src/lib/format.ts` 的 `StatsPayload`):

| 说法 | 字段 | 画成 |
|---|---|---|
| 轮次 | `turns` | 「N 轮」 |
| 步数 | `steps` | 「N 次调用」(模型调用) |
| 缓存 | `cacheHitPercent` | 「N% 缓存」(厂商 prompt cache 命中率) |
| 上下文 | `context` | 环:`usedTokens`/`windowTokens`/`percent` + system/tools/conversation 三段 |

## 改前的样子

- **首次读**:`GET /api/threads/<stem>/stats`,服务端**折一遍那份 jsonl**(`harness.edge.stats` +
  `harness.edge.context`;进程内持有该会话时读它的活折,否则流式走一遍文件)。
- **增量**:`model/end` 帧的 `:numbers`(`harness.edge.http/live-numbers-slice`),客户端
  `withPushedNumbers` 盖在快照上——这条通道本来就有,本期不动。
- run 结束后客户端**再问一次**(400ms),因为记录的写入器比最后一帧慢一拍。

## 决定

1. **`sessions.numbers`,一列 JSON**(形状照 `todos.items`):统计条要画的那一包,加 `:numbersAt`
   (写入时刻)。**一列而不是每格一列**,因为格子会长(缓存、速率、上下文三段是不同时期加的),
   这一列整写整读、从不按元素查;每格一列意味着每加一格一次迁移。
2. **写点＝已有的两个折推进时刻**,不新开定时器:
   - `model/end`(`harness.edge.http`,推送那一个 `:numbers` 的**同一时刻**,推送先走、写库随后);
   - `:run/done`(返回尾巴落盘之后、一轮的计数定稿那里)。
   **不写在终局帧上**,因为上下文的三段占比是从返回尾巴数出来的——写早了就是旧的「run 结束后
   再问一次」要修的那个状态。
3. **读侧三个来源按序**:进程内持有 ⇒ 活折(不开文件);否则 ⇒ **store 行(一次 SELECT)**;
   都没有 ⇒ 折记录(**修复路径**)。`?fold=1` 是「我不信这行,给我记录自己的答案」的门。
   **GET 从不写**(比这一列老的规矩,`docs/rules/panel-data.md`)。
4. **快照是「上次已知」**:答案带 `:numbersAt`;`:incomplete` 与 `:pressure` **不进快照**——
   它们是「这次读」的发现(最后一帧是不是终局、下一次请求的压力估计),存下来就是对后一个读者的谎。
   客户端类型里 `incomplete` 因此从必填改为可选(今天没有组件读统计条的这一个键)。
5. **不迁就旧库**:列到达为空、不回填——这一列的内容本来就是「某个时刻的一次声称」,
   一个早于它的家没有那个时刻可谈(同 `run_state` 的写法)。

## 已知代价(写在 `harness.infra.db` 的迁移 docstring 里)

- 手改过的记录、或者一次 rebuild,可以让这一列与折不一致。所以它是「上次已知」,
  并带着写入时刻;修法是显式 `?fold=1`,或下一次 `model/end`/`:run/done` 的写。
- `model/end` 那一份快照**按设计**没有 `:parts`(这时尾巴还没落盘),`:run/done` 那一份才有。

## 验证

- 后端全量:`clj -M:test -m harness.test-runner` — **1325 tests / 14005 assertions,0 失败**。
  新增:`db_test`(列到达为空、`numbers-for` 答 nil)、`project_test`(整值往返、未知会话不写)、
  `stats_test`(没有日志的会话也能从行里答出来 ⇒ 证明没折日志;`?fold=1` 走记录门;
  run 之后**行与折逐键一致**)。
- 前端:`npm run typecheck` / `npm test`(159)/ `npm run build` 全过。
- 浏览器走查(`node scripts/dev.mjs --scripted`):发一条 → 统计条画「1 轮 · 2 次调用」;
  刷新后仍画出来,且 `/stats` 只是那一次读。
- 重启验收(两个进程、同一份临时 home):进程 A 写入后退出;进程 B 起来读,答的是
  `{"turns":4,"steps":6,"cacheHitPercent":77,"numbersAt":…}`(`:numbersAt` 在 ⇒ 走的是库那扇门),
  `?fold=1` 在没有记录时答那扇门自己的拒绝。
- 已知 flake(与本次无关,基线同样偶发):`http_test` 的
  `a-running-session-reads-what-has-arrived-and-nothing-is-written` 与
  `an-overflow-refusal-compacts-aggressively-and-retries-in-one-turn`——重跑即绿。

## 追加:一个被这次走查撞出来的旧 bug(2026-09-27,`c39a12c`)

**症状**:初次发送时,底部统计条只有一串原始 key(`stats.turns`),轮次/步数/缓存都不初始化。

**根因**(两个,叠在一起):

1. **`live-numbers-slice` 从引入那天起就读错了形状**:它读 `(:stats n)`,而 `live-numbers`
   返回的是**扁平**的(`stats-answer` 再 assoc `:context`/`:pressure`)。于是每个 `contains?`
   都是假,切片恒为 `{}`——`model/end` **一直在推空对象**。有快照在手时看不出来(合并 `{}`
   等于没合并);只有「只被推送过」的页面才暴露。
2. **客户端把「只有推送」的载荷当整包用**:`withPushedNumbers(null, {})` 返回
   `{} as StatsPayload`,`statsCells` 于是问目录要一个**没有 count 的复数**
   (`t("stats.turns", {count: undefined})`)——i18next 对缺 count 不做复数解析,直接**返回键名**,
   屏幕上就是 `stats.turns`。

**修法**:

- 服务端:按 `live-numbers` 自己的形状读它(并补上 `:turns`——刚开的会话在首次 `model/end` 时
  还没有快照,而这一次 run 的轮已经开了,折里有这个数);
- 客户端:`StatsPayload.turns` 改为**可选**,`statsCells` 在没有 count 时给 `null`
  (那一格连同秒表一起缺席,而不是画出键名),`withPushedNumbers` 的契约写清「只有推送的载荷就是
  它本来的样子,缺的格不补」;
- 测试:后端钉住切片内容(`:turns` 与 `:steps`);客户端新增一例——「只有推送的载荷」必须给
  `turns: null` 且**不含** `stats.turns` 字样。

**为什么它在 main 上**:**不是这次改动引入的**——`bd5c314`(引入 `live-numbers-slice` 的那次合并)
就是这样,所以 main 上同样有这个 bug;本分支的 `c39a12c` 修掉了它。

**验证**:浏览器首屏从空直接变「1 轮 · 1 次调用」,全程无原始 key;调用数随后自己长到 2(推送);
后端全量 1326 tests / 0 失败;前端 160 tests + typecheck 过。
