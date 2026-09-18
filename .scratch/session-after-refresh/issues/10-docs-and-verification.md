# 10 — 收口：文档不许再描述旧行为，两套全量 + 走查证据

**What to build:** 一个**唯一的收口票**：把描述旧行为的文档、注释与那句话改掉，
跑完两套全量与后端全量，把走查证据收进 `evidence/`。

**Blocked by:** 02、03、04、05、06、07、08、09

**Status:** ready-for-agent

## 一、文档与注释：每一处都在说旧的事

| 地方 | 今天说的 | 改成 |
|---|---|---|
| `docs/architecture/home-and-storage.md`（「读一份断掉的记录」那一节） | 四个问题（有没有 terminal / last / crashed / shutdown） | **第五问**：它在本进程里活着吗、问谁（票 01 的登记表）；并说明「活着 ⇒ 不读终局、不收尾」 |
| `docs/architecture/client.md`（刷新与切换那一节） | 一个 agent、一份 runtime、刷新即新会话 | 刷新回到刚才那一场（localStorage）、没完的那一轮怎么画、在跑时 composer 的样子 |
| `src/harness/edge/http.clj`（`close-off-open-run!` 与 `rebuild-post` 的 docstring） | 「reading a truncated log still refuses」那一段 | 加上「**活着的** run 一个字都不动」这半边，并指向票 01 的登记表 |
| `src/harness/edge/http.clj`（`thread-verbs` 的 docstring） | 「ONE OF THE FOUR IS A GET」 | 集合与 GET 的数目按落地结果重数（票 02 的新读法、票 07 的 `cancel`） |
| `src/harness/edge/replay.clj`（`open-run` / `closing-frames`） | 只说文件里的 input/terminal | 说清它们问过登记表；「拒绝与修复不许漂移」那条继续成立 |
| `ui/src/lib/run-state.ts` | 整个模块的理由是「两句拒绝 + 探针」 | 判据加了服务端那一个事实（票 04）；**若理由变了，模块的名字与头注释一起改**，别留一个名字在说旧事 |
| `ui/src/app.tsx:95-97` | 「rebuild 会读回 `metadata.custom.agui.interrupts`」 | 票 06 已改；本票扫尾确认这句**不成立的话**没有留下 |

**判据是机读的**：

```
grep -rn "reads back" ui/src/app.tsx | grep interrupts        # 无输出
grep -rn "ONE OF THE FOUR IS A GET" src/                      # 无输出
```

## 二、`evidence/` 里要有什么

走查证据（照 `composer-status` 那一份的形式：临时 `CLJ_HARNESS_HOME`、`harness.e2e-server`、vite dev）：

- `refresh-keeps-session.md`（票 03）：正常一轮刷新、跑着刷新、关掉重开、id 已不存在
- `unfinished-turn.md`（票 03 + 04）：跑着刷新后那一轮的画法、composer 的状态、跑完能接着发
- `parked-after-refresh.md`（票 06）：刷新后卡片在、决定能提交、与 04 一起验的「悬置不发」
- `stop-after-refresh.md`（票 09）：刷新后按停真的停、没起新 run、只停当前这一场

## 验收

- [ ] `cd ui && npm run build` 过
- [ ] `cd ui && npm test` 全绿（agent 层：真 `HttpAgent` + 真后端 + 脚本厂商）
- [ ] `timeout 900 clojure -M:test -m harness.test-runner`：失败**用例名**与基线一致，
      本特征的每一条新用例名都出现在输出里
- [ ] `grep` 那两条机读判据无输出
- [ ] `.scratch/session-after-refresh/spec.md` 的「票」一节按实际落地的结果收口
      （哪一票被谁改写了、哪一条决定翻了，按仓规写**复议**段而不是改掉旧话）
