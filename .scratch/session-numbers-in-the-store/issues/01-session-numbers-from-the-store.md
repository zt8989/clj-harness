# 01：会话数字入库——首次一次 SELECT，之后由推送增量走

**What to build:** composer 上下那条统计条今天画的东西——**轮次**（`turns`，「N 轮」）、**步数**
（`steps`，「N 次调用」）、**缓存**（`cacheHitPercent`，「N% 缓存」，厂商 prompt cache 的命中率）、
**上下文**（`context`：`usedTokens` / `windowTokens` / `percent` 与 system / tools / conversation
三段占比）——**第一次从 SQLite 读**（一次 SELECT，不折日志），之后每一个变化由服务端**推送增量**
（今天已经有那条通道：`model/end` 帧带着 `numbers`）。这与 `docs/rules/panel-data.md`
（**先拉一次存量，之后由推送走**；该文件随 `composer-todo-strip` 分支进 main）是同一条规矩，
本票补的是它的**存量那一半**：存量从「折一遍 jsonl」变成「读一行」。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

## 今天是什么样

- 首次读：`GET /api/threads/<stem>/stats`（`ui/src/lib/stats.ts` 的 `statsFor`），服务端**折叠该会话的
  jsonl**（`harness.edge.stats` + `harness.edge.context`；进程内持有该会话时读它的活折，否则流式走一遍文件）。
- 增量：`model/end` 帧的 `:numbers` 切片（`harness.edge.http/live-numbers-slice`），客户端
  `withPushedNumbers` 盖在快照上。**这条通道已经有了，本票不动它。**
- 另外还有「run 结束之后再问一次」（`composer-numbers.tsx` 那个 400ms 的 `reload`），理由是记录写入器
  比最后一帧慢一拍。本票要让它也变成读一行。

## 要做的

1. **库里的 last-known 快照**：`sessions` 上加一列（形状照 `todos.items`：JSON 文本，整写整读，
   不按元素查），装统计条要画的那一包数字（`turns` / `steps` / `usage` / `cacheHitPercent` /
   `outputTokensPerSecond` / `context` / **写入时刻**）。迁移照 v 系数的老规矩（append-only、自带
   `:present?` 探针、docstring 讲清为什么这是 state 而不是 record）。
2. **写点＝已有的那两个折推进的时刻**，不新开定时器：
   - `model/end`（一次模型调用结束，`harness.edge.stats` 与 `harness.edge.context` 的 step 都在这里跑，
     `live-numbers-slice` 也在这里取）——把当时那一包写进库；
   - **一轮 run 的结束**（`turns` 是在开 run 的地方计的，模型调用那一步不会让它动）；
   - 压缩 / 剪枝改动了上下文占比时，同一次写里更新（这几个折的 step 本来就在那些边界上）。
3. **读侧**：`stats-get` 先读这一行（一次 SELECT）。行在 ⇒ 直接答，并**带出写入时刻**；行不在 ⇒ 照旧的
   折日志那条路（还没跑过的会话仍然是 404，那半不变）。**折日志不删**——它是修复路径，也是一个
   测试可以直接对照的「第二种读法」。
4. **陈旧与修复写进 docstring，不能只写在注释里**：这一列是**日志折叠的缓存**，所以它**会**与日志不一致
   （手改日志、一次重建把折改掉、压缩前后）。规矩照 `run_state` 那次的写法：列是「上次已知」，谁写它谁
   负责让它变新，读者要知道它有多旧（写入时刻）——并且要有一个显式的修复入口（例如 `?fold=1`，或
   行不在时的自动折），不许让一个陈旧的数永远站在屏幕上。
5. **不装进这一包的东西，也要写下来**：`:incomplete` / `:record` / `:behind` 是「这次读日志时的发现」，
   不是折出来的数字，留在读侧；`harness.edge.compaction` / `prune` 那两张卡今天靠重建帧回来，
   已按推送规矩走，本票不动（若也要落库，另开一票）。

## 要拍板的三点（实现者/评审决定，票不预设）

1. **一列 JSON 还是按字段开列**：字段会随统计条长大（缓存、耗时、上下文三段都可能加），一列 JSON 改动最小；
   按列开的好处是能用 SQL 看/查，代价是每次加一格就一次迁移。
2. **信任边界**：手改过的日志、一次 rebuild 之后，行是「旧但仍是最新一次写」还是被判为不可信；
   不可信时是自动折一次并改写，还是只在显式请求时折。
3. **进程死了/重启之后**：这一行**不**像 `run_state` 那样清空（它描述日志，不是描述进程），
   但要确认「重启后第一读」用的是它而不是折一遍——那正是本票要省的那一遍。

## 验收

- [ ] 会话跑起来之后刷新页面：统计条的首次读是**一次 SELECT**（网络面板确认只有那一个 GET，
      服务端不再为它 walk 日志——日志折的那条路只在行缺失/显式要求时走）。
- [ ] 一次 run 期间数字随 `model/end` **增量**更新；run 结束时不再需要「结束后再问一次」——那一问
      要么读行、要么被推送替代。
- [ ] 关掉服务再起来（同一份 home）：数字仍在（来自库），并且**写入时刻**可见/可判断新旧。
- [ ] 手改日志或跑一次 rebuild 之后：陈旧能被发现，修复路径（自动或显式）把行带回与折一致；
      有测试用「折一遍」的结果对照库里的值。
- [ ] 没跑过的会话：仍然是 404 那套（没有行、没有日志），统计条画空而不是画零。
- [ ] 迁移测试照 `harness.infra.db-test` 的形状：老库里该列不存在 → 走上来是空的/安全的默认，
      且新写点写进去的值能被外连接读到。
