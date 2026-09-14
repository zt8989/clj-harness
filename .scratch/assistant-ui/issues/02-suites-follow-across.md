# 02 — 四组用例跟着搬到 TypeScript，CLJS 工具链整体离场

**What to build:** 验收侧那六份 CLJS 搬成 TypeScript 加 vitest，然后 shadow-cljs 从仓库里彻底消失。
六份里四份是用例（frames / client / turn / approval），另外两份是支撑：一个起脚本后端、跑一轮、收事件的
`e2e` 辅助命名空间，和一个让 vitest 能驱动 cljs.test 的桥。桥随语言一起拆——它存在的唯一理由就是让
两个语言对接，没有 CLJS 就没有它。

搬测试不是为了形式统一，而是因为**这套用例从 03 起是唯一的回归网**。它们驱动的是真 `@ag-ui/client`，
不经过任何 UI 库，所以换库期间它们必须活着；而它们必须换成好读好改的语言，因为 03 之后每一次界面改动
都要靠它们证明协议那一层没被碰坏——用 CLJS 写的东西没人愿意在那时候改。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 四组用例（frames / client / turn / approval）搬成 TS，用例数与断言逐条对位，不增不减、不改判据
- [ ] `e2e` 辅助命名空间（起脚本后端、驱动一轮、收事件）在 TS 里重建，四组共用同一份，不许各写一份
- [ ] `test/support/harness.js` 那套「端口由 OS 分配 + 假 provider + 不要 api-key」仍然生效，
      `npm test` 一条命令跑完，不再需要预编译步骤（`test:build` 退场）
- [ ] shadow-cljs 整条链子拆掉：`shadow-cljs.edn`、`vite-plugin-cljs.js`、`cljs-test/` 桥、
      `test/support/build.js`、`test/support/java.js`、`test/cljs.test.js`，以及依赖里的 `shadow-cljs`
- [ ] `ui/` 下按源码搜不到 `shadow-cljs`、`cljs`、`helix`（gitignore 与历史文档里的说法不算）
- [ ] `npm test` 全绿：用例数与断言数与今天一致，审批那条链路仍端到端通过
- [ ] `npm run build` 全绿，且**构建链上不再有 Java**：找 JDK 21 的那段代码随 `java.js` 一起消失
- [ ] 落地说明记下「零用例」这个失败模式是怎么防的——今天的桥专门挡过一次（`deftest` 被编译器丢掉会
      静默变成 0 个用例、跑出一片绿），新套件要么继承这道挡板，要么说明为什么不需要
