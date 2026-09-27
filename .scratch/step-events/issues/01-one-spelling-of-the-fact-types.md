# 01 — 事实族的类型表只有一处拼写

**What to build:** 加第三族之前，先把「哪些帧名是事实」收敛成一处，让服务端与客户端**对不上就红**。

今天这句话至少有四处各写了一遍：`harness.edge.http` 里广播那一处的 `#{"model/start" "model/end"}`、
`test/harness/test_support.clj` 里那份集合、`ui/src/lib/mux.ts` 的 `FACT_TYPES`、
`ui/test/e2e.ts` 里又抄一份同样的 `FACT_TYPES`。一族两个名字时抄四遍还能忍，三族六个名字就是
「加了一族、漏改了某一处」的现成剧本——而漏在客户端那一处的后果是**这一轮被打死**
（事实落进 `run` 那一支，会被 `@ag-ui/client` 按 AG-UI 的 schema 校验）。

从用户视角：加一个新的事实帧名，只需要在一处写它，别的地方要么引用、要么有一条用例当场报出来。

**Blocked by:** 无（可立即开始）

**Status:** ready-for-agent

## 验收

- [ ] **服务端一处**：广播那一处与 `test_support` 读的是**同一个 var**（照 `harness.edge.mux`
      自己的 `fact-buffer-size` 先例，参数/清单与用途写在一起），不是两份看起来一样的集合字面量。
- [ ] **客户端一处**：`ui/test/e2e.ts` 不再自己拼一份事实名集合，改为引用 `lib/mux.ts` 导出的那一份
      （`familyOf` 已经是那个判据的门口，e2e 要用它问，不要另抄）。
- [ ] **对不上就红**：有一条用例让服务端那份清单与客户端那份清单**同一次比较**里出现——
      最直接的一版是走一趟真 run（或假 provider 的一轮），把服务端**实际发出去的**事实帧名收起来，
      断言每一个都 `familyOf(name) === "fact"`。今天这条用例只用 `model/*` 与 `turn/*` 就能立起来。
- [ ] 这条用例**先红一次**再修：先把 `FACT_TYPES` 里删掉一个真在用的名字，确认用例报的是
      「这个名字被当成 run 帧了」，不是别的什么。
- [ ] `clojure -M:test -m harness.test-runner` 与 `cd ui && npm test` 全绿（基线以落地当次为准，
      报数带上分支与提交）。

**为什么它是第一张：** 它不是交付功能，是「让下一刀好切」——02 加 `step/*` 时只会碰到一处地方。
